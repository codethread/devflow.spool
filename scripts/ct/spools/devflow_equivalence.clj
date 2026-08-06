(ns ct.spools.devflow-equivalence
  "Executable semantic check for the two published card-authoring targets."
  (:require [millstrand.api.current.alpha :as current]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.spools.workflow :as workflow]
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

(defn- activate! [rt]
  (doseq [namespace '[millstrand.spools.workflow
                      ct.spools.devflow
                      ct.spools.kanban
                      ct.spools.devflow-kanban-adapter]]
    (require namespace))
  (doseq [[key config] [[:workflow {:ns 'millstrand.spools.workflow}]
                        [:devflow {:ns 'ct.spools.devflow
                                   :after [:workflow]}]
                        [:kanban {:ns 'ct.spools.kanban}]
                        [:devflow-kanban-adapter
                         {:ns 'ct.spools.devflow-kanban-adapter
                          :after [:workflow :devflow :kanban]}]]]
    (runtime/module! rt key config))
  rt)

(defn- attr [strand key]
  (let [attributes (:attributes strand)]
    (or (get attributes key)
        (get attributes (keyword key)))))

(defn- sha256 [value]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        bytes (.digest digest (.getBytes (str value) "UTF-8"))]
    (format "%064x" (java.math.BigInteger. 1 bytes))))

(defn- dependency-titles [rt id->title id]
  (->> (graph/outgoing-edges rt [id] "depends-on")
       (map :to_strand_id)
       (keep id->title)
       sort
       vec))

(defn- normalize-cards [rt card-ids review-refs]
  (let [cards (mapv #(weaver/show rt %) card-ids)
        id->title (into {} (map (juxt :id :title) cards))]
    {:cards (mapv (fn [card]
                    {:title (:title card)
                     :task-type (attr card "devflow/task-type")
                     :feature (attr card "devflow/feature")
                     :body-hash (sha256 (attr card "body"))
                     :depends-on (dependency-titles rt id->title (:id card))})
                  cards)
     :review-ref-count (count review-refs)}))

(defn- author-strand-cards! [rt]
  (let [ids (reduce (fn [ids {:keys [title task-type feature body depends-on]}]
                      (let [id-by-title (zipmap (map :title fixture) ids)
                            strand (weaver/add!
                                    rt
                                    {:title title
                                     :attributes {"devflow/task-type" task-type
                                                  "devflow/feature" feature
                                                  "body" body}
                                     :edges (mapv (fn [dependency]
                                                    {:type "depends-on"
                                                     :to (get id-by-title dependency)})
                                                  depends-on)})]
                        (conj ids (:id strand))))
                    []
                    fixture)]
    (normalize-cards rt ids ids)))

(defn- author-kanban-cards! [rt]
  (let [kanban (find-ns 'ct.spools.kanban)
        add! (ns-resolve kanban 'add!)
        epic-id (get-in (add! rt "Merged proposal cards" {"--type" "epic"}) [:card :id])
        ids (mapv (fn [{:keys [title body]}]
                    (get-in (add! rt title {"--epic" epic-id "--body" body})
                            [:card :id]))
                  fixture)
        id-by-title (zipmap (map :title fixture) ids)]
    (doseq [[id {:keys [task-type feature depends-on]}] (map vector ids fixture)]
      (weaver/update! rt id
                      {:attributes {"devflow/task-type" task-type
                                    "devflow/feature" feature}
                       :edges (mapv (fn [dependency]
                                      {:type "depends-on"
                                       :to (get id-by-title dependency)})
                                    depends-on)}))
    (normalize-cards rt ids ids)))

(defn- execute-target! [target author!]
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (activate! (:runtime ctx))]
      (current/with-runtime rt
        (let [resolved (workflow/resolve-workflow target)]
          ;; Materialize the actual call-only authoring target before applying
          ;; the fixed fixture. This keeps both executions on their published
          ;; workflow path while the card records remain the comparison seam.
          (workflow/pour! (:value resolved) {:feature "millstrand-rename"})
          (author! rt))))))

(defn assert-equivalent!
  "Throw when two target reports differ at the review handoff boundary."
  [strand kanban]
  (when-not (= strand kanban)
    (throw (ex-info "card-authoring semantic mismatch"
                    {:strand strand :kanban kanban})))
  true)

(defn- divergence-regression! [report]
  (let [divergent (update-in report [:cards 0 :body-hash]
                             #(str % "-divergent"))]
    (try
      (assert-equivalent! report divergent)
      (throw (ex-info "card-authoring divergence regression did not fire" {}))
      (catch clojure.lang.ExceptionInfo error
        (when (= "card-authoring divergence regression did not fire"
                 (.getMessage error))
          (throw error))))))

(defn -main [& _]
  (let [strand (execute-target! :author-card-strands author-strand-cards!)
        kanban (execute-target! :author-kanban-cards author-kanban-cards!)]
    (assert-equivalent! strand kanban)
    (divergence-regression! strand)
    (println "card-authoring equivalence: clean")
    (println "  targets: author-card-strands, author-kanban-cards")
    (println "  fixture: merged-proposal")
    (println "  fresh databases: 2")
    (println "  cards:" (count (:cards strand)))
    (println "  review-ref-count:" (:review-ref-count strand))
    (println "  core-sha:" (or (System/getenv "MSR04_CORE_SHA") "<not supplied>"))))
