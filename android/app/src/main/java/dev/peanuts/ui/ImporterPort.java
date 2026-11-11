package dev.peanuts.ui;

import android.util.Log;

import java.lang.reflect.Method;

/**
 * The library's face of the importer (agent A's dev.peanuts.importer.Native: nximport.h through JNI). Reached through reflection, so this package compiles and runs
 * before the importer lands: without the class every call answers "not available" and the library shows NSP/XCI files by their file names, without icons.
 * <p>
 * Native's static methods, as DESIGN-library.md lists them: int keys(String prodKeysPath); String probe(String file) → a JSON array of {title_id, app_id, kind
 * (0 base, 1 update, 2 dlc), version, name, publisher}; int icon(String file, String titleId, String outPng); int firmwareFonts(String dir, String outDir);
 * String lastError(). 0 is success for the int ones.
 */
public interface ImporterPort {
    String NATIVE_CLASS = "dev.peanuts.importer.Native";

    boolean available();
    int keys(String prodKeysPath);
    String probe(String file);
    int icon(String file, String titleId, String outPng);
    int firmwareFonts(String firmwareDir, String outDir);
    String lastError();

    /** The importer when its class is in the APK and loads, else the stub */
    static ImporterPort get() { return Holder.PORT; }

    /** Loads the user's prod.keys into the importer once per process (probe/icon/writeNup need them); false when there is no keys file or it does not load */
    static boolean ensureKeys(android.content.Context c) {
        if (Holder.KEYS_OK) return true;
        java.io.File k = dev.peanuts.PeanutsPrefs.keysFile(c);
        if (!k.isFile() || !get().available()) return false;
        if (get().keys(k.getPath()) == 0) { Holder.KEYS_OK = true; return true; }
        android.util.Log.w("peanuts", "keys: " + get().lastError());
        return false;
    }

    final class Holder {
        static final ImporterPort PORT = make();
        static volatile boolean KEYS_OK;
        private Holder() {}
        private static ImporterPort make() {
            try {
                Class<?> k = Class.forName(NATIVE_CLASS);
                return new Reflective(k);
            } catch (Throwable t) {   // ClassNotFound, or UnsatisfiedLink from a static initializer: the library is not in this build
                Log.i("peanuts", "importer not available: " + t);
                return new Stub();
            }
        }
    }

    /** No importer in this build */
    final class Stub implements ImporterPort {
        @Override public boolean available() { return false; }
        @Override public int keys(String p) { return -1; }
        @Override public String probe(String f) { return null; }
        @Override public int icon(String f, String t, String o) { return -1; }
        @Override public int firmwareFonts(String d, String o) { return -1; }
        @Override public String lastError() { return "importer not available in this build"; }
    }

    /** dev.peanuts.importer.Native through reflection */
    final class Reflective implements ImporterPort {
        private final Method keys, probe, icon, fonts, lastError;
        private volatile String err = "";

        Reflective(Class<?> k) throws NoSuchMethodException {
            keys = k.getMethod("keys", String.class);
            probe = k.getMethod("probe", String.class);
            icon = k.getMethod("icon", String.class, String.class, String.class);
            fonts = k.getMethod("firmwareFonts", String.class, String.class);
            lastError = k.getMethod("lastError");
        }

        @Override public boolean available() { return true; }
        @Override public int keys(String p) { return callInt(keys, p); }
        @Override public String probe(String f) { Object o = call(probe, f); return o == null ? null : o.toString(); }
        @Override public int icon(String f, String t, String o) { return callInt(icon, f, t, o); }
        @Override public int firmwareFonts(String d, String o) { return callInt(fonts, d, o); }
        @Override public String lastError() {
            if (!err.isEmpty()) return err;
            Object o = call(lastError);
            return o == null ? "" : o.toString();
        }

        private int callInt(Method m, Object... a) { Object o = call(m, a); return o instanceof Integer ? (Integer) o : -1; }
        private Object call(Method m, Object... a) {
            try { err = ""; return m.invoke(null, a); }
            catch (Throwable t) { Throwable c = t.getCause() != null ? t.getCause() : t; err = c.toString(); Log.w("peanuts", "importer " + m.getName() + ": " + c); return null; }
        }
    }
}
