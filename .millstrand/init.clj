(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime]
         '[ct.spools.codethread.bootstrap :as codethread])

(def runtime (current/runtime))

;; Register shared identity, Workflow, Harnesses, aliases, reviewers, and
;; landing before this workspace's modules. The sole :agent executor is
;; activated last, after every consumer workflow and alias election is available
;; to its first scan.
(codethread/register! runtime)

(runtime/module! runtime :millstrand/spools-batteries
                 {:ns 'millstrand.spools.batteries
                  :required? true})

(runtime/module! runtime :millhouse/spools-workflow-providers
                 {:ns 'millhouse.spools.workflow.spool
                  :after [:millhouse/spools-workflow]
                  :required? true})

(runtime/module! runtime :devflow
                 {:ns 'ct.spools.devflow
                  :after [:millhouse/spools-workflow]
                  :required? true})

;; The adapter and workspace config are consumer-owned composition. The
;; published Devflow library itself remains independent of Codethread config.
(runtime/module! runtime :devflow/kanban-adapter
                 {:ns 'ct.spools.devflow-kanban-adapter
                  :after [:devflow
                          :millhouse/spools-kanban
                          :millhouse/spools-workflow]
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
                  :after [:codethread/config-help
                          :codethread/config-devflow
                          :millstrand/spools-batteries
                          :devflow/kanban-adapter]
                  :required? true})
(runtime/module! runtime :codethread/ralph
                 {:ns 'ct.spools.codethread.ralph
                  :after [:millhouse/spools-workflow]
                  :required? true})

(runtime/module! runtime :devflow/reviewers
                 {:file "me/reviewers.clj"
                  :after [:codethread/config]
                  :required? true})

(codethread/register-executor!
 runtime
 [:millhouse/spools-workflow-providers
  :devflow
  :devflow/kanban-adapter
  :codethread/config
  :codethread/ralph
  :devflow/reviewers])
