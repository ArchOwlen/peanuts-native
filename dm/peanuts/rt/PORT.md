# peanuts_rt port notes (R1a)

The Eden Duo DSMod companion runtime (`~/eden/src/core/mods`, branch `dsmod`, HEAD
`c6541721fa`) lifted into a standalone static library. Layout:

```
dm/peanuts/rt/
  peanuts_rt.h / peanuts_rt.cpp   C API: pn_rt_create / destroy / tick / frame / touch / set_page / status
  CMakeLists.txt                  add_library(peanuts_rt STATIC), option PEANUTS_RT_TEST -> rt_test
  mods/                           lifted Eden files, ORIGINAL NAMES, byte-identical except where marked "PNRT"
  mods/pnrt_stubs.cpp             bodies for the members whose files are not lifted (list below)
  shim/                           stand-ins for the Eden headers the lifted files include (same include paths)
  shim/shim.cpp, shim/stb_impl.cpp
  test/rt_test.cpp                Linux host test: real package + fake guest memory -> PNGs
dm/peanuts/third_party/
  nlohmann/json.hpp, json_fwd.hpp (v3.12.0 single header, from Eden's CPM cache)
  stb/stb_image.h, stb_image_resize.h, stb_image_write.h (Eden externals/stb; Eden uses resize v1, not resize2)
  ankerl/unordered_dense.h, stl.h  (SnapshotMap in mod_types.h)
  bc_decoder/bc_decoder.{h,cpp}    (DDS BC1/BC3/BC7, used by mod_assets.cpp DecodeDds)
```

The lifted files include each other as `core/mods/<name>`; CMake creates
`<build>/pnrt_include/core/mods -> dm/peanuts/rt/mods` (file(CREATE_LINK ... SYMBOLIC)) so no
include line changed. Upstream sync: copy the Eden file over `mods/<name>`, re-apply the PNRT
hunks listed below (`diff -u ~/eden/src/core/mods/X mods/X` shows exactly them).

## Lifted (compiled) files

mod_assets.cpp, mod_load_plan.cpp, mod_manifest.cpp, mod_map.cpp, mod_msbt.cpp, mod_pages.cpp,
mod_redraw.cpp, mod_runtime.cpp, mod_state.cpp, mod_ui.cpp, mod_ui_canvas.cpp, mod_ui_expand.cpp,
mod_ui_image.cpp, mod_ui_map_widget.cpp, mod_ui_scroll.cpp, mod_ui_text.cpp,
mod_ui_transitions.cpp, mod_ui_widget_state.cpp; headers mod_types*.h, mod_ui*.h, mod_msbt.h,
map_overview.h, map_cache_identity.h, mod_runtime.h, mod_runtime_shared.h, mod_load_plan.h,
engine_formats.h, mod_module.h, dsmod_module_abi.h, dsmod_module_extensions.h, mod_nx_assets.h
(headers only; their .cpp are not lifted). Files named in the inventory that do not exist on the
dsmod branch: mod_expr.cpp, mod_settings.cpp, mod_nav.cpp, mod_host_values.{h,cpp},
mod_view_default.h, mod_input_tokens.h (their functionality lives inside the files above).

## Edited lifted files (all hunks carry a `PNRT` marker)

| file | why |
|---|---|
| mods/mod_runtime.h | `friend struct PnRtAccess;` so peanuts_rt.cpp can switch `current_page` and read `tick_count`/page id for pn_rt_set_page / pn_rt_status (Tick itself is reached without a friend: the CoreTiming shim keeps the scheduled event and pn_rt_tick fires it). |
| mods/mod_assets.cpp | `#if !PNRT` around `SerialVfsFile` + `OpenPrivateRomFS` (Eden loader/PatchManager/NCA chain) and `#if PNRT` replacing the romfs-opening block in `ReadAssetBytesRaw` with `romfs_root = system.RomfsRoot()` (a directory on disk handed over by the host, or nullptr). |

Everything else in `mods/` is byte-identical to Eden.

## Shims (dm/peanuts/rt/shim)

