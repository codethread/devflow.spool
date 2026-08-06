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

  A lifecycle-seed callable: the re-point lives in the registry's direct layer,
  so it must be re-established on every weaver generation. `land-proposal`'s
  landed choice then routes into the kanban-bound variant."
  [{:keys [runtime]}]
  (current/with-runtime runtime
    (workflow/register-workflow! :decompose 'ct.spools.devflow-kanban-adapter/decompose-kanban))
  {:repointed :decompose})
