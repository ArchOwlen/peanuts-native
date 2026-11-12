// PNRT: stubs for the ModRuntime members whose defining file is NOT lifted. Since R7 that is
// only mod_guest_bridge.cpp (the BRK guest-call bridge): the engine files (engine_mercury.cpp,
// engine_ichigo.cpp, engine_il2cpp.cpp) are lifted byte-identically, so their stubs are gone.
// Each body here is the smallest one that keeps the lifted callers correct: "nothing happened"
// results (no-op, nullopt), except the two pattern scanners, which are Eden's verbatim. RunAction's Call and Sequence kinds reach the two guest-bridge entry
// points below, which log "unsupported on this host" and otherwise do nothing (RunAction itself
// still reports ActionResult::Done for them, fire-and-forget, as in Eden). See PORT.md.

#include <algorithm>
#include <cstring>
#include <optional>
#include <string>
#include <vector>

#include "common/logging.h"
#include "core/core.h"
#include "core/memory.h"
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

// --- mod_guest_bridge.cpp: the two pattern scanners, VERBATIM (lines 63-228 of Eden v1.3.5.1's
// mod_guest_bridge.cpp). They only read guest memory (OwnerMemory, HeapLow/HeapHigh, InGameplay)
// and need no bridge; engine_il2cpp.cpp's FindClassSlotByName calls ScanRange and ResolvePoint's
// "pattern" points call ScanPattern, so they are real here rather than nullopt stubs. Upstream
// sync: re-copy the two functions when mod_guest_bridge.cpp changes.
/// Searches an arbitrary guest range for a pattern. Returns the offset from `base`.
std::optional<u64> ModRuntime::ScanRange(VAddr base, u64 size, const PatternFind& find) const {
    if (!find.Valid() || size == 0) {
        return std::nullopt;
    }
    auto& memory = OwnerMemory();
    constexpr u64 ChunkSize = 1024 * 1024;
    const size_t needle = find.bytes.size();
    std::vector<u8> chunk(ChunkSize + needle);
    u32 seen = 0;
    for (u64 offset = 0; offset + needle <= size; offset += ChunkSize) {
        const u64 span = std::min<u64>(ChunkSize + needle, size - offset);
        if (!AddressIsSane(base + offset, span)) {
            continue;
        }
        memory.ReadBlock(base + offset, chunk.data(), span);
        for (u64 i = 0; i + needle <= span; ++i) {
            bool hit = true;
            for (size_t j = 0; j < needle; ++j) {
                if (find.mask[j] && chunk[i + j] != find.bytes[j]) {
                    hit = false;
                    break;
                }
            }
            if (hit && seen++ >= find.index) {
                return offset + i;
            }
        }
    }
    return std::nullopt;
}

