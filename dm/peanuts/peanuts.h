// Peanuts Native, host side (PEANUTS.md): live values of the running game for the companion screen, read from the game's own address space (guest VA == host VA)
// by pointer chains a text file names, refreshed on the present thread, and handed to the launcher's companion page as text. Built into libnxhost (android/host/CMakeLists.txt).
#ifndef PEANUTS_H
#define PEANUTS_H
#include <stddef.h>

// Called once per presented frame (vka_present.c): counts frames and refreshes the values every few frames. Cheap: a handful of process_vm_readv calls
void peanuts_tick(void);

// The newest snapshot as text lines "key=value\n" (frames, build, the chains file's state, then each named value; a chain that fails reads "?"): the length written
__attribute__((visibility("default"))) size_t nxhost_peanuts_snapshot(char *out, size_t n);   // exported like the launcher interface (libnxhost.map nxhost_*; the host builds hidden)

// The companion page as pixels (the DSMod runtime, dm/peanuts/rt): its size (0x0 while no package is loaded), the newest frame (RGBA8, w*h*4 bytes; 1 when a new
// frame was copied, 0 when nothing changed or no page), and a touch on it (pointer id, page coordinates, down/up)
__attribute__((visibility("default"))) void nxhost_peanuts_frame_size(int *w, int *h);
__attribute__((visibility("default"))) int nxhost_peanuts_frame(void *out_rgba, int w, int h);
__attribute__((visibility("default"))) void nxhost_peanuts_touch(int id, float x, float y, int down);

#endif
