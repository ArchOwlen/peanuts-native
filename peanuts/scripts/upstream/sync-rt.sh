#!/bin/sh
# Check the companion runtime (dm/peanuts/rt) against the pinned Eden Duo release, per
# dm/peanuts/rt/PORT.md's documented lift procedure. NEVER writes into the worktree -- only
# into a scratch dir, and only with --apply.
#
# CRITIQUE-phase1.md UPSTREAM §2 must-fix #6: the original draft compared against ~/eden's
# current HEAD. ~/eden is a shared working tree (feedback_shared_eden_build_sessions) that other
# sessions check out to whatever they're working on -- it is very often NOT at the pinned tag,
# and `git describe --tags` never equals the bare tag name exactly (it appends -N-g<sha> once
# you're even one commit past it), so a HEAD-based compare always reports "differs" even when
# nothing relevant changed. Compare against `git show <tag>:<path>` instead -- the tag's content
# is immutable regardless of what ~/eden happens to be checked out to right now.
#
# There are 6 files in dm/peanuts/rt/mods carrying PNRT-marked hunks (confirmed with
# `grep -rl PNRT dm/peanuts/rt/mods`), not 4: mod_runtime.h, mod_assets.cpp,
# mod_romfs_sources.cpp, mod_module_services.cpp, mod_module.cpp, pnrt_stubs.cpp. The last two
# are the native-module-host files added in R5; PORT.md's own "four edited files" line only
# counted files that were ALSO present before R5 (replaced-with-hunks), not new files that carry
# PNRT markers because they are Peanuts-authored content living in a directory tree that is
# otherwise a verbatim lift. Both readings are "PNRT files"; this script reports the filesystem
# truth (6), not the historical PORT.md count.
#
# Runtime/APK changes go through the cycle steward (feedback_runtime_change_gate,
# feedback_eden_duo_no_push_without_ok); this script surfaces the diff, it does not authorize
# anything. --apply only ever stages into /tmp, never into mods/.
set -eu
cd "$(git rev-parse --show-toplevel)"

eden="${EDEN_DIR:-$HOME/eden}"
tag="${EDEN_DUO_TAG:-v1.3.5.1}"
apply="${1:-}"
mods_dir="dm/peanuts/rt/mods"
scratch="/tmp/peanuts-rt-sync"

[ -d "$eden" ] || { echo "eden tree not found at $eden (set EDEN_DIR)"; exit 1; }
[ -d "$mods_dir" ] || { echo "no $mods_dir in this worktree"; exit 1; }

if ! git -C "$eden" rev-parse -q --verify "$tag^{commit}" >/dev/null 2>&1; then
    echo "tag $tag not found in $eden -- fetch it there first (this script never fetches ~/eden itself)"
    exit 1
fi
tag_commit=$(git -C "$eden" rev-parse --short "$tag")

port_tag=$(grep -oE 'v[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+' dm/peanuts/rt/PORT.md | head -1 || true)

echo "pinned Eden Duo tag: $tag ($tag_commit)"
echo "dm/peanuts/rt/PORT.md's last lift: ${port_tag:-unknown}"
if [ "$tag" != "${port_tag:-}" ]; then
    echo "** EDEN_DUO_TAG ($tag) does not match PORT.md's recorded lift ($port_tag) -- you are"
    echo "   probably checking against the wrong tag, or PORT.md needs updating after a real lift. **"
fi
echo

pnrt_files=$(grep -rl 'PNRT' "$mods_dir" 2>/dev/null | sort)
pnrt_count=$(printf '%s\n' "$pnrt_files" | grep -c . || true)
echo "PNRT-marked files in $mods_dir: $pnrt_count"
printf '%s\n' "$pnrt_files" | sed 's/^/  /'
echo

unchanged=0; safe=0; needs_review=0; missing=0
echo "partitioning $mods_dir/* against $tag:core/mods/*:"
for f in "$mods_dir"/*; do
    name=$(basename "$f")
    [ -f "$f" ] || continue
    if ! git -C "$eden" cat-file -e "$tag:src/core/mods/$name" 2>/dev/null; then
        missing=$((missing + 1))
        echo "  NOT IN $tag (new/renamed since the pin, or ours only): $name"
        continue
    fi
    if git -C "$eden" show "$tag:src/core/mods/$name" 2>/dev/null | cmp -s - "$f"; then
        unchanged=$((unchanged + 1))
        continue
    fi
    if printf '%s\n' "$pnrt_files" | grep -qx "$f"; then
        needs_review=$((needs_review + 1))
        echo "  NEEDS PNRT RE-APPLY: $name"
        echo "      diff -u <(git -C $eden show $tag:src/core/mods/$name) $f   # shows exactly the PNRT hunks to re-apply by hand"
    else
        safe=$((safe + 1))
        echo "  safe straight copy: $name"
    fi
done

echo
echo "unchanged: $unchanged   safe-copy: $safe   needs-PNRT-reapply: $needs_review   not-in-$tag: $missing"

if [ "$apply" = "--apply" ]; then
    mkdir -p "$scratch"
    for f in "$mods_dir"/*; do
        name=$(basename "$f")
        [ -f "$f" ] || continue
        git -C "$eden" cat-file -e "$tag:src/core/mods/$name" 2>/dev/null || continue
        git -C "$eden" show "$tag:src/core/mods/$name" 2>/dev/null | cmp -s - "$f" && continue
        git -C "$eden" show "$tag:src/core/mods/$name" > "$scratch/$name" 2>/dev/null
    done
    echo
    echo "staged candidate copies (from the $tag tag, not ~/eden's working tree) to $scratch/ for review."
    echo "This does NOT touch $mods_dir. Runtime bumps need cycle-steward approval before anything"
    echo "is copied into the worktree (see PORT.md's lift procedure for the PNRT files above)."
else
    echo "(no files staged; pass --apply to copy candidates into $scratch/ for review -- still never into the worktree)"
fi
