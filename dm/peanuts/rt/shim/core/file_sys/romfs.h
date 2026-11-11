// PNRT shim for Eden's core/file_sys/romfs.h: ExtractRomFS parses a romfs image into a directory
// tree. There is no romfs image here (the host hands over an extracted directory, System::
// RomfsRoot()); the one remaining caller (mod_romfs_sources.cpp's Extract helper) is only reached
// from code compiled out under PNRT, so this yields "nothing".
#pragma once

#include "core/file_sys/vfs/vfs_types.h"

namespace FileSys {

[[nodiscard]] inline VirtualDir ExtractRomFS(VirtualFile) {
    return nullptr;
}

} // namespace FileSys