| Eden header | shim provides |
|---|---|
| common/common_types.h | u8..u64, s8..s64, f32/f64, u128, VAddr |
| common/logging.h | LOG_* macros -> `Common::Log::FmtLogMessage` over `std::vformat`; one sink function pointer (`SetSink`, default stderr + `__android_log_print` on Android); `Level`/`Class` enums; enum formatter |
| fmt/format.h, fmt/ranges.h | `fmt::format` = `std::format` (only `fmt::format` is used: 40 sites, literal format strings) |
| common/settings.h | `Settings::values.disabled_addons` (empty map), `IsNceEnabled()` = true (so the runtime disables the BRK guest bridge exactly as under Eden NCE) |
| common/hex_util.h | HexToString / HexStringToVector |
| common/cityhash.h | `CityHash64` as FNV-1a + finaliser (content hash only; not CityHash-compatible, nothing persisted depends on it) |
| common/thread.h | SetCurrentThreadName (pthread), SetCurrentThreadPriority (nice) |
| common/dynamic_library.h | placeholder class (GameModule member) |
| common/stb.h | as Eden's; implementation unit shim/stb_impl.cpp |
| core/core.h | **`Core::System` = the PnHost facade**: owns `Memory::Memory`, `Timing::CoreTiming`, `Tegra::GPU` (AuxRouting), `InputSubsystem` (virtual pad), `FileSystemController` (`GetModificationLoadRoot` -> synthetic dir of package dirs), `AppLoader` (icon bytes), `HIDCore` (no controller), optional `KProcess`, `RomfsRoot()`, title id, `GetDualScreenGuestMailbox()` = {0,0} |
| core/memory.h | `Core::Memory::Memory`: Read8/16/32/64, Write*, ReadBlock, WriteBlock, ReadCString, GetPointerSilent, IsValidVirtualAddressRange over a `Backend` of 3 function pointers (read/write/pointer_at + user) ; YUZU_PAGEMASK |
| core/core_timing.h | `CreateEvent` keeps the callback; `ScheduleLoopingEvent` registers it; `FireAll()` = one tick |
| core/file_sys/vfs/vfs.h, vfs_types.h | `VfsFile`/`VfsDirectory` (GetName, GetSize, Read, ReadAllBytes, ReadBytes, GetFile, GetSubdirectory, GetFiles, GetSubdirectories, GetFileRelative) over std::filesystem; `OpenHostDirectory`, `OpenHostFile`, `MakeSyntheticDir` |
| core/file_sys/{content_archive,patch_manager,registered_cache,romfs}.h, core/hle/service/filesystem/*.h | empty |
| core/hle/kernel/k_process.h, svc_types.h | `KProcess`/`KPageTable`/`KThread` (ApplicationProcess() returns nullptr; every lifted use null-checks), `GetInteger`, `Svc::ThreadContext`. k_process.h ends with `#include "core/core.h"` because mod_state.cpp / mod_runtime.cpp reach `Core::System` through it in Eden |
| core/loader/loader.h | `AppLoader::ReadIcon` (host-supplied JPEG bytes, empty = none) |
| video_core/dsmod/aux_routing.h, video_core/gpu.h | **the UI sink**: `AuxRouting` keeps the latest frame (PublishUi / PublishUiWith / PublishUiPartial / ClearUi / TakeUi) and forwards to an optional `UiSink` of host callbacks; touch mailbox (SetTouch/GetTouch); `PublishAuxTexture`/`PublishMapFadeTextures(Tiles)`/`PublishComposite`/`ClearComposite` accepted and dropped (GPU composite path off); `TileMask`, `DiffTiles`, `UiDiffEnabled()` = false; `Quad`; haptic sink |
| input_common/drivers/virtual_gamepad.h | `VirtualGamepad` (same VirtualButton enum) forwarding to a `PadSink` of function pointers; `InputSubsystem::GetVirtualGamepad()` |
| hid_core/frontend/emulated_controller.h | `HIDCore::GetEmulatedController` -> nullptr (PadIsPushed() = false) |

## Stubs (mods/pnrt_stubs.cpp): members of files not lifted

- **mod_actions.cpp**: `ParseButton` (copied verbatim), `RunAction` (ONLY `ActionKind::Page` served, copied from Eden minus the `origin` widget lookup; other kinds return Skipped -- so page_binds work, taps/writes/calls do not), `UpdateHeldButtons`, `ReleaseHeldInputs`, `DriveInputScript`, `DriveLiveInput` (no-ops).
- **mod_input.cpp**: `DrainTaps`, `PublishMapState`, `PublishScroll`, `PublishInteraction`, `UpdateGestures`, `ReturnIdleViews`, `ApplyViewCorrections`, `ResetInteraction`, `FlushHaptic`, `DriveCmdDrag` (no-ops); `GetViewState` (returns view_state), `GlideViews` (false). Touch is therefore a no-op end to end (pn_rt_touch fills the aux mailbox; nothing consumes it).
- **mod_guest_bridge.cpp**: `ArmSpies`, `ApplyPatches`, `DrainGuestBridgeResults`, `RunPolledSequences` (no-ops), `ScanPattern` (nullopt).
- **engine_il2cpp/mercury/ichigo.cpp**: `FindClassSlotByName`, `DetectMethodsOffset`, `FindPlayerNode` (nullopt); `DecodeBctex`, `DecodeSosXtx`, `ParseMfnt`, `ParseSosFont` (false); `DecodeLzss`, `ExtractArchiveMember` (empty).
- **mod_module*.cpp**: `GameModule::~GameModule`, `InitializeGameModule` (module = none), `ShutdownGameModule`, `RunGameModule`, `DrainModuleImages`, `InstallModuleAreas`, `ApplyPendingMapUpdates` (no-ops), `LoadModuleData` (empty), `GetModuleImage` (nullptr), `LockMapStateUnlessRaster` (copied verbatim: it is generic).
- **mod_nx_runtime.cpp / mod_nx_assets.cpp**: `MakeNxAssetState` (nullptr; never dereferenced because `NxFallback` returns true so every Nintendo-container source takes the synchronous `ReadAssetBytes` path and is reported "not found" once), `NxAssetStateDeleter`, `IsNxAssetSource` (copied), `GetNxImage`/`GetCompositeImage` (nullptr), `ReadNxMember` (false), `PumpNxAssets`, `PublishNxFades`, `ResetNxAssets` (no-ops), `CompositeFade` (1.0), `RequestNxFont` (false), `GetMsbtText` (nullptr), `NxIconFont` (nullptr, pending=false), `NxAssets::DecodeBntxImage` (false), `NxAssets::ParseManifestExtras` (no-op: "composites" and the long font keys are ignored).
- Dev tools (`mod_re_tools.cpp`, `mod_console.cpp`): compiled out by `EDEN_DSMOD_BUILD_DEV_TOOLS=0` (the shells in mod_runtime.cpp are already empty then).

## Verified (host, 2026-11-11)

`rt_test <eden-duo-companions>/packages/LinksAwakening <out>`:
- Discover loads the real package (4 pages, 662 points, data file 909E904AF78AC1B8.json).
- `standby.png` 1240x1080: the manifest background only -- all five widgets are
  `romfs:...arc#...bntx#...` images, no romfs locally, each logged "asset ... not found".
- `gear.png` 1240x1080: "GEAR" title, panel, hint text, "CLOSE", and the live rupee value
  **123** read from the fake memory (main+0x1CC0760) in the built-in 3x5 font (the .bffnt font
  fails to load and the runtime falls back by itself).
- `probe.png` 640x360 from a generated 1-page package: "RUPEES 123", "HEALTH 48", a bar at
  48/80 -- proves sample -> point -> bind -> text/bar on this build.
- arm64 Android (NDK 28.2.13676358, android-33, `-fexperimental-library` for std::jthread as in
  Eden's own Android build): libpeanuts_rt.a builds with all pn_rt_* symbols. Not run on device.

## Known gaps / R1b candidates

- No Nintendo container decoding (SARC/BNTX/BFFNT) and no composites: mod_nx_assets.cpp is
  pure format code (no Eden dependency beyond common_types) and mod_nx_runtime.cpp needs only a
  language setting + the VFS shim -- lift both to get LA's real art and font once a romfs dir is
  available to the host.
- No touch/gestures/actions beyond page switches: mod_input.cpp and mod_actions.cpp depend only
  on the aux/pad shims already written; lifting them replaces most of pnrt_stubs.cpp.
- `Core::System::ApplicationProcess()` is nullptr: heap-region sweeps (ArrayFind/PlayerFind
  points) use the fixed fallback window; hosts can fill `System::process` later.
- The page is published at ~30 Hz (every other tick) from the runtime's own redraw worker
  thread; the host must keep calling pn_rt_tick for frames to appear.
- `Settings::IsNceEnabled()` = true permanently: no BRK guest bridge, by design.
