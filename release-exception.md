# Def-spool convention release exception

This record prepares `v8`. It is not a tag or a publication instruction.

- Previous marker: annotated `v7`; immutable peeled commit `20c2850dca7918810aa276f2f2dd1f484dc9fe7b`.
- Proposed marker: annotated `v8`.
- Affected root and names: `codethread/devflow`. The removed names are the nine stage constructor functions (`intake-workflow`, `proposal-workflow`, `spec-plan-workflow`, `route-after-plan-workflow`, `task-breakdown-workflow`, `run-afk-loop-workflow`, `direct-implementation-workflow`, `agent-review-workflow`, and `abort-workflow`) and `ct.spools.devflow/contribute`. Their successors are the static definition Vars `intake`, `proposal`, `spec-plan`, `route-after-plan`, `tasks`, `run-afk-loop`, `direct-implementation`, `agent-review`, and `abort`; the AFK split additionally introduces `run-afk-manual` and `run-afk-delegated`. `reconcile`, `spool`, and the old contribution registry are gone because collected `defworkflow` forms are the module contribution.
- Skein floor: the tested Skein checkout's HEAD must be `ae0888433f369dbd314ac7ab33d9d275748750f3` or a descendant of it. That commit follows `3158b8423edeefbcc7672a6176e28ed49d071a0d`, which shipped static definitions, and is the earliest commit that also ships whole-map `:param-spec` validation and choice `:input-spec` contracts. Check ancestry with `git -C /path/to/skein merge-base --is-ancestor ae0888433f369dbd314ac7ab33d9d275748750f3 HEAD`. No Skein release marker contains that commit, so `:skein/min` cannot express the requirement and this release adds none. The requirement is carried by this record and by the README prerequisite until a Skein marker can state it.
- Authorization: TEN-000 pre-v1 under kanban card `4l5ey`, user-approved.
- Known consumer: the skein-src repository only, currently pinned at `v7`.
- Compatibility alarm: `bin/compat-alarm v7` fails compiling the archived `ct.spools.devflow-test` with `No such var: devflow/spool` at line 33. The v7 fixture registers the old `devflow/spool` declaration, which static-form collection deliberately replaces; this is the approved break, and no unrelated failure is accepted.
- Decision: no compatibility shim. Retaining a `spool` or `contribute` compatibility surface would falsely imply a manually contributed module beside the collected static definitions, which the engine rejects and which would obscure the authoritative discovery surface.

Rollback is a consumer action: retain or restore the old `v7` pin and peeled SHA. Do not move or replace the old tag.
