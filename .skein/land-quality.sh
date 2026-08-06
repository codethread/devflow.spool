#!/usr/bin/env bash
set -euo pipefail

# The local land contract for codethread/devflow. The registered land workflow
# owns branch/push/clean-tree checks; this file owns repository behavior gates.
repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$repo_root"

run() {
  printf '\n[land-quality] %s\n' "$*"
  "$@"
}

run git diff --check
run clojure -M:test
run ./bin/identity-check
run ./bin/verify-card-authoring-equivalence

# MSR-07 is the deliberate alpha identity break. The alarm must fire against
# the previous v20 suite; a green alarm would mean the breaking boundary was
# not actually exercised.
printf '\n[land-quality] expected compatibility break: timeout 30 ./bin/compat-alarm v20\n'
if timeout 30 ./bin/compat-alarm v20; then
  echo "compat-alarm v20 unexpectedly passed across the Millstrand break" >&2
  exit 1
else
  echo "compat-alarm v20: expected break observed"
fi

printf '\n[land-quality] identity and release gates: clean\n'
