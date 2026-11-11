// Peanuts Native: the companion runtime's RomFS provider (PnRtRomfs, rt/peanuts_rt.h) over a .nup container (dm/host/nup_image.h).
//
// DM_ROMFS names the running version's RomFS inside the container ("<game>.nup:update/<v>/romfs", ":base/romfs"); that package's RomFS is the
// first layer. When the selector is not the base's, the container's base package (":base") is opened as a second layer under it: a path is looked
// up in the update first, then in the base (an update package holds a full RomFS -- android/NUP.md -- so the base is only a safety net; a base
// without a RomFS, as Pokopia's, is simply absent). Raw files are read with pread at their extent; zstd files are decompressed by nup_image.h when
// opened and held until closed (its own cache). Every call is thread-safe (the runtime's asset workers read while its tick thread opens).
//
// Linked into the Android host (android/host/CMakeLists.txt HOST_C) and into rt/test's nup_test on Linux (there with PN_NUP_HOST_TEST).
#pragma once
#include <stddef.h>
#include <stdint.h>
#include "rt/peanuts_rt.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct pn_nup pn_nup;

// 1 when spec names a .nup ("<file>.nup", "<file>.nup:<selector>[/romfs]")
int pn_nup_is_spec(const char *spec);
// Opens the RomFS layers spec names; NULL with the reason in why (the first layer must open; a missing base layer is not an error)
pn_nup *pn_nup_open(const char *spec, char *why, size_t wn);
void pn_nup_close(pn_nup *n);
// One line for logs: "<file> update/655360 (53 entries) + base (none)"
const char *pn_nup_describe(const pn_nup *n);
// Fills the runtime's callback set (user = n)
void pn_nup_provider(pn_nup *n, PnRtRomfs *out);

// The callbacks themselves (PnRtRomfs semantics; user is the pn_nup *)
int pn_nup_romfs_open(void *user, const char *path, uint64_t *size);
int64_t pn_nup_romfs_read(void *user, int handle, uint64_t off, void *buf, uint64_t n);
void pn_nup_romfs_close(void *user, int handle);
int pn_nup_romfs_list(void *user, const char *dir, void (*cb)(void *cb_user, const char *name, int is_dir), void *cb_user);

#ifdef __cplusplus
}
#endif
