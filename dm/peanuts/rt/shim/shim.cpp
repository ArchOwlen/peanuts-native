// PNRT shim implementations: logging sink, thread helpers, content hash, the std::filesystem VFS
// and the pluggable guest-memory accessor.

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <memory>
#include <string>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#endif
#ifdef __linux__
#include <pthread.h>
#include <sys/resource.h>
#include <unistd.h>
#endif

#include "common/cityhash.h"
#include "common/logging.h"
#include "common/settings.h"
#include "common/thread.h"
#include "core/file_sys/vfs/vfs.h"
#include "core/memory.h"
#include "peanuts_rt.h" // PnRtRomfs (R6: OpenProviderDirectory)

namespace Settings {
Values values;
}

// --- logging ------------------------------------------------------------------------------------
namespace Common::Log {
namespace {
void DefaultSink(int level, const char* where, const char* message) {
    static constexpr const char* names[] = {"T", "D", "I", "W", "E", "C"};
    const char* const tag = level >= 0 && level < 6 ? names[level] : "?";
#ifdef __ANDROID__
    static constexpr int prio[] = {ANDROID_LOG_VERBOSE, ANDROID_LOG_DEBUG, ANDROID_LOG_INFO,
                                   ANDROID_LOG_WARN,    ANDROID_LOG_ERROR, ANDROID_LOG_FATAL};
    __android_log_print(level >= 0 && level < 6 ? prio[level] : ANDROID_LOG_INFO, "peanuts_rt",
                        "%s %s", where, message);
#endif
    std::fprintf(stderr, "[%s] %s %s\n", tag, where, message);
}
std::atomic<Sink> g_sink{&DefaultSink};
std::atomic<Level> g_min{Level::Info};
} // namespace

void SetSink(Sink sink) {
    g_sink.store(sink ? sink : &DefaultSink);
}
void SetMinLevel(Level level) {
    g_min.store(level);
}
Level MinLevel() {
    return g_min.load(std::memory_order_relaxed);
}
void Emit(Level level, const char* file, unsigned line, const char* function,
          const std::string& text) {
    const char* base = std::strrchr(file, '/');
    base = base ? base + 1 : file;
    char where[256];
    std::snprintf(where, sizeof(where), "%s:%u %s:", base, line, function);
    g_sink.load()(static_cast<int>(level), where, text.c_str());
}
} // namespace Common::Log

// --- threads ------------------------------------------------------------------------------------
namespace Common {
void SetCurrentThreadPriority(ThreadPriority priority) {
#ifdef __linux__
    const int nice_value = priority == ThreadPriority::Low ? 10 : 0;
    setpriority(PRIO_PROCESS, static_cast<id_t>(gettid()), nice_value);
#else
    (void)priority;
#endif
}
void SetCurrentThreadName(const char* name) {
#ifdef __linux__
    pthread_setname_np(pthread_self(), name);
#else
    (void)name;
#endif
}

u64 CityHash64WithSeed(const char* buf, size_t len, u64 seed) {
    // FNV-1a over 64-bit words, then a splitmix64 finaliser. See cityhash.h: a content hash only.
    u64 h = 1469598103934665603ULL ^ seed;
    size_t i = 0;
    for (; i + 8 <= len; i += 8) {
        u64 w;
        std::memcpy(&w, buf + i, 8);
        h = (h ^ w) * 1099511628211ULL;
    }
    u64 tail = 0;
    for (size_t k = 0; i + k < len; ++k) {
        tail |= static_cast<u64>(static_cast<u8>(buf[i + k])) << (8 * k);
    }
    h = (h ^ tail ^ (static_cast<u64>(len) << 56)) * 1099511628211ULL;
    h ^= h >> 30;
    h *= 0xbf58476d1ce4e5b9ULL;
    h ^= h >> 27;
    h *= 0x94d049bb133111ebULL;
    h ^= h >> 31;
    return h;
}
u64 CityHash64(const char* buf, size_t len) {
    return CityHash64WithSeed(buf, len, 0);
}
} // namespace Common

