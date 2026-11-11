// peanuts_rt C API: builds the host facade (Core::System shim) from PnRtConfig, discovers the
// package with the lifted runtime's own Discover() and drives ModRuntime through the fake core
// timing (pn_rt_tick) and the AuxRouting sink (pn_rt_frame).

#include <algorithm>
#include <array>
#include <cstdio>
#include <cstring>
#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "common/hex_util.h"
#include "common/logging.h"
#include "common/settings.h"
#include "core/core.h"
#include "core/mods/mod_runtime.h"
#include "peanuts_rt.h"

namespace Core::Mods {
/// Friend of ModRuntime (mod_runtime.h, PNRT line): the few private members the C API needs.
struct PnRtAccess {
    static bool SetPage(ModRuntime& rt, const char* id) {
        for (size_t i = 0; i < rt.manifest.pages.size(); ++i) {
            if (rt.manifest.pages[i].id == id) {
                if (rt.current_page != i) {
                    rt.current_page = i;
                    rt.page_anim_request.reset();
                    rt.ui_signature_valid = false;
                    rt.widget_sig_page = ~size_t{0};
                }
                return true;
            }
        }
        return false;
    }
    static std::string PageId(const ModRuntime& rt) {
        if (rt.manifest.pages.empty()) {
            return "-";
        }
        return rt.manifest.pages[std::min(rt.current_page, rt.manifest.pages.size() - 1)].id;
    }
    static u64 Ticks(const ModRuntime& rt) {
        return rt.tick_count;
    }
    static const std::string& Name(const ModRuntime& rt) {
        return rt.manifest.name;
    }
};
} // namespace Core::Mods

struct PnRt {
    PnRtConfig config{};
    PnRtMemory memory{};
    PnRtPad pad{};
    Core::System system;
    std::optional<Core::Mods::ModRuntime> runtime;
    std::string last_error;
    u64 frame_serial{0};
    u64 frames_taken{0};
    std::vector<u32> frame_scratch;
};

namespace {

bool MemRead(void* user, u64 addr, void* buf, std::size_t len) {
    auto* const m = static_cast<PnRtMemory*>(user);
    return m->read != nullptr && m->read(m->user, addr, buf, len) != 0;
}
bool MemWrite(void* user, u64 addr, const void* buf, std::size_t len) {
    auto* const m = static_cast<PnRtMemory*>(user);
    return m->write != nullptr && m->write(m->user, addr, buf, len) != 0;
}
u8* MemPointer(void* user, u64 addr, std::size_t len) {
    auto* const m = static_cast<PnRtMemory*>(user);
    return m->pointer_at != nullptr ? m->pointer_at(m->user, addr, len) : nullptr;
}
void PadButton(void* user, std::size_t player, int button, bool pressed) {
    auto* const p = static_cast<PnRtPad*>(user);
    if (p->set_button) p->set_button(p->user, player, button, pressed ? 1 : 0);
}
void PadStick(void* user, std::size_t player, int stick, float x, float y) {
    auto* const p = static_cast<PnRtPad*>(user);
    if (p->set_stick) p->set_stick(p->user, player, stick, x, y);
}

std::array<u8, 0x20> ParseBuildId(const char* hex) {
    std::array<u8, 0x20> out{};
    if (hex == nullptr) {
        return out;
    }
    std::string text{hex};
    if (text.size() % 2 != 0) {
        text.push_back('0');
    }
    const auto bytes = Common::HexStringToVector(text, false);
    std::copy_n(bytes.begin(), std::min(bytes.size(), out.size()), out.begin());
    return out;
}

} // namespace

