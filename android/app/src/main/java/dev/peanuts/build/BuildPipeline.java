package dev.peanuts.build;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The library's Build button (dev.peanuts.ui.BuildPort → here): {@link #start} hands a {@link BuildRequest} to {@link BuildService} (a foreground service in this
 * process) and keeps the sheet's {@link BuildListener} for it; the service reports through {@link #progress}/{@link #finish}, delivered on the main thread. The
 * listener is held weakly in effect: {@link #detach} drops it when the sheet goes away (the notification still shows the build), {@link #attach} binds a new one
 * to a running build (the sheet reopened). One build runs at a time.
 */
public final class BuildPipeline {
    private static final String TAG = Prepare.TAG;
    private static final AtomicInteger NEXT = new AtomicInteger(1);
    private static final Map<Integer, BuildListener> LISTENERS = new ConcurrentHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile int running;
    private static volatile String lastStep = "", lastDetail = "";
    private static volatile int lastPercent;

    private BuildPipeline() {}

    /** Starts the build; returns its id (for {@link #attach}/{@link #cancel}), or 0 when the request is not buildable (the listener is told why) */
    public static int start(Context ctx, BuildRequest req, BuildListener l) {
        String why = check(req);
        if (why != null) { if (l != null) MAIN.post(() -> l.done(false, why)); return 0; }
        int id = NEXT.getAndIncrement();
        if (l != null) LISTENERS.put(id, l);
        running = id;
        lastStep = "import"; lastPercent = 0; lastDetail = "Starting";
        try {
            ctx.getApplicationContext().startForegroundService(BuildService.intent(ctx, id, req));
        } catch (RuntimeException e) {   // not in the manifest, or Android refused a foreground service from the background
            Log.w(TAG, "BuildService start: " + e);
            LISTENERS.remove(id);
            running = 0;
            if (l != null) MAIN.post(() -> l.done(false, "Cannot start the build: " + e.getMessage()));
            return 0;
        }
        return id;
    }

    /** Whether a build is running now (its id), else 0 */
    public static int running() { return running; }

    /** A sheet reopened while a build runs: it gets the latest progress at once and the rest as it comes */
    public static void attach(int id, BuildListener l) {
        if (l == null) return;
        LISTENERS.put(id, l);
        if (running == id) { String s = lastStep, d = lastDetail; int p = lastPercent; MAIN.post(() -> l.progress(s, p, d)); }
    }

    public static void detach(int id) { LISTENERS.remove(id); }

    public static void cancel(Context ctx) {
        try { ctx.startService(new android.content.Intent(ctx, BuildService.class).setAction(BuildService.ACTION_CANCEL)); } catch (RuntimeException e) { Log.w(TAG, "cancel: " + e); }
    }

    private static String check(BuildRequest r) {
        if (r == null) return "Nothing to build.";
        if (r.titleId == null || !r.titleId.matches("[0-9A-Fa-f]{16}")) return "The game has no title ID.";
        if (r.base == null) return "Pick the game's base file first.";
        if (!r.base.exists()) return "The base file is missing: " + r.base;
        if (r.needsImport() && r.gameDir == null) return "No game folder (Setup).";
        return null;
    }

    static void progress(int id, String step, int percent, String detail) {
        if (id == running) { lastStep = step; lastPercent = percent; lastDetail = detail; }
        BuildListener l = LISTENERS.get(id);
        if (l != null) MAIN.post(() -> l.progress(step, percent, detail));
    }

    static void finish(int id, boolean ok, String message) {
        if (id == running) running = 0;
        BuildListener l = LISTENERS.remove(id);
        if (l != null) MAIN.post(() -> l.done(ok, message));
    }
}
