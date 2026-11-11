// rt_test: loads a real companion package through peanuts_rt with a fake guest memory and writes
// the rendered pages to PNG files.
//
//   rt_test <package dir> <out dir> [romfs dir]
//
// Writes <out>/standby.png and <out>/gear.png (the Link's Awakening pages; "romfs:" art is blank
// without a romfs dir), then <out>/probe.png from a tiny generated package whose page shows the
// fake memory's rupees (main+0x1CC0760 u32 = 123) and health (main+0x1CC0764 s16 = 48) with the
// runtime's built-in font, which proves the sample -> bind -> text path end to end.

#include <chrono>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <map>
#include <string>
#include <thread>
#include <vector>

#include <stb_image_write.h>

#include "peanuts_rt.h"

namespace {

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
    int settle = 0;
    for (int tick = 0; tick < 240 && settle < 20; ++tick) {
        pn_rt_tick(rt);
        std::this_thread::sleep_for(std::chrono::milliseconds(8));
        uint32_t fw{}, fh{};
        if (pn_rt_frame(rt, &fw, &fh, nullptr, 0)) {
            pixels.resize(static_cast<size_t>(fw) * fh);
            if (pn_rt_frame(rt, &w, &h, pixels.data(), pixels.size())) {
                got = true;
            }
        }
        if (got) ++settle;
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
    cfg.romfs_dir = romfs_dir;
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

int main(int argc, char** argv) {
    if (argc < 3) {
        std::fprintf(stderr, "usage: rt_test <package dir> <out dir> [romfs dir]\n");
        return 2;
    }
    const std::string package_dir = argv[1];
    const std::filesystem::path out_dir = argv[2];
    const char* romfs_dir = argc > 3 ? argv[3] : nullptr;
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
    std::printf("rt_test: %s\n", failures == 0 ? "OK" : "FAILED");
    return failures == 0 ? 0 : 1;
}
