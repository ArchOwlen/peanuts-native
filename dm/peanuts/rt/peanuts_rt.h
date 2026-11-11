// peanuts_rt: the Eden Duo DSMod companion runtime as a standalone library (C API).
//
// One runtime instance drives one package for one running title: the host calls pn_rt_tick at
// ~60 Hz (it samples guest memory through the configured backend, runs the page logic and hands a
// redraw to the runtime's own worker thread), then polls pn_rt_frame for the latest second-screen
// image. Everything here is thread-safe with respect to the runtime's own worker; the host should
// call pn_rt_tick from one thread.
#ifndef PEANUTS_RT_H
#define PEANUTS_RT_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct PnRt PnRt;

/// Guest-memory backend. `read` copies `len` bytes at guest `addr` into `buf` and returns 1, or
/// returns 0 (leaving `buf` unspecified) when any byte is unmapped. `write` mirrors it.
/// `pointer_at` (optional) returns a host pointer to `len` contiguous bytes, or NULL.
typedef struct PnRtMemory {
    void* user;
    int (*read)(void* user, uint64_t addr, void* buf, size_t len);
    int (*write)(void* user, uint64_t addr, const void* buf, size_t len);
    uint8_t* (*pointer_at)(void* user, uint64_t addr, size_t len);
} PnRtMemory;

/// Optional UI sink: each publish is forwarded as it happens (the host may instead poll
/// pn_rt_frame). Pixels are 0xAARRGGBB, tightly packed, w*h.
typedef struct PnRtUiSink {
    void* user;
    void (*publish_ui)(void* user, uint32_t w, uint32_t h, const uint32_t* pixels);
    void (*publish_ui_partial)(void* user, uint32_t w, uint32_t h, const uint32_t* pixels,
                               const int32_t rect[4]); /* rect = {x, y, w, h} that changed */
    void (*clear_ui)(void* user);
} PnRtUiSink;

/// Optional virtual pad: button ids are InputCommon::VirtualGamepad::VirtualButton (A=0, B=1,
/// X=2, Y=3, StickL=4, StickR=5, L=6, R=7, ZL=8, ZR=9, Plus=10, Minus=11, Left=12, Up=13,
/// Right=14, Down=15, SL=16, SR=17, Home=18, Capture=19); stick 0 = left, 1 = right.
typedef struct PnRtPad {
    void* user;
    void (*set_button)(void* user, size_t player, int button, int pressed);
    void (*set_stick)(void* user, size_t player, int stick, float x, float y);
} PnRtPad;

typedef void (*PnRtLogFn)(int level, const char* where, const char* message);

typedef struct PnRtConfig {
    /// The package directory (contains package.json and dualscreen/). Required.
    const char* package_dir;
    /// The game's romfs extracted to a directory ("romfs:/..." assets). Optional (NULL = none:
    /// such assets are logged as missing and their widgets left blank).
    const char* romfs_dir;
    /// The running title and its main module build id (hex, up to 64 chars; shorter is padded).
    uint64_t title_id;
    const char* build_id_hex;
    /// Guest address range of the main module ("main+0x..." offsets are relative to it).
    uint64_t main_base;
    uint64_t main_size;
    /// Second-screen panel size in pixels (the manifest's canvas size wins when it sets one).
    uint32_t panel_w;
    uint32_t panel_h;
    PnRtMemory memory;
    PnRtUiSink ui;   /* all fields may be NULL */
    PnRtPad pad;     /* all fields may be NULL */
    PnRtLogFn log;   /* NULL = stderr (and logcat on Android) */
    int log_level;   /* 0 trace .. 5 critical; messages below it are dropped (default 2 = info) */
    /// System language for "msbt:" texts: 0 = default (American English), else 1 + Eden's
    /// Settings::Language index (1 Japanese, 2 EnglishAmerican, 3 French, 4 German, 5 Italian,
    /// 6 Spanish, 7 Chinese, 8 Korean, 9 Dutch, 10 Portuguese, 11 Russian, 12 Taiwanese,
    /// 13 EnglishBritish, 14 FrenchCanadian, 15 SpanishLatin, 16 ChineseSimplified,
    /// 17 ChineseTraditional, 18 PortugueseBrazilian, 19 Polish, 20 Thai).
    int language;
} PnRtConfig;

/// Creates a runtime for `config`. NULL when the package has no usable manifest for the title
/// (the reason is logged). The returned object owns copies of everything in `config`.
PnRt* pn_rt_create(const PnRtConfig* config);
void pn_rt_destroy(PnRt* rt);

/// One runtime tick (sample -> page logic -> redraw dispatch). Call at ~60 Hz.
void pn_rt_tick(PnRt* rt);

/// Copies the latest published frame into `out_rgba` (w*h uint32 0xAARRGGBB; `*w`/`*h` receive the
/// frame size; when `out_rgba` is NULL only the size is reported). Returns 1 when a frame newer
/// than the previous call's was copied, 0 when nothing new (or nothing published yet).
int pn_rt_frame(PnRt* rt, uint32_t* w, uint32_t* h, uint32_t* out_rgba, size_t out_capacity);

/// Touch input in panel pixels. No-op for now (gesture handling is not lifted in R1).
void pn_rt_touch(PnRt* rt, uint32_t finger_id, uint32_t x, uint32_t y, int down);

/// Switches to the page with this manifest id (e.g. "gear"). Returns 1 when found.
int pn_rt_set_page(PnRt* rt, const char* page_id);

/// One line: "<package name> page=<id> tick=<n> frames=<n> err=<last error or ->". Returns the
/// length written (truncated to n-1).
size_t pn_rt_status(PnRt* rt, char* buf, size_t n);

#ifdef __cplusplus
}
#endif

#endif /* PEANUTS_RT_H */
