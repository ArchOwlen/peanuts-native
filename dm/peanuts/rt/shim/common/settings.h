// PNRT shim for Eden's common/settings.h: the two members the lifted files read.
#pragma once

#include <map>
#include <string>
#include <vector>

#include "common/common_types.h"

namespace Settings {

struct Values {
    /// title id -> mod directory names the user disabled. Always empty in Peanuts.
    std::map<u64, std::vector<std::string>> disabled_addons;
};
extern Values values;

/// Peanuts runs the guest natively: there is no Dynarmic BRK bridge, so this is always true (the
/// runtime then disables guest calls, sequences and spies exactly as it does under Eden's NCE).
inline bool IsNceEnabled() {
    return true;
}
inline bool IsDockedMode() {
    return false;
}

} // namespace Settings
