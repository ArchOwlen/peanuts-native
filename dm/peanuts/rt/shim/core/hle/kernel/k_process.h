// PNRT shim for Eden's core/hle/kernel/k_process.h. Core::System owns one KProcess (the running
// title) whose GetMemory() is the host's guest-memory accessor; the runtime retains it as its
// "owner process" (kernel.h). The heap region can be supplied by the host through the KPageTable
// fields instead of a real page table (0 = unknown: the runtime uses its fallback window).
#pragma once

#include <vector>

#include "common/common_types.h"
#include "core/hle/kernel/svc_types.h"
#include "core/memory.h"

namespace Kernel {

inline u64 GetInteger(u64 v) {
    return v;
}

class KPageTable {
public:
    u64 heap_start{};
    u64 heap_size{};
    u64 normal_memory_size{};
    [[nodiscard]] u64 GetHeapRegionStart() const {
        return heap_start;
    }
    [[nodiscard]] u64 GetHeapRegionSize() const {
        return heap_size;
    }
    [[nodiscard]] u64 GetNormalMemorySize() const {
        return normal_memory_size;
    }
};

class KThread {
public:
    [[nodiscard]] u64 GetThreadId() const {
        return 0;
    }
    [[nodiscard]] Svc::ThreadContext& GetContext() {
        return context;
    }
    [[nodiscard]] const Svc::ThreadContext& GetContext() const {
        return context;
    }
    Svc::ThreadContext context{};
};

class KProcess {
public:
    explicit KProcess(Core::Memory::Memory* memory_ = nullptr) : memory{memory_} {}
    [[nodiscard]] KPageTable& GetPageTable() {
        return page_table;
    }
    [[nodiscard]] const KPageTable& GetPageTable() const {
        return page_table;
    }
    [[nodiscard]] u64 GetProcessId() const {
        return 0;
    }
    [[nodiscard]] const std::vector<KThread>& GetThreadList() const {
        return threads;
    }
    [[nodiscard]] Core::Memory::Memory& GetMemory() const {
        return *memory;
    }
    KPageTable page_table{};
    std::vector<KThread> threads;
    Core::Memory::Memory* memory;
};

} // namespace Kernel

using Kernel::GetInteger;

// The lifted .cpp files reach Core::System through this header in Eden (k_process.h pulls core.h
// in transitively); keep that so they need no include edits. Placed last to break the cycle.
#include "core/core.h"