/// Searches the main module for a pattern. Results are cached: a scan reads the whole module.
std::optional<s64> ModRuntime::ScanPattern(const PatternFind& find) const {
    if (!find.Valid() || main_region_begin == 0 || main_region_size == 0) {
        return std::nullopt;
    }
    const std::string key =
        (find.heap ? "H:" : "M:") +
        std::string{reinterpret_cast<const char*>(find.bytes.data()), find.bytes.size()};
    if (const auto cached = pattern_cache.find(key); cached != pattern_cache.end()) {
        if (cached->second == 0) {
            return std::nullopt; // a remembered miss
        }
        return cached->second;
    }

    auto& memory = OwnerMemory();
    constexpr u64 ChunkSize = 1024 * 1024;
    const size_t needle = find.bytes.size();
    std::vector<u8> chunk(ChunkSize + needle);
    u32 seen = 0;

    // A signature drawn from live data lives in the heap, not the executable. The heap is also
    // sparse, so it has to be walked a page at a time: asking whether a whole megabyte is mapped
    // fails almost everywhere and silently skips the very memory being searched.
    const VAddr HeapBegin = HeapLow();
    const VAddr HeapEnd = HeapHigh();
    if (find.heap) {
        // Not before there is a game to search. A signature made of live data does not exist
        // during boot, and a miss recorded then would be cached and never retried -- the same
        // mistake as scanning a menu, made permanent.
        if (!InGameplay()) {
            return std::nullopt;
        }
        std::vector<u8> run;
        VAddr run_begin = 0;
        const auto search_run = [&]() -> std::optional<s64> {
            for (u64 i = 0; i + needle <= run.size(); ++i) {
                bool hit = true;
                for (size_t j = 0; j < needle; ++j) {
                    if (find.mask[j] && run[i + j] != find.bytes[j]) {
                        hit = false;
                        break;
                    }
                }
                if (hit && seen++ >= find.index) {
                    return static_cast<s64>(run_begin + i) + find.offset;
                }
            }
            return std::nullopt;
        };
        for (VAddr page = HeapBegin; page < HeapEnd; page += 0x1000) {
            const u8* const host = memory.GetPointerSilent(page);
            if (host == nullptr) {
                // A gap breaks the run: bytes either side of it are not neighbours.
                if (const auto found = search_run()) {
                    pattern_cache[key] = *found;
                    LOG_INFO(Core,
                             "DSMod: pattern ({} bytes, index {}) found in the heap at "
                             "{:016X}",
                             needle, find.index, *found);
                    return *found;
                }
                run.clear();
                continue;
            }
            if (run.empty()) {
                run_begin = page;
            }
            run.insert(run.end(), host, host + 0x1000);
            // Keep runs bounded; carry the tail so a match across the seam is still seen.
            if (run.size() >= (16u << 20)) {
                if (const auto found = search_run()) {
                    pattern_cache[key] = *found;
                    LOG_INFO(Core,
                             "DSMod: pattern ({} bytes, index {}) found in the heap at "
                             "{:016X}",
                             needle, find.index, *found);
                    return *found;
                }
                const size_t keep = needle - 1;
                run.erase(run.begin(), run.end() - static_cast<long>(keep));
                run_begin = page + 0x1000 - keep;
            }
        }
        if (const auto found = search_run()) {
            pattern_cache[key] = *found;
            LOG_INFO(Core, "DSMod: pattern ({} bytes, index {}) found in the heap at {:016X}",
                     needle, find.index, *found);
            return *found;
        }
        // Remember the miss. Rescanning a gigabyte every frame for something that is not there
        // costs more than the search it is meant to serve.
        pattern_cache[key] = 0;
        LOG_WARNING(Core, "DSMod: pattern ({} bytes) not found in the heap", needle);
        return std::nullopt;
    }
    const VAddr scan_begin = main_region_begin;
    const u64 scan_size = main_region_size;

    for (u64 base = 0; base + needle <= scan_size; base += ChunkSize) {
        const u64 span = std::min<u64>(ChunkSize + needle, scan_size - base);
        if (!AddressIsSane(scan_begin + base, span)) {
            continue;
        }
        memory.ReadBlock(scan_begin + base, chunk.data(), span);
        for (u64 i = 0; i + needle <= span; ++i) {
            bool hit = true;
            for (size_t j = 0; j < needle; ++j) {
                if (find.mask[j] && chunk[i + j] != find.bytes[j]) {
                    hit = false;
                    break;
                }
            }
            if (!hit) {
                continue;
            }
            if (seen++ < find.index) {
                continue;
            }
            // A module match is reported relative to the module, because that is what stays
            // true between runs. A heap match cannot be: it is wherever the allocator put it
            // this time, so it is reported absolute and the caller must not add a base to it.
            const s64 result = find.heap ? static_cast<s64>(scan_begin + base + i) + find.offset
                                         : static_cast<s64>(base + i) + find.offset;
            pattern_cache[key] = result;
            LOG_INFO(Core, "DSMod: pattern ({} bytes, index {}) found at {}{:X}", needle,
                     find.index, find.heap ? "" : "main+", result);
            return result;
        }
    }
    pattern_cache[key] = 0;
    LOG_WARNING(Core, "DSMod: pattern ({} bytes) not found in the main module", needle);
    return std::nullopt;
}

} // namespace Core::Mods
