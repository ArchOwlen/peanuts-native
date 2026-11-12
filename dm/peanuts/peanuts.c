// Peanuts Native, host side: see peanuts.h.
//
// Chains: $HOME/peanuts/chains.tsv (HOME is the app's files folder, as nxlaunch sets it), one value a line:
//     <name> <TAB> <type> <TAB> <module>+0x<offset>[,0x<offset>...]
// The first element is a module's base plus an offset (no read); every later element reads a 64-bit pointer at the address so far and adds its offset (the DSMod
// package convention: three dereferences take four elements). The module "abs" is the absolute address 0 (an address a scan found, for this run only). Types: u8 u16 u32
// u64 s8 s16 s32 s64 f32 f64 str (up to 63 bytes). Lines starting with # are comments. The file is re-read when it changes: a new chain is an adb push away.
//
// Scanner: $HOME/peanuts/scan.txt holds one command; a changed file runs it on a worker thread (never the present thread) over the guest memory ([anon:nxnative guest]
// in /proc/self/maps) and the modules' data. Candidates keep their last value, so the next command can compare against it:
//     new <type> <value>          every address holding the value (types u8 u16 u32 u64 s32 f32)
//     next <op> <value>           keep candidates whose current value <op> value, op one of = != < >
//     changed | unchanged | inc | dec     keep candidates by how their value moved since the last command
//     ptr <hex address> [range]   every 8-byte value in [address - range, address] (default range 0x400): who points at a found object; module hits are static roots
//     clear
// Results: the snapshot's scan= line and up to 10 cand lines, and $HOME/peanuts/scan-out.txt (up to 5000 lines, "<address> <module+offset or -> value").
#include "peanuts.h"
#include "../host/dm.h"
#include "../host/plat/plat.h"
#include <inttypes.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>

#define MAX_CHAINS 64
#define MAX_ELEMS 8
#define REFRESH_EVERY 2   // frames between refreshes (a 10 fps game reads 5 times a second; a 60 fps one 30)
#define MAX_CAND (4u << 20)
#define CHUNK (1u << 20)

typedef struct { char name[32]; char type[8]; char module[16]; uint64_t off[MAX_ELEMS]; int n; char value[72]; } chain_t;
typedef struct { uint64_t addr; uint64_t last; } cand_t;

#include "rt/peanuts_rt.h"
static void rt_tick(void);   // the companion runtime, below
static PnRt *g_rt;
static void rt_ui_publish(void *u, uint32_t w, uint32_t h, const uint32_t *px);
static void rt_ui_publish_partial(void *u, uint32_t w, uint32_t h, const uint32_t *px, const int32_t r[4]);

static chain_t g_chains[MAX_CHAINS];
static int g_nchains;
static char g_home[256], g_path[512], g_file_state[64] = "none";
static time_t g_mtime;
static uint64_t g_frames;
static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static char g_snapshot[4096];

// ---- scanner state (g_scan_mu) ----
static pthread_mutex_t g_scan_mu = PTHREAD_MUTEX_INITIALIZER;
static cand_t *g_cand; static size_t g_ncand;
static char g_scan_type[8] = "u32";
static char g_scan_state[96] = "idle";
static char g_scan_lines[10][96]; static int g_scan_nlines;
static time_t g_scan_mtime;
static _Atomic int g_scan_busy;
static char g_scan_cmd[128];

static const char *home(void) {
    if (!g_home[0]) { const char *h = getenv("HOME"); snprintf(g_home, sizeof g_home, "%s", h ? h : "."); }
    return g_home;
}

static module_t *module_named(const char *name) {
    for (int i = 0; i < g_nmod; i++) if (!g_mod[i].nro && !strcmp(g_mod[i].name, name)) return &g_mod[i];
    return NULL;
}

// "<module>+0x<offset>" for an address inside a loaded module, else "-"
static void describe(uint64_t addr, char *out, size_t n) {
    for (int i = 0; i < g_nmod; i++) {
        uint64_t b = (uint64_t)(uintptr_t)g_mod[i].base;
        if (g_mod[i].loaded && addr >= b && addr < b + g_mod[i].size) { snprintf(out, n, "%s+%#" PRIx64, g_mod[i].name, addr - b); return; }
    }
    snprintf(out, n, "-");
}

// ---- chains ----
static void load_chains(void) {
    if (!g_path[0]) snprintf(g_path, sizeof g_path, "%s/peanuts/chains.tsv", home());
    struct stat st;
    if (stat(g_path, &st)) { if (g_nchains || strcmp(g_file_state, "missing")) { g_nchains = 0; strcpy(g_file_state, "missing"); } return; }
    if (st.st_mtime == g_mtime) return;
    g_mtime = st.st_mtime;
    FILE *f = fopen(g_path, "r");
    if (!f) { strcpy(g_file_state, "unreadable"); return; }
    int n = 0, bad = 0; char line[512];
    while (n < MAX_CHAINS && fgets(line, sizeof line, f)) {
        if (line[0] == '#' || line[0] == '\n' || line[0] == '\r') continue;
        chain_t c = {0};
        char spec[256];
        if (sscanf(line, "%31[^\t]\t%7[^\t]\t%255[^\r\n]", c.name, c.type, spec) != 3) { bad++; continue; }
        char *plus = strchr(spec, '+');
        if (!plus || (size_t)(plus - spec) >= sizeof c.module) { bad++; continue; }
        memcpy(c.module, spec, plus - spec); c.module[plus - spec] = 0;
        for (char *p = plus + 1; p && c.n < MAX_ELEMS; ) { c.off[c.n++] = strtoull(p, NULL, 0); p = strchr(p, ','); if (p) p++; }
        if (!c.n) { bad++; continue; }
        strcpy(c.value, "?");
        g_chains[n++] = c;
    }
    fclose(f);
    g_nchains = n;
    if (bad) snprintf(g_file_state, sizeof g_file_state, "%d chains, %d bad lines", n, bad);
    else snprintf(g_file_state, sizeof g_file_state, "%d chains", n);
}

