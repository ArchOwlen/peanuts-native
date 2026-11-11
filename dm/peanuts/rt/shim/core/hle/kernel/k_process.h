// PNRT shim for Eden's core/hle/kernel/k_process.h. System::ApplicationProcess() returns nullptr
// in Peanuts (every lifted use null-checks first); the class exists so those call sites compile.
// The heap region can be supplied by the host through KProcess fields instead of a page table.
#pragma once

#include <vector>

#include "common/common_types.h"
#include "core/hle/kernel/svc_types.h"

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
    [[nodiscard]] const Svc::ThreadContext& GetContext() const {
        return context;
    }
    Svc::ThreadContext context{};
};

class KProcess {
public:
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
    KPageTable page_table{};
    std::vector<KThread> threads;
};

} // namespace Kernel

using Kernel::GetInteger;

// The lifted .cpp files reach Core::System through this header in Eden (k_process.h pulls core.h
// in transitively); keep that so they need no include edits. Placed last to break the cycle.
#include "core/core.h"
