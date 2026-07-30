(ns ct.spools.devflow
  "Clojure-native workflow definitions for the devflow lifecycle.

  Every stage is a static `defworkflow` Var: a definition a worker can read
  through `strand workflow show <name>` before starting a run, with its param
  contract owned by a spec rather than by a constructor's argument list
  (PROP-Wcd-001.S12). The definitions are ordinary workflow data that callers
  can inspect, compose, pour as molecules, or materialize as wisps.

  Authoring knowledge for the artifacts each stage produces (proposal, specs,
  plan, task queue, ...) lives in `ct.spools.devflow.guidance` and is served
  by `guidance`; steps advertise their guide key via the `devflow/guide`
  attribute and ready step views surface it as `:guide`."
  (:require [camel-snake-kebab.core :as csk]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.devflow.guidance :as guidance]
            [skein.api.current.alpha :as current]
            [skein.api.spool.alpha :as spool]
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

(def stages
  "Every stage name a devflow root may carry in its `devflow/stage` attribute.

  Stage is devflow's own vocabulary rather than an engine field, so this set is
  the enum the projections check a root against: `stage-attributes` is the only
  writer and `active-stage`/`run-history` are the readers. Names are routing-
  independent, so they need not match the `stage-workflows` keys."
  #{"intake" "proposal" "land-proposal" "decompose" "card-review" "spec-plan"
    "route-after-plan" "tasks" "afk" "implementation" "abort"})

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

(defn- stage-attributes
  "Root attributes every devflow stage workflow carries: its workflow family,
  the stage it was poured for, and the feature it runs against. Fails loudly on
  an unregistered stage name so a definition cannot mint a value the projections
  will later reject."
  [stage]
  (when-not (stages stage)
    (throw (ex-info "Unknown devflow stage name"
                    {:stage stage :stages (vec (sort stages))})))
  {"workflow/family" "devflow"
   "devflow/stage" stage
   "devflow/feature" (param-value :feature)})

(defn- task-value
  "Return task field `k`, accepting keyword or string keyed task maps."
  [task k]
  (or (get task k) (get task (name k))))

