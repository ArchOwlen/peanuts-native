// PNRT shim for Eden's common/fs/fs_util.h: PathToUTF8String.
#pragma once

#include <filesystem>
#include <string>

namespace Common::FS {

[[nodiscard]] inline std::string PathToUTF8String(const std::filesystem::path& path) {
    return path.string();
}

} // namespace Common::FS
