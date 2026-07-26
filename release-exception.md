# Defer-return release record

This record prepares `v10`. It is not a tag or a publication instruction.

- Previous marker: annotated `v9`; immutable peeled commit `499c9d6c51f28cd3a5d6de28718df082118ff4cc`.
- Proposed marker: annotated `v10`.
- Affected root: `codethread/devflow`.
- Skein floor: the tested Skein checkout's HEAD must be `70a3c50e27ca0190f363d80d0b0cac72948dbacb` or a descendant. That merge removes the old `continue!` worker surface and makes defer runtime-selected returning composition whose target advertises `:call`. No Skein release marker contains that commit, so `:skein/min` cannot express the requirement and this release adds none.
- Devflow contract: every lifecycle stage reached by authored checkpoint `:next` continues to advertise `:continue`, because Skein deliberately retains that entrypoint for root transfer. Those stages now also advertise `:call`, so fixed calls and runtime-selected defers may execute them as returning procedures. `intake` remains the `:start` definition and `agent-review` remains a call-only procedure. Registered names, checkpoint routes, parameters, stage molecules, and user-visible lifecycle behavior are unchanged.
- Engine cutover: this release must be installed only after the workspace follows Skein's `docs/spools/defer-return-cutover.md`. The clean break is in the workflow engine's removed worker operations and persisted defer meaning; it does not delete devflow's authored checkpoint-transfer capability.
- Compatibility alarm: `bin/compat-alarm v9` can and should pass. The devflow change is additive under its published names, and the frozen v9 suite exercises the retained checkpoint routes against the candidate source. A failure is not an accepted consequence of Skein's clean engine cutover.
- Authorization: the user's explicit defer-return rollout instruction.
- Known consumer: skein-src. Its devflow pin moves only after this candidate is validated and released.
- Decision: no false `:continue` removal. Replacing it with `:call` would make every checkpoint `:next` reference fail registration under Skein `70a3c50e`; advertising both entrypoints preserves the stage graph while adding returning composition.

Rollback is a consumer action: retain or restore the old `v9` pin and peeled SHA. Do not move or replace the old tag.
