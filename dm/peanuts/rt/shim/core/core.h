// PNRT shim for Eden's core/core.h. Core::System is the host facade (the "PnHost"): it owns the
// guest-memory accessor, the fake core timing, the UI sink (through the GPU's AuxRouting), the
// virtual pad, the filesystem roots and the title identity. The lifted runtime keeps calling the
// Eden accessor names; peanuts_rt.cpp fills the members from PnRtConfig.
#pragma once

#include <filesystem>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "common/common_types.h"
#include "core/core_timing.h"
#include "core/file_sys/vfs/vfs.h"
#include "core/hle/kernel/k_process.h"
#include "core/loader/loader.h"
#include "core/memory.h"
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
    System() = default;
    System(const System&) = delete;
    System& operator=(const System&) = delete;

    [[nodiscard]] Memory::Memory& ApplicationMemory() {
        return memory;
    }
    [[nodiscard]] const Memory::Memory& ApplicationMemory() const {
        return memory;
    }
    /// nullptr: there is no kernel process object (every caller null-checks).
    [[nodiscard]] Kernel::KProcess* ApplicationProcess() {
        return process.get();
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
    [[nodiscard]] u64 GetApplicationProcessProgramID() const {
        return title_id;
    }
    /// {address, size} of the guest mailbox installed by a load plan; {0,0} = none.
    [[nodiscard]] std::pair<u64, u64> GetDualScreenGuestMailbox() const {
        return {0, 0};
    }
    /// PNRT: the game's romfs as a directory on disk ("romfs:" assets), nullptr when the host
    /// gave none.
    [[nodiscard]] FileSys::VirtualDir RomfsRoot() const {
        return romfs_root;
    }

    Memory::Memory memory;
    Timing::CoreTiming core_timing;
    Tegra::GPU gpu;
    InputCommon::InputSubsystem input;
    Service::FileSystem::FileSystemController fs_controller;
    Loader::AppLoader app_loader;
    HID::HIDCore hid_core;
    std::unique_ptr<Kernel::KProcess> process; ///< optional: heap region hints
    FileSys::VirtualDir romfs_root;
    u64 title_id{};
};

} // namespace Core
