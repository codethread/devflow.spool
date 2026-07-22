(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(def runtime (current/runtime))


;; Devflow is a module so its named workflow routes are published as one
;; owner-complete contribution. Keep workflow first: it declares the route kind.
(runtime/module! runtime :workflow
  {:ns 'skein.spools.workflow
   :spools ['skein.spools/workflow]
   :contribute 'skein.spools.workflow/contribute
   :reconcile 'skein.spools.workflow/reconcile
   :required? true})

(runtime/module! runtime :devflow
  {:ns 'ct.spools.devflow
   :spools ['codethread/devflow]
   :after [:workflow]
   :contribute 'ct.spools.devflow/contribute
   :reconcile 'ct.spools.devflow/reconcile
   :required? true})

;; kanban board for this repo's own coordination cards
(runtime/module! runtime :skein/spools-kanban
  {:ns 'ct.spools.kanban
   :spools ['codethread/kanban]
   :contribute 'ct.spools.kanban/contribute
   :reconcile 'ct.spools.kanban/reconcile
   :required? true})
