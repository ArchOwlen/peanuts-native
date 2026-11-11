# dev.peanuts.ui — what the session owner wires up

No upstream Java file is edited by this package. Everything upstream is reached through reflection (`Upstream.java`: Pack.titleIdOf, Nup.open().packages, PathUtil, Game.load/ready, Folders.packFor) or through public surface (`BuildConfig.NX_PROFILES`, MainActivity's exported adb extras).

## Manifest (`android/app/src/main/AndroidManifest.xml`, the PEANUTS line)

Replace the `CatalogActivity` launcher line with these three (CatalogActivity stays, without the LAUNCHER filter; the library's ⋮ menu opens it):

```xml
<activity android:name="dev.peanuts.ui.LibraryActivity" android:label="@string/app_name" android:exported="true" android:theme="@android:style/Theme.DeviceDefault.NoActionBar"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity>   <!-- PEANUTS: the library (home) -->
<activity android:name="dev.peanuts.ui.SetupActivity" android:label="Setup" android:exported="false" android:theme="@android:style/Theme.DeviceDefault.NoActionBar" />   <!-- PEANUTS: keys, firmware, folders -->
<activity android:name="dev.peanuts.CatalogActivity" android:label="Peanuts Companions" android:exported="false" android:theme="@android:style/Theme.DeviceDefault.NoActionBar" />   <!-- PEANUTS: the companion catalog (fallback) -->
```

MainActivity keeps its own LAUNCHER filter (upstream's Home stays reachable), or the owner drops that filter on the Peanuts build only — this package does not care.

## Launching a Ready game

`LibraryActivity.launch` sends `Intent().setClassName(<this package>, "dev.nxnative.android.MainActivity").putExtra("game", <dump or .nup>).putExtra("pack", <pack dir>).putExtra("run", true)` — the adb form. MainActivity gates these extras by `AdbControl.allowed(BuildConfig.NX_PLAY, Prefs.adbControl)`: always allowed in a test app (`-PnxApp=peanuts` is one). For a play/live Peanuts build the owner needs either `Prefs.adbControl` on by default or a one-line PEANUTS hook in `MainActivity.gated` letting intents from the app's own package through.

## Agent A (importer)

`ImporterPort` loads `dev.peanuts.importer.Native` reflectively: static `int keys(String)`, `String probe(String)` (JSON array of `{title_id, app_id, kind, version, name, publisher}`), `int icon(String, String, String)`, `int firmwareFonts(String, String)`, `String lastError()`. 0 = success. If its static initializer throws (no `libnximport.so` in the APK) the stub is used.

## Agent C (build pipeline)

`BuildPort` loads `dev.peanuts.build.BuildPipeline` reflectively: if it implements `dev.peanuts.ui.BuildPort` it is used as is; otherwise (agent C's actual API, as landed) `BuildPort.Pipeline` adapts to `static int start(Context, BuildRequest, BuildListener)`, filling `BuildRequest`'s public fields (titleId, name, base, update, dlcs, gameDir = the library root from setup, companionPackage = the chosen one when "install after build" is on) from `dev.peanuts.ui.GameSheet.State` (public fields: titleId, name, base, needsImport, update, dlcs, home, nup, launch, companionPackage/companionVersion/installCompanion/companionRoot, companionItem = a `dev.peanuts.Catalog.Item`) and wrapping the sheet's listener in a `BuildListener` proxy (`progress(step, percent, detail)` → `progress("step: detail", percent, 100)`). The listener's `progress`/`done` may be called from any thread. After `done(true, …)` the library re-scans; READY needs a `pack.tsv` under `<home>/<title ID>-work/<any>/` or upstream's list entry for the dump/.nup to be `Game.ready`.

Per-title choices (chosen update file, DLC files, companion package, "install after build", second-screen mode) are in `dev.peanuts.PeanutsPrefs` for `dev.peanuts.settings.CompanionSection` to reuse.

## dev.peanuts (not upstream): optional cleanups

- `Catalog`, `Catalog.Item`, `Installer` are package-private; `CompanionPort` reaches them through reflection. Making them `public` lets `CompanionPort` become plain calls.
- The catalog's names (`Catalog.NAMES`) are cached in `files/catalog-names.json` (one day) by `CompanionPort.names`.
