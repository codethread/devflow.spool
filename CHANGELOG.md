# Changelog

One entry per release marker, newest first. Markers are annotated, ordered
`v<int>` tags; consumers pin the **peeled commit sha** recorded with each
entry, never the tag object. From `v1` published names are accretion-only —
breaks are the exception and each one is recorded here with its rationale,
its `bin/compat-alarm` result against the previous marker, and who authorized
it. (Older tag messages reference `release-exception.md` / `release-v16.md`;
those records are folded into this file.)

## v16 — guidance served as markdown over `strand devflow guidance`

Peeled sha: `1455dce2d65a6f81eea21a5aac2d8cac26e442bd`

**Deliberate break under a published name.** `(ct.spools.devflow/guidance)`
and `(guidance <key>)` now return markdown strings instead of EDN map trees.
The map shape was judged to have no value over prose for its one consumer
class (agents reading authoring rules), and keeping a map-returning twin
alive would preserve a shape nobody wants maintained. The knowledge itself is
unchanged; it moved verbatim into `resources/ct/spools/devflow/guidance/*.md`.

- Compatibility alarm: `bin/compat-alarm v15` fires (1 error in
  `guidance-and-artifact-metadata-remain-devflow-owned`). That is this break
  being caught, not a regression: the frozen suite asserts the old map shape
  and the old REPL-call instruction text, and its classpath predates the
  spool's `resources/` path.
- Authorization: the user's explicit instruction to convert guidance to
  markdown and release (2026-08-01).
- Known consumers: skein-src's devflow pin and this repo's own `.skein` world
  (`:local/root`). Neither reads the guidance value structurally; both move
  deliberately with the pin.

Accretion:

- New `devflow` op: `strand devflow guidance [<guide>]`, a read-only static
  surface registered by the existing `:devflow` module activation. No run
  verbs; the generic `workflow` op remains the only run driver.
- `deps.edn` `:paths` gains `"resources"`; the spool loader honors declared
  paths, so consumers need no config change beyond the sha bump.
- Step `workflow/instruction` text now names the CLI command instead of the
  Clojure call. `devflow/guide` attribute semantics are unchanged.

## v15 — expose lifecycle definitions directly

Peeled sha: `ea9b3f00d505f8ec76ab6bb9216d1b20560c5210`

**Breaking:** remove Devflow's runtime facade. Start and drive intake and its
routed stages through Skein's generic workflow API and CLI instead.

Devflow now publishes `devflow-runs` and `devflow-ready` queries for
resumable runs and actionable work. The workspace activates the workflow and
batteries CLI modules to expose those surfaces.

Validation: current suite passed (6 tests, 51 assertions); real-weaver CLI
smoke test passed. `compat-alarm v14` fails as expected because v14's suite
calls the removed facade.

## v14 — reviewed card decomposition and authored Workflow activation

Peeled sha: `179efcc8531379e8ae6626348bfdbe028d7a1f90`

Contract change under published names: the `approved-to-cards` route now
requires `feature-card-reviewer` and `epic-card-reviewer` parameters, then
fans focused card reviews into an epic cohesion review before accepting the
decomposition. The new `review-cards` stage and card-review guidance are
accreted.

Compatibility fix: fresh test worlds activate the Workflow dependency from
source so authored declarations are collected before publication; this
supports Skein's forms-only Workflow module without an image callback
fallback.

`compat-alarm v13`: one failure and one error, both classified to the
card-review contract change above. v13's frozen stage list rejects the
accreted `review-cards` stage, and its old `approved-to-cards` call omits the
new reviewer parameters. The suite passed 35 tests and 240 assertions against
Skein main and the forms-only Workflow candidate.

## v13 — cards route (`approved-to-cards` → `land-proposal` → `decompose`)

Peeled sha: `c056282702c14feba84ea6b1538cdcc0f3043734`

Accretion under published names: new sign-off choice `approved-to-cards`, new
stages `land-proposal` and `decompose`, new `:decompose` guidance guide. The
old `approved` → spec-plan/AFK path is registered intact and sidelined.

`compat-alarm v12`: one classified failure, zero errors. v12's frozen
`route-symbols-cover-every-published-stage` pins `stage-workflows` keys
against its closed-world 11-entry list, so any accreted stage trips it;
classified as accretion per writing-shared-spools (no published name changed
behavior; the frozen `:approved` → `:spec-plan` assertion stays green).
`approved-to-cards` targets `land-proposal` by definition symbol so the
already-published proposal definition demands no new registration-set entry
from existing consumers.

## v12

Peeled sha: `8512b6a01891a2a5e7a4f440056672de0d9f1cb6`

No release notes were recorded for this marker.

## v11

