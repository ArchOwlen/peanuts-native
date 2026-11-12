#!/bin/sh
# Builds nxpack_worker (pack_worker.c) into an on-device packer stage: <stage>/python/bin/nxpack_worker, which build.gradle's python.zip then carries and PackNative
# unpacks with the Python home (<filesDir>/pack/<version>/python/bin/nxpack_worker). Run after android/ondevice/build.sh (peanuts/scripts/ondevice-stage.sh does).
# Usage: build.sh <stage dir> <python include dir>   (the include dir: <work dir>/py/prefix/include/python3.14 of the stage's build, the official Android CPython's headers)
#   ANDROID_NDK_HOME names the NDK. Linked against <stage>/lib/libpython3.14.so; the rpath $ORIGIN/../../lib is for adb shell tests of the stage itself (on the phone,
#   LD_LIBRARY_PATH names the APK's lib folder: PackService).
set -eu
S=${1:?usage: build.sh <stage dir> <python include dir>}; INC=${2:?usage: build.sh <stage dir> <python include dir>}
HERE=$(cd "$(dirname "$0")" && pwd)
[ -f "$S/lib/libpython3.14.so" ] || { echo "[RED] $S has no lib/libpython3.14.so: run android/ondevice/build.sh first"; exit 1; }
[ -f "$INC/Python.h" ] || { echo "[RED] $INC has no Python.h"; exit 1; }
[ -n "${ANDROID_NDK_HOME:-}" ] || { echo "[RED] set ANDROID_NDK_HOME"; exit 1; }
BIN=$(ls -d "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin | head -1)
mkdir -p "$S/python/bin"
"$BIN/clang" --target=aarch64-linux-android33 -O2 -Wall -fPIE -pie -I"$INC" -o "$S/python/bin/nxpack_worker" "$HERE/pack_worker.c" \
  -L"$S/lib" -lpython3.14 -Wl,-rpath,'$ORIGIN/../../lib' -Wl,-z,max-page-size=16384
"$BIN/llvm-strip" --strip-debug "$S/python/bin/nxpack_worker"
ls -l "$S/python/bin/nxpack_worker"
