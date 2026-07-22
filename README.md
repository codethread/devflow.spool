# devflow.spool

`ct.spools.devflow` provides the devflow feature-delivery lifecycle for
[Skein](https://github.com/codethread/skein) as a git-distributed spool.

It is trusted Clojure code for a live Skein weaver. The spool has no
`spool.edn` manifest; consumption is the manifest-free contract: approve source
in `spools.edn` or `spools.local.edn`, then declare its module explicitly from
trusted startup or REPL code.

Full workflow documentation lives in [devflow.md](./devflow.md). The spool is
self-contained: artifact authoring knowledge (proposal/RFC/spec/plan/task
rules and templates) ships as data in `ct.spools.devflow.guidance`, served
by the `guidance` command — no external devflow skill is required.

## Prerequisites

- A Skein checkout. `skein.spools.workflow` is one of Skein's in-repo reference
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

Do not copy a `spool.edn`; this repository intentionally does not ship one.
Metadata, prerequisites, and activation order are documented here rather than
encoded in a manifest.

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
   :contribute 'skein.spools.workflow/contribute
   :reconcile 'skein.spools.workflow/reconcile
   :required? true})

(runtime/module! runtime
  :devflow
  {:spools ['codethread/devflow]
   :ns 'ct.spools.devflow
   :contribute 'ct.spools.devflow/contribute
   :reconcile 'ct.spools.devflow/reconcile
   :after [:workflow]
   :required? true})
```

Keep the `:workflow` module before `:devflow` and keep `:after [:workflow]`
so missing or failed prerequisites are explicit. A module refresh publishes
devflow's routes as a complete owner contribution: omissions remove routes,
while an in-flight named transition resolves the current constructor when taken.
