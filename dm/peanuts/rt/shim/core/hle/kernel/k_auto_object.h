// PNRT shim for Eden's core/hle/kernel/k_auto_object.h: KScopedAutoObject<T> is a plain pointer
// holder here (no reference counting: the shim KProcess / KThread live inside Core::System and
// outlive every ModRuntime). Only the members the lifted runtime uses.
#pragma once

#include <utility>

namespace Kernel {

class KernelCore;

template <typename T>
class KScopedAutoObject {
public:
    explicit KScopedAutoObject(KernelCore&) {}
    KScopedAutoObject(KernelCore&, T* o) : object{o} {}
    KScopedAutoObject(KScopedAutoObject&& rhs) noexcept : object{rhs.object} {
        rhs.object = nullptr;
    }
    KScopedAutoObject& operator=(KScopedAutoObject&& rhs) noexcept {
        object = rhs.object;
        rhs.object = nullptr;
        return *this;
    }
    KScopedAutoObject(const KScopedAutoObject&) = delete;
    KScopedAutoObject& operator=(const KScopedAutoObject&) = delete;
    ~KScopedAutoObject() = default;

    [[nodiscard]] T* operator->() const {
        return object;
    }
    [[nodiscard]] T& operator*() const {
        return *object;
    }
    [[nodiscard]] T* GetPointerUnsafe() const {
        return object;
    }
    void SetObject(T* o) {
        object = o;
    }
    void Reset(T* o) {
        object = o;
    }
    [[nodiscard]] bool IsNull() const {
        return object == nullptr;
    }
    [[nodiscard]] bool IsNotNull() const {
        return object != nullptr;
    }

private:
    T* object{nullptr};
};

} // namespace Kernel