Peeled sha: `c4a2dabc02c8649f5d3a9f71998baaf6fc909712`

No release notes were recorded for this marker.

## v10 — returning stage composition (defer-return cutover)

Peeled sha: `c77486955825e5d4918a4928914578cad61ed08f`

- Skein floor: the tested Skein checkout's HEAD must be
  `70a3c50e27ca0190f363d80d0b0cac72948dbacb` or a descendant. That merge
  removes the old `continue!` worker surface and makes defer runtime-selected
  returning composition whose target advertises `:call`. No Skein release
  marker contains that commit, so `:skein/min` cannot express the requirement
  and this release added none.
- Devflow contract: every lifecycle stage reached by authored checkpoint
  `:next` continues to advertise `:continue`, because Skein deliberately
  retains that entrypoint for root transfer. Those stages now also advertise
  `:call`, so fixed calls and runtime-selected defers may execute them as
  returning procedures. `intake` remains the `:start` definition and
  `agent-review` remains a call-only procedure. Registered names, checkpoint
  routes, parameters, stage molecules, and user-visible lifecycle behavior
  were unchanged.
- Engine cutover: this release must be installed only after the workspace
  follows Skein's `docs/spools/defer-return-cutover.md`. The clean break is
  in the workflow engine's removed worker operations and persisted defer
  meaning; it does not delete devflow's authored checkpoint-transfer
  capability.
- Compatibility alarm: `bin/compat-alarm v9` passed. The devflow change is
  additive under its published names, and the frozen v9 suite exercises the
  retained checkpoint routes against the candidate source.
- Authorization: the user's explicit defer-return rollout instruction.
- Decision: no false `:continue` removal. Replacing it with `:call` would
  make every checkpoint `:next` reference fail registration under Skein
  `70a3c50e`; advertising both entrypoints preserves the stage graph while
  adding returning composition.

## v9

Peeled sha: `499c9d6c51f28cd3a5d6de28718df082118ff4cc`

No release notes were recorded for this marker.

## v8 — static defworkflow stage definitions

Peeled sha: `980961cf0d0d730741d5ba65330f589dfcb1d88d`

**Breaking.** The nine stage constructor fns and
`ct.spools.devflow/contribute` are removed, along with the `reconcile` and
`spool` vars: devflow's module contribution is now the entries its
`defworkflow` forms collect, and a module may not both collect authoring
forms and supply `:contribute` (skein SPEC-004.C46).

Every stage is a static Var named for its route, with a whole-map
`:param-spec` and `:defaults`, and both checkpoint choice inputs name a spec
rather than a per-key vector. The AFK stage's manual-vs-delegated decision is
now a checkpoint choice (PROP-Wcd-001.EX6) across `run-afk-loop`,
`run-afk-manual` and `run-afk-delegated`.

Skein floor `ae0888433f369dbd314ac7ab33d9d275748750f3` or a descendant.

## v7 — engine outcome cutover

Peeled sha: `20c2850dca7918810aa276f2f2dd1f484dc9fe7b`

Adapts to the workflow engine dropping `:notes` from `complete!`/`advance!`.
Stage outcome vocabulary rides `:attributes`. Compatible with Skein before
and after the cutover; the v6 compat alarm fires on the engine's removed
argument, not on a devflow contract change.

## v6 — adopt the public def spool convention

Peeled sha: `7e86aa9d65546b7ca795202411e464bde5b39baf`

## v5 — retire install!; module lifecycle is the one activation path

Peeled sha: `98ecdd8a2fe15e4deebc83ec94596337162b46a1`

## v4 — test-only compatibility release for the install! retirement epic

Peeled sha: `7135d8c296ec712ff48ec8cc48c5ff0e058e2088`

Converts the suite's workflow activation from
`skein.spools.workflow/install!` to image-mode `module!` so the suite passes
pinned against skein-src with the in-tree installers deleted. No `src/`
changes; exports unchanged (compat-alarm vs v3 clean). The full devflow
install! retirement shipped in the next marker.

## v3 — owner-scoped live refresh

Peeled sha: `b966e540624a19c547093b6f2468fa3bb5d71103`

## v2 — register stage workflows under a runtime, not at ns-load

Peeled sha: `1e65e1bc5ce43cbea462a33f51b24b669928ef4b`

Skein's workflow registry moved to runtime-owned spool-state, so
`register-workflow!` now needs an active runtime. Drop the namespace-load
registration (no runtime exists there) and register only from `install!`;
the test fixture registers per disposable world so named `:next` routes
resolve against the runtime-owned registry.

## v1 — first promise

Peeled sha: `9b0296a37b7ad8968c4630bbe676c3a4a0cf5df5`

Accretion-only under these names from here; breaks ship as new names.
