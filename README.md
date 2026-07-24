# devflow.spool

`ct.spools.devflow` provides the devflow feature-delivery lifecycle for
[Skein](https://github.com/codethread/skein) as a git-distributed spool.

It is trusted Clojure code for a live Skein weaver. The spool has no
implicit installation contract: approve source in the consumer's `spools.edn`
or `spools.local.edn`, then declare its module explicitly from trusted startup
or REPL code. The tracked singular `spool.edn` is advisory producer metadata
for authoring tools; the Skein core loader does not read it.

Full workflow documentation lives in [devflow.md](./devflow.md). The spool is
self-contained: artifact authoring knowledge (proposal/RFC/spec/plan/task
rules and templates) ships as data in `ct.spools.devflow.guidance`, served
by the `guidance` command — no external devflow skill is required.

## Prerequisites

- A Skein checkout whose HEAD is
  `343f886880092bc38ed3e0522eca2d95a7cf04bc` or a descendant of it. That commit
  taught the refresh coordinator to resolve a spool's entry points from its
  `spool` var. Verify a checkout with
  `git -C /path/to/skein merge-base --is-ancestor 343f886880092bc38ed3e0522eca2d95a7cf04bc HEAD`.
  No Skein release marker contains that commit yet, so the advisory `spool.edn`
  declares no `:skein/min` floor. The requirement is carried by this line and
  by `release-exception.md`.
- Older Skein can accept the source-mode declaration below and report the
  module applied while publishing an empty contribution. Devflow's routes are
  then absent. After activation, inspect `(runtime/status runtime)` for
  devflow's resolved entry points and contribution, and confirm the expected
  route keys in `(skein.spools.workflow/workflows)`.
- `skein.spools.workflow` is one of Skein's in-repo reference
  spools, living in a spool root (`<skein>/spools/workflow`) **off** the base
  classpath — you approve that root in `spools.edn` like any other spool.
- A live weaver configured from a workspace you control.
- A 40-hex git SHA pin for this repository, or a local checkout approved through
  `spools.local.edn` for development.
- Network or cache access for this spool's Maven dependencies. This spool
  declares `camel-snake-kebab/camel-snake-kebab` in its top-level `deps.edn
  :deps`; module refresh resolves it as an approved spool Maven dependency.

## Dependency information

Approve every source spool explicitly; no prerequisite is fetched
transitively. `devflow.spool` requires `skein.spools.workflow`, which you
approve as a root inside your Skein checkout (or as a sha-pinned nested-root
git coordinate on the Skein repo — `:git/url` + `:git/sha` +
`:deps/root "spools/workflow"` — if you want the engine pinned independently
of your checkout). Both coordinate forms and the version-skew convention are
covered in [Skein's nested-spool prerequisites
guidance](https://github.com/codethread/skein/blob/main/docs/spools/writing-shared-spools.md#nested-spool-prerequisites).

Shared workspace example:

```clojure
{:spools {skein.spools/workflow {:local/root "/path/to/your/skein/spools/workflow"}
          codethread/devflow {:git/url "git@github.com:codethread/devflow.spool.git"
                              :git/sha "<40-hex-sha-for-the-approved-commit>"}}}
```

Local development overlay example (`spools.local.edn`, usually gitignored):

```clojure
{:spools {codethread/devflow {:local/root "/Users/you/dev/devflow.spool"}}}
```

Do not use the producer's singular `spool.edn` as consumer approval. Copy and
review the full family entry above in the consumer's plural `spools.edn`.
Prerequisites and activation order remain explicit here. Once a compatible
Skein release marker exists, the producer floor belongs in `spool.edn` as
`:skein/min`.

## Activation

Declare prerequisite modules before dependents. From trusted `init.clj` or REPL
code:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

;; workflow is an approved spool root, not base-classpath code: guard the
;; module on its coordinate so a missing approval fails loudly.
(runtime/module! runtime
  :workflow
  {:ns 'skein.spools.workflow
   :spools ['skein.spools/workflow]
   :required? true})

(runtime/module! runtime
  :devflow
  {:spools ['codethread/devflow]
   :ns 'ct.spools.devflow
   :after [:workflow]
   :required? true})
```

A declaration names a source target and world policy only. Both spools export a
public `spool` var holding their `:contribute`/`:reconcile` symbols, and the
refresh coordinator resolves it at every module evaluation, so no consumer
mirrors the pair. Devflow's is `ct.spools.devflow/spool`.

Keep the `:workflow` module before `:devflow` and keep `:after [:workflow]`
so missing or failed prerequisites are explicit. A module refresh publishes
devflow's routes as a complete owner contribution: omissions remove routes,
while an in-flight named transition resolves the current constructor when taken.
