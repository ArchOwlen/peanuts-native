// PNRT shim for Eden's common/alignment.h (astc.cpp uses DivideUp).
#pragma once

#include <type_traits>

namespace Common {

template <typename T, typename U>
[[nodiscard]] constexpr T DivideUp(T x, U y) {
    return (x + (y - 1)) / y;
}
template <typename T>
[[nodiscard]] constexpr T AlignUp(T value, std::size_t size) {
    const auto mod = static_cast<T>(value % size);
    value -= mod;
    return static_cast<T>(mod == T{0} ? value : value + size);
}
template <typename T>
[[nodiscard]] constexpr T AlignDown(T value, std::size_t size) {
    return static_cast<T>(value - value % size);
}

} // namespace Common