static void read_chain(chain_t *c) {
    uint64_t addr;
    if (!strcmp(c->module, "abs")) addr = c->off[0];
    else {
        module_t *m = module_named(c->module);
        if (!m) { snprintf(c->value, sizeof c->value, "?no module %s", c->module); return; }
        addr = (uint64_t)(uintptr_t)m->base + c->off[0];
    }
    for (int i = 1; i < c->n; i++) {
        uint64_t p;
        if (plat_mem_read(addr, &p, 8)) { snprintf(c->value, sizeof c->value, "?read %#" PRIx64, addr); return; }
        addr = p + c->off[i];
    }
    union { uint8_t u8; uint16_t u16; uint32_t u32; uint64_t u64; int8_t s8; int16_t s16; int32_t s32; int64_t s64; float f32; double f64; char s[64]; } v;
    const char *t = c->type;
    size_t len = !strcmp(t, "u8") || !strcmp(t, "s8") ? 1 : !strcmp(t, "u16") || !strcmp(t, "s16") ? 2 : !strcmp(t, "u32") || !strcmp(t, "s32") || !strcmp(t, "f32") ? 4
               : !strcmp(t, "str") ? 63 : 8;
    memset(&v, 0, sizeof v);
    if (plat_mem_read(addr, &v, len)) {   // a string may end before a page does: retry shorter
        if (strcmp(t, "str") || plat_mem_read(addr, &v, 16)) { snprintf(c->value, sizeof c->value, "?read %#" PRIx64, addr); return; }
    }
    if (!strcmp(t, "u8")) snprintf(c->value, sizeof c->value, "%u", v.u8);
    else if (!strcmp(t, "u16")) snprintf(c->value, sizeof c->value, "%u", v.u16);
    else if (!strcmp(t, "u32")) snprintf(c->value, sizeof c->value, "%u", v.u32);
    else if (!strcmp(t, "u64")) snprintf(c->value, sizeof c->value, "%" PRIu64, v.u64);
    else if (!strcmp(t, "s8")) snprintf(c->value, sizeof c->value, "%d", v.s8);
    else if (!strcmp(t, "s16")) snprintf(c->value, sizeof c->value, "%d", v.s16);
    else if (!strcmp(t, "s32")) snprintf(c->value, sizeof c->value, "%d", v.s32);
    else if (!strcmp(t, "s64")) snprintf(c->value, sizeof c->value, "%" PRId64, v.s64);
    else if (!strcmp(t, "f32")) snprintf(c->value, sizeof c->value, "%g", v.f32);
    else if (!strcmp(t, "f64")) snprintf(c->value, sizeof c->value, "%g", v.f64);
    else if (!strcmp(t, "str")) { v.s[63] = 0; for (char *p = v.s; *p; p++) if (*p == '\n' || *p == '\r') *p = ' '; snprintf(c->value, sizeof c->value, "%s", v.s); }
    else snprintf(c->value, sizeof c->value, "?type %s", t);
}

// ---- scanner ----
typedef struct { uint64_t a, b; } range_t;

// The guest's memory and the modules: rw regions named "[anon:nxnative guest]" and each loaded module's image
static int scan_ranges(range_t *r, int max) {
    int n = 0;
    FILE *f = fopen("/proc/self/maps", "r");
    char line[512];
    while (f && n < max && fgets(line, sizeof line, f)) {
        uint64_t a, b; char perm[8];
        if (sscanf(line, "%" SCNx64 "-%" SCNx64 " %7s", &a, &b, perm) != 3 || perm[0] != 'r' || perm[1] != 'w') continue;
        if (strstr(line, "[anon:nxnative guest]")) { r[n].a = a; r[n].b = b; n++; }
    }
    if (f) fclose(f);
    for (int i = 0; i < g_nmod && n < max; i++) if (g_mod[i].loaded && !g_mod[i].nro) { r[n].a = (uint64_t)(uintptr_t)g_mod[i].base; r[n].b = r[n].a + g_mod[i].size; n++; }
    return n;
}

static size_t type_size(const char *t) { return !strcmp(t, "u8") ? 1 : !strcmp(t, "u16") ? 2 : !strcmp(t, "u64") ? 8 : 4; }

static uint64_t load_val(const uint8_t *p, const char *t) {
    switch (type_size(t)) { case 1: return *p; case 2: { uint16_t v; memcpy(&v, p, 2); return v; } case 8: { uint64_t v; memcpy(&v, p, 8); return v; } default: { uint32_t v; memcpy(&v, p, 4); return v; } }
}

