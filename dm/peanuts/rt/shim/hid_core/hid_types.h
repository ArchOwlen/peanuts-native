// PNRT shim for Eden's hid_core/hid_types.h: NpadIdType, NpadButton (the bit names mod_nav.h
// maps chord names onto) and AnalogStickState, as Eden defines them.
#pragma once

#include "common/common_types.h"

namespace Core::HID {

enum class NpadIdType : u32 {
    Player1 = 0x0,
    Player2 = 0x1,
    Player3 = 0x2,
    Player4 = 0x3,
    Player5 = 0x4,
    Player6 = 0x5,
    Player7 = 0x6,
    Player8 = 0x7,
    Other = 0x10,
    Handheld = 0x20,
    Invalid = 0xFFFFFFFF,
};

enum class NpadButton : u64 {
    None = 0,
    A = 1U << 0,
    B = 1U << 1,
    X = 1U << 2,
    Y = 1U << 3,
    StickL = 1U << 4,
    StickR = 1U << 5,
    L = 1U << 6,
    R = 1U << 7,
    ZL = 1U << 8,
    ZR = 1U << 9,
    Plus = 1U << 10,
    Minus = 1U << 11,
    Left = 1U << 12,
    Up = 1U << 13,
    Right = 1U << 14,
    Down = 1U << 15,
    StickLLeft = 1U << 16,
    StickLUp = 1U << 17,
    StickLRight = 1U << 18,
    StickLDown = 1U << 19,
    StickRLeft = 1U << 20,
    StickRUp = 1U << 21,
    StickRRight = 1U << 22,
    StickRDown = 1U << 23,
    LeftSL = 1U << 24,
    LeftSR = 1U << 25,
    RightSL = 1U << 26,
    RightSR = 1U << 27,
    Palma = 1U << 28,
    Verification = 1U << 29,
    HandheldLeftB = 1U << 30,
    LagonCLeft = 1U << 31,
    LagonCUp = 1ULL << 32,
    LagonCRight = 1ULL << 33,
    LagonCDown = 1ULL << 34,
    All = 0xFFFFFFFFFFFFFFFFULL,
};

struct NpadButtonState {
    u64 raw{};
};

struct AnalogStickState {
    s32 x{};
    s32 y{};
};

} // namespace Core::HID
