#!/usr/bin/env bash
# CI-only: rerun Gradle when the failure is a transient network problem
# (for example Maven Central answering HTTP 429). Artifacts that were already
# downloaded stay in ~/.gradle, so every retry makes progress. Genuine
# compile/test failures are not retried.
set -uo pipefail
max_attempts=5
log="$(mktemp)"
trap 'rm -f "$log"' EXIT
for attempt in $(seq 1 "$max_attempts"); do
  bash gradlew "$@" 2>&1 | tee "$log"
  status=${PIPESTATUS[0]}
  if [ "$status" -eq 0 ]; then
    exit 0
  fi
  if ! grep -qE 'status code (429|50[0-9])|Too Many Requests|Could not (GET|HEAD|resolve|download)|Read timed out|Connection (reset|refused|timed out)|Remote host terminated|Premature EOF|Unexpected end of (ZLIB )?input|UnknownHostException|SSLHandshakeException|Received fatal alert' "$log"; then
    echo "::error::Gradle failed for a non-network reason; not retrying."
    exit "$status"
  fi
  if [ "$attempt" -eq "$max_attempts" ]; then
    break
  fi
  delay=$((attempt * 30))
  echo "::warning::Transient network failure (attempt $attempt/$max_attempts); retrying in ${delay}s."
  sleep "$delay"
done
echo "::error::Gradle still failed after $max_attempts attempts."
exit 1
