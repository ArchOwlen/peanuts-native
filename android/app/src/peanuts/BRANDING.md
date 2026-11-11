# Peanuts rebrand — branding inventory

Every user-visible "Banana Native" / "Banana" occurrence found in the launcher, and how it is (or will be)
overridden. "Overlay" = covered by `android/app/src/peanuts/res/...` (same resource name, no upstream edit).
"Hook" = cannot be overlaid; the one-line change is proposed in `HOOKS.md`, not applied.

## App name / label

| Source | Text | Override |
|---|---|---|
| `android/app/build.gradle:115` (`resValue 'string', 'app_name', ...`) | `"Banana Native"` / `"Banana Native test"` | **Already parameterized.** For the Peanuts build (`-PnxApp=peanuts`), `app_name` resolves to `nxLabel`, which the existing build command sets explicitly: `-PnxLabel="Peanuts Native"` (see PEANUTS.md's P1 status line). No hook needed. Only the *default* test app (`nxApp` unset) still says "Banana Native test" — out of scope, that path isn't the Peanuts app. |
| `android/app/src/main/res/values/strings.xml` | *(empty — no `<string>` entries at all)* | Nothing to override. All user-facing text in this app is Java string literals, not string resources. |
| `android/app/src/main/res/values-*/strings.xml` (locales) | *(none found containing "Banana")* | n/a |

## Logo / launcher icon / header badge (all resource-based — fully overlaid, no Java hook)

| File | Role | Override |
|---|---|---|
| `res/drawable/ic_banana.xml` | Plain banana: adaptive icon's foreground base art *and* the main-screen drifting background tile (`BananaBackgroundView` draws `R.drawable.ic_banana` directly) | Overlay `android/app/src/peanuts/res/drawable/ic_banana.xml` — a peanut (tan `#D9A066` shell, `#8C5A2B` outline/dimples), same resource name |
| `res/drawable/ic_banana_mono.xml` | Monochrome (themed-icon) banana silhouette, alpha-only | Overlay with a peanut silhouette, same name |
| `res/drawable/ic_banana_happy.xml` | Banana + face: launcher header badge (`Ui.badge`), empty game list (`MainActivity.java:428`), *and* layered into `ic_launcher_foreground.xml` for the adaptive icon | Overlay with peanut + face (eyes, cheeks, smile), same name |
| `res/drawable/ic_banana_happy_mono.xml` | Happy banana monochrome silhouette (face cut out), layered into `ic_launcher_monochrome.xml` | Overlay with peanut-happy silhouette, same name |
| `res/drawable/ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`, `mipmap-anydpi-v26/ic_launcher*.xml`, `res/values/colors.xml` (`ic_launcher_background`) | Reference the four drawables above and the background color by name only — no brand text/art of their own | **No override needed.** Overriding the four `ic_banana*` drawables is sufficient; these files keep working unchanged and pick up the peanut automatically. |
| `android/images/logo.svg` | Marketing/README logo (not packaged into the APK) | Reference only, used to match style/colour (`#1E5B3A` background, tan/brown palette) for the new peanut art. Not part of the APK build; no override needed unless you also want the standalone SVG rebranded (not done — out of scope of "launcher" per the task). |

## Background pattern

`android/app/src/main/java/dev/nxnative/android/BananaBackgroundView.java` (outside `dev.peanuts`, **not edited**) draws
`R.drawable.ic_banana` as a tiled bitmap (`getContext().getDrawable(R.drawable.ic_banana)`, line 89) — it does not draw
paths in code. Overriding the `ic_banana` drawable resource (above) is sufficient to re-skin the background to peanuts;
**no Java hook or `PeanutsBackground.java` is needed.**

## Java string literals (cannot be overlaid — hooks proposed in HOOKS.md, not applied)

| File:line | Current text | Visible where |
|---|---|---|
| `MainActivity.java:108` | `"Banana Native"` | Main screen header title |
| `MainActivity.java:227` | `"...driver through Banana Native's launcher, without asking you..."` | "Turn ADB control on?" confirmation dialog body |
| `MainActivity.java:252` | `"Banana Native " + Updater.version(this) + ...` | Settings → About → "Update" row value |
| `Signer.java:66` | `"Banana Native " + (play ? "" : "test · ") + buildId` | The build tag shown on the main screen and as the first line of every game log |
| `Ui.java:150` | `"Banana Native details"` | Clipboard label when "Copy details" is tapped (system clipboard toast/UI, briefly visible on some OEM skins) |
| `Updater.java:445` | `"...allow Banana Native to install apps (\"Install unknown apps\")..."` | "Allow installs" dialog body |

## Not changed (not user-visible branding, or would break functionality)

| File:line | Text | Why left alone |
|---|---|---|
| `UpdateCheck.java:54` `REPO = "moceure/BananaNative"` | GitHub repo coordinates used to query the releases API | Functional identifier pointing at the real upstream repo; not shown to the user, and changing it breaks update checking |
| `Updater.java:141`, `DriverFetch.java:64` `"BananaNative-updater"` / `"BananaNative-driver-fetch"` | HTTP `User-Agent` strings | Not user-visible; tied to the real update/driver-fetch services |
| `LogDigest.java:32` `DOCS_BASE` URL (`.../moceure/BananaNative/...`) | Docs link | Points at the real upstream repo's docs; not a brand label |
| `values/themes.xml`, `values-night/themes.xml` style names `Theme.Banana`, `Theme.Banana.Dialog`, `Banana.DialogButton` | Style/theme identifiers | Internal names only, never rendered to the user |
| `BananaBackgroundView.java`, `bananas` field in `MainActivity.java` | Class/variable names | Internal identifiers; the file lives outside `dev.peanuts` so it isn't renamed, and renaming wouldn't change anything the user sees |

## Counts

- Overridable via resource overlay (drawables): **4** (`ic_banana`, `ic_banana_mono`, `ic_banana_happy`, `ic_banana_happy_mono`)
- Already handled by existing build flags (no action): **1** (`app_name` resValue)
- String resources to override: **0** (none exist)
- Java literals needing a hook: **6** (listed above, see `HOOKS.md`)
- Left alone (functional or internal, non-branding): **7**
