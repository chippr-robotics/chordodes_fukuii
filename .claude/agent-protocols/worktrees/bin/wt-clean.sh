#!/bin/sh
# wt-clean.sh — prune merged/stale worktrees under .claude/worktrees/
# Usage: wt-clean.sh [--dry-run]
#
# Removes worktrees whose wt/* branch has been merged into the current HEAD branch.
# Stale entries (directory gone but git still tracks) are pruned unconditionally.
# Use --dry-run to preview without removing anything.

set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
DRY_RUN=0
if [ "${1:-}" = "--dry-run" ]; then DRY_RUN=1; fi

MAIN_BRANCH="$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD)"

printf '==> Pruning stale worktree entries\n'
if [ "$DRY_RUN" -eq 1 ]; then
    git -C "$REPO_ROOT" worktree prune --dry-run
else
    git -C "$REPO_ROOT" worktree prune
fi

printf '\n==> Checking .claude/worktrees/ for merged branches\n'

REMOVED=0
while IFS= read -r line; do
    path="${line%% *}"
    if printf '%s' "$path" | grep -q '\.claude/worktrees/'; then
        branch=$(git -C "$path" rev-parse --abbrev-ref HEAD 2>/dev/null) || continue
        if git -C "$REPO_ROOT" branch --merged "$MAIN_BRANCH" 2>/dev/null | grep -q "^[* ]*${branch}$"; then
            printf 'MERGED: %s  (%s)\n' "$path" "$branch"
            if [ "$DRY_RUN" -eq 1 ]; then
                printf '  (dry-run — would remove worktree and delete branch)\n'
            else
                git -C "$REPO_ROOT" worktree remove "$path" --force
                git -C "$REPO_ROOT" branch -d "$branch"
                printf '  Removed.\n'
                REMOVED=$((REMOVED + 1))
            fi
        else
            printf 'ACTIVE: %s  (%s — not yet merged into %s)\n' "$path" "$branch" "$MAIN_BRANCH"
        fi
    fi
done << EOF
$(git -C "$REPO_ROOT" worktree list)
EOF

if [ "$DRY_RUN" -eq 0 ]; then
    printf '\n==> Done. Removed %d merged worktree(s).\n' "$REMOVED"
else
    printf '\n==> Dry run complete. Pass without --dry-run to apply.\n'
fi
