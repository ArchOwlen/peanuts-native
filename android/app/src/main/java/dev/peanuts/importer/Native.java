package dev.peanuts.importer;

/** The importer (dm/peanuts/import, libnximport.so): NSP/XCI → .nup with the user's own keys, title probing, icons, shared fonts from a firmware folder. */
public final class Native {
    static { System.loadLibrary("nximport"); }
    private Native() {}
    public interface Progress { void on(String step, long done, long total); }
    public static native int    keys(String prodKeysPath);
    public static native String probe(String file);                                  // JSON array: [{"title_id","app_id","kind","version","name","publisher"}], null on failure
    public static native int    icon(String file, String titleId, String outPng);     // titleId null = the first title with an icon
    public static native int    writeNup(String out, String[] inputs, String baseTitleId, Progress cb);
    public static native int    firmwareFonts(String firmwareDir, String outDir);     // writes <outDir>/standard.ttf and nintendo_ext.ttf
    public static native String lastError();
}
