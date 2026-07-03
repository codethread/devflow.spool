# Skein Devflow Spool

`skein.spools.devflow` is an external git-distributed spool consumed by
Skein workspaces with a sha-pinned `:git/url`+`:git/sha` coordinate. See
[Skein's writing-shared-spools guide](https://github.com/codethread/skein/blob/main/docs/writing-shared-spools.md#publishing-a-shared-spool-with-git-distribution)
for the distribution mechanism.

## 1. Overview

`skein.spools.devflow` is the reference higher-level spool built on
`skein.spools.workflow`. It encodes an opinionated feature-delivery lifecycle as
ordinary workflow definitions plus thin convenience wrappers keyed by **feature
name** — the feature name *is* the `workflow/run-id`, so there is no separate
run handle to track.

Each lifecycle stage is a plain workflow definition (`intake-workflow`,
`proposal-workflow`, …) that pours as its own molecule. Stages hand off to one
another through checkpoint `:next` routing (see workflow.md §5): choosing a
routed option closes the current stage's molecule and pours the next stage's
under the same feature/run-id. The spool owns no engine semantics of its own —
lifecycle, routing, revision loops, and `done?` all come from
`skein.spools.workflow`.

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
             :create-or-confirm-worktree  (HITL)
                 created-worktree / already-in-worktree ─▶ (continue intake)
                 abort ─▶ abort
             :discuss-scope  (agent)
                 proposal-ready     ─▶ proposal
                 needs-more-brief   ─▶ intake (revision)
          proposal
             :human-signoff-proposal  (HITL)
                 approved ─▶ spec-plan
                 revise   ─▶ proposal (revision)
                 abort    ─▶ abort
          spec-plan
             :human-signoff-spec-plan  (HITL)
                 approved ─▶ route-after-plan
                 revise   ─▶ spec-plan (revision)
                 abort    ─▶ abort
          route-after-plan
             :route-after-plan  (agent)
                 task-breakdown         ─▶ task-breakdown
                 direct-implementation  ─▶ direct-implementation
          task-breakdown
             :human-signoff-tasks  (HITL)
                 approved ─▶ run-afk-loop
                 revise   ─▶ task-breakdown (revision)
                 abort    ─▶ abort
          run-afk-loop
             no delegated task data:
               :run-afk-loop step ─▶ (run auto-closes: done)
             delegated task data:
               :task-<id> subagent gates (sequential, one per task)
                  ─▶ :human-acceptance-afk  (HITL)
                        accepted ─▶ (run auto-closes: done)
                        revise   ─▶ run-afk-loop (revision)
                        abort    ─▶ abort
          direct-implementation
             :human-acceptance  (HITL)
                 accepted ─▶ (run auto-closes: done)
                 revise   ─▶ direct-implementation (revision)
                 abort    ─▶ abort
          abort
             :record-abort step ─▶ (run auto-closes: done)
```

Notes:

- **Two terminal paths** reach a done run without routing: `run-afk-loop`
  (after the task queue is approved) and `direct-implementation` on `:accepted`.
  The route-after-plan checkpoint chooses between them. In delegated AFK mode,
  `run-afk-loop` first pours one sequential `workflow/gate "subagent"` per
  approved task, then requires the `:human-acceptance-afk` HITL checkpoint. A
  caller may pass optional `:delegate-preamble` text; devflow prepends it to each
  delegated AFK prompt as data and remains policy-free.
- **`:abort` is reachable from every HITL (`:human`) checkpoint** — the intake
  worktree checkpoint and the four sign-off checkpoints. The two `:agent`
  checkpoints (`:discuss-scope`, `:route-after-plan`) offer no abort. Aborting
  routes to `abort-workflow`, whose `:record-abort` step then closes the run.
- Every abort choice declares a **required `:reason` input** (workflow.md §5,
  D1.2), so `choose!` fails loudly before any mutation unless the aborting call
  passes it: `(choose! feature :abort {:reason "…"})`. The feature comes from
  context; the reason comes from the input and is recorded on the abort step.
  Abort itself is routed by the registered name `:abort` (`abort-workflow`).

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

The `spec-plan`, `task-breakdown`, and `direct-implementation` stages carry a
`:revision` param too, but declare no condition on it, so their revision rounds
re-run the whole stage.

Start opts seeded into `workflow/context` by `start!` (see §4) survive every
revision loop rather than resetting to defaults, because `:revise` merges its
overrides over the carried-forward context.

`:revision` is stage-local: it is recorded as `workflow/stage-params` on the
re-poured root, and a forward hand-off (`:proposal-ready`, `:approved`,
route-after-plan's two choices) drops it from the continuation params in the
engine (workflow.md §5), so a round approved after a revise never leaks
`:revision true` into a downstream stage's context. Other start opts pass
through untouched.

## 4. Agent usage

The wrappers key everything by feature name and pass opts straight through to
the engine. `next-steps`/`next-step` (and `choice-details`/`choice-detail`)
return the same shapes as their `skein.spools.workflow` counterparts, with the
current devflow `:stage` added to each ready step view while the run has an
active stage root; the run-mutating wrappers (`start!`, `complete!`, `choose!`,
`advance!`) return the engine's `{:ready [step-view ...] :done boolean}` result.

| Wrapper | Signature | Notes |
|---|---|---|
| `start!` | `(feature)` / `(feature opts)` | Pours `intake-workflow` under `family "devflow"`. Coerces keyword `opts` values to strings and seeds them (plus `:feature`) into `workflow/context` so they survive revision loops. Returns `{:ready [...] :done boolean}`. |
| `next-steps` | `(feature)` | All ready step views for the feature (each carrying `:run-id`). |
| `next-step` | `(feature)` | The single ready step view; throws if ambiguous. |
| `complete!` | `(feature)` / `(feature opts)` | Closes the current non-checkpoint step. `opts` (`:step`, `:notes`, `:attributes`, `:by`) pass through. Returns `{:ready [...] :done boolean}`. |
| `choose!` | `(feature choice)` / `(feature choice input)` / `(feature choice input opts)` | Records the checkpoint choice and routes if the choice has a `:next`. Returns `{:ready [...] :done boolean}`. |
| `advance!` | `(feature)` / `(feature opts)` | Unified step/checkpoint driver. `opts` may include `:choice`, `:input`, `:notes`, `:step`, `:by`, and `:attributes`. Returns `{:ready [...] :done boolean}`. |
| `choice-details` | `(feature)` / `(feature opts)` | Choice explanations for the current checkpoint. |
| `choice-detail` | `(feature choice)` / `(feature choice opts)` | One choice's explanation. |
| `describe` | `()` / `(stage)` | Compile-time shape of the full devflow cycle, or one registered stage key such as `:proposal`; writes nothing. |
| `history` | `(feature)` | Ordered run history for the feature (delegates to `workflow/run-history`). |
| `archive!` | `(feature)` / `(feature opts)` | Archive a finished feature run into one closed digest strand; fails loudly while any stage root is active. |
| `feature-roots` | `(feature)` | The active root molecule for the feature as a vector (empty if none). |

There is no devflow `done?` wrapper — use `skein.spools.workflow/done?` with the
feature name.

Driving example with one revise round:

```clojure
(require '[skein.spools.devflow :as devflow])

;; feature name is the run-id; step-view's :id is the generated strand id,
;; a checkpoint's stable definition name arrives as the :checkpoint string
(devflow/start! "search-filters")
;; => {:ready [{:kind "checkpoint" :checkpoint "create-or-confirm-worktree"
;;              :choices ["created-worktree" "already-in-worktree" "abort"] ...}]
;;     :done false}

;; terminal choice — stays in the intake molecule and advances to capture-brief
(devflow/choose! "search-filters" :created-worktree {})
;; => {:ready [{:title "Capture user brief for search-filters" :artifact "brief" ...}] :done false}

(devflow/complete! "search-filters")
;; => {:ready [{:kind "checkpoint" :checkpoint "discuss-scope"
;;              :choices ["proposal-ready" "needs-more-brief"] ...}] :done false}

;; scope is clear — route to the proposal stage (fresh molecule, same feature)
(devflow/choose! "search-filters" :proposal-ready {})
;; => {:ready [{:action-ref "devflow.proposal.orient" ...}] :done false}

;; complete inspect-context, write-proposal, and the inner agent-review step
;; (its join auto-closes) until the sign-off checkpoint is ready
;; ... => {:ready [{:kind "checkpoint" :checkpoint "human-signoff-proposal"
;;                  :choices ["approved" "revise" "abort"] ...}] :done false}

;; revise: closes this proposal round and pours a fresh one; :inspect-context
;; is condition-skipped, so the round is ready at :write-proposal
(devflow/choose! "search-filters" :revise {})
;; => {:ready [{:artifact "proposal.md" :skills "devflow" ...}] :done false}

;; ... re-run write-proposal + review, reach human-signoff-proposal again ...

;; approve: route to the spec/plan stage
(devflow/choose! "search-filters" :approved {})
;; => {:ready [{:artifact "specs/*.delta.md" :skills "devflow" ...}] :done false}
```

Delegating approved AFK tasks through treadle is opt-in at task sign-off. Pass
`:tasks` and a harness when approving the task queue; task maps may be keyword-
or string-keyed (choice input often round-trips through JSON). Task `:id`
values must be token-safe strings (`[A-Za-z0-9][A-Za-z0-9._-]*`) because they
become step ids (`:task-<id>`); anything else fails loudly before any pour:

```clojure
(devflow/choose! "search-filters" :approved
  {:tasks [{:id "impl" :title "Implement filters" :body "Use the signed-off plan."}
           {:id "tests" :title "Add regression tests"}]
   :delegate-harness "pi-main"
   :delegate-cwd "/path/to/feature/worktree"})
;; => {:ready [{:gate "subagent" :title "Delegate AFK task impl for search-filters" ...}]
;;     :done false}
```

Without `:tasks`, approval keeps the legacy single `:run-afk-loop` manual step.

## 5. Registries

Devflow exposes its constructors and commands as data (stringified symbols) for
trusted resolution:

- `stage-workflows` is the map of stable routing names to stage constructors:
  `:intake`, `:proposal`, `:spec-plan`, `:route-after-plan`, `:tasks`,
  `:run-afk-loop`, `:direct-implementation`, `:agent-review`, and `:abort`.
  Forward `:next` choices reference these keyword names. `register-workflows!`
  registers each with the engine's weaver-lifetime registry
  (`skein.spools.workflow/register-workflow!`); it runs on namespace load and
  from `install!`, so a startup or reload re-points every in-flight run's named
  routes (workflow.md §5). Revision loops need no registry entry — they use
  `:revise` (§3).
- `(workflows)` returns `workflow-registry` — `stage-workflows` plus `:cycle`
  (`devflow-cycle`, the ordered composable stage list).
- `(commands)` returns `command-registry` — agent-facing commands by key:
  `:start`, `:next-step`, `:next-steps`, `:choice-details`, `:choice-detail`,
  `:choose`, `:complete`, `:advance`, `:describe`, `:history`, and `:archive`.
- `(install!)` returns `{:installed true :namespace 'skein.spools.devflow
  :commands command-registry :workflows workflow-registry :registered <map>}`,
  where `:registered` is the engine registration result.

## 6. Attribute conventions

Devflow reads and writes these attributes on strands, on top of the engine's
`workflow/*` vocabulary (workflow.md §7). Stage-level attributes sit on the root
molecule; the rest sit on individual step/checkpoint strands.

| Attribute | Meaning | Set on / by |
|---|---|---|
| `devflow/stage` | Lifecycle stage: `"intake"`, `"proposal"`, `"spec-plan"`, `"route-after-plan"`, `"tasks"`, `"afk"`, `"implementation"`, `"abort"`. | Root molecule, by each stage constructor. |
| `devflow/feature` | The feature name (same value as the run-id). | Root molecule, by each stage constructor. |
| `devflow/artifact` | Artifact a step produces (`"brief"`, `"proposal.md"`, `"specs/*.delta.md"`, `"<feature>.plan.md"`, `"tasks/index.yml"`). `step-view` surfaces it as `:artifact` (via the engine's `workflow/artifact` → `devflow/artifact` fallback). | Artifact-writing steps. |
| `devflow/task` | Stable approved AFK task id attached to delegated `run-afk-loop` task gates. | `:task-<id>` subagent gates in delegated AFK mode. |
| `workflow/hitl` | `"true"` marking a human-in-the-loop checkpoint. | Auto-stamped by the engine `checkpoint` builder for every `:kind :human` checkpoint (workflow.md §7); devflow no longer sets it by hand. |
| `workflow/decision-point` | Freeform label for what the checkpoint decides (`"worktree-ready"`, `"scope-ready"`, `"proposal-signed-off"`, `"choose-tasks-or-implementation"`, `"plan-signed-off"`, `"tasks-signed-off"`, `"afk-accepted"`, `"implementation-accepted"`). | Each checkpoint. |
| `workflow/action-ref` | Pointer to the action/skill an agent should invoke (`"devflow.worktree.ensure"`, `"devflow.proposal.orient"`, `"devflow.tasks.run-afk-loop"`, `"devflow.implementation.direct"`, `"devflow.implementation.validate"`, `"devflow.abort.record"`). Surfaced by `step-view`. | Steps/checkpoints that hand off to a named action. |
| `workflow/gate` | `"subagent"` on delegated AFK task gates; the treadle consumes these gates when installed, otherwise they remain ordinary external wait-points. | `:task-<id>` gates in delegated AFK mode. |
| `shuttle/harness` | Harness or alias requested for the delegated task run. Required for each delegated AFK gate, via task `:harness` or `:delegate-harness`. | `:task-<id>` gates in delegated AFK mode. |
| `shuttle/prompt` | Prompt sent to the delegated shuttle run, prefixed with feature/task context and then the task body or title. | `:task-<id>` gates in delegated AFK mode. |
| `shuttle/cwd` | Optional working directory for delegated AFK task runs, from `:delegate-cwd`. | `:task-<id>` gates in delegated AFK mode. |
| `workflow/instruction` | Freeform instruction text surfaced in `step-view`. | Steps/checkpoints needing explicit guidance. |
| `skills` | Skill/tool hint (`"devflow"`), surfaced in `step-view`. | The four `write-*` artifact steps (`:capture-brief` produces `"brief"` without it). |

The intake root additionally carries `devflow/worktree-check`
(`"required"` or `"already-in-worktree-ok"`), seeded from the `start!`
`:worktree-check` opt.

## 7. See also

- [Skein workflow.md](https://github.com/codethread/skein/blob/main/spools/workflow.md) — the engine this spool is built on: run
  lifecycle, checkpoints and `:next` routing, revise-by-routing loops, gates,
  molecule ops, and the full `workflow/*` attribute vocabulary.
- `(skein.spools.workflow/explain topic)` — machine-readable builder contracts
  agents can call before constructing workflow data.
- [README.md](./README.md) — this spool repo's loading notes.
