# Agents

This repo ships a Millstrand spool (a spool is a Millstrand library) with two roots: the devflow library (`src/`, coupled to no card system by design) and the kanban adapter (`kanban-adapter/`, the one root allowed to know both devflow and kanban). See ./README.md and ./devflow.md for orientation. The same prime commands below also orient you to write spool code.

<!-- mill:millstrand-prime -->

## Millstrand / strand

This repo uses Millstrand strands to track work. Orientation ships in the `mill` CLI:

Start with `strand --help`. Run `mill prime millstrand` on demand when building on this repo's `.millstrand/` config or spools.
<!-- /mill:millstrand-prime -->
