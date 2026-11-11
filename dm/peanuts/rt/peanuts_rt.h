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

/// Optional haptics: at most one pulse per pn_rt_tick (the runtime's own one-haptic-per-tick
/// rule), already turned into an amplitude/duration pair (PnRtHaptics::pulse) from the
/// manifest's HapticStrength/HapticKind. NULL = none (haptics are silently dropped).
typedef struct PnRtHaptics {
    void* user;
    void (*pulse)(void* user, float amplitude_0_1, uint32_t ms);
} PnRtHaptics;

typedef void (*PnRtLogFn)(int level, const char* where, const char* message);

/// R6, optional: the game's RomFS served by host callbacks instead of a directory on disk (a game
/// imported as a .nup container: dm/peanuts/peanuts_nup.h is the host's provider over nup_image.h).
/// Used when `romfs_dir` is NULL (or not a directory) and `open` and `read` are both set. Paths are
/// RomFS-relative with '/' separators and no leading slash ("Font/jpn_main.bffnt"); the runtime
/// reads ranges on demand (the asset workers and the tick thread may call concurrently: every
/// callback must be thread-safe). `user` must outlive the runtime.
typedef struct PnRtRomfs {
    void* user;
    /// Opens a file: returns a handle >= 0 and stores its size, or -1 when there is no such file
    /// (a directory is not a file).
    int (*open)(void* user, const char* path, uint64_t* size);
    /// Copies up to `n` bytes at `off` into `buf`; returns the count read (0 at or past the end),
    /// or -1 on an I/O error.
    int64_t (*read)(void* user, int handle, uint64_t off, void* buf, uint64_t n);
    void (*close)(void* user, int handle);
    /// Optional. Lists a directory ("" = the root): `cb(cb_user, name, is_dir)` once per child;
    /// returns 1 when `path` is a directory, 0 when not. `cb` may be NULL (existence check only).
    /// NULL = the runtime cannot enumerate or descend into directories, only open known paths.
    int (*list)(void* user, const char* path, void (*cb)(void* cb_user, const char* name, int is_dir),
                void* cb_user);
} PnRtRomfs;

typedef struct PnRtConfig {
    /// The package directory (contains package.json and dualscreen/). Required.
    const char* package_dir;
    /// The game's romfs extracted to a directory ("romfs:/..." assets). Optional (NULL = none:
    /// such assets are logged as missing and their widgets left blank).
    const char* romfs_dir;
    /// R5: a writable per-app data directory. Holds the persisted runtime flags
    /// (<data_dir>/dualscreen/persist), the player's "user:" assets (<data_dir>/dualscreen/user)
    /// and the private staging directory a package's native module is copied to before dlopen
    /// (<data_dir>/dualscreen-code; on Android this must be app-private storage that allows
    /// executable code, e.g. the code-cache dir). NULL = <system temp>/peanuts_rt.
    const char* data_dir;
    /// R5, optional: the title's save-data folder (a module's read_save_file). NULL = none.
    const char* save_dir;
    /// R5, optional: a folder with the console's shared .bfttf fonts ("sysfont:" assets).
    /// NULL = none (such assets read as missing).
    const char* sysfont_dir;
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
    PnRtUiSink ui;       /* all fields may be NULL */
    PnRtPad pad;         /* all fields may be NULL */
    PnRtHaptics haptics; /* pulse may be NULL = no haptics */
    PnRtLogFn log;   /* NULL = stderr (and logcat on Android) */
    int log_level;   /* 0 trace .. 5 critical; messages below it are dropped (default 2 = info) */
    /// (R5 fields data_dir / save_dir / sysfont_dir above; NULL = defaults.)
    /// System language for "msbt:" texts: 0 = default (American English), else 1 + Eden's
    /// Settings::Language index (1 Japanese, 2 EnglishAmerican, 3 French, 4 German, 5 Italian,
    /// 6 Spanish, 7 Chinese, 8 Korean, 9 Dutch, 10 Portuguese, 11 Russian, 12 Taiwanese,
    /// 13 EnglishBritish, 14 FrenchCanadian, 15 SpanishLatin, 16 ChineseSimplified,
    /// 17 ChineseTraditional, 18 PortugueseBrazilian, 19 Polish, 20 Thai).
    int language;
    /// R6, optional: the game's RomFS through callbacks when `romfs_dir` is NULL (see PnRtRomfs).
    /// All fields NULL = none.
    PnRtRomfs romfs;
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

/// Touch input in panel pixels (scaled to the manifest's canvas size internally, as Eden's own
/// hosts do). Call once per finger per tick; the runtime tracks every live finger_id across calls
/// and feeds the full current set to the aux touch mailbox, so multi-finger gestures (pinch-zoom,
/// drag-and-drop) work exactly as on a real second screen. `down` false removes that finger.
void pn_rt_touch(PnRt* rt, uint32_t finger_id, uint32_t x, uint32_t y, int down);

/// Switches to the page with this manifest id (e.g. "gear"). Returns 1 when found.
int pn_rt_set_page(PnRt* rt, const char* page_id);

/// One line: "<package name> page=<id> tick=<n> frames=<n> module=<m> err=<last error or ->"
/// where <m> is the native module's ABI name while its instance runs, "none" for a package
/// without one, or "error: <why>" (R5). Returns the length written (truncated to n-1).
size_t pn_rt_status(PnRt* rt, char* buf, size_t n);

#ifdef __cplusplus
}
#endif

#endif /* PEANUTS_RT_H */
