# devflow.spool

`skein.spools.devflow` provides the devflow feature-delivery lifecycle for
[Skein](https://github.com/codethread/skein) as a git-distributed spool.

It is trusted Clojure code for a live Skein weaver. The spool has no
`spool.edn` manifest; consumption is the manifest-free contract: approve source
in `spools.edn` or `spools.local.edn`, run `sync!`, then activate explicitly
with `use!`.

Full workflow documentation lives in [devflow.md](./devflow.md). The spool is
self-contained: artifact authoring knowledge (proposal/RFC/spec/plan/task
rules and templates) ships as data in `skein.spools.devflow.guidance`, served
by the `guidance` command — no external devflow skill is required.

## Prerequisites

- A Skein checkout/runtime that ships `skein.spools.workflow`.
- A live weaver configured from a workspace you control.
- A 40-hex git SHA pin for this repository, or a local checkout approved through
  `spools.local.edn` for development.
- Network or cache access for this spool's Maven dependencies. This spool
  declares `camel-snake-kebab/camel-snake-kebab` in its top-level `deps.edn
  :deps`; `sync!` resolves it as an approved spool Maven dependency.

## Dependency information

Approve every source spool explicitly. `devflow.spool` depends on the
`skein.spools.workflow` namespace shipped by Skein, so there is no additional
third-party source spool to approve for that prerequisite.

Shared workspace example:

```clojure
{:spools {codethread/devflow {:git/url "git@github.com:codethread/devflow.spool.git"
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

Activate prerequisites before dependents. Since `skein.spools.workflow` ships
with Skein, activate or require it first according to your workspace convention,
then sync and activate this spool from trusted `init.clj` or REPL code.

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

;; Activate the shipped workflow prerequisite first. Adjust this stanza to match
;; your Skein checkout's shipped-spool convention if it already installs
;; `skein.spools.workflow` elsewhere in startup.
(runtime/use! runtime
  :workflow
  {:ns 'skein.spools.workflow
   :required? true})

(runtime/sync! runtime)

(runtime/use! runtime
  :devflow
  {:spools [codethread/devflow]
   :ns 'skein.spools.devflow
   :call 'skein.spools.devflow/install!
   :after [:workflow]
   :required? true})
```

Keep the `:workflow` activation before `:devflow` and keep `:after [:workflow]`
so missing or failed prerequisites are explicit.