static uint64_t parse_val(const char *t, const char *s) {
    if (!strcmp(t, "f32")) { float f = strtof(s, NULL); uint32_t u; memcpy(&u, &f, 4); return u; }
    if (!strcmp(t, "s32")) { int32_t v = (int32_t)strtol(s, NULL, 0); return (uint32_t)v; }
    return strtoull(s, NULL, 0);
}

static int cmp_val(const char *t, uint64_t cur, const char *op, uint64_t want) {
    if (!strcmp(t, "f32")) { float a, b; uint32_t ua = (uint32_t)cur, ub = (uint32_t)want; memcpy(&a, &ua, 4); memcpy(&b, &ub, 4);
        float d = a - b; if (d < 0) d = -d; float tol = (b < 0 ? -b : b) * 1e-4f + 1e-3f;
        return !strcmp(op, "=") ? d <= tol : !strcmp(op, "!=") ? d > tol : !strcmp(op, "<") ? a < b : a > b; }
    if (!strcmp(t, "s32")) { int32_t a = (int32_t)cur, b = (int32_t)want; return !strcmp(op, "=") ? a == b : !strcmp(op, "!=") ? a != b : !strcmp(op, "<") ? a < b : a > b; }
    return !strcmp(op, "=") ? cur == want : !strcmp(op, "!=") ? cur != want : !strcmp(op, "<") ? cur < want : cur > want;
}

// Full scan: every aligned slot in the ranges holding want (type t); or, for ptr scans (t = "u64", lo..hi), every 8-byte value in [lo, hi]
static void scan_new(const char *t, uint64_t want, uint64_t lo, uint64_t hi, int is_ptr) {
    range_t r[512]; int nr = scan_ranges(r, 512);
    size_t ts = type_size(t);
    uint8_t *buf = malloc(CHUNK + 16);
    if (!g_cand) g_cand = malloc(sizeof(cand_t) * MAX_CAND);
    g_ncand = 0;
    uint64_t scanned = 0;
    for (int i = 0; i < nr && g_cand; i++) {
        for (uint64_t a = r[i].a; a < r[i].b && g_ncand < MAX_CAND; a += CHUNK) {
            size_t len = r[i].b - a < CHUNK ? (size_t)(r[i].b - a) : CHUNK;
            if (plat_mem_read(a, buf, len)) continue;   // a guard or reserved page: skip the chunk
            scanned += len;
            for (size_t o = 0; o + ts <= len && g_ncand < MAX_CAND; o += ts) {
                uint64_t v = load_val(buf + o, t);
                int hit = is_ptr ? (v >= lo && v <= hi) : cmp_val(t, v, "=", want);
                if (hit) { g_cand[g_ncand].addr = a + o; g_cand[g_ncand].last = v; g_ncand++; }
            }
        }
    }
    free(buf);
    snprintf(g_scan_state, sizeof g_scan_state, "%s: %zu candidates (%" PRIu64 " MiB in %d ranges)", g_scan_cmd, g_ncand, scanned >> 20, nr);
}

// Filter the candidates by a predicate on (current, last); keeps their current value as the new last
static void scan_filter(const char *op, uint64_t want, int against_last) {
    size_t kept = 0;
    for (size_t i = 0; i < g_ncand; i++) {
        uint8_t raw[8] = {0};
        if (plat_mem_read(g_cand[i].addr, raw, type_size(g_scan_type))) continue;
        uint64_t cur = load_val(raw, g_scan_type);
        int keep = against_last ? (!strcmp(op, "changed") ? cur != g_cand[i].last : !strcmp(op, "unchanged") ? cur == g_cand[i].last
                                   : !strcmp(op, "inc") ? cmp_val(g_scan_type, cur, ">", g_cand[i].last) : cmp_val(g_scan_type, cur, "<", g_cand[i].last))
                               : cmp_val(g_scan_type, cur, op, want);
        if (keep) { g_cand[kept].addr = g_cand[i].addr; g_cand[kept].last = cur; kept++; }
    }
    g_ncand = kept;
    snprintf(g_scan_state, sizeof g_scan_state, "%s: %zu candidates", g_scan_cmd, g_ncand);
}

static void scan_report(void) {
    char path[512]; snprintf(path, sizeof path, "%s/peanuts/scan-out.txt", home());
    FILE *f = fopen(path, "w");
    g_scan_nlines = 0;
    for (size_t i = 0; i < g_ncand && i < 5000; i++) {
        char where[48]; describe(g_cand[i].addr, where, sizeof where);
        char line[96];
        if (!strcmp(g_scan_type, "f32")) { float v; uint32_t u = (uint32_t)g_cand[i].last; memcpy(&v, &u, 4); snprintf(line, sizeof line, "%#" PRIx64 " %s %g", g_cand[i].addr, where, v); }
        else snprintf(line, sizeof line, "%#" PRIx64 " %s %" PRIu64, g_cand[i].addr, where, g_cand[i].last);
        if (f) fprintf(f, "%s\n", line);
        if (g_scan_nlines < 10) snprintf(g_scan_lines[g_scan_nlines++], sizeof g_scan_lines[0], "%s", line);
    }
    if (f) { fprintf(f, "# %s\n", g_scan_state); fclose(f); }
}

