#!/bin/sh
# format.sh — gate: scalafmt check passes (no unformatted files)
# Uses sbt scalafmtCheck (verify without writing).
# Prints GATE:format RESULT:PASS or GATE:format RESULT:FAIL detail=<reason>
#
# Note: sbt task name confirmed as scalafmtCheck in build.sbt; if the build
# renames it, update here. See open assumption in DISCOVERY.md.

set -eu

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || printf '/media/dev/2tb/dev/fukuii')"
cd "$REPO_ROOT"

OUTPUT=$(sbt scalafmtCheck 2>&1) || {
    UNFORMATTED=$(printf '%s' "$OUTPUT" | grep -E 'not formatted|error' | head -5 || printf 'see output')
    printf 'GATE:format RESULT:FAIL detail=scalafmt-violations-detected\n'
    printf '%s\n' "$UNFORMATTED"
    exit 1
}

printf 'GATE:format RESULT:PASS\n'
