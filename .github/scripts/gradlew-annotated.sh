#!/usr/bin/env bash
# OwnTVnew: run ./gradlew and, on failure, publish the tail of its output as a GitHub annotation.
# Annotations are readable through the check-runs API, so a failure can be diagnosed without
# opening the Actions log pages (which the dev environment cannot reach).
set -o pipefail
log="$RUNNER_TEMP/gradle-$(date +%s%N).log"
./gradlew "$@" 2>&1 | tee "$log"
status=$?
if [ $status -ne 0 ]; then
  # Prefer the "What went wrong" block; fall back to the last lines of output.
  msg=$(awk '/What went wrong/{f=1} f{print} /^\* Try:/{exit}' "$log" | head -40)
  [ -n "$msg" ] || msg=$(grep -E "^e: |error:|FAILED|Exception" "$log" | head -40)
  [ -n "$msg" ] || msg=$(tail -40 "$log")
  msg=${msg//'%'/'%25'}; msg=${msg//$'\r'/'%0D'}; msg=${msg//$'\n'/'%0A'}
  echo "::error title=gradlew $*::$msg"
fi
exit $status
