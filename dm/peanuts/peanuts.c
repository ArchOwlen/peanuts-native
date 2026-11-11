// Peanuts Native, host side: see peanuts.h.
// The chains file, $HOME/peanuts/chains.tsv (HOME is the app's files folder, as nxlaunch sets it), one value a line:
//     <name> <TAB> <type> <TAB> <module>+0x<offset>[,0x<offset>...]
// The first element is a module's base plus an offset (no read); every later element reads a 64-bit pointer at the address so far and adds its offset (the DSMod
// package convention: three dereferences take four elements). Types: u8 u16 u32 u64 s8 s16 s32 s64 f32 f64 str (up to 63 bytes). Lines starting with # are comments.
// The file is re-read when it changes, so a new chain is an adb push away while the game runs.
#include "peanuts.h"
#include "../host/dm.h"
#include "../host/plat/plat.h"
#include <inttypes.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

#define MAX_CHAINS 64
#define MAX_ELEMS 8
#define REFRESH_EVERY 2   // frames between refreshes (a 10 fps game reads 5 times a second; a 60 fps one 30)

typedef struct { char name[32]; char type[8]; char module[16]; uint64_t off[MAX_ELEMS]; int n; char value[72]; } chain_t;

static chain_t g_chains[MAX_CHAINS];
static int g_nchains;
static char g_path[512], g_file_state[64] = "none";
static time_t g_mtime;
static uint64_t g_frames;
static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static char g_snapshot[4096];

static void load_chains(void) {
    if (!g_path[0]) { const char *home = getenv("HOME"); snprintf(g_path, sizeof g_path, "%s/peanuts/chains.tsv", home ? home : "."); }
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
    snprintf(g_file_state, sizeof g_file_state, "%d chains%s%s", n, bad ? ", bad lines: " : "", bad ? "yes" : "");
    if (bad) snprintf(g_file_state, sizeof g_file_state, "%d chains, %d bad lines", n, bad);
}

static module_t *module_named(const char *name) {
    for (int i = 0; i < g_nmod; i++) if (!g_mod[i].nro && !strcmp(g_mod[i].name, name)) return &g_mod[i];
    return NULL;
}

static void read_chain(chain_t *c) {
    module_t *m = module_named(c->module);
    if (!m) { snprintf(c->value, sizeof c->value, "?no module %s", c->module); return; }
    uint64_t addr = (uint64_t)(uintptr_t)m->base + c->off[0];
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

static void refresh(void) {
    load_chains();
    for (int i = 0; i < g_nchains; i++) read_chain(&g_chains[i]);
    module_t *main_mod = module_named("main");
    char *p = g_snapshot, *e = g_snapshot + sizeof g_snapshot;
    p += snprintf(p, e - p, "frames=%" PRIu64 "\nbuild=%s\nmain=%#" PRIx64 "\nchains=%s\n", g_frames, dm_main_build(), main_mod ? (uint64_t)(uintptr_t)main_mod->base : 0, g_file_state);
    for (int i = 0; i < g_nchains && p < e; i++) p += snprintf(p, e - p, "%s=%s\n", g_chains[i].name, g_chains[i].value);
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
