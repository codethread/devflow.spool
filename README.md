# devflow.spool

An opinionated **feature-delivery lifecycle**, built on
[Skein workflows](https://github.com/codethread/skein/blob/main/spools/workflow.md)
and shipped as a git-distributed spool for [Skein](https://github.com/codethread/skein).

You give it a feature name. It walks you and your agents from "here's a rough
idea" to either **reviewed implementation cards on mainline** or **accepted,
implemented code** — and leaves a documentation trail behind in the repo.

```sh
strand workflow start search-filters --workflow intake \
  --params '{"feature":"search-filters"}'
# => next up: create the worktree
```

Everything is keyed by that feature name — it *is* the `workflow/run-id`, so
there is no separate run handle to keep.

## What you get out of it

> **Code tells you *what*. Devflow docs tell you *why*.**

```
devflow/
|-- rfcs/YYYY-MM-DD-<slug>.md
|-- specs/<spec-name>.md              <- canonical: how the system behaves
|-- feat/<feature>/
|   |-- proposal.md                   <- why, agreed before any code
|   |-- specs/<spec-name>.delta.md    <- how this feature changes the above
|   |-- <feature>.plan.md
|   `-- tasks/index.yml + <id>-<slug>.md
`-- archive/yy-mm-dd__<feature>/      <- every past feature, proposals and deltas
```

- **`proposal.md` is plan mode, written down.** It comes before any code and the
  run stops for human sign-off, so there's time to align with the agent while
  changing your mind is still cheap. Approval freezes it as the record of what
  was agreed.
- **`specs/` + `<spec>.delta.md` keep the docs with the code.** `specs/` is
  canonical for the system's observable behaviours; every feature states its
  change as a delta beside its proposal, reviewable as a diff against the current
  contract. Those deltas merge into the canonical specs when the feature
  completes — updating the docs *is* how a feature finishes.
- **`archive/` holds every feature that came before**, proposals and deltas
  intact: why the current specs say what they say.
- **Stable ids throughout** — `PROP-Sfl-001`, `PLAN-Sfl-001.P1` — so you can
  point an agent at one exact paragraph in chat, and still find it by the same
  search years later.

The rules for writing each document ship with the spool as markdown guides:
`strand devflow guidance proposal` (or `(devflow/guidance :proposal)` from
Clojure) returns its purpose, prerequisites, procedure, constraints, checklist,
and markdown template. No external skill file needed.

## How it gets there

- **A staged lifecycle.** Intake → proposal → sign-off →
  (cards | spec+plan → tasks → execution), each stage its own workflow
  definition.
- **Two working models.** Either hand the feature off to a card loop (each card
  worked cold by its own agent), or drive spec/plan/tasks/implementation inside
  the one run.
- **Revision loops that don't waste work.** "Revise" re-runs a stage and skips
  the setup steps it already did.
- **Delegated review and execution.** Card reviews fan out to subagents
  (focused per-card reviews, then one whole-epic cohesion review); approved task
  queues can run as sequential subagent gates.
- **An abort path from every human decision point**, with a required reason.

👉 **[devflow.md](./devflow.md) is the guide** — the documents, the stage flows,
and how to drive a run.

## Install

### Prerequisites

- **Skein at `70a3c50e27ca0190f363d80d0b0cac72948dbacb` or later**, and a live
  weaver:
  ```sh
  git -C /path/to/skein merge-base --is-ancestor 70a3c50e27ca0190f363d80d0b0cac72948dbacb HEAD
  ```
  No release marker contains it yet, so `spool.edn` declares no `:skein/min`
  floor; see the v10 entry in `CHANGELOG.md`.
- **`skein.spools.workflow`** — the engine devflow builds on.
- **`camel-snake-kebab/camel-snake-kebab`**, declared in this spool's `deps.edn`.

### Approve the sources

In the **consumer's** `spools.edn`:

```clojure
{:spools {skein.spools/workflow {:local/root "/path/to/your/skein/spools/workflow"}
          codethread/devflow {:git/url "git@github.com:codethread/devflow.spool.git"
                              :git/sha "<40-hex-sha-for-the-approved-commit>"}}}
```

For local development, overlay in `spools.local.edn` (usually gitignored):

```clojure
{:spools {codethread/devflow {:local/root "/Users/you/dev/devflow.spool"}}}
```

> This repo's `spool.edn` is advisory producer metadata, not consumer approval.

To pin the engine independently of your Skein checkout, `skein.spools/workflow`
also takes `:git/url` + `:git/sha` + `:deps/root "spools/workflow"`.

### Activate the modules

From trusted `init.clj` or REPL code:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

;; Provides strand list, ready, and query for Devflow discovery.
(runtime/module! runtime
  :skein/spools-batteries
  {:ns 'skein.spools.batteries
   :spools ['skein.spools/batteries]
   :required? true})

(runtime/module! runtime
  :workflow
  {:ns 'skein.spools.workflow
   :spools ['skein.spools/workflow]
   :required? true})

(runtime/module! runtime
  :skein/spools-workflow-cli
  {:ns 'skein.spools.workflow.cli
   :spools ['skein.spools/workflow]
   :after [:workflow]
   :required? true})

(runtime/module! runtime
  :devflow
  {:spools ['codethread/devflow]
   :ns 'ct.spools.devflow
   :after [:workflow]
   :required? true})
```

Devflow needs `:workflow` declared first, and `:after [:workflow]` keeps a failed
prerequisite explicit. The batteries module provides the `strand list`, `ready`,
and `query` commands used for discovery; the workflow CLI provides lifecycle
commands. Devflow's stage definitions and queries register as the namespace
loads — there is no `spool`, `contribute`, or `reconcile` Var to call.

### Check it worked

The generic workflow CLI discovers and drives Devflow. `intake` is its sole
startable definition; the other stages are routed or callable components:

```sh
strand workflow list
strand workflow list --entrypoint continue
strand workflow show intake
strand devflow guidance
```

## Using it

Devflow is a set of ordinary Skein workflows. Start and drive the `intake`
workflow through the generic surface:

```sh
strand workflow start search-filters --workflow intake \
  --params '{"feature":"search-filters","worktree-check":"already-in-worktree-ok"}'
strand workflow ready search-filters
strand workflow next search-filters --choice already-in-worktree
```

Resume and find work across sessions with Devflow's named queries:

```sh
strand list --query devflow-runs
strand ready --query devflow-ready
```

`strand devflow guidance [<guide>]` serves the static authoring knowledge
(`(devflow/guidance)` is its Clojure twin). See [devflow.md](./devflow.md) for
lifecycle flows and generic workflow commands.
