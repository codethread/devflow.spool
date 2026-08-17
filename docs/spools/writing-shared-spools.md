# Writing shared spools

This spool follows Millstrand's [shared-spool publishing contract](https://github.com/codethread/millstrand/blob/e0ca975cd0d2d546249f63c9d699ae33e1c9b688/docs/spools/writing-shared-spools.md#publishing-a-shared-spool-with-git-distribution). The consumer approval example in [README.md](../../README.md) uses one SHA-pinned family entry and maps each published root with `:roots`.

Use `:local/root` only in a gitignored `spools.local.edn` overlay for local development. A published approval must name the Git URL, the full commit SHA, and every root the consumer loads.

## CLI style, vocabulary, and argument contracts

The following bounded rules are copied from Millstrand's [pinned CLI-style section](https://github.com/codethread/millstrand/blob/e0ca975cd0d2d546249f63c9d699ae33e1c9b688/docs/spools/writing-shared-spools.md#cli-style) and are the applicable contract for this spool:

- Verbs follow the role already named by the primitive: entity lifecycles use `start`, `finish --outcome`, `abort`, `status <id>`, and `list`; workflow steps use `start`, `next`, `complete`, `choose`, and `status`; processes use `spawn`, `kill`, `retry`, `await`, `logs`, and `ps`. A wrapper keeps the primitive's verb rather than inventing a domain synonym.
- Use `--by` for attribution. Name attribute flags after the attribute, such as `--owner`, `--branch`, `--worktree`, and `--feature`; use seconds-first duration names such as `--timeout-secs` and `--outcome` for closing state.
- Prefer `list` for live, filterable entities and a plural noun for a fixed catalog. Prefer one operation with declared subcommands for a cohesive multi-verb domain; keep single-purpose projections flat.
- Every text-bearing flag or positional argument MUST use the declared arg-spec parser so whole-value `:stdin` and `:payload/<name>` references resolve. Argument maps are boundary contracts: reject unknown keys, validate required/type-constrained values, and fail loudly with the allowed and received/unknown values rather than silently ignoring input.

Devflow's `devflow` op follows this vocabulary. Its `about` and `prime` metadata use `|`-margin blocks and `millstrand.api.format.alpha/reflow`, as required by the same pinned guide.
