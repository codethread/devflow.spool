# Devflow guide: proposal

Feature-local problem framing that starts an active feature folder: why the feature exists and what product/domain scope it owns, before planning or task slicing. Approved, it is the frozen record of the intent everyone agreed to.

## Artifacts

- Proposal: `devflow/feat/<feature>/proposal.md`
- Feature spec staging: `devflow/feat/<feature>/specs/`

## Prerequisites

- The request has enough scope to name a kebab-case `<feature>`; ask when ambiguous or when one request spans several features.
- Relevant root specs, RFCs, and code have been read when they affect problem framing or scope.

## Statuses

- **Draft** — Under discussion; rewrite freely across sign-off revision rounds.
- **Approved** — Signed off by a human at `:human-signoff-proposal`; frozen from that point on.

## Immutability

A proposal is rewritten only while Draft. Human sign-off freezes it: the approved text is what intent was, and nothing later edits it back into agreement with reality.

Why:

- The proposal exists to drive and record the sign-off discussion, so its value is being the intent as agreed, not a mirror of the build.
- Rewriting an approved proposal hides the original intent, and the drift is invisible in a document that always looks current.
- Keeping it in sync is busywork: spec deltas, the plan, and the code already carry what is true now.

Where drift lands instead:

- **Spec delta** — durable contract change: what will be true when the feature ships.
- **Plan** — changed approach, phases, or validation strategy; Developer Notes record why scope moved.
- **Tasks** — changed execution slices and acceptance criteria.
- **Code** — what actually exists and how it behaves.

Allowed after approval: nothing that changes meaning. Repairing a broken link or an ID typo is fine; restating problem, goals, non-goals, or scope is not.

When intent really changed:

- Small drift within the agreed problem: leave the proposal alone and record the change in the spec deltas and the plan.
- The agreed problem or scope no longer holds: raise it with the human rather than editing. They either accept the feature as re-scoped through the plan, or abort the run (`choose! :abort`) so a fresh feature run frames the new intent in its own proposal.
- A new proposal for the same problem supersedes rather than overwrites: the earlier approved document keeps its ID and text, and the successor allocates the next version per the ID convention.

## Document ownership

{{ownership-table:proposal,rfc,root-spec,plan,tasks}}

## Procedures

### Write

1. Choose a kebab-case `<feature>` from the request; ask if ambiguous.
2. Create `devflow/feat/<feature>/` and `devflow/feat/<feature>/specs/` if needed.
3. Write proposal.md from the template with Status Draft, allocating the document ID and document-prefixed sub IDs per the ID convention.
4. If the proposal exposes unresolved alternatives, write an RFC (see the rfc guide) before planning.
5. If the proposal changes durable contracts, stage feature-local spec deltas (see the spec guide).

### Revise

1. Only for a Draft proposal in a sign-off revision round; an Approved proposal is frozen.
2. Read the review feedback and rewrite the affected sections in place — a Draft proposal is a working document, not a history log.
3. Keep the document ID stable; the round is still the first version.

### Approve

1. On `:approved` or `:approved-to-cards` at `:human-signoff-proposal`, set Status to Approved and fill Approved with the sign-off date.
2. Make no further content edits: from here the proposal is read-only input to spec, plan, task, and archive work.

## Constraints

- Keep implementation strategy out of Proposed scope; it belongs in the feature plan.
- Do not copy RFC alternatives into the proposal; link to the RFC.
- Keep the proposal short enough to orient future plan/task authors quickly.
- Never edit an Approved proposal to match what was planned, built, or cut; that change belongs in the spec deltas, the plan's Developer Notes, and the code.
- {{id-editing}}

## Validation

- File lives at `devflow/feat/<feature>/proposal.md`
- Feature folder and `specs/` staging folder exist
- Problem, goals, non-goals, proposed scope, and open questions are present
- Document has a stable `PROP-<name>-<nnn>[@<version>]` ID with document-prefixed sub IDs
- Status is Draft while under review, or Approved with the sign-off date once signed off
- Relevant RFCs and root specs are linked or explicitly marked None
- Proposed scope avoids implementation phases and task detail
- An Approved proposal is unchanged since sign-off apart from meaning-preserving repairs

## Template

{{template:proposal.md}}

## See also

rfc, spec, plan — fetch another guide with `strand devflow guidance <key>`.
