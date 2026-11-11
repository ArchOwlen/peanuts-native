// PNRT shim for Eden's core/hle/kernel/kernel.h: the owner-process handshake the runtime does
// at construction (RetainApplicationProcess / IsApplicationProcess). There is exactly one guest
// process per Core::System here and it never changes, so the epoch is constant and the check is
// always true.
#pragma once

#include "common/common_types.h"
#include "core/hle/kernel/k_auto_object.h"
#include "core/hle/kernel/k_process.h"

namespace Kernel {

class KernelCore {
public:
    explicit KernelCore(KProcess* process_) : process{process_} {}

    [[nodiscard]] KScopedAutoObject<KProcess> RetainApplicationProcess(u64* owner_epoch = nullptr) {
        if (owner_epoch != nullptr) {
            *owner_epoch = 1;
        }
        return KScopedAutoObject<KProcess>{*this, process};
    }
    [[nodiscard]] bool IsApplicationProcess(const KProcess* p) const {
        return p != nullptr && p == process;
    }
    [[nodiscard]] bool IsApplicationProcess(const KProcess* p, u64 owner_epoch) const {
        return IsApplicationProcess(p) && owner_epoch == 1;
    }

private:
    KProcess* process;
};

} // namespace Kernel