static void *scan_thread(void *arg) {
    (void)arg;
    char cmd[128]; snprintf(cmd, sizeof cmd, "%s", g_scan_cmd);
    char w1[16] = "", w2[32] = "", w3[32] = "";
    sscanf(cmd, "%15s %31s %31s", w1, w2, w3);
    pthread_mutex_lock(&g_scan_mu);
    if (!strcmp(w1, "new") && w3[0]) { snprintf(g_scan_type, sizeof g_scan_type, "%s", w2); scan_new(w2, parse_val(w2, w3), 0, 0, 0); }
    else if (!strcmp(w1, "next") && w3[0]) scan_filter(w2, parse_val(g_scan_type, w3), 0);
    else if (!strcmp(w1, "changed") || !strcmp(w1, "unchanged") || !strcmp(w1, "inc") || !strcmp(w1, "dec")) scan_filter(w1, 0, 1);
    else if (!strcmp(w1, "ptr") && w2[0]) { uint64_t hi = strtoull(w2, NULL, 16), range = w3[0] ? strtoull(w3, NULL, 0) : 0x400; strcpy(g_scan_type, "u64"); scan_new("u64", 0, hi - range, hi, 1); }
    else if (!strcmp(w1, "clear")) { g_ncand = 0; strcpy(g_scan_state, "idle"); }
    else snprintf(g_scan_state, sizeof g_scan_state, "unknown command: %s", cmd);
    scan_report();
    pthread_mutex_unlock(&g_scan_mu);
    atomic_store(&g_scan_busy, 0);
    return NULL;
}

// A changed scan.txt starts its command on a thread of its own (one at a time)
static void scan_poll(void) {
    char path[512]; snprintf(path, sizeof path, "%s/peanuts/scan.txt", home());
    struct stat st;
    if (stat(path, &st) || st.st_mtime == g_scan_mtime || atomic_load(&g_scan_busy)) return;
    g_scan_mtime = st.st_mtime;
    FILE *f = fopen(path, "r");
    if (!f) return;
    g_scan_cmd[0] = 0;
    if (fgets(g_scan_cmd, sizeof g_scan_cmd, f)) g_scan_cmd[strcspn(g_scan_cmd, "\r\n")] = 0;
    fclose(f);
    if (!g_scan_cmd[0]) return;
    atomic_store(&g_scan_busy, 1);
    snprintf(g_scan_state, sizeof g_scan_state, "%s: running", g_scan_cmd);
    pthread_t th; pthread_attr_t at; pthread_attr_init(&at); pthread_attr_setdetachstate(&at, PTHREAD_CREATE_DETACHED);
    if (pthread_create(&th, &at, scan_thread, NULL)) { atomic_store(&g_scan_busy, 0); strcpy(g_scan_state, "thread failed"); }
    pthread_attr_destroy(&at);
}

// ---- snapshot ----
static void refresh(void) {
    load_chains();
    for (int i = 0; i < g_nchains; i++) read_chain(&g_chains[i]);
    scan_poll();
    module_t *main_mod = module_named("main");
    char *p = g_snapshot, *e = g_snapshot + sizeof g_snapshot;
    p += snprintf(p, e - p, "frames=%" PRIu64 "\nbuild=%s\nmain=%#" PRIx64 "\nchains=%s\n", g_frames, dm_main_build(), main_mod ? (uint64_t)(uintptr_t)main_mod->base : 0, g_file_state);
    for (int i = 0; i < g_nchains && p < e; i++) p += snprintf(p, e - p, "%s=%s\n", g_chains[i].name, g_chains[i].value);
    if (g_rt) { char st[256]; pn_rt_status(g_rt, st, sizeof st); p += snprintf(p, e - p, "rt=%s\n", st); }   // also in logcat on each page change via the runtime's own log
    if (pthread_mutex_trylock(&g_scan_mu) == 0) {   // a running scan keeps its lines until it is done
        p += snprintf(p, e - p, "scan=%s\n", g_scan_state);
        for (int i = 0; i < g_scan_nlines && p < e; i++) p += snprintf(p, e - p, "cand%d=%s\n", i, g_scan_lines[i]);
        pthread_mutex_unlock(&g_scan_mu);
    } else p += snprintf(p, e - p, "scan=%s\n", g_scan_state);
}

void peanuts_tick(void) {
    g_frames++;
    rt_tick();   // the companion runtime, every presented frame
    if (g_frames % REFRESH_EVERY) return;
    pthread_mutex_lock(&g_mu);
    refresh();
    pthread_mutex_unlock(&g_mu);
}

// ---- the companion runtime (rt/, the Eden Duo DSMod runtime): one package per title, found beside the pack ----
// The pack is <game>/<title ID>-work/android-pack (dm_module_dir); companion packages are <game>/<title ID>-work/peanuts/<Package>/{package.json,dualscreen/}.
// The game's RomFS (DM_ROMFS) is the runtime's romfs root: a plain folder as a directory, a .nup's inner path through the provider callbacks of peanuts_nup.c.
#include <android/log.h>
#include <dirent.h>
#include "peanuts_nup.h"

