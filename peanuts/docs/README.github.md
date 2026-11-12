<h1 align="center">Peanuts Native</h1>

<p align="center">
  Dual-screen companions for <a href="https://git.eden-emu.dev/moceure/BananaNative">Banana Native</a>, the sibling of
  <a href="https://github.com/igawa6/eden-duo">Eden Duo</a> for your own Switch game dumps turned into native Android
  packs.
</p>

<p align="center">
  <!-- placeholder badges; fill in once a CI/release pipeline and a public repo exist -->
  <img alt="status" src="https://img.shields.io/badge/status-pre--release%2C%20local%20development-lightgrey">
  <img alt="license" src="https://img.shields.io/badge/license-GPL--3.0--or--later-blue">
  <img alt="platform" src="https://img.shields.io/badge/platform-Android%2013%2B-green">
</p>

---

> [!NOTE]
> **Status: pre-release, local development.** Peanuts Native is a private fork/overlay of Banana Native, built and
> tested on an AYN Thor. It has no public release yet — this README describes the project as it stands and as it is
> meant to ship, not a downloadable product. Nothing here is affiliated with Nintendo, Banana Native's maintainer, or
> Eden/Eden Duo.

Banana Native already turns a dump of your own Switch game into a native Android pack: the game's own ARM64 code is
rewritten to run on the phone's CPU, its NVN graphics calls are replayed on Vulkan, and its shaders are translated to
SPIR-V, on the device or on a PC. **Peanuts Native adds a second screen.** On a dual-screen Android handheld (the AYN
Thor and similar devices), the game keeps running on the main display exactly as Banana Native would run it, and the
second display shows a live, touchable companion panel — map, menu, status, items — read from the running game, the
same companion package format used by [Eden Duo](https://github.com/igawa6/eden-duo) for Switch 1 titles.

Peanuts Native keeps its own code in `android/app/src/main/java/dev/peanuts/`, `android/app/src/peanuts/`,
`dm/peanuts/` and `peanuts/`. Where it has to touch a Banana Native file to hook in, that edit is a marked `PEANUTS`
hunk; `peanuts/UPSTREAM-EDITS.md` lists every such file.

## Screenshots

*(to come: the top screen and the companion on Screen-2 for each supported game)*

## Supported games

These games build and play on an AYN Thor with their dual-screen companion. Use the game update listed: the app shows
the update you have against the one a game supports, and asks before building any other.

| Game | Title ID | Game update | Companion | Runs at |
|---|---|---|---|---|
| The Legend of Zelda: Link's Awakening | `01006BB00C6F0000` | 1.1.0 | Yes | 60 fps |
| The Legend of Zelda: Echoes of Wisdom | `01008CF01BAAC000` | 1.1.0 | Yes | 60 fps |
| Fire Emblem: Three Houses | `010055D009F78000` | 1.2.0 | Yes | 60 fps |
| Fire Emblem Engage | `0100A6301214E000` | 2.0.0 | Yes | 60 fps |
| Metroid Dread | `010093801237C000` | 2.1.0 | Yes | 60 fps |
| Super Mario Odyssey | `0100000000010000` | 1.4.1 | Yes | 60 fps |
| Mario Kart 8 Deluxe | `0100152000022000` | 4.0.0, 4.0.1 | Yes (for 4.0.0) | 60 fps |
| Animal Crossing: New Horizons | `01006F8002326000` | 3.0.3 | Yes | 30 fps, as on Switch |
| Persona 5 Royal | `01005CA01580E000` | 1.0.2 | Yes | 30 fps, as on Switch |
| Castlevania: Belmont's Curse | `0100BA4026E94000` | 1.1.0 | Yes | 60 fps |
| OCTOPATH TRAVELER 0 | `01005270232F2000` | 1.0.5 | Yes | 60 fps |
| Pokémon Brilliant Diamond | `0100000011D90000` | 1.3.0 | Yes | 60 fps |

A game not in this table has not been tried on this platform yet, **except** Dragon Quest III HD-2D Remake and Breath
of the Wild above, which were tried and are version-blocked on the current test dump, not untried. Banana Native's
own [games table](dm/games/README.md#games) is a different, wider list (every profile Banana Native itself knows
about); this one is only the games this project has actually run with a second screen attached, or deliberately
tried and hit a version gate on.

## Requirements

- **Device**: a dual-screen Android handheld (developed and tested on the AYN Thor). A single-screen Android phone can
  run the underlying Banana Native pack without the companion; Peanuts Native itself needs the second display.
- **GPU / Vulkan**: Banana Native's own baseline — Vulkan 1.3 with `VK_EXT_map_memory_placed`, a Snapdragon (Adreno)
  GPU. Tested on the Thor's Adreno 740: the phone's own (system) driver boots everything but is often GPU-bound; a
  Turnip Vulkan driver (installed through Banana Native's own driver downloader) is recommended.
- **Android version**: 13 or later (Banana Native's own minimum).
- **RAM**: 12–16 GB, as Banana Native requires per title; Peanuts Native's companion overlay adds a small, measured
  overhead on top (see `PEANUTS.md`'s performance-pass notes for the current figure).
- **Storage**: enough free space for your own game dump, its imported `.nup` (if you import from NSP/XCI on-device —
  this can be larger than the original dump while both exist), its translated shader pack, and the companion package.
  Budget tens of GB per title; some titles' shader translation alone runs into the tens of thousands of shaders.

## Getting your own keys and dumps

Peanuts Native, like Banana Native and Eden Duo, decrypts and runs **only your own game data, dumped from your own
console with your own keys**. This project:

- Contains no Nintendo code, firmware, or keys, and decrypts nothing on its own.
- Never asks you for a game, a key, or a dump, and never links to a site that provides any of those.
- Does not explain how to obtain `prod.keys` or game files you do not already have. If you don't already know how to
  dump your own console, that is outside this project's scope — look to your own console's own homebrew/backup
  community documentation, and only ever dump hardware and games you own.

Bring your own `prod.keys` and your own dump (a `base/` + `updates/<version>/` + `dlc/` folder set, or a packaged
`.nup`) to the app. Nobody from this project will ever ask you for money, keys, or game files — if someone does, they
are not us.

## Install

1. Build or obtain the Peanuts Native APK (there is no public release yet; see `PEANUTS.md` for the current
   `./gradlew` invocations used during development).
2. Grant storage access the same way Banana Native's own [storage guide](docs/guides/storage-access.md) describes.
3. On first run, Peanuts Native's setup walks through: your `prod.keys`, a firmware/fonts folder (shared fonts several
   titles need to avoid crashing on text), and your game folder.

## Build a game

The on-device library (Banana Native's own Home screen, extended with Peanuts' cards and status chips) does the whole
flow in one place:

1. Add your game's NSP/XCI/`.nup`/dump folder — the library finds it automatically if it's in your configured game
   folder, and matches a base game to its update and DLC by title ID.
2. Open the game's card and tap **Build**. The sheet's Version row shows the dump's build against the profile's
   listed builds before you commit: an unlisted build (Dragon Quest III above is the example) requires an explicit
   "Build anyway" confirmation rather than building silently, and a listed-but-not-the-package build is flagged as
   "builds, but with a full shader scan" instead of just running slow with no explanation.
3. Build runs, in order: **import** (decrypts NSP/XCI into a `.nup` with your own keys), **Prepare** (Banana Native's
   own pack step: code rewrite + shader translation, which can take from a few minutes to roughly an hour depending on
   the title's shader count), then the matching **companion** install, if the catalog has one for that title *and*
   build (see the two-axis table above — a title's companion can be narrower than its pack).
4. A few titles (Fire Emblem: Three Houses among them) compile a handful of shaders themselves at boot instead of
   shipping them in any game file; for those, Build also runs a short, automated run-time shader capture pass after
   Prepare, so those draws aren't left black.
5. Play. The companion, if installed and the current pack build is one it lists, starts automatically on the second
   screen once gameplay begins. What happens instead when the installed companion doesn't list the current pack
   build (a notice, a fallback view, or something else) hasn't been independently re-verified on the Thor yet — see
   "Companions" below.

Building entirely on the phone is slower than Banana Native's own Mac/PC packer for very shader-heavy titles (see
`PEANUTS.md`'s build-speed notes), but needs nothing except the phone and your dump.

## Companions

**Peanuts Companions** is a small catalog browser, backed by Eden Duo's companion GitHub releases: it lists **every**
published `<TITLEID>-<Name>-<version>.dsmod.zip` release for a supported title, not just the newest, with a version
history you can pick from — install, update, remove, or roll back to a previous companion release for a game you've
already built, without rebuilding the pack. Every install is verified against a `sha256` before it's used. Companions
run Eden Duo's own second-screen runtime, ported to run natively on the device rather than inside an emulator
process — so a companion package built for Eden Duo is the same package Peanuts Native loads. See
[eden-duo-companions](https://github.com/igawa6/eden-duo-companions) for what a companion can do, screenshots of each
one, and how it's built.

A companion's manifest lists the pack build IDs it was written against (`build_ids`); checking the running pack's
build against that list, before trusting anything the companion reads, is the design intent of the format, and is
what the two-axis table above assumes. **This project has not yet independently re-verified the exact on-device
behavior (the precise notice text, or whether it falls back to the ordinary second-screen view) when a companion is
installed for a title whose current pack build isn't in its `build_ids`** — e.g. installing Mario Kart 8 Deluxe's
companion (4.0.0-only, see the table above) against a 4.0.1 pack. Treat "refuses to load on an unrecognized build" as
the intended behavior, not a verified-on-device claim, until that case has actually been run on the Thor.

## FAQ

**Is this piracy?** No. Peanuts Native runs only a dump you made from your own console of a game you own, the same
way Banana Native, Eden Duo and nx2android do. It ships no game code, no firmware, and no keys, and provides no way to
get any of those.

**Why does my build fail partway through shader translation?** Some titles ship or need shaders that aren't in any
game file at all — they're compiled by the game itself at boot. Build handles the known cases (see Fire Emblem: Three
Houses above) with an automated capture pass; an unfamiliar title that does this will need investigation, not a
retry.

**Can I use this with a game at a different update version than the table says?** It depends which gate you hit. If
your dump's build isn't in the profile's `build` rows at all, the game's sheet shows a version row explaining that
up front and requires an explicit "Build anyway" confirmation before it will try (it may not start, or may crash,
and the first build scans every shader) — it does not build silently on an untested version. Dragon Quest III above
is the example this caught; the right move there is to update your dump, not to keep forcing a build. If your build
*is* listed but isn't the one the shader tables were made on, the build proceeds with a slower full shader scan
without needing that confirmation — that's expected, not a version mismatch. Companions go further on their own
axis, and are meant to refuse to load on a pack build outside their own `build_ids` rather than reading memory they
don't understand — see "Companions" above for why that specific behavior isn't yet independently verified on-device.

**Does the companion slow the game down?** A little; it's been profiled and reduced since the first version (direct
guest reads against cached ranges, ticking every other presented frame rather than every frame — see `PEANUTS.md` for
the current measured figure). Some titles' own frame-rate behavior (a frame governor dropping to 30 fps under load)
is the game's own logic reacting to present latency, not something the companion causes.

**Can I get a copy of your shader library / pack / `.nup`?** No — see the legal note below.

**Will this work on my single-screen phone?** The underlying Banana Native pack will, if your phone meets Banana
Native's own requirements. The second-screen companion needs a genuine second display.

## Contributing

See `peanuts/docs/CONTRIBUTING.md`: how to add a supported game, fix a bug, verify on a device, and the publishing
rules every change follows (no game data, keys or shader libraries; no private data; no tool attribution in the
history). Run `sh peanuts/scripts/install-hooks.sh` once per clone.

The dual-screen companion runtime this project ports is maintained separately, as its own project with its own
contribution policy; nothing from this repository is sent to it.

## Reporting problems

There is no issue tracker here yet (no public repo). If you hit a problem: note the title, its build ID (as logged
on boot), the GPU driver in use (system or Turnip, with version), and either a crash address with symbolication or a
clear description of what's visibly wrong — a picture if it's visual. Never attach a `.nup`, a pack, a shader
library, game ROM/RomFS data, or any key value; see the legal note below for why, and `peanuts/docs/CONTRIBUTING.md`
for the full checklist on what's safe to share.

## Credits and license

Peanuts Native is a fork of **[Banana Native](https://git.eden-emu.dev/moceure/BananaNative)** by **moceure**. The combined
project is **GPL-3.0-or-later** as a whole: Banana Native carries GPL-licensed components (frame generation ported
from Eden/lsfg-vk, and the shader translator's task/mesh lowering ported from switch2mac/Eglantine — see Banana
Native's own `android/NOTICE.md` for the exact breakdown), and the dual-screen companion runtime this project ports
from Eden Duo is itself GPL-derived. Any distributed binary build of this project is bound by GPL-3.0-or-later's
source-availability terms; that is a fact about the license, independent of whether a given build is ever made
public — the owner's choice to keep development private so far does not change what the license requires if and
when a binary ships. Banana Native is itself built on **[nxnative](https://github.com/seedds/nxnative)** by seedds,
MIT-licensed.

Third-party components vendored by Banana Native (FidelityFX FSR1, libadrenotools, libvpx, stb_vorbis, the Ryujinx
shader translator, Vulkan headers, zstd/zstd-zbic, and others) each keep their own license; see Banana Native's
[`dm/third_party/README.md`](dm/third_party/README.md) for the full list and attributions, including fixes adapted
from [nx2android](https://github.com/shirai91/nx2android) (MIT).

The dual-screen companion runtime and package format are **Eden Duo**'s (igawa6/eden-duo and
igawa6/eden-duo-companions): Peanuts Native ports Eden Duo's runtime to run as a native library inside Banana Native's
host process, rather than inside an emulator. Eden Duo is maintained separately, with its own project and its own
contribution policy (see "Contributing" above).

Only use games you own. Don't ask for games, keys, or dumps — here or anywhere else.

## Legal note on shader libraries and packs

A built game's pack (its rewritten code and translated shaders) and a built game's companion data are both **derived
directly from your own game's files**: the shader translator runs on the game's own shader bytecode, and a companion
reads the game's own fonts, art, and text out of its RomFS at runtime. Neither is distributed by this project, and
neither should be shared, uploaded, or redistributed by anyone — doing so would be redistributing someone else's
copyrighted game assets, however transformed. If you want to help another player get a title running, point them at
their own dump and keys, not at your pack.
