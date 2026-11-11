// Peanuts Native: the companion runtime's RomFS provider over a .nup container. See peanuts_nup.h.
#include "peanuts_nup.h"
#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// nup_image.h logs through the host's LOG (dm.h). This unit does not include dm.h (it needs the game profile): its lines go to logcat under the
// companion's tag on Android, to stderr elsewhere (the Linux test)
#if defined(__ANDROID__) && !defined(PN_NUP_HOST_TEST)
#include <android/log.h>
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "peanuts", "[nup] " __VA_ARGS__)
#else
#define LOG(...) do { fprintf(stderr, "[peanuts nup] " __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif
#include "../host/nup_image.h"

enum { LAYERS = 2 };   // the spec's package, then the container's base
typedef struct { romfs_img *m; const img_ent *e; const uint8_t *z; } handle_t;   // z: the decompressed bytes a zstd entry's open holds (nup_z_get), else NULL
struct pn_nup {
    romfs_img *layer[LAYERS];
    int nlayers;
    pthread_mutex_t lock;   // the handle table
    handle_t *h; int nh, cap;
    char what[320];
};

int pn_nup_is_spec(const char *spec) { return nup_is_spec(spec); }

// The entry a RomFS path names in an image (hle_fs.c img_find: a binary search of each directory's sorted children); -1 when there is none
static int64_t find(const romfs_img *m, const char *rest) {
    uint64_t k = 0;
    while (*rest) {
        while (*rest == '/') rest++;
        if (!*rest) break;
        const char *end = strchr(rest, '/'); if (!end) end = rest + strlen(rest);
        size_t len = (size_t)(end - rest);
        if (!(len == 1 && rest[0] == '.')) {
            if (m->e[k].type) return -1;
            uint64_t lo = m->e[k].a, hi = lo + m->e[k].b; int found = 0;
            while (lo < hi) {
                uint64_t mid = lo + (hi - lo) / 2; const img_ent *x = &m->e[mid];
                int c = memcmp(m->names + x->name, rest, x->nlen < len ? x->nlen : len);
                if (!c) c = (x->nlen > len) - (x->nlen < len);
                if (!c) { k = mid; found = 1; break; }
                if (c < 0) lo = mid + 1; else hi = mid;
            }
            if (!found) return -1;
        }
        rest = end;
    }
    return (int64_t)k;
}

pn_nup *pn_nup_open(const char *spec, char *why, size_t wn) {
    char file[1100], sel[128];
    if (!nup_split(spec, file, sizeof file, sel, sizeof sel)) { snprintf(why, wn, "not a .nup spec"); return NULL; }
    pn_nup *n = calloc(1, sizeof *n);
    if (!n) { snprintf(why, wn, "out of memory"); return NULL; }
    pthread_mutex_init(&n->lock, NULL);
    if (!(n->layer[0] = nup_image_open(spec, why, wn))) { pn_nup_close(n); return NULL; }
    n->nlayers = 1;
    const char *b = strrchr(file, '/');
    int p = snprintf(n->what, sizeof n->what, "%s %s (%llu entries)", b ? b + 1 : file, sel[0] ? sel : "(newest)", (unsigned long long)n->layer[0]->n);
    // The base package under an update's RomFS: a path the update lacks is looked up there (an explicit base, or no selector -- the newest update, else the base -- needs none)
    if (sel[0] && strcmp(sel, "base")) {
        char bspec[1300], bwhy[200] = ""; snprintf(bspec, sizeof bspec, "%s:base", file);
        romfs_img *base = nup_image_open(bspec, bwhy, sizeof bwhy);
        if (base) { n->layer[n->nlayers++] = base; snprintf(n->what + p, sizeof n->what - (size_t)p, " + base (%llu entries)", (unsigned long long)base->n); }
        else snprintf(n->what + p, sizeof n->what - (size_t)p, " + base (none: %s)", bwhy);
    }
    return n;
}

void pn_nup_close(pn_nup *n) {
    if (!n) return;
    for (int i = 0; i < n->nh; i++) if (n->h[i].e && n->h[i].z) nup_z_put(n->h[i].m, n->h[i].e->a);
    for (int i = 0; i < n->nlayers; i++) romfs_image_close(n->layer[i]);
    free(n->h); pthread_mutex_destroy(&n->lock); free(n);
}

const char *pn_nup_describe(const pn_nup *n) { return n ? n->what : "none"; }

void pn_nup_provider(pn_nup *n, PnRtRomfs *out) {
    memset(out, 0, sizeof *out);
    out->user = n; out->open = pn_nup_romfs_open; out->read = pn_nup_romfs_read; out->close = pn_nup_romfs_close; out->list = pn_nup_romfs_list;
}

