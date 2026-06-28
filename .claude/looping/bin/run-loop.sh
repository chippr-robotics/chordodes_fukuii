#!/bin/sh
# run-loop.sh — orchestrator entry point: drives one recipe for one iteration
# Usage: run-loop.sh <recipe-id> [ledger-dir]
# If ledger-dir is omitted, a new one is created under state/<id>-<timestamp>/.
#
# This script handles the infrastructure (ledger init, eligibility, budget,
# refresh). The DISCOVER->PLAN->EXECUTE->VERIFY cycle is driven by the Claude
# session reading the ledger and invoking maker/checker agents in sequence.
# This script is the harness; Claude is the orchestrator.

set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOOPING_DIR="$(dirname "$SCRIPT_DIR")"
RECIPE_ID="${1:-}"

if [ -z "$RECIPE_ID" ]; then
    printf 'ERROR: usage: run-loop.sh <recipe-id> [ledger-dir]\n' >&2
    exit 1
fi

RECIPE_FILE="$LOOPING_DIR/recipes/${RECIPE_ID}.loop.md"
if [ ! -f "$RECIPE_FILE" ]; then
    printf 'ERROR: recipe not found: %s\n' "$RECIPE_FILE" >&2
    exit 1
fi

# Create or reuse ledger dir
if [ -n "${2:-}" ]; then
    LEDGER_DIR="$2"
    printf 'Resuming run: %s\n' "$LEDGER_DIR"
else
    TIMESTAMP=$(date -u '+%Y%m%dT%H%M%SZ')
    LEDGER_DIR="$LOOPING_DIR/state/${RECIPE_ID}-${TIMESTAMP}"
    mkdir -p "$LEDGER_DIR"
    date +%s > "$LEDGER_DIR/start_time"
    printf '[]' > "$LEDGER_DIR/attempts.json"
    printf '# Ledger: %s\n' "$RECIPE_ID" > "$LEDGER_DIR/ledger.md"
    printf '# Started: %s\n\n' "$TIMESTAMP" >> "$LEDGER_DIR/ledger.md"
    printf 'New run: %s\n' "$LEDGER_DIR"
fi

# Eligibility check
printf '\n==> Checking eligibility...\n'
"$SCRIPT_DIR/eligible.sh" "$RECIPE_ID"

# Budget check
printf '\n==> Checking budget...\n'
"$SCRIPT_DIR/budget-check.sh" "$LEDGER_DIR" "$RECIPE_FILE"

# Print state for the orchestrating session to read
printf '\n==> Loop state\n'
printf 'Recipe:     %s\n' "$RECIPE_ID"
printf 'Ledger:     %s\n' "$LEDGER_DIR"
printf 'Recipe:     %s\n' "$RECIPE_FILE"

ITER=$(grep -c '^## Iteration' "$LEDGER_DIR/ledger.md" 2>/dev/null) || ITER=0
printf 'Iteration:  %d\n' "$ITER"

printf '\n==> Current ledger tail\n'
tail -30 "$LEDGER_DIR/ledger.md" 2>/dev/null || printf '(empty)\n'

printf '\n==> Instructions for orchestrator\n'
cat <<'INSTRUCTIONS'
The loop harness has verified eligibility and budget. The orchestrating session
should now:

1. DISCOVER — read the ledger, read the current gate state, identify the delta
   between current code state and the recipe goal.
2. PLAN — state the single highest-impact next step in the ledger.
3. EXECUTE — invoke the maker agent to make the smallest change that moves the
   gate. Append the change summary to the ledger.
4. VERIFY — run: .claude/looping/bin/verify.sh <recipe-id> <ledger-dir>
   Let its output appear in the transcript. Do NOT assert gates pass yourself.
5. ITERATE — if ALL_GATES:FAIL, invoke the checker agent for CONFIRM:ITERATE.
   Record what failed and the next delta in the ledger. Run budget-check.sh again.
   If ALL_GATES:PASS, invoke the checker agent for CONFIRM:DONE.

Ralph guard: only the checker may issue CONFIRM:DONE.
INSTRUCTIONS

printf '\nLedger dir for this run: %s\n' "$LEDGER_DIR"
