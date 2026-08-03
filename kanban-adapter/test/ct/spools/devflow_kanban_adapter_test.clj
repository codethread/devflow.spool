(ns ct.spools.devflow-kanban-adapter-test
  "Tests the kanban adapter root: its registered catalogue additions and the
  kanban-bound decompose variant. Kanban itself and devflow each own their
  behavior; this suite covers only the binding."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ct.spools.devflow-kanban-adapter :as adapter]
            ;; The adapter no longer requires the kanban namespace itself, so
            ;; the test world loads it for the :kanban module activation below
            ;; (a real world gets it as an approved spool root).
            [ct.spools.kanban]
            [skein.api.current.alpha :as current]
            [skein.api.runtime.alpha :as runtime]
            [skein.spools.workflow :as workflow]
            [skein.test.alpha :as t]))

(defn- activate! [rt]
  (doseq [[key config] [[:workflow {:ns 'skein.spools.workflow}]
                        [:devflow {:ns 'ct.spools.devflow
                                   :after [:workflow]}]
                        [:kanban {:ns 'ct.spools.kanban}]
                        [:devflow-kanban-adapter {:ns 'ct.spools.devflow-kanban-adapter
                                          :after [:workflow :devflow :kanban]}]]]
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

(deftest adapter-accretes-its-definitions-beside-devflow
  (with-runtime
    (fn [_]
      (let [names (set (keys (workflow/workflows)))]
        (is (contains? names :author-kanban-cards))
        (is (contains? names :decompose-kanban))
        (is (contains? names :decompose)
            "devflow's own stage catalogue stays intact beside the adapter"))
      (is (= #{:call} (:entrypoints (workflow/resolve-workflow :author-kanban-cards))))
      (is (= #{:continue :call}
             (:entrypoints (workflow/resolve-workflow :decompose-kanban)))))))

(deftest decompose-kanban-offers-the-board-beside-the-strand-default
  (with-runtime
    (fn [_]
      (workflow/start! "kb" #'adapter/decompose-kanban
                       {:feature "kb"
                        :card-reviewer "seat-a"
                        :card-set-reviewer "seat-b"})
      (let [step (workflow/ready-step "kb")]
        (is (= "author-cards" (:defer step)))
        (is (= ["author-card-strands" "author-kanban-cards"] (:workflows step))
            "the binding allows the strand default and the kanban target"))
      (workflow/defer! "kb" :author-kanban-cards {:feature "kb"})
      (let [step (workflow/ready-step "kb")]
        (is (= "Author kanban epic and feature cards for kb" (:title step)))
        (is (= "implementation cards" (:artifact step)))
        (is (str/includes? (:instruction step) "--edge depends-on:")
            "the instruction spells out the core edge command kanban lacks"))
      (workflow/complete! "kb")
      (is (= "handoff-card-review" (:checkpoint (workflow/ready-step "kb")))
          "the filled target returns into the declaring stage"))))

(defn -main [& _]
  (let [summary (clojure.test/run-tests 'ct.spools.devflow-kanban-adapter-test)]
    (System/exit (if (pos? (+ (:fail summary) (:error summary))) 1 0))))
