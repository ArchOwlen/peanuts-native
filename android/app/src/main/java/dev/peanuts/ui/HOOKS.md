# dev.peanuts.ui — the library inside Banana's Home

Banana's Home (`dev.nxnative.android.MainActivity`) is the one home: its game box is filled by `Library.fill`, its Settings page starts with `LibrarySettings.add`,
its pickers' results pass through `LibrarySettings.result`, its Manage sheet has one Peanuts row (`GameSheet.open`). The look comes from `PUi`, a verbatim copy of
upstream's package-private `Ui` on the same `R.color`/`R.drawable` resources (`diff Ui.java PUi.java | grep -v '^[<>] *\(package\|import dev\|public\)'` is the
drift check; re-copy when upstream changes `Ui.java`).

## Upstream hooks (each one line, tagged `PEANUTS`)

| File | Hook |
|---|---|
| `MainActivity.build()` | `Ui.page(this, frame, 900)` — 640 → 900 dp: three compact cards per row on a handheld |
| `MainActivity.buildSettings()` | `dev.peanuts.ui.LibrarySettings.add(this, content);` before the Updates section |
| `MainActivity.refresh()` | `dev.peanuts.ui.Library.fill(this, gameBox, games, Ondevice.cardView(), Ondevice::shows, this::gameCard, this::manage, this::addGame, this::refresh);` in place of the "Your games" block |
| `MainActivity.manage()` | the `🔨 Build again` row → `dev.peanuts.ui.GameSheet.open(this, g.titleId)` |
| `MainActivity.onActivityResult()` | `if (dev.peanuts.ui.LibrarySettings.result(this, request, result, data)) return;` first (requests 60–66) |
| `AndroidManifest.xml` | `CatalogActivity` under `@style/Theme.Banana`; no Peanuts launcher entry (MainActivity keeps LAUNCHER) |
| `src/ondevice/AndroidManifest.xml` | the `BuildService` line (import → Prepare → companion) |

`gameCard`, `manage`, `addGame`, `refresh` stay private in MainActivity: the hook passes them as method references.

## Pieces

- `Library` — Home's game box: the filter row (All · Ready · Build needed · Not in this app version, Find, Add game), READY (upstream's full cards two per row, the
  progress card and the build card first and full width), BUILD NEEDED (compact cards three per row), NOT IN THIS APP VERSION (folded behind Show N); the first-run
  card when no game folder is set; the scan (`LibraryScanner`) and the catalog (`CompanionPort`) off the main thread; a scanned game that is ready goes into
  upstream's list (`Upstream.addToList`) so its card is Banana's own. The build's progress (`BuildPipeline` → `BuildListener`) lands on the build card; during Prepare
  upstream's Ondevice card is the card.
- `GameSheet` — the game's dialog: Base / Update / DLC / Companion rows, Build or Rebuild, Add keys… when prod.keys are missing. Its Choose/Add pickers come back
  through `LibrarySettings.result` (requests 65, 66).
- `LibrarySettings` — Settings › Library: folders, keys, firmware fonts, Rescan, Companions; the pickers (also the first-run card's and the sheet's).
- `LibraryScanner`, `Upstream`, `CompanionPort`, `ImporterPort`, `BuildPort` — as before (reflection into upstream and the sibling Peanuts packages).

## Launching a Ready game

Upstream's own card (Play → `MainActivity.launch`). `GameEnv.ensureFonts`/`ensureBuildId` run for every ready game after each scan, so the env is set before Play.
