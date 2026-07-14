(ns ct.spools.devflow.guidance
  "The devflow authoring knowledge base as plain Clojure data.

  This namespace absorbs the old markdown devflow skill: everything an agent
  needed from SKILL.md and its reference files now lives here as inspectable
  maps built from shared blocks (workspace paths, the document-ID convention,
  document ownership) plus one guide per artifact kind.

  Workflow steps advertise the guide for their artifact through the
  `devflow/guide` strand attribute; agents fetch a guide with
  `ct.spools.devflow/guidance`. Every guide shares one shape:

  - `:purpose`       — one sentence: what the artifact is for
  - `:artifacts`     — where the files live (paths from `paths`)
  - `:prerequisites` — what must be true or read before writing
  - `:knowledge`     — guide-specific reference maps (statuses, schemas, rules)
  - `:procedures`    — named step vectors, e.g. `{:write [...] :update [...]}`
  - `:constraints`   — hard rules that apply while writing
  - `:validation`    — acceptance checklist for the finished artifact
  - `:templates`     — markdown skeleton(s) to instantiate
  - `:see-also`      — related guide keys")

;; ---------------------------------------------------------------------------
;; Shared building blocks

(def paths
  "Every location in a devflow planning workspace, keyed by role.
  `<feature>` is the kebab-case feature name; it is also the workflow run-id."
  {:workspace     "devflow/"
   :readme        "devflow/README.md"
   :rfcs          "devflow/rfcs/"
   :rfc           "devflow/rfcs/YYYY-MM-DD-<slug>.md"
   :specs         "devflow/specs/"
   :spec          "devflow/specs/<spec-name>.md"
   :feature       "devflow/feat/<feature>/"
   :proposal      "devflow/feat/<feature>/proposal.md"
   :feature-specs "devflow/feat/<feature>/specs/"
   :feature-spec  "devflow/feat/<feature>/specs/<spec-name>.md"
   :spec-delta    "devflow/feat/<feature>/specs/<spec-name>.delta.md"
   :plan          "devflow/feat/<feature>/<feature>.plan.md"
   :tasks         "devflow/feat/<feature>/tasks/"
   :task-index    "devflow/feat/<feature>/tasks/index.yml"
   :task-file     "devflow/feat/<feature>/tasks/<zero-padded-id>-<slug>.md"
   :archive       "devflow/archive/yy-mm-dd__<feature>/"
   :archived-rfcs "devflow/archive/yy-mm-dd__<feature>/rfcs/"})

(def layout
  "The devflow workspace tree at a glance."
  "devflow/
|-- README.md
|-- rfcs/
|   `-- YYYY-MM-DD-<slug>.md
|-- specs/
|   `-- <spec-name>.md
|-- feat/
|   `-- <feature>/
|       |-- proposal.md
|       |-- specs/
|       |   |-- <existing-spec>.delta.md
|       |   `-- <new-spec>.md
|       |-- <feature>.plan.md
|       `-- tasks/
|           |-- index.yml
|           `-- <zero-padded-id>-<slug>.md
`-- archive/
    `-- yy-mm-dd__<feature>/
        `-- rfcs/
            `-- YYYY-MM-DD-<slug>.md")

(def invariants
  "Workspace rules that hold across every stage."
  ["devflow/specs/ is canonical for current contracts."
   "devflow/feat/<feature>/specs/ is staging for active feature changes."
   "devflow/archive/* is historical context, not current truth."
   "Any feature using tasks/ must have proposal.md and <feature>.plan.md."
   "Developer Notes live in the feature plan; never create task-note README files."
   "Do not copy RFC alternatives into specs, plans, or tasks; link to the RFC."
   "Only the current feature's documents are writable during normal stage work; never edit archives or sibling feature folders, and touch root specs/RFCs only when the stage promotes or records durable outcomes."])

(def id-convention
  "The stable document-ID scheme shared by every devflow document."
  {:format     "IDs order as document type, short name, sequential id, optional version: PROP-Dwr-001 for v1, SPEC-Dwr-002@3 for a third version. Known prefixes: RFC, PROP, SPEC, DELTA, PLAN, TASK."
   :versioning "Omit @1; append @2, @3, ... only when a new version supersedes an externally referenced document."
   :sub-ids    "Prefix every nested point ID with the full document ID (PLAN-Dwr-001.P1, RFC-Dwr-001.O1) so references are globally grepable and never clash across documents."
   :allocation "Before creating a document, scan the whole workspace — root specs/RFCs, active feature folders, and the archive — for existing IDs with that prefix/name pair and take the next unused number. Ask when the next number or version is ambiguous."
   :editing    "Preserve existing reference IDs when editing; append new IDs rather than renumbering unless the document is still a draft with no external references."})

(def document-ownership
  "What each document kind owns, what it must not absorb, and how long it lives.
  The root spec is the future-facing source of truth; archived feature folders
  explain why things changed, not what the current contract is."
  {:rfc          {:owns     "Idea framing, alternatives, tradeoffs, recommendation, decision outcome"
                  :not      "Implementation tracking or current feature state"
                  :lifetime "Active until implemented, then archived with the implementing feature"}
   :root-spec    {:owns     "Durable domain contracts, boundaries, rationale, non-goals"
                  :not      "Feature-local sequencing or task detail"
                  :lifetime "Permanent; evolves with the domain"}
   :proposal     {:owns     "Problem framing, goals, non-goals, scope, links to decisions"
                  :not      "Alternatives history (belongs in an RFC) or implementation strategy (belongs in the plan)"
                  :lifetime "Archived with the feature"}
   :spec-delta   {:owns     "Pending changes to durable specs staged by the feature"
                  :not      "Long-term duplicated spec content"
                  :lifetime "Merged into root specs when the feature ships, then archived"}
   :plan         {:owns     "Build strategy, phase boundaries, validation strategy, task context, developer notes"
                  :not      "Product problem framing or per-slice execution contracts"
                  :lifetime "Archived with the feature"}
   :tasks        {:owns     "Exact AFK/HITL slices, acceptance criteria, dependencies"
                  :not      "Durable design knowledge or ongoing notes"
                  :lifetime "Archived with the feature"}
   :archive      {:owns     "Historical feature context after completion or abandonment, including implemented RFCs"
                  :not      "Active source of truth for current specs"
                  :lifetime "Permanent historical record"}
   :code         {:owns     "What exists and how it behaves"
                  :not      nil
                  :lifetime "Ground truth"}})

;; ---------------------------------------------------------------------------
;; Templates

(defn- config-identification
  "The configuration-identification header paragraph, rendered for a document
  prefix (PROP, RFC, SPEC, ...). Shared verbatim across all templates so the
  ID rules never drift between document kinds."
  [prefix]
  (str "**Configuration identification:** Document IDs must be ordered as document type, "
       "short name, sequential id, then optional version: `" prefix "-Dwr-001` for v1 and `"
       prefix "-Dwr-001@2` for v2. Omit `@1`; append `@2`, `@3`, etc. only when a new version "
       "supersedes an externally referenced document. Prefix every nested point ID with the "
       "full document ID, for example `" prefix "-Dwr-001.P1` or `" prefix "-Dwr-001@2.P1`, "
       "so references are globally grepable and do not clash across documents. If the next "
       "number or version is unclear, ask before creating the document."))

(def proposal-template
  (str "# <Feature name> Proposal

**Document ID:** `PROP-<name>-<nnn>[@<version>]`
**Last Updated:** <YYYY-MM-DD>
**Related RFCs:** <links or \"None\">
**Related root specs:** <links or \"None\">
" (config-identification "PROP") "

## PROP-<name>-<nnn>.P1 Problem

What problem this feature/change solves.

## PROP-<name>-<nnn>.P2 Goals

- **PROP-<name>-<nnn>.G1:** Desired outcome.

## PROP-<name>-<nnn>.P3 Non-goals

- **PROP-<name>-<nnn>.NG1:** Boundary intentionally out of scope.

## PROP-<name>-<nnn>.P4 Proposed scope

- **PROP-<name>-<nnn>.S1:** What should change at product/domain level. Keep implementation strategy out of this section.

## PROP-<name>-<nnn>.P5 Open questions

- **PROP-<name>-<nnn>.Q1:** Question that must be resolved before planning or tasking.
"))

(def rfc-template
  (str "# <RFC title>

**Document ID:** `RFC-<name>-<nnn>[@<version>]`
**Status:** Draft | Open | Accepted | Rejected | Superseded
**Date:** <YYYY-MM-DD>
**Related:** <links to specs, feature folders, issues, code modules, or \"None yet\">
" (config-identification "RFC") "

## RFC-<name>-<nnn>.P1 Problem

What decision needs to be made and why now?

## RFC-<name>-<nnn>.P2 Goals

- **RFC-<name>-<nnn>.G1:** Desired outcome.

## RFC-<name>-<nnn>.P3 Non-goals

- **RFC-<name>-<nnn>.NG1:** Boundary this RFC does not decide.

## RFC-<name>-<nnn>.P4 Options

| ID                  | Summary | Pros | Cons |
| ------------------- | ------- | ---- | ---- |
| RFC-<name>-<nnn>.O1 |         |      |      |
| RFC-<name>-<nnn>.O2 |         |      |      |

## RFC-<name>-<nnn>.P5 Recommendation

- **RFC-<name>-<nnn>.REC1:** Chosen direction and why it best satisfies the goals.

## RFC-<name>-<nnn>.P6 Consequences

- **RFC-<name>-<nnn>.C1:** Expected implication for specs, feature planning, implementation, migration, operations, or users.

## RFC-<name>-<nnn>.P7 Outcome

- **RFC-<name>-<nnn>.OUT1:** Decision, date, decider, and links to follow-up specs or feature folders when known.
"))

(def root-spec-template
  (str "# <Domain name>

**Document ID:** `SPEC-<name>-<nnn>[@<version>]`
**Status:** Draft | Planned | Implemented | Partial | Deprecated
**Last Updated:** <YYYY-MM-DD>
**Related RFCs:** <links or \"None\">
**Code:** <module/package root or \"Not implemented yet\">
" (config-identification "SPEC") "

## SPEC-<name>-<nnn>.P1 Purpose

Why this system exists.

## SPEC-<name>-<nnn>.P2 Goals

- **SPEC-<name>-<nnn>.G1:** Durable outcome this domain must support.

## SPEC-<name>-<nnn>.P3 Non-goals

- **SPEC-<name>-<nnn>.NG1:** Boundary intentionally outside this domain.

## SPEC-<name>-<nnn>.P4 Domain concepts

- **SPEC-<name>-<nnn>.DC1:** Concept needed to understand the boundary.

## SPEC-<name>-<nnn>.P5 Interfaces and contracts

- **SPEC-<name>-<nnn>.IC1:** Durable API, schema, CLI contract, data contract, or invariant.

## SPEC-<name>-<nnn>.P6 Design decisions

### SPEC-<name>-<nnn>.D1 <Decision>

- **Decision:** What is true.
- **Rationale:** Why.
- **Rejected:** Alternatives intentionally not chosen.

## SPEC-<name>-<nnn>.P7 Open questions

- **SPEC-<name>-<nnn>.Q1:** Unresolved durable question, if any.
"))

(def spec-delta-template
  (str "# <Spec name> delta for <feature name>

**Document ID:** `DELTA-<name>-<nnn>[@<version>]`
**Root spec:** [<spec-name>.md](../../specs/<spec-name>.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Draft | Reviewed | Merged
**Last Updated:** <YYYY-MM-DD>
" (config-identification "DELTA") "

## DELTA-<name>-<nnn>.P1 Summary

What changes relative to the root spec.

## DELTA-<name>-<nnn>.P2 Contract changes

- **DELTA-<name>-<nnn>.CC1:** Durable behavior, API, schema, CLI, or invariant change.

## DELTA-<name>-<nnn>.P3 Design decisions

### DELTA-<name>-<nnn>.D1 <Decision>

- **Decision:** What will become true if the feature ships.
- **Rationale:** Why.
- **Rejected:** Alternatives intentionally not chosen.

## DELTA-<name>-<nnn>.P4 Open questions

- **DELTA-<name>-<nnn>.Q1:** Question blocking promotion or implementation.
"))

(def plan-template
  (str "# <Feature name> Plan

**Document ID:** `PLAN-<name>-<nnn>[@<version>]`
**Feature:** `<feature>`
**Proposal:** [proposal.md](./proposal.md)
**RFC:** [title](../rfcs/YYYY-MM-DD-slug.md) <!-- or: none -->
**Root specs:** [domain.md](../specs/domain.md) <!-- or: none yet -->
**Feature specs:** [specs/domain.delta.md](./specs/domain.delta.md) <!-- or: none -->
**Status:** Draft | Reviewed | Active | Shipped | Abandoned
**Last Updated:** <YYYY-MM-DD>
" (config-identification "PLAN") "

## PLAN-<name>-<nnn>.P1 Goal and scope

One paragraph: what this feature delivers. Link to the proposal/spec for why it matters.

## PLAN-<name>-<nnn>.P2 Approach

- **PLAN-<name>-<nnn>.A1:** Chosen implementation strategy, architecture, sequencing, integration boundaries, and important mechanics.

## PLAN-<name>-<nnn>.P3 Affected areas

| ID                    | Area                | Expected change                                                 |
| --------------------- | ------------------- | --------------------------------------------------------------- |
| PLAN-<name>-<nnn>.AA1 | `module/or/package` | High-level change                                               |
| PLAN-<name>-<nnn>.AA2 | `key/file.ts`       | Only include specific files when they are architectural anchors |

## PLAN-<name>-<nnn>.P4 Contract and migration impact

- **PLAN-<name>-<nnn>.CM1:** High-level data model, API, CLI, config, or migration impact. Durable contract changes belong in feature-local spec deltas or new specs, not only here.

## PLAN-<name>-<nnn>.P5 Implementation phases

### PLAN-<name>-<nnn>.PH1 <name>

Outcome: <reviewable outcome this phase delivers>

### PLAN-<name>-<nnn>.PH2 <name>

Outcome: <reviewable outcome this phase delivers>

## PLAN-<name>-<nnn>.P6 Validation strategy

- **PLAN-<name>-<nnn>.V1:** What must be proven before the change is trusted.

## PLAN-<name>-<nnn>.P7 Risks and open questions

- **PLAN-<name>-<nnn>.R1:** Implementation risk with mitigation.
- **PLAN-<name>-<nnn>.Q1:** Open question blocking task generation.

## PLAN-<name>-<nnn>.P8 Task context

- **PLAN-<name>-<nnn>.TC1:** Brief context task authors and AFK agents need, including important references.

## PLAN-<name>-<nnn>.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

### PLAN-<name>-<nnn>.DN1 Task <id>: <description> — <YYYY-MM-DD>

- Note relevant for later agents or follow-up scope.
"))

(def task-index-template
  "tasks:
  - id: 1
    description: Terse task title
    task_file: tasks/001-terse-task-title.md
    status: pending
    blocked_by: []
")

(def task-file-template
  (str "# Task <id>: <description>

**Document ID:** `TASK-<name>-<nnn>[@<version>]`
" (config-identification "TASK") "

## TASK-<name>-<nnn>.P1 Scope

Type: AFK

## TASK-<name>-<nnn>.P2 Must implement exactly

- **TASK-<name>-<nnn>.MI1:** Required implementation point.

## TASK-<name>-<nnn>.P3 Done when

- **TASK-<name>-<nnn>.DW1:** Acceptance criterion.

## TASK-<name>-<nnn>.P4 Out of scope

- **TASK-<name>-<nnn>.OS1:** Boundary excluded from this task.

## TASK-<name>-<nnn>.P5 References

- **TASK-<name>-<nnn>.REF1:** Relevant proposal, plan, spec, RFC, or code reference.
"))

(def plan-notes-template
  "## PLAN-<name>-<nnn>.P8 Task context

- **PLAN-<name>-<nnn>.TC1:** Problem statement / MVP goal, important references, and task strategy.

## PLAN-<name>-<nnn>.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

### PLAN-<name>-<nnn>.DN1 Task <id>: <description> — <YYYY-MM-DD>

- Note relevant for later agents or follow-up scope.
")

;; ---------------------------------------------------------------------------
;; Guides

(def ^:private proposal-guide
  {:purpose "Feature-local problem framing that starts an active feature folder: why the feature exists and what product/domain scope it owns, before planning or task slicing."
   :artifacts {:proposal (:proposal paths)
               :feature-specs (:feature-specs paths)}
   :prerequisites
   ["The request has enough scope to name a kebab-case <feature>; ask when ambiguous or when one request spans several features."
    "Relevant root specs, RFCs, and code have been read when they affect problem framing or scope."]
   :knowledge {:ownership (select-keys document-ownership [:proposal :rfc :root-spec :plan :tasks])}
   :procedures
   {:write
    ["Choose a kebab-case <feature> from the request; ask if ambiguous."
     "Create devflow/feat/<feature>/ and devflow/feat/<feature>/specs/ if needed."
     "Write proposal.md from the template, allocating the document ID and document-prefixed sub IDs per the ID convention."
     "If the proposal exposes unresolved alternatives, write an RFC (see the :rfc guide) before planning."
     "If the proposal changes durable contracts, stage feature-local spec deltas (see the :spec guide)."]}
   :constraints
   ["Keep implementation strategy out of Proposed scope; it belongs in the feature plan."
    "Do not copy RFC alternatives into the proposal; link to the RFC."
    "Keep the proposal short enough to orient future plan/task authors quickly."
    (:editing id-convention)]
   :validation
   ["File lives at devflow/feat/<feature>/proposal.md"
    "Feature folder and specs/ staging folder exist"
    "Problem, goals, non-goals, proposed scope, and open questions are present"
    "Document has a stable PROP-<name>-<nnn>[@<version>] ID with document-prefixed sub IDs"
    "Relevant RFCs and root specs are linked or explicitly marked None"
    "Proposed scope avoids implementation phases and task detail"]
   :templates {:proposal proposal-template}
   :see-also [:rfc :spec :plan]})

(def ^:private rfc-guide
  {:purpose "Pre-feature decision record: frame an unresolved idea, compare options, recommend a direction, and record the outcome before proposal/spec/plan work."
   :artifacts {:rfc (:rfc paths)}
   :prerequisites
   ["The idea has meaningful uncertainty: a tradeoff, architectural choice, product direction, or scope question worth recording."
    "Relevant specs, active feature folders, READMEs, and code have been read when the idea touches existing behavior."]
   :knowledge
   {:write-when
    ["Multiple plausible approaches exist and the tradeoff matters."
     "The change crosses system boundaries or affects long-lived architecture."
     "Product or user-experience direction is unclear."
     "The safest next artifact is a recommendation, not code."]
    :skip-when
    ["The approach is already chosen and only sequencing is needed — write the plan instead."
     "The request is durable domain documentation with little tradeoff exploration — write a spec instead."
     "The change is a small obvious fix where code and tests are clearer than a document."]
    :statuses
    {"Draft"      "Authoring in progress; not ready for decision."
     "Open"       "Ready for feedback or explicit decision."
     "Accepted"   "Proposal chosen; follow-up belongs in specs and feature folders."
     "Rejected"   "Intentionally not pursued; still valuable — it stops the question being reopened without new evidence."
     "Superseded" "Replaced by a newer RFC; link to the replacement."}
    :naming "Filename is creation date plus a short kebab-case idea slug (2026-06-22-subagent-cost-budget.md). The document ID is separate from the filename."
    :ownership (select-keys document-ownership [:rfc :root-spec :proposal :plan])}
   :procedures
   {:write
    ["Read relevant existing context: root specs, active feature folders, READMEs, and code."
     "Create devflow/rfcs/ if it does not exist and add YYYY-MM-DD-<slug>.md."
     "Allocate the next RFC document ID per the ID convention."
     "Write the RFC from the template; keep implementation details at consequence level."
     "Leave status Draft while drafting; set Open when ready for decision."
     "If exploration proves the decision trivial, say so and switch to the lighter artifact instead."]
    :update
    ["Read the RFC and linked context."
     "Update the proposal, options, recommendation, or outcome in place; replace stale reasoning rather than preserving a debate log."
     "If an accepted RFC changes durable contracts, update the root spec or feature-local delta that owns the current contract."]
    :close
    ["Set status to Accepted, Rejected, or Superseded."
     "Fill Outcome with the decision, rationale, date, and follow-up links."
     "For Accepted RFCs, update or create the affected root specs or feature-local spec deltas."
     "If implementation is needed, create or update the feature proposal and continue with the plan."]}
   :constraints
   ["RFC status records the decision state, not implementation progress; finished feature work retires the RFC by archiving it with the feature."
    "Keep RFCs concise enough that a future agent can quickly recover the decision."
    "Never use an RFC as the current contract; root specs own that."
    "Never put implementation phases, task checklists, or detailed migration runbooks in an RFC."
    (:editing id-convention)]
   :validation
   ["File lives in devflow/rfcs/ and follows YYYY-MM-DD-<slug>.md naming"
    "Status is one of the allowed RFC statuses"
    "Problem, goals, options, recommendation, consequences, and outcome are present when relevant"
    "Document has a stable RFC-<name>-<nnn>[@<version>] ID with document-prefixed sub IDs"
    "Alternatives and tradeoffs are clear enough to make the decision repeatable"
    "Accepted outcomes that affect current contracts are represented in root specs or feature-local deltas"]
   :templates {:rfc rfc-template}
   :see-also [:proposal :spec :plan]})

(def ^:private spec-guide
  {:purpose "Describe a stable system boundary: why it exists, what it contains, what it excludes, and how it should evolve. Root specs are the current source of truth; feature-local specs and deltas stage pending changes."
   :artifacts {:spec (:spec paths)
               :feature-spec (:feature-spec paths)
               :spec-delta (:spec-delta paths)
               :readme (:readme paths)}
   :prerequisites
   ["The system being specified has a clear scope, and relevant accepted RFCs have been read."
    "For updates, the current root spec and its referenced modules have been read first."
    "For feature staging, proposal.md, the feature plan if present, and relevant feature-local specs have been read."
    "Code first: never write a spec from memory or assumption — read the actual code before documenting implemented behavior."]
   :knowledge
   {:locations
    {:spec         "Current durable domain spec."
     :feature-spec "New spec drafted by a feature before promotion."
     :spec-delta   "Pending changes to an existing root spec, merged when the feature ships. State only what changes relative to the root spec; never duplicate it wholesale."}
    :naming
    {:rule "A root spec names a stable system boundary, not a feature request or delivery task. Deltas use the root spec name plus .delta.md (task-engine.delta.md)."
     :good ["auth-system" "task-engine" "data-pipeline"]
     :bad  ["add-priority-filter" "spec-003" "phase-2-redesign"]}
    :code-references
    {:rule "Reference code at module/package granularity, never per-file; feature plans and task files may name exact files, specs may not."
     :allowed ["module roots (packages/pithos)" "named concepts the module README maps" "one-line test directory pointer"]
     :forbidden ["individual files" "per-file tables, code-location or testing-file inventories"]}
    :statuses
    {"Draft"       "Initial write-up; may not reflect code accurately yet."
     "Planned"     "Intended contracts for a system not yet built; same density as Implemented — contracts and rationale, not build instructions."
     "Implemented" "Spec matches the code."
     "Partial"     "Some sections implemented, others still planned."
     "Deprecated"  "System is being replaced or removed."}
    :spec-vs-code
    {"Why this design was chosen"     "Code shows what the design is"
     "What was explicitly rejected"   "Code shows what was built"
     "Non-goals and scope boundaries" "Code shows current behavior"
     "Cross-system tradeoffs"         "Code shows local implementation details"
     "External API contracts"         "Code shows internal types and functions"
     "Domain concepts and invariants" "Code shows the mechanics that enforce them"}
    :ownership (select-keys document-ownership [:root-spec :spec-delta :rfc :plan])}
   :procedures
   {:write-root-spec
    ["Read accepted RFCs, existing root specs, relevant feature folders, and code for implemented behavior."
     "Create devflow/specs/<stable-domain-name>.md, allocating the next SPEC ID per the ID convention."
     "Write the lightest spec that captures the boundary from the root-spec template; Purpose, Goals, Non-goals, and Design decisions are expected for most specs."
     "Add or update the spec row in devflow/README.md."]
    :update-root-spec
    ["Read the root spec, relevant code, accepted RFCs, and archive context if it explains the change."
     "Update only durable current knowledge: contracts, rationale, non-goals, design decisions, status, open questions."
     "Remove stale planned text once it no longer describes the current contract."
     "Keep code references at module level and update devflow/README.md if index data changed."]
    :write-feature-spec-or-delta
    ["Read the proposal, feature plan if present, relevant root specs, RFCs, and code."
     "Create devflow/feat/<feature>/specs/ if needed and allocate the next DELTA (or SPEC) ID per the ID convention."
     "For an existing root spec, write <spec-name>.delta.md from the delta template; for a new feature-owned spec, use the root-spec format with status Planned or Draft."
     "Link the file from the feature plan/proposal when present."]
    :promote-feature-specs
    ["Read all files in devflow/feat/<feature>/specs/ plus the affected root specs."
     "Merge each *.delta.md's durable changes into its root spec and mark the delta Merged."
     "Move or copy each new spec's durable current version into devflow/specs/ with the right status."
     "Update devflow/README.md with promoted specs and status changes."
     "Leave the feature-local copies in place for archive history."]}
   :constraints
   ["Root specs are the current source of truth; archived feature folders are historical context."
    "No implementation phases, task checklists, or per-file code maps in specs."
    "Do not duplicate RFC alternatives or proposal narrative in specs."
    "Feature deltas are temporary staging; merge shipped outcomes into root specs."
    "Prefer minimal specs; grow only when the domain needs more explanation."
    (:editing id-convention)]
   :validation
   ["Root spec lives in devflow/specs/ and names a stable domain boundary"
    "Status is valid and code pointers are module-level only"
    "Durable contracts and design decisions are captured; no phases, checklists, file trees, or test inventories"
    "Document has a stable SPEC/DELTA ID with document-prefixed sub IDs"
    "Deltas live in devflow/feat/<feature>/specs/, use <spec-name>.delta.md, and state only changes relative to the root spec"
    "devflow/README.md index is updated for root spec changes"
    "Feature plan/proposal links are updated when present"]
   :templates {:root-spec root-spec-template
               :spec-delta spec-delta-template}
   :see-also [:rfc :plan :finish-archive]})

(def ^:private plan-guide
  {:purpose "The reviewable bridge between feature framing/spec work and the task queue: how to build this, at a level worth critiquing before committing to task slices."
   :artifacts {:plan (:plan paths)}
   :prerequisites
   ["The change has a clear goal — a feature, fix, or refactor, not open-ended exploration; unresolved direction belongs in an RFC first."
    "proposal.md exists, or is written as part of the planning pass."
    "Accepted RFCs, root specs, and feature-local spec deltas relevant to the change have been read."
    "The affected code has been read enough to avoid planning against imagined structure."]
   :knowledge
   {:why "Plans earn their keep when a change is too large or risky to jump straight from proposal/spec to tasks: the approach gets critiqued once centrally, specs stay free of implementation mechanics, task files avoid carrying architecture, and the AFK loop has one feature-local home for task context and developer notes. Skip a plan only for small obvious changes that will not use a task queue — and any feature with tasks/ needs at least a minimal Reviewed plan."
    :level-of-detail
    ["Name affected modules, packages, integration points, and key files only when they are architectural anchors."
     "No exhaustive file inventories, per-function TODOs, or command-by-command instructions."
     "Phases describe independently reviewable delivery increments, not final task files."
     "Validation strategy names the suites, scenarios, or manual checks that matter; task files make checks exact later."
     "Developer Notes are append-only operational context for agents running the task loop."]
    :statuses
    {"Draft"     "Approach is still being written or critiqued; do not generate AFK tasks yet."
     "Reviewed"  "Approach has been critiqued and is ready to slice into tasks."
     "Active"    "Tasks or implementation are in progress."
     "Shipped"   "Durable outcomes are merged into root specs; folder ready to archive."
     "Abandoned" "Work stopped intentionally; folder ready to archive with rationale preserved."}
    :ownership (select-keys document-ownership [:plan :proposal :spec-delta :tasks :rfc])}
   :procedures
   {:write
    ["Read accepted RFCs, proposal.md, affected root specs, feature-local spec deltas, and affected code first."
     "Create devflow/feat/<feature>/<feature>.plan.md from the template, allocating the PLAN ID per the ID convention."
     "Omit sections that genuinely do not apply, except Goal and scope, Approach, Affected areas, Implementation phases, Validation strategy, Task context, and Developer Notes."
     "Record durable contract changes surfaced while planning as feature-local spec deltas (see the :spec guide)."
     "Leave status Draft until the plan has been critiqued; set Reviewed only after review feedback is addressed."]
    :review-or-update
    ["Read the plan, proposal, linked RFC/specs, task queue if present, and affected code."
     "Critique for approach fit, missing dependencies, over-broad phases, hidden domain decisions, and task-generation readiness."
     "Rewrite the plan in place; plans are working documents, not history logs."
     "Move durable contract changes to feature-local spec deltas or new specs."
     "If direction-level uncertainty remains, pause task generation and write an RFC."
     "When the approach is settled and phases are sliceable, set status Reviewed."]}
   :constraints
   ["Plans are reviewable strategy documents, not task queues."
    "One active plan per feature folder; split multi-feature roadmaps into separate feature folders."
    "Never plan against imagined code structure; read affected code first."
    "A Draft plan must not be sliced into AFK tasks; for small obvious queued work, create a minimal plan and mark it Reviewed after a sanity check."
    "Once tasks exist, the task index owns sequencing and detailed acceptance criteria; stop maintaining the phase list as a parallel tracker."
    (:editing id-convention)]
   :validation
   ["Lives at devflow/feat/<feature>/<feature>.plan.md"
    "Links to the proposal, relevant RFCs, root specs, and feature-local specs"
    "Goal and scope, Approach, Affected areas, Implementation phases, Validation strategy, Task context, and Developer Notes are present"
    "Document has a stable PLAN-<name>-<nnn>[@<version>] ID with document-prefixed sub IDs"
    "Phase outcomes are independently buildable and verifiable"
    "Plan stays at strategy/phase level; no per-task implementation checklist"
    "Durable contract changes surfaced while planning were recorded in feature-local specs"
    "Status is Draft until critique is complete, then Reviewed before task generation"]
   :templates {:plan plan-template}
   :see-also [:proposal :spec :tasks]})

(def ^:private tasks-guide
  {:purpose "A deterministic, feature-local AFK task queue: tracer-bullet vertical slices an unattended agent can execute one at a time, referencing the proposal/spec/RFC/plan instead of duplicating rationale."
   :artifacts {:task-index (:task-index paths)
               :task-file (:task-file paths)
               :plan (:plan paths)}
   :prerequisites
   ["proposal.md exists and <feature>.plan.md is Reviewed; for small obvious work, create a minimal plan and mark it Reviewed after a lightweight sanity check."
    "Relevant proposal, plan, feature specs, root specs, RFCs, and affected code/tests have been read; inspect the codebase when the implementation area is unclear."
    "Tasks belong to exactly one active feature folder; ask for the feature name when it cannot be inferred."]
   :knowledge
   {:index-schema
    {:fields {:id          "Integer, starting at 1, increasing by one."
              :description "Short enough to use in a session name; prefix [HITL] for HITL tasks."
              :task_file   "Detailed task markdown path: three-digit zero-padded id plus slug (tasks/001-terse-task-title.md)."
              :status      "One of pending, in_progress, blocked, complete."
              :blocked_by  "List of task ids that must be complete before this task can run."}
     :statuses {"pending"     "Ready to start when dependencies are complete."
                "in_progress" "Selected or being continued."
                "blocked"     "Needs human input; skipped by the AFK loop."
                "complete"    "Finished and committed."}
     :rules ["Do not add extra YAML fields; put notes in the feature plan, not the index."
             "Dependencies live only in blocked_by, never in task markdown prose."]}
    :slicing
    ["Each task delivers a narrow but complete path through the relevant integration layers, not a horizontal layer-only change."
     "Each completed task is independently verifiable."
     "Prefer many thin slices over a few broad slices, and AFK-ready slices where possible."
     "Keep slices small enough for one agent run; prefer a workable MVP over comprehensive scope."
     "Put human/architectural uncertainty into the plan's Task context or Developer Notes, not hidden in task scope."]
    :classification
    {:afk  "Safe for an unattended agent loop: clear contract, enough context, deterministic validation, no user decisions, credentials, design judgment, or external access needed."
     :hitl "Requires human interaction first: an architectural decision, product/design choice, unclear acceptance criteria, secret/access setup, manual QA, or a meaningful tradeoff."
     :rules
     ["Prefer AFK; do not mark a slice HITL just because it is complex — split complex work into smaller AFK slices."
      "No YAML type field; the index schema is fixed."
      "AFK tasks use status pending, with blocked_by for dependencies (never status blocked for dependency waits)."
      "HITL tasks prefix the description with [HITL] and use status blocked until the human input exists."
      "In each task file, put Type: AFK or Type: HITL as the first line under Scope."
      "If HITL produces a decision that unlocks implementation, make the decision task HITL and create separate AFK implementation tasks blocked by it."]}
    :specificity
    ["Task files may be more specific than the plan: name exact files, functions, commands, fixtures, and assertions when unattended execution needs them."
     "Keep rationale short; link to the RFC/spec/proposal/plan for why and high-level how."
     "Translate plan phases into narrow implementation contracts; never copy phase prose into every task."
     "Include only references the implementer must inspect or change."]}
   :procedures
   {:create-queue
    ["Gather context: read the proposal, Reviewed plan, feature specs, root specs, RFCs, and affected code."
     "Confirm the feature folder; tasks must belong to exactly one."
     "Draft tracer-bullet vertical slices per the slicing rules."
     "Classify every slice AFK or HITL per the classification rules."
     "Write tasks/index.yml and one task file per slice from the templates, allocating TASK IDs per the ID convention."
     "Record task context, important references, and strategy in the plan's Task context section."
     "Request review: check the full sequence for ordering issues and dependency deadlocks, each task file for standalone clarity, and queue/plan/spec cohesion against the MVP goal."]
    :update-queue
    ["Read tasks/index.yml, the feature plan, and relevant task files before editing."
     "Preserve task ids, file names, and history for existing tasks unless the task has not started and the change is purely clarifying."
     "Do not edit completed task files except to fix formatting/references that break the queue."
     "Prefer adding follow-up tasks with the next integer ids over rewriting old tasks; never use decimal ids. Use blocked_by to slot new work after its prerequisites."
     "Narrow a too-broad pending task in place only when no agent has started it; for in-progress or completed tasks, keep the published contract intact and extract follow-up tasks."
     "Append amendment rationale to the plan's Developer Notes; never hide important plan changes in task files."]}
   :constraints
   ["Create or update the queue only; do not implement the tasks."
    "Make dependencies explicit and minimal."
    "No speculative future work unless needed to protect the MVP boundary."
    "Tasks are not durable documentation: root specs own durable outcomes."
    "Acceptance criteria belong in Done when; follow-up ideas belong in the plan's Developer Notes."
    (:editing id-convention)]
   :validation
   ["tasks/index.yml matches the fixed schema with integer ids and valid statuses"
    "Every task has a task file at the three-digit zero-padded path"
    "Every task file has a stable TASK ID, Type line under Scope, Must implement exactly, Done when, Out of scope, and References"
    "Dependencies are encoded only in blocked_by and contain no cycles"
    "HITL slices are [HITL]-prefixed and blocked; AFK slices are pending"
    "Task context and strategy are recorded in the feature plan"]
   :templates {:task-index task-index-template
               :task-file task-file-template
               :plan-notes plan-notes-template}
   :see-also [:plan :afk]})

(def ^:private afk-guide
  {:purpose "Execute the approved task queue unattended, one slice at a time, until the queue is exhausted, blocked, or a run fails."
   :artifacts {:task-index (:task-index paths)
               :plan (:plan paths)}
   :prerequisites
   ["proposal.md, <feature>.plan.md, and tasks/index.yml exist in the feature folder."
    "The plan status is Reviewed or Active; a Draft plan routes back to plan review first."
    "tasks/index.yml has at most one in_progress task, valid blocked_by ids, and at least one runnable pending or in_progress task."
    "The worktree is clean, or the only dirt belongs to the single in_progress task."]
   :knowledge
   {:modes
    {:delegated "Approve the task queue (choose! :approved) with choice input :tasks (vector of {:id :title :body :harness} maps) plus :delegate-harness / :delegate-cwd / :delegate-preamble; devflow pours one sequential subagent gate per task and finishes with a human acceptance checkpoint."
     :external  "Approve without :tasks to keep the single run-afk-loop step, run or hand off an external loop runner, then complete! the step."}
    :queue-states
    {:exhausted "All tasks complete: report it and move to finish/archive instead of running the loop."
     :blocked   "No runnable task because work is blocked/HITL: report the blocked state instead of running the loop."}
    :loop-contract
    ["Select the next runnable task: pending with all blocked_by complete, or the single in_progress task."
     "Run one slice per cycle; never run concurrent tasks in one worktree — use separate worktrees for parallelism."
     "Mark the task in_progress while working and complete when finished and committed."
     "Append discoveries, blockers, and follow-up notes to the plan's Developer Notes, not to the task YAML."
     "Stop when tasks are exhausted, a task is blocked on human input, or a run fails."]}
   :procedures
   {:prepare
    ["Identify the feature folder; ask if ambiguous."
     "Verify proposal, plan, and task index exist and the plan is Reviewed or Active."
     "Inspect tasks/index.yml for a runnable queue (statuses, blocked_by validity, single in_progress)."
     "Report exhausted or blocked queues instead of starting a loop."
     "Choose the delegated or external mode and start the loop."]}
   :constraints
   ["AFK work follows the signed-off plan and task contracts; queue-shape changes go through the :tasks guide's update procedure."
    "HITL tasks are never picked up by the loop; they wait for human input."]
   :validation
   ["Each completed slice is committed and its index status is complete"
    "Blockers and discoveries are recorded in the plan's Developer Notes"
    "The queue state (exhausted/blocked/failed) is reported when the loop stops"]
   :see-also [:tasks :finish-archive]})

(def ^:private finish-archive-guide
  {:purpose "Close out a shipped or abandoned feature: promote durable spec outcomes, reconcile task state, mark the plan, and move the feature folder (plus implemented RFCs) into the archive."
   :artifacts {:archive (:archive paths)
               :archived-rfcs (:archived-rfcs paths)
               :specs (:specs paths)
               :readme (:readme paths)}
   :prerequisites
   ["Feature work is shipped, intentionally abandoned, or the user asked to finish/archive."
    "The proposal, plan, linked RFCs, task queue, feature-local specs, and affected root specs have been read."]
   :knowledge
   {:outcomes
    {:shipped   "Implementation is complete enough that durable outcomes should become canonical."
     :abandoned "Work stops intentionally; do not promote unshipped contract changes unless the user explicitly asks."}
    :rfc-selection
    ["Archive RFC files explicitly linked from the proposal (Related RFCs) or plan (RFC)."
     "If multiple active features link the same RFC, ask before moving it; otherwise the implementing feature owns archiving it."
     "Do not archive RFCs that are only background reading or still needed by another active feature."]}
   :procedures
   {:finish
    ["Identify the feature folder; ask if ambiguous."
     "Read the proposal, plan, linked RFCs, task index and task files, feature-local specs, and affected root specs."
     "Identify the RFCs to archive per the rfc-selection rules."
     "Reconcile task state with implementation reality: confirm shipped-scope tasks are complete and covered by code/tests; classify incomplete tasks as cut scope; record cut, deferred, or abandoned scope in the plan's final Developer Notes."
     "Decide the outcome: shipped or abandoned."
     "For shipped work, run the :spec guide's promote-feature-specs procedure: merge deltas into root specs, promote new canonical specs, update the devflow/README.md index, and mark deltas Merged."
     "Update the plan: set Status Shipped or Abandoned, update Last Updated, and add a final Developer Notes entry summarizing shipped scope, cut scope, abandonment reason, and archived RFCs."
     "Move the feature folder to devflow/archive/yy-mm-dd__<feature>/."
     "Move each implemented RFC from devflow/rfcs/ into that archive's rfcs/ folder."
     "Report the root specs updated, feature folder archived, RFCs archived, and any cut or unpromoted scope."]}
   :constraints
   ["Do not promote unshipped behavior into root specs unless the user explicitly asks."
    "Do not delete proposal, plan, task, or archived RFC files; preserving feature-local context is the point of the archive."
    "Never edit other archived features or sibling feature folders while archiving."]
   :validation
   ["Shipped durable outcomes are merged into root specs and the README index is updated"
    "Cut or abandoned scope is recorded in the plan before archive"
    "Plan status is Shipped or Abandoned with a final Developer Notes entry"
    "Feature folder moved intact to devflow/archive/yy-mm-dd__<feature>/"
    "Implemented RFCs moved into the archive's rfcs/ folder"]
   :see-also [:spec :plan]})

(def guides
  "Every devflow authoring guide by stable key. Workflow steps reference these
  keys through the `devflow/guide` attribute; `overview` indexes them."
  {:proposal proposal-guide
   :rfc rfc-guide
   :spec spec-guide
   :plan plan-guide
   :tasks tasks-guide
   :afk afk-guide
   :finish-archive finish-archive-guide})

(defn guide
  "Return the guide for `k`, failing loudly on an unknown key."
  [k]
  (or (get guides k)
      (throw (ex-info "Unknown devflow guide" {:guide k :guides (vec (keys guides))}))))

(defn overview
  "Return the devflow workspace orientation: layout, paths, invariants, the
  document-ID convention, document ownership, and an index of available guides."
  []
  {:workspace {:layout layout
               :paths paths
               :invariants invariants}
   :id-convention id-convention
   :document-ownership document-ownership
   :guides (into (sorted-map) (map (fn [[k g]] [k (:purpose g)])) guides)})
