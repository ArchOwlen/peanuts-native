// PNRT shim for Eden's common/scope_exit.h: SCOPE_EXIT { ... }; runs the block at scope end.
#pragma once

#include <utility>

namespace detail {
template <typename F>
class ScopeExitGuard {
public:
    explicit ScopeExitGuard(F&& f) : fn{std::move(f)} {}
    ~ScopeExitGuard() {
        fn();
    }
    ScopeExitGuard(const ScopeExitGuard&) = delete;
    ScopeExitGuard& operator=(const ScopeExitGuard&) = delete;

private:
    F fn;
};
struct ScopeExitTag {};
template <typename F>
ScopeExitGuard<F> operator+(ScopeExitTag, F&& f) {
    return ScopeExitGuard<F>{std::forward<F>(f)};
}
} // namespace detail

#define PNRT_CONCAT_INNER(a, b) a##b
#define PNRT_CONCAT(a, b) PNRT_CONCAT_INNER(a, b)
#define SCOPE_EXIT auto PNRT_CONCAT(scope_exit_state_, __LINE__) = ::detail::ScopeExitTag{} + [&]()
