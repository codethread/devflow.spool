# Agents

This repo ships a Millstrand spool (a spool is a Millstrand library) with two roots: the devflow library (`src/`, coupled to no card system by design) and the kanban adapter (`kanban-adapter/`, the one root allowed to know both devflow and kanban). See ./README.md and ./devflow.md for orientation. The same prime commands below also orient you to write spool code.

<!-- mill:millstrand-prime -->

## Millstrand / strand

This repo uses Millstrand strands to track work. Orientation ships in the `mill` CLI:

- `mill prime millstrand` — where the Millstrand source and docs live, and how to extend this repo's `.millstrand/` config.
- `mill prime strand` — the strand planning/tracking workflow; run it before multi-step work.
<!-- /mill:millstrand-prime -->
