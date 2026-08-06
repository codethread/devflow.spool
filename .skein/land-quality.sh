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
compat_output=$(mktemp)
set +e
timeout 30 ./bin/compat-alarm v20 >"$compat_output" 2>&1
compat_status=$?
set -e
cat "$compat_output"

# The archived v20 suite must stop at the intentional old-identity import
# failure. A timeout, missing tool/dependency, setup error, or any other test
# failure is a land failure and must remain visible to the caller.
expected_signature='Could not locate skein/api/current/alpha__init.class, skein/api/current/alpha.clj or skein/api/current/alpha.cljc on classpath.'
expected_execution='^Execution error \(FileNotFoundException\) at ct\.spools\.devflow-kanban-adapter-test/'
unexpected_output=$(grep -Ev "^(WARNING:.*|$|${expected_execution}.*|${expected_signature}|Full report at:|/.*/clojure-[^ ]+\\.edn)$" "$compat_output" || true)
if [[ "$compat_status" -eq 1 ]] \
  && [[ "$(grep -Fc "$expected_signature" "$compat_output")" -eq 1 ]] \
  && [[ "$(grep -Ec "$expected_execution" "$compat_output")" -eq 1 ]] \
  && [[ -z "$unexpected_output" ]]; then
  rm -f "$compat_output"
  echo "compat-alarm v20: expected break observed"
else
  rm -f "$compat_output"
  echo "compat-alarm v20: unexpected status or failure signature (status $compat_status)" >&2
  exit 1
fi

printf '\n[land-quality] identity and release gates: clean\n'
