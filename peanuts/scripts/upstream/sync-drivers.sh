#!/bin/sh
# Diff (and, gated, pull) the GPU driver list (DriverFetch.java REPOS[]) from upstream/android.
#
# This is NOT "no-risk metadata" (CRITIQUE-phase1.md UPSTREAM §2 must-fix #7): every entry in
# REPOS[] is a URL the app will download and run native code from (a Turnip/Adreno driver). A
# new entry is a new third-party code source, not an inert label change. This script always
# prints the diff and never auto-applies; --apply only stages the merge-base-safe checkout (we
# have 0 local commits on this file, confirmed below) and still requires you to have read the
# added/removed lines it just printed.
set -eu
cd "$(git rev-parse --show-toplevel)"
. "$(cd "$(dirname "$0")" && pwd)/sync-state.sh"

f="android/app/src/main/java/dev/nxnative/android/DriverFetch.java"
apply="${1:-}"
target="upstream/android"

git fetch -q upstream --tags
git rev-parse -q --verify "$target^{commit}" >/dev/null 2>&1 || { echo "no such ref: $target"; exit 1; }
base=$(upstream_merge_base "$target")

if git diff --quiet "$base" "$target" -- "$f"; then
    echo "driver list: no change upstream since $(git rev-parse --short "$base")"
    exit 0
fi

echo "== REPOS[] diff (merge-base $(git rev-parse --short "$base") vs $target) =="
git diff "$base" "$target" -- "$f" | grep -E '^[+-]\s*\{"' || git diff --stat "$base" "$target" -- "$f"
echo
echo "** review every added repo above by hand before trusting it: each one is a source of **"
echo "** native code the app will download and run on the GPU driver path. **"
echo

local_commits=$(git log --author=igawa6 --format='%h' HEAD -- "$f")
if [ -n "$local_commits" ]; then
    echo "** unexpected: local (igawa6) commits exist on $f -- do not blind-checkout, review manually **"
    exit 1
fi

if [ "$apply" = "--apply" ]; then
    if ! upstream_file_is_unmodified_by_us "$f" "$base"; then
        echo "we have local edits to $f since the merge-base; refusing to overwrite them"
        exit 1
    fi
    [ -z "$(git status --porcelain -- "$f")" ] || { echo "$f has uncommitted changes; commit first"; exit 1; }
    git checkout "$target" -- "$f"
    echo "applied."
else
    echo "(dry run; pass --apply only after you have reviewed each added repo above)"
fi
