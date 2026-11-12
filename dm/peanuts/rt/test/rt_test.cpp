// rt_test: loads a real companion package through peanuts_rt with a fake guest memory and writes
// the rendered pages to PNG files.
//
//   rt_test <package dir> <out dir> [romfs dir [--provider]]
//   rt_test --module <package dir> <out dir> <title id hex> <build id hex> [--romfs <dir>] [page id...]
//
// `--provider` (R6) hands the romfs dir to the runtime through PnRtRomfs callbacks (a fake provider
// over POSIX open/pread/readdir, below) instead of PnRtConfig::romfs_dir: the PNGs must come out
// the same, which proves the provider-backed VFS path the .nup host glue (peanuts_nup.c) uses.
//
// The second form (R5) drives a package that ships a native module (dualscreen/modules/
// <platform>/<TITLEID>.so, e.g. Fire Emblem: Three Houses): the runtime must accept the package
// (no "update required" page), load the host platform's module through dlopen, and render the
// default page plus every page named, with fake (all-zero) guest memory and no romfs. Writes
// <out>/default.png and <out>/<page>.png; fails when the status line reports no module.
//
// Writes <out>/standby.png and <out>/gear.png (the Link's Awakening pages; "romfs:" art is blank
// without a romfs dir), then <out>/probe.png from a tiny generated package whose page shows the
// fake memory's rupees (main+0x1CC0760 u32 = 123) and health (main+0x1CC0764 s16 = 48) with the
// runtime's built-in font, which proves the sample -> bind -> text path end to end.

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <map>
#include <string>
#include <thread>
#include <vector>

#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <stb_image_write.h>

#include "peanuts_rt.h"

namespace {

/// R6: a fake RomFS provider over a directory on disk, through the PnRtRomfs callbacks only
/// (open/pread/readdir): what peanuts_nup.c does over a .nup, here over the extracted folder.
struct DirProvider {
    std::string root;
    std::atomic<int> opens{0}, reads{0}, lists{0}; // the asset workers call concurrently

    std::string Full(const char* path) const {
        return path != nullptr && path[0] != '\0' ? root + "/" + path : root;
    }
    static int Open(void* user, const char* path, uint64_t* size) {
        auto* self = static_cast<DirProvider*>(user);
        const std::string full = self->Full(path);
        struct stat st {};
        if (::stat(full.c_str(), &st) != 0 || !S_ISREG(st.st_mode)) return -1;
        const int fd = ::open(full.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) return -1;
        if (size) *size = static_cast<uint64_t>(st.st_size);
        ++self->opens;
        return fd;
    }
    static int64_t Read(void* user, int handle, uint64_t off, void* buf, uint64_t n) {
        ++static_cast<DirProvider*>(user)->reads;
        const ssize_t got = ::pread(handle, buf, static_cast<size_t>(n), static_cast<off_t>(off));
        return got < 0 ? -1 : static_cast<int64_t>(got);
    }
    static void Close(void*, int handle) {
        ::close(handle);
    }
    static int List(void* user, const char* path, void (*cb)(void*, const char*, int), void* cb_user) {
        auto* self = static_cast<DirProvider*>(user);
        ++self->lists;
        DIR* d = ::opendir(self->Full(path).c_str());
        if (d == nullptr) return 0;
        if (cb != nullptr) {
            while (const dirent* e = ::readdir(d)) {
                if (e->d_name[0] == '.') continue;
                struct stat st {};
                const std::string child = self->Full(path) + "/" + e->d_name;
                if (::stat(child.c_str(), &st) != 0) continue;
                cb(cb_user, e->d_name, S_ISDIR(st.st_mode) ? 1 : 0);
            }
        }
        ::closedir(d);
        return 1;
    }
    PnRtRomfs Callbacks() {
        return PnRtRomfs{this, &Open, &Read, &Close, &List};
    }
};

DirProvider* g_provider = nullptr; // set by --provider: MakeConfig then hands callbacks, not a dir

constexpr uint64_t MainBase = 0x0000008000000000ULL; // where the fake "main" module sits
constexpr uint64_t MainSize = 0x2000000ULL;          // 32 MiB
constexpr uint64_t TitleId = 0x01006BB00C6F0000ULL;
constexpr const char* BuildId = "909E904AF78AC1B8DEEFE97AB2CCDB51968F0EC7";

/// A fake guest memory: a map of mapped regions; anything else is unmapped (read fails).
struct FakeMemory {
    std::map<uint64_t, std::vector<uint8_t>> regions;

