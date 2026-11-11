// PNRT shim for Eden's core/file_sys/vfs/vfs_vector.h: VectorVfsFile, a read-only file over a
// std::vector<u8> (mod_nx_runtime.cpp wraps decoded SARC members in it).
#pragma once

#include <algorithm>
#include <cstring>
#include <string>
#include <utility>
#include <vector>

#include "core/file_sys/vfs/vfs.h"

namespace FileSys {

class VectorVfsFile final : public VfsFile {
public:
    explicit VectorVfsFile(std::vector<u8> initial_data = {}, std::string name_ = "",
                           VirtualDir parent_ = nullptr)
        : data{std::move(initial_data)}, name{std::move(name_)}, parent{std::move(parent_)} {}

    std::string GetName() const override {
        return name;
    }
    std::size_t GetSize() const override {
        return data.size();
    }
    bool Resize(std::size_t new_size) override {
        data.resize(new_size);
        return true;
    }
    VirtualDir GetContainingDirectory() const override {
        return parent;
    }
    bool IsWritable() const override {
        return true;
    }
    bool IsReadable() const override {
        return true;
    }
    std::size_t Read(u8* out, std::size_t length, std::size_t offset) const override {
        if (offset >= data.size()) {
            return 0;
        }
        const std::size_t n = std::min(length, data.size() - offset);
        std::memcpy(out, data.data() + offset, n);
        return n;
    }
    std::size_t Write(const u8* in, std::size_t length, std::size_t offset) override {
        if (offset + length > data.size()) {
            data.resize(offset + length);
        }
        std::memcpy(data.data() + offset, in, length);
        return length;
    }
    bool Rename(std::string_view new_name) override {
        name = std::string{new_name};
        return true;
    }

private:
    std::vector<u8> data;
    std::string name;
    VirtualDir parent;
};

} // namespace FileSys
