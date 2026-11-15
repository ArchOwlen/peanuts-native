// PEANUTS: guest memory given back inside a placed segment (dm/gfx/vk_android/vka_holes.h; vka_mem.c "Ranges given back inside placed memory"): the table of holes
// (ranges joined, cut, looked up), what a give-back means for a segment, the parts of a segment a lookup answers for, and a model of vka_mem_release, the lookups and
// the placing again (vka_mem.c compact, join, unplace) checked page by page against a plain map of what is placed
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "../../gfx/vk_android/vka_holes.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static int sane(const vkh_tab *t) {   // sorted, disjoint, not touching, none empty
    for (int i = 0; i < t->n; i++) { if (t->r[i].hi <= t->r[i].lo) return 0; if (i && t->r[i].lo <= t->r[i - 1].hi) return 0; }
    return 1;
}

// The model: segments as vka_mem.c's table holds them (sorted, disjoint), and per page what the GPU sees of the CPU's pages: 0 no segment, 1 placed, 2 a hole
enum { PG = 4096, NPG = 512, MAXSEG = 64 };
typedef struct { uint64_t lo, hi; } seg;
static seg g_s[MAXSEG]; static int g_n; static vkh_tab g_h; static unsigned char g_want[NPG];
static const uint64_t BASE = 0x4000000000ull;
static uint64_t pa(int p) { return BASE + (uint64_t)p * PG; }
static void seg_del(int k) { memmove(&g_s[k], &g_s[k + 1], (size_t)(g_n - k - 1) * sizeof *g_s); g_n--; }
static void place(int p0, int p1) {   // a new segment over pages [p0, p1) (the caller picked a free range)
    int i = 0; while (i < g_n && g_s[i].lo < pa(p0)) i++;
    memmove(&g_s[i + 1], &g_s[i], (size_t)(g_n - i) * sizeof *g_s); g_s[i] = (seg){pa(p0), pa(p1)}; g_n++;
    for (int p = p0; p < p1; p++) g_want[p] = 1;
}
static void release(uint64_t lo, uint64_t hi) {   // vka_mem_release, then unplace_all's holes_gone for the segments that went
    for (int k = 0; k < g_n;) {
        uint64_t clo, chi; int w = vkh_release(g_s[k].lo, g_s[k].hi, lo, hi, &clo, &chi);
        if (!w) { k++; continue; }
        for (uint64_t a = clo; a < chi; a += PG) g_want[(a - BASE) / PG] = 2;
        if (w == 2 && vkh_add(&g_h, clo, chi) && !vkh_covers(&g_h, g_s[k].lo, g_s[k].hi)) { k++; continue; }
        for (uint64_t a = g_s[k].lo; a < g_s[k].hi; a += PG) g_want[(a - BASE) / PG] = 0;
        CHECK(vkh_cut(&g_h, g_s[k].lo, g_s[k].hi), "cut"); seg_del(k);
    }
}
static void heal(int k) {   // compact: the segment's map made whole
    CHECK(vkh_cut(&g_h, g_s[k].lo, g_s[k].hi), "cut");
    for (uint64_t a = g_s[k].lo; a < g_s[k].hi; a += PG) g_want[(a - BASE) / PG] = 1;
}
static void join_all(void) {   // vka_mem_join_due's coalescing: segments end to end become one, and the holes in them end
    for (int k = 0; k + 1 < g_n;) {
        if (g_s[k].hi != g_s[k + 1].lo) { k++; continue; }
        g_s[k].hi = g_s[k + 1].hi; seg_del(k + 1);
        CHECK(vkh_cut(&g_h, g_s[k].lo, g_s[k].hi), "cut");
        for (uint64_t a = g_s[k].lo; a < g_s[k].hi; a += PG) g_want[(a - BASE) / PG] = 1;
    }
}
static int lookup(uint64_t a, uint64_t *plo, uint64_t *phi) {   // vka_mem.c lookup: the part of a's segment that holds a and no hole
    for (int k = 0; k < g_n; k++) if (g_s[k].lo <= a && a < g_s[k].hi) return vkh_piece(&g_h, g_s[k].lo, g_s[k].hi, a, plo, phi);
    return 0;
}
static void verify(const char *what, int step) {
    CHECK(sane(&g_h), "%s %d: the table is not sorted and disjoint", what, step);
    uint64_t hb = 0;
    for (int p = 0; p < NPG; p++) {
        uint64_t plo = 0, phi = 0; int got = lookup(pa(p), &plo, &phi);
        CHECK(got == (g_want[p] == 1), "%s %d: page %d placed %d, the model says %d", what, step, p, got, g_want[p]);
        if (got) {   // the part holds the page, lies in its segment, and every page of it is placed
            CHECK(plo <= pa(p) && pa(p) < phi, "%s %d: page %d outside its part", what, step, p);
            for (uint64_t a = plo; a < phi; a += PG) CHECK(g_want[(a - BASE) / PG] == 1, "%s %d: the part of page %d holds page %llu, which is not placed", what, step, p, (unsigned long long)((a - BASE) / PG));
        }
        if (g_want[p] == 2) hb += PG;
        CHECK(vkh_over(&g_h, pa(p), pa(p) + PG) == (g_want[p] == 2), "%s %d: page %d hole %d, the model says %d", what, step, p, vkh_over(&g_h, pa(p), pa(p) + PG), g_want[p]);
    }
    CHECK(vkh_bytes(&g_h) == hb, "%s %d: %llu bytes of holes, the model says %llu", what, step, (unsigned long long)vkh_bytes(&g_h), (unsigned long long)hb);
    for (int k = 0; k < g_n; k++) {   // the parts in order (vka_mem_chunks) are exactly the placed pages of the segment
        uint64_t at = g_s[k].lo, a, e, prev = 0, n = 0, want = 0;
        while (vkh_next_piece(&g_h, g_s[k].lo, g_s[k].hi, &at, &a, &e)) {
            CHECK(a < e && a >= g_s[k].lo && e <= g_s[k].hi && (!n || a > prev), "%s %d: parts out of order", what, step);
            for (uint64_t x = a; x < e; x += PG) CHECK(g_want[(x - BASE) / PG] == 1, "%s %d: a part holds a page that is not placed", what, step);
            n += e - a; prev = e;
        }
        for (uint64_t x = g_s[k].lo; x < g_s[k].hi; x += PG) want += g_want[(x - BASE) / PG] == 1 ? PG : 0;
        CHECK(n == want, "%s %d: the parts of segment %d hold %llu bytes, the model says %llu", what, step, k, (unsigned long long)n, (unsigned long long)want);
        CHECK(!vkh_covers(&g_h, g_s[k].lo, g_s[k].hi), "%s %d: segment %d is all holes and still there", what, step, k);
    }
}
static unsigned g_rng = 12345;
static unsigned rnd(unsigned n) { g_rng = g_rng * 1103515245u + 12345u; return (g_rng >> 8) % n; }

