package dev.peanuts.mods;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The layout normalizer (DESIGN-MODS.md "a Mods section per game", this task's step 1): given a plain folder on disk that already holds a copy of
 * whatever the player picked (a folder tree, or an extracted zip — the Android-side copy/extract into that staging folder is {@code ModImportUi}'s job,
 * not this class's), works out which of the three common mod layouts it is, and moves the right pieces into
 * {@code <modsRoot>/<modName>/} (the game's own mods folder) with a fresh {@code mod.json}. Plain Java (no {@code android.*}) so
 * {@code peanuts/tests/test_mod_importer.py} can drive it directly with javac/java on a real temp directory, the same way
 * {@code dev.peanuts.build.RuntimeShaderMap} is tested.
 * <p>
 * Layouts recognized, in this order:
 * <ol>
 * <li><b>Atmosphère</b>: {@code atmosphere/contents/<TITLEID>/{romfs,exefs}} and {@code atmosphere/exefs_patches/<name>/*.ips}. The title ID folder
 *     under {@code contents} must match the game this import is for (case-insensitive 16 hex digits) — a mismatch is rejected with the title ID it
 *     actually found, never silently imported under the wrong game.</li>
 * <li><b>Yuzu/Eden "load" layout</b>: the staging root holds exactly one subfolder (ignoring {@code atmosphere} and dotfiles/{@code __MACOSX}) that
 *     itself has {@code romfs/} and/or {@code exefs/} directly inside it — that subfolder's name is the mod's name, and its contents are the mod.</li>
 * <li><b>Bare</b>: the staging root itself has {@code romfs/} and/or {@code exefs/} directly inside it (no wrapper folder at all).</li>
 * </ol>
 * A {@code cheats/} folder is carried over from whichever level the romfs/exefs pair was found at (the Atmosphère case also accepts
 * {@code atmosphere/contents/<TITLEID>/cheats/}). Nothing is recognized is an error naming what was looked for, not a silent empty mod.
 */
public final class ModImporter {
    private ModImporter() {}

    public static final class Result {
        public final boolean ok;
        public final String error;     // null when ok
        public final String modName;   // the folder name actually used under <modsRoot>/<titleId>/, null when !ok
        public final String kind;      // ModStore.KIND_FILES / KIND_CODE, null when !ok
        private Result(boolean ok, String error, String modName, String kind) { this.ok = ok; this.error = error; this.modName = modName; this.kind = kind; }
        static Result fail(String msg) { return new Result(false, msg, null, null); }
        static Result ok(String name, String kind) { return new Result(true, null, name, kind); }
    }

    /** Reports copy progress for the (possibly large, GB-scale) romfs tree; {@code totalBytes} may be 0 when it could not be estimated cheaply */
    public interface Progress { void onProgress(long bytesDone, long totalBytes, String currentFile); }

    private static final Progress NONE = (d, t, f) -> {};

    /**
     * @param staging    a real folder already holding the picked content (a copied folder tree, or an extracted zip's contents)
     * @param modsRoot   {@code <app files>/mods}
     * @param titleId    the game's 16-hex-digit title ID this import is for
     * @param displayName the player's chosen name for the mod (from the picked folder/zip's own name, or a prompt); the actual folder name may differ
     *                   if it collides ({@link ModStore#freeName})
     */
    public static Result importFrom(File staging, File modsRoot, String titleId, String displayName, Progress progress) {
        return importFrom(staging, modsRoot, titleId, displayName, null, progress);
    }

    /**
     * Same, for a built game: gameBuilds is its pack's module → build ID rows ({@code dev.peanuts.build.ModdedBuild.buildIds}); a mod whose IPS patches
     * are all named for other builds is refused ({@link #buildIdProblem}). null or empty: nothing to compare with, no check. With or without them, a mod
     * whose exefs/main.npdm is another game's is refused ({@link #npdmTitleId}).
     */
    public static Result importFrom(File staging, File modsRoot, String titleId, String displayName, Map<String, String> gameBuilds, Progress progress) {
        if (progress == null) progress = NONE;
        File dest = null;   // the mod's folder once it is being written: taken away again when the install does not end well
        try {
            Layout layout = detect(staging, titleId);
            if (layout == null) return Result.fail("No romfs, exefs or cheats folder found. Point to the mod's own folder (with romfs/exefs inside), "
                    + "an Atmosphère-layout folder (atmosphere/contents/<title ID>/...), or its zip.");
            if (layout.mismatchedTitleId != null) return Result.fail("This mod is for title ID " + layout.mismatchedTitleId + ", not this game ("
                    + titleId.toUpperCase(Locale.ROOT) + "). Not imported.");
            String npdmId = layout.exefs == null ? null : npdmTitleId(new File(layout.exefs, "main.npdm"));
            if (npdmId != null && !npdmId.equalsIgnoreCase(titleId)) return Result.fail("This mod is for another game: its exefs/main.npdm is for title ID "
                    + npdmId + ", not this game (" + titleId.toUpperCase(Locale.ROOT) + "). Not installed.");
            String wrongBuild = buildIdProblem(layout, gameBuilds);
            if (wrongBuild != null) return Result.fail(wrongBuild);
            // The Yuzu/Eden "load" layout's wrapper folder name is the mod's real name (that is how those tools organize mods); it wins over whatever
            // the picked zip/folder was called. The Atmosphère and bare layouts carry no such embedded name, so the player's own name (from the
            // picked document, usually the zip's own filename) is all there is
            String preferred = layout.suggestedName != null && !layout.suggestedName.isBlank() ? layout.suggestedName
                    : displayName != null && !displayName.isBlank() ? displayName : staging.getName();
            String name = ModStore.freeName(modsRoot, titleId, preferred);
            File made = new File(ModStore.gameDir(modsRoot, titleId), name);
            if (!made.mkdirs()) throw new IOException("could not create " + made);
            dest = made;
            // Marked until it is complete: a process that ends in the middle of the copy (GBs, minutes) leaves a folder no list shows (it has no mod.json yet),
            // and ModStore.sweepDeleted knows it by this file
            Files.write(new File(dest, ModStore.INSTALLING).toPath(), new byte[0]);
            boolean anyCode = false;
            if (layout.romfs != null && layout.romfs.isDirectory()) copyTree(layout.romfs, new File(dest, "romfs"), progress);
            if (layout.exefs != null && layout.exefs.isDirectory() && hasFiles(layout.exefs)) { copyTree(layout.exefs, new File(dest, "exefs"), progress); anyCode = true; }
            if (layout.exefsPatches != null && layout.exefsPatches.isDirectory() && hasIps(layout.exefsPatches)) {
                copyTree(layout.exefsPatches, new File(dest, "exefs_patches"), progress); anyCode = true;
            }
            if (layout.cheats != null && layout.cheats.isDirectory() && hasFiles(layout.cheats)) copyTree(layout.cheats, new File(dest, "cheats"), progress);
            if (!new File(dest, "romfs").isDirectory() && !new File(dest, "exefs").isDirectory()
                    && !new File(dest, "exefs_patches").isDirectory() && !new File(dest, "cheats").isDirectory()) {
                deleteTree(dest);
                return Result.fail("The mod's folders were all empty once copied; nothing was imported.");
            }
            ModStore.Mod m = new ModStore.Mod(dest);
            m.name = name;
            m.version = "";
            m.enabled = true;   // installed on: it applies from the game's next start, and a code mod's build starts by itself (dev.peanuts.settings.ModsSection); a running game reads its mods at its start only
            m.order = ModStore.list(modsRoot, titleId).size();
            m.kind = anyCode ? ModStore.KIND_CODE : ModStore.KIND_FILES;
            Files.deleteIfExists(new File(dest, ModStore.INSTALLING).toPath());   // complete: the manifest below makes it a mod
            ModStore.save(m);
            return Result.ok(name, m.kind);
        } catch (IOException | RuntimeException e) {
            // RuntimeException too: a file name the file system does not take (InvalidPathException) or a walk that fails (UncheckedIOException) ended the
            // import's thread without a result, and the section showed "Installing a mod" until the app was closed. What was copied goes: a failed copy
            // (the storage is full, most often) left GBs in a folder without a manifest, which no list shows and no delete reaches
            if (dest != null) deleteTree(dest);
            return Result.fail("Could not import the mod: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private static final class Layout {
        File romfs, exefs, exefsPatches, cheats;
        String suggestedName;
        String mismatchedTitleId;   // set only for the Atmosphère case when contents/<TID> doesn't match the game
    }

    private static Layout detect(File staging, String titleId) {
        File atmosphere = new File(staging, "atmosphere");
        File contents = new File(atmosphere, "contents");
        if (contents.isDirectory()) {
            File[] tids = contents.listFiles(File::isDirectory);
            if (tids != null && tids.length > 0) {
                File match = null, firstOther = null;
                for (File t : tids) {
                    if (t.getName().equalsIgnoreCase(titleId)) match = t;
                    else if (firstOther == null) firstOther = t;
                }
                Layout l = new Layout();
                if (match != null) {
                    l.romfs = new File(match, "romfs");
                    l.exefs = new File(match, "exefs");
                    l.cheats = new File(match, "cheats");
                    l.exefsPatches = new File(atmosphere, "exefs_patches");
                    return l;   // no suggestedName: the Atmosphère layout has no embedded mod name, only a title ID folder
                }
                if (firstOther != null) { l.mismatchedTitleId = firstOther.getName().toUpperCase(Locale.ROOT); return l; }
            }
        }
        // Atmosphère with patches only (atmosphere/exefs_patches/<set>/<build id>.ips and no contents/: the usual shape of a frame rate or resolution patch).
        // No title ID folder names its game: the patches' build IDs do (buildIdProblem)
        File atmospherePatches = new File(atmosphere, "exefs_patches");
        if (hasIps(atmospherePatches)) {
            Layout l = new Layout();
            l.exefsPatches = atmospherePatches;
            return l;
        }
        // Bare: romfs/ and/or exefs/ directly at the staging root (or exefs_patches/ alone, as above)
        File romfs = new File(staging, "romfs"), exefs = new File(staging, "exefs"), cheats = new File(staging, "cheats");
        if (romfs.isDirectory() || (exefs.isDirectory() && hasFiles(exefs)) || (cheats.isDirectory() && hasFiles(cheats)) || hasIps(new File(staging, "exefs_patches"))) {
            Layout l = new Layout();
            l.romfs = romfs; l.exefs = exefs; l.cheats = cheats;
            l.exefsPatches = new File(staging, "exefs_patches");
            return l;   // no suggestedName: bare layout has no name of its own either, only the picked folder/zip's own name
        }
        // Yuzu/Eden "load" layout: exactly one real subfolder (not atmosphere, not a dotfile/__MACOSX) that itself has romfs/ or exefs/
        File[] kids = staging.listFiles(File::isDirectory);
        if (kids != null) {
            File candidate = null;
            int nCandidates = 0;
            for (File k : kids) {
                String n = k.getName();
                if (n.equalsIgnoreCase("atmosphere") || n.startsWith(".") || n.equalsIgnoreCase("__MACOSX")) continue;
                File r = new File(k, "romfs"), e = new File(k, "exefs"), ch = new File(k, "cheats");
                if (r.isDirectory() || (e.isDirectory() && hasFiles(e)) || (ch.isDirectory() && hasFiles(ch)) || hasIps(new File(k, "exefs_patches"))) { candidate = k; nCandidates++; }
            }
            if (nCandidates == 1) {
                Layout l = new Layout();
                l.romfs = new File(candidate, "romfs");
                l.exefs = new File(candidate, "exefs");
                l.cheats = new File(candidate, "cheats");
                l.exefsPatches = new File(candidate, "exefs_patches");
                l.suggestedName = candidate.getName();
                return l;
            }
        }
        return null;
    }

    /**
     * The game a mod's exefs/main.npdm is for: the program ID in its ACI0 (the META header's u32 at 0x70 is where the ACI0 starts, its u64 at +0x10 the ID,
     * both little endian), 16 hex digits in upper case. A console's loader refuses an npdm of another program, so a mod that ships one names its game with
     * it, also in the bare and "load" layouts, which have no title ID folder. null: no such file, or not an npdm (then there is nothing to tell from it).
     */
    private static String npdmTitleId(File npdm) {
        if (!npdm.isFile() || npdm.length() > (1 << 20)) return null;
        try {
            byte[] b = Files.readAllBytes(npdm.toPath());
            if (b.length < 0x80 || b[0] != 'M' || b[1] != 'E' || b[2] != 'T' || b[3] != 'A') return null;
            long aci = le(b, 0x70, 4);
            if (aci < 0x80 || aci + 0x18 > b.length || b[(int) aci] != 'A' || b[(int) aci + 1] != 'C' || b[(int) aci + 2] != 'I' || b[(int) aci + 3] != '0') return null;
            return String.format(Locale.ROOT, "%016X", le(b, (int) aci + 0x10, 8));
        } catch (IOException e) { return null; }
    }

    private static long le(byte[] b, int at, int n) {
        long v = 0;
        for (int i = n - 1; i >= 0; i--) v = v << 8 | (b[at + i] & 0xff);
        return v;
    }

    /**
     * An IPS patch is named after the build ID of the module it patches ({@code <build id>.ips}, in {@code exefs_patches/<set>/} or in {@code exefs/}), so
     * the names say which game build the mod was made for. A bare or "load"-layout mod carries no title ID at all: without this check a mod for another
     * game was imported and attached without a word (its patches then never apply, or the build with mods stops). Refused only when it can be told: the
     * mod has patches named by a build ID and none of them is for a module of this game's build (the first 32 hex digits, any case; a name cut short is
     * padded with zeros, as the tools that write them drop trailing zeros). A mod that ships several versions' patches passes when one of them fits. A
     * whole replacement module (exefs/main) says nothing about the build it replaces, so it is not judged here. null: no problem, or nothing to compare.
     */
    private static String buildIdProblem(Layout l, Map<String, String> gameBuilds) {
        if (gameBuilds == null || gameBuilds.isEmpty()) return null;
        List<String> named = new ArrayList<>();
        addIpsBuilds(l.exefs, named);
        File[] sets = l.exefsPatches == null ? null : l.exefsPatches.listFiles(File::isDirectory);
        if (sets != null) for (File s : sets) addIpsBuilds(s, named);
        if (named.isEmpty()) return null;
        for (String id : gameBuilds.values()) {
            String game = build32(id);
            // a name of exactly 16 digits is the ID's first 8 bytes whatever follows them (dm/tools/patch_ips.py stem_matches takes it so; padded with zeros
            // it fitted no game whose ID goes on, and a mod the build would have applied was refused here)
            for (String n : named) if (n.length() == 16 ? game.startsWith(n) : build32(n).equals(game)) return null;
        }
        String main = gameBuilds.containsKey("main") ? gameBuilds.get("main") : gameBuilds.values().iterator().next();
        return "This mod is for another game or another version of this game: its patches are for build " + build32(named.get(0)).substring(0, 16)
                + ", and this game's build is " + build32(main).substring(0, 16) + ". Not installed. Use the mod made for this game and version.";
    }

    private static void addIpsBuilds(File dir, List<String> out) {
        String[] names = dir == null ? null : dir.list();
        if (names == null) return;
        for (String n : names) {
            if (!isIps(n)) continue;
            String stem = n.substring(0, n.lastIndexOf('.'));
            if (stem.matches("[0-9A-Fa-f]{8,}")) out.add(stem.toUpperCase(Locale.ROOT));   // any other name is not a build ID: nothing to tell from it
        }
    }

    /** A patch file's name: .ips or .ips32 (Atmosphère's own format for a module past 16 MiB, which most games' main is), in either letter case; as
     *  dev.peanuts.build.ModBuild.isIpsName and dm/tools/patch_ips.py find_patches. With .ips alone a mod of .ips32 files lost its patches at the install */
    private static boolean isIps(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".ips") || n.endsWith(".ips32");
    }

    /** A build ID's first 32 hex digits in upper case, padded with zeros when shorter */
    private static String build32(String id) {
        StringBuilder b = new StringBuilder(id.trim().toUpperCase(Locale.ROOT));
        while (b.length() < 32) b.append('0');
        return b.substring(0, 32);
    }

    private static boolean hasFiles(File dir) {
        File[] kids = dir.listFiles();
        return kids != null && kids.length > 0;
    }

    private static boolean hasIps(File exefsPatches) {
        File[] subs = exefsPatches.listFiles(File::isDirectory);
        if (subs == null) return false;
        for (File s : subs) {
            File[] kids = s.listFiles((d, n) -> isIps(n));
            if (kids != null && kids.length > 0) return true;
        }
        return false;
    }

    /** A plain recursive copy (the source is the player's own staging copy, already off any content-provider weirdness) with byte-count progress;
     *  symbolic links are followed as files (a zip import never has any; a picked folder's own device-side links are rare and copying their target's
     *  bytes is the safe default for a RomFS mod) */
    private static void copyTree(File src, File dst, Progress progress) throws IOException {
        long total = estimateSize(src);
        long[] done = {0};
        Path srcPath = src.toPath(), dstPath = dst.toPath();
        Files.walkFileTree(srcPath, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dstPath.resolve(srcPath.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path target = dstPath.resolve(srcPath.relativize(file));
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                done[0] += attrs.size();
                progress.onProgress(done[0], total, file.getFileName().toString());
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static long estimateSize(File f) {
        if (!f.exists()) return 0;
        if (f.isFile()) return f.length();
        long total = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) total += estimateSize(k);
        return total;
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    /** Free-space check before a big copy (DESIGN-MODS.md, this task's "check free space first"): the staging source's own size against the mods
     *  volume's usable space, with a flat 10% margin for filesystem overhead/rounding. Returns null when there is enough room, else a short message
     *  for the UI to show instead of starting the copy */
    public static String freeSpaceProblem(File staging, File modsRoot) {
        long need = (long) (estimateSize(staging) * 1.10);
        File probe = modsRoot;
        while (probe != null && !probe.exists()) probe = probe.getParentFile();
        long free = probe == null ? Long.MAX_VALUE : probe.getUsableSpace();
        if (free >= need) return null;
        return "Needs about " + words(need) + " free; only " + words(free) + " is available.";
    }

    private static String words(long bytes) {
        double gb = bytes / 1e9;
        return gb >= 0.95 ? String.format(Locale.ROOT, "%.1f GB", gb) : String.format(Locale.ROOT, "%.0f MB", bytes / 1e6);
    }

    /** A zip's own name or a folder's own name, stripped of a trailing ".zip" (case-insensitive), as the initial mod name suggestion */
    public static String nameFromPickedDisplayName(String displayName) {
        if (displayName == null) return "Mod";
        String s = displayName;
        if (s.toLowerCase(Locale.ROOT).endsWith(".zip")) s = s.substring(0, s.length() - 4);
        return s.isBlank() ? "Mod" : s;
    }

    /** Extracts a zip (already copied to local disk, or read straight off a content-provider stream) into {@code staging}, rejecting path traversal
     *  entries (a {@code ..} part, or a name that resolves outside staging) outright. A name that starts with a slash lands inside staging (File(parent,
     *  child)); a link in a zip comes out as a small file holding its target, never as a link. Pure java.util.zip; no Android. {@code totalBytesHint}
     *  (0 if unknown) only feeds progress, never a size check. */
    public static void extractZip(java.io.InputStream in, File staging, long totalBytesHint, Progress progress) throws IOException {
        if (progress == null) progress = NONE;
        long done = 0;
        String root = staging.getCanonicalPath() + File.separator;
        try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(in)) {
            java.util.zip.ZipEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');   // a zip written with Windows' separators: folders here too, not one file named "romfs\Data\…"
                // by part: a file such as "notes..txt" is no traversal (any ".." in the name refused the whole mod)
                for (String part : name.split("/")) if (part.equals("..")) throw new IOException("the zip has an unsafe entry name: " + e.getName());
                File out = new File(staging, name);
                if (!(out.getCanonicalPath() + File.separator).startsWith(root)) throw new IOException("the zip has an unsafe entry name: " + e.getName());
                if (e.isDirectory() || name.endsWith("/")) { out.mkdirs(); continue; }   // name: a folder entry written with a backslash
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (java.io.OutputStream os = Files.newOutputStream(out.toPath())) {
                    int n;
                    while ((n = zin.read(buf)) > 0) { os.write(buf, 0, n); done += n; progress.onProgress(done, totalBytesHint, name); }
                }
                zin.closeEntry();
            }
        }
    }
}
