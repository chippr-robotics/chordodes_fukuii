#!/bin/sh
# compile.sh — gate: sbt compile-all exits 0
# Prints GATE:compile RESULT:PASS or GATE:compile RESULT:FAIL detail=<reason>

set -eu

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || printf '/media/dev/2tb/dev/fukuii')"
cd "$REPO_ROOT"

OUTPUT=$(sbt compile-all 2>&1) || {
    ERRORS=$(printf '%s' "$OUTPUT" | grep -c '\[error\]' 2>/dev/null || printf 'unknown')
    printf 'GATE:compile RESULT:FAIL detail=sbt-compile-all-failed:errors=%s\n' "$ERRORS"
    exit 1
}

printf 'GATE:compile RESULT:PASS\n'
