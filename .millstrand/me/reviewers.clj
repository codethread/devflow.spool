(ns me.reviewers
  "Declare the Devflow-specific review lens.

  Shared Codethread aliases cover the normal source, test, and README paths;
  this module owns only the repository-specific adapter and contract policy."
  (:require [ct.spools.harnesses.reviewers :as reviewers]
            [millstrand.api.format.alpha :as format-alpha]))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(reviewers/defreviewer!
  devflow-contract
  "Check Devflow adapter and contract coverage."
  {:seat ['reviewer 'luna]
   :labels ["PR" "Contracts" "Devflow"]
   :glob ["kanban-adapter/**" "resources/**" "devflow.md"]}
  (format-alpha/prose
   "
    Review changed adapter, resource, and Devflow contract files for
    correctness, readability, and contract coverage. Trace adapter/workflow
    behavior through its public seam, checking state transitions, boundary
    handling, resource ordering, and error behavior. Check devflow.md and
    relevant resource documentation for commands, public names, and behavior
    changed by the patch. Check adapter tests for a focused, meaningful proof
    of promised behavior; do not demand tests for claims they cannot
    establish. Report only concrete P1/P2 defects with repository-relative
    paths and line numbers, followed by the smallest practical fix. Explicitly
    say `No findings` when the changed contract is sound. Do not edit files or
    repository state.
    "
   {}))
