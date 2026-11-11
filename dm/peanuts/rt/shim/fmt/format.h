// PNRT shim for <fmt/format.h>: fmt::format over std::format (C++20). Only fmt::format is used by
// the lifted DSMod files (40 call sites, all with literal format strings).
#pragma once

#include <format>
#include <string>
#include <string_view>
#include <type_traits>
#include <utility>

// Enum classes format as their underlying integer (Eden's common/logging.h adds the same
// formatter for fmt).
template <typename T>
    requires std::is_enum_v<T>
struct std::formatter<T, char> : std::formatter<std::underlying_type_t<T>, char> {
    template <typename FormatContext>
    auto format(const T& value, FormatContext& ctx) const {
        return std::formatter<std::underlying_type_t<T>, char>::format(
            static_cast<std::underlying_type_t<T>>(value), ctx);
    }
};

namespace fmt {

template <typename... Args>
std::string format(std::format_string<Args...> fmt_str, Args&&... args) {
    return std::format(fmt_str, std::forward<Args>(args)...);
}

template <typename... Args>
using format_string = std::format_string<Args...>;

} // namespace fmt
