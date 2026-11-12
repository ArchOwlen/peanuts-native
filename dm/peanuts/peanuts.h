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

// Whether a companion is on second-screen duty for this run: 0 still deciding (no module loaded yet, or the runtime has not tried pn_rt_create), 1 a package
// loaded (nxhost_peanuts_frame_size/frame now matter), 2 no companion package beside this title's pack, 3 a package was found but pn_rt_create refused it (see
// logcat "peanuts" for why). The caller (dev.peanuts.Companion) uses this to decide whether to take the second display at all, so a title with no companion
// leaves it to Android's own launcher instead of showing a developer readout there.
__attribute__((visibility("default"))) int nxhost_peanuts_rt_state(void);

// One pending haptic pulse from the runtime (at most one per pn_rt_tick; dropped if not polled before the next one). Returns 1 and consumes it, or 0 when there
// is none. amplitude is 0..1, ms its duration, respect_system non-zero when the package wants the device's own touch-feedback switch honoured (manifest
// "haptics":{"respect_system":...}, true by default - mirrors Eden Duo's AuxScreenHost.performHaptic), kind is Core::Mods::HapticKind (0 Tap, 1 Write,
// 2 Select, 3 Drag, 4 Drop, 5 Marker, 6 Refused, 7 Hold, 8 Swipe) so the caller can play a different effect for e.g. a hold versus a refused tap.
__attribute__((visibility("default"))) int nxhost_peanuts_haptic(float *amplitude, int *ms, int *respect_system, int *kind);

#endif
