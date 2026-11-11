// PNRT shim for Eden's common/hex_util.h.
#pragma once

#include <array>
#include <string>
#include <string_view>
#include <vector>

#include "common/common_types.h"

namespace Common {

[[nodiscard]] inline u8 HexCharToValue(char c) {
    if (c >= '0' && c <= '9') return static_cast<u8>(c - '0');
    if (c >= 'a' && c <= 'f') return static_cast<u8>(c - 'a' + 10);
    if (c >= 'A' && c <= 'F') return static_cast<u8>(c - 'A' + 10);
    return 0;
}

[[nodiscard]] inline std::vector<u8> HexStringToVector(std::string_view str, bool little_endian) {
    std::vector<u8> out(str.size() / 2);
    if (little_endian) {
        for (size_t i = str.size() / 2; i > 0; --i) {
            const size_t j = (i - 1) * 2;
            out[str.size() / 2 - i] =
                static_cast<u8>((HexCharToValue(str[j]) << 4) | HexCharToValue(str[j + 1]));
        }
    } else {
        for (size_t i = 0; i + 1 < str.size(); i += 2) {
            out[i / 2] = static_cast<u8>((HexCharToValue(str[i]) << 4) | HexCharToValue(str[i + 1]));
        }
    }
    return out;
}

template <typename ContiguousContainer>
[[nodiscard]] std::string HexToString(const ContiguousContainer& data, bool upper = true) {
    static constexpr char lower_digits[] = "0123456789abcdef";
    static constexpr char upper_digits[] = "0123456789ABCDEF";
    const char* const digits = upper ? upper_digits : lower_digits;
    std::string out;
    out.reserve(data.size() * 2);
    for (const u8 byte : data) {
        out.push_back(digits[byte >> 4]);
        out.push_back(digits[byte & 0xF]);
    }
    return out;
}

} // namespace Common
