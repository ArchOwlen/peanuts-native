package dev.peanuts.ui;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import dev.peanuts.PeanutsPrefs;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A game that is not built yet: its base, the update that goes in (the newest found; Choose… picks another file), its DLC (the ones matched by title ID, Add… picks
 * more), its companion from the catalog (with "install after the build") and one Build button, which hands the sheet's State to the build pipeline (BuildPort) and
 * shows its progress. When the build ends the library re-scans.
 */
final class GameSheet {
    static final int REQ_UPDATE = 31, REQ_DLC = 32;

    /** What the pipeline gets: the files to import, where the .nup and the pack go, the companion to install. Public: agent C's dev.peanuts.build reads it */
    public static final class State {
        public String titleId, name;
        /** The base: a dump folder, a .nup or an NSP/XCI (then needsImport) */
        public File base; public boolean needsImport; public long baseVersion;
        /** The update file to import with the base (null: none), its version; and the DLC files */
        public File update; public long updateVersion;
        public final List<File> dlcs = new ArrayList<>();
        /** The folder of the game: the dump folder, the .nup's folder, or <game folder>/<name>, where the import writes nup (home/<home name>.nup) */
        public File home, nup;
        /** What upstream starts (the dump or the .nup), null before the import makes it */
        public File launch;
        /** The companion to install into companionRoot after the build (null: none), the catalog entry's package name and version */
        public String companionPackage, companionVersion; public boolean installCompanion; public File companionRoot;
        /** The catalog item (CompanionPort.Item), for the installer */
        public Object companionItem;
    }

    /** The open sheet (the activity forwards the file pickers' results to it) */
    static GameSheet current;

    private final LibraryActivity a;
    private final LibraryScanner.Title title;
    private final CompanionPort.Item companion;
    private final Runnable onFinished;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private AlertDialog dialog;
    private LinearLayout box;
    private TextView progressText;
    private ProgressBar bar;
    private Button build;
    private LibraryScanner.Source chosenUpdate;
    private final List<File> extraDlc = new ArrayList<>();
    private boolean installCompanion;
    private boolean building;

    private GameSheet(LibraryActivity a, LibraryScanner.Title t, CompanionPort.Item companion, Runnable onFinished) {
        this.a = a; this.title = t; this.companion = companion; this.onFinished = onFinished;
        String picked = PeanutsPrefs.chosenUpdate(a, t.titleId);
        for (LibraryScanner.Source u : t.updates) if (picked != null && u.file.getPath().equals(picked)) chosenUpdate = u;
        if (chosenUpdate == null && !t.updates.isEmpty()) chosenUpdate = t.updates.get(0);   // sorted newest first
        for (String d : PeanutsPrefs.chosenDlc(a, t.titleId)) extraDlc.add(new File(d));
        installCompanion = companion != null && PeanutsPrefs.installCompanion(a, t.titleId);
    }

    static void show(LibraryActivity a, LibraryScanner.Title t, CompanionPort.Item companion, Runnable onFinished) {
        GameSheet s = new GameSheet(a, t, companion, onFinished);
        current = s;
        s.open();
    }

    private void open() {
        ScrollView scroll = new ScrollView(a);
        box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        scroll.addView(box);
        render();
        dialog = new AlertDialog.Builder(a).setTitle(title.name).setView(scroll).setNegativeButton("Close", null).create();
        dialog.setOnDismissListener(d -> { if (current == this) current = null; });
        dialog.show();
    }

