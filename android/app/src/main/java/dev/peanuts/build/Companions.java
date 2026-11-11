package dev.peanuts.build;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The companion catalog and installer (dev.peanuts.Catalog / dev.peanuts.Installer, package-private in dev.peanuts) for the build pipeline and the game settings'
 * Companion section, reached through reflection so these packages stay separate from dev.peanuts. Same files on disk as CatalogActivity: &lt;companion root&gt;/&lt;package&gt;/
 * with installed.json.
 */
public final class Companions {
    public interface Progress { void on(String what, long done, long total); }

    /** One catalog entry: the newest release of a title's companion */
    public static final class Item {
        public final String titleId, packageName, version, url, tag, gameName;
        public final long size;
        final Object raw;   // the dev.peanuts.Catalog.Item behind it, for Installer.install

        Item(Object raw) throws ReflectiveOperationException {
            this.raw = raw;
            titleId = str(raw, "titleId"); packageName = str(raw, "packageName"); version = str(raw, "version"); url = str(raw, "url"); tag = str(raw, "tag"); gameName = str(raw, "gameName");
            Object s = field(raw, "size");
            size = s instanceof Number ? ((Number) s).longValue() : 0;
        }
    }

    private static final String CATALOG = "dev.peanuts.Catalog", INSTALLER = "dev.peanuts.Installer";

    private Companions() {}

    /** Network: off the main thread */
    public static List<Item> fetch() throws IOException {
        try {
            List<Item> out = new ArrayList<>();
            for (Object o : (List<?>) call(Class.forName(CATALOG), "fetch", new Class<?>[0])) out.add(new Item(o));
            return out;
        } catch (ReflectiveOperationException e) {
            throw new IOException("catalog: " + e.getMessage(), e);
        }
    }

    public static Item find(List<Item> items, String titleId) {
        for (Item i : items) if (i.titleId.equalsIgnoreCase(titleId)) return i;
        return null;
    }

    /** The installed version of a package in root, or null */
    public static String installedVersion(File root, String packageName) {
        if (root == null) return null;
        try {
            Object v = call(Class.forName(INSTALLER), "installedVersion", new Class<?>[] {File.class, String.class}, root, packageName);
            return v == null ? null : v.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** The package folders in root that hold a companion (package.json), by name */
    public static List<String> installed(File root) {
        List<String> out = new ArrayList<>();
        File[] dirs = root == null ? null : root.listFiles();
        if (dirs == null) return out;
        for (File d : dirs) if (d.isDirectory() && !d.getName().endsWith(".new") && !d.getName().endsWith(".old") && new File(d, "package.json").isFile()) out.add(d.getName());
        java.util.Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** Catalog.compareVersions: &gt;0 when a is newer */
    public static int compareVersions(String a, String b) {
        try {
            return (Integer) call(Class.forName(CATALOG), "compareVersions", new Class<?>[] {String.class, String.class}, a, b);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return a.compareTo(b);
        }
    }

    public static void install(Item item, File root, File cacheDir, Progress progress) throws IOException {
        try {
            Class<?> inst = Class.forName(INSTALLER);
            Class<?> pi = Class.forName(INSTALLER + "$Progress");
            Object proxy = Proxy.newProxyInstance(inst.getClassLoader(), new Class<?>[] {pi}, (p, m, args) -> {
                if (m.getName().equals("on") && args != null && args.length == 3) { if (progress != null) progress.on(String.valueOf(args[0]), ((Number) args[1]).longValue(), ((Number) args[2]).longValue()); return null; }
                if (m.getName().equals("toString")) return "Companions.Progress";
                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                if (m.getName().equals("equals")) return p == args[0];
                return null;
            });
            call(inst, "install", new Class<?>[] {Class.forName(CATALOG + "$Item"), File.class, File.class, pi}, item.raw, root, cacheDir, proxy);
        } catch (ReflectiveOperationException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException) throw (IOException) c;
            throw new IOException("install: " + (c != null ? c : e), e);
        }
    }

    public static void remove(File root, String packageName) throws IOException {
        try {
            call(Class.forName(INSTALLER), "remove", new Class<?>[] {File.class, String.class}, root, packageName);
        } catch (ReflectiveOperationException e) {
            throw new IOException("remove: " + e.getMessage(), e);
        }
    }

    /** "Name 1.2.0" for a line of text */
    public static String label(String packageName, String version) {
        return version == null ? packageName : String.format(Locale.ROOT, "%s %s", packageName, version);
    }

    private static Object call(Class<?> k, String name, Class<?>[] types, Object... args) throws ReflectiveOperationException {
        Method m = k.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException) throw (RuntimeException) c;
            throw new ReflectiveOperationException(name + ": " + c, c);
        }
    }

    private static Object field(Object o, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static String str(Object o, String name) throws ReflectiveOperationException {
        Object v = field(o, name);
        return v == null ? "" : v.toString();
    }
}