    std::vector<uint8_t>* Find(uint64_t addr, size_t len, size_t& offset) {
        auto it = regions.upper_bound(addr);
        if (it == regions.begin()) return nullptr;
        --it;
        if (addr < it->first || addr + len > it->first + it->second.size()) return nullptr;
        offset = static_cast<size_t>(addr - it->first);
        return &it->second;
    }
    static int Read(void* user, uint64_t addr, void* buf, size_t len) {
        auto* self = static_cast<FakeMemory*>(user);
        size_t off{};
        auto* r = self->Find(addr, len, off);
        if (!r) return 0;
        std::memcpy(buf, r->data() + off, len);
        return 1;
    }
    static int Write(void* user, uint64_t addr, const void* buf, size_t len) {
        auto* self = static_cast<FakeMemory*>(user);
        size_t off{};
        auto* r = self->Find(addr, len, off);
        if (!r) return 0;
        std::memcpy(r->data() + off, buf, len);
        return 1;
    }
    static uint8_t* PointerAt(void* user, uint64_t addr, size_t len) {
        auto* self = static_cast<FakeMemory*>(user);
        size_t off{};
        auto* r = self->Find(addr, len, off);
        return r ? r->data() + off : nullptr;
    }
    template <typename T>
    void Poke(uint64_t addr, T value) {
        Write(this, addr, &value, sizeof(T));
    }
};

bool WritePng(const std::filesystem::path& path, uint32_t w, uint32_t h,
              const std::vector<uint32_t>& argb) {
    std::vector<uint8_t> rgba(static_cast<size_t>(w) * h * 4);
    for (size_t i = 0; i < static_cast<size_t>(w) * h; ++i) {
        const uint32_t p = argb[i];
        rgba[i * 4 + 0] = static_cast<uint8_t>((p >> 16) & 0xFF);
        rgba[i * 4 + 1] = static_cast<uint8_t>((p >> 8) & 0xFF);
        rgba[i * 4 + 2] = static_cast<uint8_t>(p & 0xFF);
        rgba[i * 4 + 3] = 0xFF; // opaque: the panel composites nothing under the page
    }
    return stbi_write_png(path.string().c_str(), static_cast<int>(w), static_cast<int>(h), 4,
                          rgba.data(), static_cast<int>(w) * 4) != 0;
}

/// Ticks until a new frame lands (or `max_ticks`), then writes it. Returns true when written.
bool RenderPage(PnRt* rt, const char* page, const std::filesystem::path& out) {
    if (page != nullptr && !pn_rt_set_page(rt, page)) {
        std::fprintf(stderr, "rt_test: page '%s' not found\n", page);
        return false;
    }
    std::vector<uint32_t> pixels;
    uint32_t w{}, h{};
    bool got = false;
    // The redraw worker publishes asynchronously; settle a few more frames after the first so
    // assets requested by the first render (none here, but the path is real) could land.
    // Stop once nothing new has been published for ~1.5 s after the first frame (romfs art and
    // the font decode asynchronously on the asset worker and republish when they land).
    int quiet = 0;
    for (int tick = 0; tick < 900 && quiet < 90; ++tick) {
        pn_rt_tick(rt);
        std::this_thread::sleep_for(std::chrono::milliseconds(16));
        uint32_t fw{}, fh{};
        bool fresh = false;
        if (pn_rt_frame(rt, &fw, &fh, nullptr, 0)) {
            pixels.resize(static_cast<size_t>(fw) * fh);
            if (pn_rt_frame(rt, &w, &h, pixels.data(), pixels.size())) {
                got = fresh = true;
            }
        }
        quiet = got && !fresh ? quiet + 1 : 0;
    }
    if (!got) {
        std::fprintf(stderr, "rt_test: no frame published for page %s\n", page ? page : "(first)");
        return false;
    }
    if (!WritePng(out, w, h, pixels)) {
        std::fprintf(stderr, "rt_test: could not write %s\n", out.string().c_str());
        return false;
    }
    char status[256];
    pn_rt_status(rt, status, sizeof(status));
    std::printf("rt_test: wrote %s (%ux%u) [%s]\n", out.string().c_str(), w, h, status);
    return true;
}

PnRtConfig MakeConfig(FakeMemory& mem, const std::string& package_dir, const char* romfs_dir) {
    PnRtConfig cfg{};
    cfg.package_dir = package_dir.c_str();
    if (g_provider != nullptr && romfs_dir != nullptr) {
        cfg.romfs = g_provider->Callbacks(); // R6: the same folder, through the callbacks only
    } else {
        cfg.romfs_dir = romfs_dir;
    }
    cfg.title_id = TitleId;
    cfg.build_id_hex = BuildId;
    cfg.main_base = MainBase;
    cfg.main_size = MainSize;
    cfg.panel_w = 1280;
    cfg.panel_h = 720;
    cfg.memory = {&mem, &FakeMemory::Read, &FakeMemory::Write, &FakeMemory::PointerAt};
    cfg.log_level = 2;
    return cfg;
}

/// Records every haptic pulse the runtime asks for (PnRtHaptics -> pn_rt_touch tests).
struct HapticLog {
    struct Pulse {
        float amplitude;
        uint32_t ms;
        int respect_system;
        int kind;
    };
    std::vector<Pulse> pulses;
    static void Fn(void* user, float amplitude, uint32_t ms, int respect_system, int kind) {
        static_cast<HapticLog*>(user)->pulses.push_back({amplitude, ms, respect_system, kind});
    }
};

/// Settles a few ticks (16ms apart) without touching the page; used after a touch so the redraw
/// worker has a chance to republish.
void Settle(PnRt* rt, int ticks = 20) {
    for (int i = 0; i < ticks; ++i) {
        pn_rt_tick(rt);
        std::this_thread::sleep_for(std::chrono::milliseconds(16));
    }
}

/// One tap at canvas-space (x,y): finger down for one tick, then released for one tick -- the
/// minimum UpdateGestures needs to see a press-and-release with no travel as a tap (mod_input.cpp
/// DrainTaps runs synchronously inside the same pn_rt_tick that sees the release).
void Tap(PnRt* rt, uint32_t finger, uint32_t x, uint32_t y) {
    pn_rt_touch(rt, finger, x, y, 1);
    pn_rt_tick(rt);
    std::this_thread::sleep_for(std::chrono::milliseconds(16));
    pn_rt_touch(rt, finger, x, y, 0);
    pn_rt_tick(rt);
    std::this_thread::sleep_for(std::chrono::milliseconds(16));
}

/// Grabs whatever frame is current (ticking a little first if none has been published yet) and
/// writes it to `out`.
bool CapturePng(PnRt* rt, const std::filesystem::path& out) {
    uint32_t w{}, h{};
    std::vector<uint32_t> pixels;
    for (int tick = 0; tick < 120; ++tick) {
        uint32_t fw{}, fh{};
        if (pn_rt_frame(rt, &fw, &fh, nullptr, 0)) {
            pixels.resize(static_cast<size_t>(fw) * fh);
            if (pn_rt_frame(rt, &w, &h, pixels.data(), pixels.size())) {
                return WritePng(out, w, h, pixels);
            }
        }
        pn_rt_tick(rt);
        std::this_thread::sleep_for(std::chrono::milliseconds(16));
    }
    std::fprintf(stderr, "rt_test: CapturePng: no frame for %s\n", out.string().c_str());
    return false;
}

/// A minimal package whose one page prints the two fake values with the built-in font.
std::string WriteProbePackage(const std::filesystem::path& out_dir) {
    const auto pkg = out_dir / "probe_package";
    std::filesystem::create_directories(pkg / "dualscreen");
    std::ofstream(pkg / "package.json") << R"({"format":1,"type":"dual-screen-mod",
        "title_id":"01006BB00C6F0000","name":"rt_test probe","version":"0.0.1"})";
    std::ofstream(pkg / "dualscreen" / "manifest.json") << R"({
      "format": 1, "title_id": "01006BB00C6F0000", "name": "rt_test probe",
      "canvas_w": 640, "canvas_h": 360, "background": "#FF102030",
      "pages": [{"id": "main", "title": "probe", "widgets": [
        {"type": "rect", "rect": [20, 20, 600, 320], "color": "#FF203040"},
        {"type": "label", "rect": [40, 40, 560, 40], "text": "PEANUTS RT PROBE", "text_scale": 6, "color": "#FFFFFFFF"},
        {"type": "value", "rect": [40, 120, 560, 50], "text": "RUPEES ", "bind": "rupees", "text_scale": 8, "color": "#FF60E060"},
        {"type": "value", "rect": [40, 200, 560, 50], "text": "HEALTH ", "bind": "health", "text_scale": 8, "color": "#FFE06060"},
        {"type": "bar", "rect": [40, 280, 560, 30], "bind": "health", "max": 80, "color": "#FFE06060", "bg": "#FF402020"}
      ]}]})";
    std::ofstream(pkg / "dualscreen" / "909E904AF78AC1B8.json") << R"({
      "points": {
        "rupees": {"type": "u32", "ptr": ["main+0x1CC0760"], "offset": "0x0"},
        "health": {"type": "s16", "ptr": ["main+0x1CC0764"], "offset": "0x0"}
      }})";
    return pkg.string();
}

} // namespace

