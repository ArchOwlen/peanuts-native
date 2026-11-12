#!/bin/sh
# Build-time check for the assumption dev.peanuts.Installer hard-codes: RUNTIME_VERSION must equal dm/peanuts/rt/mods/mod_runtime.h's
# DualScreenRuntimeVersion. No Java binding for that C++ constant exists (one would need new JNI surface, out of Installer.java's scope - see
# dm/peanuts/peanuts.c's own PEANUTS touch point, rt_find_package only), so the two are kept in sync by hand. CRITIQUE-phase2b.md B "nice to have":
# this script replaces copying the number by eye - it greps both sides and fails if they ever drift, at build time rather than after a runtime lift
# ships with a stale refusal threshold (every package above the OLD number would wrongly be refused, or below the NEW one wrongly accepted).
#
# usage: check-runtime-version.sh

set -eu
cd "$(git rev-parse --show-toplevel)"

header="dm/peanuts/rt/mods/mod_runtime.h"
installer="android/app/src/main/java/dev/peanuts/Installer.java"

for f in "$header" "$installer"; do
    if [ ! -f "$f" ]; then
        echo "check-runtime-version.sh: missing $f" >&2
        exit 1
    fi
done

native="$(grep -E 'inline constexpr u32 DualScreenRuntimeVersion = [0-9]+;' "$header" | sed -E 's/.*= ([0-9]+);.*/\1/' | head -1)"
if [ -z "$native" ]; then
    echo "check-runtime-version.sh: no \"inline constexpr u32 DualScreenRuntimeVersion = <N>;\" line found in $header" >&2
    exit 1
fi

java="$(grep -E 'static final int RUNTIME_VERSION = [0-9]+;' "$installer" | sed -E 's/.*= ([0-9]+);.*/\1/' | head -1)"
if [ -z "$java" ]; then
    echo "check-runtime-version.sh: no \"static final int RUNTIME_VERSION = <N>;\" line found in $installer" >&2
    exit 1
fi

if [ "$native" = "$java" ]; then
    echo "OK: DualScreenRuntimeVersion ($header) == RUNTIME_VERSION ($installer) == $native"
    exit 0
fi

echo "MISMATCH: DualScreenRuntimeVersion ($header) = $native, but RUNTIME_VERSION ($installer) = $java" >&2
echo "update dev.peanuts.Installer.RUNTIME_VERSION (or the header) so they agree - a stale Java-side number refuses or accepts the wrong packages" >&2
exit 1
