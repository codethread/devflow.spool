# Devflow spool v22 (selectable authoring)

This release is the next `codethread/devflow` marker after the Millstrand alpha cutover. It keeps the Devflow domain and repository identity unchanged.

## Release identity

- Tag: `v22` (to be created only from canonical main after the registered land workflow succeeds).
- Repository: `codethread/devflow.spool`.
- Coordinate: `codethread/devflow`.
- Core coordinate: `io.millstrand/millstrand`.
- Core reference: immutable SHA `3bbe5dc15359975a8e8203ef47b3a7514177e75b`.
- Workflow coordinate: `millhouse.spools/workflow` at immutable SHA `f1cdda3b46706b186f547251d285791be650d232`.
- Kanban release: `codethread/kanban` `v24`, `87f61bc2750e7026f3650235907db25f19b1536e`.
- Peeled Devflow SHA: recorded by the coordinator after the annotated tag is cut; it must equal the landed canonical-main commit.

## Verification

The release proof covers the test suite, clj-kondo across every maintained root, published card-authoring equivalence, identity, diff, and resolved coordinate checks. The vendored Millstrand clj-kondo config and hook are byte-exact copies of the producer-owned exports at the core reference.

## Non-goals

This release does not rename `strand`, `mill`, `weaver`, or the Devflow domain and repository coordinate. It retains the external Kanban v24 pairing.
