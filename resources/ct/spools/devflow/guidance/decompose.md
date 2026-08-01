# Devflow guide: decompose

Decompose a merged, approved proposal into self-contained implementation cards a cold agent can work without this run's context.

## Artifacts

- Proposal: `devflow/feat/<feature>/proposal.md`

## Prerequisites

- The approved proposal is merged on the repository mainline; decomposition reads the merged copy, not a working-tree draft.
- The proposal, its linked RFCs, and the affected root specs have been read.
- The workspace's card system (kanban board, issue tracker, ...) is known; devflow does not supply or assume one.

## The cold-card contract

- Each card is workable cold: an agent holding only the card body and the merged proposal can claim it, do the work, and finish it — card bodies carry context, never pointers into this run's conversation.
- A card body states what exists today (with file/line evidence where it helps), the target shape, and any constraints or design decisions already validated during proposal work.
- Each card names an explicit done-when: observable outcomes, the validation gates that must be green, and how the work lands (merge and release discipline included).
- Every card lands independently before it closes; a card that cannot land alone is sliced wrong.

## Dependency edges

- Cards that must land in order declare dependency edges, so a ready/frontier view serves only workable cards.
- Independent cards declare no edges; false edges serialize work that could land in any order.

## Open decisions

A decision the proposal left open is either resolved on the card as recorded guidance, or the card instructs the worker to surface it as a blocker — a cold worker never decides it silently.

## Review scopes

Feature card:

- One focused reviewer per feature card checks only that card's cold-work contract, proposal traceability, direct dependencies, validation, and independent landing shape.
- Focused reviews fan out without dependency edges; the configured feature-card reviewer seat is reused for each card.
- A focused reviewer does not redesign the epic or repeat set-wide coverage analysis.

Epic:

- One separately configured epic reviewer starts after every focused review fans in.
- It checks proposal-goal coverage, gaps and overlaps, slicing, dependency direction, integration seams, and cross-card open decisions.
- It assumes focused review is complete and must not repeat fine-grained card-contract checks.

## Procedures

### Decompose

1. Read the merged proposal; note its goals, non-goals, and validation requirements.
2. Draft one epic/grouping card and the feature-card set: one independently landable outcome per feature card, sliced by outcome rather than by file or layer.
3. Write each feature-card body per the cold-card contract: current state, target shape, constraints, done-when with validation gates and landing discipline.
4. Declare dependency edges exactly where landing order is constrained.
5. At handoff-card-review, choose review with the epic ref and complete feature-card ref vector; each ref carries a token-safe id and title.

### Reconcile reviews

1. Wait for all focused feature-card review gates and the later epic cohesion gate to close.
2. Read `agent-run/result` from every review gate; keep focused findings attached to their feature card and epic findings attached to slicing, coverage, or edges.
3. Apply valid findings in the workspace's card system without editing the merged proposal.
4. If any material card changed, choose review-again with the current full card set; otherwise choose accepted and let the card loop own implementation.

## Constraints

- Never edit the merged proposal; divergence discovered while decomposing is recorded on the affected cards.
- Do not start implementation from this run; implementation belongs to the card loop that works the authored cards.
- Cards are not filesystem artifacts: author them in the workspace's card system, not as `devflow/` documents.

## Validation

- Every proposal goal is covered by at least one card and every card traces back to the proposal
- Each card body passes the cold-card contract: context, target shape, done-when, validation gates, landing discipline
- Dependency edges reflect real landing-order constraints and nothing else
- Open decisions are recorded as card guidance or explicit surface-a-blocker instructions, never left implicit
- Every feature card has a focused review result from the configured feature-card reviewer seat
- The epic has one later cohesion result from the configured epic reviewer seat, scoped to connections rather than repeated fine-grained checks
- The driving agent reconciled both result classes and repeated review after material card changes

## See also

proposal, finish-archive — fetch another guide with `strand devflow guidance <key>`.
