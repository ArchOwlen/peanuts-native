#!/bin/sh
# Report what upstream (moceure/BananaNative, branch `android`) has changed per component and
# per game since our last sync. Read-only: fetches, never checks out/rebases/resets/stashes.
# See peanuts/docs/UPSTREAM.md section 2.1. "Since" defaults to merge-base(HEAD, target), per
# sync-state.sh -- not the upstream tip (that would make every diff below empty) and not a
# stored marker file (that can go stale).
set -eu
cd "$(git rev-parse --show-toplevel)"
. "$(cd "$(dirname "$0")" && pwd)/sync-state.sh"

target="${2:-upstream/android}"
git fetch -q upstream --tags
git rev-parse -q --verify "$target^{commit}" >/dev/null 2>&1 || { echo "no such ref: $target (git fetch upstream first)"; exit 1; }

since="${1:-$(upstream_state_read "$target")}"
git rev-parse -q --verify "$since^{commit}" >/dev/null 2>&1 || { echo "bad since-ref: $since"; exit 1; }

echo "comparing $since..$target (merge-base default; override with: report.sh <since-ref> [<target>])"
echo

echo "== game profiles (dm/games) =="
changed_games=$(git diff --name-only "$since" "$target" -- dm/games | sed -n 's#^dm/games/\([0-9A-Fa-f]\{16\}\)/.*#\1#p' | sort -u)
if [ -z "$changed_games" ]; then
    echo "  no titleid changes"
else
    n=$(printf '%s\n' "$changed_games" | wc -l | tr -d ' ')
    echo "  $n titleid dirs changed upstream since $(git rev-parse --short "$since")"
    for t in $changed_games; do
        stat=$(git diff --shortstat "$since" "$target" -- "dm/games/$t" | sed 's/^ *//')
        authors=$(git log --format='%an' "$since..$target" -- "dm/games/$t" | sort -u | paste -sd, -)
        local_patch=""
        git log --format='%H' --author=igawa6 HEAD -- "dm/games/$t" | grep -q . && local_patch="  ** we have local keys on this one -- see sync-games.sh before --apply **"
        echo "  $t  $stat  ($authors)$local_patch"
    done
fi
echo

# F3 (CRITIQUE-phase1.md): the Apple chain and the Android/Vulkan chain are NOT independent.
# DMSH_VK_CHAIN covers DMSH_CHAIN (dmsh_chain_vk.h's own header comment), and DMSH_CHAIN hashes
# the Apple-chain sources (dmsh_build.py, dmsh_fetch.py, dmsh_vin.py, dmsh_geometry.py,
# dmsh_facts.py, the xlate sources, dmsh_chain.lock). So a change to ANY dmsh_*/vk_android_* file
# moves DMSH_VK_CHAIN and forces re-translation on the phone -- there is one shader chain here,
# reported as one section, not two.
echo "== shader chain (dmsh_chain.py FILES + vk_android_build.py VK_FILES) =="
echo "  DMSH_VK_CHAIN covers DMSH_CHAIN: any change below forces re-translation (see UPSTREAM.md section 2.3)."
# Use the chain's own, authoritative file list (dmsh_chain.py FILES / vk_android_build.py
# VK_FILES), not a directory-wide git log -- the latter also picks up files that live beside the
# chain but don't feed it (shader_split.py, split_pool.py, ue_ondevice.py are NOT in either list).
chain_files=$(upstream_shader_chain_files)
# shellcheck disable=SC2086
git log --format='  %an %ad %s' --date=short "$since..$target" -- $chain_files 2>/dev/null | sort -u \
    | grep . || echo "  no change"
if [ -f dm/gfx/nvn/dmsh_chain_vk.h ]; then
    ours_chain=$(grep -o 'DMSH_VK_CHAIN "[^"]*"' dm/gfx/nvn/dmsh_chain_vk.h || echo "DMSH_VK_CHAIN unknown")
    theirs_chain=$(git show "$target:dm/gfx/nvn/dmsh_chain_vk.h" 2>/dev/null | grep -o 'DMSH_VK_CHAIN "[^"]*"' || echo "DMSH_VK_CHAIN unknown")
    ours_abi=$(grep -o 'DMSH_VK_ABI "[^"]*"' dm/gfx/nvn/dmsh_chain_vk.h || echo "DMSH_VK_ABI unknown")
    theirs_abi=$(git show "$target:dm/gfx/nvn/dmsh_chain_vk.h" 2>/dev/null | grep -o 'DMSH_VK_ABI "[^"]*"' || echo "DMSH_VK_ABI unknown")
    echo "  our tree:     $ours_chain   $ours_abi"
    echo "  upstream tip: $theirs_chain   $theirs_abi"
    if [ "$ours_chain" != "$theirs_chain" ]; then
        if [ "$ours_abi" = "$theirs_abi" ]; then
            echo "  chain moved, ABI unchanged: old installed libraries keep playing while each device's delta runs."
        else
            echo "  chain AND abi moved: old libraries will not play; a full rebuild is needed, not a delta."
        fi
        echo "  our vk_android_same.tsv rows for the old chain must be re-proven against the new one (byte comparison) before trusting them."
    fi
fi
echo

echo "== gpu driver list (DriverFetch.java REPOS[]) =="
if git diff --quiet "$since" "$target" -- android/app/src/main/java/dev/nxnative/android/DriverFetch.java 2>/dev/null; then
    echo "  no change"
else
    echo "  changed -- run sync-drivers.sh and review each added repo by hand (these are third-party native code sources, not inert metadata)"
fi
echo

echo "== host/app (dm/host, android/, excluding dev.peanuts and android/app/src/peanuts) =="
echo "  host:" "$(git log --oneline "$since..$target" -- dm/host | wc -l | tr -d ' ')" "commits"
echo "  app: " "$(git log --oneline "$since..$target" -- android | grep -v -E 'dev/peanuts/|app/src/peanuts/' | wc -l | tr -d ' ')" "commits"
echo "  run check-peanuts-conflicts.sh to see which PEANUTS-marked hunks are near this churn"
echo

echo "== companion runtime (dm/peanuts/rt vs Eden Duo tag) =="
echo "  run sync-rt.sh -- it compares against a pinned Eden Duo git tag (git show), not the ~/eden working tree, which is shared and may be checked out to anything"
