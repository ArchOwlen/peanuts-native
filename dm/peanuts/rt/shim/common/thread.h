// PNRT shim for Eden's common/thread.h.
#pragma once

#include "common/common_types.h"

namespace Common {

enum class ThreadPriority : u32 { Low = 0, Normal = 1, High = 2, VeryHigh = 3, Critical = 4 };

void SetCurrentThreadPriority(ThreadPriority new_priority);
void SetCurrentThreadName(const char* name);

} // namespace Common