    private void render() {
        box.removeAllViews();
        // Base
        LibraryScanner.Source b = title.base;
        row("Base", b == null ? "not found" : b.file.getName() + "  ·  " + (b.isDump() ? "dump folder" : b.isNup() ? ".nup" : b.file.getName().toLowerCase(Locale.ROOT).endsWith(".xci") ? "XCI, import needed" : "NSP, import needed")
                + (b.version > 0 ? "  ·  " + versionText(b.version) : "") + (b != null && !b.publisher.isEmpty() ? "\n" + b.publisher : ""), null, null);
        // Update
        String up = chosenUpdate == null ? (title.updates.isEmpty() ? "none found" : "none") : chosenUpdate.file.getName() + "  ·  " + versionText(chosenUpdate.version)
                + (chosenUpdate == (title.updates.isEmpty() ? null : title.updates.get(0)) ? "  (newest)" : "");
        row("Update", up, "Choose…", v -> a.pickFile(REQ_UPDATE, "Pick the update (.nsp / .xci)"));
        // DLC
        LinearLayout dl = new LinearLayout(a); dl.setOrientation(LinearLayout.VERTICAL);
        List<LibraryScanner.Source> dlcs = title.dlcs;
        if (dlcs.isEmpty() && extraDlc.isEmpty()) dl.addView(text("none found", 14, Color.LTGRAY, Typeface.DEFAULT));
        for (LibraryScanner.Source d : dlcs) dl.addView(text("• " + (d.name.isEmpty() ? d.file.getName() : d.name) + "  ·  " + d.titleId, 14, Color.LTGRAY, Typeface.DEFAULT));
        for (File f : extraDlc) {
            boolean known = false;
            for (LibraryScanner.Source d : dlcs) if (d.file.equals(f)) known = true;
            if (known) continue;
            LinearLayout r = new LinearLayout(a); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL);
            r.addView(text("• " + f.getName(), 14, Color.LTGRAY, Typeface.DEFAULT), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            Button x = new Button(a); x.setText("Remove"); x.setOnClickListener(v -> { extraDlc.remove(f); saveDlc(); render(); });
            r.addView(x);
            dl.addView(r);
        }
        row("DLC", null, "Add…", v -> a.pickFile(REQ_DLC, "Pick a DLC (.nsp)"), dl);
        // Companion
        File root = title.companionRoot();
        String installed = companion == null ? null : CompanionPort.installedVersion(root, companion.packageName);
        String cs = companion == null ? "no companion in the catalog"
                : installed == null ? "available: " + companion.packageName + " " + companion.version + " (" + String.format(Locale.US, "%.0f KB", companion.size / 1024.0) + ")"
                : installed.equals(companion.version) ? "installed " + installed : "installed " + installed + "  ·  update " + companion.version;
        LinearLayout cl = new LinearLayout(a); cl.setOrientation(LinearLayout.VERTICAL);
        cl.addView(text(cs, 14, Color.LTGRAY, Typeface.DEFAULT));
        if (companion != null && !companion.version.equals(installed)) {
            CheckBox cb = new CheckBox(a); cb.setText(installed == null ? "Install after the build" : "Update after the build"); cb.setTextColor(Color.WHITE); cb.setChecked(installCompanion);
            cb.setOnCheckedChangeListener((v, on) -> { installCompanion = on; PeanutsPrefs.installCompanion(a, title.titleId, on); });
            cl.addView(cb);
        }
        row("Companion", null, null, null, cl);
        // Build
        if (title.status == LibraryScanner.Status.NO_PROFILE) {
            box.addView(text("This app version does not include " + title.name + " (" + title.titleId + "). Install a build that includes it.", 14, Color.rgb(0xff, 0x8a, 0x65), Typeface.DEFAULT));
        } else {
            build = new Button(a);
            build.setText(title.status == LibraryScanner.Status.READY ? "Rebuild" : "Build");
            build.setBackgroundColor(Color.rgb(0xff, 0xd9, 0x5a)); build.setTextColor(Color.rgb(0x14, 0x12, 0x0c)); build.setTypeface(Typeface.DEFAULT_BOLD);
            build.setEnabled(!building && title.base != null);
            build.setOnClickListener(v -> startBuild());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(12);
            box.addView(build, lp);
            if (title.base != null && title.base.needsImport() && !PeanutsPrefs.hasKeys(a)) box.addView(text("The import needs your prod.keys: Setup (⋮ menu).", 13, Color.rgb(0xff, 0x8a, 0x65), Typeface.DEFAULT));
        }
        bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000); bar.setVisibility(building ? View.VISIBLE : View.GONE);
        box.addView(bar);
        progressText = text("", 13, Color.LTGRAY, Typeface.DEFAULT);
        box.addView(progressText);
    }

    /** The state the pipeline gets, from the sheet as it is now */
    State state() {
        State s = new State();
        s.titleId = title.titleId; s.name = title.name;
        s.base = title.base == null ? null : title.base.file; s.needsImport = title.base != null && title.base.needsImport(); s.baseVersion = title.base == null ? 0 : title.base.version;
        if (chosenUpdate != null && chosenUpdate.needsImport()) { s.update = chosenUpdate.file; s.updateVersion = chosenUpdate.version; }
        for (LibraryScanner.Source d : title.dlcs) if (d.needsImport() && !s.dlcs.contains(d.file)) s.dlcs.add(d.file);
        for (File f : extraDlc) if (f.isFile() && !s.dlcs.contains(f)) s.dlcs.add(f);
        s.home = title.home; s.launch = title.launch;
        if (s.home != null) s.nup = s.needsImport ? new File(s.home, s.home.getName() + ".nup") : s.launch;
        s.companionRoot = title.companionRoot();
        if (companion != null && installCompanion) { s.companionPackage = companion.packageName; s.companionVersion = companion.version; s.installCompanion = true; s.companionItem = companion; }
        return s;
    }

    private void startBuild() {
        if (building) return;
        State s = state();
        if (s.base == null) { Toast.makeText(a, "No base game found", Toast.LENGTH_SHORT).show(); return; }
        if (s.home == null) { Toast.makeText(a, "Set the game folder first (Setup)", Toast.LENGTH_LONG).show(); return; }
        building = true;
        build.setEnabled(false);
        bar.setVisibility(View.VISIBLE); bar.setIndeterminate(true);
        progressText.setText("Starting…");
        dialog.setCancelable(false);
        BuildPort.get().build(a, s, new BuildPort.Listener() {
            @Override public void progress(String step, long done, long total) {
                ui.post(() -> {
                    if (total > 0) { bar.setIndeterminate(false); bar.setProgress((int) (done * 1000 / total)); progressText.setText(step + "  ·  " + (done * 100 / total) + "%"); }
                    else { bar.setIndeterminate(true); progressText.setText(step + (done > 0 ? "  ·  " + done : "")); }
                });
            }
            @Override public void done(boolean ok, String message) {
                ui.post(() -> {
                    building = false;
                    dialog.setCancelable(true);
                    bar.setVisibility(View.GONE);
                    progressText.setText(ok ? "Done." + (message == null || message.isEmpty() ? "" : " " + message) : "Failed: " + message);
                    progressText.setTextColor(ok ? Color.rgb(0x3d, 0xdc, 0x84) : Color.rgb(0xff, 0x8a, 0x65));
                    if (build != null) build.setEnabled(true);
                    if (ok) { Toast.makeText(a, title.name + ": ready", Toast.LENGTH_LONG).show(); dialog.dismiss(); }
                    if (onFinished != null) onFinished.run();
                });
            }
        });
    }

    /** A picked file (LibraryActivity.onActivityResult) for the Choose…/Add… buttons */
    void picked(int request, File f) {
        if (request == REQ_UPDATE) {
            PeanutsPrefs.chosenUpdate(a, title.titleId, f.getPath());
            LibraryScanner.Source hit = null;
            for (LibraryScanner.Source u : title.updates) if (u.file.equals(f)) hit = u;
            if (hit == null) {
                List<LibraryScanner.Source> probed = new LibraryScanner(a).probe(f);
                for (LibraryScanner.Source s : probed) if (s.kind == LibraryScanner.UPDATE) hit = s;
                if (hit == null) hit = new LibraryScanner.Source(f, LibraryScanner.UPDATE, title.titleId, title.titleId, 0, "", "");
                title.updates.add(hit);
            }
            chosenUpdate = hit;
        } else if (request == REQ_DLC) {
            if (!extraDlc.contains(f)) extraDlc.add(f);
            saveDlc();
        }
        render();
    }

    private void saveDlc() {
        List<String> l = new ArrayList<>();
        for (File f : extraDlc) l.add(f.getPath());
        PeanutsPrefs.chosenDlc(a, title.titleId, l);
    }

    // ---- Views ----

    private void row(String name, String value, String button, View.OnClickListener l) { row(name, value, button, l, null); }

    private void row(String name, String value, String button, View.OnClickListener l, View body) {
        LinearLayout r = new LinearLayout(a); r.setOrientation(LinearLayout.VERTICAL); r.setPadding(0, dp(8), 0, dp(8));
        LinearLayout head = new LinearLayout(a); head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text(name, 13, Color.rgb(0xff, 0xd9, 0x5a), Typeface.DEFAULT_BOLD), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (button != null) { Button b = new Button(a); b.setText(button); b.setEnabled(!building); b.setOnClickListener(l); head.addView(b); }
        r.addView(head);
        if (value != null) r.addView(text(value, 14, Color.WHITE, Typeface.DEFAULT));
        if (body != null) r.addView(body);
        box.addView(r);
    }

    private TextView text(String s, int sp, int color, Typeface face) {
        TextView t = new TextView(a);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setTypeface(face); t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    private static String versionText(long v) { return v == 0 ? "base" : (v & 0xFFFF) == 0 ? "v" + (v >> 16) : "v" + v; }
    private int dp(int v) { return Math.round(v * a.getResources().getDisplayMetrics().density); }

}
