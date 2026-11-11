// libnximport — Peanuts Native's importer (peanuts/DESIGN-library.md "Importer C API"). Plain C11, no C++, no OpenSSL.
// Reads the public Switch formats (PFS0/NSP, XCI/HFS0, NCA with the header key, key areas and title keys, PFS0/RomFS sections,
// BKTR patch RomFS, CNMT, NACP) and writes a .nup container the host reads (dm/host/nup_image.h; SPEC 1.0-draft).
// Every function returns 0 on success and -1 on failure with a reason in nximport_last_error() (thread-local).
#ifndef NXIMPORT_H
#define NXIMPORT_H

#ifdef __cplusplus
extern "C" {
#endif
#define NXIMPORT_API __attribute__((visibility("default")))   // the library is built with -fvisibility=hidden: only these and the JNI are exported

typedef struct NxImportInfo {
    char title_id[17];      // 16 lower-case hex digits
    char app_id[17];        // the base application's title id (= title_id for a base)
    int kind;               // 0 base, 1 update, 2 dlc
    unsigned version;       // the CNMT version (an update's NCM version, e.g. 131072)
    char name[256];         // from the control NACP (English, else the first language); "" when there is no control
    char publisher[256];
} NxImportInfo;

// Loads header_key, key_area_key_{application,ocean,system}_NN and titlekek_NN from a prod.keys; a title.keys beside it (rights id = key) is read too when present.
NXIMPORT_API int nximport_keys(const char *prod_keys_path);
// Every title (CNMT) in an NSP or XCI: an XCI/NSP can hold base + update + dlc. *count = titles found (at most max written).
NXIMPORT_API int nximport_probe(const char *nsp_or_xci, NxImportInfo *out, int max, int *count);
// The control NACP's icon (English, else the first) of that title → a PNG file. title_id may be NULL for the first title that has one.
NXIMPORT_API int nximport_icon(const char *file, const char *title_id, const char *out_png);
// The base of base_title_id and its updates and add-ons found in the inputs → one .nup (packages base, update/<version>, addon/<title id>).
// progress(step, done, total): step is "probe", "base/exefs", "base/romfs", "update/<v>/exefs", "update/<v>/romfs", "addon/<tid>/romfs", "toc"; done/total are bytes of the
// whole job (files are written in node-id order, so the steps of two packages interleave). A failed write removes the partial file.
NXIMPORT_API int nximport_write_nup(const char *out_nup, const char **inputs, int n_inputs, const char *base_title_id,
                        void (*progress)(void *user, const char *step, long long done, long long total), void *user);
// The shared system fonts out of a firmware folder (FontStandard 0100000000000811 → standard.ttf, FontNintendoExtended 0100000000000810 → nintendo_ext.ttf):
// *.nca files found in the folder and up to two levels below (Eden's nand/system/Contents/registered layout, or a flat folder; a <id>.nca/00 folder too).
NXIMPORT_API int nximport_firmware_fonts(const char *firmware_dir, const char *out_dir);
// Checks a .nup (superblock, TOC SHA-256, bounds) and lists every file: path is "<package>/<exefs|romfs>/<path>" with the package named as a selector
// (base, update/<version>, addon/<title id>). entry may be NULL (check only); returning non-zero from it stops the listing (not an error).
NXIMPORT_API int nximport_verify_nup(const char *nup, int (*entry)(void *user, const char *path, unsigned long long size, unsigned long long offset, int codec), void *user);
// Reads a raw (codec 0) file of a .nup by its path as nximport_verify_nup lists it → out_file.
NXIMPORT_API int nximport_nup_extract(const char *nup, const char *path, const char *out_file);
NXIMPORT_API const char *nximport_last_error(void);

#ifdef __cplusplus
}
#endif
#endif
