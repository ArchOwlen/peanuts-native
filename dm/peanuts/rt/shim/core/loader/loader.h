// PNRT shim for Eden's core/loader/loader.h: System::GetAppLoader().ReadIcon() (the "icon:" image
// source of the idle page). The host may supply the title's icon JPEG bytes; empty = none.
#pragma once

#include <vector>

#include "common/common_types.h"
#include "core/file_sys/vfs/vfs_types.h"

namespace Loader {

enum class ResultStatus { Success, ErrorNotImplemented };

class AppLoader {
public:
    ResultStatus ReadIcon(std::vector<u8>& out) const {
        out = icon_jpeg;
        return icon_jpeg.empty() ? ResultStatus::ErrorNotImplemented : ResultStatus::Success;
    }
    [[nodiscard]] FileSys::VirtualFile GetFile() const {
        return nullptr;
    }
    std::vector<u8> icon_jpeg;
};

} // namespace Loader
