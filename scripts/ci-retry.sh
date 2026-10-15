#!/usr/bin/env bash
#
# Run a CI install command, retrying transient package-mirror failures (Homebrew, apt,
# Chocolatey answering 5xx) with a backoff before failing the step.
#
# Usage: scripts/ci-retry.sh <description> <command> [args...]
#        CI_RETRY_ATTEMPTS overrides the attempt count (default 4).

set -uo pipefail

if [ "$#" -lt 2 ]; then
  echo "usage: $0 <description> <command> [args...]" >&2
  exit 2
fi

description=$1
shift
max_attempts=${CI_RETRY_ATTEMPTS:-4}

for ((attempt = 1; ; attempt++)); do
  "$@"
  status=$?
  if [ "$status" -eq 0 ]; then exit 0; fi
  if [ "$attempt" -ge "$max_attempts" ]; then
    echo "::error::$description failed after $max_attempts attempts (exit $status)"
    exit "$status"
  fi
  delay=$((8 + 12 * attempt))
  echo "::warning::$description failed (exit $status, attempt $attempt/$max_attempts); retrying in ${delay}s"
  sleep "$delay"
done
