// comp_test — the compressed NCA sections (nx_nca.c) against an independent LZ4: dumps LZ4 entries of a compressed section, each as the raw block
// (<out>/e<k>.lz4, nx_sec_read_raw at the entry's physical offset) and as the importer's decompression (<out>/e<k>.dec, nx_sec_read of its virtual
// range), with a manifest (<out>/entries.txt: index, virtual offset, virtual size, physical size) for tests/comp_check.py (python3 lz4.block).
// The table is parsed here again from the fs header (the importer's parse stays internal), so the layout reading is checked too.
//   comp_test [--keys prod.keys] <nsp|xci> <nca name> <out dir> [count = 8]
#define _POSIX_C_SOURCE 200809L
#include "nximport.h"
#include "nx_internal.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static uint32_t u32(const uint8_t *p) { uint32_t v; memcpy(&v, p, 4); return v; }
static uint64_t u64(const uint8_t *p) { uint64_t v; memcpy(&v, p, 8); return v; }
static int die(const char *what) { fprintf(stderr, "%s: %s\n", what, nximport_last_error()); return 1; }
static int dump(const char *dir, int k, const char *ext, const uint8_t *p, size_t n) {
    char path[1200]; snprintf(path, sizeof path, "%s/e%d.%s", dir, k, ext);
    FILE *f = fopen(path, "wb"); if (!f || fwrite(p, 1, n, f) != n) { perror(path); return 1; } fclose(f); return 0;
}

int main(int argc, char **argv) {
    const char *keys = getenv("NX_KEYS"); int a = 1;
    if (a + 1 < argc && !strcmp(argv[a], "--keys")) { keys = argv[a + 1]; a += 2; }
    if (a + 3 > argc) { fprintf(stderr, "usage: comp_test [--keys prod.keys] <nsp|xci> <nca name> <out dir> [count]\n"); return 2; }
    const char *path = argv[a], *name = argv[a + 1], *dir = argv[a + 2]; int count = a + 3 < argc ? atoi(argv[a + 3]) : 8;
    if (nximport_keys(keys ? keys : "")) return die("keys");
    NxContainer c; if (nx_container_open(&c, path)) return die("open");
    const NxEntry *e = nx_container_find(&c, name); if (!e) { fprintf(stderr, "no entry %s\n", name); return 1; }
    NxNca n; if (nx_nca_open(&n, &c, e)) return die("nca");
    int s = -1; for (int i = 0; i < 4; i++) if (n.sec[i].present && n.sec[i].compressed) { s = i; break; }
    if (s < 0) { fprintf(stderr, "%s has no compressed section\n", name); return 1; }
    NxSection *S = &n.sec[s]; const uint8_t *ci = n.hdr + 0x400 + 0x200 * s + 0x178;
    uint64_t toff = u64(ci), tsize = u64(ci + 8); uint32_t cnt = u32(ci + 0x18);
    const uint32_t per = (0x4000 - 0x10) / 0x18, sets = (cnt + per - 1) / per;
    printf("section %d: table @%#llx+%#llx from data_off %#llx, %u entries in %u sets\n", s, (unsigned long long)toff, (unsigned long long)tsize, (unsigned long long)S->data_off, cnt, sets);
    uint8_t *tb = malloc((size_t)0x4000 * (1 + sets)); if (!tb || nx_sec_read_raw(&n, s, S->data_off + toff, tb, (size_t)0x4000 * (1 + sets))) return die("table");
    uint64_t total = u64(tb + 8); printf("root: %u sets, virtual end %llu\n", u32(tb + 4), (unsigned long long)total);
    // every LZ4 entry's index, then `count` of them spread over the section (and the first and the last)
    uint32_t *lz = malloc(sizeof *lz * cnt), nlz = 0, types[8] = { 0 };
    for (uint32_t i = 0; i < sets; i++) { const uint8_t *bk = tb + 0x4000 * (i + 1); uint32_t ne = u32(bk + 4); for (uint32_t j = 0; j < ne; j++) { uint8_t t = bk[0x10 + 0x18 * j + 0x10]; types[t & 7]++; if (t == 3) lz[nlz++] = i * per + j; } }
    printf("types: none %u zeros %u two %u lz4 %u other %u\n", types[0], types[1], types[2], types[3], types[4] + types[5] + types[6] + types[7]);
    if (!nlz) { fprintf(stderr, "no LZ4 entries\n"); return 1; }
    char mpath[1200]; snprintf(mpath, sizeof mpath, "%s/entries.txt", dir); FILE *mf = fopen(mpath, "w"); if (!mf) { perror(mpath); return 1; }
    // the RomFS/PFS0 header through the layer, as a first sanity check
    uint8_t hd[0x50]; if (nx_sec_read(&n, s, S->data_off, hd, sizeof hd)) return die("header");
    printf("data level header: %02x %02x %02x %02x %02x %02x %02x %02x (%s)\n", hd[0], hd[1], hd[2], hd[3], hd[4], hd[5], hd[6], hd[7], S->fs_type == NX_FS_ROMFS ? (u64(hd) == 0x50 ? "RomFS header size 0x50 ok" : "NOT a RomFS header") : (!memcmp(hd, "PFS0", 4) ? "PFS0 ok" : "NOT a PFS0"));
    if (count < 2) count = 2;
    for (int k = 0; k < count; k++) {
        uint32_t idx = lz[k == count - 1 ? nlz - 1 : (uint32_t)((uint64_t)nlz * (uint64_t)k / (uint64_t)count)];
        const uint8_t *en = tb + 0x4000 * (1 + idx / per) + 0x10 + 0x18 * (idx % per);
        uint64_t virt = u64(en), phys = u64(en + 8); uint32_t psize = u32(en + 0x14);
        uint64_t next = idx + 1 < cnt ? u64(tb + 0x4000 * (1 + (idx + 1) / per) + 0x10 + 0x18 * ((idx + 1) % per)) : total;
        size_t vs = (size_t)(next - virt);
        uint8_t *raw = malloc(psize), *dec = malloc(vs); if (!raw || !dec) { fprintf(stderr, "out of memory\n"); return 1; }
        if (nx_sec_read_raw(&n, s, S->data_off + phys, raw, psize)) return die("raw");
        if (nx_sec_read(&n, s, S->data_off + virt, dec, vs)) return die("decompressed");
        if (dump(dir, k, "lz4", raw, psize) || dump(dir, k, "dec", dec, vs)) return 1;
        fprintf(mf, "%d %u %llu %zu %u\n", k, idx, (unsigned long long)virt, vs, psize);
        printf("  e%d: entry %u virtual %#llx+%zu physical %#llx+%u\n", k, idx, (unsigned long long)virt, vs, (unsigned long long)phys, psize);
        free(raw); free(dec);
    }
    fclose(mf); free(tb); free(lz); nx_nca_close(&n); nx_container_close(&c);
    return 0;
}
