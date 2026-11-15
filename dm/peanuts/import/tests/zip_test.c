// zip_test — checks nx_zip.c (the ZIP central-directory reader behind a firmware .zip) against a synthetic fixture built by make_zip_fixture.py:
// one stored *.nca member, one deflated *.nca member, one non-.nca member that must be filtered out. Content is the deterministic pattern()
// formula the fixture script also uses, so this test needs no second reference file — it recomputes the expected bytes itself.
//   zip_test <fixture.zip>
#define _POSIX_C_SOURCE 200809L
#include "nx_zip.h"
#include "nx_internal.h"
#include "nximport.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <zlib.h>

static void pattern(uint8_t *b, size_t n, int salt) { for (size_t i = 0; i < n; i++) b[i] = (uint8_t)((i * 37 + 11 + salt) & 0xFF); }

static int inflate_all(int fd, uint64_t off, uint64_t comp_size, uint8_t *out, size_t out_size) {
    z_stream zs; memset(&zs, 0, sizeof zs);
    if (inflateInit2(&zs, -15) != Z_OK) return -1;
    uint8_t inbuf[1 << 16];
    uint64_t remaining = comp_size, pos = off; size_t got = 0; int zrc = Z_OK;
    for (;;) {
        if (zs.avail_in == 0) {
            if (!remaining) { zrc = Z_BUF_ERROR; break; }
            size_t want = remaining < sizeof inbuf ? (size_t)remaining : sizeof inbuf;
            if (nx_pread_all(fd, inbuf, want, pos)) { zrc = Z_ERRNO; break; }
            zs.next_in = inbuf; zs.avail_in = (unsigned)want; pos += want; remaining -= want;
        }
        zs.next_out = out + got; zs.avail_out = (unsigned)(out_size - got);
        zrc = inflate(&zs, Z_NO_FLUSH);
        got = out_size - zs.avail_out;
        if (zrc == Z_STREAM_END || zrc != Z_OK) break;
    }
    inflateEnd(&zs);
    return zrc == Z_STREAM_END && got == out_size ? 0 : -1;
}

int main(int argc, char **argv) {
    if (argc != 2) { fprintf(stderr, "usage: zip_test <fixture.zip>\n"); return 2; }
    NxZip z;
    if (nx_zip_open(&z, argv[1])) { fprintf(stderr, "nx_zip_open: %s\n", nximport_last_error()); return 1; }
    int fail = 0;
    if (z.n != 2) { fprintf(stderr, "FAIL: expected 2 .nca entries (notes.txt must be filtered out), got %d\n", z.n); fail = 1; }
    const NxZipEntry *stored = NULL, *deflated = NULL;
    for (int i = 0; i < z.n; i++) {
        printf("  entry[%d] name=%s method=%u comp_size=%llu size=%llu data_off=%llu\n", i, z.e[i].name, z.e[i].method,
               (unsigned long long)z.e[i].comp_size, (unsigned long long)z.e[i].size, (unsigned long long)z.e[i].data_off);
        if (z.e[i].method == 0) stored = &z.e[i];
        else if (z.e[i].method == 8) deflated = &z.e[i];
    }
    if (!stored) { fprintf(stderr, "FAIL: no stored member found\n"); fail = 1; }
    if (!deflated) { fprintf(stderr, "FAIL: no deflated member found\n"); fail = 1; }

    if (stored) {
        size_t n = (size_t)stored->size;
        uint8_t *got = malloc(n), *want = malloc(n);
        pattern(want, n, 1);
        if (stored->comp_size != stored->size) { fprintf(stderr, "FAIL: stored member's comp_size != size\n"); fail = 1; }
        else if (nx_pread_all(z.fd, got, n, stored->data_off)) { fprintf(stderr, "FAIL: could not read the stored member's data\n"); fail = 1; }
        else if (memcmp(got, want, n)) { fprintf(stderr, "FAIL: stored member's bytes do not match the fixture's pattern\n"); fail = 1; }
        else printf("  ok: stored member (%zu bytes) read directly, byte-identical\n", n);
        free(got); free(want);
    }
    if (deflated) {
        size_t n = (size_t)deflated->size;
        uint8_t *got = malloc(n), *want = malloc(n);
        memset(want, 0, n); pattern(want, n / 2, 2);   // the fixture: half pattern(salt 2), half zeros
        if (inflate_all(z.fd, deflated->data_off, deflated->comp_size, got, n)) { fprintf(stderr, "FAIL: inflate of the deflated member failed\n"); fail = 1; }
        else if (memcmp(got, want, n)) { fprintf(stderr, "FAIL: deflated member's decompressed bytes do not match the fixture's pattern\n"); fail = 1; }
        else printf("  ok: deflated member (%zu bytes decompressed from %llu) matches the fixture's pattern\n", n, (unsigned long long)deflated->comp_size);
        free(got); free(want);
    }
    nx_zip_close(&z);
    if (!fail) printf("PASS\n");
    return fail;
}
