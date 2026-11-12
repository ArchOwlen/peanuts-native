#!/bin/zsh
# Linux equivalent of android/ondevice/build.sh (android/ONDEVICE.md): produces the same <work dir>/stage/ the Mac script makes, so the Peanuts Native APK can
# carry the real on-device Prepare engine (-PnxPackStage=<stage>, NX_PACK_STAGE) instead of -PnxPackEngine=fake.
#
# This is a thin wrapper, not a reimplementation: android/ondevice/build.sh and the scripts it calls (dm/gfx/tools/dmsh_xlate_android/build.sh,
# build_spirv_tools.sh, dm/third_party/zstd-zbic/build.sh) are zsh/sh + curl/cmake/ninja/dotnet/NDK-clang and run UNCHANGED on Linux once zsh is available and
# the Android NDK is pointed at by ANDROID_NDK_HOME (upstream's own hardcoded fallback is a Mac path under ~/Library/Android/sdk/ndk, which this script
# overrides). No upstream file is edited. The only Linux-specific step this script adds is exporting MSBuildEnableWorkloadResolver=false before any dotnet
# publish: on this machine's .NET 10 SDK (/usr/share/dotnet) the workload manifests are broken by package management, which otherwise makes `dotnet publish
# -r linux-bionic-arm64 -p:NativeLib=Shared` (dmsh_xlate_android/build.sh's `pub`) fail resolving the NativeAOT/bionic runtime pack.
#
# Stage layout produced (identical to the Mac's, android/ondevice/build.sh's header comment):
#   <work dir>/stage/
#     lib/libpython3.14.so, libnxpack.so, libzbicdec.so, libdmsh_xlate.so, libSPIRV-Tools-shared.so   arm64-v8a jniLibs the APK packs
#     python/lib/python3.14/            the Python home: stdlib + lib-dynload/, site-packages/capstone (+ lib/libcapstone.so), site-packages/lz4
#     bin/dm_pack_cli                   adb shell test runner (not packed into the APK)
#     python/bin/nxpack_worker          PEANUTS: shader_split.py's pool worker on the phone (dm/peanuts/pack_worker; goes into python.zip with the Python home)
#
# Usage: peanuts/scripts/ondevice-stage.sh <work dir> [all|python|tools|zbic]   (default all; same WHAT values as android/ondevice/build.sh)
#   ANDROID_NDK_HOME must already point at the NDK (e.g. ~/android-sdk/ndk/28.2.13676358). Put <work dir> outside the repo (downloads + build intermediates).
set -e
HERE=${0:A:h}
REPO=${HERE:A:h:h}   # peanuts/scripts -> peanuts -> repo root
[ -d "$REPO/android/ondevice" ] || { echo "[RED] $REPO does not look like the Banana Native repo root"; exit 1; }
[ -n "$ANDROID_NDK_HOME" ] || { echo "[RED] set ANDROID_NDK_HOME to the Android NDK (e.g. ~/android-sdk/ndk/28.2.13676358)"; exit 1; }
[ -d "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" ] || { echo "[RED] $ANDROID_NDK_HOME: no toolchains/llvm/prebuilt (not an NDK root?)"; exit 1; }
command -v zsh >/dev/null || { echo "[RED] zsh is required (upstream's build.sh scripts are zsh)"; exit 1; }

export MSBuildEnableWorkloadResolver=false   # this machine's dotnet SDK has broken workload manifests; see header comment

zsh "$REPO/android/ondevice/build.sh" "$@"
sh "$REPO/dm/peanuts/pack_worker/build.sh" "$1/stage" "$1/py/prefix/include/python3.14"   # PEANUTS hook: nxpack_worker into stage/python/bin (shader_split.py's pool workers on the phone)
