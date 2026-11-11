package dev.peanuts;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

/** The host's Peanuts entry points (dm/peanuts/peanuts_jni.c, in the game's libnxlaunch, loaded by Native.load before the game starts). */
public final class Bridge {
    private Bridge() {}

    /** The newest snapshot as "key=value" lines (peanuts.h); empty before the host runs or without Peanuts in it. */
    public static native String snapshot();
    /** The companion page's size as w << 16 | h, 0 while there is no page. */
    public static native int frameSize();
    /** The newest page frame (RGBA8) into a direct buffer of at least w*h*4 bytes: true when a new one was written. */
    public static native boolean frame(ByteBuffer out, int w, int h);
    /** A touch on the page, in page pixels. */
    public static native void touch(int id, float x, float y, boolean down);

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

    public static int pageWidth() { try { return frameSize() >>> 16; } catch (UnsatisfiedLinkError e) { return 0; } }
    public static int pageHeight() { try { return frameSize() & 0xffff; } catch (UnsatisfiedLinkError e) { return 0; } }
}
