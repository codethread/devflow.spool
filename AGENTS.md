# Agents

This repo ships a skein spool (a spool is a skein library) with two roots: the devflow library (`src/`, coupled to no card system by design) and the kanban adapter (`kanban-adapter/`, the one root allowed to know both devflow and kanban). See ./README.md and ./devflow.md for orientation. The same prime commands below also orient you to write spool code.

<!-- mill:skein-prime -->

## Skein / strand

This repo uses Skein strands to track work. Orientation ships in the `mill` CLI:

- `mill skein prime` — where the Skein source and docs live, and how to extend this repo's `.skein/` config.
- `mill strand prime` — the strand planning/tracking workflow; run it before multi-step work.
<!-- /mill:skein-prime -->
