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

    /** One catalog entry (Catalog.Item's fields; raw is the Item itself, handed back to Installer.install). date/notes/size are the release's; sha256/sha256Url: at
     * most one is set (a verifiable digest, or where to fetch one), both null means the release published no digest anywhere (dev.peanuts.ui.CompanionVersions shows
     * "unverified" for that version until it is installed once, the same word Installer.installedVerified() backs on disk) */
    static final class Item {
        final String titleId, packageName, version, gameName, date, notes, sha256, sha256Url; final long size; final Object raw;
        Item(String titleId, String packageName, String version, String gameName, long size, String date, String notes, String sha256, String sha256Url, Object raw) {
            this.titleId = titleId; this.packageName = packageName; this.version = version; this.gameName = gameName; this.size = size;
            this.date = date; this.notes = notes; this.sha256 = sha256; this.sha256Url = sha256Url; this.raw = raw;
        }
    }

    /** Catalog.fetch(): every title with a companion, newest first per title (network: off the main thread); empty with an error logged */
    static List<Item> fetch() {
        List<Item> out = new ArrayList<>();
        try {
            Class<?> k = Class.forName(CATALOG);
            Method m = k.getDeclaredMethod("fetch"); m.setAccessible(true);
            Class<?> ik = Class.forName(CATALOG + "$Item");
            for (Object it : (List<?>) m.invoke(null)) out.add(item(ik, it));
        } catch (Throwable t) { Log.w("peanuts", "catalog: " + cause(t)); }
        return out;
    }

    /** Catalog.fetchVersions(): every release of every title with a companion, newest first per title (network: off the main thread); empty with an error
     * logged. Kept for callers that don't care why an empty result came back. */
    static Map<String, List<Item>> fetchVersions() { return fetchVersions(new String[1]); }

    /** As above, but errorOut[0] gets the failure's message (offline, GitHub down, a parse error, …) so a caller like CompanionVersions can show "Catalog
     * unavailable: …" instead of the misleading "No companion in the catalog" an empty-but-unexplained map gives. errorOut[0] stays null on success. */
    static Map<String, List<Item>> fetchVersions(String[] errorOut) {
        Map<String, List<Item>> out = new HashMap<>();
        try {
            Class<?> k = Class.forName(CATALOG);
            Method m = k.getDeclaredMethod("fetchVersions"); m.setAccessible(true);
            Class<?> ik = Class.forName(CATALOG + "$Item");
            Map<?, ?> raw = (Map<?, ?>) m.invoke(null);
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                List<Item> versions = new ArrayList<>();
                for (Object it : (List<?>) e.getValue()) versions.add(item(ik, it));
                out.put(e.getKey().toString().toUpperCase(Locale.ROOT), versions);
            }
        } catch (Throwable t) {
            Throwable c = cause(t);
            Log.w("peanuts", "catalog versions: " + c);
            if (errorOut != null && errorOut.length > 0) errorOut[0] = c.getMessage() == null ? c.toString() : c.getMessage();
        }
        return out;
    }

    private static Item item(Class<?> ik, Object it) throws ReflectiveOperationException {
        return new Item(str(ik, it, "titleId").toUpperCase(Locale.ROOT), str(ik, it, "packageName"), str(ik, it, "version"), str(ik, it, "gameName"), lng(ik, it, "size"),
                str(ik, it, "date"), str(ik, it, "notes"), strOrNull(ik, it, "sha256"), strOrNull(ik, it, "sha256Url"), it);
    }

    /** The catalog's entry for a title, or null */
    static Item forTitle(List<Item> items, String titleId) {
        for (Item i : items) if (i.titleId.equalsIgnoreCase(titleId)) return i;
        return null;
    }

    /** Every release of one title, newest first, else empty (network error, or no companion for this title) */
    static List<Item> forTitle(Map<String, List<Item>> versions, String titleId) {
        List<Item> l = versions.get(titleId.toUpperCase(Locale.ROOT));
        return l == null ? new ArrayList<>() : l;
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
    static void install(Item item, File root, File cacheDir) throws IOException { install(item, root, cacheDir, null); }

    interface Progress { void on(String what, long done, long total); }

    /** Installer.install(item, root, cacheDir, progress, null): downloads, verifies (sha256 - refuses outright without one, never "installs as unverified"),
     * checks the package's min_runtime against this app's runtime (refuses above it) and activates it (off the main thread); throws with the reason. */
    static void install(Item item, File root, File cacheDir, Progress progress) throws IOException { install(item, root, cacheDir, progress, null); }

    /** As above, plus knownBuildId (the running game's build id, PN_BUILD_ID, when known): returns a build-id compatibility warning (null: none, or
     * unknown) rather than throwing for it - only min_runtime and the sha256 check are hard refusals. Kept for callers with no Context. */
    static String install(Item item, File root, File cacheDir, Progress progress, String knownBuildId) throws IOException { return install(item, root, cacheDir, progress, knownBuildId, null); }

    /** As above, plus ctx (nullable): refuses with "close the game first" when this app's own game process is running (Installer.gameRunning) - see
     * Installer.install()'s 6-arg overload. CompanionVersions passes its Activity here; it's the one screen that can switch/remove a companion while a
     * game might actually be playing. */
    static String install(Item item, File root, File cacheDir, Progress progress, String knownBuildId, Context ctx) throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("cannot create " + root);
        try {
            Class<?> k = Class.forName(INSTALLER);
            Class<?> pi = Class.forName(INSTALLER + "$Progress");
            Object proxy = progress == null ? null : java.lang.reflect.Proxy.newProxyInstance(k.getClassLoader(), new Class<?>[] {pi}, (p, m0, args) -> {
                if (m0.getName().equals("on") && args != null && args.length == 3) { progress.on(String.valueOf(args[0]), ((Number) args[1]).longValue(), ((Number) args[2]).longValue()); return null; }
                if (m0.getName().equals("toString")) return "CompanionPort.Progress";
                if (m0.getName().equals("hashCode")) return System.identityHashCode(p);
                if (m0.getName().equals("equals")) return p == args[0];
                return null;
            });
            Method m = k.getDeclaredMethod("install", Class.forName(CATALOG + "$Item"), File.class, File.class, pi, String.class, Context.class); m.setAccessible(true);
            Object warning = m.invoke(null, item.raw, root, cacheDir, proxy, knownBuildId, ctx);
            return warning == null ? null : warning.toString();
        } catch (ReflectiveOperationException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            if (c instanceof IOException) throw (IOException) c;
            throw new IOException(c.getMessage() == null ? c.toString() : c.getMessage(), c);
        }
    }

    /** Installer.remove(root, package): never refuses for a running game (no Context) */
    static void remove(File root, String packageName) {
        try {
            Method m = Class.forName(INSTALLER).getDeclaredMethod("remove", File.class, String.class); m.setAccessible(true);
            m.invoke(null, root, packageName);
        } catch (Throwable t) { Log.w("peanuts", "remove: " + cause(t)); }
    }

    /** As above, plus ctx (nullable): refuses ("close the game first") while the game is running - see install()'s overload */
    static void remove(File root, String packageName, Context ctx) throws IOException {
        try {
            Method m = Class.forName(INSTALLER).getDeclaredMethod("remove", File.class, String.class, Context.class); m.setAccessible(true);
            m.invoke(null, root, packageName, ctx);
        } catch (ReflectiveOperationException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            if (c instanceof IOException) throw (IOException) c;
            throw new IOException(c.getMessage() == null ? c.toString() : c.getMessage(), c);
        }
    }

    /** Installer.installedVerified(root, package): true when the installed version was checked against a publisher-provided digest, not just fingerprinted on first install */
    static boolean installedVerified(File root, String packageName) {
        if (root == null) return false;
        try {
            Method m = Class.forName(INSTALLER).getDeclaredMethod("installedVerified", File.class, String.class); m.setAccessible(true);
            return (Boolean) m.invoke(null, root, packageName);
        } catch (Throwable t) { return false; }
    }

    /** Installer.localVersions(root, package): every version already on this device for a package (the active one plus archived ones), newest first */
    static List<String> localVersions(File root, String packageName) {
        List<String> out = new ArrayList<>();
        if (root == null) return out;
        try {
            Method m = Class.forName(INSTALLER).getDeclaredMethod("localVersions", File.class, String.class); m.setAccessible(true);
            for (Object v : (List<?>) m.invoke(null, root, packageName)) out.add(v.toString());
        } catch (Throwable t) { Log.w("peanuts", "localVersions: " + cause(t)); }
        return out;
    }

    /** Installer.activateLocalVersion(root, package, version, null): switches to/rolls back to a version already downloaded (localVersions()), no network;
     * refuses (throws) above this app's min_runtime the same way install() does */
    static void activateLocalVersion(File root, String packageName, String version) throws IOException { activateLocalVersion(root, packageName, version, null); }

    /** As above, plus knownBuildId - see install()'s overload; returns the same kind of advisory warning, null for none/unknown. Kept for callers with no Context. */
    static String activateLocalVersion(File root, String packageName, String version, String knownBuildId) throws IOException { return activateLocalVersion(root, packageName, version, knownBuildId, null); }

    /** As above, plus ctx (nullable): refuses ("close the game first") while the game is running - see install()'s overload */
    static String activateLocalVersion(File root, String packageName, String version, String knownBuildId, Context ctx) throws IOException {
        try {
            Method m = Class.forName(INSTALLER).getDeclaredMethod("activateLocalVersion", File.class, String.class, String.class, String.class, Context.class); m.setAccessible(true);
            Object warning = m.invoke(null, root, packageName, version, knownBuildId, ctx);
            return warning == null ? null : warning.toString();
        } catch (ReflectiveOperationException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            if (c instanceof IOException) throw (IOException) c;
            throw new IOException(c.getMessage() == null ? c.toString() : c.getMessage(), c);
        }
    }

    /** Installer.repair(root, package): completes or rolls back a swap a killed process left half-done. Called before any install/switch already, and also
     * from CompanionVersions.load()/CompanionSection so a half-done swap self-heals as soon as either screen is opened. Kept for callers with no Context
     * (never skips for a running game). */
    static void repair(File root, String packageName) { repair(root, packageName, null); }

    /** As above, plus ctx (nullable): skips entirely while this app's own game process is running - the lock alone does not stop repair() from touching a
     * package the host currently has open from a parked ".old" (peanuts.c's fallback). */
    static void repair(File root, String packageName, Context ctx) {
        if (root == null) return;
        try {
            Method m = Class.forName(INSTALLER).getDeclaredMethod("repair", File.class, String.class, Context.class); m.setAccessible(true);
            m.invoke(null, root, packageName, ctx);
        } catch (Throwable t) { Log.w("peanuts", "repair: " + cause(t)); }
    }

    /** Installer.forgetLocalVersion(root, package, version): deletes one archived (non-active) version's files, freeing storage */
    static void forgetLocalVersion(File root, String packageName, String version) throws IOException {
        try {
            Method m = Class.forName(INSTALLER).getDeclaredMethod("forgetLocalVersion", File.class, String.class, String.class); m.setAccessible(true);
            m.invoke(null, root, packageName, version);
        } catch (ReflectiveOperationException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            if (c instanceof IOException) throw (IOException) c;
            throw new IOException(c.getMessage() == null ? c.toString() : c.getMessage(), c);
        }
    }

    /** Catalog.compareVersions: &gt;0 when a is newer */
    static int compareVersions(String a, String b) {
        try {
            Method m = Class.forName(CATALOG).getDeclaredMethod("compareVersions", String.class, String.class); m.setAccessible(true);
            return (Integer) m.invoke(null, a, b);
        } catch (Throwable t) { return a.compareTo(b); }
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
    private static String strOrNull(Class<?> k, Object o, String f) throws ReflectiveOperationException { java.lang.reflect.Field x = k.getDeclaredField(f); x.setAccessible(true); Object v = x.get(o); return v == null ? null : v.toString(); }
    private static long lng(Class<?> k, Object o, String f) throws ReflectiveOperationException { java.lang.reflect.Field x = k.getDeclaredField(f); x.setAccessible(true); return x.getLong(o); }
    private static Throwable cause(Throwable t) { return t.getCause() != null ? t.getCause() : t; }
}
