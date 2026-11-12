#!/bin/sh
# Find PEANUTS-marked hunks whose surrounding context has changed upstream since our last sync
# -- the spots peanuts/scripts/upstream-update.sh's rebase is likely to stop on. Purely
# informational; never writes anything. See peanuts/docs/UPSTREAM.md section 2.5.
set -eu
cd "$(git rev-parse --show-toplevel)"
. "$(cd "$(dirname "$0")" && pwd)/sync-state.sh"

target="upstream/android"
git fetch -q upstream --tags
git rev-parse -q --verify "$target^{commit}" >/dev/null 2>&1 || { echo "no such ref: $target"; exit 1; }
since="${1:-$(upstream_state_read "$target")}"
git rev-parse -q --verify "$since^{commit}" >/dev/null 2>&1 || { echo "bad since-ref: $since"; exit 1; }

echo "PEANUTS-marked lines whose file has upstream churn since $(git rev-parse --short "$since"):"
echo

# Search the whole tree except the Peanuts-owned source trees -- a marker there is expected and
# not a rebase risk. This intentionally covers more than dev/nxnative/android and dm/host (the
# original draft's scope): CRITIQUE-phase1.md found PEANUTS-relevant hunks scattered across
# dm/gfx/vk_android, dm/gfx/tools and dm/games too.
grep -rln 'PEANUTS' -- . \
    ':!peanuts' ':!dm/peanuts' ':!android/app/src/main/java/dev/peanuts' ':!android/app/src/peanuts' ':!PEANUTS.md' \
    2>/dev/null | while read -r file; do
    # does this file change upstream at all since our marker?
    if ! git diff --quiet "$since" "$target" -- "$file" 2>/dev/null; then
        echo "-- $file (upstream touched this file since $since) --"
        grep -n 'PEANUTS' "$file" | while IFS=: read -r lineno rest; do
            lo=$((lineno > 3 ? lineno - 3 : 1))
            hi=$((lineno + 3))
            # does upstream's diff for this file touch any line in [lo,hi] of OUR current file?
            # approximate with git log -L, which tracks the line range through history.
            hit=$(git log -L "${lo},${hi}:${file}" "$since..$target" --format='%h' 2>/dev/null | head -1 || true)
            if [ -n "$hit" ]; then
                echo "  line $lineno: $(echo "$rest" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
                echo "    nearby upstream commit since last sync: $hit -- review before rebasing"
            fi
        done
    fi
done

echo
echo "(no output above a file's header means none of its PEANUTS lines are near upstream churn)"
echo
echo "Note: this only checks lines that already carry a PEANUTS marker. It does NOT find unmarked"
echo "Peanuts hunks (see UPSTREAM-EDITS.md for the current inventory of those) -- that is checked"
echo "at hunk granularity by peanuts/scripts/upstream-update.sh itself, after the rebase."
