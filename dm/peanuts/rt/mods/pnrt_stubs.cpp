// PNRT: stubs for the ModRuntime members whose defining files are NOT lifted in R1
// (mod_actions.cpp, mod_input.cpp, mod_guest_bridge.cpp, mod_module*.cpp, mod_nx_runtime.cpp,
// mod_nx_assets.cpp, engine_*.cpp). Each is the smallest body that keeps the lifted callers
// correct: "nothing happened" results (no-op, nullptr, nullopt, false, Skipped). The one
// exception is RunAction's Page case, copied from mod_actions.cpp so that the manifest's
// page_binds (mod_pages.cpp) can still switch pages. See PORT.md for the full list.

#include <algorithm>
#include <chrono>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <vector>

#include "common/logging.h"
#include "core/mods/mod_nx_assets.h"
#include "core/mods/mod_runtime.h"
#include "core/mods/mod_runtime_shared.h"

namespace Core::Mods {

namespace {
bool EndsWithNoCase(std::string_view text, std::string_view suffix) {
    if (text.size() < suffix.size()) {
        return false;
    }
    const auto tail = text.substr(text.size() - suffix.size());
    return std::equal(tail.begin(), tail.end(), suffix.begin(), suffix.end(),
                      [](unsigned char a, unsigned char b) {
                          return std::tolower(a) == std::tolower(b);
                      });
}
} // namespace

// --- mod_actions.cpp ---------------------------------------------------------------------------
InputCommon::VirtualGamepad::VirtualButton ParseButton(const std::string& name, bool& ok) {
    using VB = InputCommon::VirtualGamepad::VirtualButton;
    ok = true;
    if (name == "A") return VB::ButtonA;
    if (name == "B") return VB::ButtonB;
    if (name == "X") return VB::ButtonX;
    if (name == "Y") return VB::ButtonY;
    if (name == "L") return VB::TriggerL;
    if (name == "R") return VB::TriggerR;
    if (name == "ZL") return VB::TriggerZL;
    if (name == "ZR") return VB::TriggerZR;
    if (name == "Plus") return VB::ButtonPlus;
    if (name == "Minus") return VB::ButtonMinus;
    if (name == "DUp") return VB::ButtonUp;
    if (name == "DDown") return VB::ButtonDown;
    if (name == "DLeft") return VB::ButtonLeft;
    if (name == "DRight") return VB::ButtonRight;
    ok = false;
    return VB::ButtonA;
}

ModRuntime::ActionResult ModRuntime::RunAction(const Action& action, const StateSnapshot& snapshot,
                                               std::optional<s64> payload,
                                               const std::array<s32, 4>* tap_origin) {
    // Only ActionKind::Page is served (verbatim from mod_actions.cpp, minus the explicit
    // "origin" widget lookup, which needs FindWidgetRect); every other kind is Skipped.
    if (action.kind != ActionKind::Page) {
        return ActionResult::Skipped;
    }
    const size_t before = current_page;
    bool found = false;
    for (size_t i = 0; i < manifest.pages.size(); ++i) {
        if (manifest.pages[i].id == action.page) {
            current_page = i;
            found = true;
            break;
        }
    }
    if (!found) {
        return ActionResult::Skipped;
    }
    if (current_page != before && action.transition != PageTransition::None &&
        action.duration_ms > 0) {
        std::array<s32, 4> origin{};
        if ((action.transition == PageTransition::Grow ||
             action.transition == PageTransition::Shrink) &&
            action.origin.empty() && tap_origin != nullptr) {
            origin = *tap_origin;
        }
        page_anim_request = PageAnimRequest{before,          current_page,  action.transition,
                                            action.duration_ms, action.easing, action.shadow,
                                            origin};
    } else if (current_page != before) {
        page_anim_request.reset();
    }
    return ActionResult::Done;
}
void ModRuntime::UpdateHeldButtons() {}
void ModRuntime::ReleaseHeldInputs() {}
void ModRuntime::DriveInputScript() {}
void ModRuntime::DriveLiveInput() {}

// --- mod_input.cpp -----------------------------------------------------------------------------
void ModRuntime::DrainTaps(const StateSnapshot&) {}
void ModRuntime::PublishMapState(StateSnapshot&) {}
void ModRuntime::PublishScroll(StateSnapshot&, bool) {}
void ModRuntime::PublishInteraction(StateSnapshot&) {}
void ModRuntime::UpdateGestures(const StateSnapshot&, std::span<const VideoCore::DSMod::AuxTouchPoint>,
                                size_t, u32, u32, u32, u32) {}
void ModRuntime::ReturnIdleViews() {}
void ModRuntime::ApplyViewCorrections() {}
void ModRuntime::ResetInteraction(const char*) {}
void ModRuntime::FlushHaptic() {
    pending_haptic = {};
}
void ModRuntime::DriveCmdDrag(u32, u32, u32, u32) {}
ViewState ModRuntime::GetViewState() const {
    std::scoped_lock lock{view_mutex};
    return view_state;
}
bool ModRuntime::GlideViews() {
    return false;
}

// --- mod_guest_bridge.cpp ----------------------------------------------------------------------
void ModRuntime::ArmSpies() {}
void ModRuntime::ApplyPatches() {}
void ModRuntime::DrainGuestBridgeResults() {}
void ModRuntime::RunPolledSequences(const StateSnapshot&) {}
std::optional<s64> ModRuntime::ScanPattern(const PatternFind&) const {
    return std::nullopt;
}

// --- engine_il2cpp.cpp / engine_mercury.cpp / engine_ichigo.cpp --------------------------------
std::optional<s64> ModRuntime::FindClassSlotByName(const std::string&) const {
    return std::nullopt;
}
std::optional<s64> ModRuntime::DetectMethodsOffset(VAddr) const {
    return std::nullopt;
}
std::optional<s64> ModRuntime::FindPlayerNode(const PlayerFind&) const {
    return std::nullopt;
}
bool ModRuntime::DecodeBctex(std::span<const u8>, Image&) {
    return false;
}
bool ModRuntime::DecodeSosXtx(std::span<const u8>, Image&) {
    return false;
}
bool ModRuntime::ParseMfnt(std::span<const u8>, FontMetrics&) {
    return false;
}
bool ModRuntime::ParseSosFont(std::span<const u8>, FontMetrics&) {
    return false;
}
std::vector<u8> ModRuntime::DecodeLzss(std::span<const u8>) {
    return {};
}
std::vector<u8> ModRuntime::ExtractArchiveMember(std::span<const u8>, const std::string&) {
    return {};
}

// --- mod_module.cpp / mod_module_host.cpp / mod_module_services.cpp ----------------------------
GameModule::~GameModule() = default;
void ModRuntime::InitializeGameModule() {
    game_module.reset();
    game_module_instance = nullptr;
}
void ModRuntime::ShutdownGameModule() {}
void ModRuntime::RunGameModule(StateSnapshot&, bool) {}
void ModRuntime::DrainModuleImages() {}
void ModRuntime::InstallModuleAreas() {
    module_areas_ready.store(false);
}
void ModRuntime::ApplyPendingMapUpdates() {}
std::vector<u8> ModRuntime::LoadModuleData(const std::string&) {
    return {};
}
std::shared_ptr<const Image> ModRuntime::GetModuleImage(const std::string&) {
    return nullptr;
}
bool ModRuntime::LockMapStateUnlessRaster(MapStateLock& lock) const {
    // Verbatim from mod_module_host.cpp: the lock is deferred on map_state_mutex by the caller.
    constexpr auto Grace = std::chrono::microseconds{250};
    const auto start = std::chrono::steady_clock::now();
    for (;;) {
        if (lock.try_lock()) {
            return true;
        }
        if (map_raster_busy.load(std::memory_order_acquire) != 0 ||
            std::chrono::steady_clock::now() - start > Grace) {
            return false;
        }
        std::this_thread::yield();
    }
}

// --- mod_nx_runtime.cpp / mod_nx_assets.cpp ----------------------------------------------------
// Nintendo container decoding (SARC/BNTX/BFFNT) and composites are not lifted in R1: every such
// source takes the synchronous ReadAssetBytes path (NxFallback = true), which reports it as
// "not found" once and leaves the widget blank.
NxAssetState* MakeNxAssetState(ModRuntime*) {
    return nullptr;
}
void NxAssetStateDeleter::operator()(NxAssetState*) const {}
bool ModRuntime::IsNxAssetSource(const std::string& src) {
    if (!src.starts_with("romfs:") && !src.starts_with("file:")) {
        return false;
    }
    const size_t hash = src.find('#');
    const std::string_view file = std::string_view{src}.substr(0, hash);
    if (EndsWithNoCase(file, ".lzs")) {
        return false;
    }
    if (hash != std::string::npos) {
        return true;
    }
    return EndsWithNoCase(file, ".bntx") || EndsWithNoCase(file, ".bffnt") ||
           EndsWithNoCase(file, ".arc") || EndsWithNoCase(file, ".sarc");
}
bool ModRuntime::NxFallback(const std::string&) const {
    return true;
}
std::shared_ptr<const Image> ModRuntime::GetNxImage(const std::string&) {
    return nullptr;
}
std::shared_ptr<const Image> ModRuntime::GetCompositeImage(const std::string& name) {
    static bool warned = false;
    if (!warned) {
        warned = true;
        LOG_WARNING(Core, "DSMod: composite images are not available in this build ('{}')", name);
    }
    return nullptr;
}
bool ModRuntime::ReadNxMember(const std::string&, std::vector<u8>&) {
    return false;
}
void ModRuntime::PumpNxAssets(const StateSnapshot&) {}
void ModRuntime::PublishNxFades(StateSnapshot&) {}
float ModRuntime::CompositeFade(const std::string&) {
    return 1.0f;
}
void ModRuntime::ResetNxAssets() {}
bool ModRuntime::RequestNxFont() {
    return false;
}
std::shared_ptr<const std::string> ModRuntime::GetMsbtText(const std::string&) {
    return nullptr;
}
const FontMetrics* ModRuntime::NxIconFont(std::shared_ptr<const Image>& atlas, bool& pending,
                                          FontMetrics*) {
    atlas = nullptr;
    pending = false;
    return nullptr;
}

namespace NxAssets {
bool DecodeBntxImage(std::span<const u8>, std::string_view, Image&, std::string& error) {
    error = "BNTX decoding is not lifted in this build";
    return false;
}
void ParseManifestExtras(const nlohmann::json&, Manifest&) {}
} // namespace NxAssets

} // namespace Core::Mods
