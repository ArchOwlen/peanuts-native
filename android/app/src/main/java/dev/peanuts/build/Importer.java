package dev.peanuts.build;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * The .nup importer's Java face (dev.peanuts.importer.Native, agent A's JNI over dm/peanuts/import) reached through reflection, so this package compiles and
 * runs without it: without the class, {@link #available()} is false and an NSP/XCI build says so.
 * <pre>
 *   static int keys(String prodKeys);  static String probe(String file);  static String lastError();
 *   static int writeNup(String out, String[] inputs, String baseTitleId, Native.Progress cb);  interface Progress { void on(String step, long done, long total); }
 * </pre>
 */
final class Importer {
    interface Progress { void on(String step, long done, long total); }

    static final String CLASS = "dev.peanuts.importer.Native";

    private Importer() {}

    static boolean available() {
        try { Class.forName(CLASS); return true; } catch (Throwable t) { return false; }
    }

    static String lastError() {
        try {
            Object s = Class.forName(CLASS).getMethod("lastError").invoke(null);
            return s == null ? "" : s.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 0 ok, else the importer's code (lastError says why) */
    static int keys(File prodKeys) throws ReflectiveOperationException {
        return (Integer) Class.forName(CLASS).getMethod("keys", String.class).invoke(null, prodKeys.getPath());
    }

    static String probe(File f) throws ReflectiveOperationException {
        Object s = Class.forName(CLASS).getMethod("probe", String.class).invoke(null, f.getPath());
        return s == null ? "" : s.toString();
    }

    static int writeNup(File out, List<File> inputs, String baseTitleId, Progress cb) throws ReflectiveOperationException {
        // A direct call: the callback must be a real class implementing Native.Progress (ART's CheckJNI aborts a CallVoidMethod on a java.lang.reflect.Proxy)
        String[] in = new String[inputs.size()];
        for (int i = 0; i < in.length; i++) in[i] = inputs.get(i).getPath();
        return dev.peanuts.importer.Native.writeNup(out.getPath(), in, baseTitleId, (step, done, total) -> cb.on(step, done, total));
    }
}
