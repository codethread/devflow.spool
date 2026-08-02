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
| `codethread/kanban` | `v20` |

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
- **`decompose-kanban`** — devflow's published `decompose-open` template bound
  with `#{author-card-strands author-kanban-cards}`, so the defer's worker
  chooses between the strand-native default and the board per feature.
- **`devflow-projection`** / **`bind-devflow-tracker!`** — devflow bound as
  kanban's card tracker: `kanban card <id>` on a card stamped via
  `kanban claim <id> --run-id <feature>` shows the run's stage and ready
  frontier.
- **`repoint-decompose!`** — re-points the routed `:decompose` stage name at
  `decompose-kanban` in the registry's direct layer, so `land-proposal`'s
  landed choice routes into the kanban-bound variant.

## Consuming it

One repository is one family entry; opt into this root by mapping it in your
existing `codethread/devflow` entry and declaring the kanban floor:

```clojure
;; .skein/spools.edn
{:spools
 {codethread/devflow
  {:git/url "https://github.com/codethread/devflow.spool.git"
   :git/tag "v18" :git/sha "<peeled sha of v18>"
   :roots {codethread/devflow "."
           codethread/devflow-kanban-adapter "kanban-adapter"}
   :requires {codethread/kanban "v20"}}
  codethread/kanban
  {:git/url "https://github.com/codethread/kanban.spool.git"
   :git/tag "v20" :git/sha "398a1610d094c85ad9c6691dafd213aaa35d73de"
   :roots {codethread/kanban "."}}}}
```

Activate it after both providers:

```clojure
;; .skein/init.clj
(runtime/module! runtime :devflow/kanban-adapter
  {:ns 'ct.spools.devflow-kanban-adapter
   :spools ['codethread/devflow-kanban-adapter 'codethread/devflow 'codethread/kanban]
   :after [:devflow :skein/spools-kanban]
   :required? true})
```

The two re-bindings live in the registry's direct layer and in runtime state,
so they are lifecycle seeds, not module declarations — take either or both:

```clojure
(lifecycle/defseed devflow-kanban-adapter-tracker
  "Bind devflow as this world's kanban card tracker."
  {:apply 'ct.spools.devflow-kanban-adapter/bind-devflow-tracker!})

(lifecycle/defseed devflow-kanban-adapter-decompose
  "Route the :decompose stage name at the kanban-bound variant."
  {:apply 'ct.spools.devflow-kanban-adapter/repoint-decompose!})
```

Without the second seed, `decompose-kanban` stays reachable by its own name
(`strand workflow show decompose-kanban`) while the routed `:decompose` keeps
devflow's strand-native default.
