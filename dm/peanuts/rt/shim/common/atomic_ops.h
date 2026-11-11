// PNRT shim for Eden's common/atomic_ops.h: AtomicCompareAndSwap over __atomic builtins (the
// module host's guest-mailbox loads/stores, mod_module_services.cpp).
#pragma once

#include "common/common_types.h"

namespace Common {

template <typename T>
[[nodiscard]] inline bool AtomicCompareAndSwap(T* pointer, T value, T expected) {
    return __atomic_compare_exchange_n(pointer, &expected, value, false, __ATOMIC_SEQ_CST,
                                       __ATOMIC_SEQ_CST);
}

template <typename T>
[[nodiscard]] inline bool AtomicCompareAndSwap(T* pointer, T value, T expected, T& actual) {
    const bool ok = __atomic_compare_exchange_n(pointer, &expected, value, false, __ATOMIC_SEQ_CST,
                                                __ATOMIC_SEQ_CST);
    actual = expected;
    return ok;
}

} // namespace Common
