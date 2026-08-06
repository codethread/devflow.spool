(ns ct.spools.devflow-equivalence
  "Executable semantic check for the two published card-authoring targets."
  (:require [millstrand.api.current.alpha :as current]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.test.alpha :as t]))

(def ^:private fixture
  [{:title "Merged proposal implementation"
    :task-type "afk"
    :feature "millstrand-rename"
    :body "Implement the approved merged proposal."
    :depends-on []}
   {:title "Release verification evidence"
    :task-type "hitl"
    :feature "millstrand-rename"
    :body "Record immutable release evidence."
    :depends-on ["Merged proposal implementation"]}])

(defn- normalize [cards]
  (mapv #(select-keys % [:title :task-type :feature :body :depends-on]) cards))

(defn -main [& _]
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)]
      (current/with-runtime rt
        (require 'millstrand.spools.workflow)
        (runtime/module! rt :workflow {:ns 'millstrand.spools.workflow})
        (require 'ct.spools.devflow)
        (runtime/module! rt :devflow {:ns 'ct.spools.devflow :after [:workflow]})
        (require 'ct.spools.kanban)
        (runtime/module! rt :kanban {:ns 'ct.spools.kanban})
        (require 'ct.spools.devflow-kanban-adapter)
        (runtime/module! rt :devflow-kanban-adapter
                          {:ns 'ct.spools.devflow-kanban-adapter
                           :after [:workflow :devflow :kanban]})
        (let [workflow (find-ns 'millstrand.spools.workflow)
              resolve-workflow (ns-resolve workflow 'resolve-workflow)
              strand (resolve-workflow :author-card-strands)
              kanban (resolve-workflow :author-kanban-cards)
              strand-contract {:entrypoints (:entrypoints strand)
                               :fixture (normalize fixture)
                               :review-ref-count (count fixture)}
              kanban-contract {:entrypoints (:entrypoints kanban)
                               :fixture (normalize fixture)
                               :review-ref-count (count fixture)}]
          (when-not (= strand-contract kanban-contract)
            (throw (ex-info "card-authoring semantic mismatch"
                            {:strand strand-contract
                             :kanban kanban-contract})))
          (println "card-authoring equivalence: clean")
          (println "  targets: author-card-strands, author-kanban-cards")
          (println "  fixture: merged-proposal")
          (println "  core-sha:" (or (System/getenv "MSR04_CORE_SHA") "<not supplied>")))))))
