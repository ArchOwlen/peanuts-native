// PEANUTS: the generic host's runtime game profile (one libnxhost.so for every title, -PnxGenericHost=true; design/platform/IMPL-GENERIC.md).
// This struct is the binary layout `gen_runtime_profile.py` writes into each title's <id>.profile.bin (packed, no padding: every field below is read back
// by profile_rt_load() with the same offsets, in the same order). Do not reorder or resize a field without bumping DM_PROFILE_SCHEMA_VERSION in
// gen_runtime_profile.py and rebuilding every title's .profile.bin; a mismatched schema hash is refused at load (profile_rt_load returns an error string).
#pragma once
#include <stdint.h>
#include <stddef.h>

#define DM_PROFILE_MAGIC 0x504e5052u   // "PNPR"
#define DM_PROFILE_MODULES_CAP 8
#define DM_PROFILE_DISPLAY_VERSIONS_CAP 16
#define DM_PROFILE_WINDOW_FN_CAP 8
#define DM_PROFILE_SUBGROUP_ONE_LANE_CAP 16
#define DM_PROFILE_MESH_VEGETATION_CUT_CAP 128
#define DM_PROFILE_MESH_PREPASS_CUT_CAP 64
#define DM_PROFILE_FPS_STEPS_CAP 8
#define DM_PROFILE_DRS_CTL_CAP 8
#define DM_PROFILE_RES_LEVELS_CAP 8
#define DM_PROFILE_RES_LEVELS_LISTS 12

// Natural C alignment, not packed (CRITIQUE-generic.md should-fix: #pragma pack(1) let call sites take a typed pointer into an unaligned uint64_t field -
// UB even though AArch64 tolerates the actual load). gen_runtime_profile.py's Packer mirrors these same alignment rules field by field when it writes a
// title's profile.bin, so the two stay in step; DM_PROFILE_LAYOUT_SIZE (schema_stamp.h, generated) plus the _Static_assert in profile_rt.c is the backstop
// if they ever drift (a same-size reorder would otherwise pass the plain sizeof check).

typedef struct { char build[48]; char ver[16]; } dm_pf_display_version_t;
typedef struct { char build[48]; uint64_t wfn, hfn; uint32_t w, h; } dm_pf_window_fn_t;
typedef uint64_t dm_pf_mesh_prepass_cut_t[4];   // {prepass key, colour key, map, has} - exactly the DM_GAME_MESH_PREPASS_CUT row shape (uint64_t[][4]), so the
                                                 // three vka_draw.c call sites can index g_profile.mesh_prepass_cut[i][0..3] the same way they index the old static array
typedef struct { uint64_t off; uint32_t len; } dm_pf_res_level_t;
typedef struct { char build[48]; uint64_t table; uint32_t n, nl; dm_pf_res_level_t l[DM_PROFILE_RES_LEVELS_LISTS]; } dm_pf_res_levels_t;
typedef struct { char build[48]; uint64_t params, pick, budget, preset; } dm_pf_drs_ctl_t;
// Field names match dm/host/hle_fpslock.c's own steps_row_t exactly (ptr_off, mode, count, nderef, deref), so DM_RUNTIME_PROFILE's lock_find() can point
// its local steps_row_t-shaped pointer straight at g_profile.fps_steps without a field-by-field translation at that call site.
typedef struct { char build[48]; uint64_t ptr_off; uint32_t mode, count; uint32_t nderef; uint64_t deref[3]; } dm_pf_fps_steps_t;

