(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

(runtime/module! runtime :skein/spools-batteries
  {:ns 'skein.spools.batteries
   :spools ['skein.spools/batteries]
   :required? true})

;; Devflow is a module so its named workflow routes are published as one
;; owner-complete contribution. Keep workflow first: it declares the route kind.
(runtime/module! runtime :workflow
  {:ns 'skein.spools.workflow
   :spools ['skein.spools/workflow]
   :required? true})

(runtime/module! runtime :skein/spools-workflow-cli
  {:ns 'skein.spools.workflow.cli
   :spools ['skein.spools/workflow]
   :after [:workflow]
   :required? true})

(runtime/module! runtime :devflow
  {:ns 'ct.spools.devflow
   :spools ['codethread/devflow]
   :after [:workflow]
   :required? true})

;; kanban board for this repo's own coordination cards: local tracking choice,
;; deliberately absent from the published devflow root.
(runtime/module! runtime :skein/spools-kanban
  {:ns 'ct.spools.kanban
   :spools ['codethread/kanban]
   :required? true})

;; the kanban adapter root, dogfooded from this checkout.
(runtime/module! runtime :devflow/kanban-adapter
  {:ns 'ct.spools.devflow-kanban-adapter
   :spools ['codethread/devflow-kanban-adapter 'codethread/devflow 'codethread/kanban]
   :after [:devflow :skein/spools-kanban]
   :required? true})