// --- VFS ----------------------------------------------------------------------------------------
namespace FileSys {

std::vector<u8> VfsFile::ReadBytes(std::size_t size, std::size_t offset) const {
    std::vector<u8> out(size);
    const std::size_t got = Read(out.data(), size, offset);
    out.resize(got);
    return out;
}
std::vector<u8> VfsFile::ReadAllBytes() const {
    return ReadBytes(GetSize(), 0);
}

VirtualFile VfsDirectory::GetFileRelative(std::string_view path) const {
    VirtualDir dir;
    std::string_view rest = path;
    while (!rest.empty() && rest.front() == '/') {
        rest.remove_prefix(1);
    }
    const VfsDirectory* at = this;
    for (;;) {
        const auto slash = rest.find('/');
        if (slash == std::string_view::npos) {
            return at->GetFile(rest);
        }
        dir = at->GetSubdirectory(rest.substr(0, slash));
        if (!dir) {
            return nullptr;
        }
        at = dir.get();
        rest.remove_prefix(slash + 1);
    }
}
VirtualDir VfsDirectory::GetDirectoryRelative(std::string_view path) const {
    VirtualDir dir;
    std::string_view rest = path;
    const VfsDirectory* at = this;
    while (!rest.empty()) {
        while (!rest.empty() && rest.front() == '/') {
            rest.remove_prefix(1);
        }
        if (rest.empty()) break;
        const auto slash = rest.find('/');
        const auto part = rest.substr(0, slash);
        dir = at->GetSubdirectory(part);
        if (!dir) {
            return nullptr;
        }
        at = dir.get();
        rest = slash == std::string_view::npos ? std::string_view{} : rest.substr(slash + 1);
    }
    return dir;
}

namespace {

class HostFile final : public VfsFile {
public:
    explicit HostFile(std::filesystem::path path_) : path{std::move(path_)} {
        std::error_code ec;
        size = static_cast<std::size_t>(std::filesystem::file_size(path, ec));
        if (ec) size = 0;
    }
    std::string GetName() const override {
        return path.filename().string();
    }
    std::size_t GetSize() const override {
        return size;
    }
    std::size_t Read(u8* data, std::size_t length, std::size_t offset) const override {
        std::ifstream f(path, std::ios::binary);
        if (!f) return 0;
        f.seekg(static_cast<std::streamoff>(offset));
        f.read(reinterpret_cast<char*>(data), static_cast<std::streamsize>(length));
        return static_cast<std::size_t>(f.gcount());
    }
    VirtualDir GetContainingDirectory() const override {
        return OpenHostDirectory(path.parent_path());
    }

private:
    std::filesystem::path path;
    std::size_t size{};
};

class HostDir final : public VfsDirectory {
public:
    explicit HostDir(std::filesystem::path path_) : path{std::move(path_)} {}
    std::string GetName() const override {
        return path.filename().string();
    }
    std::vector<VirtualFile> GetFiles() const override {
        std::vector<VirtualFile> out;
        std::error_code ec;
        for (const auto& e : std::filesystem::directory_iterator(path, ec)) {
            if (e.is_regular_file(ec)) out.push_back(std::make_shared<HostFile>(e.path()));
        }
        return out;
    }
    std::vector<VirtualDir> GetSubdirectories() const override {
        std::vector<VirtualDir> out;
        std::error_code ec;
        for (const auto& e : std::filesystem::directory_iterator(path, ec)) {
            if (e.is_directory(ec)) out.push_back(std::make_shared<HostDir>(e.path()));
        }
        return out;
    }
    VirtualFile GetFile(std::string_view name) const override {
        return OpenHostFile(path / std::string{name});
    }
    VirtualDir GetSubdirectory(std::string_view name) const override {
        return OpenHostDirectory(path / std::string{name});
    }
    std::string GetFullPath() const override {
        return path.string();
    }

private:
    std::filesystem::path path;
};

class SyntheticDir final : public VfsDirectory {
public:
    SyntheticDir(std::string name_, std::vector<VirtualDir> children_)
        : name{std::move(name_)}, children{std::move(children_)} {}
    std::string GetName() const override {
        return name;
    }
    std::vector<VirtualFile> GetFiles() const override {
        return {};
    }
    std::vector<VirtualDir> GetSubdirectories() const override {
        return children;
    }
    VirtualFile GetFile(std::string_view) const override {
        return nullptr;
    }
    VirtualDir GetSubdirectory(std::string_view want) const override {
        for (const auto& c : children) {
            if (c->GetName() == want) return c;
        }
        return nullptr;
    }

private:
    std::string name;
    std::vector<VirtualDir> children;
};

} // namespace

VirtualDir OpenHostDirectory(const std::filesystem::path& path) {
    std::error_code ec;
    if (!std::filesystem::is_directory(path, ec)) {
        return nullptr;
    }
    return std::make_shared<HostDir>(path);
}
VirtualFile OpenHostFile(const std::filesystem::path& path) {
    std::error_code ec;
    if (!std::filesystem::is_regular_file(path, ec)) {
        return nullptr;
    }
    return std::make_shared<HostFile>(path);
}
VirtualDir MakeSyntheticDir(std::string name, const std::vector<std::filesystem::path>& children) {
    std::vector<VirtualDir> dirs;
    for (const auto& c : children) {
        if (auto d = OpenHostDirectory(c)) {
            dirs.push_back(std::move(d));
        }
    }
    return std::make_shared<SyntheticDir>(std::move(name), std::move(dirs));
}

// --- R6: a RomFS served by host callbacks (PnRtRomfs) -------------------------------------------
namespace {

/// The callback set, shared by every directory and file of one provider root.
struct ProviderState {
    PnRtRomfs cb;
};

std::string ProviderJoin(const std::string& dir, std::string_view name) {
    return dir.empty() ? std::string{name} : dir + "/" + std::string{name};
}
std::string ProviderLeaf(const std::string& path) {
    const auto slash = path.rfind('/');
    return slash == std::string::npos ? path : path.substr(slash + 1);
}

VirtualDir MakeProviderDir(std::shared_ptr<ProviderState> state, std::string path);

/// One opened RomFS file: the handle is held for the object's life, reads go through `read`.
class ProviderFile final : public VfsFile {
public:
    ProviderFile(std::shared_ptr<ProviderState> state_, std::string path_, int handle_, u64 size_)
        : state{std::move(state_)}, path{std::move(path_)}, handle{handle_}, size{size_} {}
    ~ProviderFile() override {
        if (state->cb.close != nullptr) {
            state->cb.close(state->cb.user, handle);
        }
    }
    std::string GetName() const override {
        return ProviderLeaf(path);
    }
    std::size_t GetSize() const override {
        return static_cast<std::size_t>(size);
    }
    std::size_t Read(u8* data, std::size_t length, std::size_t offset) const override {
        if (offset >= size) {
            return 0;
        }
        const u64 want = std::min<u64>(length, size - offset);
        const int64_t got = state->cb.read(state->cb.user, handle, offset, data, want);
        return got < 0 ? 0 : static_cast<std::size_t>(got);
    }
    VirtualDir GetContainingDirectory() const override {
        const auto slash = path.rfind('/');
        return MakeProviderDir(state, slash == std::string::npos ? "" : path.substr(0, slash));
    }

private:
    std::shared_ptr<ProviderState> state;
    std::string path;
    int handle;
    u64 size;
};

class ProviderDir final : public VfsDirectory {
public:
    ProviderDir(std::shared_ptr<ProviderState> state_, std::string path_)
        : state{std::move(state_)}, path{std::move(path_)} {}
    std::string GetName() const override {
        return path.empty() ? "romfs" : ProviderLeaf(path);
    }
    std::vector<VirtualFile> GetFiles() const override {
        std::vector<VirtualFile> out;
        for (const auto& [name, is_dir] : List()) {
            if (!is_dir) {
                if (auto f = GetFile(name)) {
                    out.push_back(std::move(f));
                }
            }
        }
        return out;
    }
    std::vector<VirtualDir> GetSubdirectories() const override {
        std::vector<VirtualDir> out;
        for (const auto& [name, is_dir] : List()) {
            if (is_dir) {
                out.push_back(MakeProviderDir(state, ProviderJoin(path, name)));
            }
        }
        return out;
    }
    VirtualFile GetFile(std::string_view name) const override {
        const std::string full = ProviderJoin(path, name);
        u64 size = 0;
        const int handle = state->cb.open(state->cb.user, full.c_str(), &size);
        if (handle < 0) {
            return nullptr;
        }
        return std::make_shared<ProviderFile>(state, full, handle, size);
    }
    VirtualDir GetSubdirectory(std::string_view name) const override {
        if (state->cb.list == nullptr) {
            return nullptr;
        }
        const std::string full = ProviderJoin(path, name);
        if (!state->cb.list(state->cb.user, full.c_str(), nullptr, nullptr)) {
            return nullptr;
        }
        return MakeProviderDir(state, full);
    }
    std::string GetFullPath() const override {
        return "romfs:/" + path;
    }

private:
    std::vector<std::pair<std::string, bool>> List() const {
        std::vector<std::pair<std::string, bool>> out;
        if (state->cb.list == nullptr) {
            return out;
        }
        state->cb.list(
            state->cb.user, path.c_str(),
            [](void* user, const char* name, int is_dir) {
                static_cast<std::vector<std::pair<std::string, bool>>*>(user)->emplace_back(
                    name, is_dir != 0);
            },
            &out);
        return out;
    }

