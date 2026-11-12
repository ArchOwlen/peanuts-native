package dev.peanuts.build;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import dev.nxnative.android.BuildConfig;

/**
 * The generic host's per-title data (-PnxGenericHost=true, design/platform/IMPL-GENERIC.md): one libnxhost.so loads a title's profile and prewarm seed at
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

    private static File dir(Context c) { return new File(c.getFilesDir(), "peanuts/profiles"); }

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
