# Skein Devflow Spool

`ct.spools.devflow` is an external git-distributed spool consumed by Skein workspaces with a sha-pinned `:git/url`+`:git/sha` coordinate. See [Skein's writing-shared-spools guide](https://github.com/codethread/skein/blob/main/docs/spools/writing-shared-spools.md#publishing-a-shared-spool-with-git-distribution) for the distribution mechanism.

## 1. Overview

`ct.spools.devflow` is the reference higher-level spool built on `skein.spools.workflow`. It encodes an opinionated feature-delivery lifecycle as ordinary workflow definitions plus thin convenience wrappers keyed by **feature name** — the feature name *is* the `workflow/run-id`, so there is no separate run handle to track.

Each lifecycle stage is a named static `defworkflow` Var (`intake`, `proposal`, …) that pours as its own molecule. With devflow active, a caller can run `strand workflow list` and `strand workflow show <name>` against the weaver to inspect these definitions before use; the old opaque callable forms could not offer that surface. Stages hand off through checkpoint `:next` routing (see workflow.md §5): choosing a routed option closes the current stage's molecule and pours the next stage under the same feature/run-id. Routed stages therefore retain the `:continue` entrypoint that authored checkpoint transfer requires and also advertise `:call`, allowing fixed calls and runtime-selected defers to run them as returning procedures. This entrypoint is distinct from the removed `continue!` worker operation. The spool owns no engine semantics of its own — lifecycle, routing, revision loops, and `done?` all come from `skein.spools.workflow`.

A stage accepts one complete parameter map. The engine first merges the definition's defaults, then judges the resulting map against that stage's `:param-spec` before it pours anything. A checkpoint choice that needs input declares an `:input {:spec ... :doc ...}` contract, so `choose!` validates the whole choice-input map before it closes the checkpoint or routes onward.

The spool also ships its own authoring knowledge base:
`ct.spools.devflow.guidance` encodes what each artifact (proposal, specs,
plan, task queue, ...) must contain as plain Clojure data — no external skill
or markdown reference files are needed. See §5a.

This document covers the stage graph and the devflow attribute conventions.
For the engine (`start!`/`complete!`/`choose!` mechanics, checkpoints, routing
transactions, gates, molecule ops, the `workflow/*` vocabulary), see
[Skein workflow.md](https://github.com/codethread/skein/blob/main/spools/workflow.md).

## 2. Stage map

Stages, their checkpoints, and where each choice routes. A choice with no
target is **terminal** — it closes the checkpoint and the stage continues in the
same molecule. A choice with a target closes the current stage and pours the
target stage under the same feature.

```
start! ─▶ intake
             :create-or-confirm-worktree  (human)
                 created-worktree / already-in-worktree ─▶ (continue intake)
                 abort ─▶ abort
             :discuss-scope  (agent)
                 proposal-ready     ─▶ proposal
                 needs-more-brief   ─▶ intake (revision)
          proposal
             :human-signoff-proposal  (human)
                 approved          ─▶ spec-plan
                 approved-to-cards ─▶ land-proposal
                 revise            ─▶ proposal (revision)
                 abort             ─▶ abort
          land-proposal
             :merge-proposal  (gate, waiter "human": proposal merged to mainline)
             :confirm-proposal-landed  (agent)
                 landed ─▶ decompose
                 abort  ─▶ abort
          decompose
             :author-cards step ─▶ (run auto-closes: done)
          spec-plan
             :human-signoff-spec-plan  (human)
                 approved ─▶ route-after-plan
                 revise   ─▶ spec-plan (revision)
                 abort    ─▶ abort
          route-after-plan
             :route-after-plan  (agent)
                 task-breakdown         ─▶ tasks
                 direct-implementation  ─▶ direct-implementation
          tasks
             :human-signoff-tasks  (human)
                 approved ─▶ run-afk-loop
                 revise   ─▶ tasks (revision)
                 abort    ─▶ abort
          run-afk-loop
             :manual ─▶ run-afk-manual
                 :run-afk-loop step ─▶ (run auto-closes: done)
             :delegate ─▶ run-afk-delegated
               :task-<id> subagent gates (sequential, one per task)
                  ─▶ :human-acceptance-afk  (human)
                        accepted ─▶ (run auto-closes: done)
                        revise   ─▶ run-afk-delegated (revision)
                        abort    ─▶ abort
          direct-implementation
             :human-acceptance  (human)
                 accepted ─▶ (run auto-closes: done)
                 revise   ─▶ direct-implementation (revision)
                 abort    ─▶ abort
          abort
             :record-abort step ─▶ (run auto-closes: done)
```

Notes:

- **The proposal sign-off is where the two working models split.** `approved-to-cards` is the
  cards route, added in v13 and preferred where implementation runs as a card loop (each card
  worked cold by its own agent): devflow's job ends at "approved proposal merged on mainline plus
  implementation cards authored", so the run routes through the `land-proposal` gate to the
  one-step `decompose` stage and closes there. The original `approved` route — spec-plan, tasks,
  AFK/direct implementation inside this one run — stays registered intact under the accretion
  promise, but is sidelined: prefer `approved-to-cards` unless the feature genuinely wants its
  implementation driven inside the devflow run.
- `approved-to-cards` routes by **definition symbol** (`ct.spools.devflow/land-proposal`) rather
  than registered name, deliberately: a new registry-name reference from an already-published
  definition retroactively grows the registration set that definition demands, which breaks every
  consumer's existing direct-registration set. A symbol target keeps the accreted choice
  self-contained. Inside the new stages, `:landed` and `:abort` use registered names as usual —
  new names carry no legacy registration sets.
- The `:merge-proposal` gate is repo-agnostic: any mainline merge process counts, and `complete!`
  records who landed it through `:by`. The gate's waiter is `"human"`, an ordinary external
  wait-point with no executor.
- **Three terminal paths** reach a done run without routing: `run-afk-manual`, `direct-implementation` on `:accepted`, and `decompose` (completing `:author-cards` ends the cards route). The route-after-plan checkpoint chooses between AFK and direct implementation; the static `run-afk-loop` checkpoint then makes the AFK manual/delegated decision explicit. In delegated AFK mode, `run-afk-delegated` first pours one sequential `workflow/gate "subagent"` per approved task, then requires the `:human-acceptance-afk` human checkpoint. A caller may pass optional `:delegate-preamble` text; devflow prepends it to each delegated AFK prompt as data and remains policy-free.
- **`:abort` is reachable from every `:human` checkpoint** — the intake
  worktree checkpoint and the four sign-off checkpoints. Of the three `:agent`
  checkpoints, `:discuss-scope` and `:route-after-plan` offer no abort;
  `:confirm-proposal-landed` does, because it is the last decision point before
  the terminal decompose stage and a feature whose approved proposal will not
  land needs an exit. Aborting routes to the static `abort` definition, whose
  `:record-abort` step then closes the run.
- Every abort choice declares a **required `:reason` input** (workflow.md §5,
  D1.2), so `choose!` fails loudly before any mutation unless the aborting call
  passes it: `(choose! runtime feature :abort {:reason "…"})`. The feature comes from
  context; the reason comes from the input and is recorded on the abort step.
  Abort itself is routed by the registered name `:abort` (`abort`).

## 3. Revision loops

Every human sign-off `:revise` choice, and intake's `:discuss-scope`
`:needs-more-brief`, is a declarative `:revise {:params {:revision true}}`
directive (workflow.md §5): `choose!` re-pours the stage's own
`workflow/definition` under the same feature/run-id with `:revision true`
merged authoritatively over context and choice input. There are no
`<stage>-revision-workflow` wrapper fns — the engine does the re-pour.

`:revision true` condition-skips exactly two steps, via `:condition [:!= :revision true]`:

- **intake** skips `:create-or-confirm-worktree` — the worktree was already
  created/confirmed on the first pass, so the revision round is ready at
  `:capture-brief`.
- **proposal** skips `:inspect-context` — orientation was done on the first
  pass, so the revision round is ready at `:write-proposal`.

The `spec-plan`, `tasks`, and `direct-implementation` stages carry a
`:revision` param too, but declare no condition on it, so their revision rounds
re-run the whole stage.

Proposal revision rounds are also the proposal document's **only** editing
window. `:approved` freezes `proposal.md` as the intent that was agreed: the
sign-off checkpoint's instruction says to mark it Approved and stop editing, and
no later stage reopens it. Implementation is free to diverge — the spec deltas,
the plan, and the code carry what is true now, and the plan records why scope
moved. Keeping an approved proposal in sync with the build would hide the
original intent behind a document that always looks current, and cost a round of
busywork per change to do it. The rules live with the artifact, in
`(guidance :proposal)` under `:knowledge :immutability` (see §5a).

The caller's complete start parameter map is seeded into `workflow/context` by `start!` (see §4) and survives every revision loop rather than resetting to defaults, because `:revise` merges its overrides over the carried-forward context.

`:revision` is stage-local: it is recorded as `workflow/stage-params` on the
re-poured root, and a forward hand-off (`:proposal-ready`, `:approved`,
route-after-plan's two choices) drops it from the continuation params in the
engine (workflow.md §5), so a round approved after a revise never leaks
`:revision true` into a downstream stage's context. Other start parameters pass through untouched.

## 4. Agent usage

The wrappers key everything by feature name and pass their maps straight through to the engine. `ready`/`ready-step` (and `choice-details`/`choice-detail`) return the same shapes as their `skein.spools.workflow` counterparts, with the current devflow `:stage` (and, on artifact-authoring steps, the `:guide` key for `guidance`) added to each ready step view; the run-mutating wrappers (`start!`, `complete!`, `choose!`, `advance!`) return the engine's `{:ready [step-view ...] :done boolean}` result.

Stage is devflow's own vocabulary, so the projections that emit it own the
invariant: a view is never projected without one. Whenever a feature has ready
work, its active root must carry a `devflow/stage` from the enum in §7 — a
missing or unknown value fails loudly (TEN-003) naming the feature, the
offending strand, the attributes it carried, and the allowed stages, rather than
returning a view with the stage quietly dropped. `run-history` holds every
molecule root to the same rule. The projected shapes are specced as
`:ct.spools.devflow/ready`, `:ct.spools.devflow/step-view`, and
`:ct.spools.devflow/run-history`, consulted at the seams that build them; the
specs own devflow's added fields and leave the engine-inherited keys to
`skein.spools.workflow`.

| Wrapper | Signature | Notes |
|---|---|---|
| `start!` | `(runtime feature)` / `(runtime feature params)` | Pours the registered `:intake` definition. Every devflow stage definition stamps `workflow/family "devflow"`, including runs started through the generic workflow API. This wrapper merges intake defaults with the supplied complete param map, validates the result against `:ct.spools.devflow/intake-params`, and seeds it (plus `:feature`) into `workflow/context` for revision loops. Returns `{:ready [...] :done boolean}`. |
| `ready` | `(runtime feature)` | All ready step views for the feature (each carrying `:run-id`). |
| `ready-step` | `(runtime feature)` | The single ready step view; throws if ambiguous. |
| `complete!` | `(runtime feature)` / `(runtime feature opts)` | Closes the current non-checkpoint step. `opts` (`:step`, `:attributes`, `:by`) pass through. Returns `{:ready [...] :done boolean}`. |
| `choose!` | `(runtime feature choice)` / `(runtime feature choice input)` / `(runtime feature choice input opts)` | Records the checkpoint choice and routes if the choice has a `:next`. Returns `{:ready [...] :done boolean}`. |
| `advance!` | `(runtime feature)` / `(runtime feature opts)` | Unified step/checkpoint driver. `opts` may include `:choice`, `:input`, `:step`, `:by`, and `:attributes`. Returns `{:ready [...] :done boolean}`. |
| `choice-details` | `(runtime feature)` / `(runtime feature opts)` | Choice explanations for the current checkpoint. |
| `choice-detail` | `(runtime feature choice)` / `(runtime feature choice opts)` | One choice's explanation. |
| `describe` | `()` / `(stage)` | Compile-time shape of the full devflow cycle, or one registered stage key such as `:proposal`; writes nothing. |
| `guidance` | `()` / `(guide)` | Authoring knowledge base (§5a): the workspace overview, or one artifact guide by key (keyword or string); writes nothing. |
| `run-history` | `(runtime feature)` | Ordered run history for the feature (delegates to `workflow/run-history`), each molecule's `:root` carrying the `:stage` it was poured for. |
| `squash-run!` | `(runtime feature)` / `(runtime feature opts)` | Squash a finished feature run into one closed digest strand; fails loudly while any stage root is active. Closes out the graph only — the workspace side of finishing follows `(guidance :finish-archive)`. |
| `current-root` | `(runtime feature)` | The feature's single active stage root molecule, or nil when it has none; throws if ambiguous. |

There is no devflow `done?` wrapper — use `skein.spools.workflow/done?` with the
feature name.

Driving example with one revise round:

```clojure
(require '[ct.spools.devflow :as devflow]
         '[skein.api.current.alpha :as current])

(def runtime (current/runtime))

;; feature name is the run-id; step-view's :id is the generated strand id,
;; a checkpoint's stable definition name arrives as the :checkpoint string
(devflow/start! runtime "search-filters")
;; => {:ready [{:role "checkpoint" :checkpoint "create-or-confirm-worktree"
;;              :choices ["created-worktree" "already-in-worktree" "abort"] ...}]
;;     :done false}

;; terminal choice — stays in the intake molecule and advances to capture-brief
(devflow/choose! runtime "search-filters" :created-worktree {})
;; => {:ready [{:title "Capture user brief for search-filters" :artifact "brief" ...}] :done false}

(devflow/complete! runtime "search-filters")
;; => {:ready [{:role "checkpoint" :checkpoint "discuss-scope"
;;              :choices ["proposal-ready" "needs-more-brief"] ...}] :done false}

;; scope is clear — route to the proposal stage (fresh molecule, same feature)
(devflow/choose! runtime "search-filters" :proposal-ready {})
;; => {:ready [{:action-ref "devflow.proposal.orient" ...}] :done false}

;; complete inspect-context, write-proposal, and the inner agent-review step
;; (its join auto-closes) until the sign-off checkpoint is ready
;; ... => {:ready [{:role "checkpoint" :checkpoint "human-signoff-proposal"
;;                  :choices ["approved" "revise" "abort"] ...}] :done false}

;; revise: closes this proposal round and pours a fresh one; :inspect-context
;; is condition-skipped, so the round is ready at :write-proposal
(devflow/choose! "search-filters" :revise {})
;; => {:ready [{:artifact "proposal.md" :guide :proposal ...}] :done false}

;; ... re-run write-proposal + review, reach human-signoff-proposal again ...

;; approve: route to the spec/plan stage
(devflow/choose! "search-filters" :approved {})
;; => {:ready [{:artifact "specs/*.delta.md" :guide :spec ...}] :done false}
```

Delegating approved AFK tasks through the subagent executor is opt-in. Pass `:tasks` and a harness when approving the task queue, then choose `:delegate` at `:choose-afk-execution`; task maps may be keyword- or string-keyed (choice input often round-trips through JSON). Task `:id` values must be token-safe strings (`[A-Za-z0-9][A-Za-z0-9._-]*`) because they become step ids (`:task-<id>`); anything else fails loudly before any pour:

```clojure
(devflow/choose! "search-filters" :approved
  {:tasks [{:id "impl" :title "Implement filters" :body "Use the signed-off plan."}
           {:id "tests" :title "Add regression tests"}]
   :delegate-harness "pi-main"
   :delegate-cwd "/path/to/feature/worktree"})
;; => {:ready [{:gate "subagent" :title "Delegate AFK task impl for search-filters" ...}]
;;     :done false}
```

Choose `:manual` at `:choose-afk-execution` to use the single `:run-afk-loop` manual step; it needs no task data.

## 5. Registries

Devflow exposes static definitions and commands as data for trusted resolution:

- `stage-workflows` is the local map of stable routing names to the thirteen static definitions: `:intake`, `:proposal`, `:land-proposal`, `:decompose`, `:spec-plan`, `:route-after-plan`, `:tasks`, `:run-afk-loop`, `:run-afk-manual`, `:run-afk-delegated`, `:direct-implementation`, `:agent-review`, and `:abort`. Forward `:next` choices reference these names, with one deliberate exception: the `:approved-to-cards` choice targets `land-proposal` by definition symbol (see §2's notes). `intake` advertises `:start`, `agent-review` advertises `:call`, and every routed lifecycle stage advertises both `:continue` for authored checkpoint transfer and `:call` for returning call/defer composition. The engine collects the Vars when the namespace loads, so a caller discovers the same definitions through `strand workflow list` and `strand workflow show <name>`; there is no separate registration or contribution call.
- `(workflows)` returns `stage-workflows`, and `devflow-cycle` is the ordered composable single-run path. The cards route's stages are not part of that vector (its frozen shape predates them); describe them individually with `describe :land-proposal` and `describe :decompose`.
- `(commands)` returns `command-registry` — agent-facing commands by key: `:start`, `:ready-step`, `:ready`, `:choice-details`, `:choice-detail`, `:choose`, `:complete`, `:advance`, `:describe`, `:guidance`, `:run-history`, and `:squash-run`.
- Workspace configuration activates the spool through `runtime/module!`, the sole route-publication path. The declaration names a source target and world policy only; static forms provide the workflow catalogue.
- `(dependency-sentinel)` returns `"devflow-spool"`, produced through this spool's declared `camel-snake-kebab` Maven dependency so runtime validation can observe that approved spool dependencies were resolved.

## 5a. Authoring guidance

`ct.spools.devflow.guidance` is the authoring knowledge base for the
artifacts the lifecycle produces. It replaces the markdown devflow skill: the
guide content lives in ordinary Clojure defs built from shared blocks —
`paths` (every workspace location by role), `id-convention` (the stable
document-ID scheme), `document-ownership` (what each document kind owns,
must not absorb, and how long it stays writable — the proposal only until
sign-off), `invariants`, and markdown templates composed from a shared
`config-identification` renderer so the ID rules never drift between document
kinds.

`(guidance)` returns the workspace overview (layout, paths, invariants, ID
convention, ownership, and a key → purpose index of the guides).
`(guidance :proposal)` returns one guide; keys are `:proposal`, `:rfc`,
`:spec`, `:plan`, `:tasks`, `:afk`, `:decompose`, and `:finish-archive`,
accepted as keywords or strings, failing loudly otherwise. Every guide shares
one shape:

| Key | Contents |
|---|---|
| `:purpose` | One sentence: what the artifact is for. |
| `:artifacts` | Where the files live, from `guidance/paths`. |
| `:prerequisites` | What must be true or read before writing. |
| `:knowledge` | Guide-specific reference maps: statuses, schemas, naming and slicing rules. |
| `:procedures` | Named step vectors, e.g. `{:write [...] :review-or-update [...]}`. |
| `:constraints` | Hard rules that apply while writing. |
| `:validation` | Acceptance checklist for the finished artifact. |
| `:templates` | Markdown skeleton(s) to instantiate. |
| `:see-also` | Related guide keys. |

Artifact-authoring steps advertise their guide through the `devflow/guide`
strand attribute and a `workflow/instruction` telling the driving agent to
call `guidance` before writing; ready step views surface the key as `:guide`
(derived from `artifact-guides`, the `workflow/artifact` → guide-key map).
The `:rfc` and `:finish-archive` guides have no dedicated stage step: RFCs
are written on demand when intake/proposal work exposes meaningful
uncertainty, and finish/archive work is the workspace-side procedure a
caller follows after `squash-run!` closes out the graph.

## 6. Attribute conventions

Devflow reads and writes these attributes on strands, on top of the engine's
`workflow/*` vocabulary (workflow.md §7). Stage-level attributes sit on the root
molecule; the rest sit on individual step/checkpoint strands.

| Attribute | Meaning | Set on / by |
|---|---|---|
| `workflow/family` | `"devflow"` for every devflow stage, including stages poured by generic `workflow start`, revision loops, and named routes. | Root molecule, by each stage definition. |
| `devflow/stage` | Lifecycle stage: `"intake"`, `"proposal"`, `"land-proposal"`, `"decompose"`, `"spec-plan"`, `"route-after-plan"`, `"tasks"`, `"afk"`, `"implementation"`, `"abort"`. The `stages` set is the enum of record — the definitions write it through one helper and the projections (§4) reject a root that carries anything else. | Root molecule, by each stage definition. |
| `devflow/feature` | The feature name. Carries the same value as `workflow/run-id`, but is not redundant with it: the roster spool reads this key's *presence* to derive `roster/engine "devflow"` rather than `"workflow"` (roster.md, SPEC-RosterSpool-001.C13), so a devflow root that stopped stamping it would silently register as a plain workflow run. | Root molecule, by each stage definition. |
| `workflow/artifact` | Artifact a step produces (`"brief"`, `"proposal.md"`, `"specs/*.delta.md"`, `"<feature>.plan.md"`, `"tasks/index.yml"`). The engine's own key, caller-supplied; `step-view` surfaces it as `:artifact`. | Artifact-writing steps. |
| `devflow/task` | Stable approved AFK task id attached to delegated `run-afk-delegated` task gates. | `:task-<id>` subagent gates in delegated AFK mode. |
| `devflow/review` | `"agent"` marking a step as an agent review round (the reusable `agent-review` definition). Distinct from the engine's `workflow/checkpoint-kind`, which says who decides a *checkpoint*. | The `:review` step of `agent-review`. |
| `workflow/checkpoint-kind` | `"human"` or `"agent"` — who decides the checkpoint. | Auto-stamped by the engine `checkpoint` builder from its `:kind` opt (workflow.md §7); devflow never sets it by hand. |
| `workflow/decision-point` | Freeform label for what the checkpoint decides (`"worktree-ready"`, `"scope-ready"`, `"proposal-signed-off"`, `"proposal-landed"`, `"choose-tasks-or-implementation"`, `"plan-signed-off"`, `"tasks-signed-off"`, `"afk-accepted"`, `"implementation-accepted"`). | Each checkpoint. |
| `workflow/action-ref` | Pointer to the action/skill an agent should invoke (`"devflow.worktree.ensure"`, `"devflow.proposal.orient"`, `"devflow.proposal.land"`, `"devflow.decompose.cards"`, `"devflow.tasks.run-afk-loop"`, `"devflow.implementation.direct"`, `"devflow.implementation.validate"`, `"devflow.abort.record"`). Surfaced by `step-view`. | Steps/checkpoints that hand off to a named action. |
| `workflow/gate` | `"subagent"` on delegated AFK task gates (agent-run consumes these when installed, otherwise they remain ordinary external wait-points), and `"human"` on the land-proposal merge gate, which is always an ordinary external wait-point. | `:task-<id>` gates in delegated AFK mode; `:merge-proposal` in the land-proposal stage. |
| `agent-run/harness` | Harness or alias requested for the delegated task run. Required for each delegated AFK gate, via task `:harness` or `:delegate-harness`. | `:task-<id>` gates in delegated AFK mode. |
| `agent-run/prompt` | Prompt sent to the delegated agent run, prefixed with feature/task context and then the task body or title. | `:task-<id>` gates in delegated AFK mode. |
| `agent-run/cwd` | Optional working directory for delegated AFK task runs, from `:delegate-cwd`. | `:task-<id>` gates in delegated AFK mode. |
| `workflow/instruction` | Freeform instruction text surfaced in `step-view`. | Steps/checkpoints needing explicit guidance, including every guided artifact step's pointer to `guidance`. |
| `devflow/guide` | Guidance key (`"proposal"`, `"spec"`, `"plan"`, `"tasks"`, `"afk"`, `"decompose"`) naming the authoring guide for the step (§5a). | The four `write-*` artifact steps, the `run-afk-manual` step, and the decompose `:author-cards` step (`:capture-brief` produces `"brief"` without one). |

The intake root additionally carries `devflow/worktree-check` (`"required"` or `"already-in-worktree-ok"`), seeded from the `:worktree-check` start parameter.

## 7. See also

- [Skein workflow.md](https://github.com/codethread/skein/blob/main/spools/workflow.md) — the engine this spool is built on: run
  lifecycle, checkpoints and `:next` routing, revise-by-routing loops, gates,
  molecule ops, and the full `workflow/*` attribute vocabulary.
- `(skein.spools.workflow/explain topic)` — machine-readable builder contracts
  agents can call before constructing workflow data.
- [README.md](./README.md) — this spool repo's loading notes.
