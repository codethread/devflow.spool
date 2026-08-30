# Devflow spool v22 (selectable authoring)

This release is the next `codethread/devflow` marker after the Millstrand alpha cutover. It keeps the Devflow domain and repository identity unchanged.

## Release identity

- Tag: `v22` (to be created only from canonical main after the registered land workflow succeeds).
- Repository: `codethread/devflow.spool`.
- Coordinate: `codethread/devflow`.
- Core coordinate: `io.millstrand/millstrand`.
- Core reference: immutable SHA `71c0ed3d80fcad090b74a704a8eb165a3fad996e`.
- Workflow coordinate: `millhouse.spools/workflow` at immutable SHA `f487eb42ea9523e8bd405e64a7c319013217d988`.
- Kanban coordinate: `millhouse.spools/kanban` at immutable SHA `f487eb42ea9523e8bd405e64a7c319013217d988`.
- Peeled Devflow SHA: recorded by the coordinator after the annotated tag is cut; it must equal the landed canonical-main commit.

## Verification

The release proof covers the test suite, clj-kondo across every maintained root, published card-authoring equivalence, identity, diff, and resolved coordinate checks. The vendored Millstrand clj-kondo config and hook are byte-exact copies of the producer-owned exports at the core reference.

## Non-goals

This release does not rename `strand`, `mill`, `weaver`, or the Devflow domain and repository coordinate. It uses ordinary tools.deps roots and explicit module activation.
