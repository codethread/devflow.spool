(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

(runtime/module! runtime :millstrand/spools-batteries
  {:ns 'millstrand.spools.batteries
   :spools ['millstrand.spools/batteries]
   :required? true})

;; Devflow is a module so its named workflow routes are published as one
;; owner-complete contribution. Keep workflow first: it declares the route kind.
(runtime/module! runtime :millhouse/spools-workflow
  {:ns 'millhouse.spools.workflow
   :spools ['millhouse.spools/workflow]
   :required? true})

(runtime/module! runtime :millhouse/spools-workflow-cli
  {:ns 'millhouse.spools.workflow.cli
   :spools ['millhouse.spools/workflow]
   :after [:millhouse/spools-workflow]
   :required? true})

(runtime/module! runtime :devflow
  {:ns 'ct.spools.devflow
   :spools ['codethread/devflow]
   :after [:millhouse/spools-workflow]
   :required? true})

;; kanban board for this repo's own coordination cards: local tracking choice,
;; deliberately absent from the published devflow root.
(runtime/module! runtime :millstrand/spools-kanban
  {:ns 'ct.spools.kanban
   :spools ['codethread/kanban]
   :required? true})

;; the kanban adapter root, dogfooded from this checkout.
(runtime/module! runtime :devflow/kanban-adapter
  {:ns 'ct.spools.devflow-kanban-adapter
   :spools ['codethread/devflow-kanban-adapter 'codethread/devflow 'codethread/kanban]
   :after [:devflow :millstrand/spools-kanban]
   :required? true})
