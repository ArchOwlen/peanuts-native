package dev.peanuts.build;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The build: a foreground service ("Building &lt;name&gt;" with a progress bar) in the launcher's process that runs the steps in order on one worker thread, each
 * resumable (a step whose output is there already is skipped):
 * <ol>
 * <li><b>import</b> — an NSP/XCI base becomes &lt;gameDir&gt;/&lt;Name&gt;/&lt;Name&gt;.nup with its update and DLC inside ({@link Importer}: dev.peanuts.importer.Native.writeNup
 *     after Native.keys on &lt;files&gt;/keys/prod.keys); skipped when the .nup is there and newer than every input. A .nup or a dump folder passes through.</li>
 * <li><b>prepare</b> — the on-device Prepare ({@link Prepare}): upstream's job in &lt;dump&gt;/../&lt;title ID&gt;-work/ondevice/job.json, run by its PackService (":pack"),
 *     watched until the pack is done, then the game into the launcher's list.</li>
 * <li><b>companion</b> — the chosen catalog package into &lt;title ID&gt;-work/peanuts/&lt;Name&gt;/ ({@link Companions}); skipped when that version is installed.</li>
 * </ol>
 * One build at a time; a second request while one runs is refused with a message. ACTION_CANCEL stops the running build between steps (the packer's job stays
 * saved; the Home card resumes it). Needs the manifest entry (dev/peanuts/settings/HOOKS.md) and, for Android 13+, the notification permission for the bar to show.
 */
public final class BuildService extends Service {
    public static final String ACTION_BUILD = "dev.peanuts.build.BUILD", ACTION_CANCEL = "dev.peanuts.build.CANCEL";
    static final String EXTRA_ID = "id", EXTRA_TITLE = "title_id", EXTRA_NAME = "name", EXTRA_BASE = "base", EXTRA_UPDATE = "update", EXTRA_DLCS = "dlcs", EXTRA_GAME_DIR = "game_dir",
            EXTRA_COMPANION = "companion";
    private static final String TAG = Prepare.TAG, CHANNEL = "peanuts_build";
    private static final int NOTE = 7001;
    /** Each step's share of the bar: import 0-45, prepare 45-90, companion 90-100 */
    private static final int IMPORT_END = 45, PREPARE_END = 90;

    private final Handler main = new Handler(Looper.getMainLooper());
    private NotificationManager nm;
    private Thread worker;
    private volatile Prepare prepare;
    private volatile boolean cancelled;
    private int id;
    private String name = "";

    static Intent intent(Context c, int id, BuildRequest r) {
        Intent i = new Intent(c, BuildService.class).setAction(ACTION_BUILD).putExtra(EXTRA_ID, id).putExtra(EXTRA_TITLE, r.titleId).putExtra(EXTRA_NAME, r.name)
                .putExtra(EXTRA_GAME_DIR, r.gameDir == null ? null : r.gameDir.getPath()).putExtra(EXTRA_COMPANION, r.companionPackage);
        if (r.base != null) i.putExtra(EXTRA_BASE, r.base.getPath());
        if (r.update != null) i.putExtra(EXTRA_UPDATE, r.update.getPath());
        List<String> d = new ArrayList<>();
        for (File f : r.dlcs) if (f != null) d.add(f.getPath());
        i.putStringArrayListExtra(EXTRA_DLCS, new ArrayList<>(d));
        return i;
    }

    static BuildRequest request(Intent i) {
        BuildRequest r = new BuildRequest();
        r.titleId = i.getStringExtra(EXTRA_TITLE);
        r.name = i.getStringExtra(EXTRA_NAME);
        String s = i.getStringExtra(EXTRA_BASE);
        r.base = s == null ? null : new File(s);
        s = i.getStringExtra(EXTRA_UPDATE);
        r.update = s == null ? null : new File(s);
        List<String> d = i.getStringArrayListExtra(EXTRA_DLCS);
        if (d != null) for (String p : d) r.dlcs.add(new File(p));
        s = i.getStringExtra(EXTRA_GAME_DIR);
        r.gameDir = s == null ? null : new File(s);
        r.companionPackage = i.getStringExtra(EXTRA_COMPANION);
        return r;
    }

