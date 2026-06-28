#!/bin/sh
# wt-list.sh — list active worktrees, highlighting .claude/worktrees/ entries
# Usage: wt-list.sh

set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"

printf '==> All worktrees\n'
git -C "$REPO_ROOT" worktree list

printf '\n==> .claude/worktrees/ entries\n'

FOUND=0
while IFS= read -r line; do
    path="${line%% *}"
    if printf '%s' "$path" | grep -q '\.claude/worktrees/'; then
        FOUND=1
        printf '%s\n' "$line"
        # Show last commit on this worktree's branch
        branch=$(git -C "$path" rev-parse --abbrev-ref HEAD 2>/dev/null || printf '(detached)')
        last=$(git -C "$path" log -1 --oneline 2>/dev/null || printf '(no commits)')
        printf '   branch: %s\n' "$branch"
        printf '   last:   %s\n' "$last"
        # Show merge status
        main_branch=$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD)
        if git -C "$REPO_ROOT" branch --merged "$main_branch" | grep -q "$branch"; then
            printf '   status: MERGED into %s\n' "$main_branch"
        else
            printf '   status: NOT YET MERGED into %s\n' "$main_branch"
        fi
        printf '\n'
    fi
done << EOF
$(git -C "$REPO_ROOT" worktree list)
EOF

if [ "$FOUND" -eq 0 ]; then
    printf '  (none)\n'
fi
