// PNRT shim for Eden's core/core.h. Core::System is the host facade (the "PnHost"): it owns the
// guest-memory accessor, the fake core timing, the UI sink (through the GPU's AuxRouting), the
// virtual pad, the filesystem roots and the title identity. The lifted runtime keeps calling the
// Eden accessor names; peanuts_rt.cpp fills the members from PnRtConfig.
#pragma once

#include <filesystem>
#include <functional>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "common/common_types.h"
#include "core/core_timing.h"
#include "core/file_sys/vfs/vfs.h"
#include "core/hle/kernel/k_process.h"
#include "core/hle/kernel/kernel.h"
#include "core/loader/loader.h"
#include "core/memory.h"
#include "core/perf_stats.h"
#include "hid_core/frontend/emulated_controller.h"
#include "input_common/drivers/virtual_gamepad.h"
#include "video_core/gpu.h"

namespace Service::FileSystem {

/// GetModificationLoadRoot(title) in Eden is load/<title>/ with one subdirectory per installed
/// mod; here it is a synthetic root over the package directories the host names.
class FileSystemController {
public:
    [[nodiscard]] FileSys::VirtualDir GetModificationLoadRoot(u64) const {
        return load_root;
    }
    FileSys::VirtualDir load_root;
};

} // namespace Service::FileSystem

namespace Core {

class System {
public:
    System() : process{std::make_unique<Kernel::KProcess>(&memory)}, kernel{process.get()} {}
    System(const System&) = delete;
    System& operator=(const System&) = delete;

    [[nodiscard]] Memory::Memory& ApplicationMemory() {
        return memory;
    }
    [[nodiscard]] const Memory::Memory& ApplicationMemory() const {
        return memory;
    }
    /// The one guest process (the running title); its GetMemory() is `memory`.
    [[nodiscard]] Kernel::KProcess* ApplicationProcess() {
        return process.get();
    }
    [[nodiscard]] Kernel::KernelCore& Kernel() {
        return kernel;
    }
    [[nodiscard]] Timing::CoreTiming& CoreTiming() {
        return core_timing;
    }
    [[nodiscard]] Tegra::GPU& GPU() {
        return gpu;
    }
    [[nodiscard]] InputCommon::InputSubsystem* GetInputSubsystem() {
        return &input;
    }
    [[nodiscard]] Service::FileSystem::FileSystemController& GetFileSystemController() {
        return fs_controller;
    }
    [[nodiscard]] Loader::AppLoader& GetAppLoader() {
        return app_loader;
    }
    [[nodiscard]] HID::HIDCore& HIDCore() {
        return hid_core;
    }
    [[nodiscard]] Core::PerfStats& GetPerfStats() {
        return perf_stats;
    }
    [[nodiscard]] u64 GetApplicationProcessProgramID() const {
        return title_id;
    }
    /// The guest is always "running" from the runtime's point of view once pn_rt_create returned.
    [[nodiscard]] bool IsPoweredOn() const {
        return true;
    }
    [[nodiscard]] bool IsMulticore() const {
        return true;
    }
    /// Eden pauses the guest's threads around `fn` (a module's atomic write batch). The host owns
    /// the guest here; `fn` simply runs (the writes still go through PnRtMemory.write).
    bool RunWithGuestThreadsSuspended(const std::function<void()>& fn) {
        fn();
        return true;
    }
    /// {address, size} of the guest mailbox installed by a load plan; {0,0} = none.
    [[nodiscard]] std::pair<u64, u64> GetDualScreenGuestMailbox() const {
        return {0, 0};
    }
    [[nodiscard]] u32 GetDualScreenGuestMailboxEpoch() const {
        return 0;
    }
    /// PNRT: the game's romfs as a directory on disk ("romfs:" / "base:" assets), nullptr when
    /// the host gave none.
    [[nodiscard]] FileSys::VirtualDir RomfsRoot() const {
        return romfs_root;
    }
    /// PNRT: the title's save-data folder (a module's read_save_file), nullptr when none.
    [[nodiscard]] FileSys::VirtualDir SaveRoot() const {
        return save_root;
    }
    /// PNRT: a folder holding the console's shared .bfttf fonts ("sysfont:"), nullptr when none.
    [[nodiscard]] FileSys::VirtualDir SysfontRoot() const {
        return sysfont_root;
    }

    Memory::Memory memory;
    std::unique_ptr<Kernel::KProcess> process; ///< the running title; heap region hints optional
    Kernel::KernelCore kernel;
    Timing::CoreTiming core_timing;
    Tegra::GPU gpu;
    InputCommon::InputSubsystem input;
    Service::FileSystem::FileSystemController fs_controller;
    Loader::AppLoader app_loader;
    HID::HIDCore hid_core;
    Core::PerfStats perf_stats;
    FileSys::VirtualDir romfs_root;
    FileSys::VirtualDir save_root;
    FileSys::VirtualDir sysfont_root;
    u64 title_id{};
};

} // namespace Core
