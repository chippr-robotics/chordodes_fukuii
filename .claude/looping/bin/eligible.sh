#!/bin/sh
# eligible.sh — check whether a recipe qualifies to run as a loop
# Usage: eligible.sh <recipe-id>
# Prints ELIGIBLE:YES or ELIGIBLE:NO reason=<text> then exits accordingly.

set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOOPING_DIR="$(dirname "$SCRIPT_DIR")"
RECIPE_FILE="$LOOPING_DIR/recipes/${1:-}.loop.md"
REGISTRY="$LOOPING_DIR/registry.yaml"
VERIFY_DIR="$LOOPING_DIR/verify"

fail() {
    printf 'ELIGIBLE:NO reason=%s\n' "$1"
    exit 1
}

if [ -z "${1:-}" ]; then
    fail "no-recipe-id-provided"
fi

# 1. Recipe file must exist
if [ ! -f "$RECIPE_FILE" ]; then
    fail "recipe-not-found:$RECIPE_FILE"
fi

# 2. registry.yaml must exist
if [ ! -f "$REGISTRY" ]; then
    fail "registry-not-found:$REGISTRY"
fi

# 3. Extract gates from recipe and verify each gate script exists
GATES=$(grep '^gates:' "$RECIPE_FILE" | sed 's/gates:[[:space:]]*//' | tr -d '[]' | tr ',' '\n' | tr -d ' ')
for gate in $GATES; do
    if [ "$gate" = "NONE" ]; then
        continue
    fi
    GATE_SCRIPT="$VERIFY_DIR/${gate}.sh"
    if [ ! -f "$GATE_SCRIPT" ]; then
        fail "gate-script-missing:$GATE_SCRIPT"
    fi
done

# 4. If refresh_refs: true, verify at least one ref repo is reachable
REFRESH=$(grep '^refresh_refs:' "$RECIPE_FILE" | awk '{print $2}')
if [ "$REFRESH" = "true" ]; then
    # Check that the ECIPs repo exists as a basic proxy
    ECIP_PATH="$(dirname "$LOOPING_DIR")/../.."
    # Use the first client path from registry as a probe
    if ! git -C /media/dev/2tb/dev/reference-clients-evm/besu rev-parse HEAD >/dev/null 2>&1; then
        fail "ref-repos-unreachable:reference-clients-evm/besu"
    fi
fi

printf 'ELIGIBLE:YES\n'
exit 0
