// PNRT shim for Eden's hid_core: PadIsPushed() (mod_state.cpp) asks player 1's emulated controller
// for its sticks. The host does not expose the real pad yet, so GetEmulatedController() returns
// nullptr and the caller takes its "not pushed" branch.
#pragma once

#include "common/common_types.h"

namespace Core::HID {

enum class NpadIdType : u32 { Player1 = 0, Player2, Player3, Player4, Player5, Player6, Player7, Player8, Other, Handheld, Invalid };

class EmulatedController {
public:
    struct Stick {
        s32 x{};
        s32 y{};
    };
    struct Sticks {
        Stick left;
        Stick right;
    };
    struct Buttons {
        u64 raw{};
    };
    [[nodiscard]] Sticks GetSticks() const {
        return {};
    }
    [[nodiscard]] Buttons GetNpadButtons() const {
        return {};
    }
};

class HIDCore {
public:
    [[nodiscard]] EmulatedController* GetEmulatedController(NpadIdType) {
        return nullptr;
    }
};

} // namespace Core::HID
