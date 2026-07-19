(ns ct.spools.devflow-test
  "Tests for the ct.spools.devflow lifecycle spool: stage workflows,
  decision-point checkpoints, revision loops, and the small operational
  loop layered over skein.spools.workflow runs."
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ct.spools.devflow :as devflow]
            [ct.spools.devflow.guidance :as guidance]
            [skein.spools.workflow :as workflow]
            [skein.api.weaver.alpha :as weaver]
            [skein.test.alpha :as t]
            [skein.core.weaver.runtime :as weaver-runtime]))

(defn- return-case-leaves [operation context return-case]
  (if (and (map? return-case) (contains? return-case :stream))
    (set (map (fn [channel] [operation (assoc context :channel channel)]) [:emits :result]))
    #{[operation context]}))

(defn- op-return-leaves [{:keys [name returns]}]
  (if (and (map? returns) (contains? returns :subcommands))
    (into #{} (mapcat (fn [[subcommand return-case]]
                        (return-case-leaves name {:subcommand subcommand} return-case)))
          (:subcommands returns))
    (return-case-leaves name {} returns)))

(defn with-runtime
  "Run f in a disposable skein.test.alpha weaver world.

  The devflow assertions call the same Clojure APIs a trusted REPL/config would
  call, but the runtime lifecycle and isolation come from the public author test
  helper rather than repo-local fixtures. Registers the devflow stage workflows
  in the world's runtime first, as `install!` does at startup, so named `:next`
  routes resolve against the runtime-owned workflow registry."
  [f]
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (weaver-runtime/with-runtime-binding (:runtime ctx)
      (fn []
        (devflow/register-workflows!)
        (f (:runtime ctx) (:config-dir ctx))))))

