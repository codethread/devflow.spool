(ns ct.spools.devflow
  "Clojure-native workflow definitions for the devflow lifecycle.

  Every stage is a static `defworkflow` Var: a definition a worker can read
  through `strand workflow show <name>` before starting a run, with its param
  contract owned by a spec rather than by a constructor's argument list
  (PROP-Wcd-001.S12). The definitions are ordinary workflow data that callers
  can inspect, compose, pour as molecules, or materialize as wisps.

  Authoring knowledge for the artifacts each stage produces (proposal, specs,
  plan, task queue, ...) lives in `ct.spools.devflow.guidance` and is served
  by `guidance` from Clojure and by the `devflow` op (`strand devflow
  guidance`) for CLI workers; artifact-authoring steps advertise the matching
  guide key via the `devflow/guide` attribute."
  (:require [camel-snake-kebab.core :as csk]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.devflow.guidance :as guidance]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millhouse.spools.workflow :as workflow]))

(def artifact-guides
  "Maps each `workflow/artifact` value an authoring step advertises to the
  guidance key holding its authoring rules (see `guidance`). The brief has no
  guide; it is captured conversationally during intake."
  {"proposal.md" :proposal
   "specs/*.delta.md" :spec
   "<feature>.plan.md" :plan
   "task strands" :tasks
   "implementation cards" :decompose})

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
     "workflow/instruction" (str "Run `strand devflow guidance " (name guide) "` for the "
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
(s/def ::card-reviewer non-blank-string?)
(s/def ::card-set-reviewer non-blank-string?)
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

(s/def ::cards
  (s/and (s/coll-of ::review-card :kind vector? :min-count 1) distinct-card-ids?))

(defn- harnesses-resolve?
  "Every delegated task names a harness, or inherits the stage's default one."
  [{:keys [tasks delegate-harness]}]
  (every? #(non-blank-string? (or (task-value % :harness) delegate-harness)) tasks))

(s/def ::intake-params
  (s/keys :req-un [::feature]
          :opt-un [::worktree-check ::revision ::card-reviewer
                   ::card-set-reviewer ::review-cwd]))
(s/def ::agent-review-params (s/keys :req-un [::feature ::artifact]))
(s/def ::proposal-params (s/keys :req-un [::feature] :opt-un [::revision]))
(s/def ::land-proposal-params
  (s/keys :req-un [::feature ::card-reviewer ::card-set-reviewer]
          :opt-un [::review-cwd]))
(s/def ::decompose-params
  (s/keys :req-un [::feature ::card-reviewer ::card-set-reviewer]
          :opt-un [::review-cwd]))
(s/def ::card-set-input (s/keys :req-un [::cards]))
(s/def ::review-cards-params
  (s/keys :req-un [::feature ::card-reviewer ::card-set-reviewer ::cards]
          :opt-un [::review-cwd ::revision]))
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
(s/def ::author-strands-params (s/keys :req-un [::feature]))

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

(defn- card-review-prompt
  "Render the focused review prompt for one card."
  [{:keys [feature item]}]
  (str "Review one implementation card for " feature " as a focused, read-only "
       "reviewer. Use the workspace's card system to inspect card "
       (card-value item :id) " (" (card-value item :title) ") and read the merged, approved "
       "proposal it implements.\n\nJudge only this card's cold-work contract: current-state "
       "evidence, target outcome, constraints, proposal traceability, explicit done-when, "
       "validation gates, landing discipline, and whether its direct dependencies let it land "
       "independently. Do not redesign the card set or repeat set-wide coverage analysis; a "
       "separate set-level reviewer owns relationships across cards. Do not edit cards.\n\n"
       "Return `VERDICT: pass` or `VERDICT: revise`, followed by concrete findings ordered by "
       "severity. Say plainly when the card passes."))

(defn- card-set-review-prompt
  "Render the set-level review prompt after every focused card review fans in."
  [{:keys [feature cards]}]
  (str "Review the implementation-card decomposition for " feature " as the set-level, "
       "read-only reviewer. Focused reviewers have already reviewed each card's "
       "cold-work contract; do not repeat that fine-grained work.\n\nUse the workspace's card "
       "system to inspect these cards:\n"
       (str/join "\n" (map #(str "- " (card-value % :id) ": " (card-value % :title))
                            cards))
       "\n\nReview only the connections and whole-set shape: complete proposal-goal coverage, "
       "gaps and overlaps, outcome-oriented slicing, independently landable increments, "
       "dependency-edge direction and necessity, integration seams, and open decisions that "
       "would otherwise be decided inconsistently by cold workers. Do not edit cards.\n\nReturn "
       "`VERDICT: pass` or `VERDICT: revise`, followed by concrete set-level findings ordered "
       "by severity. Say plainly when the decomposition is cohesive."))

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

(workflow/defworkflow author-task-strands
  "The shipped strand-native task-authoring target for the tasks stage's defer.

  Tasks are ordinary strands, not files: `devflow/task-type` is `afk` or
  `hitl`, `devflow/feature` names the feature, dependencies are `depends-on`
  edges, and the runnable queue is the ready frontier
  (`strand ready --query devflow-tasks`). HITL tasks also carry `hitl=true`
  so the batteries convention (stop and ask the user) applies unchanged. The
  authoring rules and body headings live in `strand devflow guidance tasks`."
  {:entrypoints #{:call}
   :param-spec ::author-strands-params
   :defaults {}}
  (workflow/workflow
    (titled "Author task strands for ")
    (workflow/step :author-task-strands
                   (titled "Author strand-native task queue for ")
                   :self
                   :attributes (guided-artifact "task strands"))))

(workflow/defworkflow author-card-strands
  "The shipped strand-native card-authoring target for the decompose stage's defer.

  Cards use the same strand vocabulary as tasks (`devflow/task-type`,
  `devflow/feature`, `depends-on` edges); the difference is body density — a
  card body carries the full cold-work contract from
  `strand devflow guidance decompose`. Strand ids are token-safe, so they are
  the card ids the review handoff expects."
  {:entrypoints #{:call}
   :param-spec ::author-strands-params
   :defaults {}}
  (workflow/workflow
    (titled "Author card strands for ")
    (workflow/step :author-card-strands
                   (titled "Author strand-native implementation cards for ")
                   :self
                   :attributes (guided-artifact "implementation cards"))))

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

(def decompose-open
  "The decompose stage as an unbound template.

  The `:author-cards` defer is the pluggable seam: the template names where a
  workspace chooses its card-authoring workflow without naming anyone's
  implementation. Consumer code that can see both spools binds it with
  `workflow/bind-defers` — the shipped strand-native target, an issue-tracker
  target, any other card system's target — and registers the result under its
  own name, or re-points `:decompose` at it."
  (workflow/workflow
    (titled "Devflow decompose: ")
    {:attributes (stage-attributes "decompose")}
    (workflow/defer :author-cards
                    (titled "Choose the card-authoring workflow for ")
                    :attributes {"workflow/action-ref" "devflow.decompose.cards"
                                 "devflow/guide" "decompose"
                                 "workflow/instruction" (str "Fill this defer with one of the workflows "
                                                             "listed in workflow/defer-workflows: "
                                                             "`strand workflow defer <feature> --workflow "
                                                             "<target> --params '{\"feature\":\"<feature>\"}'`. "
                                                             "Targets receive only the params passed at the "
                                                             "fill, so pass the feature explicitly. Run "
                                                             "`strand devflow guidance decompose` for the "
                                                             "cold-card and review handoff contracts.")})
    (workflow/checkpoint :handoff-card-review
                         (titled "Hand authored cards to review for ")
                         :depends-on [:author-cards]
                         :kind :agent
                         :choices [{:key :review
                                    :label "Review cards"
                                    :description "Supply the authored card refs; fan focused reviews out before the set-level cohesion review."
                                    ;; Like proposal's later-added cards route, this
                                    ;; continuation is accreted onto an already-published
                                    ;; definition. A symbol keeps old direct-registration
                                    ;; sets able to register :decompose; the new review
                                    ;; definition remains published and discoverable itself.
                                    :next 'ct.spools.devflow/review-cards
                                    :input {:spec ::card-set-input
                                            :doc (str "The non-empty vector of authored card refs; each requires "
                                                      "token-safe id and title. Include every card the review "
                                                      "should judge — grouping cards are the workspace's own "
                                                      "convention, not devflow's.")}}
                                   {:key :abort
                                    :label "Abort"
                                    :description "Stop this feature because a reviewable implementation-card set could not be authored."
                                    :next :abort
                                    :input abort-reason-input}]
                         :attributes {"workflow/decision-point" "implementation-cards-authored"
                                      "workflow/instruction" (str "After authoring the cards, choose review "
                                                                  "with every authored card ref. The review "
                                                                  "stage uses the configured card-reviewer "
                                                                  "and card-set-reviewer seats.")})))

(workflow/defworkflow decompose
  "Author implementation cards through a pluggable target, then hand their
  refs to the review stage.

  `:author-cards` is a defer bound to the shipped strand-native target;
  workspaces bind their own card systems through `decompose-open`. Workflow
  loops expand when a stage pours, before any card exists, so the agent
  checkpoint after authoring remains the explicit data boundary: its `:review`
  choice supplies the card refs that the continuation fans out over. Reviewer
  seats are caller-selected params."
  {:entrypoints #{:continue :call}
   :param-spec ::decompose-params
   :defaults {}}
  (workflow/bind-defers decompose-open {:author-cards #{:author-card-strands}}))

(workflow/defworkflow review-cards
  "Review authored implementation cards at focused and set-level scopes.

  The card gate expands without a chain, so every focused review is ready
  together and the subagent executor may run them up to its fan-out ceiling.
  The set gate depends on the loop's base id, which fans in over all focused
  reviews, and its prompt deliberately judges only cross-card cohesion. How
  cards are grouped (a parent card, a milestone, nothing) is the caller's own
  convention: the run reviews exactly the refs supplied. The driving agent
  then reconciles both result classes. Material changes may choose
  `:review-again`, re-pouring this stage with the current card refs."
  {:entrypoints #{:continue :call}
   :param-spec ::review-cards-params
   :defaults {:revision false}}
  (workflow/workflow
    (titled "Devflow card review: ")
    {:attributes (stage-attributes "card-review")}
    (workflow/gate :card-review
                   (fn [{:keys [item]}]
                     (str "Focused review of card " (card-value item :id) ": "
                          (card-value item :title)))
                   :subagent
                   :loop {:each :cards}
                   :attributes {"devflow/review" "agent"
                                "devflow/review-scope" "card"
                                "devflow/card" (fn [{:keys [item]}] (card-value item :id))
                                "agent-run/harness" (param-value :card-reviewer)
                                "agent-run/cwd" (param-value :review-cwd)
                                "agent-run/prompt" card-review-prompt
                                "workflow/instruction" (str "Executor-owned focused card review. "
                                                            "The configured card reviewer must "
                                                            "inspect exactly this card and return "
                                                            "its verdict; parallel sibling gates "
                                                            "review the other cards.")})
    (workflow/gate :card-set-review
                   (titled "Cohesion review of the card set for ")
                   :subagent
                   :depends-on [:card-review]
                   :attributes {"devflow/review" "agent"
                                "devflow/review-scope" "card-set"
                                "agent-run/harness" (param-value :card-set-reviewer)
                                "agent-run/cwd" (param-value :review-cwd)
                                "agent-run/prompt" card-set-review-prompt
                                "workflow/instruction" (str "Executor-owned set-level cohesion "
                                                            "review. It starts only after every "
                                                            "focused card review closes and must "
                                                            "not repeat those per-card checks.")})
    (workflow/step :reconcile-card-reviews
                   (titled "Reconcile implementation-card reviews for ")
                   :self
                   :depends-on [:card-set-review]
                   :attributes {"workflow/action-ref" "devflow.decompose.reconcile-reviews"
                                "devflow/guide" "decompose"
                                "workflow/instruction" (str "Read agent-run/result from every closed "
                                                            "card-review-* gate and from the "
                                                            "card-set-review gate. Apply valid focused "
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
                                    :description "The focused and set-level findings are resolved; end devflow and leave implementation to the card loop."}
                                   {:key :review-again
                                    :label "Review again"
                                    :description "Cards changed materially while reconciling findings; fan out a fresh review round over the current set."
                                    :input {:spec ::card-set-input
                                            :doc "Resupply the current complete card refs for the next review round."}
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
                                "workflow/instruction" "Run or hand off the devflow AFK task loop for this feature after task sign-off. Run `strand devflow guidance afk` for the loop contract and queue checks."})))

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

(def tasks-open
  "The task-breakdown stage as an unbound template.

  The `:author-tasks` defer is the pluggable seam: it names where a workspace
  chooses its task-authoring workflow — the shipped strand-native target, an
  issue tracker, any other task system — without naming anyone's implementation.
  Consumer code binds it with `workflow/bind-defers` and registers the result
  under its own name, or re-points `:tasks` at it."
  (workflow/workflow
    (titled "Devflow task breakdown: ")
    {:attributes (stage-attributes "tasks")}
    (workflow/defer :author-tasks
                    (titled "Choose the task-authoring workflow for ")
                    :attributes {"devflow/guide" "tasks"
                                 "workflow/instruction" (str "Fill this defer with one of the workflows "
                                                             "listed in workflow/defer-workflows: "
                                                             "`strand workflow defer <feature> --workflow "
                                                             "<target> --params '{\"feature\":\"<feature>\"}'`. "
                                                             "Targets receive only the params passed at the "
                                                             "fill, so pass the feature explicitly. Run "
                                                             "`strand devflow guidance tasks` for the queue "
                                                             "contract before filling.")})
    (workflow/call :agent-review-tasks
                   :agent-review
                   {:artifact "task queue"}
                   :title (titled "Complete agent review for " " task queue")
                   :depends-on [:author-tasks])
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

(workflow/defworkflow tasks
  "The reviewed task queue stage.

  `:author-tasks` is a defer bound to the shipped strand-native target;
  workspaces bind their own queue systems through `tasks-open`. A revision
  round (`:revision true`) re-runs the whole task-breakdown stage, including
  the defer."
  {:entrypoints #{:continue :call}
   :param-spec ::tasks-params
   :defaults {:revision false}}
  (workflow/bind-defers tasks-open {:author-tasks #{:author-task-strands}}))

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

;; Devflow defines reusable workflow, query, and op declarations, then selects
;; the complete catalogue for this root's publishing module. A consumer that
;; requires this namespace outside module collection can select only the Vars
;; it wants with the matching typed use form.
;;
;; The generic `millhouse.spools.workflow` API owns starting, inspecting,
;; advancing, archiving, and querying workflow runs. Devflow adds no parallel
;; run-driving facade; the `devflow` op serves authoring knowledge only.

(workflow/use-workflow!
 intake
 agent-review
 author-task-strands
 author-card-strands
 proposal
 land-proposal
 decompose
 review-cards
 route-after-plan
 spec-plan
 run-afk-loop
 run-afk-manual
 run-afk-delegated
 tasks
 direct-implementation
 abort)

(millstrand/defquery devflow-runs
  "Return active Devflow workflow roots that can be resumed."
  {:usage "strand list --query devflow-runs"}
  [:and
   [:= :state "active"]
   [:= [:attr "workflow/role"] "root"]
   [:= [:attr "workflow/family"] "devflow"]])

(millstrand/defquery devflow-ready
  "Return ready work belonging to an active Devflow workflow run."
  {:usage "strand ready --query devflow-ready"}
  [:edge/in "parent-of"
   [:and
    [:= :state "active"]
    [:= [:attr "workflow/role"] "root"]
    [:= [:attr "workflow/family"] "devflow"]]])

(millstrand/defquery devflow-tasks
  "Return active strand-native devflow tasks and cards (devflow/task-type).

  With `strand list` this is the whole open queue; with `strand ready` it is
  the runnable frontier — active tasks whose depends-on prerequisites are all
  closed. HITL tasks additionally carry hitl=true, which the batteries agent
  convention treats as stop-and-ask."
  {:usage "strand ready --query devflow-tasks"}
  [:and
   [:= :state "active"]
   [:exists [:attr "devflow/task-type"]]])

(millstrand/use-query! devflow-runs
                       devflow-ready
                       devflow-tasks)

(defn guidance
  "Return Devflow's static authoring knowledge as markdown.

  With no argument, return the workspace overview. With a keyword or string
  guide key, return that artifact's authoring guide: purpose, prerequisites,
  procedures, constraints, validation checklist, and templates."
  ([] (guidance/overview))
  ([guide] (guidance/guide (if (string? guide) (keyword guide) guide))))

(def ^:private devflow-arg-spec
  "Declared command surface for the `devflow` op."
  {:op "devflow"
   :doc "Devflow's static authoring knowledge, served to CLI workers."
   :subcommands
   {"guidance"
    {:doc (str "Show the devflow workspace overview, or one artifact's full "
               "authoring guide.")
     :hook-class :read
     :deadline-class :standard
     :positionals [{:name :guide
                    :type :string
                    :doc (str "Guide key, as advertised by a step's devflow/guide "
                              "attribute (e.g. proposal). Omit for the workspace "
                              "overview, which indexes every key.")}]
     :annotations
     {:use-when [(str "A ready step carries a devflow/guide attribute and you "
                      "are about to author its artifact.")
                 (str "Working outside a run: rfc and finish-archive have no "
                      "workflow step, and the overview orients any devflow "
                      "workspace work.")]
      :notes [(str "The payload is resolved live from the loaded spool on every "
                   "call, never from run state, so it is always the current "
                   "guide. The Clojure equivalent is "
                   "(ct.spools.devflow/guidance <key>).")]}}}})

(def ^:private devflow-returns
  {:subcommands
   {"guidance" {:type :map
                :required {:operation :string
                           ;; One markdown document, loaded from the spool's
                           ;; guidance resources by ct.spools.devflow.guidance.
                           :guidance :string}
                :optional {:guide :string}}}})

(def ^:private devflow-meta
  "Cross-verb narrative for `devflow`, projected by the `about`/`prime`
  meta-verbs."
  {:about (format-alpha/reflow
           "|devflow ships the feature-delivery lifecycle as ordinary Millstrand
            |workflow definitions, driven through the generic workflow op; this
            |op adds no run verbs. guidance is its one read: the static authoring
            |knowledge behind the lifecycle. With no argument it returns the
            |workspace overview — layout, paths, invariants, the document-ID
            |convention, document ownership, and an index of every guide key. With
            |a key it returns that artifact's authoring guide as one markdown
            |document: purpose, prerequisites, knowledge, procedures, constraints,
            |validation checklist, and templates. Artifact-authoring steps advertise
            |their key in the devflow/guide strand attribute, and the payload resolves
            |live from the loaded spool rather than from anything recorded on the run.")
   :prime (format-alpha/reflow
           "|Run `strand devflow guidance` for the workspace overview and the index
            |of guide keys, then `strand devflow guidance <key>` (e.g. proposal)
            |before authoring that artifact. When driving a workflow run, the ready
            |step's devflow/guide attribute names the key to fetch. rfc and
            |finish-archive belong to no step: fetch rfc when intake or proposal work
            |exposes real uncertainty, and finish-archive after squash-run! to close
            |out a feature.")})

(millstrand/defop devflow
  "Serve Devflow's static authoring knowledge: the workspace overview or one artifact's authoring guide."
  (merge {:arg-spec devflow-arg-spec
          :returns devflow-returns
          :stream? false}
         devflow-meta)
  [{:op/keys [args]}]
  (case (first (:subcommand args))
    "guidance" (if-let [guide (:guide args)]
                 {:operation "devflow guidance"
                  :guide guide
                  :guidance (guidance guide)}
                 {:operation "devflow guidance"
                  :guidance (guidance)})
    (throw (ex-info "Unsupported devflow subcommand"
                    {:subcommand (:subcommand args) :allowed ["guidance"]}))))

(millstrand/use-op! devflow)

;; The unbanged forms above define declarations without publishing them. The
;; explicit use forms are the root module's owner-complete contribution. There
;; is no spool entry point and no run-driving Devflow facade.
