# Peanuts Native

A second-screen companion layer for Banana Native, the sibling of Eden Duo for Switch 2 titles. Upstream
Banana Native ships several releases a day, so this fork is kept as a **thin overlay** that rebases cleanly.

## Rules that keep upstream updates cheap

1. **All new code lives in Peanuts-only places.** Upstream never touches them, so they never conflict:
   - `android/app/src/main/java/dev/peanuts/` — launcher side: the `Presentation` on the second display, its view, the JNI bridge.
   - `dm/peanuts/` — host side: companion package loader, guest readers, tick, input injection (plain C).
   - `peanuts/` — companion packages, docs, tests, `scripts/`.
2. **Hooks into upstream files are one-liners, each tagged `PEANUTS`.** A hook is a call-out to Peanuts code, never logic.
   Allowed hook sites (keep this list current):
   - `android/app/src/main/java/dev/nxnative/android/GameActivity.java` — create/destroy the companion `Presentation`.
   - `android/app/src/main/cpp/nxlaunch.c` — JNI entry points forwarded to `dm/peanuts`.
   - `android/host/CMakeLists.txt`, `android/app/src/main/cpp/CMakeLists.txt` — add the `dm/peanuts` sources.
   - `dm/gfx/vk_android/vka_present.c` — one call per presented frame (`peanuts_tick()`), beside `ovl_draw`.
   - `android/app/build.gradle` — applicationId / label.
3. **Settings go through upstream's own mechanisms**: `env.txt` keys named `PN_*`, `settings.tsv` rows, `DM_CTL` lines. No new settings UI inside upstream classes.
4. **Never reformat or "fix" upstream files.** A general fix belongs upstream as a PR to Banana Native (its AGENTS.md prefers general changes), then it arrives by rebase.
5. **Branches:** `upstream/android` is Banana Native; `peanuts` is this overlay, always a linear set of commits on top of a Banana Native tag. History is rewritten on every update (rebase, never merge).

## Updating to a new Banana Native release

```sh
peanuts/scripts/upstream-update.sh            # latest upstream/android
peanuts/scripts/upstream-update.sh v0.3.1     # a tag
```

The script fetches, rebases `peanuts` onto the target, then checks that every `PEANUTS` hook marker is still present. If a hook moved, fix the one-liner and re-run; nothing else should conflict.

## Status

- 2026-11-11: fork created on v0.3.0 (65745297). Hardware gate passed on the AYN Thor (Adreno 740, system driver): Readiness "Ready, with limits", Pokémon Pokopia boots to its first interactive screen at 10 fps / 1080p.
- 2026-11-11: P1 — the companion `Presentation` on the second display (`dev.peanuts.Companion`, two hooks in `GameActivity`) verified on the Thor: placeholder page on Screen-2 (display 4, 1080x1240) while the game runs on the main screen at an unchanged frame rate. Test app: `./gradlew apk -PnxApp=peanuts -PnxLabel="Peanuts Native" -PnxGames=0400A3A01EE4C000 -PnxPackEngine=fake` → `out/nxnative-android-peanuts.apk` (packs are made on the PC).
- 2026-11-11: P2 — the data path verified on the Thor with Pokopia: `dm/peanuts/peanuts.c` in libnxhost reads pointer chains from `$HOME/peanuts/chains.tsv` (re-read when it changes), refreshed by the `peanuts_tick()` hook in `vka_present.c` every 2 presented frames; `dm/peanuts/peanuts_jni.c` in libnxlaunch hands the snapshot to `dev.peanuts.Bridge`; the page polls it 4×/s. Pokopia `main` build `76a489f3bd3642fa5e18bd83d32c98ba`, base `0x7405d17000` on that run. Exported host symbols need `__attribute__((visibility("default")))` (the host builds hidden; the map alone does not export).
- 2026-11-11: with the Turnip driver (v26.2.0 R5, Settings › GPU driver) Pokopia runs at 60 fps on the Thor (GPU 5 ms) and renders correctly; the system driver gave 10 fps. Module bases change per run (ASLR), chains are module-relative.
- 2026-11-12 (R1): the Eden Duo DSMod runtime runs inside libnxhost (`dm/peanuts/rt/`, see its PORT.md): the unmodified Link's Awakening companion package (`<title ID>-work/peanuts/LinksAwakening/`) loads for the running build, decodes the game's own fonts, textures and text from its RomFS, and shows its standby page on the Thor's Screen-2 while the game runs at 60 fps. Runtime tick: 2.5 ms CPU per frame on the present thread (to move off it). Not yet: touch/gestures/actions (R2), guest calls, title `.so` modules, `.nup`-internal RomFS reads.
- 2026-11-12 (R2): touch on Screen-2 → the runtime's gestures and actions (mod_input, mod_actions lifted as-is); the virtual pad is the host's keyboard/touch input source (`dm_input_set`, ORed with the real controller); the runtime ticks on its own thread. Verified on the Thor in Link's Awakening: the map page with Link's live marker, the gear page with live counts and equips, taps (Close, pins) firing actions with haptic clicks, page binds following the game's menu state. Haptics are not yet routed to the device's vibrator.
- Next: R3 — companion catalog download; then the on-device pack engine and the `.nup` importer; later guest calls and title `.so` modules for the companions that use them.
- Parked: P3 — Pokopia's first real values (memory-diff hunt on the Thor), then the page as DSMod-style widgets; P4 — taps → `nxhost_input_*`.
