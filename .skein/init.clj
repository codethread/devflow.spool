(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(def runtime (current/runtime))


;; Devflow is a module so its named workflow routes are published as one
;; owner-complete contribution. Keep workflow first: it declares the route kind.
(runtime/module! runtime :workflow
  {:ns 'skein.spools.workflow
   :spools ['skein.spools/workflow]
   :required? true})

(runtime/module! runtime :devflow
  {:ns 'ct.spools.devflow
   :spools ['codethread/devflow]
   :after [:workflow]
   :required? true})

;; kanban board for this repo's own coordination cards. The approved v10
;; candidate exports `spool`; publish its v10 marker before using this config.
(runtime/module! runtime :skein/spools-kanban
  {:ns 'ct.spools.kanban
   :spools ['codethread/kanban]
   :required? true})
