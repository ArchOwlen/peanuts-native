package dev.peanuts.build;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import dev.nxnative.android.BuildConfig;

/**
 * The generic host's per-title data (-PnxGenericHost=true): one libnxhost.so loads a title's profile and prewarm seed at
 * start instead of having them compiled in. android/app/build.gradle's nxGenericProfiles task runs dm/peanuts/profile/gen_runtime_profile.py at build time
 * for every nxGames title, into assets/profiles/&lt;title ID&gt;.profile.bin and .seed (never into libnxhost.so itself: the whole point is one host, no
 * per-title code or data compiled in). This class copies a title's two files out of the APK's assets into app-private storage once per installed APK
 * version (assets can only be read as a stream, not opened as a plain file path, and the host wants a path - DM_PROFILE_PATH/DM_PROFILE_SEED), and returns
 * the env lines GameActivity.start() adds beside DM_ROMFS/DM_SAVE/... A title with no .profile.bin asset (not one of this build's nxGames) throws
 * IOException with a sentence for the player; GameActivity's existing catch (IOException | RuntimeException e) turns that into a clear failure screen,
 * never a crash.
 */
public final class GenericProfile {
    private static final String TAG = Prepare.TAG;

    private GenericProfile() {}

    /** DM_PROFILE_PATH / DM_PROFILE_SEED for this title, or an empty list when this APK was not built with -PnxGenericHost=true. */
    public static List<String> envLines(Context c, String titleId) throws IOException {
        List<String> out = new ArrayList<>();
        if (!BuildConfig.NX_GENERIC_HOST || titleId == null || titleId.isEmpty()) return out;
        File dir = dir(c);
        File profile = ensure(c, dir, titleId + ".profile.bin", true);
        File seed = ensure(c, dir, titleId + ".seed", false);   // optional: a title with no vk_prewarm.bin has no seed asset either (gen_runtime_profile.py writes "" for it)
        out.add("DM_PROFILE_PATH=" + profile.getPath());
        out.add("DM_PROFILE_SEED=" + (seed != null ? seed.getPath() : ""));
        return out;
    }

    /**
     * Same as {@link #envLines(Context, String)}, except that when packDir (the pack actually being launched — GameActivity.start()'s local
     * {@code packDir}, built from the launch intent's "pack" extra) holds its own {@code profile.bin} — dev.peanuts.build.ModBuild writes one next to a
     * modded variant's pack (its own module list, e.g. a mod's added subsdk9: DM_PROFILE_MODULES_CAP has eight slots, the shipped per-title profile
     * asset uses only as many as game.tsv's modules row lists) — that file is used as DM_PROFILE_PATH instead of the title's shipped asset. The seed
     * stays the title's normal one (vk_prewarm.bin only helps pipeline warm-up; a modded build's main is still the same title's own compile, so the
     * same seed is still valid, just possibly a little less complete for whatever a code mod newly exercises).
     * <p>
     * <b>Not yet wired up:</b> GameActivity.java's existing call (one marked PEANUTS hook line, "env.addAll(dev.peanuts.build.GenericProfile.
     * envLines(this, id));") calls the two-argument form above, which never looks at packDir — GameActivity.java is owned by the companion/Mods-UI work
     * on this branch, so switching that one line to this three-argument overload (packDir is already a local variable right above it in start()) is
     * left for that work to pick up, or for a coordinated follow-up commit. Until then this overload exists and is covered by its own tests, but a
     * modded build's extra module can only actually load once that one-line call is updated — see the mods build report for the exact line.
     */
    public static List<String> envLines(Context c, String titleId, File packDir) throws IOException {
        File override = packDir == null ? null : new File(packDir, "profile.bin");
        if (override == null || !override.isFile()) return envLines(c, titleId);
        List<String> out = new ArrayList<>();
        File profile = installOverride(dir(c), override, titleId);
        File seed = ensure(c, dir(c), titleId + ".seed", false);
        out.add("DM_PROFILE_PATH=" + profile.getPath());
        out.add("DM_PROFILE_SEED=" + (seed != null ? seed.getPath() : ""));
        return out;
    }

    private static File dir(Context c) { return new File(c.getFilesDir(), "peanuts/profiles"); }

    /** Copies a modded pack's own profile.bin into app-private storage, same per-process-pid tmp-then-rename as {@link #ensure}; always re-copied (a
     *  modded build's profile.bin can change between builds of the same title without this class having any version marker for it to compare against,
     *  unlike the APK-asset path's own versionCode) */
    private static File installOverride(File dir, File source, String titleId) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File dest = new File(dir, titleId + ".mod.profile.bin");
        File tmp = new File(dir, dest.getName() + "." + android.os.Process.myPid() + ".tmp");
        try (InputStream in = new FileInputStream(source); OutputStream o = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
        if (!tmp.renameTo(dest)) throw new IOException("cannot install " + dest);
        Log.i(TAG, "generic profile override: " + source + " -> " + dest);
        return dest;
    }

    /**
     * Copies assets/profiles/&lt;name&gt; to dir/&lt;name&gt; if missing or from an older APK version (a ".ver" sidecar per file, not per directory: titles
     * are added to an APK one at a time, and an unrelated title's stale copy must not block a new one's). required=false and the asset is missing: null.
     */
    private static File ensure(Context c, File dir, String name, boolean required) throws IOException {
        AssetManager am = c.getAssets();
        String assetPath = "profiles/" + name;
        try (InputStream probe = am.open(assetPath)) { /* present */ } catch (IOException e) {
            if (required) throw new IOException("this build has no generic host profile for " + name.replace(".profile.bin", "") + " (assets/" + assetPath
                    + "; -PnxGenericHost=true only packs it for its own nxGames list)");
            return null;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        // copied on every start: a few KB, and dev builds share one versionCode, so any "already copied" key could keep a stale profile after a reinstall
        File dest = new File(dir, name);
        new File(dir, name + ".ver").delete();   // the key file of an earlier build of this class
        File tmp = new File(dir, name + "." + android.os.Process.myPid() + ".tmp");   // per process: the build service and the game may both copy it at once
        try (InputStream in = am.open(assetPath); OutputStream o = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
        if (!tmp.renameTo(dest)) throw new IOException("cannot install " + dest);
        Log.i(TAG, "generic profile: " + name + " installed (" + dest.length() + " bytes)");
        return dest;
    }
}
