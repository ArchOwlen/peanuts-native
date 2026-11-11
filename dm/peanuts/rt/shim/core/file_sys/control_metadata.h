// PNRT shim for Eden's core/file_sys/control_metadata.h: the NACP's supported-language mask.
// The host does not read the NACP (0 = "every language supported" in ResolveMsbtLanguage).
#pragma once

#include "common/common_types.h"

namespace FileSys {

class NACP {
public:
    [[nodiscard]] u32 GetSupportedLanguages() const {
        return supported_languages;
    }
    u32 supported_languages{0};
};

} // namespace FileSys
