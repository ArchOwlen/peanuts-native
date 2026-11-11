# libnximport — upstream hooks (one line each; agent A writes none of them)

## android/app/src/main/cpp/CMakeLists.txt

After the `add_subdirectory(... host ...)` line (outside the `foreach(g ${games})` loop: the importer is one library, not per game):

```cmake
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../../dm/peanuts/import ${CMAKE_CURRENT_BINARY_DIR}/nximport)   # PEANUTS: the importer (libnximport.so, dev.peanuts.importer.Native)
```

That is all the native side needs: the target is `nximport` → `libnximport.so` (arm64, 16 KB page aligned, links only `log`), picked up by the
APK like the other shared libraries in the module.

## Java side (agent B/C own the Java; this is the class the JNI binds to)

```java
package dev.peanuts.importer;
public final class Native {
    static { System.loadLibrary("nximport"); }
    public interface Progress { void on(String step, long done, long total); }
    public static native int    keys(String prodKeysPath);
    public static native String probe(String file);                                  // JSON array: [{"title_id","app_id","kind","version","name","publisher"}], null on failure
    public static native int    icon(String file, String titleId, String outPng);     // titleId null = the first title with an icon
    public static native int    writeNup(String out, String[] inputs, String baseTitleId, Progress cb);
    public static native int    firmwareFonts(String firmwareDir, String outDir);     // writes <outDir>/standard.ttf and nintendo_ext.ttf
    public static native String lastError();
}
```

`writeNup` blocks for the whole write (minutes for a 6 GB game): call it from a worker thread (the foreground service of the build pipeline);
`Progress.on` is called on that same thread once per MiB with `step` one of `probe`, `base/exefs`, `base/romfs`, `update/<version>/exefs|romfs`,
`addon/<title id>/romfs`, `toc`, and `done`/`total` in bytes of the whole job (`total` is 0 during `probe`). A failed write removes the partial file.

## Keys

`keys()` reads the user's `prod.keys` (header_key, key_area_key_application/ocean/system_NN, titlekek_NN) and, when a `title.keys` sits beside
it, its title keys; tickets inside an NSP are used first. Nothing is shipped in the APK.
