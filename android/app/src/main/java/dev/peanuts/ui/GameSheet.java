package dev.peanuts.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import dev.nxnative.android.R;
import dev.peanuts.PeanutsPrefs;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A game's sheet (SPEC.md): a Banana dialog (PUi.sheet, Close as the negative button) of Setting rows: its base, the update that goes in (the newest found;
 * Choose another… picks a file), its DLC (the ones matched by title ID; Add… picks more), its companion from the catalog ("Install after the build" toggle), then
 * Build (Rebuild for a ready game), which hands the sheet's {@link State} to the build pipeline through {@link Library#startBuild} and closes: progress lives on the
 * game's card. Without prod.keys an NSP/XCI's Build is off, with "Add keys…" beside it (the keys picker; the sheet re-renders when they land). A game this app has
 * no profile for gets Profile.missing's sentence only.
 */
public final class GameSheet {
    static final int REQ_UPDATE = LibrarySettings.REQ_SHEET_UPDATE, REQ_DLC = LibrarySettings.REQ_SHEET_DLC;

    /** What the pipeline gets: the files to import, where the .nup and the pack go, the companion to install. Public: dev.peanuts.build reads it */
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

    /** The open sheet (the activity forwards the file pickers' results to it: LibrarySettings.result) */
    static GameSheet current;

    private final Activity a;
    private final LibraryScanner.Title title;
    private final CompanionPort.Item companion;
    private final Library lib;
    private AlertDialog dialog;
    private LinearLayout box;
    private LibraryScanner.Source chosenUpdate;
    private final List<File> extraDlc = new ArrayList<>();
    private boolean installCompanion;

    private GameSheet(Activity a, LibraryScanner.Title t, CompanionPort.Item companion, Library lib) {
        this.a = a; this.title = t; this.companion = companion; this.lib = lib;
        String picked = PeanutsPrefs.chosenUpdate(a, t.titleId);
        for (LibraryScanner.Source u : t.updates) if (picked != null && u.file.getPath().equals(picked)) chosenUpdate = u;
        if (chosenUpdate == null && !t.updates.isEmpty()) chosenUpdate = t.updates.get(0);   // sorted newest first
        for (String d : PeanutsPrefs.chosenDlc(a, t.titleId)) extraDlc.add(new File(d));
        installCompanion = companion != null && PeanutsPrefs.installCompanion(a, t.titleId);
    }

    /** MainActivity's Manage › Build again (the PEANUTS row): the sheet of that title */
    public static void open(Activity a, String titleId) { Library.openSheet(a, titleId); }

    static void show(Activity a, LibraryScanner.Title t, CompanionPort.Item companion, Library lib) {
        GameSheet s = new GameSheet(a, t, companion, lib);
        current = s;
        s.open();
    }

    private void open() {
        box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        render();
        dialog = PUi.sheet(a, title.name, box).setNegativeButton("Close", null).create();
        dialog.setOnDismissListener(d -> { if (current == this) current = null; });
        dialog.show();
    }

