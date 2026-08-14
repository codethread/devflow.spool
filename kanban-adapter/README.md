# devflow-kanban-adapter

The kanban binding for devflow's pluggable seams, shipped as its own root
(`codethread/devflow-kanban-adapter`) so the main `codethread/devflow` root stays
coupled to no card system. If your workspace runs both devflow and the
[kanban spool](https://github.com/codethread/kanban.spool), activate this root
instead of hand-rolling the same glue.

## Dependencies

Unlike the main devflow root, this root **requires the kanban spool**:

| Root | Floor |
|---|---|
| `codethread/devflow` | same repository, same marker |
| `codethread/kanban` | `v24` |

## What it ships

- **`author-kanban-cards`** — a call-only card-authoring target for devflow's
  decompose defer. It instructs the driving agent to author the breakdown on
  the board: one epic card grouping the set (`kanban add --type epic`), one
  feature card per independently landable outcome (`--epic <id>`, body per the
  cold-card contract), and landing-order constraints as `depends-on` edges via
  `strand update <dependent> --edge depends-on:<blocker>` (kanban has no verb
  for card-to-card dependencies). The feature cards' strand ids are the card
  ids the review handoff expects; the grouping-only epic stays out of the
  review set.
- **`decompose-kanban`** — devflow's published `decompose-open` template bound with `#{author-card-strands author-kanban-cards}`, so the defer's worker chooses between the strand-native default and the board per feature.
- **`repoint-decompose!`** — re-points the routed `:decompose` stage name at `decompose-kanban` in the registry's direct layer, so `land-proposal`'s landed choice routes into the kanban-bound variant. Its exact public input is `{:runtime <active Millstrand runtime>}` and its exact result is `{:repointed :decompose}`; extra or missing keys fail with allowed/received diagnostics. The lifecycle-context adapter is `repoint-decompose-seed!`, which validates the owning `::repoint-seed-context` spec: `:runtime` is required, and any additional keyword metadata keys with arbitrary values are accepted.

## Consuming it

One repository is one family entry; opt into this root by mapping it in your
existing `codethread/devflow` entry and declaring the kanban floor:

```clojure
;; .millstrand/spools.edn
{:spools
 {codethread/devflow
  {:git/url "https://github.com/codethread/devflow.spool.git"
   :git/tag "v22" :git/sha "<peeled sha of v22>"
   :roots {codethread/devflow "."
           codethread/devflow-kanban-adapter "kanban-adapter"}
   :requires {codethread/kanban "v24"}}
  codethread/kanban
  {:git/url "https://github.com/codethread/kanban.spool.git"
   :git/tag "v24" :git/sha "87f61bc2750e7026f3650235907db25f19b1536e"
   :roots {codethread/kanban "."}}}}
```

Activate it after both providers:

```clojure
;; .millstrand/init.clj
(runtime/module! runtime :devflow/kanban-adapter
  {:ns 'ct.spools.devflow-kanban-adapter
   :spools ['codethread/devflow-kanban-adapter 'codethread/devflow 'codethread/kanban]
   :after [:devflow :millstrand/spools-kanban]
   :required? true})
```

The `:decompose` re-point lives in the registry's direct layer, so it is a
lifecycle seed, not a module declaration:

```clojure
(lifecycle/defseed! devflow-kanban-adapter-decompose
  "Route the :decompose stage name at the kanban-bound variant."
  {:apply 'ct.spools.devflow-kanban-adapter/repoint-decompose-seed!})
```

`defseed!` is an idempotent process-lifetime lifecycle effect. The coordinator invokes its `:apply` callable once for each weaver generation, passing a context map whose `:runtime` is the active runtime plus lifecycle metadata. The adapter validates that context against `::repoint-seed-context`, whose open metadata policy accepts additional keyword keys with arbitrary values, then projects it into `repoint-decompose!`; the direct registry entry is therefore re-established after every refresh. The lifecycle result is data, `{:repointed :decompose}`, which the seed runner records as the effect result; it is not a module declaration or a workflow step handle. Without the seed, `decompose-kanban` stays reachable by its own name (`strand workflow show decompose-kanban`) while the routed `:decompose` keeps devflow's strand-native default.
