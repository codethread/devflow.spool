(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

(runtime/module! runtime :millstrand/spools-batteries
  {:ns 'millstrand.spools.batteries
   :required? true})
;; Devflow is a module so its named workflow routes are published as one
;; owner-complete contribution. Keep workflow first: it declares the route kind.
(runtime/module! runtime :millhouse/spools-workflow
  {:ns 'millhouse.spools.workflow
   :required? true})
(runtime/module! runtime :millhouse/spools-workflow-providers
  {:ns 'millhouse.spools.workflow.spool
   :after [:millhouse/spools-workflow]
   :required? true})
(runtime/module! runtime :millhouse/spools-identity
  {:ns 'millhouse.spools.identity
   :required? true})

;; Harnesses owns provider-neutral runs and the provider implementations. The
;; shared Codethread config publishes aliases after this module and before the
;; workflow adapter, whose initial scan must resolve every ready gate.
(runtime/module! runtime :harnesses
  {:ns 'ct.spools.harnesses.spool
   :after [:millhouse/spools-identity]
   :required? true})

(runtime/module! runtime :devflow
  {:ns 'ct.spools.devflow
   :after [:millhouse/spools-workflow]
   :required? true})

;; kanban board for this repo's own coordination cards: local tracking choice,
;; deliberately absent from the published devflow root.
(runtime/module! runtime :millhouse/spools-kanban
  {:ns 'millhouse.spools.kanban
   :required? true})

;; the kanban adapter root, dogfooded from this checkout.
(runtime/module! runtime :devflow/kanban-adapter
  {:ns 'ct.spools.devflow-kanban-adapter
   :after [:devflow :millhouse/spools-kanban
           :millhouse/spools-workflow]
   :required? true})

(runtime/module! runtime :codethread/config-agents
  {:ns 'ct.spools.codethread.agents
   :after [:harnesses]
   :required? true})
(runtime/module! runtime :codethread/config-help
  {:ns 'ct.spools.codethread.help
   :after [:millstrand/spools-batteries]
   :required? true})
(runtime/module! runtime :codethread/config-devflow
  {:ns 'ct.spools.codethread.devflow
   :required? true})
(runtime/module! runtime :codethread/config
  {:ns 'ct.spools.codethread.config
   :after [:codethread/config-agents
           :codethread/config-help
           :codethread/config-devflow
           :millstrand/spools-batteries
           :harnesses
           :devflow/kanban-adapter]
   :required? true})
(runtime/module! runtime :devflow/reviewers
  {:file "me/reviewers.clj"
   :after [:codethread/config]
   :required? true})
(runtime/module! runtime :harnesses/agent-executor
  {:ns 'ct.spools.harnesses.executors.agent.spool
   :after [:millhouse/spools-workflow
           :harnesses
           :codethread/config
           :devflow/reviewers]
   :required? true})
(runtime/module! runtime :codethread/ralph
  {:ns 'ct.spools.codethread.ralph
   :after [:millhouse/spools-workflow]
   :required? true})
