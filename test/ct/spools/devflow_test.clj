(ns ct.spools.devflow-test
  "Tests for the ct.spools.devflow lifecycle spool: stage workflows,
  decision-point checkpoints, revision loops, and the small operational
  loop layered over skein.spools.workflow runs."
  (:require [clojure.java.shell :as shell]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ct.spools.devflow :as devflow]
            [ct.spools.devflow.guidance :as guidance]
            [skein.api.current.alpha :as current]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.spool.alpha :as spool]
            [skein.spools.workflow :as workflow]
            [skein.api.weaver.alpha :as weaver]
            [skein.test.alpha :as t]))

(deftest tested-skein-checkout-contains-defer-return
  (let [root (str (t/spool-checkout-root "skein/api/spool/alpha.clj"))
        floor "70a3c50e27ca0190f363d80d0b0cac72948dbacb"
        head-result (shell/sh "git" "-C" root "rev-parse" "HEAD")
        head (str/trim (:out head-result))
        ancestry-result (shell/sh "git" "-C" root "merge-base" "--is-ancestor"
                                  floor head)]
    (is (zero? (:exit head-result))
        (str "cannot resolve tested Skein HEAD at " root ": "
             (str/trim (:err head-result))))
    (is (zero? (:exit ancestry-result))
        (str "tested Skein HEAD " head " must contain floor " floor
             "; git error: " (str/trim (:err ancestry-result))))))

