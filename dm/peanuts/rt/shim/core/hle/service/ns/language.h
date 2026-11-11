// PNRT shim for Eden's core/hle/service/ns/language.h. Same enum and conversion as Eden; the
// per-language priority lists are reduced to "the language itself, then American English, then
// Japanese" (Eden's full tables are 18 x 18 entries; the game's NACP support mask then picks).
#pragma once

#include <array>
#include <cstddef>
#include <optional>

#include "common/common_types.h"
#include "core/hle/service/set/settings_types.h"

namespace Service::NS {

enum class ApplicationLanguage : u8 {
    AmericanEnglish = 0,
    BritishEnglish,
    Japanese,
    French,
    German,
    LatinAmericanSpanish,
    Spanish,
    Italian,
    Dutch,
    CanadianFrench,
    Portuguese,
    Russian,
    Korean,
    TraditionalChinese,
    SimplifiedChinese,
    BrazilianPortuguese,
    Polish,
    Thai,
    Count
};
using ApplicationLanguagePriorityList = const std::array<ApplicationLanguage, 3>;

constexpr u32 GetSupportedLanguageFlag(const ApplicationLanguage lang) {
    return 1U << static_cast<u32>(lang);
}

inline const ApplicationLanguagePriorityList* GetApplicationLanguagePriorityList(
    ApplicationLanguage lang) {
    static std::array<std::array<ApplicationLanguage, 3>,
                      static_cast<size_t>(ApplicationLanguage::Count)>
        lists = [] {
            std::array<std::array<ApplicationLanguage, 3>,
                       static_cast<size_t>(ApplicationLanguage::Count)>
                out{};
            for (size_t i = 0; i < out.size(); ++i) {
                out[i] = {static_cast<ApplicationLanguage>(i), ApplicationLanguage::AmericanEnglish,
                          ApplicationLanguage::Japanese};
            }
            return out;
        }();
    const auto i = static_cast<size_t>(lang);
    return i < lists.size() ? &lists[i] : nullptr;
}

inline std::optional<ApplicationLanguage> ConvertToApplicationLanguage(
    Set::LanguageCode language_code) {
    using L = Set::LanguageCode;
    using A = ApplicationLanguage;
    switch (language_code) {
    case L::EN_US: return A::AmericanEnglish;
    case L::EN_GB: return A::BritishEnglish;
    case L::JA: return A::Japanese;
    case L::FR: return A::French;
    case L::DE: return A::German;
    case L::ES_419: return A::LatinAmericanSpanish;
    case L::ES: return A::Spanish;
    case L::IT: return A::Italian;
    case L::NL: return A::Dutch;
    case L::FR_CA: return A::CanadianFrench;
    case L::PT: return A::Portuguese;
    case L::RU: return A::Russian;
    case L::KO: return A::Korean;
    case L::ZH_TW:
    case L::ZH_HANT: return A::TraditionalChinese;
    case L::ZH_CN:
    case L::ZH_HANS: return A::SimplifiedChinese;
    case L::PT_BR: return A::BrazilianPortuguese;
    case L::TH: return A::Thai;
    case L::PL: return A::Polish;
    default: return std::nullopt;
    }
}

} // namespace Service::NS
