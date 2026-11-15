// nx_zip: just enough of the ZIP format to find named members and read their bytes — not a general unzip. Built for one job: a firmware .zip made of
// flat *.nca files (Nintendo's own distribution layout, stored or deflated; cnmt.nca entries too). Central directory + local headers only, ZIP64
// fields read when a size/offset is the 0xFFFFFFFF escape (firmware zips are all well under 4 GiB today, but a user's zip tool may still write
// ZIP64 records out of habit). No support for split archives, encryption, or any method but stored (0) and deflate (8); other methods are reported
// per-entry (method field) so the caller can decide to skip them.
#ifndef NX_ZIP_H
#define NX_ZIP_H
#include <stddef.h>
#include <stdint.h>

typedef struct NxZipEntry {
    char name[256];
    uint16_t method;            // 0 stored, 8 deflate, anything else unsupported
    uint64_t comp_size;         // bytes on disk (= size for stored)
    uint64_t size;              // uncompressed size
    uint64_t data_off;          // absolute offset in the zip file of the first byte of this entry's data
} NxZipEntry;

typedef struct NxZip {
    int fd;
    NxZipEntry *e;
    int n;
} NxZip;

// Opens path and reads its central directory, keeping only entries whose name ends in ".nca" (case-insensitive) — the only members the caller
// (the firmware font scan) ever needs; a firmware zip can hold thousands of unrelated files and there is no reason to carry them all in memory.
// 0 ok (sets nx_err and returns -1 on a truncated/unrecognised zip, a central directory that does not fit the file, or a ZIP64 field it cannot trust).
int nx_zip_open(NxZip *z, const char *path);
void nx_zip_close(NxZip *z);
#endif
