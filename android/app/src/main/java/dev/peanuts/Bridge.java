package dev.peanuts;

import java.util.LinkedHashMap;
import java.util.Map;

/** The host's Peanuts entry points (dm/peanuts/peanuts_jni.c, in the game's libnxlaunch, loaded by Native.load before the game starts). */
public final class Bridge {
    private Bridge() {}

    /** The newest snapshot as "key=value" lines (peanuts.h); empty before the host runs or without Peanuts in it. */
    public static native String snapshot();

    public static Map<String, String> values() {
        Map<String, String> m = new LinkedHashMap<>();
        String s;
        try { s = snapshot(); } catch (UnsatisfiedLinkError e) { return m; }
        for (String line : s.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) m.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return m;
    }
}
