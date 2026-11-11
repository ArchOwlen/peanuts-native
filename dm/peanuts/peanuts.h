// Peanuts Native, host side (PEANUTS.md): live values of the running game for the companion screen, read from the game's own address space (guest VA == host VA)
// by pointer chains a text file names, refreshed on the present thread, and handed to the launcher's companion page as text. Built into libnxhost (android/host/CMakeLists.txt).
#ifndef PEANUTS_H
#define PEANUTS_H
#include <stddef.h>

// Called once per presented frame (vka_present.c): counts frames and refreshes the values every few frames. Cheap: a handful of process_vm_readv calls
void peanuts_tick(void);

// The newest snapshot as text lines "key=value\n" (frames, build, the chains file's state, then each named value; a chain that fails reads "?"): the length written
size_t nxhost_peanuts_snapshot(char *out, size_t n);

#endif
