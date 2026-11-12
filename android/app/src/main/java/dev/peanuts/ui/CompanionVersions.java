package dev.peanuts.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The companion's version history (UX-SPEC.md package 6, CRITIQUE-phase1.md's must-fix: the same GitHub Releases catalog as today, every release kept instead of
 * only the newest). A Banana sheet (PUi.sheet) of every release Catalog knows for the game's title, newest first: date, release notes, size, whether it is
 * verified (dev.peanuts.Installer's sha256 check against the release's digest or a "&lt;asset&gt;.sha256" sibling - a release with neither is refused outright,
 * never installed as "unverified"), and which one is installed. Install / Switch to / Roll back per row - a version already on this device
 * (Installer.localVersions) switches instantly with no re-download, matching Eden Duo's companion manager. Entry point: {@link #open}, called with just the
 * title ID (GameSheet's "Versions" row); the game's name and companion root are found the same way {@link Library} does (a library scan, briefly cached - see
 * {@link #load}), off a worker thread - nothing here touches disk or the network on the main thread (AUDIT.md #6, CRITIQUE-phase2a.md §B).
 */
public final class CompanionVersions {
    private final Activity act;
    private final String titleId;
    private final Runnable onClose;
    private final Handler main = new Handler(Looper.getMainLooper());
    private AlertDialog dialog;
    private LinearLayout box;

    // Loaded off the main thread by load(); read only from render() on the main thread after a main.post
    private boolean loading = true;
    private String loadError;
    private String gameName;
    private File root;
    private String packageName;
    private String knownBuildId;   // PN_BUILD_ID for this title, when the game's env has set one; null when not known
    private List<CompanionPort.Item> versions = new ArrayList<>();
    private List<String> localVersions = new ArrayList<>();
    private String activeVersion;
    private boolean activeVerified;

    private boolean busy;
    private String busyText = "";
    private long lastProgressRenderAt;   // throttles a download/verify's progress re-renders to ~4 Hz (CRITIQUE-phase2a.md §B)

    private CompanionVersions(Activity act, String titleId, Runnable onClose) { this.act = act; this.titleId = titleId; this.onClose = onClose; }

    /** Opens the sheet for a title (GameSheet's "Versions" row: dev.peanuts.ui.CompanionVersions.open(act, titleId)) */
    public static void open(Activity act, String titleId) { open(act, titleId, null); }

    /** As above, plus onClose: called when the sheet is dismissed, so a caller with its own installed-state cache (CompanionSection's installedKnown,
     * CatalogActivity's render()) can invalidate it and redraw - a switch made in here otherwise leaves that caller showing a stale version until something
     * else happens to refresh it (CRITIQUE-phase2a.md §B, should-fix). */
    public static void open(Activity act, String titleId, Runnable onClose) {
        CompanionVersions s = new CompanionVersions(act, titleId, onClose);
        s.show();
    }

    private void show() {
        box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        render();
        dialog = PUi.sheet(act, "Versions", box).setNegativeButton("Close", null).create();
        dialog.setOnDismissListener(d -> { if (onClose != null) onClose.run(); });
        dialog.show();
        load();
    }

    // ---- A short-lived cache of the last library scan, so reopening this sheet (or opening it for a second title right after) doesn't always re-walk the
    // whole game folder just to find one companionRoot (CRITIQUE-phase2a.md §B, should-fix; a real fix needs Library's own cache, which is another agent's
    // file - see IMPL-B.md) ----
    private static List<LibraryScanner.Title> titleCache;
    private static long titleCacheAt;
    private static final long TITLE_CACHE_TTL = 15_000;

    private void load() {
        new Thread(() -> {
            String name = titleId; File r = null; String pkg = null; String buildId = null;
            List<CompanionPort.Item> v = new ArrayList<>();
            List<String> lv = new ArrayList<>();
            String active = null; boolean verified = false; String err = null;
            try {
                List<LibraryScanner.Title> titles;
                synchronized (CompanionVersions.class) {
                    if (titleCache != null && System.currentTimeMillis() - titleCacheAt < TITLE_CACHE_TTL) titles = titleCache;
                    else { titles = new LibraryScanner(act).scan(); titleCache = titles; titleCacheAt = System.currentTimeMillis(); }
                }
                for (LibraryScanner.Title t : titles) {
                    if (t.titleId.equalsIgnoreCase(titleId)) { name = t.name.isEmpty() ? titleId : t.name; r = t.companionRoot(); break; }
                }
                buildId = runningBuildId(titleId);
                String[] errOut = new String[1];
                Map<String, List<CompanionPort.Item>> all = CompanionPort.fetchVersions(errOut);
                err = errOut[0];   // CRITIQUE-phase2a.md §B: surfaced, not swallowed into a misleading empty "No companion" state
                v = CompanionPort.forTitle(all, titleId);
                if (!v.isEmpty()) {
                    pkg = v.get(0).packageName;
                    // Should-fix: a release under a different package name for the same title (e.g. the legacy <TITLEID>.dsmod.zip naming) would otherwise
                    // create a second active folder and the host would load one of the two at random; keep only the current package name's releases.
                    String fpkg = pkg;
                    List<CompanionPort.Item> filtered = new ArrayList<>();
                    for (CompanionPort.Item it : v) if (it.packageName.equals(fpkg)) filtered.add(it);
                    v = filtered;
                }
                if (pkg != null && r != null) {
                    CompanionPort.repair(r, pkg, act);   // self-heal a half-done swap before reading any state, not only before the next install/switch; ctx = act so this skips while the game is running (the lock alone does not cover that - CRITIQUE-phase2d.md B-2)
                    lv = CompanionPort.localVersions(r, pkg);
                    active = CompanionPort.installedVersion(r, pkg);
                    if ("?".equals(active)) active = null;   // a package.json with no installed.json: not a version this sheet can act on
                    verified = active != null && CompanionPort.installedVerified(r, pkg);
                }
            } catch (RuntimeException e) { if (err == null) err = e.getMessage() == null ? e.toString() : e.getMessage(); }
            final String fname = name; final File fr = r; final String fpkg2 = pkg; final String fbuild = buildId; final List<CompanionPort.Item> fv = v; final List<String> flv = lv;
            final String factive = active; final boolean fverified = verified; final String ferr = err;
            main.post(() -> {
                gameName = fname; root = fr; packageName = fpkg2; knownBuildId = fbuild; versions = fv; localVersions = flv; activeVersion = factive; activeVerified = fverified; loadError = ferr;
                loading = false;
                if (dialog != null) dialog.setTitle("Versions · " + gameName);
                render();
            });
        }, "peanuts-companion-versions").start();
    }

    /** PN_BUILD_ID set for this title (dev.peanuts.build.GameEnv.ensureBuildId writes it into the game's env; dev.nxnative.android.Prefs.gameEnv holds the
     * text), or null when none is set yet (the game has never been built, or the line was never written). Read the same way dev.peanuts.build.Upstream does,
     * by its own small reflective call - that bridge is package-private to dev.peanuts.build, not reachable from here. */
    private String runningBuildId(String titleId) {
        try {
            Class<?> prefs = Class.forName("dev.nxnative.android.Prefs");
            java.lang.reflect.Method m = prefs.getDeclaredMethod("gameEnv", android.content.Context.class, String.class);
            m.setAccessible(true);
            Object env = m.invoke(null, act, titleId);
            if (env == null) return null;
            for (String line : env.toString().split("\n")) {
                String l = line.trim();
                if (l.startsWith("PN_BUILD_ID=")) { String id = l.substring("PN_BUILD_ID=".length()).trim(); return id.isEmpty() ? null : id; }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Re-reads only the installed/local state after an install/switch/forget finishes - never on a progress tick (AUDIT.md #6's fix, applied here too) */
    private void refreshLocalState() {
        if (packageName == null || root == null) return;
        localVersions = CompanionPort.localVersions(root, packageName);
        activeVersion = CompanionPort.installedVersion(root, packageName);
        if ("?".equals(activeVersion)) activeVersion = null;
        activeVerified = activeVersion != null && CompanionPort.installedVerified(root, packageName);
    }

    private void render() {
        box.removeAllViews();
        if (loading) { PUi.add(box, PUi.body(act, "Loading versions…"), 0); return; }
        if (loadError != null && versions.isEmpty()) { PUi.add(box, PUi.body(act, "Catalog unavailable: " + loadError), 0); return; }
        if (root == null) { PUi.add(box, PUi.body(act, "Build the game first: a companion goes beside its pack, so there is nowhere to install one yet."), 0); return; }
        if (versions.isEmpty()) { PUi.add(box, PUi.body(act, "No companion in the catalog for this game."), 0); return; }
        PUi.add(box, PUi.small(act, busy ? busyText : versions.size() + (versions.size() == 1 ? " version" : " versions") + " · newest first"), 0);
        if (activeVersion != null) {
            LinearLayout actions = PUi.row(act);
            android.widget.Button removeBtn = PUi.button(act, "Remove companion", PUi.Kind.QUIET, v -> removeCompanion());
            removeBtn.setEnabled(!busy);
            actions.addView(removeBtn, PUi.wrap());
            PUi.add(box, actions, 8);
        }
        LinearLayout card = PUi.add(box, PUi.listCard(act), 14);
        for (CompanionPort.Item item : versions) card.addView(row(item));
    }

    private LinearLayout row(CompanionPort.Item item) {
        boolean isActive = item.version.equals(activeVersion);
        boolean isLocal = localVersions.contains(item.version);
        String verifiedTxt = isActive ? (activeVerified ? "verified" : "unverified (installed by an older version of this app)")
                : item.sha256 != null ? "will verify: release checksum" : item.sha256Url != null ? "will verify: .sha256 asset" : "no checksum published - cannot be installed";
        StringBuilder value = new StringBuilder();
        if (!item.date.isEmpty()) value.append(item.date.length() >= 10 ? item.date.substring(0, 10) : item.date).append(" · ");
        value.append(size(item.size)).append(" · ").append(verifiedTxt);
        if (isLocal && !isActive) value.append(" · on this device");
        String notes = firstLine(item.notes);
        if (!notes.isEmpty()) value.append("\n").append(notes);
        PUi.Setting s = new PUi.Setting(act, "🖥️", item.version + (isActive ? " · installed" : ""), notes.isEmpty() ? null : v -> showNotes(item));
        s.value(value.toString());
        boolean installable = isLocal || (item.sha256 != null || item.sha256Url != null);   // CRITIQUE-phase2a.md §B: a digest-less release is refused by Installer anyway; grey it out here too rather than let the user hit the error
        if (!isActive) {
            String label = activeVersion == null ? "Install" : isOlderThanActive(item.version) ? "Roll back" : "Switch to";
            PUi.Kind kind = !isLocal ? PUi.Kind.PRIMARY : PUi.Kind.TONAL;
            android.widget.Button b = PUi.button(act, label, kind, v -> install(item, isLocal));
            b.setEnabled(!busy && installable);
            s.view.addView(b);
            if (isLocal) {
                android.widget.Button forget = PUi.button(act, "Forget", PUi.Kind.QUIET, v -> forget(item));
                forget.setEnabled(!busy);
                s.view.addView(forget);
            }
        }
        return s.view;
    }

    /** True when version is older than the installed one (Roll back's label), false when newer or equal (Switch to's) */
    private boolean isOlderThanActive(String version) { return activeVersion != null && CompanionPort.compareVersions(version, activeVersion) < 0; }

    private static String firstLine(String notes) {
        if (notes == null) return "";
        String s = notes.trim();
        int nl = s.indexOf('\n');
        if (nl >= 0) s = s.substring(0, nl).trim();
        return s.length() > 140 ? s.substring(0, 140) + "…" : s;
    }

    private static String size(long bytes) { return bytes >= 1 << 20 ? String.format(Locale.US, "%.1f MB", bytes / 1048576.0) : String.format(Locale.US, "%.0f KB", bytes / 1024.0); }

    private void showNotes(CompanionPort.Item item) {
        new AlertDialog.Builder(act).setTitle(item.version).setMessage(item.notes.isEmpty() ? "No release notes." : item.notes).setPositiveButton("Close", null).show();
    }

    private void install(CompanionPort.Item item, boolean isLocal) {
        if (busy) return;
        busy = true; busyText = isLocal ? "Switching to " + item.version + "…" : "Downloading " + item.version + "…";
        lastProgressRenderAt = 0;
        render();
        new Thread(() -> {
            String err = null; String warning = null;
            try {
                // ctx = act (CRITIQUE-phase2c.md §B, should-fix): refuses with "close the game first" if this app's own ":game" process is alive -
                // swapping the active folder out from under a host that has it open breaks anything it loads lazily by path afterwards.
                if (isLocal) warning = CompanionPort.activateLocalVersion(root, packageName, item.version, knownBuildId, act);
                else warning = CompanionPort.install(item, root, act.getCacheDir(), (what, done, total) -> {
                    long now = System.currentTimeMillis();
                    boolean last = total > 0 && done >= total;
                    if (!last && now - lastProgressRenderAt < 250) return;   // CRITIQUE-phase2a.md §B: throttle to ~4 Hz, not every single chunk
                    lastProgressRenderAt = now;
                    main.post(() -> { busyText = (what.equals("download") ? "Downloading " : what.equals("verify") ? "Verifying " : "Installing ") + item.version + (total > 0 ? " " + (done * 100 / total) + " %" : "") + "…"; render(); });
                }, knownBuildId, act);
            } catch (IOException | RuntimeException e) { err = e.getMessage() == null ? e.toString() : e.getMessage(); }
            final String ferr = err; final String fwarning = warning;
            main.post(() -> {
                if (act.isFinishing() || act.isDestroyed()) { busy = false; return; }   // the screen closed during the work: no dialog on a gone window
                busy = false;
                refreshLocalState();
                if (ferr != null) Toast.makeText(act, "Failed: " + ferr, Toast.LENGTH_LONG).show();
                else if (fwarning != null) new AlertDialog.Builder(act).setTitle(item.version + " is active").setMessage(fwarning).setPositiveButton("OK", null).show();
                else Toast.makeText(act, item.version + " is active. It loads at the game's next start.", Toast.LENGTH_LONG).show();
                render();
            });
        }, "peanuts-companion-switch").start();
    }

    private void forget(CompanionPort.Item item) {
        PUi.confirmDanger(act, "Forget " + item.version + "?", "Its files are deleted from this device. It can be downloaded again from the catalog.", "Forget", () -> {
            busy = true; busyText = "Removing " + item.version + "…";
            render();
            new Thread(() -> {   // CRITIQUE-phase2a.md §B: a recursive delete, off the main thread like every other disk operation here
                String err = null;
                try { CompanionPort.forgetLocalVersion(root, packageName, item.version); } catch (IOException e) { err = e.getMessage() == null ? e.toString() : e.getMessage(); }
                final String ferr = err;
                main.post(() -> {
                    busy = false;
                    if (ferr != null) Toast.makeText(act, "Could not forget it: " + ferr, Toast.LENGTH_LONG).show();
                    refreshLocalState();
                    render();
                });
            }, "peanuts-companion-forget").start();
        });
    }

    private void removeCompanion() {
        PUi.confirmDanger(act, "Remove the companion?", gameName + "'s companion (every version on this device) is deleted. The catalog can install it again. The game and its saves stay.", "Remove", () -> {
            busy = true; busyText = "Removing the companion…";
            render();
            new Thread(() -> {   // CRITIQUE-phase2a.md §B: off the main thread
                String err = null;
                try { CompanionPort.remove(root, packageName, act); } catch (IOException e) { err = e.getMessage() == null ? e.toString() : e.getMessage(); }   // ctx = act: refuses while the game is running (CRITIQUE-phase2c.md §B)
                final String ferr = err;
                main.post(() -> {
                    busy = false;
                    if (ferr != null) Toast.makeText(act, "Failed: " + ferr, Toast.LENGTH_LONG).show();
                    refreshLocalState();
                    render();
                });
            }, "peanuts-companion-remove").start();
        });
    }
}
