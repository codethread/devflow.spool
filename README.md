# devflow.spool

`skein.spools.devflow` — the devflow feature-delivery lifecycle for
[Skein](https://github.com/codethread/skein), extracted from the shipped spool
set into a standalone git-distributed spool.

Built on `skein.spools.workflow` (shipped with Skein). Consume by approving a
pinned sha in your workspace `spools.edn`:

```clojure
{:spools {codethread/devflow {:git/url "git@github.com:codethread/devflow.spool.git"
                              :git/sha "<40-hex>"}}}
```

then `sync!` and `use!` with `:ns skein.spools.devflow` /
`:call skein.spools.devflow/install!` from trusted config.

Full contract documentation currently lives in the Skein repo
(`spools/devflow.md`) and will migrate here in a follow-up docs pass.