static int g_rt_tried;
static pn_nup *g_nup;   // the .nup RomFS layers behind the runtime's "romfs:" (for the process's life, as the runtime is)
static uint32_t *g_frame; static uint32_t g_frame_w, g_frame_h; static int g_frame_new;
static pthread_mutex_t g_frame_mu = PTHREAD_MUTEX_INITIALIZER;

// Guest memory for the runtime. Guest VA == host VA, so a read inside a mapped readable range is a memcpy; the ranges (the guest block's rw regions and the modules, from
// /proc/self/maps) are cached and refreshed every 2 s and after any miss. Outside them process_vm_readv (plat_mem_read) decides, as before: a syscall per read was
// the runtime's whole cost (profiled: 15 % of the process in peanuts-tick, mostly process_vm_readv, for ~700 point reads a tick)
static range_t g_rd[512]; static int g_nrd; static time_t g_rd_at;
static pthread_mutex_t g_rd_mu = PTHREAD_MUTEX_INITIALIZER;
static int range_cmp(const void *a, const void *b) { uint64_t x = ((const range_t *)a)->a, y = ((const range_t *)b)->a; return x < y ? -1 : x > y; }
static void rt_ranges_refresh(void) {
    range_t r[512]; int n = scan_ranges(r, 512);
    qsort(r, n, sizeof r[0], range_cmp);
    pthread_mutex_lock(&g_rd_mu); memcpy(g_rd, r, n * sizeof r[0]); g_nrd = n; g_rd_at = time(NULL); pthread_mutex_unlock(&g_rd_mu);
}
static int rt_range_has(uint64_t addr, size_t len) {   // [addr, addr+len) inside one cached readable range
    pthread_mutex_lock(&g_rd_mu);
    int lo = 0, hi = g_nrd - 1, ok = 0;
    while (lo <= hi) { int mid = (lo + hi) / 2; if (g_rd[mid].a > addr) hi = mid - 1; else if (g_rd[mid].b <= addr) lo = mid + 1; else { ok = addr + len <= g_rd[mid].b; break; } }
    pthread_mutex_unlock(&g_rd_mu);
    return ok;
}
static int rt_mem_read(void *u, uint64_t addr, void *buf, size_t len) {
    (void)u;
    if (time(NULL) - g_rd_at > 2) rt_ranges_refresh();
    if (rt_range_has(addr, len)) { memcpy(buf, (const void *)(uintptr_t)addr, len); return 1; }
    if (plat_mem_read(addr, buf, len)) return 0;
    rt_ranges_refresh();   // readable but not in the cache: a new mapping
    return 1;
}
static int rt_mem_write(void *u, uint64_t addr, const void *buf, size_t len) { (void)u; return plat_mem_write(addr, buf, len) == 0; }
static uint8_t *rt_mem_pointer(void *u, uint64_t addr, size_t len) {
    (void)u; uint8_t b;
    if (!len) return NULL;
    if (time(NULL) - g_rd_at > 2) rt_ranges_refresh();
    if (rt_range_has(addr, len)) return (uint8_t *)(uintptr_t)addr;
    if (plat_mem_read(addr, &b, 1) || plat_mem_read(addr + len - 1, &b, 1)) return NULL;   // both ends read: the guest block is contiguous between
    return (uint8_t *)(uintptr_t)addr;
}
// The companion's virtual pad: the host's keyboard/touch/scripted source (dm_input_set), ORed with player 1's real controller by the HID service — the same semantics as
// Eden Duo's virtual gamepad. The runtime's button ids are nn::hid::NpadButton bit order (dm_input.h), sticks -1..1 → ±DM_STICK_MAX
#include "../host/dm_input.h"
static uint64_t g_vpad_btn; static int32_t g_vpad[4];
static pthread_mutex_t g_vpad_mu = PTHREAD_MUTEX_INITIALIZER;
static void rt_pad_button(void *u, size_t player, int button, int pressed) {
    (void)u; if (player != 0 || button < 0 || button > 15) return;
    pthread_mutex_lock(&g_vpad_mu);
    if (pressed) g_vpad_btn |= 1ull << button; else g_vpad_btn &= ~(1ull << button);
    dm_input_set(g_vpad_btn, g_vpad[0], g_vpad[1], g_vpad[2], g_vpad[3]);
    pthread_mutex_unlock(&g_vpad_mu);
}
static void rt_pad_stick(void *u, size_t player, int stick, float x, float y) {
    (void)u; if (player != 0 || stick < 0 || stick > 1) return;
    pthread_mutex_lock(&g_vpad_mu);
    g_vpad[stick * 2] = (int32_t)(x * DM_STICK_MAX); g_vpad[stick * 2 + 1] = (int32_t)(y * DM_STICK_MAX);
    dm_input_set(g_vpad_btn, g_vpad[0], g_vpad[1], g_vpad[2], g_vpad[3]);
    pthread_mutex_unlock(&g_vpad_mu);
}

static void rt_log(int level, const char *where, const char *msg) {
    __android_log_print(level >= 4 ? ANDROID_LOG_ERROR : level == 3 ? ANDROID_LOG_WARN : ANDROID_LOG_INFO, "peanuts", "[rt] %s: %s", where ? where : "", msg ? msg : "");
}

