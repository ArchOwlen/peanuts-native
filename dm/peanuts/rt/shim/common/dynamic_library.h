// PNRT shim for Eden's common/dynamic_library.h (Dolphin's DynamicLibrary): the slice the module
// loader uses -- Open / Close / GetSymbol / IsOpen -- over dlopen (shim/shim.cpp). The lifted
// mod_module.cpp dlopen's a package's dualscreen/modules/<platform>/<TITLEID>.so through it.
#pragma once

#include <string>

namespace Common {

class DynamicLibrary final {
public:
    DynamicLibrary() = default;
    explicit DynamicLibrary(const char* filename) {
        (void)Open(filename);
    }
    DynamicLibrary(DynamicLibrary&& rhs) noexcept : handle{rhs.handle} {
        rhs.handle = nullptr;
    }
    DynamicLibrary& operator=(DynamicLibrary&& rhs) noexcept;
    DynamicLibrary(const DynamicLibrary&) = delete;
    DynamicLibrary& operator=(const DynamicLibrary&) = delete;
    ~DynamicLibrary();

    [[nodiscard]] bool IsOpen() const {
        return handle != nullptr;
    }
    [[nodiscard]] bool Open(const char* filename);
    void Close();
    [[nodiscard]] void* GetSymbolAddress(const char* name) const;

    template <typename T>
    [[nodiscard]] bool GetSymbol(const char* name, T* ptr) const {
        *ptr = reinterpret_cast<T>(GetSymbolAddress(name));
        return *ptr != nullptr;
    }

private:
    void* handle = nullptr;
};

} // namespace Common
