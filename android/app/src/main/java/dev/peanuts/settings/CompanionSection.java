package dev.peanuts.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.List;

import dev.peanuts.build.Companions;

/**
 * The Companion section of a game's page (its Game tab, GameSettings.build; a PEANUTS hook, HOOKS.md): the installed companion and the catalog's version, Install /
 * Update / Remove, and where the companion shows ("Second screen": Physical / Off, kept in the "peanuts" prefs as second_screen_&lt;title ID&gt; for dev.peanuts.Companion
 * to read). Rows are upstream's Ui.Setting and Ui.button, reached through SectionUi.
 */
public final class CompanionSection {
    public static final String PREFS = "peanuts", KEY_SECOND_SCREEN = "second_screen_", PHYSICAL = "physical", OFF = "off";

    private CompanionSection() {}

    /** dev.peanuts.Games.Entry.companionRoot for a game's pack folder: &lt;pack&gt;/../peanuts, or null without a pack */
    public static File root(String packDir) {
        return packDir == null || packDir.isEmpty() ? null : new File(new File(packDir).getParentFile(), "peanuts");
    }

    public static String secondScreen(Context c, String titleId) {
        return prefs(c).getString(KEY_SECOND_SCREEN + titleId, PHYSICAL);
    }

    public static void secondScreen(Context c, String titleId, String v) {
        prefs(c).edit().putString(KEY_SECOND_SCREEN + titleId, v).apply();
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS); }

    /** The section: a label, the companion row with its buttons, the second-screen row. companionRoot null (no pack yet): Install is off and the row says so */
    public static View view(Context ctx, String titleId, File companionRoot) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        SectionUi ui = new SectionUi(ctx);
        box.addView(ui.section("Companion"), SectionUi.lp(0, 12));
        new Rows(ctx, ui, titleId, companionRoot, box).render();
        return box;
    }

    /** The rows' state and async work; render() rebuilds them in place. Found in review: installedPkg/installedVersion/installedVerified are a cache, filled by
     * refreshInstalled() - a directory scan plus a reflective file read - and only ever refreshed there, never from render() itself. Without this, every single
     * progress tick of an install (several a second on a slow download) re-scanned the companion folder and re-read installed.json on the UI thread for no reason,
     * since render() during a busy install only ever shows busyText anyway. */
    private static final class Rows {
        final Context c;
        final SectionUi ui;
        final String titleId;
        final File root;
        final LinearLayout box;
        final Handler main = new Handler(Looper.getMainLooper());
        List<Companions.Item> catalog;
        String catalogError;
        boolean busy;
        String busyText = "";
        final LinearLayout body;
        View buttonsRow;   // the row of Install / Update / Remove / Versions of the last render (null: none)
        // The installed-state cache (found in review): stale until refreshInstalled() runs, which render() asks for only on its first call and after fetch/install/remove finish
        boolean installedKnown;
        String installedPkg, installedVersion;
        boolean installedVerified;

        Rows(Context c, SectionUi ui, String titleId, File root, LinearLayout box) {
            this.c = c; this.ui = ui; this.titleId = titleId; this.root = root; this.box = box;
            body = new LinearLayout(c);
            body.setOrientation(LinearLayout.VERTICAL);
            box.addView(body, SectionUi.lp(0, 0));
            fetch();
        }

        void fetch() {
            new Thread(() -> {
                List<Companions.Item> it = null; String err = null;
                try { it = Companions.fetch(); } catch (IOException | RuntimeException e) { err = e.getMessage() == null ? e.toString() : e.getMessage(); }
                final List<Companions.Item> fit = it; final String ferr = err;
                main.post(() -> {
                    catalog = fit; catalogError = ferr; installedKnown = false;
                    View had = buttonsRow;
                    render();
                    if (had == null && buttonsRow != null) dev.peanuts.ui.Feel.appeared(buttonsRow);   // Install / Versions came with the catalog: they fade in, once (a progress tick's render never does)
                });
            }, "peanuts-catalog").start();
        }

        /** The one place that touches disk for the installed package's state (found in review): a directory scan (Companions.installed) plus installed.json's version
         * and verified flag. Called from render() only when installedKnown is false - set true right after, so a busy install's progress ticks (which call render()
         * every update) reuse the cached values instead of re-scanning. */
        void refreshInstalled(Companions.Item item) {
            // Self-heal before reading anything: a killed install/switch is completed or rolled back here, never left with no active companion. The package
            // name to repair can only come from the catalog item at this point (a fully-vanished active folder has no package.json for installed() to find).
            if (item != null) Companions.repair(root, item.packageName, c);   // ctx = c: skips while the game is running (the lock alone does not cover that; found in review)
            List<String> installed = Companions.installed(root);
            installedPkg = item != null ? item.packageName : installed.isEmpty() ? null : installed.get(0);
            installedVersion = installedPkg == null ? null : Companions.installedVersion(root, installedPkg);
            installedVerified = installedPkg != null && installedVersion != null && Companions.installedVerified(root, installedPkg);
            installedKnown = true;
        }

        void render() {
            body.removeAllViews();
            Companions.Item item = catalog == null ? null : Companions.find(catalog, titleId);
            if (!installedKnown) refreshInstalled(item);
            String pkg = installedPkg, have = installedVersion;
            boolean update = item != null && have != null && Companions.compareVersions(item.version, have) > 0;
            String value;
            if (busy) value = busyText;
            else if (have != null) value = "Installed: " + Companions.label(pkg, have) + (!installedVerified ? " · unverified" : "") + (item == null ? (catalog == null && catalogError == null ? " · checking the catalog…" : "") : update ? " · update " + item.version + " available" : " · up to date");
            else if (item != null) value = "Available: " + Companions.label(item.packageName, item.version) + (root == null ? " · build the game first" : "");
            else if (catalog == null && catalogError == null) value = "Checking the catalog…";
            else if (catalogError != null) value = "Catalog unavailable: " + catalogError;
            else value = "No companion in the catalog for this game";
            boolean hasVersions = root != null && (item != null || have != null) && c instanceof Activity;
            View.OnClickListener openVersions = !hasVersions ? null : v -> dev.peanuts.ui.CompanionVersions.open((Activity) c, titleId, () -> { installedKnown = false; render(); });
            body.addView(ui.setting("🖥️", "Companion", value, openVersions), SectionUi.lp(0, 0));
            LinearLayout buttons = SectionUi.buttons(c);
            if (have == null && item != null) SectionUi.add(buttons, ui.button("Install", SectionUi.PRIMARY, v -> install(item)), !busy && root != null);
            if (update) SectionUi.add(buttons, ui.button("Update", SectionUi.PRIMARY, v -> install(item)), !busy);
            if (have != null) SectionUi.add(buttons, ui.button("Remove", SectionUi.DANGER, v -> remove(pkg)), !busy);
            if (hasVersions) SectionUi.add(buttons, ui.button("Versions", SectionUi.QUIET, v -> dev.peanuts.ui.CompanionVersions.open((Activity) c, titleId, () -> { installedKnown = false; render(); })), !busy);
            buttonsRow = buttons.getChildCount() > 0 ? buttons : null;
            if (buttonsRow != null) body.addView(buttons, SectionUi.lp(0, 0));
            String ss = secondScreen(c, titleId);
            body.addView(ui.setting("📱", "Second screen", ss.equals(OFF) ? "Off: the companion does not show" : "Physical: the companion shows on the second display",
                    v -> new AlertDialog.Builder(c).setTitle("Second screen").setSingleChoiceItems(new String[] {"Physical display", "Off"}, ss.equals(OFF) ? 1 : 0, (d, w) -> {
                        dev.peanuts.ui.Feel.picked(d);
                        secondScreen(c, titleId, w == 1 ? OFF : PHYSICAL);
                        d.dismiss();
                        render();
                    }).setNegativeButton("Cancel", null).show()), SectionUi.lp(0, 0));
        }

        void install(Companions.Item item) {
            if (root == null) { dev.peanuts.ui.Feel.refused(c); Toast.makeText(c, "Build the game first: the companion goes beside its pack.", Toast.LENGTH_LONG).show(); return; }
            busy = true; busyText = "Downloading " + Companions.label(item.packageName, item.version) + "…";
            render();
            new Thread(() -> {
                String err = null;
                try {
                    Companions.install(item, root, c.getCacheDir(), (what, done, total) -> main.post(() -> { busyText = what + (total > 0 ? " " + (done * 100 / total) + " %" : ""); render(); }), c);   // ctx = c: refuses with "close the game first" while the game is running (found in review)
                } catch (IOException | RuntimeException e) { err = e.getMessage() == null ? e.toString() : e.getMessage(); }
                final String ferr = err;
                main.post(() -> {
                    busy = false;
                    installedKnown = false;   // Found in review: the one point this cache is invalidated - an install actually finished, not merely ticked
                    if (ferr == null) dev.peanuts.ui.Feel.accepted(c); else dev.peanuts.ui.Feel.refused(c);   // the install ended: done, or refused
                    Toast.makeText(c, ferr == null ? "Installed " + Companions.label(item.packageName, item.version) + ". It loads at the game's next start." : "Install failed: " + ferr, Toast.LENGTH_LONG).show();
                    render();
                });
            }, "peanuts-install").start();
        }

        void remove(String pkg) {
            new AlertDialog.Builder(c).setTitle("Remove the companion?").setMessage(pkg + " is deleted from the game's folder. The catalog can install it again.")
                    .setPositiveButton("Remove", dev.peanuts.ui.Feel.confirm((d, w) -> {
                        // off the UI thread: the delete walks shared storage and waits for the package's lock (an install of it may be downloading)
                        Handler mainUi = new Handler(Looper.getMainLooper());
                        new Thread(() -> {
                            String err = null;
                            try { Companions.remove(root, pkg, c); } catch (IOException | RuntimeException e) { err = e.getMessage() == null ? e.toString() : e.getMessage(); }
                            final String ferr = err;
                            mainUi.post(() -> {
                                if (ferr != null) dev.peanuts.ui.Feel.refused(c);
                                Toast.makeText(c, ferr == null ? "Removed" : "Remove failed: " + ferr, ferr == null ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                                installedKnown = false;
                                render();
                            });
                        }, "peanuts-companion-remove").start();
                    })).setNegativeButton("Cancel", null).show();
        }
    }
}