// The running title, from the pack folder's name: the app's packs/<title ID>, or <title ID>-work/android-pack beside the dump. 0 when neither
static uint64_t rt_running_title(void) {
    const char *p = dm_module_dir(); if (!p) return 0;
    char buf[768]; snprintf(buf, sizeof buf, "%s", p);
    for (int up = 0; up < 2; up++) {
        size_t l = strlen(buf); while (l && buf[l - 1] == '/') buf[--l] = 0;
        char *slash = strrchr(buf, '/'); const char *base = slash ? slash + 1 : buf;
        if (strlen(base) >= 16) { char *end; uint64_t t = strtoull(base, &end, 16); if (end - base == 16 && (*end == 0 || *end == '-')) return t; }
        if (!slash) break;
        *slash = 0;
    }
    return 0;
}
// The companion package of the running title: in one of the roots a package can be installed in (beside the pack: <pack>/../peanuts, which is the app's packs/peanuts for a pack
// the app owns; and <title ID>-work/peanuts beside the dump, from DM_ROMFS's path), the one whose manifest names the title. Earlier builds took the first package in the first
// root, so another title's companion loaded (Three Houses' for Echoes of Wisdom)
//
// PEANUTS (companion-versions work package): a root now holds exactly one *active* package folder per package name (dev.peanuts.Installer keeps every other installed
// version archived outside these roots, under a dot-prefixed ".peanuts-versions" folder that readdir() already skips below via the leading-dot check) - so under normal
// operation at most one entry per root ever has a manifest matching the running title. The one unsafe window is a process kill mid-install: Installer briefly has a
// "<package>.new" (being extracted/verified) and/or a "<package>.old" (the previous active folder, about to be archived) sitting beside the real active folder, and a
// killed process can leave either of those non-dot-prefixed stray folders behind with a complete, title-matching manifest - at that point two "live-looking" folders for the
// same title exist in one root and which one readdir() returns first is filesystem order, i.e. random. Skip both known transient suffixes explicitly so a stray never
// competes with the real active folder.
//
// PEANUTS (round 3, CRITIQUE-phase2b.md §B "early recovery"): Installer.repair() only runs when the app itself next touches the package (opening a settings
// screen, starting another install) - a player who launches the game straight after a kill that landed between the two renames of a swap gets no companion at
// all, because the active folder is genuinely missing and ".old"/".new" are both skipped. Rather than wait for the app side, a second pass here falls back to
// a parked "<pkg>.old" (never ".new" - an extraction in progress has no business being loaded) when no root has an active folder for the title, logging that
// it did. This can only ever help: ".old" holds a version that was active and working a moment ago, found only once nothing else matched.
static int rt_pkgdir_suffix(const char *name, const char *suf) {
    size_t l = strlen(name), sl = strlen(suf);
    return l > sl && !strcmp(name + l - sl, suf);
}

// One root, one pass; allow_old lets the second (fallback-only) pass accept a "<pkg>.old" match, logging that it fell back. ".new" is never accepted, in
// either pass - it may still be being written.
static int rt_scan_root(const char *root, uint64_t want, int allow_old, char *dir, size_t n, uint64_t *title) {
    DIR *d = opendir(root);
    if (!d) return 0;
    struct dirent *e;
    while ((e = readdir(d))) {
        if (e->d_name[0] == '.') continue;   // skips dotfiles and the ".peanuts-versions" archive, never the active package
        if (rt_pkgdir_suffix(e->d_name, ".new")) continue;   // PEANUTS: never - an install/switch may still be writing into it
        int is_old = rt_pkgdir_suffix(e->d_name, ".old");
        if (is_old && !allow_old) continue;   // PEANUTS: a stray from a killed swap - the first pass skips it, the fallback pass (below) accepts it
        char manifest[768]; snprintf(manifest, sizeof manifest, "%s/%s/dualscreen/manifest.json", root, e->d_name);
        FILE *f = fopen(manifest, "r");
        if (!f) continue;
        char head[4096] = {0}; size_t got = fread(head, 1, sizeof head - 1, f); fclose(f); (void)got;
        uint64_t t = 0; const char *ts = strstr(head, "\"title_id\"");
        if (ts) { ts = strchr(ts + 10, '"'); if (ts) t = strtoull(ts + 1, NULL, 16); }
        if (want && t != want) { __android_log_print(ANDROID_LOG_INFO, "peanuts", "companion package %s/%s is for title %016" PRIx64 ", not %016" PRIx64 ": passed over", root, e->d_name, t, want); continue; }
        *title = t;
        snprintf(dir, n, "%s/%s", root, e->d_name);
        if (is_old) __android_log_print(ANDROID_LOG_WARN, "peanuts", "no active companion package for title %016" PRIx64 "; falling back to the parked previous version %s (a swap was interrupted before it finished)", want, dir);
        closedir(d);
        return 1;
    }
    closedir(d);
    return 0;
}
static int rt_find_package(char *dir, size_t n, uint64_t *title) {
    uint64_t want = rt_running_title();
    char roots[2][512]; int nroots = 0;
    snprintf(roots[nroots++], sizeof roots[0], "%s/../peanuts", dm_module_dir());
    const char *romfs = getenv("DM_ROMFS");
    if (romfs && want) {
        const char *nup = strstr(romfs, ".nup:"); const char *slash = NULL;
        for (const char *q = romfs; q < (nup ? nup : romfs + strlen(romfs)); q++) if (*q == '/') slash = q;
        if (slash) snprintf(roots[nroots++], sizeof roots[0], "%.*s/%016" PRIX64 "-work/peanuts", (int)(slash - romfs), romfs, want);
    }
    for (int r = 0; r < nroots; r++) if (rt_scan_root(roots[r], want, /*allow_old=*/0, dir, n, title)) return 1;
    for (int r = 0; r < nroots; r++) if (rt_scan_root(roots[r], want, /*allow_old=*/1, dir, n, title)) return 1;   // PEANUTS: fall back to ".old" before giving up, see comment above
    return 0;
}

