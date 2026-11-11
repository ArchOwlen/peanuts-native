// PNRT shim for Eden's core/memory.h: Core::Memory::Memory is the guest-memory accessor the lifted
// sampler (mod_state.cpp) reads through. It forwards to a pluggable backend of three function
// pointers so the host (Peanuts' native loader, or the test's fake map) owns the actual bytes.
#pragma once

#include <cstddef>
#include <string>

#include "common/common_types.h"

namespace Core::Memory {

constexpr u64 YUZU_PAGEBITS = 12;
constexpr u64 YUZU_PAGESIZE = 1ULL << YUZU_PAGEBITS;
constexpr u64 YUZU_PAGEMASK = YUZU_PAGESIZE - 1;

struct Backend {
    void* user{};
    /// Copies `len` bytes at guest `addr` into `buf`; false (and `buf` untouched or zeroed) when any
    /// byte of the range is unmapped.
    bool (*read)(void* user, u64 addr, void* buf, std::size_t len){};
    bool (*write)(void* user, u64 addr, const void* buf, std::size_t len){};
    /// Host pointer to `len` contiguous bytes at `addr`, or nullptr when unavailable (then the
    /// runtime falls back to read()).
    u8* (*pointer_at)(void* user, u64 addr, std::size_t len){};
};

class Memory {
public:
    Memory() = default;
    explicit Memory(Backend backend_) : backend{backend_} {}

    void SetBackend(Backend backend_) {
        backend = backend_;
    }
    [[nodiscard]] const Backend& GetBackend() const {
        return backend;
    }

    [[nodiscard]] bool IsValidVirtualAddressRange(VAddr base, u64 size) const;
    [[nodiscard]] u8* GetPointerSilent(VAddr vaddr);

    [[nodiscard]] u8 Read8(VAddr addr);
    [[nodiscard]] u16 Read16(VAddr addr);
    [[nodiscard]] u32 Read32(VAddr addr);
    [[nodiscard]] u64 Read64(VAddr addr);
    void Write8(VAddr addr, u8 value);
    void Write16(VAddr addr, u16 value);
    void Write32(VAddr addr, u32 value);
    void Write64(VAddr addr, u64 value);

    bool ReadBlock(VAddr src_addr, void* dest_buffer, std::size_t size);
    bool ReadBlockUnsafe(VAddr src_addr, void* dest_buffer, std::size_t size) {
        return ReadBlock(src_addr, dest_buffer, size);
    }
    bool WriteBlock(VAddr dest_addr, const void* src_buffer, std::size_t size);
    [[nodiscard]] std::string ReadCString(VAddr vaddr, std::size_t max_length);

private:
    Backend backend{};
};

} // namespace Core::Memory
