#!/bin/sh
# tests.sh — gate: run test tier or targeted suite; fail on any failure or count regression
# Controlled by LOOP_TEST_TARGET env var:
#   essential          -> fukuii-test essential (Tier 1, ~24 min)
#   standard           -> fukuii-test standard (Tier 2, ~30 min)
#   only <Spec>...     -> fukuii-test only <Spec>...
# Defaults to essential if not set.
#
# Prints GATE:tests RESULT:PASS or GATE:tests RESULT:FAIL detail=<reason>

set -eu

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || printf '/media/dev/2tb/dev/fukuii')"
TEST_SCRIPT="$REPO_ROOT/.local/scripts/fukuii-test"

if [ ! -x "$TEST_SCRIPT" ]; then
    printf 'GATE:tests RESULT:FAIL detail=fukuii-test-script-not-found:%s\n' "$TEST_SCRIPT"
    exit 1
fi

TARGET="${LOOP_TEST_TARGET:-essential}"

# SyncTest exclusion guard: reject if caller tries to run a known SyncTest spec
for blocked in RegularSyncSpec FastSyncSpec SyncControllerSpec BlockchainHostActorSpec SyncStateDownloaderStateSpec; do
    if printf '%s' "$TARGET" | grep -q "$blocked"; then
        printf 'GATE:tests RESULT:FAIL detail=SyncTest-excluded:%s-stalls-under-CI-load\n' "$blocked"
        exit 1
    fi
done

case "$TARGET" in
    essential|standard)
        OUTPUT=$("$TEST_SCRIPT" "$TARGET" 2>&1) || {
            FAILURES=$(printf '%s' "$OUTPUT" | grep -E 'FAILED|failures' | tail -3)
            printf 'GATE:tests RESULT:FAIL detail=test-failures:see-output\n'
            printf '%s\n' "$FAILURES"
            exit 1
        }
        ;;
    only\ *)
        SPECS=$(printf '%s' "$TARGET" | sed 's/^only //')
        OUTPUT=$("$TEST_SCRIPT" only $SPECS 2>&1) || {
            printf 'GATE:tests RESULT:FAIL detail=targeted-test-failure:suite=%s\n' "$SPECS"
            exit 1
        }
        ;;
    *)
        printf 'GATE:tests RESULT:FAIL detail=unknown-target:%s\n' "$TARGET"
        exit 1
        ;;
esac

# Test count regression check for essential tier
if [ "$TARGET" = "essential" ]; then
    COUNT=$(printf '%s' "$OUTPUT" | grep -oE '[0-9]+ test' | tail -1 | awk '{print $1}' || printf '0')
    BASELINE=3595
    if [ -n "$COUNT" ] && [ "$COUNT" -lt "$BASELINE" ]; then
        printf 'GATE:tests RESULT:FAIL detail=test-count-regression:expected>=%d:got=%s\n' "$BASELINE" "$COUNT"
        exit 1
    fi
fi

printf 'GATE:tests RESULT:PASS\n'