static void rt_start(void) {
    g_rt_tried = 1;
    module_t *m = module_named("main");
    if (!m) return;
    char dir[768]; uint64_t title = 0;
    if (!rt_find_package(dir, sizeof dir, &title)) { __android_log_print(ANDROID_LOG_INFO, "peanuts", "no companion package beside %s", dm_module_dir()); return; }
    // The runtime's romfs root: the game's base RomFS folder (an update's folder is only its overlay: a few changed files). DM_ROMFS names the running version's folder,
    // <game>/updates/<v>/romfs or <game>/base/romfs. For a .nup ("<game>.nup:update/<v>/romfs") the container is read in place through the provider callbacks
    // (peanuts_nup.c): the named package's RomFS, the container's base package under it
    const char *romfs = getenv("DM_ROMFS");
    static char base_romfs[768];
    PnRtConfig c = {0};
    if (romfs && pn_nup_is_spec(romfs)) {
        char why[256] = "";
        if ((g_nup = pn_nup_open(romfs, why, sizeof why))) pn_nup_provider(g_nup, &c.romfs);
        else __android_log_print(ANDROID_LOG_WARN, "peanuts", "companion romfs: cannot open %s: %s (the \"romfs:\" assets stay blank)", romfs, why);
    } else if (romfs) {
        const char *u = strstr(romfs, "/updates/");
        struct stat st;
        if (u) snprintf(base_romfs, sizeof base_romfs, "%.*s/base/romfs", (int)(u - romfs), romfs);
        if (!u || stat(base_romfs, &st)) snprintf(base_romfs, sizeof base_romfs, "%s", romfs);   // a dump whose update folder holds the whole RomFS (no base/romfs)
    }
    c.package_dir = dir;
    c.romfs_dir = base_romfs[0] ? base_romfs : NULL;
    c.title_id = title;
    // The whole NSO build id when the launcher hands it (PN_BUILD_ID, dev.peanuts.build.GameEnv: 64 hex digits; the host's own is the first 8 bytes): a package's native
    // module may list whole ids and refuse a prefix. Taken only when it begins with the host's
    const char *full = getenv("PN_BUILD_ID");
    c.build_id_hex = full && strlen(full) >= strlen(m->build_id) && !strncasecmp(full, m->build_id, strlen(m->build_id)) ? full : m->build_id;
    c.main_base = (uint64_t)(uintptr_t)m->base;
    c.main_size = m->size;
    c.panel_w = 1240; c.panel_h = 1080;
    // R5: the runtime's own data (persisted flags, "user:" assets, and the staging folder a package's native module is copied to before dlopen) lives in the app's
    // private files folder (DM_AOC_DIR = <private files>/aoc/<title>, the one private path the launcher hands the game): shared storage is mounted noexec, so a
    // module staged there would not load. The title's save folder is DM_SAVE; the console's shared fonts come as .ttf here, not .bfttf, so "sysfont:" stays off
    static char data_dir[768];
    const char *aoc = getenv("DM_AOC_DIR");
    if (aoc) {
        const char *a = strstr(aoc, "/aoc/");
        if (a) snprintf(data_dir, sizeof data_dir, "%.*s/peanuts-rt", (int)(a - aoc), aoc);
    }
    if (!data_dir[0]) snprintf(data_dir, sizeof data_dir, "%s/peanuts-rt", getenv("DM_TEMP") ? getenv("DM_TEMP") : "/data/local/tmp");
    mkdir(data_dir, 0700);
    c.data_dir = data_dir;
    c.save_dir = getenv("DM_SAVE");
    c.memory.read = rt_mem_read; c.memory.write = rt_mem_write; c.memory.pointer_at = rt_mem_pointer;
    c.pad.set_button = rt_pad_button; c.pad.set_stick = rt_pad_stick;
    c.ui.publish_ui = rt_ui_publish; c.ui.publish_ui_partial = rt_ui_publish_partial;
    c.log = rt_log; c.log_level = 2;
    g_rt = pn_rt_create(&c);
    __android_log_print(ANDROID_LOG_INFO, "peanuts", "companion package %s (title %016" PRIx64 ", build %s, romfs %s): %s", dir, title, m->build_id,
                        c.romfs_dir ? c.romfs_dir : g_nup ? pn_nup_describe(g_nup) : "none", g_rt ? "loaded" : "not loaded");
}

// The runtime ticks on a thread of its own, once per presented frame (the present thread only signals), so its sampling and page logic never cost the game a frame
static pthread_mutex_t g_tick_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_tick_cv = PTHREAD_COND_INITIALIZER;
static uint64_t g_tick_signalled, g_tick_done;

