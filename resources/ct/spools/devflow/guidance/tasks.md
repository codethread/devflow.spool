# Devflow guide: tasks

A deterministic, feature-local AFK task queue: tracer-bullet vertical slices an unattended agent can execute one at a time, referencing the proposal/spec/RFC/plan instead of duplicating rationale.

## Artifacts

- Task index: `devflow/feat/<feature>/tasks/index.yml`
- Task file: `devflow/feat/<feature>/tasks/<zero-padded-id>-<slug>.md`
- Plan: `devflow/feat/<feature>/<feature>.plan.md`

## Prerequisites

- proposal.md exists and `<feature>.plan.md` is Reviewed; for small obvious work, create a minimal plan and mark it Reviewed after a lightweight sanity check.
- Relevant proposal, plan, feature specs, root specs, RFCs, and affected code/tests have been read; inspect the codebase when the implementation area is unclear.
- Tasks belong to exactly one active feature folder; ask for the feature name when it cannot be inferred.

## Index schema

Fields:

- `id` — Integer, starting at 1, increasing by one.
- `description` — Short enough to use in a session name; prefix [HITL] for HITL tasks.
- `task_file` — Detailed task markdown path: three-digit zero-padded id plus slug (`tasks/001-terse-task-title.md`).
- `status` — One of pending, in_progress, blocked, complete.
- `blocked_by` — List of task ids that must be complete before this task can run.

Statuses:

- **pending** — Ready to start when dependencies are complete.
- **in_progress** — Selected or being continued.
- **blocked** — Needs human input; skipped by the AFK loop.
- **complete** — Finished and committed.

Rules:

- Do not add extra YAML fields; put notes in the feature plan, not the index.
- Dependencies live only in `blocked_by`, never in task markdown prose.

## Slicing

- Each task delivers a narrow but complete path through the relevant integration layers, not a horizontal layer-only change.
- Each completed task is independently verifiable.
- Prefer many thin slices over a few broad slices, and AFK-ready slices where possible.
- Keep slices small enough for one agent run; prefer a workable MVP over comprehensive scope.
- Put human/architectural uncertainty into the plan's Task context or Developer Notes, not hidden in task scope.

## AFK vs HITL classification

- **AFK** — Safe for an unattended agent loop: clear contract, enough context, deterministic validation, no user decisions, credentials, design judgment, or external access needed.
- **HITL** — Requires human interaction first: an architectural decision, product/design choice, unclear acceptance criteria, secret/access setup, manual QA, or a meaningful tradeoff.

Rules:

- Prefer AFK; do not mark a slice HITL just because it is complex — split complex work into smaller AFK slices.
- No YAML type field; the index schema is fixed.
- AFK tasks use status pending, with `blocked_by` for dependencies (never status blocked for dependency waits).
- HITL tasks prefix the description with [HITL] and use status blocked until the human input exists.
- In each task file, put `Type: AFK` or `Type: HITL` as the first line under Scope.
- If HITL produces a decision that unlocks implementation, make the decision task HITL and create separate AFK implementation tasks blocked by it.

## Specificity

- Task files may be more specific than the plan: name exact files, functions, commands, fixtures, and assertions when unattended execution needs them.
- Keep rationale short; link to the RFC/spec/proposal/plan for why and high-level how.
- Translate plan phases into narrow implementation contracts; never copy phase prose into every task.
- Include only references the implementer must inspect or change.

## Procedures

### Create the queue

1. Gather context: read the proposal, Reviewed plan, feature specs, root specs, RFCs, and affected code.
2. Confirm the feature folder; tasks must belong to exactly one.
3. Draft tracer-bullet vertical slices per the slicing rules.
4. Classify every slice AFK or HITL per the classification rules.
5. Write `tasks/index.yml` and one task file per slice from the templates, allocating TASK IDs per the ID convention.
6. Record task context, important references, and strategy in the plan's Task context section.
7. Request review: check the full sequence for ordering issues and dependency deadlocks, each task file for standalone clarity, and queue/plan/spec cohesion against the MVP goal.

### Update the queue

1. Read `tasks/index.yml`, the feature plan, and relevant task files before editing.
2. Preserve task ids, file names, and history for existing tasks unless the task has not started and the change is purely clarifying.
3. Do not edit completed task files except to fix formatting/references that break the queue.
4. Prefer adding follow-up tasks with the next integer ids over rewriting old tasks; never use decimal ids. Use `blocked_by` to slot new work after its prerequisites.
5. Narrow a too-broad pending task in place only when no agent has started it; for in-progress or completed tasks, keep the published contract intact and extract follow-up tasks.
6. Append amendment rationale to the plan's Developer Notes; never hide important plan changes in task files.

## Constraints

- Create or update the queue only; do not implement the tasks.
- Make dependencies explicit and minimal.
- No speculative future work unless needed to protect the MVP boundary.
- Tasks are not durable documentation: root specs own durable outcomes.
- Acceptance criteria belong in Done when; follow-up ideas belong in the plan's Developer Notes.
- {{id-editing}}

## Validation

- `tasks/index.yml` matches the fixed schema with integer ids and valid statuses
- Every task has a task file at the three-digit zero-padded path
- Every task file has a stable TASK ID, Type line under Scope, Must implement exactly, Done when, Out of scope, and References
- Dependencies are encoded only in `blocked_by` and contain no cycles
- HITL slices are [HITL]-prefixed and blocked; AFK slices are pending
- Task context and strategy are recorded in the feature plan

## Templates

### Task index

{{template:task-index.yml}}

### Task file

{{template:task-file.md}}

### Plan notes

{{template:plan-notes.md}}

## See also

plan, afk — fetch another guide with `strand devflow guidance <key>`.
