(ns ct.spools.devflow-test
  "Tests Devflow as a collection of static Skein workflows and named discovery
  queries. Workflow execution itself belongs to skein.spools.workflow."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [ct.spools.devflow :as devflow]
            [skein.api.current.alpha :as current]
            [skein.api.graph.alpha :as graph]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.weaver.alpha :as weaver]
            [skein.spools.workflow :as workflow]
            [skein.test.alpha :as t]))

(def ^:private stage-names
  #{:intake :proposal :land-proposal :decompose :review-cards :spec-plan
    :route-after-plan :tasks :run-afk-loop :run-afk-manual :run-afk-delegated
    :direct-implementation :agent-review :abort})

(defn- activate! [rt]
  (doseq [[key config] [[:workflow {:ns 'skein.spools.workflow}]
                        [:devflow {:ns 'ct.spools.devflow
                                   :after [:workflow]}]]]
    (let [result (runtime/module! rt key config)
          status (get-in result [:modules key :status])]
      (when-not (contains? #{:applied :unchanged} status)
        (throw (ex-info "Module activation failed" {:module key :result result})))))
  rt)

(defn with-runtime [f]
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (activate! (:runtime ctx))]
      (current/with-runtime rt
        (f rt)))))

(deftest devflow-contributes-static-definitions-not-a-runtime-facade
  (is (nil? (resolve 'ct.spools.devflow/spool)))
  (is (nil? (resolve 'ct.spools.devflow/contribute)))
  (doseq [sym '[start! ready ready-step complete! choose! advance!
                choice-detail choice-details current-root run-history squash-run!
                describe workflows commands]]
    (is (nil? (ns-resolve 'ct.spools.devflow sym))
        (str sym " must remain a generic workflow operation"))))

(deftest module-publishes-the-complete-devflow-workflow-catalogue
  (with-runtime
    (fn [rt]
      (is (= stage-names (set (keys (workflow/workflows)))))
      (is (= #{:start} (:entrypoints (workflow/resolve-workflow :intake))))
      (is (= #{:call} (:entrypoints (workflow/resolve-workflow :agent-review))))
      (doseq [stage (disj stage-names :intake :agent-review)]
        (is (= #{:continue :call}
               (:entrypoints (workflow/resolve-workflow stage)))
            (str stage " can route or return")))
      (is (= "Devflow proposal: <feature>"
             (:name (workflow/describe :proposal {:feature "<feature>"})))))))

(deftest generic-workflow-api-starts-and-drives-a-devflow-run
  (with-runtime
    (fn [rt]
      (let [started (workflow/start! "search-filters" :intake
                                     {:feature "search-filters"
                                      :worktree-check "already-in-worktree-ok"})]
        (is (= ["Create or confirm feature worktree for search-filters"]
               (mapv :title (:ready started))))
        (is (= "devflow"
               (get-in (workflow/current-root "search-filters")
                       [:attributes :workflow/family])))
        (is (= "intake"
               (get-in (workflow/current-root "search-filters")
                       [:attributes :devflow/stage])))
        (workflow/choose! "search-filters" :already-in-worktree)
        (is (= ["Capture user brief for search-filters"]
               (mapv :title (workflow/ready "search-filters"))))
        (workflow/complete! "search-filters")
        (workflow/choose! "search-filters" :proposal-ready)
        (is (= ["Inspect relevant RFCs, spikes, root specs, and active feature context for search-filters"]
               (mapv :title (workflow/ready "search-filters"))))))))

(deftest generic-workflow-api-enforces-devflow-choice-contracts
  (with-runtime
    (fn [_]
      (workflow/start! "abortable" :intake {:feature "abortable"})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Value does not satisfy the named spec"
                            (workflow/choose! "abortable" :abort)))
      (is (= "create-or-confirm-worktree"
             (:checkpoint (workflow/ready-step "abortable"))))
      (let [result (workflow/choose! "abortable" :abort {:reason "superseded"})]
        (is (= ["Record abort for abortable: superseded"]
               (mapv :title (:ready result))))
        (is (true? (:done (workflow/complete! "abortable"))))))))

(deftest devflow-queries-surface-resumable-runs-and-actionable-work
  (with-runtime
    (fn [rt]
      (workflow/start! "paused" :intake {:feature "paused"})
      (workflow/start! "other" (workflow/workflow "Other"
                                        {:attributes {"workflow/family" "other"}}
                                        (workflow/step :work "Other work" :self)) {})
      (testing "the active-run query returns only Devflow roots"
        (is (= #{"paused"}
               (set (map #(get-in % [:attributes :workflow/run-id])
                         (weaver/list-query rt "devflow-runs" {}))))))
      (testing "the ready query follows a Devflow root's parent-of edges"
        (is (= #{"Create or confirm feature worktree for paused"}
               (set (map :title
                         (weaver/ready rt (graph/resolve-query rt "devflow-ready") {})))))
        (workflow/choose! "paused" :already-in-worktree)
        (is (= #{"Capture user brief for paused"}
               (set (map :title
                         (weaver/ready rt (graph/resolve-query rt "devflow-ready") {}))))))
      (workflow/complete! "paused")
      (workflow/choose! "paused" :proposal-ready)
      (dotimes [_ 3] (workflow/complete! "paused"))
      (workflow/choose! "paused" :abort {:reason "stop"})
      (workflow/complete! "paused")
      (is (empty? (weaver/list-query rt "devflow-runs" {})))
      (is (empty? (weaver/ready rt (graph/resolve-query rt "devflow-ready") {}))))))

(deftest guidance-and-artifact-metadata-remain-devflow-owned
  (is (= "devflow-spool" (devflow/dependency-sentinel)))
  (let [overview (devflow/guidance)]
    (is (contains? (:guides overview) :proposal))
    (is (seq (get-in overview [:workspace :invariants]))))
  (with-runtime
    (fn [_]
      (workflow/start! "guided" #'devflow/proposal {:feature "guided"})
      (workflow/complete! "guided")
      (let [step (workflow/ready-step "guided")]
        (is (= "proposal.md" (:artifact step)))
        (is (= "Call (ct.spools.devflow/guidance :proposal) for the authoring procedure, constraints, template, and validation checklist before writing proposal.md."
               (:instruction step)))))))

(defn -main [& _]
  (let [summary (clojure.test/run-tests 'ct.spools.devflow-test)]
    (System/exit (if (pos? (+ (:fail summary) (:error summary))) 1 0))))
