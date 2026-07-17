(ns ct.spools.devflow
  "Clojure-native workflow definitions for the devflow lifecycle.

  These helpers encode the agent-facing devflow checkpoints as Skein workflow
  data. They intentionally produce ordinary workflow definitions that callers
  can inspect, compose, pour as molecules, or materialize as wisps.

  Authoring knowledge for the artifacts each stage produces (proposal, specs,
  plan, task queue, ...) lives in `ct.spools.devflow.guidance` and is served
  by `guidance`; steps advertise their guide key via the `devflow/guide`
  attribute and ready step views surface it as `:guide`."
  (:require [camel-snake-kebab.core :as csk]
            [clojure.string :as str]
            [ct.spools.devflow.guidance :as guidance]
            [skein.api.current.alpha :as current]
            [skein.api.weaver.alpha :as weaver]
            [skein.spools.workflow :as workflow]))

(def artifact-guides
  "Maps each `workflow/artifact` value an authoring step advertises to the
  guidance key holding its authoring rules (see `guidance` and
  `ct.spools.devflow.guidance/guides`). The brief has no guide; it is
  captured conversationally during intake."
  {"proposal.md" :proposal
   "specs/*.delta.md" :spec
   "<feature>.plan.md" :plan
   "tasks/index.yml" :tasks})

(defn- guided-artifact
  "Attributes for a step that authors a guided artifact: the artifact path, its
  guide key, and the instruction telling the driving agent to fetch that guide."
  [artifact]
  (let [guide (or (artifact-guides artifact)
                  (throw (ex-info "No guide registered for artifact"
                                  {:artifact artifact :artifacts (vec (keys artifact-guides))})))]
    {"workflow/artifact" artifact
     "devflow/guide" (name guide)
     "workflow/instruction" (str "Call (ct.spools.devflow/guidance " guide ") for the "
                                 "authoring procedure, constraints, template, and validation "
                                 "checklist before writing " artifact ".")}))

(defn- titled
  ([prefix]
   (titled prefix ""))
  ([prefix suffix]
   (fn [{:keys [feature]}]
     (str prefix feature suffix))))

(defn- param-value [k]
  (fn [params]
    (get params k)))

(defn- task-value
  "Return task field `k`, accepting keyword or string keyed task maps."
  [task k]
  (or (get task k) (get task (name k))))

