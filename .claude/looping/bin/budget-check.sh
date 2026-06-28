#!/bin/sh
# budget-check.sh — enforce loop budget caps
# Usage: budget-check.sh <ledger-dir> <recipe-file>
# Reads budget.* from recipe-file and state from ledger-dir.
# Exits nonzero with BUDGET:EXCEEDED reason=<text> if any cap is hit.
# Exits 0 with BUDGET:OK if all caps are clear.

set -eu

LEDGER_DIR="${1:-}"
RECIPE_FILE="${2:-}"

if [ -z "$LEDGER_DIR" ] || [ -z "$RECIPE_FILE" ]; then
    printf 'ERROR: usage: budget-check.sh <ledger-dir> <recipe-file>\n' >&2
    exit 1
fi

fail() {
    printf 'BUDGET:EXCEEDED reason=%s\n' "$1"
    exit 1
}

# Extract budget values from recipe YAML (simple grep; assumes one value per line)
field() {
    grep "^  $1:" "$RECIPE_FILE" 2>/dev/null | awk '{print $2}' | tr -d "'\"" | head -1
}

MAX_ITER=$(field max_iterations)
MAX_WALLCLOCK_RAW=$(field max_wallclock)
MIN_ACCEPT=$(field min_accept_rate)

# Parse wallclock limit to seconds
parse_duration() {
    _raw="$1"
    _num=$(printf '%s' "$_raw" | tr -d 'mhsMHS')
    _unit=$(printf '%s' "$_raw" | tr -d '0123456789')
    case "$_unit" in
        m|M) printf '%d' "$((_num * 60))" ;;
        h|H) printf '%d' "$((_num * 3600))" ;;
        s|S) printf '%d' "$_num" ;;
        *)   printf '%d' "$((_num * 60))" ;;
    esac
}

MAX_WALLCLOCK_SECS=5400
if [ -n "$MAX_WALLCLOCK_RAW" ]; then
    MAX_WALLCLOCK_SECS=$(parse_duration "$MAX_WALLCLOCK_RAW")
fi

# Count iterations from ledger (grep -c returns exit 1 on 0 matches in POSIX sh)
ITERATION_COUNT=0
if [ -f "$LEDGER_DIR/ledger.md" ]; then
    _count=$(grep -c '^## Iteration' "$LEDGER_DIR/ledger.md" 2>/dev/null) || _count=0
    ITERATION_COUNT=$_count
fi

# Check iteration cap
if [ -n "$MAX_ITER" ] && [ "$ITERATION_COUNT" -ge "$MAX_ITER" ]; then
    fail "max_iterations:${MAX_ITER}:reached_at:${ITERATION_COUNT}"
fi

# Check wallclock cap
ELAPSED=0
if [ -f "$LEDGER_DIR/start_time" ]; then
    _start=$(cat "$LEDGER_DIR/start_time")
    _now=$(date +%s)
    ELAPSED=$((_now - _start))
    if [ "$ELAPSED" -ge "$MAX_WALLCLOCK_SECS" ]; then
        fail "max_wallclock:${MAX_WALLCLOCK_RAW}:elapsed:${ELAPSED}s"
    fi
fi

# Check accept rate (only after 3+ iterations to avoid division by zero)
if [ -n "$MIN_ACCEPT" ] && [ -f "$LEDGER_DIR/attempts.json" ] && [ "$ITERATION_COUNT" -ge 3 ]; then
    _accepted=$(grep -c '"outcome": "accepted"' "$LEDGER_DIR/attempts.json" 2>/dev/null) || _accepted=0
    # Integer arithmetic: multiply by 100 to avoid floats
    _rate_pct=$((_accepted * 100 / ITERATION_COUNT))
    _min_pct=$(printf '%s' "$MIN_ACCEPT" | awk '{printf "%d", $1 * 100}')
    if [ "$_rate_pct" -lt "$_min_pct" ]; then
        fail "min_accept_rate:${MIN_ACCEPT}:current:${_accepted}/${ITERATION_COUNT}"
    fi
fi

_remaining=$((MAX_WALLCLOCK_SECS - ELAPSED))
printf 'BUDGET:OK iterations=%d wallclock_remaining=%ds\n' "$ITERATION_COUNT" "$_remaining"
