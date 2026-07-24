# Spool installer retirement release exception

This record prepares `v5`. It is not a tag or a publication instruction.

- Previous marker: annotated `v4`; immutable peeled commit `7135d8c296ec712ff48ec8cc48c5ff0e058e2088`.
- Proposed marker: annotated `v5`.
- Affected root and names: `codethread/devflow`; the removed name is `ct.spools.devflow/install!`, the no-op pre-module metadata shim. Activation is `contribute`/`reconcile` via the module lifecycle; the newly exported `ct.spools.devflow/module` datum is the authored declaration source (ADR-003.P7 in skein-src's devflow record).
- Authorization: TEN-000@1 removal recorded by skein-src ADR-003.P5 (epic waq0l, feature 9snqu) — retiring `install!` everywhere so the module lifecycle is the one activation path.
- Known consumer: the skein-src repository only. Its current immutable old pin remains `v4` at the peeled commit above until the epic's consumer-cutover feature bumps it.
- Compatibility alarm: `bin/compat-alarm v4` is expected to fail compiling archived `ct.spools.devflow-test` (`No such var: devflow/install!` at its line 131) because the archived suite calls the removed shim. This is the approved lifecycle break; no unrelated failure is accepted.
- Decision: no compatibility shim. The shim WAS the compatibility layer — its docstring already redirected to `contribute`/`reconcile`, and keeping it would preserve the retired activation path this release exists to delete.

Rollback is a consumer action: retain or restore the old `v4` pin and peeled SHA. Do not move or replace the old tag.
