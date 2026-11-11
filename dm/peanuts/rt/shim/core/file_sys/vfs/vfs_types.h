// PNRT shim for Eden's core/file_sys/vfs/vfs_types.h.
#pragma once

#include <memory>

namespace FileSys {

class VfsDirectory;
class VfsFile;

using VirtualDir = std::shared_ptr<VfsDirectory>;
using VirtualFile = std::shared_ptr<VfsFile>;

} // namespace FileSys
