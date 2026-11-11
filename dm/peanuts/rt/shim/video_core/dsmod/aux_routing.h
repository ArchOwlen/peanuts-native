// PNRT shim for Eden's video_core/dsmod/aux_routing.h. In Eden, AuxRouting is the mailbox between
// the mod runtime (producer) and the Vulkan renderer (consumer of the second-screen image). Here
// it is the runtime's UI sink: it keeps the latest published frame (pn_rt_frame reads it) and
// forwards each publish to an optional UiSink of host callbacks (the Android compositor later).
// GPU-composite textures / quads are accepted and dropped (the GPU composite path is off in
// Peanuts; the runtime only takes it when the manifest flag "gpu_composite" is set).
#pragma once

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <mutex>
#include <span>
#include <vector>

#include "common/common_types.h"

namespace VideoCore::DSMod {

/// Mirrors the guest-side DsmTouchPoint (24 bytes).
struct AuxTouchPoint {
    u32 finger_id;
    u32 x; // pixels, aux surface space
    u32 y;
    u32 attributes; // bit0 = start, bit1 = end
    u64 delta_ns;
};
static_assert(sizeof(AuxTouchPoint) == 24);

struct TileMask {
    static constexpr u32 Size = 64;
    u32 cols{0};
    u32 rows{0};
    bool all{true};
    std::vector<u8> bits;

    void Reset(u32 w, u32 h) {
        cols = (w + Size - 1) / Size;
        rows = (h + Size - 1) / Size;
        bits.assign(static_cast<size_t>(cols) * rows, 0);
        all = true;
    }
    void Clear() {
        std::fill(bits.begin(), bits.end(), u8{0});
        all = false;
    }
    void MarkRect(s32 x, s32 y, s32 w, s32 h) {
        if (w <= 0 || h <= 0 || cols == 0) {
            return;
        }
        const u32 c0 = static_cast<u32>(x) / Size;
        const u32 c1 = std::min(cols - 1, static_cast<u32>(x + w - 1) / Size);
        const u32 r0 = static_cast<u32>(y) / Size;
        const u32 r1 = std::min(rows - 1, static_cast<u32>(y + h - 1) / Size);
        for (u32 r = r0; r <= r1; ++r) {
            std::fill_n(bits.begin() + static_cast<size_t>(r) * cols + c0, c1 - c0 + 1, u8{1});
        }
    }
    [[nodiscard]] bool Any() const {
        return all || std::any_of(bits.begin(), bits.end(), [](u8 b) { return b != 0; });
    }
};

/// Tile diffing is a renderer-upload optimisation; without a tiled uploader it is off.
inline bool UiDiffEnabled() {
    return false;
}

inline void DiffTiles(std::span<const u32> a, std::span<const u32> b, u32 w, u32 h,
                      TileMask& mask) {
    constexpr u32 T = TileMask::Size;
    for (u32 y = 0; y < h; ++y) {
        const u32* const ra = a.data() + static_cast<size_t>(y) * w;
        const u32* const rb = b.data() + static_cast<size_t>(y) * w;
        if (std::memcmp(ra, rb, static_cast<size_t>(w) * sizeof(u32)) == 0) {
            continue;
        }
        u8* const tile_row = mask.bits.data() + static_cast<size_t>(y / T) * mask.cols;
        for (u32 tx = 0; tx < w; tx += T) {
            if (tile_row[tx / T] != 0) {
                continue;
            }
            const size_t n = static_cast<size_t>(std::min(T, w - tx)) * sizeof(u32);
            if (std::memcmp(ra + tx, rb + tx, n) != 0) {
                tile_row[tx / T] = 1;
            }
        }
    }
}

/// Host callbacks a publish is forwarded to (all optional).
struct UiSink {
    void* user{};
    /// A whole frame: w*h ARGB (0xAARRGGBB) pixels.
    void (*publish_ui)(void* user, u32 w, u32 h, const u32* pixels){};
    /// A partial frame: `pixels` is the whole w*h canvas, only `rect` {x,y,w,h} changed.
    void (*publish_ui_partial)(void* user, u32 w, u32 h, const u32* pixels, const s32 rect[4]){};
    void (*clear_ui)(void* user){};
};

class AuxRouting {
public:
    static constexpr size_t MaxTouch = 16;
    static constexpr u64 NoLayer = 0;
    static constexpr u32 NumAuxTex = 6;
    static constexpr u32 MapCurrentSlot = 0;
    static constexpr u32 MapPreviousSlot = 4;
    static constexpr u32 MapFadeSlot = 5;

