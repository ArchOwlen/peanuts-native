// PNRT shim: the system zlib, then an `#undef far`.
//
// engine_mercury.cpp (lifted byte-identical) includes <zlib.h> before core/mods/mod_runtime.h.
// The NDK's zconf.h (zlib 1.3.0.1, line 415) defines `far` as an empty macro for 16-bit memory
// models, and mod_input_swipe.h (reached through mod_runtime.h) has a local named `far`, which the
// macro then erases ("expected unqualified-id"). Eden never meets this because it links its own
// bundled zlib, not the NDK's. This shim sits ahead of the system include dirs (-I shim), takes
// the real header through #include_next (gcc and clang) and drops the macro, so the lifted file
// stays untouched. A host build (glibc zlib, no `far` macro) is unaffected.
#pragma once
#include_next <zlib.h>
#ifdef far
#undef far
#endif
#ifdef near
#undef near
#endif