(deftest devflow-supplies-no-module-entry-points
  ;; Devflow's contribution is the entries its `defworkflow` forms collect while
  ;; the namespace loads. A module may not both collect authoring forms and
  ;; supply `:contribute` (SPEC-004.C46), so there is no `spool` var at all.
  (is (nil? (resolve 'ct.spools.devflow/spool)))
  (is (nil? (resolve 'ct.spools.devflow/contribute))))

(def ^:private route-symbols
  "Every stage route, as the qualified symbol the registry stores, in an order
  each entry's references are already registered in.

  Production publishes the whole partition atomically from the entries
  `defworkflow` collects during source load, which a bare test world cannot
  perform. Direct registration is the documented REPL-layer equivalent, but it
  validates the live registry on every call, so a set that routes to itself has
  to arrive leaves-first."
  [[:abort 'ct.spools.devflow/abort]
   [:agent-review 'ct.spools.devflow/agent-review]
   [:run-afk-manual 'ct.spools.devflow/run-afk-manual]
   [:run-afk-delegated 'ct.spools.devflow/run-afk-delegated]
   [:run-afk-loop 'ct.spools.devflow/run-afk-loop]
   [:tasks 'ct.spools.devflow/tasks]
   [:direct-implementation 'ct.spools.devflow/direct-implementation]
   [:route-after-plan 'ct.spools.devflow/route-after-plan]
   [:spec-plan 'ct.spools.devflow/spec-plan]
   [:decompose 'ct.spools.devflow/decompose]
   [:land-proposal 'ct.spools.devflow/land-proposal]
   [:proposal 'ct.spools.devflow/proposal]
   [:intake 'ct.spools.devflow/intake]])

(deftest route-symbols-cover-every-published-stage
  ;; The fixture below registers from `route-symbols`; this keeps that list from
  ;; drifting away from what the namespace actually publishes. Because the
  ;; equality is closed-world, the frozen copy of this test in a previous
  ;; release turns `bin/compat-alarm` red whenever a later release accretes a
  ;; stage (v13 added :land-proposal and :decompose against v12's frozen list).
  ;; That single failure class is accretion under the shared-spools contract
  ;; rule, not a break: classify it at release time rather than weakening the
  ;; live drift check.
  (is (= (set (keys devflow/stage-workflows)) (set (map first route-symbols)))))

(deftest routed-stages-support-transfer-and-returning-composition
  ;; Checkpoint :next still requires :continue at Skein's authored root-transfer
  ;; boundary. The same stages advertise :call so a fixed call or runtime-selected
  ;; defer can execute them as returning procedures.
  (is (= #{:start} (:entrypoints devflow/intake)))
  (is (= #{:call} (:entrypoints devflow/agent-review)))
  (doseq [[stage definition] (dissoc devflow/stage-workflows :intake :agent-review)]
    (is (= #{:continue :call} (:entrypoints definition))
        (str stage " supports checkpoint routing and returning composition"))))

(defn- publish-devflow-routes!
  "Register devflow's stage routes in `rt`'s workflow registry."
  [_rt]
  (doseq [[name definition-sym] route-symbols]
    (workflow/register-workflow! name definition-sym)))

(defn- activate-workflow!
  "Activate the workflow spool module on `rt` from the loaded JVM image.

  The suite's own require of `skein.spools.workflow` guarantees the namespace
  is image-loaded, so `:load :image` skips the source sync a bare test world
  cannot perform and the coordinator resolves the entry points from that
  image's `spool` var. Throws with the refresh result unless the module applied."
  [rt]
  (let [result (runtime/module! rt :workflow
                                {:ns 'skein.spools.workflow
                                 :load :image})
        status (get-in result [:modules :workflow :status])]
    (when-not (contains? #{:applied :unchanged} status)
      (throw (ex-info "workflow module activation failed"
                      {:module/key :workflow :module/status status :result result})))))

(defn with-runtime
  "Run f in a disposable skein.test.alpha weaver world.

  The devflow assertions call the same Clojure APIs a trusted REPL/config would
  call, but the runtime lifecycle and isolation come from the public author test
  helper rather than repo-local fixtures. Activates the workflow module in the
  world's runtime first, as the consuming workspace's module declarations do at
  startup, so named `:next` routes resolve against the runtime-owned workflow
  registry."
  [f]
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (current/with-runtime (:runtime ctx)
      (activate-workflow! (:runtime ctx))
      (publish-devflow-routes! (:runtime ctx))
      (f (:runtime ctx) (:config-dir ctx)))))

(deftest explicit-runtime-facade-overrides-a-different-ambient-world
  (t/with-weaver-world [ambient {:storage :sqlite-memory}]
    (t/with-weaver-world [target {:storage :sqlite-memory}]
      (let [ambient-runtime (:runtime ambient)
            target-runtime (:runtime target)]
        (current/with-runtime target-runtime
          (activate-workflow! target-runtime)
          (publish-devflow-routes! target-runtime))
        (current/with-runtime ambient-runtime
          (devflow/start! target-runtime "target-run"
                          {:worktree-check :already-in-worktree-ok})
          (is (nil? (workflow/current-root "target-run")))
          (is (= "target-run"
                 (get-in (devflow/current-root target-runtime "target-run")
                         [:attributes :workflow/run-id]))))))))

(s/def ::repointed-params (s/keys :req-un [::devflow/feature]))

(workflow/defworkflow repointed-proposal
  "A test-only replacement proving named routes bind at transition time."
  {:entrypoints #{:continue :call}
   :param-spec ::repointed-params
   :defaults {}}
  (workflow/workflow
   (fn [{:keys [feature]}] (str "Repointed proposal: " feature))
   {:attributes {"devflow/stage" "proposal"
                 "devflow/feature" (fn [{:keys [feature]}] feature)}}
   (workflow/step :replacement "Use replacement proposal route" :self)))

(deftest devflow-routes-repoint-live-and-fail-loudly-once-removed
  ;; Re-pointing a route changes only the next named transition; poured
  ;; molecules stay as historical graph state.
  (with-runtime
    (fn [rt _]
      (workflow/start! "route-repoint" :intake {:feature "route-repoint"})
      (workflow/choose! "route-repoint" :already-in-worktree)
      (workflow/complete! "route-repoint")
      (let [intake-root (:id (workflow/current-root "route-repoint"))]
        (workflow/register-workflow! :proposal 'ct.spools.devflow-test/repointed-proposal)
        (is (= ["Use replacement proposal route"]
               (mapv :title (:ready (workflow/choose! "route-repoint" :proposal-ready))))
            "the current definition is resolved when the route is taken")
        (is (= "closed" (:state (weaver/show rt intake-root)))
            "the poured intake stage was not rewritten")
        (is (= "Repointed proposal: route-repoint"
               (:title (workflow/current-root "route-repoint")))))
      ;; Removing :proposal leaves the failed transition resumable with the
      ;; missing-name registry diagnostic.
      (workflow/start! "route-deletion" :intake {:feature "route-deletion"})
      (workflow/choose! "route-deletion" :already-in-worktree)
      (workflow/complete! "route-deletion")
      (workflow/unregister-workflow! :proposal)
      (let [checkpoint (:id (workflow/ready-step "route-deletion"))
            data (try
                   (workflow/choose! "route-deletion" :proposal-ready)
                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :proposal (:name data)))
        (is (not (contains? (set (:registered data)) :proposal)))
        (is (= checkpoint (:id (workflow/ready-step "route-deletion")))
            "removed-route failure leaves the active checkpoint resumable")))))

(deftest devflow-maven-dependency-is-observable
  (is (= "devflow-spool" (devflow/dependency-sentinel))))

(deftest devflow-proposal-revise-loops-back-through-the-proposal-stage
  (with-runtime
    (fn [rt _]
      (workflow/start! "prop-run"
                       ;; a mid-cycle stage is normally reached by routing, so
                       ;; start it by Var: only a registered-name start is held
                       ;; to the definition's declared entrypoints
                       #'devflow/proposal
                       {:feature "widgets"}
                       {:family "devflow"
                        :context {:feature "widgets"}})
      (is (= "devflow"
             (get-in (workflow/current-root "prop-run")
                     [:attributes :workflow/family])))
      (is (= "Inspect relevant RFCs, spikes, root specs, and active feature context for widgets"
             (:title (workflow/ready-step "prop-run"))))
      (is (= "Write devflow proposal for widgets" (:title (first (:ready (workflow/complete! "prop-run"))))))
      (is (= "Run agent review for widgets proposal" (:title (first (:ready (workflow/complete! "prop-run"))))))
      ;; completing the inner review step auto-closes the agent-review join, so
      ;; the sign-off checkpoint is next with no join step to complete
      (is (= "Human sign-off for widgets proposal" (:title (first (:ready (workflow/complete! "prop-run"))))))
      ;; revise routes back into a fresh proposal round that skips :inspect-context
      (let [remaining (:ready (workflow/choose! "prop-run" :revise))]
        (is (= [{:title "Write devflow proposal for widgets" :role "step"}]
               (mapv #(select-keys % [:title :role]) remaining)))
        (is (= "devflow"
               (get-in (workflow/current-root "prop-run")
                       [:attributes :workflow/family]))))
      (is (= "Run agent review for widgets proposal" (:title (first (:ready (workflow/complete! "prop-run"))))))
      (is (= "Human sign-off for widgets proposal" (:title (first (:ready (workflow/complete! "prop-run"))))))
      ;; :approved routes on to the spec/plan stage; the poured spec-plan root
      ;; presenting its entry step is enough to confirm the loop closed
      (is (= [{:title "Write needed spec deltas for widgets" :role "step"}]
             (mapv #(select-keys % [:title :role]) (:ready (workflow/choose! "prop-run" :approved)))))
      (let [root (workflow/current-root "prop-run")]
        (is (= "Devflow spec and plan: widgets" (:title root)))
        (is (= "devflow" (get-in root [:attributes :workflow/family])))
        ;; entering a fresh stage sheds the previous stage's loop state: the
        ;; revised round's :revision true does not ride forward, and the new
        ;; stage's own default takes its place
        (is (false? (get-in root [:attributes :workflow/context :revision])))))))

(deftest devflow-approved-to-cards-lands-the-proposal-then-decomposes
  ;; The cards route ends devflow's job at "approved proposal on mainline plus
  ;; implementation cards authored": sign-off routes to the landing gate, the
  ;; agent confirms the merge, and the one-step decompose stage closes the run.
  (with-runtime
    (fn [rt _]
      (workflow/start! "cards-route"
                       ;; a mid-cycle stage is normally reached by routing, so
                       ;; start it by Var: only a registered-name start is held
                       ;; to the definition's declared entrypoints
                       #'devflow/proposal
                       {:feature "widgets"}
                       {:family "devflow"
                        :context {:feature "widgets"}})
      ;; inspect-context, write-proposal, then the inner agent-review step (whose
      ;; completion auto-closes the join) reach the sign-off checkpoint
      (dotimes [_ 3] (workflow/complete! "cards-route"))
      ;; the new sign-off choice routes to the landing stage: the mainline merge
      ;; is an external wait-point, not driving-agent work
      (let [ready (:ready (devflow/choose! rt "cards-route" :approved-to-cards))]
        (is (= [{:title "Land approved proposal for widgets on mainline"
                 :gate "human"
                 :stage "land-proposal"}]
               (mapv #(select-keys % [:title :gate :stage]) ready))))
      ;; the merge gate closes only with :by recording who landed the proposal
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-blank :by"
                            (devflow/complete! rt "cards-route")))
      (devflow/complete! rt "cards-route" {:by "coordinator"})
      (let [confirm (devflow/ready-step rt "cards-route")]
        (is (= "confirm-proposal-landed" (:checkpoint confirm)))
        (is (= "land-proposal" (:stage confirm)))
        (is (= ["landed" "abort"] (:choices confirm))))
      ;; confirming the merge pours the terminal decompose stage
      (let [ready (:ready (devflow/choose! rt "cards-route" :landed))]
        (is (= [{:title "Author implementation cards for widgets"
                 :stage "decompose"
                 :action-ref "devflow.decompose.cards"}]
               (mapv #(select-keys % [:title :stage :action-ref]) ready)))
        (is (str/includes? (:instruction (first ready)) "guidance :decompose")))
      ;; the run ends at authored cards: no implementation stage follows
      (is (true? (:done (devflow/complete! rt "cards-route"))))
      (is (workflow/done? "cards-route")))))

(deftest devflow-revise-input-does-not-override-revision-round
  (with-runtime
    (fn [rt _]
      (workflow/start! "prop-input"
                       ;; a mid-cycle stage is normally reached by routing, so
                       ;; start it by Var: only a registered-name start is held
                       ;; to the definition's declared entrypoints
                       #'devflow/proposal
                       {:feature "widgets"}
                       {:family "devflow"
                        :context {:feature "widgets"}})
      ;; inspect-context, write-proposal, then the inner agent-review step (whose
      ;; completion auto-closes the join) reach the sign-off checkpoint
      (dotimes [_ 3] (workflow/complete! "prop-input"))
      ;; a caller passing {:revision false} must not un-skip :inspect-context:
      ;; the revision wrapper's :params are authoritative over the choice input
      (let [remaining (:ready (workflow/choose! "prop-input" :revise {:revision false}))]
        (is (= [{:title "Write devflow proposal for widgets" :role "step"}]
               (mapv #(select-keys % [:title :role]) remaining)))))))

(deftest devflow-intake-revision-preserves-start-opts
  (with-runtime
    (fn [rt _]
      (devflow/start! rt "intake-loop" {:worktree-check :already-in-worktree-ok})
      (devflow/choose! rt "intake-loop" :already-in-worktree)
      (devflow/complete! rt "intake-loop")
      ;; the revision round skips the worktree checkpoint and resumes at capture-brief
      (is (= "Capture user brief for intake-loop"
             (:title (first (:ready (devflow/choose! rt "intake-loop" :needs-more-brief))))))
      ;; the start opt survived the loop: the fresh intake root still records it
      (is (= "already-in-worktree-ok"
             (get-in (workflow/current-root "intake-loop")
                     [:attributes :devflow/worktree-check]))))))

(deftest generic-workflow-start-identifies-devflow-family
  (with-runtime
    (fn [_rt _]
      (workflow/start! "generic-intake"
                       :intake
                       {:feature "generic-intake"})
      (is (= "devflow"
             (get-in (workflow/current-root "generic-intake")
                     [:attributes :workflow/family]))))))

(deftest devflow-spool-composes-decision-point-workflows
  (with-runtime
    (fn [rt _]
      (let [intake devflow/intake
            proposal devflow/proposal
            route devflow/route-after-plan
            intake-result (workflow/pour! intake {:feature "workflow-stress"
                                                  :worktree-check "already-in-worktree-ok"})
            intake-root (first (:created intake-result))
            proposal-payload (workflow/compile proposal {:feature "workflow-stress"})
            route-payload (workflow/compile route {:feature "workflow-stress"})]
        (is (= "already-in-worktree-ok"
               (get-in intake-root [:attributes :devflow/worktree-check])))
        (is (some #(= "Create or confirm feature worktree for workflow-stress" (:title %))
                  (:created intake-result)))
        (is (some #(= "proposal" (get-in % [:attributes "devflow/guide"]))
                  (:strands proposal-payload)))
        (is (some #(= {"workflow/checkpoint-kind" "human"
                       "workflow/decision-point" "proposal-signed-off"}
                      (select-keys (:attributes %) ["workflow/checkpoint-kind" "workflow/decision-point"]))
                  (:strands proposal-payload)))
        (is (some #(= ["task-breakdown" "direct-implementation"]
                      (get-in % [:attributes "workflow/choices"]))
                  (:strands route-payload)))))))

(deftest devflow-spool-exposes-small-operational-loop
  (with-runtime
    (fn [rt _]
      (devflow/start! rt "workflow-loop" {:worktree-check :already-in-worktree-ok})
      (let [first-step (devflow/ready-step rt "workflow-loop")]
        (is (= "checkpoint" (:role first-step)))
        (is (= "intake" (:stage first-step)))
        (is (= "create-or-confirm-worktree" (:checkpoint first-step)))
        (is (= "already-in-worktree-ok"
               (get-in (devflow/current-root rt "workflow-loop")
                       [:attributes :devflow/worktree-check])))
        (is (= ["created-worktree" "already-in-worktree" "abort"]
               (:choices first-step)))
        (let [detail (devflow/choice-detail rt "workflow-loop" :abort)]
          (is (= {"label" "Abort"
                  "description" "Stop the feature before any substantive work begins."
                  "next" ":abort"}
                 (select-keys detail ["label" "description" "next"])))
          ;; the choice states its contract as a live spec identity, not a
          ;; frozen per-key list, and carries the form graph a worker reads
          (is (= {"spec" "ct.spools.devflow/abort-reason-input"
                  "doc" "Why the feature is being aborted; recorded on the abort step."}
                 (select-keys (get detail "input-spec") ["spec" "doc"])))
          (is (seq (get-in detail ["input-spec" "spec-forms"]))))
        (is (not (contains? first-step :choice-details)))
        (let [ready (first (:ready (devflow/choose! rt "workflow-loop" :already-in-worktree)))]
          (is (= "Capture user brief for workflow-loop" (:title ready)))
          (is (= "intake" (:stage ready))))))))

(def ^:private afk-params
  {:feature "widgets"
   :tasks [{:id "a" :title "Do A" :body "Body A"}
           {:id "b" :title "Do B"}]
   :delegate-harness "pi-main"
   :delegate-cwd "/tmp/widgets"})

(deftest devflow-afk-execution-mode-is-a-checkpoint-not-a-constructor-branch
  ;; The old constructor chose manual or delegated by whether :tasks was
  ;; supplied. The decision is now a durable choice with a continuation each
  ;; (PROP-Wcd-001.EX6), so each route is separately describable.
  (let [chooser (workflow/describe devflow/run-afk-loop {:feature "widgets"})
        manual (workflow/describe devflow/run-afk-manual {:feature "widgets"})
        delegated (workflow/describe devflow/run-afk-delegated afk-params)
        steps (into {} (map (juxt :id identity)) (:steps delegated))]
    (is (= [:choose-afk-execution] (mapv :id (:steps chooser))))
    (is (= ["manual" "delegate" "abort"]
           (mapv :key (:choices (first (:steps chooser))))))
    (is (= [":run-afk-manual" ":run-afk-delegated" ":abort"]
           (mapv :next (:choices (first (:steps chooser))))))
    (is (= [:run-afk-loop] (mapv :id (:steps manual))))
    (is (= [:task-a :task-b :human-acceptance-afk] (mapv :id (:steps delegated))))
    (is (= "subagent" (:gate (steps :task-a))))
    (is (= "subagent" (:gate (steps :task-b))))
    (is (= [] (:depends-on (steps :task-a))))
    (is (= [:task-a] (:depends-on (steps :task-b))))
    (is (= [:task-a :task-b] (:depends-on (steps :human-acceptance-afk))))
    (is (= ["accepted" "revise" "abort"]
           (mapv :key (:choices (steps :human-acceptance-afk)))))))

(deftest devflow-afk-delegated-gates-render-every-value-from-params
  (let [payload (workflow/compile
                 devflow/run-afk-delegated
                 (assoc afk-params
                        :tasks [{:id "a" :title "Do A" :body "Body A"}
                                {:id "b" :title "Do B" :harness "pi-alt"}]
                        :delegate-preamble "Policy text"))
        by-local-id (into {} (map (juxt :ref identity)) (:strands payload))]
    (is (= {"workflow/gate" "subagent"
            "devflow/task" "a"
            "agent-run/harness" "pi-main"
            "agent-run/cwd" "/tmp/widgets"}
           (select-keys (get-in by-local-id [:task-a :attributes])
                        ["workflow/gate" "devflow/task" "agent-run/harness" "agent-run/cwd"])))
    (is (= "Policy text\n\nDevflow AFK task for widgets: Do A\n\nBody A"
           (get-in by-local-id [:task-a :attributes "agent-run/prompt"])))
    (is (= "pi-alt"
           (get-in by-local-id [:task-b :attributes "agent-run/harness"])))))

(deftest devflow-afk-delegated-prompt-renders-from-params
  ;; Nothing is baked in when the definition is written, so a feature supplied
  ;; only at pour time still renders into the prompt.
  (let [payload (workflow/compile
                 devflow/run-afk-delegated
                 {:feature "widgets"
                  :tasks [{:id "a" :title "Do A" :body "Body A"}]
                  :delegate-harness "pi-main"})
        task-a (first (filter #(= :task-a (:ref %)) (:strands payload)))]
    (is (= "Devflow AFK task for widgets: Do A\n\nBody A"
           (get-in task-a [:attributes "agent-run/prompt"])))))

(defn- afk-params-rejected
  "Return the failure data from describing the delegated AFK stage with `params`."
  [params]
  (try
    (workflow/describe :run-afk-delegated (merge {:feature "widgets"} params))
    nil
    (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest devflow-afk-delegated-params-fail-loudly-before-any-pour
  ;; The queue's shape is the stage's :param-spec, so every one of these is
  ;; refused by the engine at the boundary rather than by a hand-rolled
  ;; constructor-time validator.
  (with-runtime
   (fn [_ _]
    (doseq [[label params] [["an empty queue" {:tasks [] :delegate-harness "pi"}]
                          ["a missing id" {:tasks [{:title "No id"}] :delegate-harness "pi"}]
                          ["an unsafe id" {:tasks [{:id "has space" :title "Bad id"}]
                                           :delegate-harness "pi"}]
                          ["a non-string id" {:tasks [{:id :kw-id :title "Bad id"}]
                                              :delegate-harness "pi"}]
                          ["a non-string harness" {:tasks [{:id "a" :title "A" :harness :pi}]
                                                   :delegate-harness "pi"}]
                          ["duplicate ids" {:tasks [{:id "a" :title "A"}
                                                    {:id "a" :title "Again"}]
                                            :delegate-harness "pi"}]
                          ["no resolvable harness" {:tasks [{:id "a" :title "A"}]}]
                          ["no queue at all" {}]]]
      (is (= :workflow/params-invalid (:reason (afk-params-rejected params)))
          (str "delegated AFK params must be refused for " label))))))

(deftest devflow-afk-routing-pours-the-chosen-continuation
  (with-runtime
    (fn [rt _]
      (workflow/start! "afk-route"
                       #'devflow/tasks
                       {:feature "afk-route"}
                       {:family "devflow"
                        :context {:feature "afk-route"}})
      (dotimes [_ 2] (workflow/complete! "afk-route"))
      ;; Sign-off carries the queue forward and lands on the execution-mode
      ;; checkpoint rather than guessing from the queue's presence.
      (let [ready (:ready (devflow/choose! rt "afk-route" :approved
                                           {:tasks [{"id" "one" "title" "One" "body" "Do one"}
                                                    {"id" "two" "title" "Two"}]
                                            :delegate-harness "sh"}))]
        (is (= ["Choose how the AFK task queue runs for afk-route"] (mapv :title ready)))
        (is (= "choose-afk-execution" (:checkpoint (first ready))))
        (is (= "afk" (get-in (workflow/current-root "afk-route") [:attributes :devflow/stage]))))
      (let [delegated (:ready (devflow/choose! rt "afk-route" :delegate))]
        (is (= [{:title "Delegate AFK task one for afk-route" :gate "subagent"}]
               (mapv #(select-keys % [:title :gate]) delegated))))
      (let [after-first (:ready (devflow/complete! rt "afk-route" {:by "run-one"}))]
        (is (= [{:title "Delegate AFK task two for afk-route" :gate "subagent"}]
               (mapv #(select-keys % [:title :gate]) after-first))))
      (devflow/complete! rt "afk-route" {:by "run-two"})
      (is (= "human-acceptance-afk" (:checkpoint (devflow/ready-step rt "afk-route"))))
      (let [revised (:ready (devflow/choose! rt "afk-route" :revise))]
        (is (= [{:title "Delegate AFK task one for afk-route" :gate "subagent"}]
               (mapv #(select-keys % [:title :gate]) revised)))
        (is (= "afk-route" (:run-id (first revised))))))))

(def ^:private returning-afk-stage-caller
  (workflow/workflow
   "Caller around a routed devflow stage"
   (workflow/call :manual-stage :run-afk-manual {:feature "afk-return"})
   (workflow/step :after-stage "Continue after the devflow stage returns"
                  :self
                  :depends-on [:manual-stage])))

(deftest devflow-routed-stage-returns-to-its-caller
  (with-runtime
    (fn [_rt _]
      (let [started (workflow/start! "afk-return" returning-afk-stage-caller {})
            root-id (get-in started [:root :id])]
        (is (= ["Run or hand off AFK task loop for afk-return"]
               (mapv :title (:ready started))))
        (let [returned (workflow/complete! "afk-return")]
          (is (= root-id (get-in returned [:root :id]))
              "the registered stage executes inside the caller's run")
          (is (= ["Continue after the devflow stage returns"]
                 (mapv :title (:ready returned)))
              "closing the called stage resumes the caller after its procedure join"))
        (is (true? (:done (workflow/complete! "afk-return"))))))))

(deftest devflow-afk-manual-route-pours-the-single-step
  ;; The checkpoint route still enters the same stage through :continue.
  (with-runtime
    (fn [rt _]
      (workflow/start! "afk-manual" #'devflow/run-afk-loop {:feature "afk-manual"}
                       {:family "devflow" :context {:feature "afk-manual"}})
      (let [ready (:ready (workflow/choose! "afk-manual" :manual))]
        (is (= ["Run or hand off AFK task loop for afk-manual"] (mapv :title ready)))))))

(deftest devflow-registered-routes-cover-later-stage-runtime-paths
  (with-runtime
    (fn [rt _]
      (devflow/start! rt "route-happy" {:worktree-check :already-in-worktree-ok})
      (devflow/advance! rt "route-happy" {:choice :already-in-worktree})
      (devflow/advance! rt "route-happy")
      (devflow/advance! rt "route-happy" {:choice :proposal-ready})
      (dotimes [_ 3] (devflow/advance! rt "route-happy"))
      (devflow/advance! rt "route-happy" {:choice :approved})
      (dotimes [_ 3] (devflow/advance! rt "route-happy"))
      (let [route (first (:ready (devflow/advance! rt "route-happy" {:choice :approved})))]
        (is (= "route-after-plan" (:stage route)))
        (is (= "route-after-plan" (:checkpoint route))))
      (let [implementation (first (:ready (devflow/advance! rt "route-happy" {:choice :direct-implementation})))]
        (is (= "implementation" (:stage implementation)))
        (is (= "devflow.implementation.direct" (:action-ref implementation))))
      (dotimes [_ 3] (devflow/advance! rt "route-happy"))
      (is (= {:ready [] :done true}
             (devflow/advance! rt "route-happy" {:choice :accepted}))))))

(deftest devflow-choice-next-workflow-validates-lazily
  (with-runtime
    (fn [rt _]
      (devflow/start! rt "workflow-abort" {:worktree-check :required})
      ;; the abort choice declares a required :reason input, so omitting it fails
      ;; loudly before any mutation (D1.2), leaving the checkpoint active
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Value does not satisfy the named spec"
                            (devflow/choose! rt "workflow-abort" :abort)))
      (is (= "create-or-confirm-worktree"
             (:checkpoint (devflow/ready-step rt "workflow-abort"))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Choice input must be a map"
                            (devflow/choose! rt "workflow-abort" :abort [:bad])))
      (let [ready (first (:ready (devflow/choose! rt "workflow-abort" :abort {:reason "cancelled"})))]
        (is (= "Record abort for workflow-abort: cancelled" (:title ready)))
        (is (= "abort" (:stage ready)))))))

(deftest devflow-start-fails-on-multiple-active-roots
  (with-runtime
    (fn [rt _]
      (devflow/start! rt "workflow-duplicate-root" {:worktree-check :required})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Active workflow run already exists"
                            (devflow/start! rt "workflow-duplicate-root" {:worktree-check :required}))))))

(deftest devflow-describe-surfaces-stage-choices-and-conditioned-steps
  ;; describing a stage projects its shape without pouring: the proposal stage's
  ;; sign-off checkpoint carries its routing and declared abort input, and the
  ;; agent-review call expands into a :procedure step. A stage names other
  ;; registered stages, so describing one reads the live registry.
  (with-runtime
    (fn [_ _]
      (let [proposal (devflow/describe :proposal)
            ids (set (map :id (:steps proposal)))
            signoff (first (filter #(= "checkpoint" (:role %)) (:steps proposal)))
            choices (into {} (map (juxt :key identity)) (:choices signoff))]
        (is (contains? ids :inspect-context))
        (is (some #(= "procedure" (:role %)) (:steps proposal)))
        (is (= ":spec-plan" (:next (get choices "approved"))))
        ;; the cards route targets its stage by definition symbol (see the
        ;; choice's comment in ct.spools.devflow), so no leading colon here
        (is (= "ct.spools.devflow/land-proposal" (:next (get choices "approved-to-cards"))))
        (is (= {"spec" "ct.spools.devflow/abort-reason-input"
                "doc" "Why the feature is being aborted; recorded on the abort step."}
               (select-keys (:input-spec (get choices "abort")) ["spec" "doc"]))))
      ;; a revision round condition-excludes the orientation step
      (is (not (contains? (set (map :id (:steps (workflow/describe :proposal
                                                                  {:feature "widgets"
                                                                   :revision true}))))
                          :inspect-context))))))

(deftest devflow-describe-defaults-to-the-full-cycle
  (with-runtime
    (fn [_ _]
      (let [cycle (devflow/describe)]
        (is (= 7 (count cycle)))
        (is (= "Devflow intake: <feature>" (:name (first cycle))))
        (is (every? #(seq (:steps %)) cycle))))))

(deftest devflow-run-history-and-squash-run-project-then-squash-a-run
  (with-runtime
    (fn [rt _]
      (devflow/start! rt "af-run" {:worktree-check :already-in-worktree-ok})
      ;; abort the feature: intake routes to the abort stage, then record the abort
      (devflow/choose! rt "af-run" :abort {:reason "not needed"})
      (devflow/complete! rt "af-run")
      (is (workflow/done? "af-run"))
      (let [history (devflow/run-history rt "af-run")
            intake-mol (first (filter #(= "intake" (get-in % [:root :stage])) history))
            ;; the abort route also force-closes intake's later discuss-scope
            ;; checkpoint (a decision-less :choice event), so select by outcome
            abort-choice (first (filter #(= "abort" (:outcome %)) (:events intake-mol)))]
        (is (= 2 (count history)))
        (is (= #{"intake" "abort"} (set (keep #(get-in % [:root :stage]) history))))
        (is (= :choice (:type abort-choice)))
        (is (= {:reason "not needed"} (:input abort-choice))))
      (let [digest (devflow/squash-run! rt "af-run")]
        (is (= "digest" (get-in digest [:attributes :workflow/role])))
        (is (= "af-run" (get-in digest [:attributes :workflow/run-id])))
        (is (some #(str/includes? (:title % "") "intake")
                  (get-in digest [:attributes :workflow/summary])))
        ;; the run's molecules are burned, so history now fails loudly
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow run"
                              (devflow/run-history rt "af-run")))))))

(defn unstaged-workflow
  "Return a one-step workflow whose root carries `stage` verbatim (nil for a root
  with no `devflow/stage` at all), standing in for a root that reached a devflow
  run without devflow's own vocabulary on it."
  [{:keys [feature stage]}]
  (workflow/workflow
    (str "Unstaged run: " feature)
    {:attributes (cond-> {"devflow/feature" feature}
                   stage (assoc "devflow/stage" stage))}
    (workflow/step :do-the-work (str "Do the work for " feature) :self)
    ;; a second step keeps work ready after a complete!, so the mutation seams
    ;; still have a view to project
    (workflow/step :do-more-work (str "Do more work for " feature) :self
                   :depends-on [:do-the-work])))

(defn- start-unstaged! [feature stage]
  (workflow/start! feature
                   (unstaged-workflow {:feature feature :stage stage})
                   {:feature feature}
                   {:family "devflow"
                    :definition 'ct.spools.devflow-test/unstaged-workflow
                    :context {:feature feature}}))

(deftest devflow-ready-projections-fail-loudly-on-an-unstaged-root
  (with-runtime
    (fn [rt _]
      ;; every seam that projects :stage refuses the root, so no caller sees a
      ;; ready view that silently dropped the stage it advertises; each seam gets
      ;; its own run because the mutating ones consume a step to reach a view
      (doseq [[feature project] [["no-stage-ready" #(devflow/ready rt %)]
                                 ["no-stage-ready-step" #(devflow/ready-step rt %)]
                                 ["no-stage-complete" #(devflow/complete! rt %)]
                                 ["no-stage-advance" #(devflow/advance! rt %)]]]
        (start-unstaged! feature nil)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"no active root carrying a known devflow/stage"
                              (project feature))
            feature))
      ;; the failure names the run, the offending strand, what it carried, and
      ;; what it was allowed to carry
      (start-unstaged! "no-stage" nil)
      (let [root (workflow/current-root "no-stage")
            data (try (devflow/ready rt "no-stage")
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= "no-stage" (:feature data)))
        (is (= (:id root) (:strand data)))
        (is (nil? (:stage data)))
        (is (= "no-stage" (get-in data [:attributes :devflow/feature])))
        (is (= (vec (sort devflow/stages)) (:stages data))))
      ;; an out-of-enum stage is no more acceptable than a missing one
      (start-unstaged! "bad-stage" "wandering")
      (let [data (try (devflow/ready-step rt "bad-stage")
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= "wandering" (:stage data)))
        (is (= (vec (sort devflow/stages)) (:stages data)))))))

(deftest devflow-run-history-fails-loudly-on-an-unstaged-molecule-root
  (with-runtime
    (fn [rt _]
      (start-unstaged! "history-no-stage" nil)
      (let [data (try (devflow/run-history rt "history-no-stage")
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= "history-no-stage" (:feature data)))
        (is (= (:id (workflow/current-root "history-no-stage")) (:strand data)))
        (is (= (vec (sort devflow/stages)) (:stages data))))
      (start-unstaged! "history-bad-stage" "wandering")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"carries no known devflow/stage"
                            (devflow/run-history rt "history-bad-stage"))))))

(deftest devflow-projections-conform-to-their-public-specs
  (with-runtime
    (fn [rt _]
      (devflow/start! rt "spec-shapes" {:worktree-check :already-in-worktree-ok})
      (let [ready (devflow/ready rt "spec-shapes")]
        (is (s/valid? ::devflow/ready ready) (s/explain-str ::devflow/ready ready))
        (is (= "intake" (:stage (first ready))))
        (is (s/valid? ::devflow/step-view (devflow/ready-step rt "spec-shapes"))))
      (let [ready (:ready (devflow/choose! rt "spec-shapes" :abort {:reason "shape check"}))]
        (is (s/valid? ::devflow/ready ready) (s/explain-str ::devflow/ready ready))
        (is (= "abort" (:stage (first ready)))))
      (devflow/complete! rt "spec-shapes")
      (let [history (devflow/run-history rt "spec-shapes")]
        (is (s/valid? ::devflow/run-history history)
            (s/explain-str ::devflow/run-history history))
        (is (= #{"intake" "abort"} (set (map #(get-in % [:root :stage]) history))))))))

(deftest devflow-guidance-serves-the-authoring-knowledge-base
  ;; the overview orients without picking a guide
  (let [overview (devflow/guidance)
        guides (into {} (map (fn [key] [key (devflow/guidance key)])) (keys (:guides overview)))]
    (is (= (set (keys guides)) (set (keys (:guides overview)))))
    (is (contains? (get-in overview [:workspace :paths]) :proposal))
    (is (seq (get-in overview [:workspace :invariants]))))
  ;; every guide shares the documented shape (procedures as named step vectors)
  (doseq [[key guide] (into {} (map (fn [key] [key (devflow/guidance key)]))
                            (keys (:guides (devflow/guidance))))]
    (is (string? (:purpose guide)) (str key " has a purpose"))
    (is (map? (:artifacts guide)) (str key " has artifact paths"))
    (is (and (map? (:procedures guide))
             (every? vector? (vals (:procedures guide))))
        (str key " has named procedure vectors"))
    (is (vector? (:constraints guide)) (str key " has constraints"))
    (is (vector? (:validation guide)) (str key " has a validation checklist")))
  ;; keyword and string keys resolve alike; unknown keys fail loudly
  (is (= (devflow/guidance :proposal) (devflow/guidance "proposal")))
  (is (str/includes? (get-in (devflow/guidance :tasks) [:templates :task-index]) "blocked_by"))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown devflow guide"
                        (devflow/guidance :nope))))

(deftest devflow-artifact-steps-advertise-a-resolvable-guide
  ;; every artifact->guide mapping resolves, so no step can point at a missing guide
  (doseq [[artifact guide-key] devflow/artifact-guides]
    (is (map? (devflow/guidance guide-key)) (str artifact " -> " guide-key)))
  ;; ready step views carry the guide key alongside the artifact
  (with-runtime
    (fn [rt _]
      (workflow/start! "guide-views"
                       ;; a mid-cycle stage is normally reached by routing, so
                       ;; start it by Var: only a registered-name start is held
                       ;; to the definition's declared entrypoints
                       #'devflow/proposal
                       {:feature "widgets"}
                       {:family "devflow"
                        :context {:feature "widgets"}})
      (workflow/complete! "guide-views")
      (let [step (devflow/ready-step rt "guide-views")]
        (is (= "proposal.md" (:artifact step)))
        (is (= :proposal (:guide step)))
        (is (str/includes? (:instruction step) "guidance :proposal"))))))

(defn -main
  "Run the standalone devflow.spool test suite."
  [& _args]
  (let [summary (clojure.test/run-tests 'ct.spools.devflow-test)]
    (System/exit (if (pos? (+ (:fail summary) (:error summary))) 1 0))))
