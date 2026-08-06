(ns ct.spools.devflow-kanban-adapter
  "The kanban binding for devflow's pluggable seams.

  Devflow deliberately ships no coupling to any card system; the kanban spool
  deliberately ships no run tracking of its own. This root is the one place
  that knows both vocabularies, so consumers stop re-inventing the same glue:

  - `author-kanban-cards` — a card-authoring target for devflow's decompose
    defer that puts the breakdown on the kanban board as one epic card plus
    feature cards.
  - `decompose-kanban` — devflow's `decompose-open` template bound with that
    target beside the shipped strand-native default.
  - `repoint-decompose!` — a lifecycle-seed callable for workspaces that want
    the routed `:decompose` stage name to resolve to the kanban-bound variant.

  Requires the `codethread/devflow` and `codethread/kanban` roots; see this
  root's README for the consumer entry shape and floors."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.devflow :as devflow]
            [millstrand.api.current.alpha :as current]
            [millstrand.spools.workflow :as workflow]))

(defn- titled [prefix]
  (fn [{:keys [feature]}]
    (str prefix feature)))

(defn- non-blank-string? [v]
  (and (string? v) (not (str/blank? v))))

(s/def ::feature non-blank-string?)
(s/def ::author-cards-params (s/keys :req-un [::feature]))
(s/def ::runtime some?)
(s/def ::repoint-input (s/keys :req-un [::runtime]))
(s/def ::seed-metadata-key (s/and keyword? #(not= :runtime %)))
(s/def ::seed-metadata (s/map-of ::seed-metadata-key any?))
(s/def ::repoint-seed-context
  (s/and
    (s/keys :req-un [::runtime])
    #(s/valid? ::seed-metadata (dissoc % :runtime))))
(s/def ::repointed #{:decompose})
(s/def ::repoint-result (s/keys :req-un [::repointed]))

(def ^:private repoint-input-keys #{:runtime})
(def ^:private repoint-seed-context-shape
  {:required-keys [:runtime]
   :metadata {:keys :keyword :values :any}})

(defn- sorted-keys [m]
  (vec (sort-by pr-str (keys m))))

(defn- require-valid!
  [spec value label]
  (if (s/valid? spec value)
    value
    (throw (ex-info label {:spec spec
                           :value value
                           :explain (s/explain-data spec value)}))))

(defn- require-seed-context!
  [context]
  (if (s/valid? ::repoint-seed-context context)
    context
    (let [received (if (map? context)
                     (sorted-keys context)
                     context)]
      (throw (ex-info
               (str "Invalid repoint-decompose-seed! context: allowed shape "
                    (pr-str repoint-seed-context-shape)
                    "; received " (pr-str received))
               {:spec ::repoint-seed-context
                :value context
                :allowed repoint-seed-context-shape
                :received received
                :explain (s/explain-data ::repoint-seed-context context)})))))

(workflow/defworkflow author-kanban-cards
  "The kanban card-authoring target for devflow's decompose defer.

  Authors the decompose breakdown as kanban cards: one epic card grouping the
  set, one feature card per independently landable outcome, and landing-order
  constraints as depends-on edges. Card bodies carry the cold-card contract
  from `strand devflow guidance decompose`; board discipline comes from
  `strand kanban prime`. A card's strand id is the card id the review handoff
  expects. The epic is grouping-only — kanban refuses to claim one — so it
  stays out of the review set."
  {:entrypoints #{:call}
   :param-spec ::author-cards-params
   :defaults {}}
  (workflow/workflow
    (titled "Author kanban implementation cards for ")
    (workflow/step :author-kanban-cards
                   (titled "Author kanban epic and feature cards for ")
                   :self
                   :attributes {"workflow/artifact" "implementation cards"
                                "devflow/guide" "decompose"
                                "workflow/instruction"
                                (str "Author this feature's implementation cards on the kanban "
                                     "board. Run `strand devflow guidance decompose` for the "
                                     "cold-card contract and `strand kanban prime` for board "
                                     "discipline first. Create one epic card grouping the set "
                                     "(`strand kanban add \"<epic title>\" --type epic`), then "
                                     "one feature card per independently landable outcome "
                                     "(`strand kanban add \"<title>\" --epic <epic-id> --body "
                                     "<cold-card body>`). Kanban has no verb for card-to-card "
                                     "dependencies: declare landing-order constraints with "
                                     "`strand update <dependent> --edge depends-on:<blocker>`. "
                                     "At the review handoff, supply the feature cards' refs — a "
                                     "card's strand id is its card id — and leave the "
                                     "grouping-only epic out of the review set.")})))

(workflow/defworkflow decompose-kanban
  "The decompose stage bound for kanban workspaces.

  Binds devflow's `decompose-open` template with the kanban authoring target
  beside the shipped strand-native default, so the defer's worker chooses per
  feature. Registered under its own name because devflow's module already owns
  `:decompose`; a workspace that wants the routed `:decompose` stage name to
  resolve here re-points it from a lifecycle seed with `repoint-decompose!`."
  {:entrypoints #{:continue :call}
   :param-spec :ct.spools.devflow/decompose-params
   :defaults {}}
  (workflow/bind-defers devflow/decompose-open
                        {:author-cards #{:author-card-strands :author-kanban-cards}}))

(defn repoint-decompose!
  "Re-point the routed `:decompose` stage name at `decompose-kanban`.

  This is the strict runtime operation used by the lifecycle adapter below. The
  re-point lives in the registry's direct layer, so it must be re-established on
  every weaver generation. `land-proposal`'s landed choice then routes into the
  kanban-bound variant.

  Accepts `{:runtime runtime}` satisfying `::repoint-input` and returns
  `{:repointed :decompose}` satisfying `::repoint-result`. The runtime is the
  lifecycle context's active Millstrand runtime. The input map is closed: any
  extra or missing key fails with the allowed and received key sets."
  [params]
  (let [params (if (map? params)
                 (let [received (set (keys params))]
                   (when-not (= repoint-input-keys received)
                     (throw (ex-info
                              (str "Invalid repoint-decompose! input: expected exact keys; "
                                   "allowed keys " (pr-str (vec (sort repoint-input-keys)))
                                   "; received keys " (pr-str (sorted-keys params)))
                              {:allowed (vec (sort repoint-input-keys))
                               :received (sorted-keys params)
                               :value params})))
                   params)
                 params)
        {:keys [runtime]} (require-valid! ::repoint-input params
                                           "Invalid repoint-decompose! input")]
    (current/with-runtime runtime
      (workflow/register-workflow! :decompose
                                   'ct.spools.devflow-kanban-adapter/decompose-kanban))
    (require-valid! ::repoint-result {:repointed :decompose}
                    "Invalid repoint-decompose! result")))

(defn repoint-decompose-seed!
  "Apply `repoint-decompose!` from a Millstrand lifecycle seed context.

  Lifecycle callables receive coordinator metadata in addition to `:runtime`;
  this adapter validates the `::repoint-seed-context` spec, whose metadata
  policy allows any additional keyword keys with arbitrary values, then
  projects the context to the strict public operation contract.
  The seed runner consumes the returned `{:repointed :decompose}` data result."
  [context]
  (let [{:keys [runtime]} (require-seed-context! context)]
    (repoint-decompose! {:runtime runtime})))
