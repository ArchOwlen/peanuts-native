// PNRT shim for Eden's common/cityhash.h. The lifted code uses CityHash64 only as a content hash
// (HUD overlay change detection), so any good 64-bit hash serves; this is a 64-bit FNV-1a over
// 8-byte words with a final avalanche. NOT CityHash-compatible (no persisted hashes depend on it).
#pragma once

#include <cstddef>

#include "common/common_types.h"

namespace Common {

[[nodiscard]] u64 CityHash64(const char* buf, size_t len);
[[nodiscard]] u64 CityHash64WithSeed(const char* buf, size_t len, u64 seed);

} // namespace Common