    private void render() {
        box.removeAllViews();
        String missing = title.status == LibraryScanner.Status.NO_PROFILE ? Upstream.profileMissing(title.titleId, title.name) : null;
        if (missing != null) { PUi.add(box, PUi.body(a, missing), 0); return; }
        boolean ready = title.status == LibraryScanner.Status.READY;
        boolean building = lib.building(title.titleId);
        PUi.add(box, PUi.body(a, ready
                ? "Rebuild makes the .nup and the pack again from the files below, and updates the companion. About 25 minutes, in the background."
                : "Build makes one .nup from the files below, prepares it, and installs the companion. About 25 minutes, in the background."), 0);
        // Base
        LibraryScanner.Source b = title.base;
        String baseLine = b == null ? "None found" : b.file.getName() + (b.version > 0 || !b.isDump() && !b.isNup() ? " · " + versionText(b.version) : "")
                + " · " + (b.isDump() ? "dump folder" : b.isNup() ? ".nup" : b.file.getName().toLowerCase(Locale.ROOT).endsWith(".xci") ? "XCI, import needed" : "import needed")
                + (!b.publisher.isEmpty() ? " · " + b.publisher : "");
        PUi.add(box, new PUi.Setting(a, "📀", "Base", null).value(baseLine).view, 12);
        // Update
        LibraryScanner.Source newest = title.updates.isEmpty() ? null : title.updates.get(0);
        String up = chosenUpdate == null ? (title.updates.isEmpty() ? "None found · Choose…" : "None · Choose…")
                : chosenUpdate.file.getName() + " · " + versionText(chosenUpdate.version) + (chosenUpdate == newest ? ", the newest found" : "") + " · Choose another…";
        PUi.add(box, new PUi.Setting(a, "⬆️", "Update", v -> { if (!building) LibrarySettings.pickFile(a, REQ_UPDATE, "Pick the update (.nsp / .xci)"); }).value(up).chevron(a).view, 0);
        // DLC
        List<String> names = new ArrayList<>();
        for (LibraryScanner.Source d : title.dlcs) names.add(d.name.isEmpty() ? d.file.getName() : d.name);
        for (File f : extraDlc) { boolean known = false; for (LibraryScanner.Source d : title.dlcs) if (d.file.equals(f)) known = true; if (!known) names.add(f.getName()); }
        String dl = names.isEmpty() ? "None found · Add…" : names.size() + (names.size() == 1 ? " found: " : " found: ") + String.join(", ", names) + " · Add…";
        PUi.add(box, new PUi.Setting(a, "➕", "DLC", v -> { if (!building) LibrarySettings.pickFile(a, REQ_DLC, "Pick a DLC (.nsp)"); }).value(dl).chevron(a).view, 0);
        if (!extraDlc.isEmpty()) PUi.add(box, PUi.button(a, "Forget the DLC you added", PUi.Kind.QUIET, v -> { extraDlc.clear(); saveDlc(); render(); }), 0, PUi.wrap());
        // Companion
        File root = title.companionRoot();
        String installed = companion == null ? null : CompanionPort.installedVersion(root, companion.packageName);
        boolean canInstall = companion != null && !companion.version.equals(installed);
        String cs = companion == null ? "No companion in the catalog for this game"
                : installed == null ? companion.packageName + " " + companion.version + " · " + size(companion.size) + (installCompanion ? " · Install after the build" : " · Not installed after the build")
                : installed.equals(companion.version) ? "installed " + installed + " · up to date"
                : "installed " + installed + " · " + companion.version + " " + (installCompanion ? "after the build" : "available");
        PUi.Setting comp = new PUi.Setting(a, "🖥️", "Companion", null).value(cs);
        if (canInstall) comp.toggle(a, installCompanion, (v, on) -> { installCompanion = on; PeanutsPrefs.installCompanion(a, title.titleId, on); render(); });
        PUi.add(box, comp.view, 0);
        PUi.add(box, PUi.divider(a), 12, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, PUi.dp(a, 1)));
        // Build
        boolean needKeys = title.base != null && title.base.needsImport() && !PeanutsPrefs.hasKeys(a);
        if (needKeys) {
            LinearLayout r = PUi.row(a);
            r.addView(PUi.warnChip(a, "Keys needed", "Keys needed", "Importing an NSP or XCI decrypts it with your own prod.keys. Add them, then Build."));
            TextView t = PUi.small(a, "Importing an NSP needs your prod.keys.");
            t.setPadding(PUi.dp(a, 10), 0, 0, 0);
            r.addView(t, PUi.weight());
            PUi.add(box, r, 14);
        }
        if (title.base == null) PUi.add(box, PUi.body(a, "No base game found. Only an update or DLC is in the folder."), 14);
        if (building) PUi.add(box, PUi.small(a, "Building now: the progress is on the game's card."), 14);
        LinearLayout actions = PUi.row(a);
        PUi.Action build = PUi.action(a, R.drawable.ic_build, ready ? "Rebuild" : "Build", PUi.Kind.PRIMARY, v -> startBuild());
        build.setEnabled(!building && title.base != null && !needKeys && title.home != null);
        actions.addView(build, PUi.weight());
        if (needKeys) {
            LinearLayout.LayoutParams lp = PUi.wrap();
            lp.leftMargin = PUi.dp(a, 10);
            actions.addView(PUi.button(a, "Add keys…", PUi.Kind.SOFT, v -> LibrarySettings.pickKeys(a)), lp);
        }
        PUi.add(box, actions, 14);
    }

    private static String size(long bytes) { return bytes >= 1 << 20 ? String.format(Locale.US, "%.1f MB", bytes / 1048576.0) : String.format(Locale.US, "%.0f KB", bytes / 1024.0); }

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
        State s = state();
        if (s.base == null) { Toast.makeText(a, "No base game found", Toast.LENGTH_SHORT).show(); return; }
        if (s.home == null) { Toast.makeText(a, "Set the game folder first (Settings › Library)", Toast.LENGTH_LONG).show(); return; }
        String why = lib.startBuild(s);
        if (why != null) { new AlertDialog.Builder(a).setTitle("Build").setMessage(why).setPositiveButton("OK", null).show(); return; }
        dialog.dismiss();
    }

    /** A picked file (LibrarySettings.result) for the Update and DLC rows */
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

    /** The keys landed (Add keys…): Build comes on */
    void keysChanged() { render(); }

    private void saveDlc() {
        List<String> l = new ArrayList<>();
        for (File f : extraDlc) l.add(f.getPath());
        PeanutsPrefs.chosenDlc(a, title.titleId, l);
    }

    private static String versionText(long v) { return v == 0 ? "v0" : (v & 0xFFFF) == 0 ? "v" + (v >> 16) : "v" + v; }
}
