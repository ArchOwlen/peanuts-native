#!/bin/sh
# Report (never apply) on the shader-translation tool chain vs upstream/android.
#
# CRITIQUE-phase2a.md D-2: this script is now **report-only**. Three problems with the earlier
# --apply path:
#   (a) its "unmodified by us" check (git diff --quiet base HEAD) ignores the working tree, so a
#       checkout could clobber an uncommitted edit -- sync-games.sh and sync-drivers.sh guard this
#       with a porcelain check; this script had none.
#   (b) the changed-file list was a hand-picked subset of what actually feeds the chain digest
#       (missing dmsh_atomic.py, dmsh_pull.py/.metal, dmsh_alpha.py, dmsh_logic.py, dmsh_mesh.py,
#       dmsh_ampere.py, nvn/dmsh_abi.h, dmsh_abi.py, ios_metal.py, and the vendored Ryujinx .cs
#       sources) -- "no change" from a partial list can be flat wrong.
#   (c) even a complete, correct file-level pull of translator sources is unsafe applied alone: a
#       new Program.cs or Ryujinx source needs a rebuilt libdmsh_xlate.so stage, or Gradle's
#       nxPackXlateCheck fails on the mismatch. The only safe pull path for this chain is the
#       whole-tree rebase (peanuts/scripts/upstream-update.sh).
#
# So: the decisive signal is DMSH_VK_CHAIN / DMSH_VK_ABI themselves (dm/gfx/nvn/dmsh_chain_vk.h),
# compared directly between our tree and upstream's, exactly as report.sh already does -- not a
# hand-kept file list. The full file list (imported read-only from dmsh_chain.py's own FILES and
# vk_android_build.py's own VK_FILES, never executed beyond module-level assignment) is used only
# to list *which* commits are responsible, for a human's information.
set -eu
cd "$(git rev-parse --show-toplevel)"
. "$(cd "$(dirname "$0")" && pwd)/sync-state.sh"

target="upstream/android"
git fetch -q upstream --tags
git rev-parse -q --verify "$target^{commit}" >/dev/null 2>&1 || { echo "no such ref: $target"; exit 1; }
base=$(upstream_merge_base "$target")

header="dm/gfx/nvn/dmsh_chain_vk.h"
[ -f "$header" ] || { echo "missing $header in this tree"; exit 1; }

ours_chain=$(grep -o 'DMSH_VK_CHAIN "[^"]*"' "$header")
ours_abi=$(grep -o 'DMSH_VK_ABI "[^"]*"' "$header")
theirs_chain=$(git show "$target:$header" 2>/dev/null | grep -o 'DMSH_VK_CHAIN "[^"]*"' || echo "DMSH_VK_CHAIN unknown")
theirs_abi=$(git show "$target:$header" 2>/dev/null | grep -o 'DMSH_VK_ABI "[^"]*"' || echo "DMSH_VK_ABI unknown")

echo "our tree:     $ours_chain   $ours_abi"
echo "upstream tip: $theirs_chain   $theirs_abi"

if [ "$ours_chain" = "$theirs_chain" ]; then
    echo "shader chain: unchanged since $(git rev-parse --short "$base") (DMSH_VK_CHAIN matches)"
    exit 0
fi
echo

files=$(upstream_shader_chain_files)

echo "commits touching the chain's actual file set since $(git rev-parse --short "$base"):"
# shellcheck disable=SC2086
git log --format='  %an %ad %s' --date=short "$base..$target" -- $files 2>/dev/null | sort -u || true
echo

if [ "$ours_abi" != "$theirs_abi" ]; then
    echo "DMSH_VK_ABI also moved: old installed libraries will NOT play; every device needs a full rebuild, not a delta."
else
    echo "DMSH_VK_ABI unchanged: old installed libraries keep playing their current version while each device's delta runs."
fi

cat <<'EOF'

This script is report-only -- it never checks out or writes anything. The chain's files are not
safe to pull piecemeal (a partial translator-source update can leave Gradle's nxPackXlateCheck
failing against a stale libdmsh_xlate.so). Pull them via the whole-tree rebase instead:

  1. peanuts/scripts/upstream-update.sh            # the real rebase
  2. python3 dm/gfx/tools/tool_lock.py --update-lock   # if an Apple-chain input moved
  3. python3 dm/gfx/tools/dmsh_chain.py --header        # regenerates DMSH_CHAIN
  4. python3 dm/gfx/tools/vk_android_build.py --header # regenerates dm/gfx/nvn/dmsh_chain_vk.h
  5. build a known game with the new tools, byte-compare the resulting dmsh_vk.lib against the
     previous version's library for that same game:
       - byte-identical: append a row to dm/gfx/tools/vk_android_same.tsv (tools digest -> library
         version it reproduced, with an evidence string naming the actual build and comparison --
         "by inspection" is not sufficient evidence).
       - different: no row; every device's library simply moves to the new version.
  6. python3 dm/gfx/tools/vk_android_build.py --check <rebuilt dmsh_vk.lib>
EOF
