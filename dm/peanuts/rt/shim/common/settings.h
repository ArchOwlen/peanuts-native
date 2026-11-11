// PNRT shim for Eden's common/settings.h: the members the lifted files read.
#pragma once

#include <array>
#include <map>
#include <string>
#include <string_view>
#include <vector>

#include "common/common_types.h"

namespace Settings {

/// Eden's Language enum (common/settings_enums.h); the index order matches
/// Service::Set::available_language_codes.
enum class Language : u32 {
    Japanese, EnglishAmerican, French, German, Italian, Spanish, Chinese, Korean, Dutch,
    Portuguese, Russian, Taiwanese, EnglishBritish, FrenchCanadian, SpanishLatin,
    ChineseSimplified, ChineseTraditional, PortugueseBrazilian, Polish, Thai,
};

inline std::string_view CanonicalizeEnum(Language id) {
    static constexpr std::array<std::string_view, 20> names{
        "Japanese", "EnglishAmerican", "French", "German", "Italian", "Spanish", "Chinese",
        "Korean", "Dutch", "Portuguese", "Russian", "Taiwanese", "EnglishBritish",
        "FrenchCanadian", "SpanishLatin", "ChineseSimplified", "ChineseTraditional",
        "PortugueseBrazilian", "Polish", "Thai"};
    const auto i = static_cast<size_t>(id);
    return i < names.size() ? names[i] : "unknown";
}

template <typename T>
struct Setting {
    T value{};
    [[nodiscard]] T GetValue() const {
        return value;
    }
    void SetValue(T v) {
        value = v;
    }
};

struct Values {
    /// title id -> mod directory names the user disabled. Always empty in Peanuts.
    std::map<u64, std::vector<std::string>> disabled_addons;
    /// The system language the game is given (msbt text language). Set from PnRtConfig.
    Setting<Language> language_index{Language::EnglishAmerican};
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
