# Devflow spool v21 (Millstrand alpha break)

This release is the MSR-07 cut of `codethread/devflow`. It changes the engine-facing identity from Skein to Millstrand. The Devflow domain and `codethread/devflow` repository identity remain unchanged.

## Release identity

- Tag: `v21` (to be created only from canonical main after the registered land workflow succeeds).
- Repository: `codethread/devflow.spool`.
- Coordinate: `codethread/devflow`.
- Core coordinate: `io.millstrand/millstrand`.
- Core reference: immutable SHA `3bbe5dc15359975a8e8203ef47b3a7514177e75b` from the selectable-authoring producer commit.
- Kanban release: `codethread/kanban` `v24`, `87f61bc2750e7026f3650235907db25f19b1536e`.
- Peeled Devflow SHA: recorded by the coordinator after the annotated tag is cut; it must equal the landed canonical-main commit.

## Alpha break

Active source, tests, module keys, workflow namespaces, dependency forms, and guidance now use `millstrand.*`, `io.millstrand/millstrand`, `.millstrand` or `.ms`, and `MILLSTRAND_*`. There are no `skein.*` aliases, old-coordinate fallbacks, or local-root substitutions in published release proof. Historical release records remain unchanged.

## Verification and rollback

Run these commands from the candidate checkout before landing:

```sh
clojure -M:test
bin/identity-check
bin/verify-card-authoring-equivalence
timeout 30 bin/compat-alarm v20   # expected non-zero alpha-break alarm
```

The repository-local aggregate is `.millstrand/land-quality.sh`. After landing and tagging, the coordinator reruns the same evidence against the immutable v21 tag and records the exact peeled SHA in the MSR-07 release map. Rollback means stopping consumption of v21 and pinning the previous Devflow release; no compatibility artifact or automatic namespace migration is provided.

## Non-goals

This release does not rename `strand`, `mill`, `weaver`, or the Devflow domain/repository coordinate. It does not rewrite historical archive text, stored strand attributes, or persisted workflow symbols.
