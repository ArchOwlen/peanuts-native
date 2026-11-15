// nx_zip: central directory + local header parsing (see nx_zip.h for scope). Plain C11, no external zip library — the format is small enough that a
// dependency would cost more than it saves, and this project already hand-rolls PFS0/HFS0/RomFS readers the same way.
#define _FILE_OFFSET_BITS 64
#include "nx_zip.h"
#include "nx_internal.h"
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <unistd.h>

static uint16_t rd16(const uint8_t *p) { uint16_t v; memcpy(&v, p, 2); return v; }
static uint32_t rd32(const uint8_t *p) { uint32_t v; memcpy(&v, p, 4); return v; }
static uint64_t rd64(const uint8_t *p) { uint64_t v; memcpy(&v, p, 8); return v; }

#define EOCD_SIG 0x06054b50u
#define EOCD64_LOC_SIG 0x07064b50u
#define EOCD64_SIG 0x06064b50u
#define CD_SIG 0x02014b50u
#define LFH_SIG 0x04034b50u
#define NX_ZIP_NAME_MAX ((uint16_t)sizeof(((NxZipEntry *)0)->name))

static int add_entry(NxZip *z, int *cap, const NxZipEntry *src) {
    if (z->n == *cap) {
        int nc = *cap ? *cap * 2 : 64;
        NxZipEntry *g = realloc(z->e, (size_t)nc * sizeof *g);
        if (!g) { nx_err("zip: out of memory"); return -1; }
        z->e = g; *cap = nc;
    }
    z->e[z->n++] = *src;
    return 0;
}

// Finds the EOCD record's offset by scanning backward from the end of the file (the comment field makes its position variable, up to 64 KiB + the
// record itself). Returns the EOCD's offset in the file, or -1 if none is found (not a zip, or truncated).
static int64_t find_eocd(int fd, uint64_t fsize, uint8_t *rec /* 22 bytes out */) {
    size_t want = fsize < 0xFFFF + 22 ? (size_t)fsize : 0xFFFF + 22;
    if (want < 22) return -1;
    uint8_t *buf = malloc(want);
    if (!buf) { nx_err("zip: out of memory"); return -1; }
    int64_t rc = -1;
    if (nx_pread_all(fd, buf, want, fsize - want)) goto out;
    for (size_t i = want - 22; (int64_t)i >= 0; i--) {
        if (rd32(buf + i) == EOCD_SIG) { memcpy(rec, buf + i, 22); rc = (int64_t)(fsize - want + i); break; }
    }
    if (rc < 0) nx_err("zip: no end-of-central-directory record found (not a zip, or truncated)");
out:
    free(buf);
    return rc;
}

