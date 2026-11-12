// PEANUTS: the generic host's stand-in for vk_prewarm_seed.h (game.py seed; nxhost_add_generic(), android/host/CMakeLists.txt). vka_pso.c includes
// "vk_prewarm_seed.h" and reads dm_vk_prewarm_seed / dm_vk_prewarm_seed_len exactly as it does from the compiled-in per-title header - those symbols are
// declared in profile_rt.h (included by game_profile_generic.h, which dm.h already pulls in) and set by profile_rt_load_seed() at host start, so this
// file only needs to exist on the include path; it carries nothing of its own.
#pragma once
#include "profile_rt.h"