// The runtime's publishes arrive here (its redraw thread), whole or as the changed rectangle; the rows are swizzled to the bytes Android's ARGB_8888 bitmaps hold (R, G, B, A)
// into the page buffer, so the page's poll copies as is and a moving marker costs its rectangle, not the whole page
static inline uint32_t swz(uint32_t p) { return (p & 0xff00ff00u) | ((p >> 16) & 0xffu) | ((p & 0xffu) << 16); }
static void rt_ui_rect(uint32_t w, uint32_t h, const uint32_t *px, int x0, int y0, int rw, int rh) {
    pthread_mutex_lock(&g_frame_mu);
    if (w != g_frame_w || h != g_frame_h) { free(g_frame); g_frame = malloc((size_t)w * h * 4); g_frame_w = w; g_frame_h = h; x0 = 0; y0 = 0; rw = (int)w; rh = (int)h; }
    if (g_frame) {
        if (x0 < 0) { rw += x0; x0 = 0; } if (y0 < 0) { rh += y0; y0 = 0; }
        if (x0 + rw > (int)w) rw = (int)w - x0; if (y0 + rh > (int)h) rh = (int)h - y0;
        for (int y = y0; y < y0 + rh; y++) for (int x = x0; x < x0 + rw; x++) g_frame[(size_t)y * w + x] = swz(px[(size_t)y * w + x]);
        g_frame_new = 1;
    }
    pthread_mutex_unlock(&g_frame_mu);
}
static void rt_ui_publish(void *u, uint32_t w, uint32_t h, const uint32_t *px) { (void)u; rt_ui_rect(w, h, px, 0, 0, (int)w, (int)h); }
static void rt_ui_publish_partial(void *u, uint32_t w, uint32_t h, const uint32_t *px, const int32_t r[4]) { (void)u; rt_ui_rect(w, h, px, r[0], r[1], r[2], r[3]); }

static void rt_tick_once(void) { pn_rt_tick(g_rt); }   // the frames come through the sink above

static void *rt_thread(void *arg) {
    (void)arg;
    pthread_setname_np(pthread_self(), "peanuts-tick");
    for (;;) {
        pthread_mutex_lock(&g_tick_mu);
        while (g_tick_signalled - g_tick_done < 2) {   // one tick per two presented frames (30 Hz at 60 fps: enough for a second screen, half the sampling and redraw cost); a stalled present (the game paused) still ticks at 20 Hz for the page's own animations
            struct timespec ts; clock_gettime(CLOCK_REALTIME, &ts); ts.tv_nsec += 50000000; if (ts.tv_nsec >= 1000000000) { ts.tv_sec++; ts.tv_nsec -= 1000000000; }
            if (pthread_cond_timedwait(&g_tick_cv, &g_tick_mu, &ts)) break;
        }
        g_tick_done = g_tick_signalled;
        pthread_mutex_unlock(&g_tick_mu);
        rt_tick_once();
    }
    return NULL;
}

static void rt_tick(void) {
    if (!g_rt) {
        if (g_rt_tried || g_nmod <= 0) return;
        rt_start();
        if (!g_rt) return;
        pthread_t th; pthread_attr_t at; pthread_attr_init(&at); pthread_attr_setdetachstate(&at, PTHREAD_CREATE_DETACHED);
        if (pthread_create(&th, &at, rt_thread, NULL)) __android_log_print(ANDROID_LOG_ERROR, "peanuts", "tick thread failed");
        pthread_attr_destroy(&at);
        return;
    }
    pthread_mutex_lock(&g_tick_mu); g_tick_signalled++; pthread_cond_signal(&g_tick_cv); pthread_mutex_unlock(&g_tick_mu);
}

void nxhost_peanuts_frame_size(int *w, int *h) {
    pthread_mutex_lock(&g_frame_mu);
    if (w) *w = (int)g_frame_w; if (h) *h = (int)g_frame_h;
    pthread_mutex_unlock(&g_frame_mu);
}

// The newest frame as RGBA8 bytes (Android's ARGB_8888 bitmaps hold R, G, B, A in memory; the runtime's pixels are 0xAARRGGBB words)
int nxhost_peanuts_frame(void *out_rgba, int w, int h) {
    int got = 0;
    pthread_mutex_lock(&g_frame_mu);
    if (g_frame && g_frame_new && (uint32_t)w == g_frame_w && (uint32_t)h == g_frame_h) {
        memcpy(out_rgba, g_frame, (size_t)w * h * 4);   // already R, G, B, A bytes (swizzled on publish)
        g_frame_new = 0; got = 1;
    }
    pthread_mutex_unlock(&g_frame_mu);
    return got;
}

void nxhost_peanuts_touch(int id, float x, float y, int down) { if (g_rt && x >= 0 && y >= 0) pn_rt_touch(g_rt, (uint32_t)id, (uint32_t)x, (uint32_t)y, down); }

size_t nxhost_peanuts_snapshot(char *out, size_t n) {
    if (!out || !n) return 0;
    pthread_mutex_lock(&g_mu);
    size_t len = strlen(g_snapshot); if (len >= n) len = n - 1;
    memcpy(out, g_snapshot, len); out[len] = 0;
    pthread_mutex_unlock(&g_mu);
    return len;
}