    std::shared_ptr<ProviderState> state;
    std::string path; // "" = the root, else "a/b" (no leading or trailing slash)
};

VirtualDir MakeProviderDir(std::shared_ptr<ProviderState> state, std::string path) {
    return std::make_shared<ProviderDir>(std::move(state), std::move(path));
}

} // namespace

VirtualDir OpenProviderDirectory(const PnRtRomfs& provider) {
    if (provider.open == nullptr || provider.read == nullptr) {
        return nullptr;
    }
    auto state = std::make_shared<ProviderState>();
    state->cb = provider;
    return MakeProviderDir(std::move(state), "");
}

} // namespace FileSys

// --- guest memory -------------------------------------------------------------------------------
namespace Core::Memory {

bool Memory::IsValidVirtualAddressRange(VAddr base, u64 size) const {
    if (!backend.read || size == 0) {
        return false;
    }
    u8 probe{};
    // First and last byte: the backends map whole regions, so both ends inside means all inside.
    return backend.read(backend.user, base, &probe, 1) &&
           backend.read(backend.user, base + size - 1, &probe, 1);
}
u8* Memory::GetPointerSilent(VAddr vaddr) {
    return backend.pointer_at ? backend.pointer_at(backend.user, vaddr, 1) : nullptr;
}
template <typename T>
static T ReadT(const Backend& b, VAddr addr) {
    T value{};
    if (b.read) {
        if (!b.read(b.user, addr, &value, sizeof(T))) {
            value = T{};
        }
    }
    return value;
}
template <typename T>
static void WriteT(const Backend& b, VAddr addr, T value) {
    if (b.write) {
        b.write(b.user, addr, &value, sizeof(T));
    }
}
u8 Memory::Read8(VAddr addr) {
    return ReadT<u8>(backend, addr);
}
u16 Memory::Read16(VAddr addr) {
    return ReadT<u16>(backend, addr);
}
u32 Memory::Read32(VAddr addr) {
    return ReadT<u32>(backend, addr);
}
u64 Memory::Read64(VAddr addr) {
    return ReadT<u64>(backend, addr);
}
void Memory::Write8(VAddr addr, u8 value) {
    WriteT(backend, addr, value);
}
void Memory::Write16(VAddr addr, u16 value) {
    WriteT(backend, addr, value);
}
void Memory::Write32(VAddr addr, u32 value) {
    WriteT(backend, addr, value);
}
void Memory::Write64(VAddr addr, u64 value) {
    WriteT(backend, addr, value);
}
bool Memory::ReadBlock(VAddr src_addr, void* dest_buffer, std::size_t size) {
    if (!backend.read) {
        std::memset(dest_buffer, 0, size);
        return false;
    }
    if (!backend.read(backend.user, src_addr, dest_buffer, size)) {
        std::memset(dest_buffer, 0, size);
        return false;
    }
    return true;
}
bool Memory::WriteBlock(VAddr dest_addr, const void* src_buffer, std::size_t size) {
    return backend.write && backend.write(backend.user, dest_addr, src_buffer, size);
}
std::string Memory::ReadCString(VAddr vaddr, std::size_t max_length) {
    std::string out;
    for (std::size_t i = 0; i < max_length; ++i) {
        u8 c{};
        if (!backend.read || !backend.read(backend.user, vaddr + i, &c, 1) || c == 0) {
            break;
        }
        out.push_back(static_cast<char>(c));
    }
    return out;
}

} // namespace Core::Memory
