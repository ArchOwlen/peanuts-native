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

- 2026-11-11: fork created on v0.3.0 (65745297). Hardware gate (Readiness check on the AYN Thor, Adreno 740) not yet run. No hooks added yet.
