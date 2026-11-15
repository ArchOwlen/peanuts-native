// PEANUTS: guest memory given back inside a placed segment (vka_mem.c vka_mem_release, "Ranges given back inside placed memory"): the range arithmetic, free of Vulkan
// so the unit test (dm/host/tests/t_android_holes.c) can check it. A hole is a range of a placed segment whose CPU pages the host took back (UnmapPhysicalMemory, a
// heap shrink): the segment keeps its Vulkan memory, its buffer and its place in the table, so every other byte of it stays GPU-visible, and only the hole's addresses
// answer "not placed" until the segment's CPU map is made whole again. The table is the set of those addresses: ranges sorted by start, disjoint and not touching
// (two that touch or overlap are one), so it does not matter which segment a range was given back under, nor that segments are joined or given back later
#ifndef VKA_HOLES_H
#define VKA_HOLES_H
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

typedef struct { uint64_t lo, hi; } vkh_range;
typedef struct { vkh_range *r; int n, cap; } vkh_tab;

// The first hole that ends after a (the one holding a when its start is at or below a), or n
static inline int vkh_find(const vkh_tab *t, uint64_t a) {
    int lo = 0, hi = t->n;
    while (lo < hi) { int m = lo + (hi - lo) / 2; if (t->r[m].hi <= a) lo = m + 1; else hi = m; }
    return lo;
}
static inline int vkh_room(vkh_tab *t) {   // room for one more range; 0 = no memory (the table as it was)
    if (t->n < t->cap) return 1;
    int nc = t->cap ? 2 * t->cap : 16; vkh_range *nr = realloc(t->r, (size_t)nc * sizeof *nr);
    if (!nr) return 0;
    t->r = nr; t->cap = nc; return 1;
}
// [lo, hi) becomes a hole, joined with the holes it overlaps or touches. 1 = done, 0 = no memory (the table as it was)
static inline int vkh_add(vkh_tab *t, uint64_t lo, uint64_t hi) {
    if (hi <= lo) return 1;
    int i = 0, j; while (i < t->n && t->r[i].hi < lo) i++;   // the first that overlaps or touches
    for (j = i; j < t->n && t->r[j].lo <= hi; j++) { if (t->r[j].lo < lo) lo = t->r[j].lo; if (t->r[j].hi > hi) hi = t->r[j].hi; }
    if (i == j) {
        if (!vkh_room(t)) return 0;
        memmove(&t->r[i + 1], &t->r[i], (size_t)(t->n - i) * sizeof *t->r); t->n++;
    } else if (j > i + 1) { memmove(&t->r[i + 1], &t->r[j], (size_t)(t->n - j) * sizeof *t->r); t->n -= j - i - 1; }
    t->r[i] = (vkh_range){lo, hi};
    return 1;
}
// No address of [lo, hi) is a hole any more (its segment went, or its CPU map was made whole). 1 = done, 0 = no memory for the one case that needs it (a hole reaching
// past both ends is two afterwards): the table as it was
static inline int vkh_cut(vkh_tab *t, uint64_t lo, uint64_t hi) {
    if (hi <= lo) return 1;
    int i = vkh_find(t, lo);
    if (i < t->n && t->r[i].lo < lo && t->r[i].hi > hi) {
        if (!vkh_room(t)) return 0;
        memmove(&t->r[i + 2], &t->r[i + 1], (size_t)(t->n - i - 1) * sizeof *t->r); t->n++;
        t->r[i + 1] = (vkh_range){hi, t->r[i].hi}; t->r[i].hi = lo;
        return 1;
    }
    if (i < t->n && t->r[i].lo < lo) { t->r[i].hi = lo; i++; }   // its tail is cut
    int j = i; while (j < t->n && t->r[j].hi <= hi) j++;         // those wholly inside go
    if (j > i) { memmove(&t->r[i], &t->r[j], (size_t)(t->n - j) * sizeof *t->r); t->n -= j - i; }
    if (i < t->n && t->r[i].lo < hi) t->r[i].lo = hi;            // its head is cut
    return 1;
}
static inline int vkh_over(const vkh_tab *t, uint64_t lo, uint64_t hi) { int i = vkh_find(t, lo); return hi > lo && i < t->n && t->r[i].lo < hi; }   // some address of [lo, hi) is a hole
static inline int vkh_covers(const vkh_tab *t, uint64_t lo, uint64_t hi) { int i = vkh_find(t, lo); return i < t->n && t->r[i].lo <= lo && t->r[i].hi >= hi; }   // every address of it is
static inline uint64_t vkh_bytes(const vkh_tab *t) { uint64_t b = 0; for (int i = 0; i < t->n; i++) b += t->r[i].hi - t->r[i].lo; return b; }
// The holes in [lo, hi), clipped to it, in order: *i starts at vkh_find(t, lo); 1 = [*hlo, *hhi) is the next one, 0 = no more
static inline int vkh_next_hole(const vkh_tab *t, uint64_t lo, uint64_t hi, int *i, uint64_t *hlo, uint64_t *hhi) {
    if (*i >= t->n || t->r[*i].lo >= hi || t->r[*i].hi <= lo) return 0;
    *hlo = t->r[*i].lo > lo ? t->r[*i].lo : lo; *hhi = t->r[*i].hi < hi ? t->r[*i].hi : hi; (*i)++;
    return 1;
}
// The part of the segment [slo, shi) that holds a and no hole, into [*plo, *phi). 0 = a is in a hole (not placed)
static inline int vkh_piece(const vkh_tab *t, uint64_t slo, uint64_t shi, uint64_t a, uint64_t *plo, uint64_t *phi) {
    int j = vkh_find(t, a);
    if (j < t->n && t->r[j].lo <= a) return 0;
    *phi = j < t->n && t->r[j].lo < shi ? t->r[j].lo : shi;
    *plo = j > 0 && t->r[j - 1].hi > slo ? t->r[j - 1].hi : slo;
    return 1;
}
// The parts of [slo, shi) outside every hole, in order: *at starts at slo; 1 = [*plo, *phi) is the next one, 0 = no more
static inline int vkh_next_piece(const vkh_tab *t, uint64_t slo, uint64_t shi, uint64_t *at, uint64_t *plo, uint64_t *phi) {
    uint64_t a = *at;
    while (a < shi) {
        int j = vkh_find(t, a);
        if (j < t->n && t->r[j].lo <= a) { a = t->r[j].hi; continue; }   // a is in it: on from its end
        uint64_t e = j < t->n && t->r[j].lo < shi ? t->r[j].lo : shi;
        *plo = a; *phi = e; *at = e;
        return 1;
    }
    *at = shi;
    return 0;
}
// What a give-back of [lo, hi) means for the segment [slo, shi): 0 = nothing of it, 1 = all of it (the segment is given back, as before), 2 = the part [*clo, *chi)
static inline int vkh_release(uint64_t slo, uint64_t shi, uint64_t lo, uint64_t hi, uint64_t *clo, uint64_t *chi) {
    uint64_t a = lo > slo ? lo : slo, e = hi < shi ? hi : shi;
    if (e <= a) return 0;
    *clo = a; *chi = e;
    return a == slo && e == shi ? 1 : 2;
}
#endif