int main(void) {
    // The table: ranges that overlap or touch are one
    { vkh_tab t = {0};
      CHECK(vkh_add(&t, 100, 200) && vkh_add(&t, 300, 400) && t.n == 2, "two ranges apart");
      CHECK(vkh_add(&t, 200, 300) && t.n == 1 && t.r[0].lo == 100 && t.r[0].hi == 400, "the range between them joins all three");
      CHECK(vkh_add(&t, 50, 60) && vkh_add(&t, 500, 600) && t.n == 3 && sane(&t), "one before, one after");
      CHECK(vkh_add(&t, 0, 1000) && t.n == 1 && t.r[0].lo == 0 && t.r[0].hi == 1000, "one over all");
      CHECK(vkh_add(&t, 10, 10) && t.n == 1, "an empty range changes nothing");
      // Cuts: in the middle (two afterwards), at either end, over several
      CHECK(vkh_cut(&t, 400, 500) && t.n == 2 && t.r[0].hi == 400 && t.r[1].lo == 500, "a cut in the middle makes two");
      CHECK(vkh_cut(&t, 0, 100) && t.r[0].lo == 100 && vkh_cut(&t, 900, 2000) && t.r[1].hi == 900 && t.n == 2, "cuts at the ends");
      CHECK(vkh_cut(&t, 350, 550) && t.n == 2 && t.r[0].hi == 350 && t.r[1].lo == 550, "a cut across two");
      CHECK(vkh_cut(&t, 0, 2000) && t.n == 0, "a cut over all");
      free(t.r); }
    // A give-back and a segment
    { uint64_t a, e;
      CHECK(vkh_release(1000, 2000, 0, 500, &a, &e) == 0 && vkh_release(1000, 2000, 2000, 3000, &a, &e) == 0, "beside the segment");
      CHECK(vkh_release(1000, 2000, 0, 3000, &a, &e) == 1 && vkh_release(1000, 2000, 1000, 2000, &a, &e) == 1, "over all of it");
      CHECK(vkh_release(1000, 2000, 0, 1500, &a, &e) == 2 && a == 1000 && e == 1500, "its start");
      CHECK(vkh_release(1000, 2000, 1800, 3000, &a, &e) == 2 && a == 1800 && e == 2000, "its end");
      CHECK(vkh_release(1000, 2000, 1200, 1300, &a, &e) == 2 && a == 1200 && e == 1300, "its middle"); }
    // The three give-backs of the Pokemon Brilliant Diamond log (2026-11-14), each under a joined segment: the addresses the replay then missed stay placed
    { struct { uint64_t slo, ssz, lo, sz, probe; } c[] = {
          {0x405dd14000ull, 0x2000000, 0x405ed40000ull, 0x40000, 0x405dd14000ull},      // 256 KiB in the middle of 32 MiB
          {0x406968d000ull, 0xd000000, 0x4069900000ull, 0x40000, 0x406af05c00ull},      // 256 KiB inside 208 MiB: the storage buffer the replay then did not bind
          {0x4059612000ull, 0x2000000, 0x4054300000ull, 0x6300000, 0x405ac92000ull} };  // 99 MiB ending 15.9 MiB into 32 MiB: the vertex stream that then read zeros
      for (int i = 0; i < 3; i++) {
          vkh_tab t = {0}; uint64_t a, e, plo, phi, shi = c[i].slo + c[i].ssz;
          CHECK(vkh_release(c[i].slo, shi, c[i].lo, c[i].lo + c[i].sz, &a, &e) == 2, "log case %d: part of the segment", i);
          CHECK(vkh_add(&t, a, e) && !vkh_covers(&t, c[i].slo, shi), "log case %d: a hole, the segment stays", i);
          CHECK(vkh_piece(&t, c[i].slo, shi, c[i].probe, &plo, &phi) && plo <= c[i].probe && c[i].probe < phi, "log case %d: %#llx stays placed", i, (unsigned long long)c[i].probe);
          CHECK(!vkh_piece(&t, c[i].slo, shi, a, &plo, &phi) && !vkh_piece(&t, c[i].slo, shi, e - 1, &plo, &phi), "log case %d: the range given back is not placed", i);
          if (a > c[i].slo) CHECK(vkh_piece(&t, c[i].slo, shi, a - 1, &plo, &phi) && plo == c[i].slo && phi == a, "log case %d: the part before the hole", i);
          if (e < shi) CHECK(vkh_piece(&t, c[i].slo, shi, e, &plo, &phi) && plo == e && phi == shi, "log case %d: the part after the hole", i);
          CHECK(vkh_cut(&t, c[i].slo, shi) && t.n == 0 && vkh_piece(&t, c[i].slo, shi, a, &plo, &phi) && plo == c[i].slo && phi == shi, "log case %d: placed again, the whole segment", i);
          free(t.r);
      } }
    // The model, by hand: three segments, the middle two end to end; a give-back across all of them
    place(10, 20); place(30, 40); place(40, 50); verify("hand", 0);
    release(pa(15), pa(45)); verify("hand", 1);   // the end of the first, all of the second, the start of the third
    CHECK(g_n == 2 && g_s[0].lo == pa(10) && g_s[1].lo == pa(40), "the segment covered whole went, the two others stay (%d segments)", g_n);
    { uint64_t a, e; CHECK(lookup(pa(12), &a, &e) && a == pa(10) && e == pa(15), "the first segment's part before the hole");
      CHECK(lookup(pa(47), &a, &e) && a == pa(45) && e == pa(50), "the third segment's part after the hole");
      CHECK(!lookup(pa(17), &a, &e) && !lookup(pa(35), &a, &e) && !lookup(pa(42), &a, &e), "nothing given back is placed"); }
    release(pa(10), pa(15)); verify("hand", 2);   // the rest of the first: every byte of it is given back now, it goes
    CHECK(g_n == 1 && g_s[0].lo == pa(40), "a segment whose every byte was given back goes");
    release(pa(47), pa(48)); verify("hand", 3);   // a second hole in the middle of what is left
    { uint64_t a, e; CHECK(lookup(pa(46), &a, &e) && a == pa(45) && e == pa(47), "the part between two holes");
      CHECK(lookup(pa(49), &a, &e) && a == pa(48) && e == pa(50), "the part after the second hole"); }
    heal(0); verify("hand", 4);
    { uint64_t a, e; CHECK(lookup(pa(42), &a, &e) && a == pa(40) && e == pa(50) && g_h.n == 0, "placed again: one part, no hole left"); }
    release(pa(0), pa(NPG)); verify("hand", 5); CHECK(g_n == 0 && g_h.n == 0, "everything given back");
    // The model at random: placements, give-backs of any size at any place, placing again, joins
    for (int step = 0; step < 20000; step++) {
        unsigned op = rnd(100);
        if (op < 30 && g_n < MAXSEG - 1) {   // a segment in a free range
            int p0 = (int)rnd(NPG), len = 1 + (int)rnd(48), p1 = p0;
            while (p1 < NPG && p1 - p0 < len && !g_want[p1]) p1++;
            if (p1 > p0) place(p0, p1);
        } else if (op < 75) {
            int p0 = (int)rnd(NPG), len = 1 + (int)rnd(rnd(4) ? 8 : 200);
            release(pa(p0), pa(p0 + len > NPG ? NPG : p0 + len));
        } else if (op < 90 && g_n) heal((int)rnd((unsigned)g_n));
        else if (op >= 95) join_all();
        verify("random", step);
        if (fails > 20) break;
    }
    free(g_h.r);
    if (fails) { printf("t_android_holes: %d FAILED\n", fails); return 1; }
    printf("t_android_holes: ok\n");
    return 0;
}
