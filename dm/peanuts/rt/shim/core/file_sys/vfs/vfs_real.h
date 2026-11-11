// PNRT shim for Eden's core/file_sys/vfs/vfs_real.h: RealVfsFilesystem::OpenDirectory over the
// std::filesystem VFS (the "user:" source's folder, mod_user_source.cpp).
#pragma once

#include <string_view>

#include "core/file_sys/vfs/vfs.h"

namespace FileSys {

enum class OpenMode : u32 {
    Read = 1,
    Write = 2,
    ReadWrite = Read | Write,
    AllowAppend = 4,
    All = ReadWrite | AllowAppend,
};

class RealVfsFilesystem {
public:
    [[nodiscard]] VirtualDir OpenDirectory(std::string_view path, OpenMode) {
        return OpenHostDirectory(std::filesystem::path{std::string{path}});
    }
};

} // namespace FileSys
