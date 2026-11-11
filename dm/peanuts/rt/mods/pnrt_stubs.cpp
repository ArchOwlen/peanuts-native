// PNRT: stubs for the ModRuntime members whose defining files are NOT lifted
// (mod_guest_bridge.cpp, engine_*.cpp). Each is the smallest body that keeps the lifted callers
// correct: "nothing happened" results (no-op, nullptr, nullopt, false). The native module host
// (mod_module.cpp, mod_module_host.cpp, mod_module_services.cpp) is lifted as of R5, so the
// module stubs that used to live here are gone. RunAction's Call and Sequence kinds reach the two
// guest-bridge entry points below, which log "unsupported on this host" and otherwise do nothing
// (RunAction itself still reports ActionResult::Done for them, fire-and-forget, as in Eden). See
// PORT.md for the full list.

#include <optional>
#include <string>
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

} // namespace Core::Mods
