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
#include <sys/stat.h>

#define MAX_CHAINS 64
#define MAX_ELEMS 8
#define REFRESH_EVERY 2   // frames between refreshes (a 10 fps game reads 5 times a second; a 60 fps one 30)
#define MAX_CAND (4u << 20)
#define CHUNK (1u << 20)

typedef struct { char name[32]; char type[8]; char module[16]; uint64_t off[MAX_ELEMS]; int n; char value[72]; } chain_t;
typedef struct { uint64_t addr; uint64_t last; } cand_t;

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
    if (pthread_mutex_trylock(&g_scan_mu) == 0) {   // a running scan keeps its lines until it is done
        p += snprintf(p, e - p, "scan=%s\n", g_scan_state);
        for (int i = 0; i < g_scan_nlines && p < e; i++) p += snprintf(p, e - p, "cand%d=%s\n", i, g_scan_lines[i]);
        pthread_mutex_unlock(&g_scan_mu);
    } else p += snprintf(p, e - p, "scan=%s\n", g_scan_state);
}

void peanuts_tick(void) {
    g_frames++;
    if (g_frames % REFRESH_EVERY) return;
    pthread_mutex_lock(&g_mu);
    refresh();
    pthread_mutex_unlock(&g_mu);
}

size_t nxhost_peanuts_snapshot(char *out, size_t n) {
    if (!out || !n) return 0;
    pthread_mutex_lock(&g_mu);
    size_t len = strlen(g_snapshot); if (len >= n) len = n - 1;
    memcpy(out, g_snapshot, len); out[len] = 0;
    pthread_mutex_unlock(&g_mu);
    return len;
}
