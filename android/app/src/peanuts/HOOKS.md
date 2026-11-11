# Peanuts rebrand — hooks to apply

None of these are applied. Each is a single line, to be added by hand (tag it `// PEANUTS` /
`<!-- PEANUTS -->` per PEANUTS.md's convention) at the exact site named. See `BRANDING.md` for why each is
needed and what it's visible as.

## 1. Wire the `src/peanuts/res` overlay (`android/app/build.gradle`)

Allowed hook site per PEANUTS.md ("`android/app/build.gradle` — applicationId / label"). Add one line inside
the existing `sourceSets { main { ... } }` block (right after the existing `res.srcDirs +=` line for the
test/play badge, so the merge order is: main → variant-test/variant-play → peanuts, i.e. peanuts wins last),
following the exact pattern upstream already uses to overlay `ic_launcher_badge*`:

```diff
     sourceSets {
         main {
             res.srcDirs += nxPlay || nxLive ? 'src/variant-play/res' : 'src/variant-test/res'
+            res.srcDirs += nxApp == 'peanuts' ? 'src/peanuts/res' : []   // PEANUTS: peanut icon/background overlay
             java.srcDirs += 'src/ondevice/java'
         }
```

Without this, `android/app/src/peanuts/res/` is just inert files on disk — Gradle never looks at them.

## 2. `MainActivity.java:108` — main screen header title

```diff
-        content.addView(Ui.header(this, Ui.badge(this, 44), "Banana Native", Prefs.buildId(), Ui.iconButton(this, R.drawable.ic_settings, "Settings", Ui.Kind.TONAL, v -> page(true))));
+        content.addView(Ui.header(this, Ui.badge(this, 44), "Peanuts Native", Prefs.buildId(), Ui.iconButton(this, R.drawable.ic_settings, "Settings", Ui.Kind.TONAL, v -> page(true))));   // PEANUTS: rebrand
```

## 3. `MainActivity.java:227` — "Turn ADB control on?" dialog body

```diff
-                        + "driver through Banana Native's launcher, without asking you. A GPU driver is code that runs inside the game. Turn it on only while you use adb, then off again.",
+                        + "driver through Peanuts Native's launcher, without asking you. A GPU driver is code that runs inside the game. Turn it on only while you use adb, then off again.",   // PEANUTS: rebrand
```

## 4. `MainActivity.java:252` — About → "Update" row value

```diff
-        updateRow.value("Banana Native " + Updater.version(this) + (Updater.available(this) ? "" : " · release app only"));
+        updateRow.value("Peanuts Native " + Updater.version(this) + (Updater.available(this) ? "" : " · release app only"));   // PEANUTS: rebrand
```

## 5. `Signer.java:66` — the build tag (main screen + first line of every game log)

```diff
-    static String buildTag(boolean play, String buildId) { return "Banana Native " + (play ? "" : "test · ") + buildId; }
+    static String buildTag(boolean play, String buildId) { return "Peanuts Native " + (play ? "" : "test · ") + buildId; }   // PEANUTS: rebrand
```

## 6. `Ui.java:150` — clipboard label on "Copy details"

```diff
-                if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("Banana Native details", text.getText()));
+                if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("Peanuts Native details", text.getText()));   // PEANUTS: rebrand
```

## 7. `Updater.java:445` — "Allow installs" dialog body

```diff
-                    .setMessage("To install the update, allow Banana Native to install apps (\"Install unknown apps\"), then come back.")
+                    .setMessage("To install the update, allow Peanuts Native to install apps (\"Install unknown apps\"), then come back.")   // PEANUTS: rebrand
```

## Not proposed (see BRANDING.md "Not changed")

`UpdateCheck.REPO`, `Updater`/`DriverFetch` User-Agent strings, `LogDigest.DOCS_BASE`, and the `Theme.Banana*`
style names are functional/internal, not user-visible branding — left alone on purpose.
