# Devflow guide: afk

Execute the approved task queue unattended, one slice at a time, until the queue is exhausted, blocked, or a run fails.

## Artifacts

- Task index: `devflow/feat/<feature>/tasks/index.yml`
- Plan: `devflow/feat/<feature>/<feature>.plan.md`

## Prerequisites

- proposal.md, `<feature>.plan.md`, and `tasks/index.yml` exist in the feature folder.
- The plan status is Reviewed or Active; a Draft plan routes back to plan review first.
- `tasks/index.yml` has at most one in_progress task, valid `blocked_by` ids, and at least one runnable pending or in_progress task.
- The worktree is clean, or the only dirt belongs to the single in_progress task.

## Modes

- **Delegated** — Approve the task queue (`choose! :approved`) with choice input `:tasks` (vector of `{:id :title :body :harness}` maps) plus `:delegate-harness` / `:delegate-cwd` / `:delegate-preamble`; devflow pours one sequential subagent gate per task and finishes with a human acceptance checkpoint.
- **External** — Approve without `:tasks` to keep the single run-afk-loop step, run or hand off an external loop runner, then `complete!` the step.

## Queue states

- **Exhausted** — All tasks complete: report it and move to finish/archive instead of running the loop.
- **Blocked** — No runnable task because work is blocked/HITL: report the blocked state instead of running the loop.

## Loop contract

- Select the next runnable task: pending with all `blocked_by` complete, or the single in_progress task.
- Run one slice per cycle; never run concurrent tasks in one worktree — use separate worktrees for parallelism.
- Mark the task in_progress while working and complete when finished and committed.
- Append discoveries, blockers, and follow-up notes to the plan's Developer Notes, not to the task YAML.
- Stop when tasks are exhausted, a task is blocked on human input, or a run fails.

## Procedures

### Prepare

1. Identify the feature folder; ask if ambiguous.
2. Verify proposal, plan, and task index exist and the plan is Reviewed or Active.
3. Inspect `tasks/index.yml` for a runnable queue (statuses, `blocked_by` validity, single in_progress).
4. Report exhausted or blocked queues instead of starting a loop.
5. Choose the delegated or external mode and start the loop.

## Constraints

- AFK work follows the signed-off plan and task contracts; queue-shape changes go through the tasks guide's update procedure.
- HITL tasks are never picked up by the loop; they wait for human input.
- Never edit proposal.md during the loop; discoveries go to the plan's Developer Notes and contract changes to the spec deltas.

## Validation

- Each completed slice is committed and its index status is complete
- Blockers and discoveries are recorded in the plan's Developer Notes
- The queue state (exhausted/blocked/failed) is reported when the loop stops

## See also

tasks, finish-archive — fetch another guide with `strand devflow guidance <key>`.
