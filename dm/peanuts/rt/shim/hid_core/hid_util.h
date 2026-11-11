// PNRT shim for Eden's hid_core/hid_util.h: NpadIdTypeToIndex (the pad gate's per-slot latch).
#pragma once

#include <cstddef>

#include "hid_core/hid_types.h"

namespace Service::HID {

constexpr size_t NpadIdTypeToIndex(Core::HID::NpadIdType npad_id_type) {
    switch (npad_id_type) {
    case Core::HID::NpadIdType::Player1:
        return 0;
    case Core::HID::NpadIdType::Player2:
        return 1;
    case Core::HID::NpadIdType::Player3:
        return 2;
    case Core::HID::NpadIdType::Player4:
        return 3;
    case Core::HID::NpadIdType::Player5:
        return 4;
    case Core::HID::NpadIdType::Player6:
        return 5;
    case Core::HID::NpadIdType::Player7:
        return 6;
    case Core::HID::NpadIdType::Player8:
        return 7;
    case Core::HID::NpadIdType::Handheld:
        return 8;
    case Core::HID::NpadIdType::Other:
        return 9;
    default:
        return 8;
    }
}

} // namespace Service::HID