(deftest production-return-coverage-is-derived-from-devflow-provenance
  (with-runtime
    (fn [rt _]
      (devflow/install!)
      (let [entries (filterv #(= 'ct.spools.devflow (:provenance %)) (weaver/ops rt))
            missing (filterv #(not (contains? % :returns)) entries)
            required (into #{} (mapcat op-return-leaves) (remove #(not (contains? % :returns)) entries))
            checked #{}]
        (is (empty? missing) (str "production ops missing :returns: " (mapv :name missing)))
        (is (empty? (set/difference required checked)))))))

(deftest devflow-maven-dependency-is-observable
  (is (= "devflow-spool" (devflow/dependency-sentinel)))
  (with-runtime
    (fn [_ _]
      (is (= "devflow-spool" (:dependency-sentinel (devflow/install!)))))))

(deftest devflow-proposal-revise-loops-back-through-the-proposal-stage
  (with-runtime
    (fn [rt _]
      (workflow/start! "prop-run"
                       (devflow/proposal-workflow {:feature "widgets"})
                       {:feature "widgets"}
                       {:family "devflow"
                        :definition 'ct.spools.devflow/proposal-workflow
                        :context {:feature "widgets"}})
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
               (mapv #(select-keys % [:title :role]) remaining))))
      (is (= "Run agent review for widgets proposal" (:title (first (:ready (workflow/complete! "prop-run"))))))
      (is (= "Human sign-off for widgets proposal" (:title (first (:ready (workflow/complete! "prop-run"))))))
      ;; :approved routes on to the spec/plan stage; the poured spec-plan root
      ;; presenting its entry step is enough to confirm the loop closed
      (is (= [{:title "Write needed spec deltas for widgets" :role "step"}]
             (mapv #(select-keys % [:title :role]) (:ready (workflow/choose! "prop-run" :approved)))))
      (let [root (workflow/current-root "prop-run")]
        (is (= "Devflow spec and plan: widgets" (:title root)))
        ;; entering a fresh stage resets stage-local loop state: the revised
        ;; round's :revision flag must not ride forward in downstream context
        (is (not (contains? (get-in root [:attributes :workflow/context]) :revision)))))))

(deftest devflow-revise-input-does-not-override-revision-round
  (with-runtime
    (fn [rt _]
      (workflow/start! "prop-input"
                       (devflow/proposal-workflow {:feature "widgets"})
                       {:feature "widgets"}
                       {:family "devflow"
                        :definition 'ct.spools.devflow/proposal-workflow
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
      (devflow/start! "intake-loop" {:worktree-check :already-in-worktree-ok})
      (devflow/choose! "intake-loop" :already-in-worktree)
      (devflow/complete! "intake-loop")
      ;; the revision round skips the worktree checkpoint and resumes at capture-brief
      (is (= "Capture user brief for intake-loop"
             (:title (first (:ready (devflow/choose! "intake-loop" :needs-more-brief))))))
      ;; the start opt survived the loop: the fresh intake root still records it
      (is (= "already-in-worktree-ok"
             (get-in (workflow/current-root "intake-loop")
                     [:attributes :devflow/worktree-check]))))))

(deftest devflow-spool-composes-decision-point-workflows
  (with-runtime
    (fn [rt _]
      (let [intake (devflow/intake-workflow {:worktree-check :already-in-worktree-ok})
            proposal (devflow/proposal-workflow {})
            route (devflow/route-after-plan-workflow {})
            intake-result (workflow/pour! intake {:feature "workflow-stress"})
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
      (devflow/start! "workflow-loop" {:worktree-check :already-in-worktree-ok})
      (let [first-step (devflow/ready-step "workflow-loop")]
        (is (= "checkpoint" (:role first-step)))
        (is (= "intake" (:stage first-step)))
        (is (= "create-or-confirm-worktree" (:checkpoint first-step)))
        (is (= "already-in-worktree-ok"
               (get-in (devflow/current-root "workflow-loop")
                       [:attributes :devflow/worktree-check])))
        (is (= ["created-worktree" "already-in-worktree" "abort"]
               (:choices first-step)))
        (is (= {"label" "Abort"
                "description" "Stop the feature before any substantive work begins."
                "next" ":abort"
                "input" [{"key" "reason" "required" true
                          "description" "Why the feature is being aborted; recorded on the abort step."}]}
               (devflow/choice-detail "workflow-loop" :abort)))
        (is (not (contains? first-step :choice-details)))
        (let [ready (first (:ready (devflow/choose! "workflow-loop" :already-in-worktree)))]
          (is (= "Capture user brief for workflow-loop" (:title ready)))
          (is (= "intake" (:stage ready))))))))

(deftest devflow-afk-loop-delegation-shapes-gates-and-legacy-step
  (let [legacy (workflow/describe (devflow/run-afk-loop-workflow {}) {:feature "widgets"})
        delegated (workflow/describe
                   (devflow/run-afk-loop-workflow
                    {:feature "widgets"
                     :tasks [{:id "a" :title "Do A" :body "Body A"}
                             {:id "b" :title "Do B"}]
                     :delegate-harness "pi-main"
                     :delegate-cwd "/tmp/widgets"})
                   {:feature "widgets"})
        steps (into {} (map (juxt :id identity)) (:steps delegated))]
    (is (= [:run-afk-loop] (mapv :id (:steps legacy))))
    (is (= [:task-a :task-b :human-acceptance-afk]
           (mapv :id (:steps delegated))))
    (is (= "subagent" (:gate (steps :task-a))))
    (is (= "subagent" (:gate (steps :task-b))))
    (is (= [] (:depends-on (steps :task-a))))
    (is (= [:task-a] (:depends-on (steps :task-b))))
    (is (= [:task-a :task-b] (:depends-on (steps :human-acceptance-afk))))
    (is (= ["accepted" "revise" "abort"]
           (mapv :key (:choices (steps :human-acceptance-afk))))))
  (let [payload (workflow/compile
                 (devflow/run-afk-loop-workflow
                  {:feature "widgets"
                   :tasks [{:id "a" :title "Do A" :body "Body A"}
                           {:id "b" :title "Do B" :harness "pi-alt"}]
                   :delegate-harness "pi-main"
                   :delegate-cwd "/tmp/widgets"
                   :delegate-preamble "Policy text"})
                 {:feature "widgets"})
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

(deftest devflow-afk-loop-prompt-renders-from-params
  ;; feature supplied only as a workflow param, not a constructor opt: the
  ;; prompt must render at compile time like the title instead of baking nil
  (let [payload (workflow/compile
                 (devflow/run-afk-loop-workflow
                  {:tasks [{:id "a" :title "Do A" :body "Body A"}]
                   :delegate-harness "pi-main"})
                 {:feature "widgets"})
        task-a (first (filter #(= :task-a (:ref %)) (:strands payload)))]
    (is (= "Devflow AFK task for widgets: Do A\n\nBody A"
           (get-in task-a [:attributes "agent-run/prompt"])))))

(deftest devflow-afk-loop-delegation-fails-loudly
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must not be empty"
                        (devflow/run-afk-loop-workflow {:tasks [] :delegate-harness "pi"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"token-safe"
                        (devflow/run-afk-loop-workflow {:tasks [{:title "No id"}]
                                                       :delegate-harness "pi"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"token-safe"
                        (devflow/run-afk-loop-workflow {:tasks [{:id "has space" :title "Bad id"}]
                                                       :delegate-harness "pi"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"token-safe"
                        (devflow/run-afk-loop-workflow {:tasks [{:id :kw-id :title "Bad id"}]
                                                       :delegate-harness "pi"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"harness must be a non-blank string"
                        (devflow/run-afk-loop-workflow {:tasks [{:id "a" :title "A" :harness :pi}]
                                                       :delegate-harness "pi"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ids must be unique"
                        (devflow/run-afk-loop-workflow {:tasks [{:id "a" :title "A"}
                                                                {:id "a" :title "Again"}]
                                                       :delegate-harness "pi"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing harness resolution"
                        (devflow/run-afk-loop-workflow {:tasks [{:id "a" :title "A"}]}))))

(deftest devflow-afk-loop-routing-and-revision-pour-delegated-gates
  (with-runtime
    (fn [rt _]
      (workflow/start! "afk-route"
                       (devflow/task-breakdown-workflow {:feature "afk-route"})
                       {:feature "afk-route"}
                       {:family "devflow"
                        :definition 'ct.spools.devflow/task-breakdown-workflow
                        :context {:feature "afk-route"}})
      (dotimes [_ 2] (workflow/complete! "afk-route"))
      (let [ready (:ready (devflow/choose! "afk-route" :approved
                                           {"tasks" [{"id" "one" "title" "One" "body" "Do one"}
                                                      {"id" "two" "title" "Two"}]
                                            "delegate-harness" "sh"}))]
        (is (= [{:title "Delegate AFK task one for afk-route" :gate "subagent"}]
               (mapv #(select-keys % [:title :gate]) ready)))
        (is (= "afk" (get-in (workflow/current-root "afk-route") [:attributes :devflow/stage]))))
      (let [after-first (:ready (devflow/complete! "afk-route" {:by "run-one"}))]
        (is (= [{:title "Delegate AFK task two for afk-route" :gate "subagent"}]
               (mapv #(select-keys % [:title :gate]) after-first))))
      (devflow/complete! "afk-route" {:by "run-two"})
      (is (= "human-acceptance-afk" (:checkpoint (devflow/ready-step "afk-route"))))
      (let [revised (:ready (devflow/choose! "afk-route" :revise))]
        (is (= [{:title "Delegate AFK task one for afk-route" :gate "subagent"}]
               (mapv #(select-keys % [:title :gate]) revised)))
        (is (= "afk-route" (:run-id (first revised))))))))

(deftest devflow-registered-routes-cover-later-stage-runtime-paths
  (with-runtime
    (fn [rt _]
      (devflow/start! "route-happy" {:worktree-check :already-in-worktree-ok})
      (devflow/advance! "route-happy" {:choice :already-in-worktree})
      (devflow/advance! "route-happy" {:notes "brief captured"})
      (devflow/advance! "route-happy" {:choice :proposal-ready})
      (dotimes [_ 3] (devflow/advance! "route-happy" {:notes "proposal work"}))
      (devflow/advance! "route-happy" {:choice :approved})
      (dotimes [_ 3] (devflow/advance! "route-happy" {:notes "spec-plan work"}))
      (let [route (first (:ready (devflow/advance! "route-happy" {:choice :approved})))]
        (is (= "route-after-plan" (:stage route)))
        (is (= "route-after-plan" (:checkpoint route))))
      (let [implementation (first (:ready (devflow/advance! "route-happy" {:choice :direct-implementation})))]
        (is (= "implementation" (:stage implementation)))
        (is (= "devflow.implementation.direct" (:action-ref implementation))))
      (dotimes [_ 3] (devflow/advance! "route-happy" {:notes "implementation work"}))
      (is (= {:ready [] :done true}
             (devflow/advance! "route-happy" {:choice :accepted}))))))

(deftest devflow-choice-next-workflow-validates-lazily
  (with-runtime
    (fn [rt _]
      (devflow/start! "workflow-abort" {:worktree-check :required})
      ;; the abort choice declares a required :reason input, so omitting it fails
      ;; loudly before any mutation (D1.2), leaving the checkpoint active
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing required keys"
                            (devflow/choose! "workflow-abort" :abort)))
      (is (= "create-or-confirm-worktree"
             (:checkpoint (devflow/ready-step "workflow-abort"))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Choice input must be a map"
                            (devflow/choose! "workflow-abort" :abort [:bad])))
      (let [ready (first (:ready (devflow/choose! "workflow-abort" :abort {:reason "cancelled"})))]
        (is (= "Record abort for workflow-abort: cancelled" (:title ready)))
        (is (= "abort" (:stage ready)))))))

(deftest devflow-start-fails-on-multiple-active-roots
  (with-runtime
    (fn [rt _]
      (devflow/start! "workflow-duplicate-root" {:worktree-check :required})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Active workflow run already exists"
                            (devflow/start! "workflow-duplicate-root" {:worktree-check :required}))))))

(deftest devflow-describe-surfaces-stage-choices-and-conditioned-steps
  ;; describing a stage projects its shape without pouring: the proposal stage's
  ;; sign-off checkpoint carries its routing and declared abort input, and the
  ;; agent-review call expands into a :procedure step.
  (let [proposal (devflow/describe :proposal)
        ids (set (map :id (:steps proposal)))
        signoff (first (filter #(= "checkpoint" (:role %)) (:steps proposal)))
        choices (into {} (map (juxt :key identity)) (:choices signoff))]
    (is (contains? ids :inspect-context))
    (is (some #(= "procedure" (:role %)) (:steps proposal)))
    (is (= ":spec-plan" (:next (get choices "approved"))))
    (is (= [{"key" "reason" "required" true
             "description" "Why the feature is being aborted; recorded on the abort step."}]
           (:input (get choices "abort")))))
  ;; a revision round condition-excludes the orientation step
  (is (not (contains? (set (map :id (:steps (workflow/describe (devflow/proposal-workflow {:revision true})
                                                               {:feature "widgets"}))))
                      :inspect-context))))

(deftest devflow-describe-defaults-to-the-full-cycle
  (let [cycle (devflow/describe)]
    (is (= 7 (count cycle)))
    (is (= "Devflow intake: <feature>" (:name (first cycle))))
    (is (every? #(seq (:steps %)) cycle))))

(deftest devflow-run-history-and-squash-run-project-then-squash-a-run
  (with-runtime
    (fn [rt _]
      (devflow/start! "af-run" {:worktree-check :already-in-worktree-ok})
      ;; abort the feature: intake routes to the abort stage, then record the abort
      (devflow/choose! "af-run" :abort {:reason "not needed"})
      (devflow/complete! "af-run")
      (is (workflow/done? "af-run"))
      (let [history (devflow/run-history "af-run")
            intake-mol (first (filter #(= "intake" (get-in % [:root :stage])) history))
            ;; the abort route also force-closes intake's later discuss-scope
            ;; checkpoint (a decision-less :choice event), so select by outcome
            abort-choice (first (filter #(= "abort" (:outcome %)) (:events intake-mol)))]
        (is (= 2 (count history)))
        (is (= #{"intake" "abort"} (set (keep #(get-in % [:root :stage]) history))))
        (is (= :choice (:type abort-choice)))
        (is (= {:reason "not needed"} (:input abort-choice))))
      (let [digest (devflow/squash-run! "af-run")]
        (is (= "digest" (get-in digest [:attributes :workflow/role])))
        (is (= "af-run" (get-in digest [:attributes :workflow/run-id])))
        (is (some #(str/includes? (:title % "") "intake")
                  (get-in digest [:attributes :workflow/summary])))
        ;; the run's molecules are burned, so history now fails loudly
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow run"
                              (devflow/run-history "af-run")))))))

(defn unstaged-workflow
  "Return a one-step workflow whose root carries `stage` verbatim (nil for a root
  with no `devflow/stage` at all), standing in for a root that reached a devflow
  run without devflow's own vocabulary on it."
  [{:keys [feature stage]}]
  (workflow/workflow
    (str "Unstaged run: " feature)
    {:params {:feature (workflow/param :required true)}
     :attributes (cond-> {"devflow/feature" feature}
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
      (doseq [[feature project] [["no-stage-ready" devflow/ready]
                                 ["no-stage-ready-step" devflow/ready-step]
                                 ["no-stage-complete" #(devflow/complete! %)]
                                 ["no-stage-advance" #(devflow/advance! %)]]]
        (start-unstaged! feature nil)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"no active root carrying a known devflow/stage"
                              (project feature))
            feature))
      ;; the failure names the run, the offending strand, what it carried, and
      ;; what it was allowed to carry
      (start-unstaged! "no-stage" nil)
      (let [root (workflow/current-root "no-stage")
            data (try (devflow/ready "no-stage")
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= "no-stage" (:feature data)))
        (is (= (:id root) (:strand data)))
        (is (nil? (:stage data)))
        (is (= "no-stage" (get-in data [:attributes :devflow/feature])))
        (is (= (vec (sort devflow/stages)) (:stages data))))
      ;; an out-of-enum stage is no more acceptable than a missing one
      (start-unstaged! "bad-stage" "wandering")
      (let [data (try (devflow/ready-step "bad-stage")
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= "wandering" (:stage data)))
        (is (= (vec (sort devflow/stages)) (:stages data)))))))

(deftest devflow-run-history-fails-loudly-on-an-unstaged-molecule-root
  (with-runtime
    (fn [rt _]
      (start-unstaged! "history-no-stage" nil)
      (let [data (try (devflow/run-history "history-no-stage")
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= "history-no-stage" (:feature data)))
        (is (= (:id (workflow/current-root "history-no-stage")) (:strand data)))
        (is (= (vec (sort devflow/stages)) (:stages data))))
      (start-unstaged! "history-bad-stage" "wandering")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"carries no known devflow/stage"
                            (devflow/run-history "history-bad-stage"))))))

(deftest devflow-projections-conform-to-their-public-specs
  (with-runtime
    (fn [rt _]
      (devflow/start! "spec-shapes" {:worktree-check :already-in-worktree-ok})
      (let [ready (devflow/ready "spec-shapes")]
        (is (s/valid? ::devflow/ready ready) (s/explain-str ::devflow/ready ready))
        (is (= "intake" (:stage (first ready))))
        (is (s/valid? ::devflow/step-view (devflow/ready-step "spec-shapes"))))
      (let [ready (:ready (devflow/choose! "spec-shapes" :abort {:reason "shape check"}))]
        (is (s/valid? ::devflow/ready ready) (s/explain-str ::devflow/ready ready))
        (is (= "abort" (:stage (first ready)))))
      (devflow/complete! "spec-shapes")
      (let [history (devflow/run-history "spec-shapes")]
        (is (s/valid? ::devflow/run-history history)
            (s/explain-str ::devflow/run-history history))
        (is (= #{"intake" "abort"} (set (map #(get-in % [:root :stage]) history))))))))

(deftest devflow-guidance-serves-the-authoring-knowledge-base
  ;; the overview orients without picking a guide
  (let [overview (devflow/guidance)]
    (is (= (set (keys guidance/guides)) (set (keys (:guides overview)))))
    (is (contains? (get-in overview [:workspace :paths]) :proposal))
    (is (seq (get-in overview [:workspace :invariants]))))
  ;; every guide shares the documented shape (procedures as named step vectors)
  (doseq [[key guide] guidance/guides]
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
                       (devflow/proposal-workflow {:feature "widgets"})
                       {:feature "widgets"}
                       {:family "devflow"
                        :definition 'ct.spools.devflow/proposal-workflow
                        :context {:feature "widgets"}})
      (workflow/complete! "guide-views")
      (let [step (devflow/ready-step "guide-views")]
        (is (= "proposal.md" (:artifact step)))
        (is (= :proposal (:guide step)))
        (is (str/includes? (:instruction step) "guidance :proposal"))))))

(defn -main
  "Run the standalone devflow.spool test suite."
  [& _args]
  (let [summary (clojure.test/run-tests 'ct.spools.devflow-test)]
    (System/exit (if (pos? (+ (:fail summary) (:error summary))) 1 0))))
