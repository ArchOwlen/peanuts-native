// PNRT shim for Eden's input_common/drivers/virtual_gamepad.h: the virtual pad the runtime's
// actions press. Forwards to a PnPad sink (function pointers) owned by the host; no-op when unset.
#pragma once

#include <cstddef>
#include <string>

#include "common/common_types.h"

namespace InputCommon {

struct PadSink {
    void* user{};
    void (*set_button)(void* user, std::size_t player, int button, bool pressed){};
    void (*set_stick)(void* user, std::size_t player, int stick, float x, float y){};
};

class VirtualGamepad final {
public:
    enum class VirtualButton {
        ButtonA, ButtonB, ButtonX, ButtonY, StickL, StickR, TriggerL, TriggerR, TriggerZL,
        TriggerZR, ButtonPlus, ButtonMinus, ButtonLeft, ButtonUp, ButtonRight, ButtonDown,
        ButtonSL, ButtonSR, ButtonHome, ButtonCapture,
    };
    enum class VirtualStick { Left = 0, Right = 1 };

    explicit VirtualGamepad(std::string = "virtual_gamepad") {}

    void SetSink(PadSink sink_) {
        sink = sink_;
    }
    void SetButtonState(std::size_t player_index, int button_id, bool value) {
        if (sink.set_button) sink.set_button(sink.user, player_index, button_id, value);
    }
    void SetButtonState(std::size_t player_index, VirtualButton button_id, bool value) {
        SetButtonState(player_index, static_cast<int>(button_id), value);
    }
    void SetStickPosition(std::size_t player_index, int axis_id, float x_value, float y_value) {
        if (sink.set_stick) sink.set_stick(sink.user, player_index, axis_id, x_value, y_value);
    }
    void SetStickPosition(std::size_t player_index, VirtualStick axis_id, float x_value,
                          float y_value) {
        SetStickPosition(player_index, static_cast<int>(axis_id), x_value, y_value);
    }
    void ResetControllers() {}

private:
    PadSink sink{};
};

/// System::GetInputSubsystem() in Eden; here just the owner of the one virtual pad.
class InputSubsystem {
public:
    [[nodiscard]] VirtualGamepad* GetVirtualGamepad() {
        return &pad;
    }
    VirtualGamepad pad;
};

} // namespace InputCommon
