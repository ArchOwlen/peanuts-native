package dev.peanuts.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import dev.peanuts.build.Companions;

/**
 * The Companion section of a game's settings sheet (GameSettings.dialog; the one PEANUTS hook, HOOKS.md): the installed companion and the catalog's version, Install /
 * Update / Remove, and where the companion shows ("Second screen": Physical / Off, kept in the "peanuts" prefs as second_screen_&lt;title ID&gt; for dev.peanuts.Companion
 * to read). Rows are upstream's Ui.Setting and Ui.button, reached through reflection (Ui is package-private); when that fails the rows are plain views of the same shape.
 */
public final class CompanionSection {
    public static final String PREFS = "peanuts", KEY_SECOND_SCREEN = "second_screen_", PHYSICAL = "physical", OFF = "off";
    private static final String TAG = "peanuts";

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
        Ui ui = new Ui(ctx);
        box.addView(ui.section("Companion"), lp(0, 12));
        new Rows(ctx, ui, titleId, companionRoot, box).render();
        return box;
    }

    private static LinearLayout.LayoutParams lp(int bottomDp, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp); p.bottomMargin = dp(bottomDp);
        return p;
    }

    private static int dp(int v) { return Math.round(v * android.content.res.Resources.getSystem().getDisplayMetrics().density); }

    /** The rows' state and async work; render() rebuilds them in place. AUDIT.md #6: installedPkg/installedVersion/installedVerified are a cache, filled by
     * refreshInstalled() - a directory scan plus a reflective file read - and only ever refreshed there, never from render() itself. Without this, every single
     * progress tick of an install (several a second on a slow download) re-scanned the companion folder and re-read installed.json on the UI thread for no reason,
     * since render() during a busy install only ever shows busyText anyway. */
    private static final class Rows {
        final Context c;
        final Ui ui;
        final String titleId;
        final File root;
        final LinearLayout box;
        final Handler main = new Handler(Looper.getMainLooper());
        List<Companions.Item> catalog;
        String catalogError;
        boolean busy;
        String busyText = "";
        final LinearLayout body;
        // The installed-state cache (AUDIT.md #6): stale until refreshInstalled() runs, which render() asks for only on its first call and after fetch/install/remove finish
        boolean installedKnown;
        String installedPkg, installedVersion;
        boolean installedVerified;

        Rows(Context c, Ui ui, String titleId, File root, LinearLayout box) {
            this.c = c; this.ui = ui; this.titleId = titleId; this.root = root; this.box = box;
            body = new LinearLayout(c);
            body.setOrientation(LinearLayout.VERTICAL);
            box.addView(body, lp(0, 0));
            fetch();
        }

        void fetch() {
            new Thread(() -> {
                List<Companions.Item> it = null; String err = null;
                try { it = Companions.fetch(); } catch (IOException | RuntimeException e) { err = e.getMessage() == null ? e.toString() : e.getMessage(); }
                final List<Companions.Item> fit = it; final String ferr = err;
                main.post(() -> { catalog = fit; catalogError = ferr; installedKnown = false; render(); });
            }, "peanuts-catalog").start();
        }

        /** The one place that touches disk for the installed package's state (AUDIT.md #6): a directory scan (Companions.installed) plus installed.json's version
         * and verified flag. Called from render() only when installedKnown is false - set true right after, so a busy install's progress ticks (which call render()
         * every update) reuse the cached values instead of re-scanning. */
        void refreshInstalled(Companions.Item item) {
            // Self-heal before reading anything: a killed install/switch is completed or rolled back here, never left with no active companion. The package
            // name to repair can only come from the catalog item at this point (a fully-vanished active folder has no package.json for installed() to find).
            if (item != null) Companions.repair(root, item.packageName);
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
            body.addView(ui.setting("🖥️", "Companion", value, openVersions), lp(0, 0));
            LinearLayout buttons = new LinearLayout(c);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setPadding(dp(10), 0, dp(12), dp(8));
            if (have == null && item != null) add(buttons, ui.button("Install", Ui.PRIMARY, v -> install(item)), !busy && root != null);
            if (update) add(buttons, ui.button("Update", Ui.PRIMARY, v -> install(item)), !busy);
            if (have != null) add(buttons, ui.button("Remove", Ui.DANGER, v -> remove(pkg)), !busy);
            if (hasVersions) add(buttons, ui.button("Versions", Ui.QUIET, v -> dev.peanuts.ui.CompanionVersions.open((Activity) c, titleId, () -> { installedKnown = false; render(); })), !busy);
            if (buttons.getChildCount() > 0) body.addView(buttons, lp(0, 0));
            String ss = secondScreen(c, titleId);
            body.addView(ui.setting("📱", "Second screen", ss.equals(OFF) ? "Off: the companion does not show" : "Physical: the companion shows on the second display",
                    v -> new AlertDialog.Builder(c).setTitle("Second screen").setSingleChoiceItems(new String[] {"Physical display", "Off"}, ss.equals(OFF) ? 1 : 0, (d, w) -> {
                        secondScreen(c, titleId, w == 1 ? OFF : PHYSICAL);
                        d.dismiss();
                        render();
                    }).setNegativeButton("Cancel", null).show()), lp(0, 0));
        }

        void add(LinearLayout row, View b, boolean enabled) {
            b.setEnabled(enabled);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            p.rightMargin = dp(8);
            row.addView(b, p);
        }

        void install(Companions.Item item) {
            if (root == null) { Toast.makeText(c, "Build the game first: the companion goes beside its pack.", Toast.LENGTH_LONG).show(); return; }
            busy = true; busyText = "Downloading " + Companions.label(item.packageName, item.version) + "…";
            render();
            new Thread(() -> {
                String err = null;
                try {
                    Companions.install(item, root, c.getCacheDir(), (what, done, total) -> main.post(() -> { busyText = what + (total > 0 ? " " + (done * 100 / total) + " %" : ""); render(); }), c);   // ctx = c: refuses with "close the game first" while the game is running (CRITIQUE-phase2c.md §B)
                } catch (IOException | RuntimeException e) { err = e.getMessage() == null ? e.toString() : e.getMessage(); }
                final String ferr = err;
                main.post(() -> {
                    busy = false;
                    installedKnown = false;   // AUDIT.md #6: the one point this cache is invalidated - an install actually finished, not merely ticked
                    Toast.makeText(c, ferr == null ? "Installed " + Companions.label(item.packageName, item.version) + ". It loads at the game's next start." : "Install failed: " + ferr, Toast.LENGTH_LONG).show();
                    render();
                });
            }, "peanuts-install").start();
        }

        void remove(String pkg) {
            new AlertDialog.Builder(c).setTitle("Remove the companion?").setMessage(pkg + " is deleted from the game's folder. The catalog can install it again.")
                    .setPositiveButton("Remove", (d, w) -> {
                        try { Companions.remove(root, pkg, c); Toast.makeText(c, "Removed", Toast.LENGTH_SHORT).show(); } catch (IOException e) { Toast.makeText(c, "Remove failed: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
                        installedKnown = false;
                        render();
                    }).setNegativeButton("Cancel", null).show();
        }
    }

    /**
     * Upstream's look through reflection: Ui.section(Context, CharSequence), new Ui.Setting(Context, String emoji, String title, OnClickListener).value(CharSequence).chevron(Context).view,
     * Ui.button(Context, CharSequence, Ui.Kind, OnClickListener). Any failure falls back to plain views shaped like them.
     */
    private static final class Ui {
        static final String PRIMARY = "PRIMARY", DANGER = "DANGER", QUIET = "QUIET";
        final Context c;
        Class<?> ui, setting, kind;

        Ui(Context c) {
            this.c = c;
            try {
                ui = Class.forName("dev.nxnative.android.Ui");
                setting = Class.forName("dev.nxnative.android.Ui$Setting");
                kind = Class.forName("dev.nxnative.android.Ui$Kind");
            } catch (ClassNotFoundException e) {
                ui = null;
            }
        }

        View section(String s) {
            try {
                Method m = ui.getDeclaredMethod("section", Context.class, CharSequence.class);
                m.setAccessible(true);
                return (View) m.invoke(null, c, s);
            } catch (ReflectiveOperationException | RuntimeException e) {
                TextView t = new TextView(c);
                t.setText(s);
                t.setAllCaps(true);
                t.setLetterSpacing(0.08f);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                t.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, 700, false));
                t.setPadding(dp(4), 0, 0, 0);
                return t;
            }
        }

        View setting(String emoji, String title, String value, View.OnClickListener l) {
            try {
                Constructor<?> ctor = setting.getDeclaredConstructor(Context.class, String.class, String.class, View.OnClickListener.class);
                ctor.setAccessible(true);
                Object s = ctor.newInstance(c, emoji, title, l);
                Method v = setting.getDeclaredMethod("value", CharSequence.class);
                v.setAccessible(true);
                v.invoke(s, value);
                if (l != null) { Method ch = setting.getDeclaredMethod("chevron", Context.class); ch.setAccessible(true); ch.invoke(s, c); }
                Field f = setting.getDeclaredField("view");
                f.setAccessible(true);
                return (View) f.get(s);
            } catch (ReflectiveOperationException | RuntimeException e) {
                Log.w(TAG, "Ui.Setting: " + e);
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(8), dp(12), dp(8));
                row.setMinimumHeight(dp(60));
                row.setOnClickListener(l);
                TextView tile = new TextView(c);
                tile.setText(emoji);
                tile.setGravity(Gravity.CENTER);
                tile.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                row.addView(tile, new LinearLayout.LayoutParams(dp(40), dp(40)));
                LinearLayout col = new LinearLayout(c);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(dp(14), 0, dp(8), 0);
                TextView t = new TextView(c);
                t.setText(title);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                t.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, 600, false));
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                col.addView(t);
                TextView v = new TextView(c);
                v.setText(value);
                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                v.setMaxLines(2);
                v.setEllipsize(TextUtils.TruncateAt.END);
                v.setVisibility(value == null || value.isEmpty() ? View.GONE : View.VISIBLE);
                col.addView(v);
                row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                if (l != null) { TextView ch = new TextView(c); ch.setText("›"); ch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20); row.addView(ch); }
                return row;
            }
        }

        View button(String label, String kindName, View.OnClickListener l) {
            try {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object k = Enum.valueOf((Class) kind, kindName);
                Method m = ui.getDeclaredMethod("button", Context.class, CharSequence.class, kind, View.OnClickListener.class);
                m.setAccessible(true);
                return (View) m.invoke(null, c, label, k, l);
            } catch (ReflectiveOperationException | RuntimeException e) {
                Button b = new Button(c);
                b.setText(label);
                b.setAllCaps(false);
                b.setOnClickListener(l);
                if (kindName.equals(DANGER)) b.setTextColor(Color.rgb(0xB0, 0x30, 0x20));
                return b;
            }
        }
    }

    /** For a plain Activity caller that wants the section in a dialog of its own (CatalogActivity-style use) */
    public static void dialog(Activity a, String titleId, String gameName, File companionRoot) {
        new AlertDialog.Builder(a).setTitle("Companion · " + gameName).setView(view(a, titleId, companionRoot)).setPositiveButton("Done", null).show();
    }
}
