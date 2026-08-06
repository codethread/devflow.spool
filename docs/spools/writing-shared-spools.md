# Writing shared spools

This spool follows Millstrand's [shared-spool publishing contract](https://github.com/codethread/millstrand/blob/5790c459e9bb692b5e975f9715df7d5b403feff2/docs/spools/writing-shared-spools.md#publishing-a-shared-spool-with-git-distribution). The consumer approval example in [README.md](../../README.md) uses one SHA-pinned family entry and maps each published root with `:roots`.

Use `:local/root` only in a gitignored `spools.local.edn` overlay for local development. A published approval must name the Git URL, the full commit SHA, and every root the consumer loads.

## CLI style

The authoritative [CLI-style section](https://github.com/codethread/millstrand/blob/5790c459e9bb692b5e975f9715df7d5b403feff2/docs/spools/writing-shared-spools.md#cli-style) defines vocabulary for shared-spool commands and their argument contracts. Devflow's `devflow` op follows that contract; its `about` and `prime` metadata use the `|`-margin blocks and `millstrand.api.format.alpha/reflow` required by the same guide.
