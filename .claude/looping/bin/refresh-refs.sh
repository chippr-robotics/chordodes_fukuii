#!/bin/sh
# refresh-refs.sh — pull upstream on all reference repos and record SHAs
# Usage: refresh-refs.sh <ledger-dir>
# Writes ref_shas.md into <ledger-dir>. Aborts if any repo has local changes
# or cannot fast-forward. Never touches main branches.

set -eu

LEDGER_DIR="${1:-}"
if [ -z "$LEDGER_DIR" ]; then
    printf 'ERROR: usage: refresh-refs.sh <ledger-dir>\n' >&2
    exit 1
fi

SHA_FILE="$LEDGER_DIR/ref_shas.md"
printf '# Reference Repo SHAs\n' > "$SHA_FILE"
printf '# Written by refresh-refs.sh at %s\n\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" >> "$SHA_FILE"

FAILED=0

refresh_repo() {
    local path="$1"
    local remote="$2"
    local branch="$3"

    if [ ! -d "$path/.git" ] && [ ! -f "$path/.git" ]; then
        printf 'WARN: skipping %s (not a git repo)\n' "$path"
        return 0
    fi

    # Abort if branch has local modifications
    if ! git -C "$path" diff --quiet "$branch" 2>/dev/null; then
        printf 'ERROR: %s branch %s has local changes; aborting refresh\n' "$path" "$branch" >&2
        FAILED=1
        return 1
    fi

    # Fetch upstream remote
    printf 'Fetching %s %s/%s ...\n' "$path" "$remote" "$branch"
    if ! git -C "$path" fetch "$remote" "$branch" --quiet 2>&1; then
        printf 'ERROR: fetch failed for %s %s/%s\n' "$path" "$remote" "$branch" >&2
        FAILED=1
        return 1
    fi

    # Fast-forward only — never merge, never rebase
    if ! git -C "$path" merge --ff-only "$remote/$branch" --quiet 2>&1; then
        printf 'ERROR: cannot fast-forward %s branch %s to %s/%s\n' "$path" "$branch" "$remote" "$branch" >&2
        printf 'ERROR: the branch has diverged; do not verify against stale state\n' >&2
        FAILED=1
        return 1
    fi

    # Record resolved SHA
    SHA=$(git -C "$path" rev-parse HEAD)
    printf 'REPO:%s BRANCH:%s SHA:%s\n' "$path" "$branch" "$SHA" >> "$SHA_FILE"
    printf 'OK: %s -> %s\n' "$path" "$SHA"
}

# Spec repos (relative paths resolved from repo root)
REPO_ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel 2>/dev/null || printf '/media/dev/2tb/dev/fukuii')"

refresh_repo "$REPO_ROOT/.claude/repo-references/ECIPs"   upstream master
refresh_repo "$REPO_ROOT/.claude/repo-references/EIPs"    origin   master
refresh_repo "$REPO_ROOT/.claude/repo-references/hive"    upstream upstream
refresh_repo "$REPO_ROOT/.claude/repo-references/pekko"   upstream upstream
refresh_repo "$REPO_ROOT/.claude/repo-references/scala3"  upstream upstream

# Reference EVM clients
# Only the `upstream` branch is updated here.
# main branches (ETC overlays for besu/core-geth/nethermind) are never touched.
for client in besu core-geth nethermind go-ethereum reth erigon; do
    CLIENT_PATH="/media/dev/2tb/dev/reference-clients-evm/$client"
    if [ -d "$CLIENT_PATH" ]; then
        refresh_repo "$CLIENT_PATH" upstream upstream
    else
        printf 'WARN: skipping %s (not checked out)\n' "$CLIENT_PATH"
    fi
done

if [ "$FAILED" -eq 1 ]; then
    printf '\nERROR: one or more repos failed to refresh; do not run conformance gate\n' >&2
    exit 1
fi

printf '\nAll reference repos refreshed. SHAs written to %s\n' "$SHA_FILE"
