// PNRT shim for Eden's core/file_sys/patch_manager.h: only GetMetadataFromBaseOrUpdate is used
// (mod_nx_runtime.cpp, msbt language); there is no NCA/NACP here, so it yields "no metadata".
#pragma once

#include <memory>
#include <utility>

#include "common/common_types.h"
#include "core/file_sys/control_metadata.h"
#include "core/file_sys/vfs/vfs_types.h"

namespace Core {
class System;
}

namespace FileSys {

class PatchManager {
public:
    using Metadata = std::pair<std::unique_ptr<NACP>, VirtualFile>;
    [[nodiscard]] static Metadata GetMetadataFromBaseOrUpdate(Core::System&, u64) noexcept {
        return {nullptr, nullptr};
    }
};

} // namespace FileSys
