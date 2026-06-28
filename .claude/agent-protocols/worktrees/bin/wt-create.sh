#!/bin/sh
# wt-create.sh — create a named worktree under .claude/worktrees/
# Usage: wt-create.sh <name> [base-branch]
#
# Creates: <repo-root>/.claude/worktrees/<name> on branch wt/<name>
# base-branch defaults to the current HEAD branch if omitted.
#
# Example:
#   .claude/agent-protocols/worktrees/bin/wt-create.sh 7c-sprint scala3-cleanup-june
#   .claude/agent-protocols/worktrees/bin/wt-create.sh 8b-h3

set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
NAME="${1:-}"

if [ -z "$NAME" ]; then
    printf 'ERROR: usage: wt-create.sh <name> [base-branch]\n' >&2
    exit 1
fi

BRANCH="wt/${NAME}"
WORKTREE_DIR="${REPO_ROOT}/.claude/worktrees/${NAME}"

# Determine base branch
if [ -n "${2:-}" ]; then
    BASE="$2"
else
    BASE="$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD)"
fi

# Guard: worktree already exists
if git -C "$REPO_ROOT" worktree list | grep -q "${WORKTREE_DIR}"; then
    printf 'ERROR: worktree already exists: %s\n' "$WORKTREE_DIR" >&2
    exit 1
fi

# Guard: branch already exists
if git -C "$REPO_ROOT" rev-parse --verify "$BRANCH" >/dev/null 2>&1; then
    printf 'ERROR: branch already exists: %s\n' "$BRANCH" >&2
    printf '  To resume an existing worktree, cd into %s directly.\n' "$WORKTREE_DIR" >&2
    exit 1
fi

git -C "$REPO_ROOT" worktree add "$WORKTREE_DIR" -b "$BRANCH" "$BASE"

printf '\n==> Worktree ready\n'
printf 'Path:   %s\n' "$WORKTREE_DIR"
printf 'Branch: %s (based on %s)\n' "$BRANCH" "$BASE"
printf '\nNext:\n'
printf '  cd %s\n' "$WORKTREE_DIR"