(def ^:private loop-item-id-pattern
  "Loop item ids become workflow step ids, so they must be token-safe: no
  whitespace, slashes, colons, or leading punctuation."
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

;; Param contracts. Each stage names one whole-map spec the engine validates
;; before anything compiles or pours, so a bad param map fails at the
;; boundary with the spec's own explanation rather than part-way through a
;; stage. Task maps stay keyword- or string-keyed (`task-value`), which is why
;; their shape is predicates over that reader rather than `s/keys`.
(s/def ::feature non-blank-string?)
(s/def ::revision boolean?)
(s/def ::worktree-check #{"required" "already-in-worktree-ok"})
(s/def ::artifact non-blank-string?)
(s/def ::reason non-blank-string?)
(s/def ::delegate-harness non-blank-string?)
(s/def ::delegate-cwd non-blank-string?)
(s/def ::delegate-preamble non-blank-string?)
(s/def ::feature-card-reviewer non-blank-string?)
(s/def ::epic-card-reviewer non-blank-string?)
(s/def ::review-cwd non-blank-string?)

(defn- workflow-step-id? [v]
  (and (non-blank-string? v) (some? (re-matches loop-item-id-pattern v))))

(defn- optional-non-blank? [v]
  (or (nil? v) (non-blank-string? v)))

(s/def ::afk-task
  (s/and map?
         #(workflow-step-id? (task-value % :id))
         #(non-blank-string? (task-value % :title))
         #(optional-non-blank? (task-value % :body))
         #(optional-non-blank? (task-value % :harness))))

(defn- distinct-task-ids?
  "AFK task ids become workflow step ids, so a duplicate would collide."
  [tasks]
  (let [ids (map #(task-value % :id) tasks)]
    (= (count ids) (count (distinct ids)))))

(s/def ::tasks
  (s/and (s/coll-of ::afk-task :kind vector? :min-count 1) distinct-task-ids?))

(defn- card-value
  "Return card-ref field `k`, accepting keyword or string keyed maps."
  [card k]
  (or (get card k) (get card (name k))))

(s/def ::review-card
  (s/and map?
         #(workflow-step-id? (card-value % :id))
         #(non-blank-string? (card-value % :title))))

(defn- distinct-card-ids? [cards]
  (let [ids (map #(card-value % :id) cards)]
    (= (count ids) (count (distinct ids)))))

(s/def ::feature-cards
  (s/and (s/coll-of ::review-card :kind vector? :min-count 1) distinct-card-ids?))
(s/def ::epic-card ::review-card)

(defn- epic-distinct-from-features?
  [{:keys [epic-card feature-cards]}]
  (not (contains? (set (map #(card-value % :id) feature-cards))
                  (card-value epic-card :id))))

(defn- harnesses-resolve?
  "Every delegated task names a harness, or inherits the stage's default one."
  [{:keys [tasks delegate-harness]}]
  (every? #(non-blank-string? (or (task-value % :harness) delegate-harness)) tasks))

(s/def ::intake-params
  (s/keys :req-un [::feature]
          :opt-un [::worktree-check ::revision ::feature-card-reviewer
                   ::epic-card-reviewer ::review-cwd]))
(s/def ::agent-review-params (s/keys :req-un [::feature ::artifact]))
(s/def ::proposal-params (s/keys :req-un [::feature] :opt-un [::revision]))
(s/def ::land-proposal-params
  (s/keys :req-un [::feature ::feature-card-reviewer ::epic-card-reviewer]
          :opt-un [::review-cwd]))
(s/def ::decompose-params
  (s/keys :req-un [::feature ::feature-card-reviewer ::epic-card-reviewer]
          :opt-un [::review-cwd]))
(s/def ::card-set-input
  (s/and (s/keys :req-un [::epic-card ::feature-cards])
         epic-distinct-from-features?))
(s/def ::review-cards-params
  (s/and (s/keys :req-un [::feature ::feature-card-reviewer ::epic-card-reviewer
                          ::epic-card ::feature-cards]
                 :opt-un [::review-cwd ::revision])
         epic-distinct-from-features?))
(s/def ::spec-plan-params (s/keys :req-un [::feature] :opt-un [::revision]))
(s/def ::route-after-plan-params (s/keys :req-un [::feature]))
(s/def ::tasks-params (s/keys :req-un [::feature] :opt-un [::revision]))
(s/def ::run-afk-loop-params
  (s/keys :req-un [::feature]
          :opt-un [::tasks ::delegate-harness ::delegate-cwd ::delegate-preamble]))
(s/def ::run-afk-manual-params (s/keys :req-un [::feature]))
(s/def ::run-afk-delegated-params
  (s/and (s/keys :req-un [::feature ::tasks]
                 :opt-un [::delegate-harness ::delegate-cwd ::delegate-preamble ::revision])
         harnesses-resolve?))
(s/def ::direct-implementation-params (s/keys :req-un [::feature] :opt-un [::revision]))
(s/def ::abort-params (s/keys :req-un [::feature ::reason]))

;; Choice input contracts: the whole map `choose!` must accept, resolved live
;; at the checkpoint rather than baked in when the stage poured.
(s/def ::abort-reason-input (s/keys :req-un [::reason]))
(s/def ::afk-queue-input (s/keys :opt-un [::tasks]))

(defn- afk-task-prompt [feature task delegate-preamble]
  (str (when (non-blank-string? delegate-preamble)
         (str delegate-preamble "\n\n"))
       "Devflow AFK task for " feature ": " (task-value task :title) "\n\n"
       (or (task-value task :body) (task-value task :title))))

(defn- afk-task-gate
  "The per-task subagent gate the delegated AFK stage expands one of per task.

  Every value renders from resolved params, which is what lets the stage be a
  static definition: nothing here is decided when the definition is written.
  `agent-run/cwd` is always declared and renders nil when no `:delegate-cwd`
  was supplied, which the subagent executor reads exactly as an absent cwd."
  []
  (workflow/gate :task
                 (fn [{:keys [feature item]}]
                   (str "Delegate AFK task " (task-value item :id) " for " feature))
                 :subagent
                 :loop {:each :tasks :chain true}
                 :attributes {"devflow/task" (fn [{:keys [item]}] (task-value item :id))
                              "agent-run/harness" (fn [{:keys [item delegate-harness]}]
                                                    (or (task-value item :harness) delegate-harness))
                              "agent-run/cwd" (param-value :delegate-cwd)
                              "agent-run/prompt" (fn [{:keys [feature item delegate-preamble]}]
                                                   (afk-task-prompt feature item delegate-preamble))}))

(defn- feature-card-review-prompt
  "Render the focused review prompt for one feature card."
  [{:keys [feature item]}]
  (str "Review one implementation feature card for " feature " as a focused, read-only "
       "reviewer. Use the workspace's card system to inspect card "
       (card-value item :id) " (" (card-value item :title) ") and read the merged, approved "
       "proposal it implements.\n\nJudge only this card's cold-work contract: current-state "
       "evidence, target outcome, constraints, proposal traceability, explicit done-when, "
       "validation gates, landing discipline, and whether its direct dependencies let it land "
       "independently. Do not redesign the epic or repeat set-wide coverage analysis; a separate "
       "epic reviewer owns relationships across cards. Do not edit cards.\n\nReturn `VERDICT: pass` "
       "or `VERDICT: revise`, followed by concrete findings ordered by severity. Say plainly "
       "when the card passes."))

(defn- epic-card-review-prompt
  "Render the set-level review prompt after every focused card review fans in."
  [{:keys [feature epic-card feature-cards]}]
  (str "Review the implementation-card decomposition for " feature " as the set-level, "
       "read-only epic reviewer. Focused reviewers have already reviewed each feature card's "
       "cold-work contract; do not repeat that fine-grained work.\n\nUse the workspace's card "
       "system to inspect epic " (card-value epic-card :id) " (" (card-value epic-card :title)
       ") and these feature cards:\n"
       (str/join "\n" (map #(str "- " (card-value % :id) ": " (card-value % :title))
                            feature-cards))
       "\n\nReview only the connections and whole-epic shape: complete proposal-goal coverage, "
       "gaps and overlaps, outcome-oriented slicing, independently landable increments, "
       "dependency-edge direction and necessity, integration seams, and open decisions that "
       "would otherwise be decided inconsistently by cold workers. Do not edit cards.\n\nReturn "
       "`VERDICT: pass` or `VERDICT: revise`, followed by concrete set-level findings ordered "
       "by severity. Say plainly when the decomposition is cohesive."))

(defn- feature-card-review-gate
  "The parallel subagent gate expanded once per authored feature card."
  []
  (workflow/gate :feature-card-review
                 (fn [{:keys [item]}]
                   (str "Focused review of feature card " (card-value item :id) ": "
                        (card-value item :title)))
                 :subagent
                 :loop {:each :feature-cards}
                 :attributes {"devflow/review" "agent"
                              "devflow/review-scope" "feature-card"
                              "devflow/card" (fn [{:keys [item]}] (card-value item :id))
                              "agent-run/harness" (param-value :feature-card-reviewer)
                              "agent-run/cwd" (param-value :review-cwd)
                              "agent-run/prompt" feature-card-review-prompt
                              "workflow/instruction" (str "Executor-owned focused card review. "
                                                          "The configured feature-card reviewer "
                                                          "must inspect exactly this card and return "
                                                          "its verdict; parallel sibling gates review "
                                                          "the other feature cards.")}))

(def ^:private abort-reason-input
  "Declared choice input for every abort choice: a required `:reason` recorded on
  the abort step and surfaced with the choice (workflow.md §5). `choose!` fails
  loudly before any mutation when it is omitted."
  {:spec ::abort-reason-input
   :doc "Why the feature is being aborted; recorded on the abort step."})

(workflow/defworkflow intake
  "The mandatory brief intake stage.

  The first strand is a `:human` checkpoint that requires worktree creation
  before substantive discovery. `:worktree-check` may be `\"required\"` for a
  fresh brief or `\"already-in-worktree-ok\"` for agents launched directly inside
  the feature worktree. On a revision round (`:revision true`), the worktree
  checkpoint is skipped because it was already satisfied on the first pass;
  F4's splice reattaches `:capture-brief` as the entry step."
  {:entrypoints #{:start}
   :param-spec ::intake-params
   :defaults {:worktree-check "required" :revision false}}
  (workflow/workflow
    (titled "Devflow intake: ")
    {:attributes (assoc (stage-attributes "intake")
                        "devflow/worktree-check" (param-value :worktree-check))}
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

(workflow/defworkflow agent-review
  "A reusable one-step agent review procedure, spliced into a stage by `call`."
  {:entrypoints #{:call}
   :param-spec ::agent-review-params
   :defaults {}}
  (workflow/workflow
    (fn [{:keys [feature artifact]}]
      (str "Agent review: " feature " " artifact))
    (workflow/step :review
                   (fn [{:keys [feature artifact]}]
                     (str "Run agent review for " feature " " artifact))
                   :self
                   :attributes {"devflow/review" "agent"})))

(workflow/defworkflow proposal
  "The proposal gate stage.

  This encodes: inspect RFCs/spikes/specs first, write proposal, run agent
  review, then stop for human sign-off. On a revision round (`:revision true`),
  `:inspect-context` is skipped because orientation was done on the first pass;
  F4's splice reattaches `:write-proposal` as the entry step.

  Revision rounds are the proposal's whole editing window. Sign-off freezes the
  document as the intent that was agreed; later divergence is recorded in the
  spec deltas and plan, so no downstream stage edits it back into agreement
  with what was built."
  {:entrypoints #{:continue :call}
   :param-spec ::proposal-params
   :defaults {:revision false}}
  (workflow/workflow
    (titled "Devflow proposal: ")
    {:attributes (stage-attributes "proposal")}
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
                   :agent-review
                   {:artifact "proposal"}
                   :title (titled "Complete agent review for " " proposal")
                   :depends-on [:write-proposal])
    (workflow/checkpoint :human-signoff-proposal
                         (titled "Human sign-off for " " proposal")
                         :depends-on [:agent-review-proposal]
                         :kind :human
                         :choices [{:key :approved
                                    :label "Approve"
                                    :description "Proposal is accepted and frozen as the agreed intent; mark it Approved and continue to spec and plan work."
                                    :next :spec-plan}
                                   {:key :approved-to-cards
                                    :label "Approve to cards"
                                    :description (str "Proposal is accepted and frozen as the agreed intent; "
                                                      "land it on mainline, decompose it into implementation "
                                                      "cards, and end the run there. Implementation belongs "
                                                      "to the card loop, not to this run.")
                                    ;; routed by definition symbol, not registered name: a new
                                    ;; registry-name reference from an already-published definition
                                    ;; retroactively grows the registration set that definition
                                    ;; demands, breaking every consumer's existing direct-registration
                                    ;; set (v12's frozen suite catches exactly this). A symbol target
                                    ;; keeps the accreted choice self-contained.
                                    :next 'ct.spools.devflow/land-proposal}
                                   {:key :revise
                                    :label "Revise"
                                    :description "Proposal needs changes; revise the proposal stage and re-review before proceeding. Revision rounds are the only time the proposal is rewritten."
                                    :revise {:params {:revision true}}}
                                   {:key :abort
                                    :label "Abort"
                                    :description "Stop this feature intentionally. Do not proceed to spec or plan work."
                                    :next :abort
                                    :input abort-reason-input}]
                         :attributes {"workflow/decision-point" "proposal-signed-off"
                                      "workflow/instruction" (str "Approval freezes the proposal: set its Status to Approved with "
                                                                  "the sign-off date and make no further content edits. Later "
                                                                  "divergence belongs in the spec deltas and plan, not in a "
                                                                  "rewritten proposal. Choose revise while the document still "
                                                                  "needs to change.")})))

(workflow/defworkflow land-proposal
  "The proposal landing stage on the cards route.

  Reached by the sign-off's `:approved-to-cards` choice. Devflow's job on this
  route ends at \"approved proposal on mainline plus implementation cards
  authored\", so the frozen proposal must land before decomposition reads it.
  The merge is an external wait-point rather than driving-agent work: the gate
  stays repo-agnostic — any mainline merge process counts — and `complete!`
  records who landed it through `:by`. The follow-up `:agent` checkpoint then
  routes to the decompose stage, or aborts a feature whose proposal will not
  land."
  {:entrypoints #{:continue :call}
   :param-spec ::land-proposal-params
   :defaults {}}
  (workflow/workflow
    (titled "Devflow land proposal: ")
    {:attributes (stage-attributes "land-proposal")}
    (workflow/gate :merge-proposal
                   (titled "Land approved proposal for " " on mainline")
                   :human
                   :attributes {"workflow/action-ref" "devflow.proposal.land"
                                "workflow/instruction" (str "Merge the approved proposal to the repository "
                                                            "mainline through the workspace's own landing "
                                                            "process, then complete this gate with :by "
                                                            "recording who merged it.")})
    (workflow/checkpoint :confirm-proposal-landed
                         (titled "Confirm the proposal landed for ")
                         :depends-on [:merge-proposal]
                         :kind :agent
                         :choices [{:key :landed
                                    :label "Landed"
                                    :description "The approved proposal is merged on mainline; decompose it into implementation cards next."
                                    :next :decompose}
                                   {:key :abort
                                    :label "Abort"
                                    :description "Stop this feature; its approved proposal will not land on mainline."
                                    :next :abort
                                    :input abort-reason-input}]
                         :attributes {"workflow/decision-point" "proposal-landed"})))

(workflow/defworkflow decompose
  "Author implementation cards, then hand their refs to the review stage.

  Workflow loops expand when a stage pours, before `:author-cards` has created
  anything. The agent checkpoint after authoring is therefore the explicit data
  boundary: its `:review` choice supplies the epic and feature card refs that
  the continuation fans out over. Reviewer seats are caller-selected params,
  and cards remain in the workspace's own card system."
  {:entrypoints #{:continue :call}
   :param-spec ::decompose-params
   :defaults {}}
  (workflow/workflow
    (titled "Devflow decompose: ")
    {:attributes (stage-attributes "decompose")}
    (workflow/step :author-cards
                   (titled "Author implementation cards for ")
                   :self
                   :attributes {"workflow/action-ref" "devflow.decompose.cards"
                                "devflow/guide" "decompose"
                                "workflow/instruction" (str "Author one epic and self-contained "
                                                            "feature cards from the merged proposal. Call "
                                                            "(ct.spools.devflow/guidance :decompose) for "
                                                            "the cold-card and review handoff contracts.")})
    (workflow/checkpoint :handoff-card-review
                         (titled "Hand authored cards to review for ")
                         :depends-on [:author-cards]
                         :kind :agent
                         :choices [{:key :review
                                    :label "Review cards"
                                    :description "Supply the authored epic and feature card refs; fan focused reviews out before the epic cohesion review."
                                    ;; Like proposal's later-added cards route, this
                                    ;; continuation is accreted onto an already-published
                                    ;; definition. A symbol keeps old direct-registration
                                    ;; sets able to register :decompose; the new review
                                    ;; definition remains published and discoverable itself.
                                    :next 'ct.spools.devflow/review-cards
                                    :input {:spec ::card-set-input
                                            :doc "The authored epic card and non-empty feature-card vector; each ref requires token-safe id and title."}}
                                   {:key :abort
                                    :label "Abort"
                                    :description "Stop this feature because a reviewable implementation-card set could not be authored."
                                    :next :abort
                                    :input abort-reason-input}]
                         :attributes {"workflow/decision-point" "implementation-cards-authored"
                                      "workflow/instruction" (str "After authoring the cards, choose review "
                                                                  "with the epic card and every feature-card "
                                                                  "ref. The review stage uses the configured "
                                                                  "feature-card-reviewer and epic-card-reviewer "
                                                                  "seats.")})))

(workflow/defworkflow review-cards
  "Review authored implementation cards at focused and whole-epic scopes.

  The feature-card gate expands without a chain, so every focused review is
  ready together and the subagent executor may run them up to its fan-out
  ceiling. The epic gate depends on the loop's base id, which fans in over all
  focused reviews, and its prompt deliberately judges only cross-card cohesion.
  The driving agent then reconciles both result classes. Material changes may
  choose `:review-again`, re-pouring this stage with the current card refs."
  {:entrypoints #{:continue :call}
   :param-spec ::review-cards-params
   :defaults {:revision false}}
  (workflow/workflow
    (titled "Devflow card review: ")
    {:attributes (stage-attributes "card-review")}
    (feature-card-review-gate)
    (workflow/gate :epic-card-review
                   (fn [{:keys [epic-card]}]
                     (str "Cohesion review of epic card " (card-value epic-card :id) ": "
                          (card-value epic-card :title)))
                   :subagent
                   :depends-on [:feature-card-review]
                   :attributes {"devflow/review" "agent"
                                "devflow/review-scope" "epic"
                                "devflow/card" (fn [{:keys [epic-card]}]
                                                 (card-value epic-card :id))
                                "agent-run/harness" (param-value :epic-card-reviewer)
                                "agent-run/cwd" (param-value :review-cwd)
                                "agent-run/prompt" epic-card-review-prompt
                                "workflow/instruction" (str "Executor-owned epic cohesion review. "
                                                            "It starts only after every focused "
                                                            "feature-card review closes and must not "
                                                            "repeat those per-card checks.")})
    (workflow/step :reconcile-card-reviews
                   (titled "Reconcile implementation-card reviews for ")
                   :self
                   :depends-on [:epic-card-review]
                   :attributes {"workflow/action-ref" "devflow.decompose.reconcile-reviews"
                                "devflow/guide" "decompose"
                                "workflow/instruction" (str "Read agent-run/result from every closed "
                                                            "feature-card-review-* gate and from the "
                                                            "epic-card-review gate. Apply valid focused "
                                                            "findings to their cards and valid cohesion "
                                                            "findings to card slicing or dependency edges. "
                                                            "Do not collapse the two review scopes. If any "
                                                            "material card changed, choose review-again and "
                                                            "supply the current full card set.")})
    (workflow/checkpoint :card-review-verdict
                         (titled "Decide whether implementation cards are reviewed for ")
                         :depends-on [:reconcile-card-reviews]
                         :kind :agent
                         :choices [{:key :accepted
                                    :label "Accept reviewed cards"
                                    :description "The focused and epic findings are resolved; end devflow and leave implementation to the card loop."}
                                   {:key :review-again
                                    :label "Review again"
                                    :description "Cards changed materially while reconciling findings; fan out a fresh review round over the current set."
                                    :input {:spec ::card-set-input
                                            :doc "Resupply the current epic and complete feature-card refs for the next review round."}
                                    :revise {:params {:revision true}}}
                                   {:key :abort
                                    :label "Abort"
                                    :description "Stop this feature because the implementation-card decomposition cannot be made reviewable."
                                    :next :abort
                                    :input abort-reason-input}]
                         :attributes {"workflow/decision-point" "implementation-cards-reviewed"})))

(workflow/defworkflow route-after-plan
  "The post-plan route-choice stage."
  {:entrypoints #{:continue :call}
   :param-spec ::route-after-plan-params
   :defaults {}}
  (workflow/workflow
    (titled "Devflow route after plan: ")
    {:attributes (stage-attributes "route-after-plan")}
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

(workflow/defworkflow spec-plan
  "The spec-delta and plan gate stage.

  After review and human sign-off, approval routes to the task/direct
  implementation decision workflow. A revision round (`:revision true`) re-runs
  the whole spec/plan stage."
  {:entrypoints #{:continue :call}
   :param-spec ::spec-plan-params
   :defaults {:revision false}}
  (workflow/workflow
    (titled "Devflow spec and plan: ")
    {:attributes (stage-attributes "spec-plan")}
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
                   :agent-review
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

(workflow/defworkflow run-afk-loop
  "The post-task-signoff AFK execution stage: choose how the queue runs.

  The old constructor decided this invisibly, by whether a `:tasks` opt was
  supplied. A checkpoint names the decision instead, so each way of running the
  queue is a continuation a worker can discover and read before choosing it
  (PROP-Wcd-001.EX6). Delegation carries the queue forward in `:tasks`, so the
  delegated route is only honest when one is present."
  {:entrypoints #{:continue :call}
   :param-spec ::run-afk-loop-params
   :defaults {}}
  (workflow/workflow
    (titled "Devflow AFK execution: ")
    {:attributes (stage-attributes "afk")}
    (workflow/checkpoint :choose-afk-execution
                         (titled "Choose how the AFK task queue runs for ")
                         :kind :human
                         :choices [{:key :manual
                                    :label "Run manually"
                                    :description "Run or hand off the AFK task loop in this worker."
                                    :next :run-afk-manual}
                                   {:key :delegate
                                    :label "Delegate"
                                    :description "Run the approved task queue as sequential subagent gates."
                                    :next :run-afk-delegated}
                                   {:key :abort
                                    :label "Abort"
                                    :description "Stop or abandon this feature before AFK execution."
                                    :next :abort
                                    :input abort-reason-input}]
                         :attributes {"workflow/decision-point" "afk-execution-mode"})))

(workflow/defworkflow run-afk-manual
  "Run or hand off the AFK task loop in the current worker."
  {:entrypoints #{:continue :call}
   :param-spec ::run-afk-manual-params
   :defaults {}}
  (workflow/workflow
    (titled "Devflow AFK manual execution: ")
    {:attributes (stage-attributes "afk")}
    (workflow/step :run-afk-loop
                   (titled "Run or hand off AFK task loop for ")
                   :self
                   :attributes {"workflow/action-ref" "devflow.tasks.run-afk-loop"
                                "devflow/guide" "afk"
                                "workflow/instruction" "Run or hand off the devflow AFK task loop for this feature after task sign-off. Call (ct.spools.devflow/guidance :afk) for the loop contract and queue checks."})))

(workflow/defworkflow run-afk-delegated
  "Run the approved AFK task queue as sequential subagent gates.

  One gate per task, chained, then a `:human` acceptance checkpoint. Task maps
  may be keyword- or string-keyed; `::run-afk-delegated-params` judges the whole
  queue — including that every task resolves a harness — before anything pours."
  {:entrypoints #{:continue :call}
   :param-spec ::run-afk-delegated-params
   :defaults {:revision false}}
  (workflow/workflow
    (titled "Devflow AFK delegated execution: ")
    {:attributes (stage-attributes "afk")}
    (afk-task-gate)
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
                         :attributes {"workflow/decision-point" "afk-accepted"})))

(workflow/defworkflow tasks
  "The reviewed task queue stage.

  A revision round (`:revision true`) re-runs the whole task-breakdown stage."
  {:entrypoints #{:continue :call}
   :param-spec ::tasks-params
   :defaults {:revision false}}
  (workflow/workflow
    (titled "Devflow task breakdown: ")
    {:attributes (stage-attributes "tasks")}
    (workflow/step :write-tasks
                   (titled "Write AFK/HITL task queue for ")
                   :self
                   :attributes (guided-artifact "tasks/index.yml"))
    (workflow/call :agent-review-tasks
                   :agent-review
                   {:artifact "task queue"}
                   :title (titled "Complete agent review for " " task queue")
                   :depends-on [:write-tasks])
    (workflow/checkpoint :human-signoff-tasks
                         (titled "Human sign-off for " " task queue")
                         :depends-on [:agent-review-tasks]
                         :kind :human
                         :choices [{:key :approved
                                    :label "Approve"
                                    :description "Task queue is accepted; choose how the AFK loop runs next."
                                    :next :run-afk-loop
                                    :input {:spec ::afk-queue-input
                                            :doc "Optional vector of AFK task maps to delegate as sequential subagent gates."}}
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

(workflow/defworkflow direct-implementation
  "The post-plan direct implementation stage for small, settled changes.

  A revision round (`:revision true`) re-runs the whole implementation stage."
  {:entrypoints #{:continue :call}
   :param-spec ::direct-implementation-params
   :defaults {:revision false}}
  (workflow/workflow
    (titled "Devflow direct implementation: ")
    {:attributes (stage-attributes "implementation")}
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
                   :agent-review
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

(workflow/defworkflow abort
  "A tiny stage that records intentional feature abortion."
  {:entrypoints #{:continue :call}
   :param-spec ::abort-params
   :defaults {}}
  (workflow/workflow
    (titled "Abort devflow feature: ")
    {:attributes (stage-attributes "abort")}
    (workflow/step :record-abort
                   (fn [{:keys [feature reason]}]
                     (str "Record abort for " feature ": " reason))
                   :self
                   :attributes {"workflow/action-ref" "devflow.abort.record"
                                "workflow/instruction" "Record the abort reason in the feature plan or conversation summary, then stop the active workflow."})))

(def devflow-cycle
  "The ordered devflow stage definitions along the single-run path, as data.

  Pour the first, then let each stage's decision-point outcomes route to the
  next. Order is the path a single-run feature walks; `agent-review` is spliced
  into stages by `call` and `abort` is reachable from every checkpoint, so
  neither appears here. The cards route (`:approved-to-cards` →
  `land-proposal` → `decompose` → `review-cards`) branches off at proposal sign-off and is not
  part of this vector: its stages are described individually through
  `describe` with their `stage-workflows` keys."
  [intake proposal spec-plan route-after-plan tasks run-afk-loop
   direct-implementation])

;; The projection specs below own only the fields devflow adds to the engine's
;; views. Everything else — a step view's `:id`/`:title`/`:role`/`:choices`, a
;; history molecule's `:events` — is engine-owned passthrough from
;; `skein.spools.workflow`, specced there or not at all; devflow neither
;; restates nor re-checks it.
(s/def ::stage stages)
(s/def ::guide (set (keys guidance/guides)))
(s/def ::step-view (s/keys :req-un [::stage] :opt-un [::guide]))
(s/def ::ready (s/coll-of ::step-view :kind vector?))
(s/def ::root (s/keys :req-un [::stage]))
(s/def ::molecule (s/keys :req-un [::root]))
(s/def ::run-history (s/coll-of ::molecule :kind vector?))

(defn- active-stage
  "Return the stage devflow poured `feature`'s active root for.

  Fails loudly (TEN-003) when the run has no active root or that root carries no
  known `stages` member: stage is devflow's own vocabulary and every devflow root
  records it, so a run with ready work but no stage is unexpected state, not a
  view that may quietly ship without one. Ask only while work is ready."
  [runtime feature]
  (let [root (current/with-runtime runtime (workflow/current-root feature))
        stage (spool/attr-get root :devflow/stage)]
    (or (stages stage)
        (throw (ex-info "Devflow run has no active root carrying a known devflow/stage"
                        {:feature feature
                         :strand (:id root)
                         :stage stage
                         :attributes (:attributes root)
                         :stages (vec (sort stages))})))))

(defn- stage-view
  "Add the devflow stage and artifact guide key to one engine ready step view."
  [stage step]
  (let [guide (artifact-guides (:artifact step))]
    (cond-> (assoc step :stage stage)
      guide (assoc :guide guide))))

(defn- stage-views
  "Return engine ready step views as devflow views carrying `feature`'s active
  stage (shape: `:ct.spools.devflow/ready`)."
  [runtime feature steps]
  (if (seq steps)
    (let [stage (active-stage runtime feature)]
      (spool/require-valid! ::ready
                            (mapv (partial stage-view stage) steps)
                            "Devflow ready step views are invalid"))
    []))

(defn- stage-result
  "Add the feature's current stage to every ready step in a mutation result."
  [runtime feature result]
  (update result :ready #(stage-views runtime feature %)))

(defn start!
  "Start the devflow intake workflow for `feature` and return the engine
  `{:ready [step-view ...] :done boolean}` result shape.

  Each ready step view carries the current devflow `:stage` (shape:
  `:ct.spools.devflow/ready`)."
  ([runtime feature]
   (start! runtime feature {}))
  ([runtime feature opts]
   ;; keyword opt values (e.g. :worktree-check :required) are coerced to strings
   ;; so they survive JSON round-tripping in workflow/context, and because the
   ;; param specs name the string forms the engine will read back
   (let [context (reduce-kv (fn [m k v] (assoc m k (if (keyword? v) (name v) v)))
                            {:feature feature}
                            opts)]
     (stage-result
      runtime
      feature
      (current/with-runtime
        runtime
        (workflow/start!
         feature
         :intake
         context
         {:family "devflow"
          ;; seed start opts into context so they survive intake revision loops
          ;; rather than resetting to their defaults
          :context context}))))))

(defn current-root
  "Return the feature's single active devflow stage root, or nil when the run has
  none (see `skein.spools.workflow/current-root`). Throws when ambiguous."
  [runtime feature]
  (current/with-runtime runtime (workflow/current-root feature)))

(defn ready
  "Return agent-facing ready devflow steps for `feature`, each carrying `:stage`
  (shape: `:ct.spools.devflow/ready`)."
  [runtime feature]
  (stage-views runtime feature (current/with-runtime runtime (workflow/ready feature))))

(defn ready-step
  "Return the single agent-facing ready devflow step for `feature` (shape:
  `:ct.spools.devflow/step-view`), nil when none is ready, or fail if ambiguous."
  [runtime feature]
  (first (stage-views runtime feature
                      (some-> (current/with-runtime runtime (workflow/ready-step feature)) vector))))

(defn choice-details
  "Return choice explanations for the current devflow checkpoint.

  opts may include `:step` (materialized strand id) to select among multiple
  ready checkpoints."
  ([runtime feature]
   (choice-details runtime feature {}))
  ([runtime feature opts]
   (current/with-runtime runtime (workflow/choice-details feature opts))))

(defn choice-detail
  "Return one choice explanation for the current devflow checkpoint.

  opts may include `:step` (materialized strand id) to select among multiple
  ready checkpoints."
  ([runtime feature choice]
   (choice-detail runtime feature choice {}))
  ([runtime feature choice opts]
   (current/with-runtime runtime (workflow/choice-detail feature choice opts))))

(defn complete!
  "Close the current devflow step for `feature` and return the engine
  `{:ready [step-view ...] :done boolean}` result shape, its ready views carrying
  the devflow `:stage` (shape: `:ct.spools.devflow/ready`).

  opts may include `:step` and `:attributes`; see
  `skein.spools.workflow/complete!`. The engine records no outcome prose, so a
  stage's own outcome vocabulary rides `:attributes`."
  ([runtime feature]
   (complete! runtime feature {}))
  ([runtime feature opts]
   (stage-result runtime feature
                 (current/with-runtime runtime (workflow/complete! feature opts)))))

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
  `{:ready [step-view ...] :done boolean}` result shape, its ready views carrying
  the devflow `:stage` (shape: `:ct.spools.devflow/ready`).

  opts may include `:step`; see `skein.spools.workflow/choose!`."
  ([runtime feature choice]
   (stage-result runtime feature
                 (current/with-runtime runtime (workflow/choose! feature choice))))
  ([runtime feature choice input]
   (stage-result runtime feature
                 (current/with-runtime
                   runtime
                   (workflow/choose! feature choice (keywordize-choice-input input)))))
  ([runtime feature choice input opts]
   (stage-result runtime feature
                 (current/with-runtime
                   runtime
                   (workflow/choose! feature choice (keywordize-choice-input input) opts)))))

(defn advance!
  "Advance the current devflow step or checkpoint for `feature`.

  Delegates to `skein.spools.workflow/advance!` and adds the active devflow
  `:stage` to returned ready step views (shape: `:ct.spools.devflow/ready`).
  opts may include `:choice`, `:input`, `:step`, `:by`, and `:attributes`."
  ([runtime feature]
   (advance! runtime feature {}))
  ([runtime feature opts]
   (let [opts (cond-> opts
                (contains? opts :input) (update :input keywordize-choice-input))]
     (stage-result runtime feature
                   (current/with-runtime runtime (workflow/advance! feature opts))))))

(def stage-workflows
  "Devflow's stage definitions by the stable routing name each is registered
  under. Forward `:next` choices reference these keyword names, and every stage
  is discoverable through `strand workflow show <name>` once devflow is active.

  `defworkflow` collects the registry entries itself, so this map is the local
  read of the same set rather than the thing that publishes it."
  {:intake intake
   :proposal proposal
   :land-proposal land-proposal
   :decompose decompose
   :review-cards review-cards
   :spec-plan spec-plan
   :route-after-plan route-after-plan
   :tasks tasks
   :run-afk-loop run-afk-loop
   :run-afk-manual run-afk-manual
   :run-afk-delegated run-afk-delegated
   :direct-implementation direct-implementation
   :agent-review agent-review
   :abort abort})

(def ^:private describe-placeholder-params
  "Placeholder params used to render stage titles when describing devflow workflow
  shapes. A description reports structure, not a specific run, so these are
  stand-in values — and they must satisfy each stage's `:param-spec`, which is
  why the delegated AFK stage's queue and harness appear here too."
  {:feature "<feature>"
   :reason "<reason>"
   :artifact "<artifact>"
   :feature-card-reviewer "<feature-card-reviewer>"
   :epic-card-reviewer "<epic-card-reviewer>"
   :epic-card {:id "epic" :title "<epic>"}
   :feature-cards [{:id "card" :title "<feature-card>"}]
   :tasks [{:id "task" :title "<task>"}]
   :delegate-harness "<harness>"})

(defn describe
  "Return the compile-time shape of a devflow stage, or of the whole cycle.

  With no argument, returns a vector describing every stage in `devflow-cycle`, in
  order. With a registered stage key (a key of `stage-workflows`, e.g.
  `:proposal`), returns that one stage's description. Shapes come from
  `skein.spools.workflow/describe`; titles render against placeholder params
  because a description is run-independent. Fails loudly on an unknown stage key."
  ([]
   (mapv #(workflow/describe % describe-placeholder-params) devflow-cycle))
  ([stage]
   (let [definition (or (get stage-workflows stage)
                        (throw (ex-info "Unknown devflow stage"
                                        {:stage stage :stages (vec (keys stage-workflows))})))]
     (workflow/describe definition describe-placeholder-params))))

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
  devflow `:stage` it was poured for (shape: `:ct.spools.devflow/run-history`).

  Stage is devflow's own vocabulary, so this projection owns it: the engine's
  history reports only engine-owned root fields. Every root devflow poured for a
  run records its stage, so a molecule whose root carries no known `stages`
  member fails loudly (TEN-003) rather than projecting a stageless root."
  [runtime feature]
  (let [rt runtime]
    (spool/require-valid!
     ::run-history
     (mapv (fn [{:keys [root] :as molecule}]
             (let [strand (weaver/show rt (:id root))
                   stage (spool/attr-get strand :devflow/stage)]
               (when-not (stages stage)
                 (throw (ex-info "Devflow run molecule root carries no known devflow/stage"
                                 {:feature feature
                                  :strand (:id root)
                                  :stage stage
                                  :attributes (:attributes strand)
                                  :stages (vec (sort stages))})))
               (assoc-in molecule [:root :stage] stage)))
           (current/with-runtime runtime (workflow/run-history feature)))
     "Devflow run history molecules are invalid")))

(defn squash-run!
  "Squash a finished devflow `feature`'s run into one closed digest strand (see
  `skein.spools.workflow/squash-run!`). Fails loudly if the feature still has an
  active root. opts may include `:title` and `:attributes`.

  This closes out the graph only. The workspace side of finishing a feature —
  spec promotion, plan status, and moving the feature folder into
  `devflow/archive/` — is a separate devflow procedure: follow
  `(guidance :finish-archive)`."
  ([runtime feature]
   (current/with-runtime runtime (workflow/squash-run! feature)))
  ([runtime feature opts]
   (current/with-runtime runtime (workflow/squash-run! feature opts))))

;; Devflow's contribution is the registry entries its `defworkflow` forms
;; collect while this namespace loads, so there is no `contribute` fn and no
;; `spool` entry-point var: a module may not both collect authoring forms and
;; supply `:contribute` (SPEC-004.C46). A stage disappears from the registry by
;; the same rule that publishes it — stop evaluating its form and the next
;; refresh drops the entry by omission. The consumer declaration is unchanged:
;; `{:ns 'ct.spools.devflow :spools ['codethread/devflow]}`.

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
  "Return devflow's stage definitions by their stable routing name."
  []
  stage-workflows)

(defn commands
  "Return agent-facing devflow commands by stable key."
  []
  command-registry)
