// PNRT shim for Eden's common/common_types.h: the fixed-width aliases the lifted DSMod code uses.
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

using u8 = std::uint8_t;
using u16 = std::uint16_t;
using u32 = std::uint32_t;
using u64 = std::uint64_t;
using u128 = std::array<std::uint64_t, 2>;

using s8 = std::int8_t;
using s16 = std::int16_t;
using s32 = std::int32_t;
using s64 = std::int64_t;

using f32 = float;
using f64 = double;

using VAddr = u64;
using PAddr = u64;
using GPUVAddr = u64;