int RunModuleMode(int argc, char** argv) {
    if (argc < 6) {
        std::fprintf(stderr,
                     "usage: rt_test --module <package dir> <out dir> <title hex> <build hex> [--romfs <dir>] [page...]\n");
        return 2;
    }
    const std::string package_dir = argv[2];
    const std::filesystem::path out_dir = argv[3];
    const uint64_t title = std::strtoull(argv[4], nullptr, 16);
    const char* build = argv[5];
    // R7: `--romfs <dir>` anywhere after the build id hands the module a game RomFS (the Dread
    // module's standby page names romfs:...bctex textures); the remaining arguments are page ids.
    const char* romfs_dir = nullptr;
    std::vector<const char*> pages;
    for (int i = 6; i < argc; ++i) {
        if (std::strcmp(argv[i], "--romfs") == 0 && i + 1 < argc) {
            romfs_dir = argv[++i];
        } else {
            pages.push_back(argv[i]);
        }
    }
    std::filesystem::create_directories(out_dir);
    FakeMemory mem;
    mem.regions[MainBase] = std::vector<uint8_t>(MainSize, 0);
    PnRtConfig cfg = MakeConfig(mem, package_dir, romfs_dir);
    cfg.title_id = title;
    cfg.build_id_hex = build;
    cfg.panel_w = 1240;
    cfg.panel_h = 1080;
    const std::string data_dir = (out_dir / "data").string();
    cfg.data_dir = data_dir.c_str();
    PnRt* rt = pn_rt_create(&cfg);
    if (rt == nullptr) {
        std::fprintf(stderr, "rt_test: pn_rt_create failed for %s\n", package_dir.c_str());
        return 1;
    }
    int failures = 0;
    failures += !RenderPage(rt, nullptr, out_dir / "default.png");
    char status[512];
    pn_rt_status(rt, status, sizeof(status));
    std::printf("rt_test: module status: %s\n", status);
    if (std::strstr(status, "module=none") != nullptr || std::strstr(status, "module=error") != nullptr) {
        std::fprintf(stderr, "rt_test: FAILED: the native module did not load\n");
        ++failures;
    }
    if (std::strstr(status, "page=update_required") != nullptr ||
        std::strstr(status, "page=idle") != nullptr) {
        std::fprintf(stderr, "rt_test: FAILED: the package was not accepted (%s)\n", status);
        ++failures;
    }
    for (const char* page : pages) {
        failures += !RenderPage(rt, page, out_dir / (std::string{page} + ".png"));
    }
    pn_rt_destroy(rt);
    std::printf("rt_test: %s\n", failures == 0 ? "OK" : "FAILED");
    return failures == 0 ? 0 : 1;
}