extern "C" {

PnRt* pn_rt_create(const PnRtConfig* config) {
    if (config == nullptr || config->package_dir == nullptr) {
        return nullptr;
    }
    if (config->log != nullptr) {
        Common::Log::SetSink(config->log);
    }
    Common::Log::SetMinLevel(static_cast<Common::Log::Level>(std::clamp(config->log_level, 0, 5)));

    Settings::values.language_index.SetValue(
        config->language >= 1 && config->language <= 20
            ? static_cast<Settings::Language>(config->language - 1)
            : Settings::Language::EnglishAmerican);

    auto rt = std::make_unique<PnRt>();
    rt->config = *config;
    rt->memory = config->memory;
    rt->pad = config->pad;
    auto& sys = rt->system;
    sys.title_id = config->title_id;
    sys.memory.SetBackend({&rt->memory, &MemRead, &MemWrite, &MemPointer});
    sys.input.pad.SetSink({&rt->pad, &PadButton, &PadStick});
    sys.fs_controller.load_root = FileSys::MakeSyntheticDir("load", {config->package_dir});
    if (config->romfs_dir != nullptr) {
        sys.romfs_root = FileSys::OpenHostDirectory(config->romfs_dir);
        if (!sys.romfs_root) {
            LOG_WARNING(Core, "peanuts_rt: romfs_dir '{}' is not a directory", config->romfs_dir);
        }
    }
    auto& aux = sys.gpu.DSModAux();
    aux.width.store(config->panel_w != 0 ? config->panel_w : 1280);
    aux.height.store(config->panel_h != 0 ? config->panel_h : 720);
    aux.present.store(true);
    aux.SetUiSink({config->ui.user, config->ui.publish_ui, config->ui.publish_ui_partial,
                   config->ui.clear_ui});

    const auto build_id = ParseBuildId(config->build_id_hex);
    auto manifest = Core::Mods::ModRuntime::Discover(sys, config->title_id, build_id);
    if (!manifest) {
        LOG_ERROR(Core, "peanuts_rt: no usable dual-screen package in '{}' for title {:016X}",
                  config->package_dir, config->title_id);
        return nullptr;
    }
    rt->runtime.emplace(sys, std::move(*manifest));
    rt->runtime->SetMainMemoryParameters(config->main_base, config->main_size);
    rt->runtime->Initialize();
    return rt.release();
}

void pn_rt_destroy(PnRt* rt) {
    delete rt;
}

void pn_rt_tick(PnRt* rt) {
    if (rt == nullptr) {
        return;
    }
    rt->system.core_timing.FireAll();
}

int pn_rt_frame(PnRt* rt, uint32_t* w, uint32_t* h, uint32_t* out_rgba, size_t out_capacity) {
    if (rt == nullptr) {
        return 0;
    }
    u64 serial = rt->frame_serial;
    u32 fw{}, fh{};
    if (!rt->system.gpu.DSModAux().TakeUi(rt->frame_scratch, fw, fh, serial)) {
        return 0;
    }
    if (w) *w = fw;
    if (h) *h = fh;
    if (out_rgba == nullptr) {
        return 1; // size query only: the frame stays "new" for the next call
    }
    if (out_capacity < rt->frame_scratch.size()) {
        return 0;
    }
    std::copy(rt->frame_scratch.begin(), rt->frame_scratch.end(), out_rgba);
    rt->frame_serial = serial;
    ++rt->frames_taken;
    return 1;
}

void pn_rt_touch(PnRt* rt, uint32_t finger_id, uint32_t x, uint32_t y, int down) {
    if (rt == nullptr) {
        return;
    }
    // Fed to the aux touch mailbox; the gesture consumers (mod_input.cpp) are stubs in R1, so this
    // is a no-op in effect until they are lifted.
    VideoCore::DSMod::AuxTouchPoint point{finger_id, x, y, down ? 1u : 2u, 0};
    rt->system.gpu.DSModAux().SetTouch(std::span<const VideoCore::DSMod::AuxTouchPoint>{&point, 1});
}

int pn_rt_set_page(PnRt* rt, const char* page_id) {
    if (rt == nullptr || page_id == nullptr || !rt->runtime) {
        return 0;
    }
    return Core::Mods::PnRtAccess::SetPage(*rt->runtime, page_id) ? 1 : 0;
}

size_t pn_rt_status(PnRt* rt, char* buf, size_t n) {
    if (buf == nullptr || n == 0) {
        return 0;
    }
    if (rt == nullptr || !rt->runtime) {
        return static_cast<size_t>(std::snprintf(buf, n, "no runtime"));
    }
    const auto& r = *rt->runtime;
    const int len = std::snprintf(buf, n, "%s page=%s tick=%llu frames=%llu err=%s",
                                  Core::Mods::PnRtAccess::Name(r).c_str(),
                                  Core::Mods::PnRtAccess::PageId(r).c_str(),
                                  static_cast<unsigned long long>(Core::Mods::PnRtAccess::Ticks(r)),
                                  static_cast<unsigned long long>(rt->frames_taken),
                                  rt->last_error.empty() ? "-" : rt->last_error.c_str());
    return len < 0 ? 0 : std::min(static_cast<size_t>(len), n - 1);
}

} // extern "C"
