// PNRT shim for Eden's common/swap.h: swap32 (the shared-font decrypt, mod_sysfont_source.cpp).
#pragma once

#include "common/common_types.h"

namespace Common {

[[nodiscard]] inline u16 swap16(u16 v) {
    return __builtin_bswap16(v);
}
[[nodiscard]] inline u32 swap32(u32 v) {
    return __builtin_bswap32(v);
}
[[nodiscard]] inline u64 swap64(u64 v) {
    return __builtin_bswap64(v);
}

} // namespace Common
