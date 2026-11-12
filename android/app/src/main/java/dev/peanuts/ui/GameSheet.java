package dev.peanuts.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import dev.nxnative.android.R;
import dev.peanuts.PeanutsPrefs;
import dev.peanuts.build.SpaceEstimate;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
        /** The player's explicit "Build anyway" for a version not in the profile's build list (P1, critique-phase1 must-fix 2): never set on its own */
        public boolean buildAnyway;
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
        boolean baseGone = b != null && !b.file.exists();
        String baseLine = b == null ? "None found"
                : baseGone ? b.file.getName() + " — this file is no longer where the app found it. Rescan, or pick the game folder again (Settings › Library)."
                : b.file.getName() + (b.version > 0 || !b.isDump() && !b.isNup() ? " · " + versionText(b.version) : "")
                + " · " + (b.isDump() ? "dump folder" : b.isNup() ? ".nup" : b.file.getName().toLowerCase(Locale.ROOT).endsWith(".xci") ? "XCI, import needed" : "import needed")
                + (!b.publisher.isEmpty() ? " · " + b.publisher : "");
        PUi.add(box, new PUi.Setting(a, "📀", "Base", null).value(baseLine).view, 12);
        // Update
        LibraryScanner.Source newest = title.updates.isEmpty() ? null : title.updates.get(0);
        boolean updateGone = chosenUpdate != null && !chosenUpdate.file.exists();
        String up = updateGone ? chosenUpdate.file.getName() + " — this file is no longer where the app found it. Pick it again."
                : chosenUpdate == null ? (title.updates.isEmpty() ? "None found · Choose…" : "None · Choose…")
                : chosenUpdate.file.getName() + " · " + versionText(chosenUpdate.version) + (chosenUpdate == newest ? ", the newest found" : "") + " · Choose another…";
        PUi.add(box, new PUi.Setting(a, "⬆️", "Update", v -> { if (!building) LibrarySettings.pickFile(a, REQ_UPDATE, "Pick the update (.nsp / .xci)"); }).value(up).chevron(a).view, 0);
        // Version (P1, UX-SPEC §2.2): the version the player has vs. what this build's profile supports, read before Build rather than discovered deep into a run
        boolean fileGone = baseGone || updateGone;
        if (!fileGone) renderVersionRow();
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
        // P2.5: "Versions…" opens the Companion Versions sheet (dev.peanuts.ui.CompanionVersions, another agent's package) when the catalog has this title at
        // all. onClose: installedVersion (above) is read live from disk on every render, not cached in a field here, so re-rendering after a rollback/switch
        // is the whole refresh — critique-phase2a §B's "cached state goes stale" note, satisfied without a separate cache to invalidate
        if (companion != null) PUi.add(box, PUi.button(a, "Versions…", PUi.Kind.QUIET, v -> dev.peanuts.ui.CompanionVersions.open(a, title.titleId, this::render)), 2, PUi.wrap());
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
        if (fileGone) PUi.add(box, PUi.body(a, "Pick the file again above before building."), 14);
        if (building) PUi.add(box, PUi.small(a, "Building now: the progress is on the game's card."), 14);
        // Space needed (P4/SpaceEstimate, warn-only: critique-phase1 must-fix 7 — never disables Build, the multiplier is uncalibrated)
        renderSpaceLine();
        boolean unsupported = chosenVersionState() == LibraryScanner.VersionState.UNSUPPORTED;
        LinearLayout actions = PUi.row(a);
        PUi.Action build = PUi.action(a, R.drawable.ic_build, ready ? "Rebuild" : unsupported ? "Build anyway" : "Build", PUi.Kind.PRIMARY, v -> startBuild());
        build.setEnabled(!building && title.base != null && !needKeys && title.home != null && !fileGone);
        actions.addView(build, PUi.weight());
        if (needKeys) {
            LinearLayout.LayoutParams lp = PUi.wrap();
            lp.leftMargin = PUi.dp(a, 10);
            actions.addView(PUi.button(a, "Add keys…", PUi.Kind.SOFT, v -> LibrarySettings.pickKeys(a)), lp);
        }
        PUi.add(box, actions, 14);
    }

    private static String size(long bytes) { return bytes >= 1 << 20 ? String.format(Locale.US, "%.1f MB", bytes / 1048576.0) : String.format(Locale.US, "%.0f KB", bytes / 1024.0); }

    /**
     * The version state of whichever file is actually chosen right now (critique-phase2a §A must-fix 1): {@code title.versionState} (LibraryScanner) is
     * computed from {@code title.version()}, the newest update found on disk — not the file the player picked with "Choose another…", which can be an
     * older, or an unlisted, file. Recomputing this live on every render (instead of caching it) means {@link #picked} needs no extra step to stay current:
     * calling {@code render()} after a pick is already enough. Returns UNKNOWN when there is no profile build data to compare against (never blocks or warns)
     */
    private LibraryScanner.VersionState chosenVersionState() {
        if (title.supportedBuilds.isEmpty()) return LibraryScanner.VersionState.UNKNOWN;
        long have = chosenUpdate != null ? chosenUpdate.version : (title.base != null ? title.base.version : 0);
        if (!title.supportedBuilds.containsKey(have)) return LibraryScanner.VersionState.UNSUPPORTED;
        return have == title.recommendedVersion ? LibraryScanner.VersionState.RECOMMENDED : LibraryScanner.VersionState.SUPPORTED_OTHER;
    }

    /**
     * The Version row (P1, UX-SPEC §2.2): "You have" is the chosen update's version (else the base's), "This build supports" lists the profile's builds with
     * the recommended one marked. Nothing is shown for UNKNOWN (no profile builds data: never a reason to block or warn)
     */
    private void renderVersionRow() {
        LibraryScanner.VersionState vs = chosenVersionState();
        if (vs == LibraryScanner.VersionState.UNKNOWN || vs == LibraryScanner.VersionState.NOT_IN_APP) return;
        long have = chosenUpdate != null ? chosenUpdate.version : (title.base != null ? title.base.version : 0);
        String haveText = versionText(have) + (title.supportedBuilds.get(have) != null ? " (" + title.supportedBuilds.get(have) + ")" : "");
        String supports = supportedShortText();
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(PUi.small(a, "You have: " + haveText));
        col.addView(PUi.small(a, "This build supports: " + supports));
        int tint;
        String note;
        switch (vs) {
            case RECOMMENDED: tint = R.color.bn_leaf; note = "Matches this build's shader tables."; break;
            case SUPPORTED_OTHER: tint = R.color.bn_banana; note = "Supported, but builds slower the first time (a full shader scan): the shader tables are for " + recommendedText() + "."; break;
            default: tint = R.color.bn_danger; note = "Not one of this build's versions. Build anyway may not work, or Choose another… picks the recommended update."; break;   // UNSUPPORTED
        }
        TextView n = PUi.text(a, note, 13, tint, 600);
        n.setPadding(0, PUi.dp(a, 2), 0, 0);
        col.addView(n);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView tile = PUi.text(a, "🧩", 18, R.color.bn_text, 400);   // puzzle piece, matches the mockup's Version row icon
        tile.setGravity(android.view.Gravity.CENTER);
        tile.setBackground(PUi.round(PUi.col(a, R.color.bn_tint), PUi.dp(a, 12), 0, 0));
        tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(tile, new LinearLayout.LayoutParams(PUi.dp(a, 40), PUi.dp(a, 40)));
        LinearLayout.LayoutParams clp = PUi.fill();
        clp.setMargins(PUi.dp(a, 14), 0, PUi.dp(a, 8), 0);
        row.addView(col, clp);
        PUi.add(box, row, 10);
    }

    /** The profile's builds in words ("1.0.0, 1.0.1 or 1.2.1 (recommended)"), for the Version row and the "Build anyway" confirm dialog */
    private String supportedShortText() {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Long, String> e : title.supportedBuilds.entrySet()) {
            String d = e.getValue() != null ? e.getValue() : versionText(e.getKey());
            parts.add(d + (e.getKey() == title.recommendedVersion ? " (recommended)" : ""));
        }
        return parts.isEmpty() ? "nothing known" : String.join(", ", parts);
    }

    /** The recommended build's display version, else its version number */
    private String recommendedText() {
        String d = title.supportedBuilds.get(title.recommendedVersion);
        return d != null ? d : versionText(title.recommendedVersion);
    }

    /**
     * A dump-folder or .nup base's size on disk, cached across sheet opens by title ID (critique-phase2b §A-1: Engage's dump folder alone is 206,874
     * files — one base, the game's exefs plus romfs, so the recursive {@code SpaceEstimate.onDiskBytes} walk is real filesystem I/O, not a quick stat, and
     * must never run on the UI thread). A title's base rarely changes between sheet opens, and this is only ever a cosmetic estimate, so a process-lifetime
     * cache (never invalidated by a rescan) is an acceptable trade for not re-walking a 200K-file dump every time its sheet opens
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> ON_DISK_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean computingSpace;   // this sheet instance already started the background walk for its base — render() runs again once, not on every pass

    /**
     * The "Needs about X GB" line (P4/SpaceEstimate §3.2, critique-phase2a §A must-fix 3, critique-phase2b §A-1): warn-only, never disables Build. Compares
     * the game folder's volume against only the import size, and the app's external storage volume (not internal — {@code getExternalFilesDir(null)},
     * where AppStorage actually puts the pack) against only the pack size, whose basis is the merged .nup's size when importing, else the existing base's
     * real size on disk. For a dump-folder/.nup base not yet in {@link #ON_DISK_CACHE}, this renders "Checking space…" and kicks off exactly one background
     * walk for this sheet instance; the real line replaces it once the walk lands and a render fires again — never a synchronous walk on the UI thread
     */
    private void renderSpaceLine() {
        boolean needsImport = title.base != null && title.base.needsImport();
        List<File> inputs = new ArrayList<>();
        if (needsImport) {
            inputs.add(title.base.file);
            if (chosenUpdate != null && chosenUpdate.needsImport()) inputs.add(chosenUpdate.file);
            for (LibraryScanner.Source d : title.dlcs) if (d.needsImport()) inputs.add(d.file);
            for (File f : extraDlc) inputs.add(f);
        }
        long importBytes = SpaceEstimate.inputBytes(inputs);
        Long cachedBasis = needsImport || title.base == null ? null : ON_DISK_CACHE.get(title.titleId);
        if (!needsImport && title.base != null && cachedBasis == null) {
            PUi.add(box, PUi.small(a, "Checking space…"), 14);
            if (!computingSpace) {
                computingSpace = true;
                File baseFile = title.base.file;
                String titleId = title.titleId;
                new Thread(() -> {
                    long size = SpaceEstimate.onDiskBytes(baseFile);
                    ON_DISK_CACHE.put(titleId, size);
                    a.runOnUiThread(() -> { if (current == this && dialog != null && dialog.isShowing()) render(); });
                }, "peanuts-space").start();
            }
            return;
        }
        long packBasis = needsImport ? importBytes : (cachedBasis != null ? cachedBasis : 0L);
        String gameDir = PeanutsPrefs.gameDir(a);
        String warn = SpaceEstimate.warning(importBytes, packBasis, needsImport, gameDir == null ? null : new File(gameDir), a.getExternalFilesDir(null));
        if (warn != null) PUi.add(box, PUi.warnChip(a, "Low disk space", "Low disk space", warn + " Build still works; it may just run out partway through."), 14);
        else {
            long need = SpaceEstimate.totalNeeded(importBytes, packBasis, needsImport);
            PUi.add(box, PUi.small(a, "Needs about " + SpaceEstimate.words(need) + " free (a rough estimate)."), 14);
        }
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

    /**
     * The same {@link State} {@link #state()} would build, rebuilt from a title and its persisted sheet choices (PeanutsPrefs) with no sheet open — for the
     * build card's "Resume" (critique-phase2a §A must-fix 2): it must actually resume the job, not just reopen the sheet. The job itself resumes on its own
     * (Prepare.java's existing job.json path); this only has to reconstruct the same request the sheet would have sent
     */
    static State resumeState(Activity a, LibraryScanner.Title t, CompanionPort.Item companion) {
        State s = new State();
        s.titleId = t.titleId; s.name = t.name;
        s.base = t.base == null ? null : t.base.file; s.needsImport = t.base != null && t.base.needsImport(); s.baseVersion = t.base == null ? 0 : t.base.version;
        String picked = PeanutsPrefs.chosenUpdate(a, t.titleId);
        LibraryScanner.Source chosen = null;
        for (LibraryScanner.Source u : t.updates) if (picked != null && u.file.getPath().equals(picked)) chosen = u;
        if (chosen == null && !t.updates.isEmpty()) chosen = t.updates.get(0);
        if (chosen != null && chosen.needsImport()) { s.update = chosen.file; s.updateVersion = chosen.version; }
        for (LibraryScanner.Source d : t.dlcs) if (d.needsImport() && !s.dlcs.contains(d.file)) s.dlcs.add(d.file);
        for (String p : PeanutsPrefs.chosenDlc(a, t.titleId)) { File f = new File(p); if (f.isFile() && !s.dlcs.contains(f)) s.dlcs.add(f); }
        s.home = t.home; s.launch = t.launch;
        if (s.home != null) s.nup = s.needsImport ? new File(s.home, s.home.getName() + ".nup") : s.launch;
        s.companionRoot = t.companionRoot();
        boolean installCompanion = companion != null && PeanutsPrefs.installCompanion(a, t.titleId);
        if (companion != null && installCompanion) { s.companionPackage = companion.packageName; s.companionVersion = companion.version; s.installCompanion = true; s.companionItem = companion; }
        // buildAnyway stays false: a job that already needed it is already marked tryAnyway in its own job.json (Prepare.run0 reads that, not this request's
        // consent, once a job exists), and a job that never needed it does not care
        return s;
    }

    /**
     * Build, or Build anyway: for an UNSUPPORTED version (P1, critique-phase1 must-fix 2) this always asks first — Prepare.java never relaxes the check on its
     * own any more, so without this confirm the build would just stop and ask again from the failed card instead of going on
     */
    private void startBuild() {
        if (chosenVersionState() == LibraryScanner.VersionState.UNSUPPORTED) {
            PUi.confirmDanger(a, "Build anyway?", "Version " + versionText(chosenUpdate != null ? chosenUpdate.version : title.base != null ? title.base.version : 0)
                            + " is not one of this build's versions (it supports " + supportedShortText() + "). It goes on without that check: the game may not start, "
                            + "may crash, or may draw parts wrongly, and the first build scans every shader (slower). Your dump and saves are not changed.",
                    "Build anyway", () -> doStartBuild(true));
        } else doStartBuild(false);
    }

    private void doStartBuild(boolean anyway) {
        State s = state();
        s.buildAnyway = anyway;
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
