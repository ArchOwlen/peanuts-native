#!/bin/sh
# Equivalence gate for the generic host's runtime profile (design/platform/IMPL-GENERIC.md), to run BEFORE any Thor boot test and before trusting
# -PnxGenericHost=true for a real build. For every dm/games/<id> profile (131 at this writing, plus any under DM_GAMES_DIR):
#   1. dm/games/game.py header <id> <tmp>/<id>/game_profile.h      -- the exact header the per-title Android host compiles today
#   2. dm/peanuts/profile/gen_runtime_profile.py profile <id> <tmp>/<id>.profile.bin  -- the generic host's runtime data for the same title
#   3. compiles dump_const (against step 1's header) and, once, dump_runtime (against profile_rt.c) -- both print every DM_GAME_* field as
#      "name=value" / "name[i]=..." text, in the same order
#   4. diffs the two outputs field by field; any difference is a real behavior divergence the generic host would have, not a formatting quirk
#   5. (CRITIQUE-generic.md §2) gen_runtime_profile.py seed <id> <tmp>/<id>.seed, cmp'd against dm/games/<id>/vk_prewarm.bin (or an empty file when the
#      title has none) -- the seed is a verbatim byte copy, so this is the one new code path the field-by-field diff above never touches
#
# Exits non-zero on the first profile that fails gen_runtime_profile.py's own build guard (an unknown macro - see check-all) or whose dump or seed
# differs. Prints one OK/FAIL line per title and a final count. Host compiler: cc (plain PC build, no NDK -- game_profile.h and profile_rt.c have no
# Android dependency).
set -eu
cd "$(git rev-parse --show-toplevel)"

CC=${CC:-cc}
ROOT=$(pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

echo "gen_runtime_profile.py check-all (build guard: every profile's macros are known, and the layout size) ..."
python3 dm/peanuts/profile/gen_runtime_profile.py check-all

python3 dm/peanuts/profile/gen_runtime_profile.py schema-stamp "$TMP/schema_stamp.h"
$CC -O1 -I dm/peanuts/profile -I "$TMP" -c dm/peanuts/profile/profile_rt.c -o "$TMP/profile_rt.o"
$CC -O1 -I dm/peanuts/profile -I "$TMP" dm/peanuts/profile/test/dump_runtime.c "$TMP/profile_rt.o" -o "$TMP/dump_runtime"

titles=$(python3 dm/games/game.py list | cut -f1)
checked=0
status=0
for id in $titles; do
  mkdir -p "$TMP/$id"
  python3 dm/games/game.py header "$id" "$TMP/$id/game_profile.h"
  python3 dm/peanuts/profile/gen_runtime_profile.py profile "$id" "$TMP/$id.profile.bin"
  python3 dm/peanuts/profile/gen_runtime_profile.py seed "$id" "$TMP/$id.seed"
  srcseed=$(python3 dm/games/game.py dir "$id")/vk_prewarm.bin
  if [ ! -f "$srcseed" ]; then : > "$TMP/$id.seed.want"; else cp "$srcseed" "$TMP/$id.seed.want"; fi
  seedfail=""
  if ! cmp -s "$TMP/$id.seed" "$TMP/$id.seed.want"; then seedfail="seed differs from vk_prewarm.bin"; fi
  if ! $CC -O1 -I "$TMP/$id" dm/peanuts/profile/test/dump_const.c -o "$TMP/$id/dump_const" 2>"$TMP/$id/cc.log"; then
    echo "FAIL $id: dump_const did not compile against this profile's own game_profile.h"
    sed 's/^/    /' "$TMP/$id/cc.log"; status=1; checked=$((checked + 1)); continue
  fi
  "$TMP/$id/dump_const" > "$TMP/$id/const.txt"
  "$TMP/dump_runtime" "$TMP/$id.profile.bin" > "$TMP/$id/runtime.txt"
  if diff -u "$TMP/$id/const.txt" "$TMP/$id/runtime.txt" > "$TMP/$id/diff.txt" && [ -z "$seedfail" ]; then
    echo "OK   $id"
  else
    echo "FAIL $id: ${seedfail:+$seedfail; }$( [ -s "$TMP/$id/diff.txt" ] && echo "compile-time and runtime profiles differ" )"
    sed 's/^/    /' "$TMP/$id/diff.txt"
    status=1
  fi
  checked=$((checked + 1))
done

echo "check-runtime-profile.sh: $checked profiles checked"
exit $status
