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
(runtime/module! runtime :millstrand/spools-agent-run
  {:ns 'ct.spools.agent-run
   :required? true})

(runtime/module! runtime :millstrand/spools-delegation
  {:ns 'ct.spools.delegation
   :after [:millstrand/spools-agent-run]
   :required? true})

(runtime/module! runtime :millstrand/spools-harness-core
  {:ns 'ct.spools.harness-core
   :after [:millstrand/spools-agent-run]
   :required? true})

(runtime/module! runtime :millstrand/spools-claude-harness
  {:ns 'ct.spools.claude-harness
   :after [:millstrand/spools-harness-core]
   :required? true})

(runtime/module! runtime :millstrand/spools-codex-harness
  {:ns 'ct.spools.codex-harness
   :after [:millstrand/spools-harness-core]
   :required? true})

(runtime/module! runtime :millstrand/spools-pi-harness
  {:ns 'ct.spools.pi-harness
   :after [:millstrand/spools-harness-core]
   :required? true})

(runtime/module! runtime :millstrand/spools-agent-cli
  {:ns 'ct.spools.agent-cli
   :after [:millstrand/spools-harness-core
           :millstrand/spools-claude-harness
           :millstrand/spools-codex-harness
           :millstrand/spools-pi-harness]
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

(runtime/module! runtime :millstrand/spools-subagent
  {:ns 'ct.spools.executors.subagent
   :after [:millstrand/spools-agent-run
           :millhouse/spools-workflow
           :codethread/config
           :devflow/kanban-adapter]
   :required? true})

(runtime/module! runtime :codethread/config-agents
  {:ns 'ct.spools.codethread.agents
   :after [:millstrand/spools-agent-run]
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
           :millstrand/spools-agent-run
           :millstrand/spools-delegation
           :devflow/kanban-adapter]
   :required? true})
(runtime/module! runtime :codethread/ralph
  {:ns 'ct.spools.codethread.ralph
   :after [:millhouse/spools-workflow]
   :required? true})
