// PNRT: a package's load plan (mailbox, patches of main, guest helper code) applied to the running game: peanuts_helper.cpp
#pragma once

#include <array>
#include <string>

#include "common/common_types.h"
#include "peanuts_rt.h"

namespace Core {
class System;
}

namespace PeanutsHelper {

struct Result {
    bool installed{};  ///< the plan is in place and the mailbox is set on the system
    std::string error; ///< why not, when the package has a plan for this title; empty when it has none
};

/// Reads the selected package's load plan for this build of main and applies it to the running process.
[[nodiscard]] Result Install(Core::System& system, const PnRtConfig& config, const std::array<u8, 0x20>& build_id);

} // namespace PeanutsHelper
