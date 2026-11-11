// PNRT shim for Eden's hid_core/frontend/emulated_controller.h: PadIsPushed() (mod_state.cpp)
// and the controller focus mode (mod_nav.cpp ReadNavPad / LatchNavPad) ask the emulated
// controllers for their buttons and sticks. The host does not expose the real pad yet, so
// HIDCore::GetEmulatedController() returns nullptr and every caller takes its "no controller"
// branch (not pushed / no buttons).
#pragma once

#include "common/common_types.h"
#include "hid_core/hid_types.h"

namespace Core::HID {

class EmulatedController {
public:
    struct Sticks {
        AnalogStickState left;
        AnalogStickState right;
    };
    [[nodiscard]] bool IsConnected() const {
        return false;
    }
    [[nodiscard]] Sticks GetSticks() const {
        return {};
    }
    [[nodiscard]] NpadButtonState GetNpadButtons() const {
        return {};
    }
};

class HIDCore {
public:
    [[nodiscard]] EmulatedController* GetEmulatedController(NpadIdType) {
        return nullptr;
    }
    [[nodiscard]] const EmulatedController* GetEmulatedController(NpadIdType) const {
        return nullptr;
    }
};

} // namespace Core::HID