int nx_zip_open(NxZip *z, const char *path) {
    memset(z, 0, sizeof *z);
    z->fd = open(path, O_RDONLY | O_CLOEXEC);
    if (z->fd < 0) { nx_err("cannot open %s: %s", path, strerror(errno)); return -1; }
    struct stat st;
    if (fstat(z->fd, &st) || !S_ISREG(st.st_mode)) { nx_err("%s is not a file", path); goto fail; }
    uint64_t fsize = (uint64_t)st.st_size;

    uint8_t eocd[22];
    int64_t eocd_off = find_eocd(z->fd, fsize, eocd);
    if (eocd_off < 0) goto fail;

    uint64_t cd_count = rd16(eocd + 10), cd_size = rd32(eocd + 12), cd_off = rd32(eocd + 16);
    int want64 = cd_count == 0xFFFF || cd_size == 0xFFFFFFFFu || cd_off == 0xFFFFFFFFu;

    if (want64) {
        if (eocd_off < 20) { nx_err("%s: zip64 locator missing before the end-of-central-directory record", path); goto fail; }
        uint8_t loc[20];
        if (nx_pread_all(z->fd, loc, 20, (uint64_t)eocd_off - 20)) goto fail;
        if (rd32(loc) != EOCD64_LOC_SIG) { nx_err("%s: zip64 sizes but no zip64 end-of-central-directory locator", path); goto fail; }
        uint64_t rec_off = rd64(loc + 8);
        uint8_t rec[56];
        if (rec_off + sizeof rec > fsize || nx_pread_all(z->fd, rec, sizeof rec, rec_off)) goto fail;
        if (rd32(rec) != EOCD64_SIG) { nx_err("%s: zip64 end-of-central-directory record not where its locator points", path); goto fail; }
        cd_count = rd64(rec + 32); cd_size = rd64(rec + 40); cd_off = rd64(rec + 48);
    }
    if (cd_off > fsize || cd_size > fsize - cd_off) { nx_err("%s: central directory is outside the file (truncated zip?)", path); goto fail; }

    uint8_t *cd = malloc((size_t)cd_size ? (size_t)cd_size : 1);
    if (!cd) { nx_err("zip: out of memory"); goto fail; }
    if (nx_pread_all(z->fd, cd, (size_t)cd_size, cd_off)) { free(cd); goto fail; }

    int cap = 0; uint64_t p = 0; uint64_t seen = 0;
    while (p + 46 <= cd_size) {
        if (rd32(cd + p) != CD_SIG) { nx_err("%s: bad central directory entry at +%llu", path, (unsigned long long)p); free(cd); goto fail; }
        uint16_t method = rd16(cd + p + 10);
        uint64_t comp_size = rd32(cd + p + 20), size = rd32(cd + p + 24);
        uint16_t nlen = rd16(cd + p + 28), elen = rd16(cd + p + 30), clen = rd16(cd + p + 32);
        uint64_t lfh_off = rd32(cd + p + 42);
        if (p + 46 + nlen + elen + clen > cd_size) { nx_err("%s: central directory entry runs past its end", path); free(cd); goto fail; }
        const uint8_t *name = cd + p + 46, *extra = name + nlen;

        // ZIP64 extra field (id 1): uncompressed size, compressed size, local header offset, disk — only the ones that were the 0xFFFFFFFF escape,
        // in that fixed order, so each override consumes exactly the bytes present.
        if (size == 0xFFFFFFFFu || comp_size == 0xFFFFFFFFu || lfh_off == 0xFFFFFFFFu) {
            uint16_t eo = 0; int got = 0;
            while (eo + 4 <= elen) {
                uint16_t id = rd16(extra + eo), flen = rd16(extra + eo + 2);
                if (eo + 4 + flen > elen) break;
                if (id == 1) {
                    const uint8_t *f = extra + eo + 4; uint16_t fo = 0;
                    if (size == 0xFFFFFFFFu && fo + 8 <= flen) { size = rd64(f + fo); fo += 8; }
                    if (comp_size == 0xFFFFFFFFu && fo + 8 <= flen) { comp_size = rd64(f + fo); fo += 8; }
                    if (lfh_off == 0xFFFFFFFFu && fo + 8 <= flen) { lfh_off = rd64(f + fo); fo += 8; }
                    got = 1; break;
                }
                eo += 4 + flen;
            }
            if (!got) { nx_err("%s: entry needs a zip64 extra field that is not there", path); free(cd); goto fail; }
        }

        // Keep only *.nca names (see nx_zip.h): the firmware scan never needs anything else, and a full firmware zip can hold thousands of files.
        if (nlen >= 4 && !strncasecmp((const char *)name + nlen - 4, ".nca", 4) && nlen < NX_ZIP_NAME_MAX) {
            if (lfh_off + 30 > fsize) { nx_err("%s: local header offset out of range", path); free(cd); goto fail; }
            uint8_t lfh[30];
            if (nx_pread_all(z->fd, lfh, 30, lfh_off)) { free(cd); goto fail; }
            if (rd32(lfh) != LFH_SIG) { nx_err("%s: local header missing at the offset its central directory entry names", path); free(cd); goto fail; }
            uint16_t lnlen = rd16(lfh + 26), lelen = rd16(lfh + 28);
            uint64_t data_off = lfh_off + 30 + lnlen + lelen;
            if (data_off > fsize || comp_size > fsize - data_off) { nx_err("%s: entry data runs past the end of the file", path); free(cd); goto fail; }
            NxZipEntry ent; memset(&ent, 0, sizeof ent);
            memcpy(ent.name, name, nlen); ent.name[nlen] = 0;
            ent.method = method; ent.comp_size = comp_size; ent.size = size; ent.data_off = data_off;
            if (add_entry(z, &cap, &ent)) { free(cd); goto fail; }
        }
        p += 46 + nlen + elen + clen;
        seen++;
    }
    free(cd);
    if (cd_count && seen != cd_count) { nx_err("%s: central directory has %llu entries, expected %llu", path, (unsigned long long)seen, (unsigned long long)cd_count); goto fail; }
    return 0;
fail:
    nx_zip_close(z);
    return -1;
}

void nx_zip_close(NxZip *z) {
    if (z->fd >= 0) close(z->fd);
    free(z->e);
    z->fd = -1; z->e = NULL; z->n = 0;
}
