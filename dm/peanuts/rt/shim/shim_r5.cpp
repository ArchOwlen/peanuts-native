// PNRT shim implementations added for the v1.3.5.1 (runtime 24) lift: dynamic libraries
// (the module loader), SHA-256 (the module checksum), the host data paths, the device power
// state and the shared-font decrypt.

#include <algorithm>
#include <cstring>
#include <filesystem>
#include <string>
#include <system_error>
#include <vector>

#include <dlfcn.h>

#include "common/common_types.h"
#include "common/device_power_state.h"
#include "common/dynamic_library.h"
#include "common/fs/path_util.h"
#include "common/swap.h"
#include "core/hle/service/ns/platform_service_manager.h"
#include "openssl/sha.h"

namespace Common {

DynamicLibrary& DynamicLibrary::operator=(DynamicLibrary&& rhs) noexcept {
    Close();
    handle = rhs.handle;
    rhs.handle = nullptr;
    return *this;
}
DynamicLibrary::~DynamicLibrary() {
    Close();
}
bool DynamicLibrary::Open(const char* filename) {
    Close();
    handle = dlopen(filename, RTLD_NOW | RTLD_LOCAL);
    return handle != nullptr;
}
void DynamicLibrary::Close() {
    if (handle != nullptr) {
        dlclose(handle);
        handle = nullptr;
    }
}
void* DynamicLibrary::GetSymbolAddress(const char* name) const {
    return handle != nullptr ? dlsym(handle, name) : nullptr;
}

PowerStatus GetPowerStatus() {
    // No battery model on this host: "unknown, no battery" (the @device.* keys read -1 / 0).
    return PowerStatus{-1, false, false};
}

namespace FS {
namespace {
std::filesystem::path g_eden_dir;
}
void SetEdenDir(const std::filesystem::path& dir) {
    g_eden_dir = dir;
}
std::filesystem::path GetEdenPath(EdenPath path) {
    std::filesystem::path root = g_eden_dir;
    if (root.empty()) {
        std::error_code ec;
        root = std::filesystem::temp_directory_path(ec) / "peanuts_rt";
    }
    switch (path) {
    case EdenPath::CacheDir:
        return root / "cache";
    case EdenPath::ConfigDir:
        return root / "config";
    case EdenPath::LogDir:
        return root / "log";
    default:
        return root;
    }
}
} // namespace FS

} // namespace Common

namespace Service::NS {
// As Eden's platform_service_manager.cpp: the key is the first word (already byte-swapped by the
// caller) XOR the expected result; every word is XORed with it and byte-swapped; the two header
// words are dropped.
void DecryptSharedFontToTTF(const std::vector<u32>& input, std::vector<u8>& output) {
    constexpr u32 EXPECTED_RESULT = 0x7f9a0218;
    if (input.size() < 2) {
        return;
    }
    const u32 key = input[0] ^ EXPECTED_RESULT;
    std::vector<u32> transformed(input.size());
    std::transform(input.begin(), input.end(), transformed.begin(),
                   [key](u32 word) { return Common::swap32(word ^ key); });
    const std::size_t bytes = std::min(output.size(), (transformed.size() - 2) * sizeof(u32));
    std::memcpy(output.data(), transformed.data() + 2, bytes);
}
} // namespace Service::NS

// --- SHA-256 (FIPS 180-4), for the module checksum in mod_module.cpp ---------------------------
namespace {
constexpr u32 kSha256K[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};
inline u32 Rotr(u32 x, int n) {
    return (x >> n) | (x << (32 - n));
}
void Sha256Block(u32 h[8], const unsigned char* p) {
    u32 w[64];
    for (int i = 0; i < 16; ++i) {
        w[i] = (u32{p[i * 4]} << 24) | (u32{p[i * 4 + 1]} << 16) | (u32{p[i * 4 + 2]} << 8) |
               u32{p[i * 4 + 3]};
    }
    for (int i = 16; i < 64; ++i) {
        const u32 s0 = Rotr(w[i - 15], 7) ^ Rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
        const u32 s1 = Rotr(w[i - 2], 17) ^ Rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    u32 a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
    for (int i = 0; i < 64; ++i) {
        const u32 S1 = Rotr(e, 6) ^ Rotr(e, 11) ^ Rotr(e, 25);
        const u32 ch = (e & f) ^ (~e & g);
        const u32 t1 = hh + S1 + ch + kSha256K[i] + w[i];
        const u32 S0 = Rotr(a, 2) ^ Rotr(a, 13) ^ Rotr(a, 22);
        const u32 maj = (a & b) ^ (a & c) ^ (b & c);
        const u32 t2 = S0 + maj;
        hh = g;
        g = f;
        f = e;
        e = d + t1;
        d = c;
        c = b;
        b = a;
        a = t1 + t2;
    }
    h[0] += a;
    h[1] += b;
    h[2] += c;
    h[3] += d;
    h[4] += e;
    h[5] += f;
    h[6] += g;
    h[7] += hh;
}
} // namespace

extern "C" unsigned char* SHA256(const unsigned char* data, size_t len, unsigned char* out) {
    u32 h[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
    size_t i = 0;
    for (; i + 64 <= len; i += 64) {
        Sha256Block(h, data + i);
    }
    unsigned char tail[128] = {};
    const size_t rest = len - i;
    std::memcpy(tail, data + i, rest);
    tail[rest] = 0x80;
    const size_t total = rest + 1 <= 56 ? 64 : 128;
    const u64 bits = static_cast<u64>(len) * 8;
    for (int k = 0; k < 8; ++k) {
        tail[total - 1 - k] = static_cast<unsigned char>(bits >> (8 * k));
    }
    Sha256Block(h, tail);
    if (total == 128) {
        Sha256Block(h, tail + 64);
    }
    for (int k = 0; k < 8; ++k) {
        out[k * 4] = static_cast<unsigned char>(h[k] >> 24);
        out[k * 4 + 1] = static_cast<unsigned char>(h[k] >> 16);
        out[k * 4 + 2] = static_cast<unsigned char>(h[k] >> 8);
        out[k * 4 + 3] = static_cast<unsigned char>(h[k]);
    }
    return out;
}
