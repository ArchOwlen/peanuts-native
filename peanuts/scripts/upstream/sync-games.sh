#!/bin/sh
# Per-title report (and, gated, a scoped pull) of a game profile from upstream/android.
#
# The whole-tree rebase (peanuts/scripts/upstream-update.sh) is the only routine update path
# (CRITIQUE-phase1.md UPSTREAM §2 must-fix #3): a game.tsv change often depends on a game.py or
# host change landing in the SAME upstream commit, and game.py silently ignores unknown keys, so
# a profile pulled on its own can look fine and simply not apply. This script is a report by
# default, and --apply only proceeds when every upstream commit touching this titleid's profile
# touches nothing else -- otherwise it refuses and tells you to rebase instead.
#
# When --apply does proceed, it only checks out files we have never locally edited (comparing
# against the merge-base, not a two-dot diff against HEAD) -- this is what keeps our 3-commit
# footprint (mem_map_limit, fps_target, the LA vsync pin) safe. A file we HAVE locally edited is
# never checked out; it's listed instead so you can replay the upstream content by hand.
set -eu
cd "$(git rev-parse --show-toplevel)"
. "$(cd "$(dirname "$0")" && pwd)/sync-state.sh"

titleid="${1:-}"
apply="${2:-}"
[ -n "$titleid" ] || { echo "usage: sync-games.sh <titleid> [--apply]"; exit 1; }
[ -d "dm/games/$titleid" ] || { echo "no such profile: dm/games/$titleid"; exit 1; }

target="upstream/android"
git fetch -q upstream --tags
git rev-parse -q --verify "$target^{commit}" >/dev/null 2>&1 || { echo "no such ref: $target"; exit 1; }
base=$(upstream_merge_base "$target")

echo "== upstream vs merge-base ($(git rev-parse --short "$base")): dm/games/$titleid =="
upstream_changed=$(git diff --name-only "$base" "$target" -- "dm/games/$titleid")
if [ -z "$upstream_changed" ]; then
    echo "  no upstream change since the merge-base"
else
    git diff --stat "$base" "$target" -- "dm/games/$titleid"
fi
echo

echo "== our local (igawa6) commits on this profile =="
our_commits=$(git log --format='%h' --author=igawa6 HEAD -- "dm/games/$titleid")
if [ -n "$our_commits" ]; then
    git log --format='  %h %ad %s' --date=short --author=igawa6 HEAD -- "dm/games/$titleid"
else
    echo "  none"
fi
echo

[ -n "$upstream_changed" ] || { echo "nothing to apply."; exit 0; }

# Does every upstream commit that touches this titleid touch ONLY this titleid's directory?
# If not, a per-title pull is unsafe on its own (it would be missing the other half of a
# commit, e.g. a game.py schema change) -- refuse --apply and point at the full rebase instead.
commits_touching=$(git log --format='%H' "$base..$target" -- "dm/games/$titleid")
scope_dirty=0
for c in $commits_touching; do
    other=$(git diff-tree --no-commit-id --name-only -r "$c" | grep -v -F "dm/games/$titleid/" || true)
    if [ -n "$other" ]; then
        scope_dirty=1
        echo "  ** upstream commit $c also touches files outside dm/games/$titleid/ -- a per-title pull would be partial **"
    fi
done

if [ "$apply" != "--apply" ]; then
    echo "(dry run; pass --apply to attempt a scoped pull of only the files we have not locally edited)"
    exit 0
fi

if [ "$scope_dirty" != 0 ]; then
    echo
    echo "refusing --apply: at least one upstream commit here is not scoped to this titleid."
    echo "run peanuts/scripts/upstream-update.sh (the full rebase) instead."
    exit 1
fi

[ -z "$(git status --porcelain -- "dm/games/$titleid")" ] || { echo "dm/games/$titleid has uncommitted changes; commit or discard first"; exit 1; }

echo
for f in $upstream_changed; do
    if upstream_file_is_unmodified_by_us "$f" "$base"; then
        echo "checking out $f from $target (we have no local edits to it)"
        git checkout "$target" -- "$f"
    else
        echo "SKIPPING $f -- we have local edits since the merge-base (would revert them); replay upstream's content by hand"
    fi
done
echo "applied what was safe. Review the SKIPPING lines above and replay those by hand, then commit."
