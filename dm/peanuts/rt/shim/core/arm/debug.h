// PNRT shim for Eden's core/arm/debug.h: the guest bridge (not lifted) patches code and flushes
// the JIT's instruction cache; the one call left in a lifted file (RefuseChangedProcess, with an
// empty patched_original) is a no-op here.
#pragma once

#include <cstddef>

#include "common/common_types.h"

namespace Kernel {
class KProcess;
}

namespace Core {

class ArmInterface;

inline void InvalidateInstructionCacheRange(Kernel::KProcess*, u64, u64) {}

} // namespace Core
