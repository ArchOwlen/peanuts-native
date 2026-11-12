# libnximport — upstream hooks (one line each; agent A writes none of them)

## android/app/src/main/cpp/CMakeLists.txt

After the `add_subdirectory(... host ...)` line (outside the `foreach(g ${games})` loop: the importer is one library, not per game):

```cmake
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../../dm/peanuts/import ${CMAKE_CURRENT_BINARY_DIR}/nximport)   # PEANUTS: the importer (libnximport.so, dev.peanuts.importer.Native)
```

That is all the native side needs: the target is `nximport` → `libnximport.so` (arm64, 16 KB page aligned, links only `log`), picked up by the
APK like the other shared libraries in the module. The source list lives in this folder's CMakeLists.txt (`NXIMPORT_SOURCES`): a new file there
(nx_lz4.c, the compressed sections' LZ4 block decoder, 2026-11-12) needs no change upstream.

## Compressed NCA sections (nx_nca.c, nx_lz4.c)

Newer titles (Echoes of Wisdom) compress their RomFS: the fs header's CompressionInfo @0x178 (table offset, size, a BucketTree header) names a table
of entries {virtual offset, physical offset, type 0 none / 1 zeros / 3 LZ4, physical size} over the IVFC data level, the layout of Atmosphère's
CompressedStorage as vendored by Eden (`src/core/file_sys/fssystem/fssystem_compressed_storage.h`, `fssystem_nca_file_system_driver.cpp`:
the compression layer sits above the hash layer; a patch's base is read raw, below the patch's own table). `nx_sec_read` decompresses
transparently (the hash layers before `data_off` stay raw, `nx_sec_read_raw` reads everything raw); the trees and the .nup writer need nothing.
Tests: `comp_test` (host build) dumps LZ4 blocks + the importer's bytes, `tests/comp_check.py` compares them with python's `lz4.block`;
`nximport_cli ivfc` checks a data level against its IVFC hashes (raw, so it holds for compressed sections too).

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
    public static native void   setThreads(int n);                                    // optional (2026-11-12): writeNup's reader threads, 0 = automatic; see "Speed"
}
```

`writeNup` blocks for the whole write (minutes for a 6 GB game): call it from a worker thread (the foreground service of the build pipeline);
`Progress.on` is called on that same thread once per MiB with `step` one of `probe`, `base/exefs`, `base/romfs`, `update/<version>/exefs|romfs`,
`addon/<title id>/romfs`, `toc`, and `done`/`total` in bytes of the whole job (`total` is 0 during `probe`). A failed write removes the partial file.

## Speed (2026-11-12)

`writeNup` is a pipeline: `n` reader threads decrypt + decompress consecutive chunks (4 MiB, cut at LZ4 entry ends) in parallel, one thread hashes in
order, the calling thread writes in order and keeps every decision (extent places, equal-file sharing, the BKTR shortcut) in file order — the .nup is
byte-identical whatever `n`, and at most ~256 MiB of chunks is in flight. SHA-256 uses the CPU's SHA instructions (ARMv8 SHA2 / x86 SHA-NI; the hash
self-tests against FIPS vectors at start and falls back to the portable one). `n` defaults to the online CPUs capped at 6, or `NXIMPORT_THREADS` from
the environment; `Native.setThreads(int)` (JNI `Java_dev_peanuts_importer_Native_setThreads`, declare it in the Java class to use it — writeNup's
signature is unchanged) overrides both; `n <= 0` = automatic. `Progress.on` is still called on the calling thread, once per MiB.

## Keys

`keys()` reads the user's `prod.keys` (header_key, key_area_key_application/ocean/system_NN, titlekek_NN) and, when a `title.keys` sits beside
it, its title keys; tickets inside an NSP are used first. Nothing is shipped in the APK.
