#!/bin/sh
# warnings.sh — gate: no new @nowarn or @SuppressWarnings added since baseline
# Baseline is the HEAD commit before the loop started.
# Prints GATE:warnings RESULT:PASS or GATE:warnings RESULT:FAIL detail=<reason>
#
# The gate catches the most common ratchet regression: adding a suppression
# annotation to silence a warning rather than fixing the underlying issue.
# It does NOT catch blanket file-level suppressions (those are rare and caught
# by code review / prism gate).

set -eu

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || printf '/media/dev/2tb/dev/fukuii')"
cd "$REPO_ROOT"

# Diff HEAD (current state) vs the last commit
# We look for additions (+lines) containing @nowarn or @SuppressWarnings
NEW_SUPPRESSIONS=$(git diff HEAD -- '*.scala' 2>/dev/null \
    | grep '^+' \
    | grep -v '^+++' \
    | grep -E '@nowarn|@SuppressWarnings' \
    | grep -v '^[[:space:]]*//') || true

if [ -n "$NEW_SUPPRESSIONS" ]; then
    COUNT=$(printf '%s\n' "$NEW_SUPPRESSIONS" | grep -c . 2>/dev/null || printf '?')
    printf 'GATE:warnings RESULT:FAIL detail=new-suppressions-detected:count=%s\n' "$COUNT"
    printf '%s\n' "$NEW_SUPPRESSIONS" | head -10
    exit 1
fi

printf 'GATE:warnings RESULT:PASS\n'
