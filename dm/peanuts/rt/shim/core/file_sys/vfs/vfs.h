// PNRT shim for Eden's core/file_sys/vfs/vfs.h: the slice of the VFS the lifted DSMod code uses,
// implemented over std::filesystem (shim/shim.cpp). A VirtualDir is a real directory on disk;
// MakeSyntheticDir builds a root whose children are explicitly listed (the host hands the
// runtime one package directory as the "mod load root" instead of a load/<title>/ tree).
#pragma once

#include <cstddef>
#include <filesystem>
#include <string>
#include <string_view>
#include <vector>

#include "common/common_types.h"
#include "core/file_sys/vfs/vfs_types.h"

struct PnRtRomfs; // peanuts_rt.h: the host's RomFS callbacks (R6)

namespace FileSys {

class VfsFile {
public:
    virtual ~VfsFile() = default;
    [[nodiscard]] virtual std::string GetName() const = 0;
    [[nodiscard]] virtual std::size_t GetSize() const = 0;
    /// Reads up to `length` bytes at `offset`; returns the byte count actually read.
    virtual std::size_t Read(u8* data, std::size_t length, std::size_t offset) const = 0;
    [[nodiscard]] virtual VirtualDir GetContainingDirectory() const = 0;
    // The write side of Eden's VfsFile, so lifted subclasses (SerialRomfsFile, VectorVfsFile)
    // can override it; the host files here are read-only.
    virtual bool Resize(std::size_t) {
        return false;
    }
    [[nodiscard]] virtual bool IsWritable() const {
        return false;
    }
    [[nodiscard]] virtual bool IsReadable() const {
        return true;
    }
    virtual std::size_t Write(const u8*, std::size_t, std::size_t) {
        return 0;
    }
    virtual bool Rename(std::string_view) {
        return false;
    }
    [[nodiscard]] std::vector<u8> ReadBytes(std::size_t size, std::size_t offset = 0) const;
    [[nodiscard]] std::vector<u8> ReadAllBytes() const;
};

class VfsDirectory {
public:
    virtual ~VfsDirectory() = default;
    [[nodiscard]] virtual std::string GetName() const = 0;
    [[nodiscard]] virtual std::vector<VirtualFile> GetFiles() const = 0;
    [[nodiscard]] virtual std::vector<VirtualDir> GetSubdirectories() const = 0;
    [[nodiscard]] virtual VirtualFile GetFile(std::string_view name) const = 0;
    [[nodiscard]] virtual VirtualDir GetSubdirectory(std::string_view name) const = 0;
    /// The directory's path on disk (diagnostics only); the name for a synthetic root.
    [[nodiscard]] virtual std::string GetFullPath() const {
        return GetName();
    }
    /// "a/b/c.png" relative to this directory (nullptr when missing).
    [[nodiscard]] VirtualFile GetFileRelative(std::string_view path) const;
    [[nodiscard]] VirtualDir GetDirectoryRelative(std::string_view path) const;
};

/// A directory on disk; nullptr when `path` is not a directory.
[[nodiscard]] VirtualDir OpenHostDirectory(const std::filesystem::path& path);
/// A file on disk; nullptr when `path` is not a regular file.
[[nodiscard]] VirtualFile OpenHostFile(const std::filesystem::path& path);
/// A root directory with exactly these subdirectories (each opened from disk; missing ones are
/// skipped) and no files.
[[nodiscard]] VirtualDir MakeSyntheticDir(std::string name,
                                          const std::vector<std::filesystem::path>& children);
/// R6: the root of a RomFS served by host callbacks (PnRtRomfs, copied). Files read ranges on
/// demand through `read`; a file stays open (handle held) for the VfsFile's life. Directories
/// enumerate through `list` when the provider has one (else they are empty and every
/// GetSubdirectory is nullptr -- only GetFile / GetFileRelative work). nullptr when `open` or
/// `read` is missing.
[[nodiscard]] VirtualDir OpenProviderDirectory(const PnRtRomfs& provider);

} // namespace FileSys
