# PEANUTS hooks for the build pipeline and the Companion section

Two one-line additions to upstream files (PEANUTS.md rule 2). Neither is applied by agent C; the session owner inserts them.

## 1. GameSettings.java — the Companion section (one line)

File: `android/app/src/main/java/dev/nxnative/android/GameSettings.java`, in `static void dialog(MainActivity a, Game g)`, directly after the GPU timing row.

Old (the two lines the hook goes between; unchanged):

```java
        Ui.add(box, new Ui.Setting(a, "⏲️", "GPU timing", v -> gpuTiming(a, g, again)).value(gpuTsLabel(a, id)).chevron(a).view, 0);
        Ui.add(box, new Ui.Setting(a, "🌐", "Game language", v -> language(a, g, again)).value(GameLanguage.label(Prefs.gameLang(a, id), phoneTag())).chevron(a).view, 0);
```

New (one line inserted between them):

```java
        Ui.add(box, new Ui.Setting(a, "⏲️", "GPU timing", v -> gpuTiming(a, g, again)).value(gpuTsLabel(a, id)).chevron(a).view, 0);
        Ui.add(box, dev.peanuts.settings.CompanionSection.view(a, id, dev.peanuts.settings.CompanionSection.root(g.packDir)), 8);   // PEANUTS: the Companion section (install/update/remove, second screen)
        Ui.add(box, new Ui.Setting(a, "🌐", "Game language", v -> language(a, g, again)).value(GameLanguage.label(Prefs.gameLang(a, id), phoneTag())).chevron(a).view, 0);
```

`CompanionSection.root(g.packDir)` computes the companion root the way `dev.peanuts.Games.Entry.companionRoot` does: `<packDir>/../peanuts`
(`<game>/<title ID>-work/peanuts`), or null without a pack (then the section says "build the game first" and Install is off).

## 2. AndroidManifest.xml — the build service (one element)

File: `android/app/src/ondevice/AndroidManifest.xml` (merged over main's in every APK; it already declares FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC and
POST_NOTIFICATIONS). Inside `<application>`, after the PackService element:

```xml
        <service android:name="dev.peanuts.build.BuildService" android:exported="false" android:foregroundServiceType="dataSync" />   <!-- PEANUTS: the library's build service -->
```

The service runs in the launcher's process (no `android:process`), so the game sheet's listener is called directly; the packer it starts (`PackService`) stays in `:pack`.

## 3. android/app/.gitignore — the `dev.peanuts.build` package is ignored (one line)

`android/app/.gitignore` line 2 is `build/`, which matches `src/main/java/dev/peanuts/build/` too (git check-ignore confirms), so the pipeline's sources
would never be committed. Either change that line to anchor it at the module root:

```
/build/
```

or add after it:

```
!src/main/java/dev/peanuts/build/
```

(or `git add -f` the folder once; the files then stay tracked). The session owner picks; nothing in the pipeline depends on which.
