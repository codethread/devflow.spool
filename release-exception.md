# Explicit-runtime release exception

This record prepares `v9`. It is not a tag or a publication instruction.

- Previous marker: annotated `v8`; immutable peeled commit `980961cf0d0d730741d5ba65330f589dfcb1d88d`.
- Proposed marker: annotated `v9`.
- Affected root: `codethread/devflow`.
- Breaking change: every runtime-dependent public workflow facade now takes the target runtime as its first argument. Static workflow definitions, registered names, routes, parameters, and CLI behavior are unchanged.
- Reason: devflow is a shared spool and must work in unpublished runtimes and in JVMs containing more than one runtime. Its public facade no longer selects a process-wide ambient world.
- Consumer cutover: pass the module or test runtime explicitly, for example `(devflow/start! runtime feature opts)` and `(devflow/ready runtime feature)`.
- Floor: none. Compatibility remains documented and tested without adding a `:skein/min` requirement.
- Authorization: TEN-000@1 and the user's explicit instruction to make the required breaking changes.
- Known consumer: skein-src. Its tracker and CLI adapters move to the explicit runtime API in the same coordinated release.
- Compatibility alarm: the frozen `v8` suite fails at the changed public calls. Calls whose old and new arity sets overlap interpret the former feature argument as a runtime and fail at the runtime boundary; the others fail with arity errors. `bin/compat-alarm v8` currently reports 15 errors across those two expected classes. No unrelated failure is accepted.
- Decision: no ambient-runtime compatibility arities. Keeping them would preserve the cross-runtime bug this release removes.

Rollback is a consumer action: retain or restore the old `v8` pin and peeled SHA. Do not move or replace the old tag.
