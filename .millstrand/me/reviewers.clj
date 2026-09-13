(ns me.reviewers
  "Declare review lenses for the Devflow source, adapter, and docs.

  Shared Codethread aliases are selected by `codethread/config`; this module
  owns only the repository-specific review policy."
  (:require [ct.spools.harnesses.reviewers :as reviewers]
            [millstrand.api.format.alpha :as format-alpha]))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(reviewers/defreviewer!
  source-form
  "Check Clojure readability and source prose."
  {:seat ['reviewer 'luna]
   :labels ["PR" "Clojure" "Readability"]
   :glob ["src/**" "kanban-adapter/src/**"]
   :system-prompt
   (format-alpha/prose
    "
     This is a judgment-focused source-form review. Prefer a clear public
     story, named steps, and prose that is easy to scan over stylistic
     nitpicks. Do not duplicate the correctness or test-coverage review.
     "
    {})}
  (format-alpha/prose
   "
    Review changed Clojure source for readability and source prose, not for
    the test suite. Check that public entry points lead the reader through
    named steps, that helpers do not hide important control flow, and that
    docstrings, comments, and long prose values follow the surrounding source
    style. Report only concrete P1/P2 defects with repository-relative paths
    and line numbers, plus a practical rewrite or restructuring. Explicitly
    say `No findings` when the changed source is clear. Do not edit files or
    repository state.
    "
   {}))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(reviewers/defreviewer!
  docs-and-tests
  "Check contract coverage in docs and tests."
  {:seat ['luna 'reviewer]
   :labels ["PR" "Docs" "Tests"]
   :glob ["README.md" "devflow.md" "docs/**" "resources/**" "src/**"
          "kanban-adapter/**" "test/**"]}
  (format-alpha/prose
   "
    Review changed files for contract coverage. Check README and relevant
    docs for commands, public names, and behavior that the patch changes.
    Check source and tests for a focused, meaningful proof of the promised
    behavior; do not demand tests for claims they cannot establish. Report
    concrete P1/P2 omissions with repository-relative paths and line numbers,
    followed by a practical fix. Explicitly say `No findings` when coverage
    is adequate. Do not edit files or repository state.
    "
   {}))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(reviewers/defreviewer!
  runtime-correctness
  "Check correctness of changed Clojure runtime behavior."
  {:seat ['luna 'reviewer]
   :labels ["PR" "Correctness" "Clojure"]
   :glob ["src/**" "kanban-adapter/src/**"]}
  (format-alpha/prose
   "
    Trace changed Clojure runtime behavior through its public seam and the
    adjacent paths it touches. Look for incorrect state transitions, boundary
    handling, error behavior, resource ordering, and concurrency mistakes.
    Report only actionable P1/P2 defects supported by repository-relative
    paths and line numbers, with the smallest practical fix. Explicitly say
    `No findings` when behavior is sound. Do not edit files or repository
    state.
    "
   {}))
