// PNRT: stubs for the ModRuntime members whose defining files are NOT lifted
// (mod_guest_bridge.cpp, mod_module*.cpp, engine_*.cpp). Each is the smallest body that keeps the
// lifted callers correct: "nothing happened" results (no-op, nullptr, nullopt, false, Skipped).
// mod_actions.cpp and mod_input.cpp are lifted as of R2 (interaction); the three guest-bridge /
// module entry points RunAction still reaches (Call, Sequence, Module) are stubbed here, each
// logging "unsupported on this host" and otherwise doing nothing -- RunAction itself still
// reports ActionResult::Done for them (fire-and-forget, as in Eden). See PORT.md for the full
// list.

#include <algorithm>
#include <chrono>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <vector>

#include "common/logging.h"
#include "core/mods/mod_runtime.h"
#include "core/mods/mod_runtime_shared.h"

namespace Core::Mods {

// --- mod_guest_bridge.cpp (guest-call sequences / RunAction's Call & Sequence kinds) -----------
void ModRuntime::StartSequence(const std::string& name, const CallSequence&,
                               const StateSnapshot&) {
    LOG_WARNING(Core, "DSMod: sequence '{}' unsupported on this host (no guest-call bridge)",
               name);
}
void ModRuntime::RequestCall(const Action& action, const StateSnapshot&) {
    LOG_WARNING(Core, "DSMod: call action '{}' unsupported on this host (no guest-call bridge)",
               action.name);
}
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
void ModRuntime::RunModuleAction(const std::string& name, s64) {
    LOG_WARNING(Core, "DSMod: module action '{}' unsupported on this host (no game module)", name);
}
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
bool ModRuntime::LoadModuleImageSync(const std::string&, Image&) {
    return false;
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

} // namespace Core::Mods