int pn_nup_romfs_open(void *user, const char *path, uint64_t *size) {
    pn_nup *n = user;
    for (int li = 0; li < n->nlayers; li++) {
        romfs_img *m = n->layer[li]; int64_t k = find(m, path);
        if (k < 0) continue;
        const img_ent *e = &m->e[k];
        if (!e->type) return -1;   // a directory (the lower layer cannot hold a file under a name the upper layer gives a directory)
        const uint8_t *z = NULL;
        if (e->flags & IMG_ENT_ZSTD) {
            char why[200] = "";
            if (!(z = nup_z_get(m, e->a, why, sizeof why))) { LOG("[RED] companion romfs %s: cannot decompress it from %s: %s", path, n->what, why); return -1; }
        }
        pthread_mutex_lock(&n->lock);
        int h = -1;
        for (int i = 0; i < n->nh; i++) if (!n->h[i].e) { h = i; break; }
        if (h < 0) {
            if (n->nh == n->cap) { int cap = n->cap ? n->cap * 2 : 64; handle_t *g = realloc(n->h, sizeof *g * (size_t)cap); if (!g) { pthread_mutex_unlock(&n->lock); if (z) nup_z_put(m, e->a); return -1; } n->h = g; n->cap = cap; }
            h = n->nh++;
        }
        n->h[h].m = m; n->h[h].e = e; n->h[h].z = z;
        pthread_mutex_unlock(&n->lock);
        if (size) *size = e->b;
        return h;
    }
    return -1;
}

static int handle_get(pn_nup *n, int h, handle_t *out) {
    if (h < 0) return 0;
    pthread_mutex_lock(&n->lock);
    int ok = h < n->nh && n->h[h].e; if (ok) *out = n->h[h];
    pthread_mutex_unlock(&n->lock);
    return ok;
}

int64_t pn_nup_romfs_read(void *user, int handle, uint64_t off, void *buf, uint64_t len) {
    handle_t x;
    if (!handle_get(user, handle, &x)) return -1;
    if (off >= x.e->b) return 0;
    if (len > x.e->b - off) len = x.e->b - off;
    if (!len) return 0;
    if (x.z) { memcpy(buf, x.z + off, (size_t)len); return (int64_t)len; }
    if (!romfs_image_read(x.m->fd, buf, (size_t)len, (off_t)(x.e->a + off))) return -1;
    return (int64_t)len;
}

void pn_nup_romfs_close(void *user, int handle) {
    pn_nup *n = user; handle_t x = {0};
    pthread_mutex_lock(&n->lock);
    if (handle >= 0 && handle < n->nh && n->h[handle].e) { x = n->h[handle]; memset(&n->h[handle], 0, sizeof n->h[handle]); }
    pthread_mutex_unlock(&n->lock);
    if (x.z) nup_z_put(x.m, x.e->a);
}

// The children of a directory: the upper layer's, then the lower layer's that the upper does not name. 1 when any layer has the directory
int pn_nup_romfs_list(void *user, const char *dir, void (*cb)(void *, const char *, int), void *cb_user) {
    pn_nup *n = user; const romfs_img *seen[LAYERS]; int64_t seen_k[LAYERS]; int ns = 0;
    for (int li = 0; li < n->nlayers; li++) {
        const romfs_img *m = n->layer[li]; int64_t k = find(m, dir);
        if (k < 0 || m->e[k].type) continue;
        if (cb) {
            const img_ent *d = &m->e[k];
            for (uint64_t j = 0; j < d->b; j++) {
                const img_ent *x = &m->e[d->a + j]; char name[0x301];
                memcpy(name, m->names + x->name, x->nlen); name[x->nlen] = 0;
                int shadowed = 0;
                for (int s = 0; s < ns && !shadowed; s++) {   // named by an upper layer already?
                    const img_ent *u = &seen[s]->e[seen_k[s]]; uint64_t lo = u->a, hi = lo + u->b;
                    while (lo < hi) {
                        uint64_t mid = lo + (hi - lo) / 2; const img_ent *y = &seen[s]->e[mid];
                        int c = memcmp(seen[s]->names + y->name, name, y->nlen < x->nlen ? y->nlen : x->nlen);
                        if (!c) c = (y->nlen > x->nlen) - (y->nlen < x->nlen);
                        if (!c) { shadowed = 1; break; }
                        if (c < 0) lo = mid + 1; else hi = mid;
                    }
                }
                if (!shadowed) cb(cb_user, name, !x->type);
            }
        }
        seen[ns] = m; seen_k[ns] = k; ns++;
    }
    return ns > 0;
}
