package dev.peanuts.ui;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import dev.nxnative.android.BuildConfig;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Upstream's launcher classes (dev.nxnative.android, package-private) read through reflection, as dev.peanuts.Games does, so no upstream file needs a hook: the APK's
 * profiles (BuildConfig.NX_PROFILES, public), a dump's or .nup's title ID (Pack.titleIdOf), a .nup's packages (Nup.open().packages), SAF URIs → paths (PathUtil),
 * the game list and its readiness (Game.load, Game.ready) and the pack for a game (Folders.packFor). Everything that reads files runs off the main thread.
 */
final class Upstream {
    private static final String P = "dev.nxnative.android.";
    private Upstream() {}

    /** Title ID → name of every game this APK runs (NX_PROFILES: one line per game, title ID then name, tab-separated; Profile.java) */
    static Map<String, String> profiles() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : BuildConfig.NX_PROFILES.split("\n")) {
            String[] c = line.split("\t");
            if (c.length >= 1 && c[0].matches("[0-9A-F]{16}")) out.put(c[0], c.length > 1 && !c[1].isEmpty() ? c[1] : c[0]);
        }
        return out;
    }

    static boolean hasProfile(String titleId) { return titleId != null && profiles().containsKey(titleId.toUpperCase(Locale.ROOT)); }

    /** A dump folder's or .nup's title ID (Pack.titleIdOf), null when it is neither */
    static String titleIdOf(File game) {
        try {
            Method m = Class.forName(P + "Pack").getDeclaredMethod("titleIdOf", File.class);
            m.setAccessible(true);
            Object o = m.invoke(null, game);
            return o == null ? null : o.toString().toUpperCase(Locale.ROOT);
        } catch (Throwable t) { Log.w("peanuts", "titleIdOf " + game + ": " + cause(t)); return null; }
    }

    /** One package of a .nup: kind (0 base, 1 update, 2 add-on), its title ID (16 upper-case hex digits) and version */
    static final class NupPkg {
        final int kind; final String titleId; final long version;
        NupPkg(int kind, String titleId, long version) { this.kind = kind; this.titleId = titleId; this.version = version; }
    }

    /** The packages of a .nup (Nup.open(f).packages), empty when it cannot be read */
    static List<NupPkg> nupPackages(File nup) {
        List<NupPkg> out = new ArrayList<>();
        try {
            Class<?> k = Class.forName(P + "Nup");
            Method open = k.getDeclaredMethod("open", File.class); open.setAccessible(true);
            Object n = open.invoke(null, nup);
            Field pk = k.getDeclaredField("packages"); pk.setAccessible(true);
            Class<?> pkg = Class.forName(P + "Nup$Pkg");
            Field kind = field(pkg, "kind"), title = field(pkg, "titleId"), ver = field(pkg, "version");
            for (Object p : (List<?>) pk.get(n)) out.add(new NupPkg(kind.getInt(p), String.format(Locale.ROOT, "%016X", title.getLong(p)), ver.getLong(p)));
        } catch (Throwable t) { Log.w("peanuts", "nup " + nup + ": " + cause(t)); }
        return out;
    }

    /** A folder picker's tree URI → its /storage path (PathUtil.treeToPath); null off a local volume */
    static String treeToPath(Uri tree) { return path("treeToPath", tree); }
    /** A file picker's document URI → its path (PathUtil.documentToPath); null when the provider gives none */
    static String documentToPath(Uri doc) { return path("documentToPath", doc); }

    private static String path(String method, Uri u) {
        try {
            Method m = Class.forName(P + "PathUtil").getDeclaredMethod(method, Uri.class);
            m.setAccessible(true);
            Object o = m.invoke(null, u);
            return o == null ? null : o.toString();
        } catch (Throwable t) { Log.w("peanuts", method + ": " + cause(t)); return null; }
    }

    /** An entry of upstream's game list (Game): where the game and its pack are, and whether it starts as it is (Game.ready) */
    static final class Entry {
        final String titleId, name, gameDir, packDir; final boolean ready;
        Entry(String titleId, String name, String gameDir, String packDir, boolean ready) { this.titleId = titleId; this.name = name; this.gameDir = gameDir; this.packDir = packDir; this.ready = ready; }
    }

    /** Upstream's game list with each entry's readiness (reads packs: off the main thread) */
    static List<Entry> games(Context c) {
        List<Entry> out = new ArrayList<>();
        try {
            Class<?> game = Class.forName(P + "Game");
            Method load = game.getDeclaredMethod("load", Context.class); load.setAccessible(true);
            Method ready = game.getDeclaredMethod("ready", game); ready.setAccessible(true);
            Field titleId = field(game, "titleId"), name = field(game, "name"), gameDir = field(game, "gameDir"), packDir = field(game, "packDir");
            for (Object g : (List<?>) load.invoke(null, c)) {
                boolean r; try { r = Boolean.TRUE.equals(ready.invoke(null, g)); } catch (Throwable t) { r = false; }
                out.add(new Entry(str(titleId.get(g)).toUpperCase(Locale.ROOT), str(name.get(g)), str(gameDir.get(g)), str(packDir.get(g)), r));
            }
        } catch (Throwable t) { Log.w("peanuts", "game list unreadable: " + cause(t)); }
        return out;
    }

    /** The pack folder upstream would use for a game (Folders.packFor: the picked one, the app's packs/<id>, beside or inside the game, <id>-work/<any>); null without one */
    static File packFor(Context c, File game, String titleId) {
        try {
            Method m = Class.forName(P + "Folders").getDeclaredMethod("packFor", Context.class, File.class, String.class);
            m.setAccessible(true);
            return (File) m.invoke(null, c, game, titleId);
        } catch (Throwable t) { Log.w("peanuts", "packFor " + game + ": " + cause(t)); return null; }
    }

    private static Field field(Class<?> k, String n) throws NoSuchFieldException { Field f = k.getDeclaredField(n); f.setAccessible(true); return f; }
    private static String str(Object o) { return o == null ? "" : o.toString(); }
    private static Throwable cause(Throwable t) { return t.getCause() != null ? t.getCause() : t; }
}