    std::atomic<bool> present{false};
    std::atomic<u32> width{0};
    std::atomic<u32> height{0};
    std::atomic<u32> rotation{0};
    std::atomic<u32> refresh_hz{60};
    std::atomic<u64> bound_layer{NoLayer};
    std::atomic<bool> rt_capture{false};
    std::atomic<bool> mirror_enabled{false};
    std::atomic<float> mirror_x{0.0f};
    std::atomic<float> mirror_y{0.0f};
    std::atomic<float> mirror_w{1.0f};
    std::atomic<float> mirror_h{1.0f};
    std::atomic<bool> gpu_composite{false};

    struct Quad {
        u32 slot;
        float u0, v0, u1, v1;
        float x, y, w, h;
        float a{1.0f};
        float r{1.0f}, g{1.0f}, b{1.0f};
        bool map_fade{false};
        bool solid{false};
        u32 color{0xFF000000u};
    };

    struct HapticEvent {
        u8 strength{};
        u8 kind{};
        u8 flags{};
    };
    using HapticSink = std::function<void(const HapticEvent&)>;
    void SetHapticSink(HapticSink sink) {
        std::scoped_lock lk{haptic_mutex};
        haptic_sink = std::move(sink);
    }
    void RaiseHaptic(HapticEvent event) {
        std::scoped_lock lk{haptic_mutex};
        if (haptic_sink) {
            haptic_sink(event);
        }
    }

    void SetUiSink(UiSink sink_) {
        std::scoped_lock lk{ui_mutex};
        sink = sink_;
    }

    // --- touch (host -> runtime) ---
    void SetTouch(std::span<const AuxTouchPoint> points) {
        std::scoped_lock lk{touch_mutex};
        touch_count = std::min(points.size(), MaxTouch);
        std::copy_n(points.begin(), touch_count, touch_points.begin());
    }
    size_t GetTouch(std::span<AuxTouchPoint> out) {
        std::scoped_lock lk{touch_mutex};
        const size_t n = std::min(out.size(), touch_count);
        size_t retained = 0;
        for (size_t i = 0; i < touch_count; ++i) {
            auto point = touch_points[i];
            if (i < n) {
                out[i] = point;
                if ((point.attributes & 1u) != 0) {
                    out[i].attributes &= ~2u;
                    point.attributes &= ~1u;
                } else if ((point.attributes & 2u) != 0) {
                    continue;
                }
            }
            touch_points[retained++] = point;
        }
        touch_count = retained;
        return n;
    }

    // --- UI frame (runtime -> host) ---
    void PublishUi(u32 w, u32 h, std::span<const u32> pixels) {
        if (pixels.size() != static_cast<size_t>(w) * h) {
            return;
        }
        std::scoped_lock lk{ui_mutex};
        ui_w = w;
        ui_h = h;
        ui_pixels.assign(pixels.begin(), pixels.end());
        ui_dirty = FullRect(w, h);
        ui_serial++;
        ui_present.store(true, std::memory_order_release);
        if (sink.publish_ui) {
            sink.publish_ui(sink.user, w, h, ui_pixels.data());
        }
    }
    template <typename F>
    void PublishUiWith(u32 w, u32 h, F&& fill) {
        std::scoped_lock lk{ui_mutex};
        ui_w = w;
        ui_h = h;
        ui_pixels.resize(static_cast<size_t>(w) * h);
        fill(std::span<u32>{ui_pixels});
        ui_dirty = FullRect(w, h);
        ui_serial++;
        ui_present.store(true, std::memory_order_release);
        if (sink.publish_ui) {
            sink.publish_ui(sink.user, w, h, ui_pixels.data());
        }
    }
    void PublishUiPartial(u32 w, u32 h, std::span<const u32> pixels, std::array<s32, 4> dirty) {
        if (pixels.size() != static_cast<size_t>(w) * h) {
            return;
        }
        std::scoped_lock lk{ui_mutex};
        const bool identity_changed =
            (w != ui_w || h != ui_h || ui_pixels.size() != static_cast<size_t>(w) * h);
        if (identity_changed) {
            ui_w = w;
            ui_h = h;
            ui_pixels.assign(pixels.begin(), pixels.end());
            ui_dirty = FullRect(w, h);
        } else {
            const auto [x0, y0, cw, ch] = ClampRect(dirty, w, h);
            for (s32 y = y0; y < y0 + ch; ++y) {
                const size_t row = static_cast<size_t>(y) * w + static_cast<size_t>(x0);
                std::copy_n(pixels.data() + row, static_cast<size_t>(cw), ui_pixels.data() + row);
            }
            ui_dirty = UnionRect(ui_dirty, {x0, y0, cw, ch});
        }
        ui_serial++;
        ui_present.store(true, std::memory_order_release);
        if (sink.publish_ui_partial) {
            const auto r = identity_changed ? FullRect(w, h) : ClampRect(dirty, w, h);
            sink.publish_ui_partial(sink.user, w, h, ui_pixels.data(), r.data());
        } else if (sink.publish_ui) {
            sink.publish_ui(sink.user, w, h, ui_pixels.data());
        }
    }
    void ClearUi() {
        std::scoped_lock lk{ui_mutex};
        ui_pixels.clear();
        ui_w = ui_h = 0;
        ui_serial++;
        ui_present.store(false, std::memory_order_release);
        if (sink.clear_ui) {
            sink.clear_ui(sink.user);
        }
    }
    /// Copies the latest frame out when its serial moved past `serial_in_out`.
    bool TakeUi(std::vector<u32>& out, u32& w, u32& h, u64& serial_in_out) {
        std::scoped_lock lk{ui_mutex};
        if (ui_serial == serial_in_out || ui_pixels.empty()) {
            return false;
        }
        out = ui_pixels;
        w = ui_w;
        h = ui_h;
        serial_in_out = ui_serial;
        return true;
    }
    [[nodiscard]] std::array<s32, 4> TakeDirty() {
        std::scoped_lock lk{ui_mutex};
        const auto d = ui_dirty;
        ui_dirty = {};
        return d;
    }

