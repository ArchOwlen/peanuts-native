package dev.peanuts.build;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Shaders a game compiles at run time with its own GLSLC (Fire Emblem: Three Houses: nine built-in 2D/text shaders at boot; Turrican) are in no game file, so a
 * pack made from the files alone lacks them: the job finishes (Try anyway) with "shaders_missing" [n, total] and the host skips their draws (black panels, no
 * text). The Mac's NSP script boots the game once with DM_GLSLC_CAPTURE to collect them (dm/host/hle_glslc.c, dm/games/README.md); this does the same on the
 * phone, inside Build, after Prepare:
 * <ol>
 * <li>the game gets DM_GLSLC_CAPTURE=&lt;work&gt;/glslc_capture in its own env lines (Prefs.gameEnv) and is started as Play does (MainActivity --es game --ez run);</li>
 * <li>its boot is watched until the folder holds the missing count of .glslc files (or {@link #CAPTURE_MS} pass), then the ":game" process is ended and the env line removed;</li>
 * <li>&lt;work&gt;/ondevice/env.txt gets the same line for the packer's tools (PackService reads it; shader_split.py reads DM_GLSLC_CAPTURE), the job's split, shaders
 *     and pack steps are set back to pending, and Prepare resumes it: the split finds the captured outputs, the shaders step keeps the library's translated shaders
 *     (DMSH_VK_DELTA_FROM) and translates the new ones, the pack is written again.</li>
 * </ol>
 * The whole step is skipped when nothing is missing, or when the capture folder already holds enough files (a second Build only re-runs the job).
 */
final class RuntimeShaders {
    private static final String TAG = Prepare.TAG;
    private static final String MAIN = "dev.nxnative.android.MainActivity";
    private static final String ENV = "DM_GLSLC_CAPTURE";
    /** How long the game may run for its boot-time compiles (they come within seconds of the first frame) */
    private static final long CAPTURE_MS = 150_000;
    private static final long START_MS = 30_000;

    private final Context c;

    RuntimeShaders(Context c) { this.c = c.getApplicationContext(); }

    /** The missing count from the job file, 0 when the job lacks the line or nothing is missing */
    static int missing(File jobFile) {
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(jobFile.toPath()), StandardCharsets.UTF_8));
            JSONArray a = o.optJSONArray("shaders_missing");
            return a == null || a.length() < 1 ? 0 : a.optInt(0);
        } catch (IOException | JSONException e) {
            return 0;
        }
    }

    static File captureDir(File work) { return new File(work, "glslc_capture"); }

    static int captured(File dir) {
        String[] l = dir.list((d, n) -> n.endsWith(".glslc"));
        return l == null ? 0 : l.length;
    }

    /**
     * Captures the game's run-time shaders when the capture folder holds fewer than {@code need}; returns true when the folder has enough afterwards.
     * {@code game} and {@code pack} are what Play hands MainActivity; {@code cancelled} ends the wait early.
     */
    boolean capture(String titleId, File work, File game, File pack, int need, Prepare.Progress p, java.util.function.BooleanSupplier cancelled) throws IOException {
        File dir = captureDir(work);
        if (captured(dir) >= need) return true;
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot make " + dir);
        String line = ENV + "=" + dir.getPath();
        String before;
        try {
            before = Upstream.gameEnv(c, titleId);
            Upstream.gameEnv(c, titleId, withLine(before, line));
        } catch (ReflectiveOperationException e) {
            throw new IOException("This build of the app has no game settings the build service knows (" + e.getMessage() + ").");
        }
        try {
            p.on(0, "Starting the game once to capture its run-time shaders");
            Intent i = new Intent().setClassName(c.getPackageName(), MAIN).putExtra("game", game.getPath()).putExtra("run", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (pack != null) i.putExtra("pack", pack.getPath());
            try {
                c.startActivity(i);
            } catch (RuntimeException e) {   // Android lets a service start an activity only while the app is in front
                throw new IOException("Android did not let the build start the game. Play " + game.getName() + " once from the library, then tap Build again.");
            }
            long t0 = SystemClock.elapsedRealtime();
            boolean seen = false;
            while (!cancelled.getAsBoolean()) {
                SystemClock.sleep(1000);
                int have = captured(dir);
                boolean alive = gameAlive();
                seen |= alive;
                if (have >= need) { p.on(100, "Captured " + have + " run-time shaders"); break; }
                long t = SystemClock.elapsedRealtime() - t0;
                if (seen && !alive) { Log.w(TAG, "capture: the game ended with " + have + " of " + need); break; }
                if (!seen && t > START_MS) throw new IOException("The game did not start for the shader capture. Play " + game.getName() + " once from the library, then tap Build again.");
                if (t > CAPTURE_MS) { Log.w(TAG, "capture: " + have + " of " + need + " after " + t / 1000 + " s"); break; }
                p.on((int) Math.min(95, 5 + have * 90L / Math.max(1, need)), "Capturing the game's run-time shaders: " + have + " of " + need);
            }
            endGame();
        } finally {
            try { Upstream.gameEnv(c, titleId, withoutLine(Upstream.gameEnv(c, titleId), ENV)); } catch (ReflectiveOperationException e) { Log.w(TAG, "capture: env restore " + e); }
        }
        return captured(dir) >= need;
    }

    /** Points the packer's tools at the capture folder and sets the job back to its split step, so Prepare resumes it from there */
    static void rerun(File work, File jobFile) throws IOException {
        File od = jobFile.getParentFile();
        File env = new File(od, "env.txt");
        String text = env.isFile() ? new String(Files.readAllBytes(env.toPath()), StandardCharsets.UTF_8) : "";
        Files.write(env.toPath(), withLine(text, ENV + "=" + captureDir(work).getPath()).getBytes(StandardCharsets.UTF_8));
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(jobFile.toPath()), StandardCharsets.UTF_8));
            JSONObject steps = o.optJSONObject("steps");
            if (steps == null) throw new IOException("The job file has no steps: " + jobFile);
            for (String s : new String[] {"split", "shaders", "pack"}) {
                JSONObject st = steps.optJSONObject(s);
                if (st == null) { st = new JSONObject(); steps.put(s, st); }
                st.put("state", "pending").put("done", 0).put("total", 0).put("stage", "");
            }
            o.put("state", "pending").put("step", "split").put("reason", "").put("error", "");
            o.remove("shaders_missing");
            o.remove("shader_cache");
            Files.write(jobFile.toPath(), o.toString(1).getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("The job file cannot be reset: " + e.getMessage());
        }
    }

    private boolean gameAlive() {
        ActivityManager am = c.getSystemService(ActivityManager.class);
        List<ActivityManager.RunningAppProcessInfo> l = am == null ? null : am.getRunningAppProcesses();
        if (l != null) for (ActivityManager.RunningAppProcessInfo p : l) if (p.processName != null && p.processName.endsWith(":game")) return true;
        return false;
    }

    /** Ends the ":game" process (the game and its activity: GameActivity runs in it), as the system does when a game is swiped away */
    private void endGame() {
        // The run's exit record (ExitWatch: <files>/last-exit.txt, "state=running" while the game runs) gets the player's own "stopped" line first, so the launcher
        // does not tell the player that the game ended unexpectedly: this end is the build's
        File rec = new File(c.getFilesDir(), "last-exit.txt");
        if (rec.isFile()) {
            try { Files.write(rec.toPath(), "state=stopped\n".getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND); } catch (IOException e) { Log.w(TAG, "capture: exit record " + e); }
        }
        ActivityManager am = c.getSystemService(ActivityManager.class);
        List<ActivityManager.RunningAppProcessInfo> l = am == null ? null : am.getRunningAppProcesses();
        if (l != null) for (ActivityManager.RunningAppProcessInfo p : l) if (p.processName != null && p.processName.endsWith(":game")) android.os.Process.killProcess(p.pid);
        SystemClock.sleep(1500);
    }

    static String withLine(String text, String line) {
        String key = line.substring(0, line.indexOf('=') + 1);
        List<String> out = new ArrayList<>();
        for (String l : (text == null ? "" : text).split("\n")) if (!l.trim().isEmpty() && !l.trim().startsWith(key)) out.add(l);
        out.add(line);
        return String.join("\n", out);
    }

    static String withoutLine(String text, String key) {
        List<String> out = new ArrayList<>();
        for (String l : (text == null ? "" : text).split("\n")) if (!l.trim().isEmpty() && !l.trim().startsWith(key + "=")) out.add(l);
        return String.join("\n", out);
    }
}
