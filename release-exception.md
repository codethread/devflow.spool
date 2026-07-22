# Owner-scoped live refresh release exception

This record prepares `v3`. It is not a tag or a publication instruction.

- Previous marker: lightweight `v2`; immutable commit `1e65e1bc5ce43cbea462a33f51b24b669928ef4b` (the peeled value is the same).
- Proposed marker: annotated `v3`.
- Affected root and names: `codethread/devflow`; route publication uses `ct.spools.devflow/contribute` and `ct.spools.devflow/reconcile`. The removed legacy helper is `ct.spools.devflow/register-workflows!`.
- Required Skein range: the owner-scoped live-refresh candidate from `b8be0c8` through `91bec8ac0caf1cb21bf1119d4b253d4601159ecb` (the latter is the release-preparation baseline).
- Known consumer: this Skein repository only. Its current immutable old pin remains `v2` at the commit above until human approval changes it.
- Compatibility alarm: `bin/compat-alarm v2` is expected to fail at archived `ct.spools.devflow-test` because it resolves the removed `devflow/register-workflows!`. This is the approved lifecycle break. The alarm now mirrors the owner suite's Skein and workflow local roots; no unrelated failure is accepted.
- Decision: no compatibility shim. A shim would preserve the retired route-registration path rather than requiring explicit module publication.

Rollback is a consumer action: retain or restore the old `v2` pin and commit. Do not create or move an old tag.