    // --- GPU composite path: accepted, not consumed ---
    void PublishAuxTexture(u32, u32, u32, std::span<const u32>) {}
    u64 PublishMapFadeTextures(u32, u32, std::span<const u32>, std::span<const u32>, u32, u32,
                               std::span<const u32>) {
        return ++map_bundle_epoch;
    }
    u64 PublishMapFadeTexturesTiles(u32, u32, std::span<const u32>, std::span<const u32>,
                                    const TileMask&, const TileMask&, u32, u32,
                                    std::span<const u32>, u64) {
        return ++map_bundle_epoch;
    }
    void PublishComposite(u32, u32, u32, std::span<const Quad>) {}
    void ClearComposite() {}

    static constexpr std::array<s32, 4> FullRect(u32 w, u32 h) {
        return {0, 0, static_cast<s32>(w), static_cast<s32>(h)};
    }
    static std::array<s32, 4> ClampRect(std::array<s32, 4> r, u32 canvas_w, u32 canvas_h) {
        const s32 x0 = std::clamp(r[0], 0, static_cast<s32>(canvas_w));
        const s32 y0 = std::clamp(r[1], 0, static_cast<s32>(canvas_h));
        const s32 x1 = std::clamp(r[0] + r[2], x0, static_cast<s32>(canvas_w));
        const s32 y1 = std::clamp(r[1] + r[3], y0, static_cast<s32>(canvas_h));
        return {x0, y0, x1 - x0, y1 - y0};
    }
    static std::array<s32, 4> UnionRect(std::array<s32, 4> a, std::array<s32, 4> b) {
        if (a[2] <= 0 || a[3] <= 0) return b;
        if (b[2] <= 0 || b[3] <= 0) return a;
        const s32 x0 = std::min(a[0], b[0]);
        const s32 y0 = std::min(a[1], b[1]);
        const s32 x1 = std::max(a[0] + a[2], b[0] + b[2]);
        const s32 y1 = std::max(a[1] + a[3], b[1] + b[3]);
        return {x0, y0, x1 - x0, y1 - y0};
    }

private:
    std::mutex touch_mutex;
    std::array<AuxTouchPoint, MaxTouch> touch_points{};
    size_t touch_count{0};

    std::mutex ui_mutex;
    UiSink sink{};
    std::vector<u32> ui_pixels;
    u32 ui_w{0}, ui_h{0};
    u64 ui_serial{0};
    std::array<s32, 4> ui_dirty{};
    std::atomic<bool> ui_present{false};
    u64 map_bundle_epoch{0};

    std::mutex haptic_mutex;
    HapticSink haptic_sink;
};

} // namespace VideoCore::DSMod
