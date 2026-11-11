package dev.peanuts.ui;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The companion catalog and installer (dev.peanuts.Catalog, dev.peanuts.Installer: package-private, so reached through reflection; HOOKS.md asks for them to become
 * public, after which this shrinks to plain calls) and the catalog's names (catalog.json) kept in files/catalog-names.json for a day, so the library can name a title
 * the probe gave no name for without the network.
 */
final class CompanionPort {
    private static final String CATALOG = "dev.peanuts.Catalog", INSTALLER = "dev.peanuts.Installer";
    private static final long NAMES_TTL = 24L * 3600 * 1000;
    private CompanionPort() {}

    /** One catalog entry (Catalog.Item's fields; raw is the Item itself, handed back to Installer.install) */
    static final class Item {
        final String titleId, packageName, version, gameName; final long size; final Object raw;
        Item(String titleId, String packageName, String version, String gameName, long size, Object raw) { this.titleId = titleId; this.packageName = packageName; this.version = version; this.gameName = gameName; this.size = size; this.raw = raw; }
    }

    /** Catalog.fetch(): every title with a companion, newest first per title (network: off the main thread); empty with an error logged */
    static List<Item> fetch() {
        List<Item> out = new ArrayList<>();
        try {
            Class<?> k = Class.forName(CATALOG);
            Method m = k.getDeclaredMethod("fetch"); m.setAccessible(true);
            Class<?> ik = Class.forName(CATALOG + "$Item");
            for (Object it : (List<?>) m.invoke(null)) {
                out.add(new Item(str(ik, it, "titleId").toUpperCase(Locale.ROOT), str(ik, it, "packageName"), str(ik, it, "version"), str(ik, it, "gameName"), lng(ik, it, "size"), it));
            }
        } catch (Throwable t) { Log.w("peanuts", "catalog: " + cause(t)); }
        return out;
    }

    /** The catalog's entry for a title, or null */
    static Item forTitle(List<Item> items, String titleId) {
        for (Item i : items) if (i.titleId.equalsIgnoreCase(titleId)) return i;
        return null;
    }

    /** Installer.installedVersion(root, package): the installed version, "?" for a package without installed.json, null when not installed */
    static String installedVersion(File root, String packageName) {
        if (root == null) return null;
        try {
            Method m = Class.forName(INSTALLER).getDeclaredMethod("installedVersion", File.class, String.class); m.setAccessible(true);
            Object o = m.invoke(null, root, packageName);
            return o == null ? null : o.toString();
        } catch (Throwable t) { Log.w("peanuts", "installedVersion: " + cause(t)); return null; }
    }

    /** Installer.install(item, root, cacheDir, null): downloads and places the package (off the main thread); throws with the reason */
    static void install(Item item, File root, File cacheDir) throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("cannot create " + root);
        try {
            Class<?> k = Class.forName(INSTALLER);
            Method m = k.getDeclaredMethod("install", Class.forName(CATALOG + "$Item"), File.class, File.class, Class.forName(INSTALLER + "$Progress")); m.setAccessible(true);
            m.invoke(null, item.raw, root, cacheDir, null);
        } catch (ReflectiveOperationException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            if (c instanceof IOException) throw (IOException) c;
            throw new IOException(c.getMessage() == null ? c.toString() : c.getMessage(), c);
        }
    }

    /** Installer.remove(root, package) */
    static void remove(File root, String packageName) {
        try {
            Method m = Class.forName(INSTALLER).getDeclaredMethod("remove", File.class, String.class); m.setAccessible(true);
            m.invoke(null, root, packageName);
        } catch (Throwable t) { Log.w("peanuts", "remove: " + cause(t)); }
    }

    // ---- The catalog's names (catalog.json), cached ----

    private static Map<String, String> names;

    /** Title ID → game name from the catalog's catalog.json: the cache file when fresh, else fetched (network: off the main thread); empty when neither is at hand */
    static synchronized Map<String, String> names(Context c) {
        if (names != null) return names;
        File f = new File(c.getFilesDir(), "catalog-names.json");
        Map<String, String> out = new HashMap<>();
        boolean fresh = f.isFile() && System.currentTimeMillis() - f.lastModified() < NAMES_TTL;
        String text = null;
        if (fresh) text = read(f);
        if (text == null) {
            try {
                Class<?> k = Class.forName(CATALOG);
                String url = (String) k.getDeclaredField("NAMES").get(null);
                Method get = k.getDeclaredMethod("get", String.class); get.setAccessible(true);
                text = (String) get.invoke(null, url);
                new JSONArray(text);   // well-formed before it is kept
                f.getParentFile().mkdirs();
                Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
            } catch (Throwable t) { Log.w("peanuts", "catalog names: " + cause(t)); text = f.isFile() ? read(f) : null; }   // stale beats nothing
        }
        if (text != null) {
            try {
                JSONArray a = new JSONArray(text);
                for (int i = 0; i < a.length(); i++) { JSONObject o = a.getJSONObject(i); out.put(o.optString("titleId").toUpperCase(Locale.ROOT), o.optString("name")); }
            } catch (org.json.JSONException e) { Log.w("peanuts", "catalog names: " + e); }
        }
        names = out;
        return out;
    }

    /** Forgets the in-memory names (Refresh): the file is re-read, or re-fetched when a day old */
    static synchronized void resetNames() { names = null; }

    private static String read(File f) { try { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); } catch (IOException e) { return null; } }
    private static String str(Class<?> k, Object o, String f) throws ReflectiveOperationException { java.lang.reflect.Field x = k.getDeclaredField(f); x.setAccessible(true); Object v = x.get(o); return v == null ? "" : v.toString(); }
    private static long lng(Class<?> k, Object o, String f) throws ReflectiveOperationException { java.lang.reflect.Field x = k.getDeclaredField(f); x.setAccessible(true); return x.getLong(o); }
    private static Throwable cause(Throwable t) { return t.getCause() != null ? t.getCause() : t; }
}
