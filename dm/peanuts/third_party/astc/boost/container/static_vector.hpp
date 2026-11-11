// PNRT: a minimal boost::container::static_vector for Eden's astc.cpp (the only Boost use in the
// lifted set). Fixed capacity, in-place storage, the handful of members astc.cpp calls. The
// options types are accepted and ignored (astc asks for no-throw overflow: it never overflows).
#pragma once

#include <cstddef>
#include <new>
#include <utility>

namespace boost::container {

template <std::size_t A>
struct inplace_alignment {};
template <bool B>
struct throw_on_overflow {};
template <typename... Opts>
struct static_vector_options {
    using type = static_vector_options<Opts...>;
};

template <typename T, std::size_t N, typename Options = void>
class static_vector {
public:
    static_vector() = default;
    ~static_vector() {
        clear();
    }
    static_vector(const static_vector& o) {
        for (const auto& v : o) push_back(v);
    }
    static_vector& operator=(const static_vector& o) {
        if (this != &o) {
            clear();
            for (const auto& v : o) push_back(v);
        }
        return *this;
    }

    template <typename... Args>
    T& emplace_back(Args&&... args) {
        T* const p = new (Slot(count)) T(std::forward<Args>(args)...);
        ++count;
        return *p;
    }
    void push_back(const T& v) {
        emplace_back(v);
    }
    void clear() {
        for (std::size_t i = 0; i < count; ++i) Slot(i)->~T();
        count = 0;
    }
    [[nodiscard]] std::size_t size() const {
        return count;
    }
    [[nodiscard]] bool empty() const {
        return count == 0;
    }
    static constexpr std::size_t capacity() {
        return N;
    }
    T& operator[](std::size_t i) {
        return *Slot(i);
    }
    const T& operator[](std::size_t i) const {
        return *Slot(i);
    }
    T* begin() {
        return Slot(0);
    }
    T* end() {
        return Slot(count);
    }
    const T* begin() const {
        return Slot(0);
    }
    const T* end() const {
        return Slot(count);
    }
    T* data() {
        return Slot(0);
    }
    const T* data() const {
        return Slot(0);
    }
    T& back() {
        return *Slot(count - 1);
    }

private:
    T* Slot(std::size_t i) {
        return reinterpret_cast<T*>(storage) + i;
    }
    const T* Slot(std::size_t i) const {
        return reinterpret_cast<const T*>(storage) + i;
    }
    alignas(T) unsigned char storage[N * sizeof(T)];
    std::size_t count{0};
};

} // namespace boost::container
