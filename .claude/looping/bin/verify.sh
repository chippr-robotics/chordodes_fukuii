#!/bin/sh
# verify.sh — run all required gates for a recipe and emit aggregate sentinel
# Usage: verify.sh <recipe-id> [ledger-dir]
# Reads gate list from recipes/<recipe-id>.loop.md and runs each verify/<gate>.sh.
# Prints every GATE: line then the LOOP: aggregate.
# Exits 0 on ALL_GATES:PASS, nonzero on ALL_GATES:FAIL.

set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOOPING_DIR="$(dirname "$SCRIPT_DIR")"
RECIPE_ID="${1:-}"
LEDGER_DIR="${2:-}"

if [ -z "$RECIPE_ID" ]; then
    printf 'ERROR: usage: verify.sh <recipe-id> [ledger-dir]\n' >&2
    exit 1
fi

RECIPE_FILE="$LOOPING_DIR/recipes/${RECIPE_ID}.loop.md"
VERIFY_DIR="$LOOPING_DIR/verify"

if [ ! -f "$RECIPE_FILE" ]; then
    printf 'ERROR: recipe not found: %s\n' "$RECIPE_FILE" >&2
    exit 1
fi

# Extract gate list
GATES=$(grep '^gates:' "$RECIPE_FILE" | sed 's/gates:[[:space:]]*//' | tr -d '[]' | tr ',' '\n' | tr -d ' ' | grep -v '^$' | grep -v '^NONE$')

# Extract refresh_refs flag
REFRESH=$(grep '^refresh_refs:' "$RECIPE_FILE" | awk '{print $2}')

# Run refresh-refs if required
if [ "$REFRESH" = "true" ]; then
    if [ -n "$LEDGER_DIR" ]; then
        printf '==> Refreshing reference repos...\n'
        "$SCRIPT_DIR/refresh-refs.sh" "$LEDGER_DIR" || {
            printf 'GATE:conformance RESULT:FAIL detail=refresh-refs-failed\n'
            printf 'LOOP:%s ALL_GATES:FAIL failed=[conformance]\n' "$RECIPE_ID"
            exit 1
        }
    else
        printf 'WARN: refresh_refs=true but no ledger-dir provided; skipping refresh\n'
    fi
fi

FAILED_GATES=""
ALL_PASS=1

# Set LOOP_RECIPE_ID for gate scripts that need it
export LOOP_RECIPE_ID="$RECIPE_ID"
export LOOP_RECIPE_FILE="$RECIPE_FILE"

for gate in $GATES; do
    GATE_SCRIPT="$VERIFY_DIR/${gate}.sh"
    if [ ! -f "$GATE_SCRIPT" ]; then
        printf 'GATE:%s RESULT:FAIL detail=gate-script-missing\n' "$gate"
        ALL_PASS=0
        FAILED_GATES="${FAILED_GATES}${gate},"
        continue
    fi

    # Run gate; capture its output; always print it
    GATE_OUTPUT=$("$GATE_SCRIPT" 2>&1) || true
    printf '%s\n' "$GATE_OUTPUT"

    # Check the sentinel line
    RESULT=$(printf '%s' "$GATE_OUTPUT" | grep "^GATE:${gate} RESULT:" | tail -1 | awk -F'RESULT:' '{print $2}' | awk '{print $1}')
    if [ "$RESULT" != "PASS" ]; then
        ALL_PASS=0
        FAILED_GATES="${FAILED_GATES}${gate},"
    fi
done

# Strip trailing comma
FAILED_GATES=$(printf '%s' "$FAILED_GATES" | sed 's/,$//')

if [ "$ALL_PASS" -eq 1 ]; then
    printf 'LOOP:%s ALL_GATES:PASS\n' "$RECIPE_ID"
    exit 0
else
    printf 'LOOP:%s ALL_GATES:FAIL failed=[%s]\n' "$RECIPE_ID" "$FAILED_GATES"
    exit 1
fi