int main(int argc, char** argv) {
    if (argc >= 2 && std::strcmp(argv[1], "--module") == 0) {
        return RunModuleMode(argc, argv);
    }
    if (argc < 3) {
        std::fprintf(stderr, "usage: rt_test <package dir> <out dir> [romfs dir [--provider]]\n");
        return 2;
    }
    const std::string package_dir = argv[1];
    const std::filesystem::path out_dir = argv[2];
    const char* romfs_dir = argc > 3 ? argv[3] : nullptr;
    DirProvider provider;
    if (romfs_dir != nullptr && argc > 4 && std::strcmp(argv[4], "--provider") == 0) {
        provider.root = romfs_dir;
        g_provider = &provider;
        std::printf("rt_test: romfs through PnRtRomfs callbacks over %s\n", romfs_dir);
    }
    std::filesystem::create_directories(out_dir);

    FakeMemory mem;
    mem.regions[MainBase] = std::vector<uint8_t>(MainSize, 0);
    mem.Poke<uint32_t>(MainBase + 0x1CC0760, 123); // rupees
    mem.Poke<int16_t>(MainBase + 0x1CC0764, 48);   // health (quarter hearts)

    int failures = 0;
    {
        PnRtConfig cfg = MakeConfig(mem, package_dir, romfs_dir);
        PnRt* rt = pn_rt_create(&cfg);
        if (rt == nullptr) {
            std::fprintf(stderr, "rt_test: pn_rt_create failed for %s\n", package_dir.c_str());
            return 1;
        }
        failures += !RenderPage(rt, "standby", out_dir / "standby.png");
        failures += !RenderPage(rt, "gear", out_dir / "gear.png");
        pn_rt_destroy(rt);
    }
    {
        const std::string probe = WriteProbePackage(out_dir);
        PnRtConfig cfg = MakeConfig(mem, probe, nullptr);
        PnRt* rt = pn_rt_create(&cfg);
        if (rt == nullptr) {
            std::fprintf(stderr, "rt_test: pn_rt_create failed for the probe package\n");
            return 1;
        }
        failures += !RenderPage(rt, nullptr, out_dir / "probe.png");
        pn_rt_destroy(rt);
    }
    // --- R2 interaction: touch -> AuxRouting -> mod_input.cpp/mod_actions.cpp, on the real LA
    // package. The panel is set to the manifest's own canvas size (1240x1080) so the touch
    // coordinates handed to pn_rt_touch equal canvas pixels exactly (to_canvas_x/y is then the
    // identity), which is all that matters for hitting these two widgets.
    const auto interact_dir = out_dir / "out-romfs2";
    std::filesystem::create_directories(interact_dir);
    HapticLog haptics;
    {
        // (a) Tap the gear page's "Close" button (rect [30,962,1180,118], on_tap "page_map_from_gear"):
        // must switch the current page away from "gear".
        PnRtConfig cfg = MakeConfig(mem, package_dir, romfs_dir);
        cfg.panel_w = 1240;
        cfg.panel_h = 1080;
        cfg.haptics = {&haptics, &HapticLog::Fn};
        PnRt* rt = pn_rt_create(&cfg);
        if (rt == nullptr) {
            std::fprintf(stderr, "rt_test: pn_rt_create failed for the touch test\n");
            return 1;
        }
        failures += !RenderPage(rt, "gear", interact_dir / "gear_before_close.png");
        char status[256];
        pn_rt_status(rt, status, sizeof(status));
        std::printf("rt_test: before Close tap: %s\n", status);

        Tap(rt, /*finger=*/1, /*x=*/30 + 1180 / 2, /*y=*/962 + 118 / 2); // (620, 1021)
        Settle(rt, 10);

        pn_rt_status(rt, status, sizeof(status));
        std::printf("rt_test: after Close tap: %s\n", status);
        const bool page_switched = std::strstr(status, "page=map") != nullptr;
        if (!page_switched) {
            std::fprintf(stderr, "rt_test: FAILED: Close tap did not switch off the gear page\n");
        }
        failures += !page_switched;
        failures += !CapturePng(rt, interact_dir / "gear_after_close.png");
        std::printf("rt_test: haptic pulses so far: %zu\n", haptics.pulses.size());
        pn_rt_destroy(rt);
    }
    {
        // (b) On the gear page: tap item disc "cell_8" (ItemMagicPowder, payload 8, select_group
        // "gear"), then tap the X slot ("drop_X", drop_action "equip_x", accept_group "gear").
        // The tap-then-tap path in DrainTaps (mod_input.cpp) carries the selection into RunAction's
        // ActionKind::Write for "equip_x" (point "equip_x" = main+0x1CC0783, u8), which must write
        // the fake memory through PnRtMemory.write.
        mem.Poke<uint8_t>(MainBase + 0x1CC0783, 0xFF);        // X slot: "empty" sentinel
        mem.Poke<uint32_t>(MainBase + 0x1CC0788, 1u << 8);    // own_8 (bit 8): the Magic Powder is owned

        PnRtConfig cfg = MakeConfig(mem, package_dir, romfs_dir);
        cfg.panel_w = 1240;
        cfg.panel_h = 1080;
        PnRt* rt = pn_rt_create(&cfg);
        if (rt == nullptr) {
            std::fprintf(stderr, "rt_test: pn_rt_create failed for the equip test\n");
            return 1;
        }
        failures += !RenderPage(rt, "gear", interact_dir / "gear_before_equip.png");

        uint8_t before{};
        FakeMemory::Read(&mem, MainBase + 0x1CC0783, &before, sizeof(before));
        std::printf("rt_test: main+0x1CC0783 (equip_x) before = 0x%02X\n", before);

        Tap(rt, 1, 85 + 164 / 2, 137 + 164 / 2);     // cell_8 center (167, 219): select payload 8
        Settle(rt, 2);
        Tap(rt, 1, 1040 + 196 / 2, 211 + 204 / 2);   // drop_X center (1138, 313): tap-drop equip_x
        Settle(rt, 10);

        uint8_t after{};
        FakeMemory::Read(&mem, MainBase + 0x1CC0783, &after, sizeof(after));
        std::printf("rt_test: main+0x1CC0783 (equip_x) after  = 0x%02X\n", after);
        const bool equipped = after == 8;
        if (!equipped) {
            std::fprintf(stderr,
                         "rt_test: FAILED: equip_x was not written (expected 0x08, got 0x%02X)\n",
                         after);
        }
        failures += !equipped;
        failures += !CapturePng(rt, interact_dir / "gear_after_equip.png");
        pn_rt_destroy(rt);
    }
    if (g_provider != nullptr) {
        std::printf("rt_test: provider calls: %d opens, %d reads, %d lists\n", provider.opens.load(),
                    provider.reads.load(), provider.lists.load());
        if (provider.opens == 0 || provider.reads == 0) {
            std::fprintf(stderr, "rt_test: FAILED: the provider callbacks were never used\n");
            ++failures;
        }
    }
    std::printf("rt_test: %s\n", failures == 0 ? "OK" : "FAILED");
    return failures == 0 ? 0 : 1;
}
