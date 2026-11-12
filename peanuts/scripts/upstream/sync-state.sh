#!/bin/sh
# Helper, sourced by the other peanuts/scripts/upstream/*.sh scripts. Read-only: never writes
# unless upstream_state_write is called explicitly (nothing in this directory calls it; the
# owner's merge flow does, by hand, after a real rebase).
#
# "Since we last synced" is measured against the merge-base of HEAD and the upstream target, not
# a stored marker and not the upstream tip. A stored marker can go stale (forgotten after a
# manual rebase) and defaulting to the upstream tip makes `since..target` empty, so every report
# silently says "no change" (CRITIQUE-phase1.md UPSTREAM §2 must-fix #1). The merge-base needs no
# bookkeeping: right after upstream-update.sh's rebase, HEAD's merge-base with the target *is*
# the last synced commit, by construction.
#
# peanuts/.upstream-sync, if present, overrides the merge-base default -- useful for asking "what
# changed since some earlier point", e.g. for a report you want to re-run. It is never read by
# default for the --apply paths below; those always use the merge-base so a stale override file
# can never cause a revert.

upstream_state_file() {
    printf '%s/peanuts/.upstream-sync' "$(git rev-parse --show-toplevel)"
}

# Prints the merge-base of HEAD and $1 (default upstream/android). This is the correct default
# "since" ref for report/diff purposes.
upstream_merge_base() {
    target="${1:-upstream/android}"
    git merge-base HEAD "$target"
}

# Prints the SHA to compare "since" against: the override file if present and non-empty,
# otherwise the merge-base with $1 (default upstream/android).
upstream_state_read() {
    f="$(upstream_state_file)"
    if [ -f "$f" ] && [ -s "$f" ]; then
        cat "$f"
    else
        upstream_merge_base "${1:-upstream/android}"
    fi
}

# Only called explicitly by the owner's merge flow, never by a report/diff/sync script here.
upstream_state_write() {
    sha="$1"
    f="$(upstream_state_file)"
    [ -n "$sha" ] || { echo "upstream_state_write: missing sha" >&2; return 1; }
    printf '%s\n' "$sha" > "$f"
    echo "wrote $f = $sha"
}

# Prints the full, accurate list of files whose bytes feed DMSH_VK_CHAIN -- imported read-only
# from dmsh_chain.py's own FILES list (module-level exec only: plain assignments, no I/O) and
# vk_android_build.py's own VK_FILES (extracted by regex + ast.literal_eval on just that line,
# so vk_android_build.py's own heavier import chain is never executed). A hand-kept file list
# here previously missed dmsh_atomic.py, dmsh_pull.py/.metal, dmsh_alpha.py, dmsh_logic.py,
# dmsh_mesh.py, dmsh_ampere.py, nvn/dmsh_abi.h, dmsh_abi.py, ios_metal.py and the vendored
# Ryujinx .cs sources (CRITIQUE-phase2a.md D-2b) -- this is the authoritative source instead.
upstream_shader_chain_files() {
    python3 - <<'PYEOF'
import importlib.util, os, re, ast

def resolve(gfx, rel):
    return os.path.normpath(os.path.join(gfx, rel))

spec = importlib.util.spec_from_file_location('dmsh_chain', 'dm/gfx/tools/dmsh_chain.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
for f in m.FILES:
    print(resolve('dm/gfx', f))

text = open('dm/gfx/tools/vk_android_build.py').read()
vk = ast.literal_eval(re.search(r'VK_FILES\s*=\s*(\[[^\]]*\])', text).group(1))
for f in vk:
    print(resolve('dm/gfx', f))
PYEOF
}

# True (exit 0) iff our tree's copy of $1, as of HEAD, is unchanged from the merge-base $2 --
# i.e. we have never locally edited this file, so it is safe to take upstream's version of it
# wholesale. False (exit 1) if we have local edits to this file (even if upstream also changed
# it) -- those paths must never be blind-checked-out, or a local change silently reverts
# (CRITIQUE-phase1.md UPSTREAM §2 must-fix #2: the "two-dot diff" bug in the original drafts).
upstream_file_is_unmodified_by_us() {
    file="$1"
    base="$2"
    git diff --quiet "$base" HEAD -- "$file"
}
