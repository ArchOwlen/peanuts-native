// PNRT shim for Eden's common/dynamic_library.h: GameModule (mod_module.h) keeps one as a member.
// Title .so modules are not loaded in R1 (GameModule::Load is stubbed to "none"), so this is a
// placeholder type with no behaviour.
#pragma once

namespace Common {

class DynamicLibrary {
public:
    DynamicLibrary() = default;
    ~DynamicLibrary() = default;
    DynamicLibrary(const DynamicLibrary&) = delete;
    DynamicLibrary& operator=(const DynamicLibrary&) = delete;
    [[nodiscard]] bool IsOpen() const {
        return false;
    }
};

} // namespace Common
