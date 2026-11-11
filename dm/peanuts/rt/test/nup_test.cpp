// nup_test (R6): the host's .nup RomFS provider (dm/peanuts/peanuts_nup.c over dm/host/nup_image.h) on a Linux PC, without the
// Android host: opens a container spec, lists the RomFS root through the PnRtRomfs callbacks and reads one file (the one named, else the first
// file of the root), printing its size and first bytes. Exit 0 when the file opened and read whole.
//
//   nup_test <file>.nup[:<selector>[/romfs]] [path inside the RomFS]
//
// nup_image.h checks the TOC's SHA-256 through the host's plat_sha256 (plat_android.c on the phone): here it is the runtime shim's SHA256().
#include <cinttypes>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "peanuts_nup.h"

extern "C" unsigned char* SHA256(const unsigned char* data, size_t len, unsigned char* out); // shim_r5.cpp
extern "C" void plat_sha256(const void* p, size_t n, uint8_t out[32]) {
    SHA256(static_cast<const unsigned char*>(p), n, out);
}

namespace {
struct Entry {
    std::string name;
    bool is_dir;
};
void Collect(void* user, const char* name, int is_dir) {
    static_cast<std::vector<Entry>*>(user)->push_back({name, is_dir != 0});
}
} // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: nup_test <file>.nup[:<selector>[/romfs]] [romfs path]\n");
        return 2;
    }
    char why[256] = "";
    pn_nup* n = pn_nup_open(argv[1], why, sizeof why);
    if (n == nullptr) {
        std::fprintf(stderr, "nup_test: cannot open %s: %s\n", argv[1], why);
        return 1;
    }
    std::printf("nup_test: %s\n", pn_nup_describe(n));
    PnRtRomfs p{};
    pn_nup_provider(n, &p);

    std::vector<Entry> root;
    if (!p.list(p.user, "", &Collect, &root)) {
        std::fprintf(stderr, "nup_test: the RomFS root does not list\n");
        pn_nup_close(n);
        return 1;
    }
    std::printf("nup_test: root: %zu entries\n", root.size());
    std::string pick = argc > 2 ? argv[2] : "";
    for (const auto& e : root) {
        std::printf("  %s%s\n", e.name.c_str(), e.is_dir ? "/" : "");
        if (pick.empty() && !e.is_dir) pick = e.name;
    }
    if (pick.empty()) {
        std::fprintf(stderr, "nup_test: no file at the root; name one\n");
        pn_nup_close(n);
        return 1;
    }
    uint64_t size = 0;
    const int h = p.open(p.user, pick.c_str(), &size);
    if (h < 0) {
        std::fprintf(stderr, "nup_test: %s: not in the RomFS\n", pick.c_str());
        pn_nup_close(n);
        return 1;
    }
    std::vector<uint8_t> head(static_cast<size_t>(size < 16 ? size : 16));
    const int64_t got = p.read(p.user, h, 0, head.data(), head.size());
    // The whole file in 1 MiB pieces: every byte must come, the count must match the size.
    std::vector<uint8_t> buf(1u << 20);
    uint64_t total = 0;
    for (uint64_t off = 0; off < size;) {
        const int64_t k = p.read(p.user, h, off, buf.data(), buf.size());
        if (k <= 0) break;
        off += static_cast<uint64_t>(k);
        total = off;
    }
    const int64_t past = p.read(p.user, h, size, buf.data(), buf.size()); // at the end: 0
    p.close(p.user, h);
    std::printf("nup_test: %s: %" PRIu64 " bytes, first %" PRId64 ":", pick.c_str(), size, got);
    for (int64_t i = 0; i < got; ++i) std::printf(" %02x", head[static_cast<size_t>(i)]);
    std::printf("\nnup_test: read whole: %" PRIu64 " of %" PRIu64 " bytes, read at end -> %" PRId64 "\n", total, size, past);
    // A path the container cannot hold must say so cleanly, and a directory must not open as a file.
    uint64_t s2 = 0;
    const int miss = p.open(p.user, "no/such/file.bin", &s2);
    std::printf("nup_test: missing path -> %d, root as a file -> %d\n", miss, p.open(p.user, "", &s2));
    pn_nup_close(n);
    const bool ok = got >= 0 && total == size && past == 0 && miss == -1;
    std::printf("nup_test: %s\n", ok ? "OK" : "FAILED");
    return ok ? 0 : 1;
}