    @Override public void onCreate() {
        super.onCreate();
        nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Building games", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }
        if (ACTION_CANCEL.equals(intent.getAction())) {
            cancelled = true;
            Prepare p = prepare;
            if (p != null) p.cancel();
            Thread w = worker;
            if (w != null) w.interrupt();
            else stopSelf(startId);
            return START_NOT_STICKY;
        }
        BuildRequest r = request(intent);
        int reqId = intent.getIntExtra(EXTRA_ID, 0);
        if (worker != null && worker.isAlive()) {
            BuildPipeline.finish(reqId, false, name + " is being built now. Wait for it to finish.");
            return START_NOT_STICKY;
        }
        id = reqId;
        name = r.name == null || r.name.isEmpty() ? r.titleId : r.name;
        cancelled = false;
        try {
            startForeground(NOTE, note(0, "Starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } catch (RuntimeException e) {   // ForegroundServiceStartNotAllowedException, or the manifest lacks the service type
            Log.w(TAG, "startForeground refused: " + e);
            BuildPipeline.finish(id, false, "Android did not let the build start in the background: open the app and tap Build again.");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        worker = new Thread(() -> run(r), "peanuts-build");
        worker.start();
        return START_NOT_STICKY;
    }

    private void run(BuildRequest r) {
        boolean ok = false;
        String message;
        try {
            File dump = stepImport(r);
            if (cancelled) throw new IOException("Build cancelled.");
            File pack = null;
            IOException prepareFailed = null;
            try {
                pack = stepPrepare(r, dump);
                if (!cancelled) pack = stepRuntimeShaders(r, dump, pack);
            } catch (IOException e) {
                prepareFailed = e;   // the companion is still installed (it goes beside the dump's work folder, which Prepare made): the next Build has only the pack left to do
            }
            if (cancelled) throw new IOException("Build cancelled.");
            GameEnv.ensureFonts(this, r.titleId);
            GameEnv.ensureBuildId(this, r.titleId, dump);
            try {
                stepCompanion(r, pack);
            } catch (IOException e) {
                if (prepareFailed != null) throw new IOException(prepareFailed.getMessage() + "\n\nCompanion: " + e.getMessage());
                throw e;
            }
            if (prepareFailed != null) throw prepareFailed;
            ok = true;
            message = name + " is ready to play.";
            progress("done", 100, message);
        } catch (IOException e) {
            message = e.getMessage() == null ? e.toString() : e.getMessage();
            Log.w(TAG, "build " + name + ": " + message);
        } catch (RuntimeException e) {
            message = "Build failed: " + e;
            Log.e(TAG, "build " + name, e);
        }
        final boolean fok = ok;
        final String fmsg = message;
        main.post(() -> {
            nm.notify(NOTE + 1, done(fok, fmsg));
            BuildPipeline.finish(id, fok, fmsg);
            worker = null;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
    }

    /** (a) the .nup from an NSP/XCI, or the base as it is */
    private File stepImport(BuildRequest r) throws IOException {
        if (r.base == null || !r.base.exists()) throw new IOException("The game's base file is missing" + (r.base == null ? "." : ": " + r.base));
        if (!r.needsImport()) { progress("import", IMPORT_END, "Game file: " + r.base.getName()); return r.base; }
        if (r.gameDir == null) throw new IOException("No game folder to write the .nup into (Setup).");
        File dir = new File(r.gameDir, r.fileName()), out = new File(dir, r.fileName() + ".nup");
        List<File> inputs = r.inputs();
        long newest = 0;
        for (File f : inputs) newest = Math.max(newest, f.lastModified());
        if (out.isFile() && out.length() > 0 && out.lastModified() >= newest) { progress("import", IMPORT_END, "Imported already: " + out.getName()); return out; }
        if (!Importer.available()) throw new IOException("This build of the app cannot import NSP/XCI files yet (no importer). Use a .nup or a dump folder.");
        File keys = new File(new File(getFilesDir(), "keys"), "prod.keys");
        if (!keys.isFile()) throw new IOException("No prod.keys: add your keys in Setup first.");
        try {
            progress("import", 0, "Loading keys");
            if (Importer.keys(keys) != 0) throw new IOException("Keys: " + Importer.lastError());
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
            File tmp = new File(dir, out.getName() + ".part");
            tmp.delete();
            final long[] last = {0};
            int rc = Importer.writeNup(tmp, inputs, r.titleId, (step, done, total) -> {
                long now = System.currentTimeMillis();
                if (now - last[0] < 250 && done < total) return;
                last[0] = now;
                int pct = total > 0 ? (int) Math.min(IMPORT_END - 1, done * (IMPORT_END - 1) / Math.max(1, total)) : 0;
                progress("import", pct, step + (total > 0 ? String.format(Locale.getDefault(), ": %d %%", Math.min(100, done * 100 / total)) : ""));
            });
            if (cancelled) { tmp.delete(); throw new IOException("Build cancelled."); }
            if (rc != 0) { tmp.delete(); throw new IOException("Import failed: " + Importer.lastError()); }
            if (!tmp.renameTo(out)) { tmp.delete(); throw new IOException("Cannot place " + out); }
        } catch (ReflectiveOperationException e) {
            throw new IOException("The importer does not match this build of the app (" + e.getMessage() + ").");
        }
        progress("import", IMPORT_END, "Imported " + out.getName());
        return out;
    }

    /** (b) the pack, by upstream's on-device Prepare */
    private File stepPrepare(BuildRequest r, File dump) throws IOException {
        Prepare p = new Prepare(this);
        prepare = p;
        try {
            return p.run(dump, r.titleId, (pct, detail) -> progress("prepare", IMPORT_END + pct * (PREPARE_END - IMPORT_END) / 100, detail));
        } finally {
            prepare = null;
        }
    }

    /**
     * (b2) the shaders the game compiles at run time ({@link RuntimeShaders}): when the finished job still misses some, the game is started once to capture them
     * and the job is run again from its split; the pack of the second run is returned (the same folder)
     */
    private File stepRuntimeShaders(BuildRequest r, File dump, File pack) throws IOException {
        File work, jobFile;
        try {
            work = Upstream.workOf(dump, r.titleId);
            jobFile = Upstream.jobFileOf(work);
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "runtime shaders: " + e);
            return pack;
        }
        int need = RuntimeShaders.missing(jobFile);
        if (need <= 0 || !jobFile.isFile()) return pack;
        if (!RuntimeShaders.compilesAtRunTime(this, r.titleId)) { Log.i(TAG, "runtime shaders: " + need + " missing from the pack of " + name + ", but the title compiles none at run time: nothing to capture"); return pack; }
        Log.i(TAG, "runtime shaders: " + need + " missing from the pack of " + name + ": capturing");
        boolean enough = new RuntimeShaders(this).capture(r.titleId, work, dump, pack, need, (pct, detail) -> progress("prepare", PREPARE_END - 5 + pct * 5 / 100, detail), () -> cancelled);
        if (cancelled) throw new IOException("Build cancelled.");
        if (!enough) {
            int have = RuntimeShaders.captured(RuntimeShaders.captureDir(work));
            if (have == 0) throw new IOException(name + " is ready, but " + need + " of its shaders are only made while it runs and none were captured: parts of its picture may stay black. Tap Build again after playing it once.");
            Log.w(TAG, "runtime shaders: only " + have + " of " + need + " captured; re-running the job with those");
        }
        RuntimeShaders.rerun(work, jobFile);
        progress("prepare", IMPORT_END, "Making the pack again with the captured shaders");
        return stepPrepare(r, dump);
    }

    /** (c) the companion beside the pack: <title ID>-work/peanuts/<Name>/ */
    private void stepCompanion(BuildRequest r, File pack) throws IOException {
        if (r.companionPackage == null || r.companionPackage.isEmpty()) { progress("companion", 100, "No companion chosen"); return; }
        File root = companionRoot(pack, dumpWork(r, pack));
        Log.i(TAG, "companion " + r.companionPackage + " for " + r.titleId + " into " + root);
        progress("companion", PREPARE_END, "Looking up the companion");
        Companions.Item item = Companions.find(Companions.fetch(), r.titleId);
        if (item == null) throw new IOException("The catalog has no companion for " + name + " now; the game is ready to play without one.");
        String have = Companions.installedVersion(root, item.packageName);
        if (have != null && Companions.compareVersions(have, item.version) >= 0) { progress("companion", 100, "Companion " + Companions.label(item.packageName, have) + " installed already"); return; }
        Companions.install(item, root, getCacheDir(), (what, done, total) -> progress("companion", PREPARE_END + (total > 0 ? (int) Math.min(9, done * 9 / total) : 0), what));
        progress("companion", 100, "Companion " + Companions.label(item.packageName, item.version) + " installed");
    }

    /** The work folder from the pack when the pack is in it (dump's ../<title ID>-work/android-pack), else beside the dump (DumpCheck.workOf) */
    private static File dumpWork(BuildRequest r, File pack) {
        File parent = pack == null ? null : pack.getParentFile();
        if (parent != null && parent.getName().endsWith("-work")) return parent;
        File dump = r.base == null ? null : r.base.getAbsoluteFile();
        File dp = dump == null ? null : dump.getParentFile();
        return new File(dp != null ? dp : new File("."), r.titleId + "-work");
    }

    /** Where the runtime looks (dm/peanuts/peanuts.c rt_find_package, dev.peanuts.Games.Entry.companionRoot): beside the pack, <pack>/../peanuts (the app's packs/peanuts for a pack
     *  the app owns, <title ID>-work/peanuts for one beside the dump); without a pack, beside the dump */
    static File companionRoot(File pack, File work) {
        File parent = pack == null ? null : pack.getAbsoluteFile().getParentFile();
        return new File(parent != null ? parent : work, "peanuts");
    }

    private void progress(String step, int percent, String detail) {
        if (worker != null) nm.notify(NOTE, note(percent, detail));
        BuildPipeline.progress(id, step, percent, detail);
    }

    private Notification note(int percent, String detail) {
        Intent cancel = new Intent(this, BuildService.class).setAction(ACTION_CANCEL);
        Notification.Builder b = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Building " + name)
                .setContentText(detail).setOnlyAlertOnce(true).setOngoing(true).setProgress(100, Math.max(0, Math.min(100, percent)), false)
                .addAction(new Notification.Action.Builder(null, "Cancel", PendingIntent.getService(this, 1, cancel, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open != null) b.setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return b.build();
    }

    private Notification done(boolean ok, String message) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL).setSmallIcon(ok ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_notify_error)
                .setContentTitle(ok ? name + " is ready to play" : "Building " + name + " stopped").setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message)).setAutoCancel(true);
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open != null) b.setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return b.build();
    }
}
