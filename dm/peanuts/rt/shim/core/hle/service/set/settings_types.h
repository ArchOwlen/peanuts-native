// PNRT shim for Eden's core/hle/service/set/settings_types.h: the language codes
// mod_nx_runtime.cpp maps the settings language through (msbt language resolution).
#pragma once

#include <array>

#include "common/common_types.h"

namespace Service::Set {

enum class LanguageCode : u64 {
    JA = 0x000000000000616A,
    EN_US = 0x00000053552D6E65,
    FR = 0x0000000000007266,
    DE = 0x0000000000006564,
    IT = 0x0000000000007469,
    ES = 0x0000000000007365,
    ZH_CN = 0x0000004E432D687A,
    KO = 0x0000000000006F6B,
    NL = 0x0000000000006C6E,
    PT = 0x0000000000007470,
    RU = 0x0000000000007572,
    ZH_TW = 0x00000057542D687A,
    EN_GB = 0x00000042472D6E65,
    FR_CA = 0x00000041432D7266,
    ES_419 = 0x00003931342D7365,
    ZH_HANS = 0x00736E61482D687A,
    ZH_HANT = 0x00746E61482D687A,
    PT_BR = 0x00000052422D7470,
    PL = 0x000000000000706C,
    TH = 0x0000000000006874,
};

constexpr std::array<LanguageCode, 20> available_language_codes = {{
    LanguageCode::JA,    LanguageCode::EN_US, LanguageCode::FR,      LanguageCode::DE,
    LanguageCode::IT,    LanguageCode::ES,    LanguageCode::ZH_CN,   LanguageCode::KO,
    LanguageCode::NL,    LanguageCode::PT,    LanguageCode::RU,      LanguageCode::ZH_TW,
    LanguageCode::EN_GB, LanguageCode::FR_CA, LanguageCode::ES_419,  LanguageCode::ZH_HANS,
    LanguageCode::ZH_HANT, LanguageCode::PT_BR, LanguageCode::PL,    LanguageCode::TH,
}};

} // namespace Service::Set
