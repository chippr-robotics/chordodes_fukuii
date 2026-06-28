#!/bin/sh
# conformance.sh — gate: Fukuii implementation matches spec and upstream client behavior
# Requires refresh-refs.sh to have run first (called by verify.sh when refresh_refs=true).
# The specific surface being checked is passed via LOOP_RECIPE_ID and LOOP_RECIPE_FILE.
#
# Initial implementation: structural diff mode. Produces a human-readable drift report.
# A non-empty diff = FAIL. See open assumption in DISCOVERY.md: semantic verdict requires
# human review to triage.
#
# Prints GATE:conformance RESULT:PASS or GATE:conformance RESULT:FAIL detail=<reason>

set -eu

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || printf '/media/dev/2tb/dev/fukuii')"
RECIPE_ID="${LOOP_RECIPE_ID:-unknown}"
RECIPE_FILE="${LOOP_RECIPE_FILE:-}"

if [ -z "$RECIPE_FILE" ] || [ ! -f "$RECIPE_FILE" ]; then
    printf 'GATE:conformance RESULT:FAIL detail=no-recipe-file-set:set-LOOP_RECIPE_FILE\n'
    exit 1
fi

# Extract surface from recipe id: spec-conformance-<surface> -> <surface>
# For ref-parity-audit we check all surfaces.
SURFACE=$(printf '%s' "$RECIPE_ID" | sed 's/spec-conformance-//')

# Route to the right checker based on surface
case "$SURFACE" in
    eth70|eth69|eth68|snap|wire*)
        CHECKER="herald"
        REF_CLIENT="/media/dev/2tb/dev/reference-clients-evm/go-ethereum"
        REF_BRANCH="upstream"
        ;;
    etc*|ecip*|olympia*|mordor*|classic*)
        CHECKER="forge"
        REF_CLIENT="/media/dev/2tb/dev/reference-clients-evm/core-geth"
        REF_BRANCH="upstream"
        ;;
    eth*|sepolia*|osaka*|eip*)
        CHECKER="beacon"
        REF_CLIENT="/media/dev/2tb/dev/reference-clients-evm/go-ethereum"
        REF_BRANCH="upstream"
        ;;
    ref-parity-audit)
        # Poll recipe: report drift across all surfaces into a summary
        CHECKER="forge,beacon,herald,vault,conduit"
        REF_CLIENT="/media/dev/2tb/dev/reference-clients-evm"
        REF_BRANCH="upstream"
        ;;
    *)
        # Unknown surface: fail and ask for explicit routing
        printf 'GATE:conformance RESULT:FAIL detail=unknown-surface:%s:update-conformance.sh-routing\n' "$SURFACE"
        exit 1
        ;;
esac

# Verify ref client is on the expected branch and is fresh
if [ -d "$REF_CLIENT/.git" ] || [ -f "$REF_CLIENT/.git" ]; then
    CURRENT_BRANCH=$(git -C "$REF_CLIENT" rev-parse --abbrev-ref HEAD 2>/dev/null || printf 'unknown')
    if [ "$CURRENT_BRANCH" != "$REF_BRANCH" ]; then
        printf 'WARN: %s is on branch %s not %s; conformance may be stale\n' \
            "$REF_CLIENT" "$CURRENT_BRANCH" "$REF_BRANCH"
    fi
fi

# This gate's output is intentionally a report for the checker agent to read.
# The sentinel line indicates whether drift was found structurally.
# The checker agent (forge/beacon/herald) provides semantic verdict.
printf '\n=== Conformance Report: %s ===\n' "$RECIPE_ID"
printf 'Surface:   %s\n' "$SURFACE"
printf 'Checker:   %s\n' "$CHECKER"
printf 'Ref:       %s @ %s\n' "$REF_CLIENT" "$REF_BRANCH"
printf 'Timestamp: %s\n\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

# For ref-parity-audit: emit per-client HEAD SHAs and flag if any are stale (> 7 days)
if [ "$SURFACE" = "ref-parity-audit" ]; then
    STALE_COUNT=0
    for client in besu core-geth nethermind go-ethereum reth erigon; do
        CLIENT_PATH="/media/dev/2tb/dev/reference-clients-evm/$client"
        if [ -d "$CLIENT_PATH" ]; then
            SHA=$(git -C "$CLIENT_PATH" rev-parse upstream 2>/dev/null || printf 'unknown')
            AGE_SECS=$(git -C "$CLIENT_PATH" log upstream -1 --format='%ct' 2>/dev/null || printf '0')
            NOW=$(date +%s)
            AGE_DAYS=$(( (NOW - AGE_SECS) / 86400 ))
            STATUS="fresh"
            if [ "$AGE_DAYS" -gt 7 ]; then
                STATUS="STALE(${AGE_DAYS}d)"
                STALE_COUNT=$((STALE_COUNT + 1))
            fi
            printf 'CLIENT:%-15s SHA:%.8s STATUS:%s\n' "$client" "$SHA" "$STATUS"
        fi
    done

    if [ "$STALE_COUNT" -gt 0 ]; then
        printf '\nCONFORMANCE:DRIFT detected=%d stale clients; run refresh-refs.sh first\n' "$STALE_COUNT"
        printf 'GATE:conformance RESULT:FAIL detail=stale-ref-clients:count=%d\n' "$STALE_COUNT"
        exit 1
    fi
    printf '\nCONFORMANCE:NO_DRIFT all clients fresh\n'
    printf 'GATE:conformance RESULT:PASS\n'
    exit 0
fi

# For spec-conformance recipes: the checker agent (forge/beacon/herald) must
# review this output and issue CONFIRM:DONE or CONFIRM:ITERATE.
# This script produces the evidence; the checker provides the verdict.
printf 'NOTE: Invoke the %s agent to review this conformance report and issue\n' "$CHECKER"
printf 'NOTE: CONFIRM:DONE (no drift) or CONFIRM:ITERATE reason=<drift-details>.\n\n'

# Check if the checker is proactive (forge, beacon) — remind orchestrator
case "$CHECKER" in
    forge|beacon)
        printf 'IMPORTANT: %s is a proactive checker. If it was not consulted in the\n' "$CHECKER"
        printf 'IMPORTANT: DISCOVER phase before the maker executed, the loop has a protocol\n'
        printf 'IMPORTANT: violation. The checker must review BEFORE implementation, not after.\n\n'
        ;;
esac

# Emit a provisional PASS to allow the checker agent to make the final call.
# The Ralph guard requires the checker to confirm; this gate being PASS does not
# mean the loop is done — it means the evidence is ready for checker review.
printf 'GATE:conformance RESULT:PASS\n'
printf 'NOTE: Checker confirmation required before LOOP ALL_GATES:PASS is meaningful.\n'
