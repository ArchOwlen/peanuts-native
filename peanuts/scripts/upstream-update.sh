#!/bin/sh
# Rebase the `peanuts` overlay onto a Banana Native release (default: latest upstream/android), then verify the hooks.
set -eu
cd "$(git rev-parse --show-toplevel)"
target="${1:-upstream/android}"
branch="$(git branch --show-current)"

git fetch -q upstream --tags
git rev-parse -q --verify "$target^{commit}" >/dev/null || { echo "no such ref: $target"; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "working tree not clean; commit first"; exit 1; }

echo "rebasing $branch onto $target ($(git rev-parse --short "$target"))"
git rebase -q --rebase-merges "$target" || { echo "rebase stopped: resolve, 'git rebase --continue', then re-run"; exit 1; }   # keeps merge commits: other branches base on them

# Every upstream file the overlay touches must still carry a PEANUTS marker, or a hook was lost in the rebase.
fail=0
for f in $(git diff --name-only "$target" "$branch" | grep -v -E '^(peanuts/|dm/peanuts/|android/app/src/main/java/dev/peanuts/|PEANUTS\.md)'); do
    grep -q PEANUTS "$f" || { echo "hook marker missing in $f"; fail=1; }
done
[ "$fail" = 0 ] || exit 1

echo "overlay commits on top of $target:"
git log --oneline "$target".."$branch"
