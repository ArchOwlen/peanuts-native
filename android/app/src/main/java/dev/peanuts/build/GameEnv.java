package dev.peanuts.build;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import dev.peanuts.PeanutsPrefs;

/**
 * The game's own env lines (upstream's Game settings, Prefs.gameEnv) that the library fills in for the player: the console's shared fonts. A game that decodes the
 * shared font at boot (SDK 20's ScalableFontEngineHelper: The Legend of Zelda: Echoes of Wisdom) crashes without DM_SHARED_FONT, and button glyphs need the extended
 * font (DM_SHARED_FONT_EXT). The files come from Setup's firmware step (PeanutsPrefs.fontsDir: standard.ttf, nintendo_ext.ttf); a line the player set stays.
 */
public final class GameEnv {
    private static final String TAG = Prepare.TAG;

    private GameEnv() {}

    public static void ensureFonts(Context c, String titleId) {
        if (titleId == null || titleId.isEmpty()) return;
        File dir = PeanutsPrefs.fontsDir(c);
        File std = new File(dir, "standard.ttf"), ext = new File(dir, "nintendo_ext.ttf");
        if (!std.isFile()) return;
        try {
            String env = Upstream.gameEnv(c, titleId);
            String out = withDefault(env, "DM_SHARED_FONT", std.getPath());
            if (ext.isFile()) out = withDefault(out, "DM_SHARED_FONT_EXT", ext.getPath());
            if (!out.equals(env == null ? "" : env)) { Upstream.gameEnv(c, titleId, out); Log.i(TAG, "env " + titleId + ": shared fonts set"); }
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "env " + titleId + ": " + e);
        }
    }

    /**
     * PN_BUILD_ID=<main's whole NSO build id, 64 hex digits> for the companion runtime: the host knows a build by its first 8 bytes, a package's native module may list the
     * whole id (Echoes of Wisdom's) and refuses a prefix. From upstream's DumpLayout.find (the launcher's own version check), for the dump as it is now; set anew each time
     */
    public static void ensureBuildId(Context c, String titleId, File dump) {
        if (titleId == null || dump == null) return;
        try {
            Object found = Upstream.call(Upstream.k("DumpLayout"), null, "find", new Class<?>[] {File.class, boolean.class}, dump, true);
            Object ids = Upstream.get(found, "buildIds");
            Object main = ids instanceof java.util.Map ? ((java.util.Map<?, ?>) ids).get("main") : null;
            if (main == null || main.toString().length() < 16) return;
            String env = Upstream.gameEnv(c, titleId);
            String out = RuntimeShaders.withLine(env, "PN_BUILD_ID=" + main.toString().toUpperCase(java.util.Locale.ROOT));
            if (!out.equals(env == null ? "" : env)) Upstream.gameEnv(c, titleId, out);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "env " + titleId + " build id: " + e);
        }
    }

    /** text with KEY=value added when no line sets KEY */
    static String withDefault(String text, String key, String value) {
        List<String> out = new ArrayList<>();
        for (String l : (text == null ? "" : text).split("\n")) {
            if (l.trim().isEmpty()) continue;
            if (l.trim().startsWith(key + "=")) return text;
            out.add(l);
        }
        out.add(key + "=" + value);
        return String.join("\n", out);
    }
}