(def ^:private afk-task-id-pattern
  "AFK task ids become workflow step ids (`:task-<id>`), so they must be
  token-safe: no whitespace, slashes, colons, or leading punctuation."
  #"[A-Za-z0-9][A-Za-z0-9._-]*")

(defn dependency-sentinel
  "Return a stable value produced through the Maven dependency declared by this spool.

  This is intentionally operationally harmless; runtime/demo validation calls it
  only to prove `camel-snake-kebab` was resolved through the approved spool's
  top-level `deps.edn :deps`."
  []
  (csk/->kebab-case-string "devflow_spool"))

(defn- non-blank-string? [v]
  (and (string? v) (not (str/blank? v))))

(defn- validate-afk-tasks
  "Return validated AFK delegation tasks or nil when delegation is not requested."
  [tasks]
  (when (some? tasks)
    (when-not (vector? tasks)
      (throw (ex-info "AFK tasks must be a vector" {:tasks tasks})))
    (when (empty? tasks)
      (throw (ex-info "AFK tasks must not be empty" {:tasks tasks})))
    (doseq [task tasks]
      (when-not (map? task)
        (throw (ex-info "AFK task must be a map" {:task task})))
      (let [id (task-value task :id)]
        (when-not (and (non-blank-string? id) (re-matches afk-task-id-pattern id))
          (throw (ex-info "AFK task id must be a token-safe string"
                          {:task task :id id :pattern (str afk-task-id-pattern)}))))
      (when-not (non-blank-string? (task-value task :title))
        (throw (ex-info "AFK task title must be a non-blank string" {:task task})))
      (let [harness (task-value task :harness)]
        (when (and (some? harness) (not (non-blank-string? harness)))
          (throw (ex-info "AFK task harness must be a non-blank string"
                          {:task task :harness harness})))))
    (let [ids (map #(task-value % :id) tasks)]
      (when-not (= (count ids) (count (distinct ids)))
        (throw (ex-info "AFK task ids must be unique" {:ids ids}))))
    tasks))

(defn- validate-afk-harnesses
  "Fail loudly unless every delegated AFK task resolves a harness."
  [tasks delegate-harness]
  (doseq [task tasks]
    (when-not (non-blank-string? (or (task-value task :harness) delegate-harness))
      (throw (ex-info "AFK task missing harness resolution"
                      {:task task :delegate-harness delegate-harness})))))

(defn- afk-task-prompt [feature task delegate-preamble]
  (str (when (non-blank-string? delegate-preamble)
         (str delegate-preamble "\n\n"))
       "Devflow AFK task for " feature ": " (task-value task :title) "\n\n"
       (or (task-value task :body) (task-value task :title))))

(defn- afk-task-gate [delegate-harness delegate-cwd]
  (workflow/gate :task
                 (fn [{:keys [feature item]}]
                   (str "Delegate AFK task " (task-value item :id) " for " feature))
                 :subagent
                 :loop {:each :tasks :chain true}
                 ;; the prompt renders from resolved params like the title, so
                 ;; direct compile/pour! usage with :feature supplied only as a
                 ;; workflow param cannot bake "nil" into agent-run/prompt
                 :attributes (cond-> {"devflow/task" (fn [{:keys [item]}] (task-value item :id))
                                      "agent-run/harness" (fn [{:keys [item delegate-harness]}]
                                                          (or (task-value item :harness) delegate-harness))
                                      "agent-run/prompt" (fn [{:keys [feature item delegate-preamble]}]
                                                         (afk-task-prompt feature item delegate-preamble))}
                               delegate-cwd (assoc "agent-run/cwd" delegate-cwd))))

(def ^:private abort-reason-input
  "Declared choice input for every abort choice: a required `:reason` recorded on
  the abort step and surfaced with the choice (workflow.md §5). `choose!` fails
  loudly before any mutation when it is omitted."
  [{:key :reason :required true
    :description "Why the feature is being aborted; recorded on the abort step."}])

(defn intake-workflow
  "Return the mandatory brief intake workflow.

  The first strand is a HITL decision/checkpoint that requires worktree creation
  before substantive discovery. `:worktree-check` may be `:required` for a fresh
  brief or `:already-in-worktree-ok` for agents launched directly inside the
  feature worktree. On a revision round (`:revision true`), the worktree
  checkpoint is skipped because it was already satisfied on the first pass;
  F4's splice reattaches `:capture-brief` as the entry step."
  [{:keys [feature worktree-check revision]
    :or {worktree-check :required}}]
  (workflow/workflow
    (titled "Devflow intake: ")
    {:params {:feature (workflow/param :required true)
              :worktree-check (workflow/param :default (name worktree-check))
              :revision (workflow/param :default (boolean revision))}
     :attributes {"devflow/stage" "intake"
                  "devflow/feature" (param-value :feature)
                  "devflow/worktree-check" (param-value :worktree-check)}}
    (workflow/checkpoint :create-or-confirm-worktree
                         (titled "Create or confirm feature worktree for ")
                         :kind :human
                         :condition [:!= :revision true]
                         :choices [{:key :created-worktree
                                    :label "Created worktree"
                                    :description "A new feature worktree was created; continue intake there."}
                                   {:key :already-in-worktree
                                    :label "Already in worktree"
                                    :description "This agent is already running in the correct feature worktree; continue intake."}
                                   {:key :abort
                                    :label "Abort"
                                    :description "Stop the feature before any substantive work begins."
                                    :next :abort
                                    :input abort-reason-input}]
                         :attributes {"workflow/decision-point" "worktree-ready"
                                      "workflow/action-ref" "devflow.worktree.ensure"
                                      "workflow/instruction" "Create a new feature worktree before doing discovery or code work. If this agent is already running inside the correct feature worktree, choose already-in-worktree."})
    (workflow/step :capture-brief
                   (titled "Capture user brief for ")
                   :self
                   :depends-on [:create-or-confirm-worktree]
                   :attributes {"workflow/artifact" "brief"})
    (workflow/checkpoint :discuss-scope
                         (titled "Discuss scope and open questions for ")
                         :depends-on [:capture-brief]
                         :kind :agent
                         :choices [{:key :proposal-ready
                                    :label "Proposal ready"
                                    :description "Scope is clear enough; create the proposal workflow next."
                                    :next :proposal}
                                   {:key :needs-more-brief
                                    :label "Needs more brief"
                                    :description "Scope is incomplete; revise intake to gather more brief before proposing."
                                    :revise {:params {:revision true}}}]
                         :attributes {"workflow/decision-point" "scope-ready"})))

(defn agent-review-workflow
  "Return a reusable one-step agent review procedure."
  [_opts]
  (workflow/workflow
    (fn [{:keys [feature artifact]}]
      (str "Agent review: " feature " " artifact))
    {:params {:feature (workflow/param :required true)
              :artifact (workflow/param :required true)}}
    (workflow/step :review
                   (fn [{:keys [feature artifact]}]
                     (str "Run agent review for " feature " " artifact))
                   :self
                   :attributes {"devflow/review" "agent"})))

(defn proposal-workflow
  "Return the proposal gate workflow.

  This encodes: inspect RFCs/spikes/specs first, write proposal, run agent
  review, then stop for human sign-off. On a revision round (`:revision true`),
  `:inspect-context` is skipped because orientation was done on the first pass;
  F4's splice reattaches `:write-proposal` as the entry step."
  [{:keys [revision] :as _opts}]
  (workflow/workflow
    (titled "Devflow proposal: ")
    {:params {:feature (workflow/param :required true)
              :revision (workflow/param :default (boolean revision))}
     :attributes {"devflow/stage" "proposal"
                  "devflow/feature" (param-value :feature)}}
    (workflow/step :inspect-context
                   (titled "Inspect relevant RFCs, spikes, root specs, and active feature context for ")
                   :self
                   :condition [:!= :revision true]
                   :attributes {"workflow/action-ref" "devflow.proposal.orient"
                                "workflow/instruction" "Inspect relevant active RFCs, spikes, root specs, active feature folders, and affected code before writing the proposal."})
    (workflow/step :write-proposal
                   (titled "Write devflow proposal for ")
                   :self
                   :depends-on [:inspect-context]
                   :attributes (guided-artifact "proposal.md"))
    (workflow/call :agent-review-proposal
                   agent-review-workflow
                   {:artifact "proposal"}
                   :title (titled "Complete agent review for " " proposal")
                   :depends-on [:write-proposal])
    (workflow/checkpoint :human-signoff-proposal
                         (titled "Human sign-off for " " proposal")
                         :depends-on [:agent-review-proposal]
                         :kind :human
                         :choices [{:key :approved
                                    :label "Approve"
                                    :description "Proposal is accepted; continue to spec and plan work."
                                    :next :spec-plan}
                                   {:key :revise
                                    :label "Revise"
                                    :description "Proposal needs changes; revise the proposal stage and re-review before proceeding."
                                    :revise {:params {:revision true}}}
                                   {:key :abort
                                    :label "Abort"
                                    :description "Stop this feature intentionally. Do not proceed to spec or plan work."
                                    :next :abort
                                    :input abort-reason-input}]
                         :attributes {"workflow/decision-point" "proposal-signed-off"})))

(defn route-after-plan-workflow
  "Return the post-plan route-choice workflow."
  [_opts]
  (workflow/workflow
    (titled "Devflow route after plan: ")
    {:params {:feature (workflow/param :required true)}
     :attributes {"devflow/stage" "route-after-plan"
                  "devflow/feature" (param-value :feature)}}
    (workflow/checkpoint :route-after-plan
                         (titled "Recommend next workflow: tasks or direct implementation for ")
                         :kind :agent
                         :choices [{:key :task-breakdown
                                    :label "Task breakdown"
                                    :description "Create an AFK/HITL task queue before implementation."
                                    :next :tasks}
                                   {:key :direct-implementation
                                    :label "Direct implementation"
                                    :description "Proceed directly to implementation because the reviewed plan is small and settled."
                                    :next :direct-implementation}]
                         :attributes {"workflow/decision-point" "choose-tasks-or-implementation"})))

(defn spec-plan-workflow
  "Return the spec-delta and plan gate workflow.

  After review and human sign-off, approval routes to the task/direct
  implementation decision workflow. A revision round (`:revision true`) re-runs
  the whole spec/plan stage."
  [{:keys [revision] :as _opts}]
  (workflow/workflow
    (titled "Devflow spec and plan: ")
    {:params {:feature (workflow/param :required true)
              :revision (workflow/param :default (boolean revision))}
     :attributes {"devflow/stage" "spec-plan"
                  "devflow/feature" (param-value :feature)}}
    (workflow/step :write-spec-deltas
                   (titled "Write needed spec deltas for ")
                   :self
                   :attributes (guided-artifact "specs/*.delta.md"))
    (workflow/step :write-plan
                   (titled "Write implementation plan for ")
                   :self
                   :depends-on [:write-spec-deltas]
                   :attributes (guided-artifact "<feature>.plan.md"))
    (workflow/call :agent-review-spec-plan
                   agent-review-workflow
                   {:artifact "spec deltas and plan"}
                   :title (titled "Complete agent review for " " spec deltas and plan")
                   :depends-on [:write-plan])
    (workflow/checkpoint :human-signoff-spec-plan
                         (titled "Human sign-off for " " spec deltas and plan")
                         :depends-on [:agent-review-spec-plan]
                         :kind :human
                         :choices [{:key :approved
                                    :label "Approve"
                                    :description "Spec deltas and plan are accepted; choose tasks or direct implementation next."
                                    :next :route-after-plan}
                                   {:key :revise
                                    :label "Revise"
                                    :description "Spec deltas or plan need changes; revise the spec/plan stage and re-review before proceeding."
                                    :revise {:params {:revision true}}}
                                   {:key :abort
                                    :label "Abort"
                                    :description "Stop this feature intentionally before implementation."
                                    :next :abort
                                    :input abort-reason-input}]
                         :attributes {"workflow/decision-point" "plan-signed-off"})))

(defn run-afk-loop-workflow
  "Return the post-task-signoff AFK loop workflow.

  With no `:tasks` opt, returns the legacy single manual AFK step. With `:tasks`,
  returns a sequential chain of `:subagent` gates for subagent-executor fulfillment, then a
  HITL acceptance checkpoint. Task maps may be keyword- or string-keyed."
  [{:keys [tasks delegate-harness delegate-cwd delegate-preamble] :as opts}]
  (let [tasks (validate-afk-tasks (or tasks (get opts "tasks")))
        delegate-harness (or delegate-harness (get opts "delegate-harness"))
        delegate-cwd (or delegate-cwd (get opts "delegate-cwd"))
        delegate-preamble (or delegate-preamble (get opts "delegate-preamble"))]
    (when tasks
      (validate-afk-harnesses tasks delegate-harness))
    (apply workflow/workflow
           (titled "Devflow AFK execution: ")
           {:params {:feature (workflow/param :required true)
                     :tasks (workflow/param :default tasks)
                     :delegate-harness (workflow/param :default delegate-harness)
                     :delegate-cwd (workflow/param :default delegate-cwd)
                     :delegate-preamble (workflow/param :default delegate-preamble)}
            :attributes {"devflow/stage" "afk"
                         "devflow/feature" (param-value :feature)}}
           (if tasks
             [(afk-task-gate delegate-harness delegate-cwd)
              (workflow/checkpoint :human-acceptance-afk
                                   (titled "Human acceptance for " " AFK task execution")
                                   :depends-on [:task]
                                   :kind :human
                                   :choices [{:key :accepted
                                              :label "Accept"
                                              :description "AFK task execution is accepted; the run is done."}
                                             {:key :revise
                                              :label "Revise"
                                              :description "AFK task execution needs changes; re-run the delegated AFK stage."
                                              :revise {:params {:revision true}}}
                                             {:key :abort
                                              :label "Abort"
                                              :description "Stop or abandon this feature after AFK execution."
                                              :next :abort
                                              :input abort-reason-input}]
                                   :attributes {"workflow/decision-point" "afk-accepted"})]
             [(workflow/step :run-afk-loop
                             (titled "Run or hand off AFK task loop for ")
                             :self
                             :attributes {"workflow/action-ref" "devflow.tasks.run-afk-loop"
                                          "devflow/guide" "afk"
                                          "workflow/instruction" "Run or hand off the devflow AFK task loop for this feature after task sign-off. Call (ct.spools.devflow/guidance :afk) for the loop contract and queue checks."})]))))

(defn task-breakdown-workflow
  "Return the reviewed task queue workflow.

  A revision round (`:revision true`) re-runs the whole task-breakdown stage."
  [{:keys [revision] :as _opts}]
  (workflow/workflow
    (titled "Devflow task breakdown: ")
    {:params {:feature (workflow/param :required true)
              :revision (workflow/param :default (boolean revision))}
     :attributes {"devflow/stage" "tasks"
                  "devflow/feature" (param-value :feature)}}
    (workflow/step :write-tasks
                   (titled "Write AFK/HITL task queue for ")
                   :self
                   :attributes (guided-artifact "tasks/index.yml"))
    (workflow/call :agent-review-tasks
                   agent-review-workflow
                   {:artifact "task queue"}
                   :title (titled "Complete agent review for " " task queue")
                   :depends-on [:write-tasks])
    (workflow/checkpoint :human-signoff-tasks
                         (titled "Human sign-off for " " task queue")
                         :depends-on [:agent-review-tasks]
                         :kind :human
                         :choices [{:key :approved
                                    :label "Approve"
                                    :description "Task queue is accepted; run or hand off the AFK loop next."
                                    :next :run-afk-loop
                                    :input [{:key :tasks
                                             :required false
                                             :description "Optional vector of AFK task maps to delegate as sequential subagent gates."}]}
                                   {:key :revise
                                    :label "Revise"
                                    :description "Task queue needs changes; revise the task-breakdown stage and re-review before execution."
                                    :revise {:params {:revision true}}}
                                   {:key :abort
                                    :label "Abort"
                                    :description "Stop this feature before task execution."
                                    :next :abort
                                    :input abort-reason-input}]
                         :attributes {"workflow/decision-point" "tasks-signed-off"})))

(defn direct-implementation-workflow
  "Return the post-plan direct implementation workflow for small, settled changes.

  A revision round (`:revision true`) re-runs the whole implementation stage."
  [{:keys [revision] :as _opts}]
  (workflow/workflow
    (titled "Devflow direct implementation: ")
    {:params {:feature (workflow/param :required true)
              :revision (workflow/param :default (boolean revision))}
     :attributes {"devflow/stage" "implementation"
                  "devflow/feature" (param-value :feature)}}
    (workflow/step :implement
                   (titled "Implement reviewed plan for ")
                   :self
                   :attributes {"workflow/action-ref" "devflow.implementation.direct"
                                "workflow/instruction" "Implement the reviewed plan directly because the signed-off scope does not need a separate task breakdown."})
    (workflow/step :validate
                   (titled "Validate implementation for ")
                   :self
                   :depends-on [:implement]
                   :attributes {"workflow/action-ref" "devflow.implementation.validate"
                                "workflow/instruction" "Run validation relevant to the touched implementation and report failures before review."})
    (workflow/call :review-implementation
                   agent-review-workflow
                   {:artifact "implementation"}
                   :title (titled "Complete implementation review for ")
                   :depends-on [:validate])
    (workflow/checkpoint :human-acceptance
                         (titled "Human acceptance for " " implementation")
                         :depends-on [:review-implementation]
                         :kind :human
                         :choices [{:key :accepted
                                    :label "Accept"
                                    :description "Implementation is accepted; continue to finish/archive work."}
                                   {:key :revise
                                    :label "Revise"
                                    :description "Implementation needs changes; revise the implementation stage and re-review before acceptance."
                                    :revise {:params {:revision true}}}
                                   {:key :abort
                                    :label "Abort"
                                    :description "Stop or abandon this feature after implementation review."
                                    :next :abort
                                    :input abort-reason-input}]
                         :attributes {"workflow/decision-point" "implementation-accepted"})))

(defn abort-workflow
  "Return a tiny workflow that records intentional feature abortion."
  [_opts]
  (workflow/workflow
    (titled "Abort devflow feature: ")
    {:params {:feature (workflow/param :required true)
              :reason (workflow/param :required true)}
     :attributes {"devflow/stage" "abort"
                  "devflow/feature" (param-value :feature)}}
    (workflow/step :record-abort
                   (fn [{:keys [feature reason]}]
                     (str "Record abort for " feature ": " reason))
                   :self
                   :attributes {"workflow/action-ref" "devflow.abort.record"
                                "workflow/instruction" "Record the abort reason in the feature plan or conversation summary, then stop the active workflow."})))

(defn devflow-cycle
  "Return the ordered composable devflow workflow definitions for `opts`.

  Callers can pour the first workflow, then use decision-point strand outcomes to
  choose and pour the next workflow."
  [opts]
  [(intake-workflow opts)
   (proposal-workflow opts)
   (spec-plan-workflow opts)
   (route-after-plan-workflow opts)
   (task-breakdown-workflow opts)
   (run-afk-loop-workflow opts)
   (direct-implementation-workflow opts)])

(defn- root-stage
  "Return the active devflow stage string for feature, or nil when the run is done."
  [feature]
  (let [root (workflow/current-root feature)]
    (or (get-in root [:attributes :devflow/stage])
        (get-in root [:attributes "devflow/stage"]))))

(defn- add-stage
  "Add the devflow stage and artifact guide key to a ready step view."
  [stage step]
  (when step
    (let [guide (artifact-guides (:artifact step))]
      (cond-> step
        stage (assoc :stage stage)
        guide (assoc :guide guide)))))

(defn- add-current-stage
  "Add the feature's current stage to every ready step in a mutation result."
  [feature result]
  (let [stage (root-stage feature)]
    (update result :ready #(mapv (partial add-stage stage) %))))

(defn start!
  "Start the devflow intake workflow for `feature` and return the engine
  `{:ready [step-view ...] :done boolean}` result shape.

  Each ready step view carries the current devflow `:stage` while the run has an
  active stage root."
  ([feature]
   (start! feature {}))
  ([feature opts]
   ;; keyword opt values (e.g. :worktree-check :required) are coerced to strings
   ;; so they survive JSON round-tripping in workflow/context; stage
   ;; constructors read them back through `name`, which accepts strings
   ;; unchanged
   (let [context (reduce-kv (fn [m k v] (assoc m k (if (keyword? v) (name v) v)))
                            {:feature feature}
                            opts)]
     (add-current-stage
      feature
      (workflow/start!
       feature
       (intake-workflow context)
       {:feature feature}
       {:family "devflow"
        :definition 'ct.spools.devflow/intake-workflow
        ;; seed start opts into context so they survive intake revision loops
        ;; rather than resetting to their defaults
        :context context})))))

(defn current-root
  "Return the feature's single active devflow stage root, or nil when the run has
  none (see `skein.spools.workflow/current-root`). Throws when ambiguous."
  [feature]
  (workflow/current-root feature))

(defn ready
  "Return agent-facing ready devflow steps for `feature`, each carrying `:stage`."
  [feature]
  (let [stage (root-stage feature)]
    (mapv (partial add-stage stage) (workflow/ready feature))))

(defn ready-step
  "Return the single agent-facing ready devflow step for `feature`, or fail if ambiguous."
  [feature]
  (add-stage (root-stage feature) (workflow/ready-step feature)))

(defn choice-details
  "Return choice explanations for the current devflow checkpoint.

  opts may include `:step` (materialized strand id) to select among multiple
  ready checkpoints."
  ([feature]
   (choice-details feature {}))
  ([feature opts]
   (workflow/choice-details feature opts)))

(defn choice-detail
  "Return one choice explanation for the current devflow checkpoint.

  opts may include `:step` (materialized strand id) to select among multiple
  ready checkpoints."
  ([feature choice]
   (choice-detail feature choice {}))
  ([feature choice opts]
   (workflow/choice-detail feature choice opts)))

(defn complete!
  "Close the current devflow step for `feature` and return the engine
  `{:ready [step-view ...] :done boolean}` result shape.

  opts may include `:step`, `:notes`, and `:attributes`; see
  `skein.spools.workflow/complete!`."
  ([feature]
   (complete! feature {}))
  ([feature opts]
   (add-current-stage feature (workflow/complete! feature opts))))

(defn- keywordize-choice-input
  "Return choice input with top-level string keys converted to keywords."
  [input]
  (if-not (map? input)
    input
    (into {}
          (map (fn [[k v]] [(if (string? k) (keyword k) k) v]))
          input)))

(defn choose!
  "Record a devflow checkpoint choice and return the engine
  `{:ready [step-view ...] :done boolean}` result shape.

  opts may include `:step`; see `skein.spools.workflow/choose!`."
  ([feature choice]
   (add-current-stage feature (workflow/choose! feature choice)))
  ([feature choice input]
   (add-current-stage feature (workflow/choose! feature choice (keywordize-choice-input input))))
  ([feature choice input opts]
   (add-current-stage feature (workflow/choose! feature choice (keywordize-choice-input input) opts))))

(defn advance!
  "Advance the current devflow step or checkpoint for `feature`.

  Delegates to `skein.spools.workflow/advance!` and adds the active devflow
  `:stage` to returned ready step views. opts may include `:choice`, `:input`,
  `:notes`, `:step`, `:by`, and `:attributes`."
  ([feature]
   (advance! feature {}))
  ([feature opts]
   (add-current-stage feature (workflow/advance! feature opts))))

(def stage-workflows
  "Devflow stage constructors registered with the engine under stable routing
  names. Forward `:next` choices reference these keyword names; `register-workflows!`
  registers each with `skein.spools.workflow/register-workflow!`."
  {:intake 'ct.spools.devflow/intake-workflow
   :proposal 'ct.spools.devflow/proposal-workflow
   :spec-plan 'ct.spools.devflow/spec-plan-workflow
   :route-after-plan 'ct.spools.devflow/route-after-plan-workflow
   :tasks 'ct.spools.devflow/task-breakdown-workflow
   :run-afk-loop 'ct.spools.devflow/run-afk-loop-workflow
   :direct-implementation 'ct.spools.devflow/direct-implementation-workflow
   :agent-review 'ct.spools.devflow/agent-review-workflow
   :abort 'ct.spools.devflow/abort-workflow})

(def workflow-registry
  "Workflow constructors exposed by the devflow spool: the engine-registered
  stage constructors (see `stage-workflows`) plus `:cycle`, the ordered
  composable stage list."
  (assoc stage-workflows :cycle 'ct.spools.devflow/devflow-cycle))

(def ^:private describe-placeholder-params
  "Placeholder params used to render stage titles when describing devflow workflow
  shapes. A description reports structure, not a specific run, so `:feature` (and
  the abort/review stages' `:reason`/`:artifact`) are stand-in strings."
  {:feature "<feature>" :reason "<reason>" :artifact "<artifact>"})

(defn describe
  "Return the compile-time shape of a devflow stage, or of the whole cycle.

  With no argument, returns a vector describing every stage in `devflow-cycle`, in
  order. With a registered stage key (a key of `stage-workflows`, e.g.
  `:proposal`), returns that one stage's description. Shapes come from
  `skein.spools.workflow/describe`; titles render against placeholder params
  because a description is run-independent. Fails loudly on an unknown stage key."
  ([]
   (mapv #(workflow/describe % describe-placeholder-params)
         (devflow-cycle describe-placeholder-params)))
  ([stage]
   (let [sym (or (get stage-workflows stage)
                 (throw (ex-info "Unknown devflow stage"
                                 {:stage stage :stages (vec (keys stage-workflows))})))]
     (workflow/describe ((requiring-resolve sym) describe-placeholder-params)
                        describe-placeholder-params))))

(defn guidance
  "Return devflow authoring guidance as inspectable data.

  With no argument, returns the workspace overview: layout, paths, invariants,
  the document-ID convention, document ownership, and an index of guide keys.
  With a guide key (keyword or string, e.g. `:proposal`), returns that
  artifact's guide: purpose, prerequisites, knowledge, procedures, constraints,
  validation checklist, and templates. Ready step views advertise their guide
  key as `:guide`; unknown keys fail loudly."
  ([]
   (guidance/overview))
  ([guide]
   (guidance/guide (if (string? guide) (keyword guide) guide))))

(defn run-history
  "Return the ordered run history for devflow `feature` (see
  `skein.spools.workflow/run-history`), each molecule's `:root` carrying the
  devflow `:stage` it was poured for.

  Stage is devflow's own vocabulary, so this projection owns it: the engine's
  history reports only engine-owned root fields."
  [feature]
  (let [rt (current/runtime)]
    (mapv (fn [{:keys [root] :as molecule}]
            (let [attrs (:attributes (weaver/show rt (:id root)))
                  stage (or (get attrs :devflow/stage) (get attrs "devflow/stage"))]
              (cond-> molecule
                stage (assoc-in [:root :stage] stage))))
          (workflow/run-history feature))))

(defn squash-run!
  "Squash a finished devflow `feature`'s run into one closed digest strand (see
  `skein.spools.workflow/squash-run!`). Fails loudly if the feature still has an
  active root. opts may include `:title` and `:attributes`.

  This closes out the graph only. The workspace side of finishing a feature —
  spec promotion, plan status, and moving the feature folder into
  `devflow/archive/` — is a separate devflow procedure: follow
  `(guidance :finish-archive)`."
  ([feature]
   (workflow/squash-run! feature))
  ([feature opts]
   (workflow/squash-run! feature opts)))

(defn register-workflows!
  "Register every devflow stage constructor with the engine's weaver-lifetime
  workflow registry under its stable name (see `stage-workflows`).

  Idempotent: duplicate names replace, so a reload re-points in-flight runs'
  named `:next` routes at the reloaded constructors. Called on namespace load
  (for REPL use) and from `install!` (startup config); returns the registered
  name -> constructor map."
  []
  (into {}
        (map (fn [[name sym]] [name (workflow/register-workflow! name sym)]))
        stage-workflows))

;; Register on namespace load so a live `weaver repl` require resolves named
;; routes before `install!` runs; startup config re-registers via `install!`.
(register-workflows!)

(def command-registry
  "Agent-facing commands exposed by the devflow spool."
  {:start 'ct.spools.devflow/start!
   :ready-step 'ct.spools.devflow/ready-step
   :ready 'ct.spools.devflow/ready
   :choice-details 'ct.spools.devflow/choice-details
   :choice-detail 'ct.spools.devflow/choice-detail
   :choose 'ct.spools.devflow/choose!
   :complete 'ct.spools.devflow/complete!
   :advance 'ct.spools.devflow/advance!
   :describe 'ct.spools.devflow/describe
   :guidance 'ct.spools.devflow/guidance
   :run-history 'ct.spools.devflow/run-history
   :squash-run 'ct.spools.devflow/squash-run!})

(defn workflows
  "Return devflow workflow constructors by stable key."
  []
  workflow-registry)

(defn commands
  "Return agent-facing devflow commands by stable key."
  []
  command-registry)

(defn install!
  "Return installation metadata for the devflow workflow spool.

  Re-registers the stage constructors with the engine registry (see
  `register-workflows!`) so named `:next` routes resolve after a startup or
  reload."
  []
  {:installed true
   :namespace 'ct.spools.devflow
   :dependency-sentinel (dependency-sentinel)
   :commands command-registry
   :workflows workflow-registry
   :registered (register-workflows!)})