// One title's profile, loaded once at host start (nxhost_main, before anything else reads it) from DM_PROFILE_PATH (profile_rt_load).
// Field names mirror DM_GAME_* macro names lower-cased; dm/peanuts/profile/game_profile_generic.h is what call sites actually see
// (`#define DM_GAME_X (g_profile.x)`), so adding a field here means adding the matching #define there too, or the build fails to link.
typedef struct {
    uint32_t magic, schema;   // DM_PROFILE_MAGIC, DM_PROFILE_SCHEMA_VERSION (gen_runtime_profile.py): a mismatch is refused, never silently defaulted

    char id[32], name[128], romfs_marker[192];
    uint64_t program_id;
    uint64_t mem_reserve, mem_alias, mem_stack, app_pool, mem_used, sys_resource, stdalloc_cap;
    char args[256], env[256], fastboot_env[256], devtools[256], romfs_subst[512];
    uint32_t mem_map_limit;
    uint32_t present_display, core_preempt, alloc_big_align;
    uint32_t fps_target, fps_hold, fps_limit;
    uint32_t ue_texture_pool; char ue_fps_cvar[32];
    uint32_t present_snap, pso_mtl4, enc_thread;
    uint32_t pad_layout, opmode, perf_boost, tex_flush_upload;
    uint64_t shader_key_skip_off, shader_key_skip_len;
    uint32_t render_cond, tex_dirty_upload, tex_dyn_slots, tex_soft, gpu_ts_segment, draw_barriers_game, vconv_range, av1_dav1d;

    uint32_t has_shared_font_ttf, shared_font_ttf;   // presence macro: absent (has=0) -> the host decides by SDK version, as today (hle_sdk16.c)
    uint32_t has_nvn_api, nvn_api_major, nvn_api_minor;   // presence macro: absent (has=0) -> decided by SDK version (hle_nvn.c)

    uint32_t nmod; char modules[DM_PROFILE_MODULES_CAP][32];
    uint32_t ndisplay_versions; dm_pf_display_version_t display_versions[DM_PROFILE_DISPLAY_VERSIONS_CAP];
    uint32_t nwindow_fn; dm_pf_window_fn_t window_fn[DM_PROFILE_WINDOW_FN_CAP];
    uint32_t nsubgroup_one_lane; uint64_t subgroup_one_lane[DM_PROFILE_SUBGROUP_ONE_LANE_CAP];
    uint32_t nmesh_vegetation_cut; uint64_t mesh_vegetation_cut[DM_PROFILE_MESH_VEGETATION_CUT_CAP];
    uint32_t nmesh_prepass_cut; dm_pf_mesh_prepass_cut_t mesh_prepass_cut[DM_PROFILE_MESH_PREPASS_CUT_CAP];
    uint32_t nfps_steps; dm_pf_fps_steps_t fps_steps[DM_PROFILE_FPS_STEPS_CAP];
    uint32_t ndrs_ctl; dm_pf_drs_ctl_t drs_ctl[DM_PROFILE_DRS_CTL_CAP];
    uint32_t nres_levels; dm_pf_res_levels_t res_levels[DM_PROFILE_RES_LEVELS_CAP];
} dm_game_profile_t;

// A few checkpoints along the layout (CRITIQUE-generic.md should-fix), so a field inserted or resized in the wrong place fails the build here, at the
// exact field, rather than only showing up as a total-size mismatch (profile_rt.c's _Static_assert against DM_PROFILE_LAYOUT_SIZE) that still has to be
// tracked down by hand.
_Static_assert(offsetof(dm_game_profile_t, id) == 8, "magic+schema must be exactly 8 bytes before id");
_Static_assert(offsetof(dm_game_profile_t, nmod) % 4 == 0, "nmod must be 4-byte aligned");
_Static_assert(offsetof(dm_game_profile_t, modules) % 4 == 0, "modules[] must be 4-byte aligned (char[32] rows need no more)");
_Static_assert(offsetof(dm_game_profile_t, window_fn) % 8 == 0, "dm_pf_window_fn_t has uint64_t members: must be 8-byte aligned");
_Static_assert(offsetof(dm_game_profile_t, mesh_prepass_cut) % 8 == 0, "dm_pf_mesh_prepass_cut_t is uint64_t[4]: must be 8-byte aligned");
_Static_assert(offsetof(dm_game_profile_t, fps_steps) % 8 == 0, "dm_pf_fps_steps_t has uint64_t members: must be 8-byte aligned");
_Static_assert(offsetof(dm_game_profile_t, drs_ctl) % 8 == 0, "dm_pf_drs_ctl_t has uint64_t members: must be 8-byte aligned");
_Static_assert(offsetof(dm_game_profile_t, res_levels) % 8 == 0, "dm_pf_res_levels_t has uint64_t members: must be 8-byte aligned");
_Static_assert(sizeof(dm_game_profile_t) % 8 == 0, "the whole struct must round to its own (8-byte) alignment, like any C array of it would");

extern dm_game_profile_t g_profile;   // the loaded profile; zeroed (all "off"/0/"") until profile_rt_load succeeds

// Loads path (a title's <id>.profile.bin, written at pack time by gen_runtime_profile.py) into g_profile. NULL on success, else a message for the
// caller to LOG and refuse to start with (never falls back to defaults: a schema or bounds mismatch is a hard stop, not game-specific guessing).
const char *profile_rt_load(const char *path);

// The prewarm seed (dm/gfx/vk_android/vka_pwlist.h), loaded the same way instead of being compiled in as vk_prewarm_seed.h (game.py seed <id>.seed.bin,
// a verbatim copy of the title's vk_prewarm.bin, empty when it has none). Sets dm_vk_prewarm_seed / dm_vk_prewarm_seed_len below - the same symbol names
// vka_pso.c already uses from the compiled vk_prewarm_seed.h, so dm/peanuts/profile/vk_prewarm_seed_generic.h (the generic build's stand-in for that
// header) needs no per-call-site edit there, only this one load call at host start (nxhost_main, beside profile_rt_load()). NULL on success (including
// "this title has no seed"); otherwise a message for the caller to LOG with a [RED] line - never fatal, a missing seed only costs a slower warm-up.
const char *profile_rt_load_seed(const char *path);

extern const unsigned char *dm_vk_prewarm_seed;   // NULL / 0 until profile_rt_load_seed runs, same as an empty compiled-in seed
extern size_t dm_vk_prewarm_seed_len;
