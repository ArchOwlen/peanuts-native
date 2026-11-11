// PNRT shim for Eden's core/hle/kernel/svc_types.h: ThreadContext (the guest bridge's saved
// register file; the bridge is stubbed, the member just has to exist).
#pragma once

#include <array>

#include "common/common_types.h"

namespace Kernel::Svc {

struct ThreadContext {
    std::array<u64, 29> r{};
    u64 fp{};
    u64 lr{};
    u64 sp{};
    u64 pc{};
    u32 pstate{};
    u32 padding{};
    std::array<u128, 32> v{};
    u32 fpcr{};
    u32 fpsr{};
    u64 tpidr{};
};

} // namespace Kernel::Svc
