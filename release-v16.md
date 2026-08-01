# v16 release record: guidance becomes markdown, served over the CLI

- Previous marker: annotated `v15`.
- Proposed marker: annotated `v16`.
- Affected root: `codethread/devflow`.

## Deliberate break under a published name

`(ct.spools.devflow/guidance)` and `(guidance <key>)` now return markdown
strings instead of EDN map trees. The map shape was judged to have no value
over prose for its one consumer class (agents reading authoring rules), and
keeping a map-returning twin alive would preserve a shape nobody wants
maintained. The knowledge itself is unchanged; it moved verbatim into
`resources/ct/spools/devflow/guidance/*.md`.

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

## Accretion

- New `devflow` op: `strand devflow guidance [<guide>]`, a read-only static
  surface registered by the existing `:devflow` module activation. No run
  verbs; the generic `workflow` op remains the only run driver.
- `deps.edn` `:paths` gains `"resources"`; the spool loader honors declared
  paths, so consumers need no config change beyond the sha bump.
- Step `workflow/instruction` text now names the CLI command instead of the
  Clojure call. `devflow/guide` attribute semantics are unchanged.

Rollback is a consumer action: retain or restore the `v15` pin and its peeled
SHA. Do not move or replace old tags.
