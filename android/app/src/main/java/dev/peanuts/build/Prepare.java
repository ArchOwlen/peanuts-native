package dev.peanuts.build;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * The Prepare step without MainActivity's Ondevice panel: what Ondevice.Panel.picked → checked → go/adopt does, done directly against upstream's classes
 * (reflection, {@link Upstream}) and its PackService (the ":pack" process):
 * <ol>
 * <li>DumpCheck.check on the .nup / dump folder (the title id, the work folder &lt;dump&gt;/../&lt;title ID&gt;-work, whether this app can prepare it);</li>
 * <li>a job.json already in the work folder for this dump (running, paused, failed, pending) is resumed; a pack beside the dump that fits (PackCheck) is adopted
 *     as it is, one that fits in part has only the rest redone (Job.adopt); else a whole job (Job.create into the app's packs/&lt;title ID&gt;);</li>
 * <li>the job is saved and remembered (Job.remember: the panel's card shows it too when Home is open), and PackService is started with ACTION_START + the job file,
 *     exactly as the panel's startService does. The build service is a foreground service, so startForegroundService from here is allowed while it runs; the
 *     service is also bound (BIND_AUTO_CREATE) so the ":pack" process is held and its MSG_STATE messages carry progress between job.json writes;</li>
 * <li>progress is read from &lt;work&gt;/ondevice/job.json (format 2: "state", "step", "steps".&lt;step&gt;.done/total/stage) once a second until the job is done,
 *     failed, or paused for a reason that stops the service (user, timeout, refused); heat and battery pauses are waited out, as the service does;</li>
 * <li>a done job's game goes into the launcher's list with its pack (Upstream.addGame = Ondevice.Panel.addGame), unless the pack is the fake engine's placeholder.</li>
 * </ol>
 */
final class Prepare {
    interface Progress { void on(int percent, String detail); }

    static final String TAG = "peanuts.build";
    private static final String[] STEPS = {"identify", "rewrite", "split", "shaders", "pack"};
    private static final String[] STEP_TEXT = {"Checking the dump", "Rewriting the game code", "Finding the shaders", "Translating shaders", "Finishing the pack"};
    /** How long a job may sit "running" with no job.json change and no service message before we call it stalled (the service writes at least every few seconds while it runs) */
    private static final long STALL_MS = 10 * 60 * 1000;

    private final Context c;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;
    private volatile String fromService;   // the newest job JSON the service sent over the Messenger
    private volatile long fromServiceAt;
    private Link link;

    Prepare(Context c) { this.c = c.getApplicationContext(); }

    void cancel() { cancelled = true; }

    /** Runs the whole step; returns the pack folder of the ready game. Throws with a user-readable message when it cannot. */
    File run(File dump, String titleId, Progress p) throws IOException {
        try {
            return run0(dump, titleId, p);
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "prepare: upstream reflection", e);
            throw new IOException("This build of the app has no on-device packer the build service knows (" + e.getMessage() + ").");
        } finally {
            unbind();
        }
    }

    private File run0(File dump, String titleId, Progress p) throws IOException, ReflectiveOperationException {
        p.on(0, "Checking " + dump.getName());
        // 1. a job already there for this dump?
        String id = titleId;
        try { id = Upstream.titleIdOf(dump); } catch (ReflectiveOperationException | RuntimeException e) { /* DumpCheck says what is wrong */ }
        if (id == null || id.isEmpty()) id = titleId;
        File jf = Upstream.jobFileOf(Upstream.workOf(dump, id));
        boolean anyBuild = false;
        Object existing = null;
        if (jf.isFile()) {
            try {
                existing = Upstream.jobRead(jf);
                anyBuild = Boolean.TRUE.equals(Upstream.call(existing.getClass(), existing, "tryAnyway", new Class<?>[0]));
            } catch (ReflectiveOperationException | RuntimeException e) {
                existing = null;   // another format or damaged: a new job replaces it
            }
        }
        // 2. the dump
        Object r = Upstream.dumpCheck(dump, existing == null, anyBuild);
        String error = (String) Upstream.get(r, "error");
        if (error != null) {
            boolean other = Boolean.TRUE.equals(Upstream.get(r, "otherBuild"));
            String detail = Upstream.str(Upstream.get(r, "detail"));
            throw new IOException((other ? "Untested game version: " : "Cannot prepare this dump: ") + error + (detail.isEmpty() ? "" : "\n" + detail)
                    + (other ? "\nOpen the game in the Home screen's Add game to try it anyway." : ""));
        }
        String rid = Upstream.str(Upstream.get(r, "titleId"));
        if (!rid.isEmpty()) id = rid;
        File dumpR = (File) Upstream.get(r, "dump");
        long version = ((Number) Upstream.get(r, "version")).longValue();
        Object job = null;
        if (existing != null) {
            String s = Upstream.jobState(existing);
            boolean same = Upstream.jobDump(existing).getPath().equals(dumpR.getPath()) && Upstream.jobVersion(existing) == version;
            if (same && (s.equals("running") || s.equals("paused") || s.equals("failed") || s.equals("pending"))) job = existing;   // resumed where it stopped
            else if (same && s.equals("done") && Upstream.jobPack(existing).isDirectory() && Upstream.packRead(Upstream.jobPack(existing)) != null) {
                // already prepared by an earlier run: the game only needs listing
                p.on(100, "Already prepared");
                Upstream.addGame(c, existing);
                return Upstream.jobPack(existing);
            }
            else if (!s.equals("done") && !s.isEmpty()) throw new IOException("This dump is already being prepared into another pack folder. Resume it from its card on the Home screen, or delete its working files first.");
        }
        // 3. a pack beside the dump that fits (PackCheck): adopted, or only the rest redone
        if (job == null) {
            File packDir = null;
            try { packDir = Upstream.packFor(c, dumpR, id); } catch (ReflectiveOperationException | RuntimeException e) { /* no pack: the whole job */ }
            Object pack = packDir == null ? null : Upstream.packRead(packDir);
            if (pack != null && !Boolean.TRUE.equals(Upstream.get(pack, "simulated")) && id.equals(Upstream.str(Upstream.get(pack, "titleId")))) {
                String blocking = Upstream.packBlocking(c, pack, dumpR);
                String shaders = Upstream.packShaders(packDir, id);
                boolean code = blocking != null, sh = shaders != null;
                job = Upstream.jobAdopt(r, packDir, code, sh);
                Upstream.jobSave(job);
                Upstream.jobRemember(c, Upstream.jobFile(job));
                if (!code && !sh) {
                    p.on(100, "The pack beside the game fits: nothing to prepare");
                    Upstream.addGame(c, job);
                    return packDir;
                }
                Log.i(TAG, "prepare: redoing " + (code ? "code " : "") + (sh ? "shaders " : "") + "of " + packDir + (blocking != null ? " (" + blocking + ")" : "") + (shaders != null ? " (" + shaders + ")" : ""));
            }
        }
        if (job == null) {
            job = Upstream.jobCreate(r, Upstream.appPack(c, id));
            Upstream.jobSave(job);
            Upstream.jobRemember(c, Upstream.jobFile(job));
        }
        File file = Upstream.jobFile(job);
        // 4. the service, as the panel starts it
        bind();
        try {
            c.startForegroundService(Upstream.packStart(c, file));
        } catch (RuntimeException e) {   // ForegroundServiceStartNotAllowedException (Android 12+): the app counts as background now
            Log.w(TAG, "PackService start refused: " + e);
            throw new IOException("Android did not let the packer start in the background. The job is saved: open " + appName() + " and tap Resume on the game's card.");
        }
        p.on(1, STEP_TEXT[0]);
        // 5. progress from job.json (and the service's messages while bound) until the job ends
        long startedAt = SystemClock.elapsedRealtime(), lastChange = startedAt;
        String lastJson = null;
        while (!cancelled) {
            SystemClock.sleep(1000);
            String json = read(file);
            String fs = fromService;
            if (fs != null && fromServiceAt > lastChange) json = fs;
            if (json == null) {
                if (SystemClock.elapsedRealtime() - startedAt > 30000) throw new IOException("The job file went missing: " + file);
                continue;
            }
            if (!json.equals(lastJson)) { lastJson = json; lastChange = SystemClock.elapsedRealtime(); }
            JSONObject o;
            try { o = new JSONObject(json); } catch (org.json.JSONException e) { continue; }
            String state = o.optString("state"), reason = o.optString("reason"), step = o.optString("step");
            int si = Math.max(0, java.util.Arrays.asList(STEPS).indexOf(step));
            JSONObject st = o.optJSONObject("steps") != null ? o.optJSONObject("steps").optJSONObject(STEPS[si]) : null;
            long done = st == null ? 0 : st.optLong("done"), total = st == null ? 0 : st.optLong("total");
            String stage = st == null ? "" : st.optString("stage");
            if (state.equals("done")) {
                p.on(100, "Pack finished");
                Object j = Upstream.jobRead(file);
                Upstream.addGame(c, j);
                return Upstream.jobPack(j);
            }
            if (state.equals("failed")) {
                String err = o.optString("error");
                throw new IOException("Preparing failed at " + STEP_TEXT[si].toLowerCase(Locale.ROOT) + (err.isEmpty() ? "." : ": " + err) + "\nRetry from the game's card on the Home screen.");
            }
            int pct = (int) Math.min(99, Math.round(si * 100.0 / STEPS.length + (total > 0 ? Math.min(1.0, done / (double) total) * 100.0 / STEPS.length : 0)));
            if (state.equals("paused")) {
                if (reason.equals("user") || reason.equals("timeout") || reason.equals("refused") || reason.equals("apk"))
                    throw new IOException("Preparing paused (" + reasonText(reason) + "). Resume from the game's card on the Home screen.");
                p.on(pct, "Paused: " + reasonText(reason) + ". " + stepText(si, done, total, stage));
                lastChange = SystemClock.elapsedRealtime();   // a heat or battery pause is not a stall
                continue;
            }
            p.on(pct, stepText(si, done, total, stage));
            if (SystemClock.elapsedRealtime() - lastChange > STALL_MS && !(link != null && link.alive))
                throw new IOException("The packer stopped reporting progress. Resume from the game's card on the Home screen.");
        }
        throw new IOException("Build cancelled. The job is saved: resume from the game's card on the Home screen.");
    }

    private static String stepText(int si, long done, long total, String stage) {
        if (si == 3) {
            if (stage.equals("library") || stage.equals("done")) return "Writing the shader library";
            if (stage.equals("check")) return "Checking the shader library";
        }
        if (stage.equals("code") && total > 0) return String.format(Locale.getDefault(), "%s (%d %%)", STEP_TEXT[si], Math.min(100, done * 100 / total));
        if (total > 0) return String.format(Locale.getDefault(), "%s: %,d of %,d%s", STEP_TEXT[si], done, total, si == 2 ? " files" : "");
        return STEP_TEXT[si];
    }

    private static String reasonText(String r) {
        switch (r) {
            case "user": return "by you";
            case "battery": return "battery below 20 %: plug in the charger to go on";
            case "thermal": return "the phone is too hot: it goes on when it has cooled";
            case "timeout": return "Android's daily limit for background work";
            case "refused": return "Android did not let it start in the background";
            case "apk": return "the app was updated";
            default: return r.isEmpty() ? "stopped" : r;
        }
    }

    private String appName() {
        try { return c.getString(c.getApplicationInfo().labelRes); } catch (RuntimeException e) { return "the app"; }
    }

    private static String read(File f) {
        try { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); } catch (IOException e) { return null; }
    }

    // ---- The Messenger link to PackService (Ondevice.Panel's protocol: MSG_REGISTER with replyTo → MSG_STATE with "job" JSON and "alive") ----

    private void bind() {
        main.post(() -> {
            if (link != null) return;
            link = new Link();
            try {
                if (!c.bindService(Upstream.packBind(c), link, Context.BIND_AUTO_CREATE)) { Log.w(TAG, "PackService: bind refused"); link = null; }
            } catch (ClassNotFoundException | RuntimeException e) {
                Log.w(TAG, "PackService: bind " + e);
                link = null;
            }
        });
    }

    private void unbind() {
        main.post(() -> {
            if (link == null) return;
            Link l = link;
            link = null;
            if (l.service != null) {
                try { Message m = Message.obtain(null, Upstream.MSG_UNREGISTER); m.replyTo = l.client; l.service.send(m); } catch (RemoteException e) { /* gone */ }
            }
            try { c.unbindService(l); } catch (RuntimeException e) { /* not bound */ }
        });
    }

    private final class Link implements ServiceConnection {
        final Messenger client = new Messenger(new Handler(Looper.getMainLooper(), this::onMessage));
        Messenger service;
        volatile boolean alive;

        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            service = new Messenger(b);
            Message m = Message.obtain(null, Upstream.MSG_REGISTER);
            m.replyTo = client;
            try { service.send(m); } catch (RemoteException e) { service = null; }
        }

        @Override public void onServiceDisconnected(ComponentName n) { service = null; alive = false; }

        boolean onMessage(Message m) {
            if (m.what != Upstream.MSG_STATE) return false;
            Bundle d = m.getData();
            String j = d.getString("job");
            if (j != null) { fromService = j; fromServiceAt = SystemClock.elapsedRealtime(); }
            alive = d.getBoolean("alive");
            return true;
        }
    }
}
