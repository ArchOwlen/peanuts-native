package dev.peanuts.mods;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * DESIGN-MODS.md's "Mods" section, the shared contract between the per-game Mods UI (play-time: romfs overlay + cheats, this package) and the build-time
 * code-mod pipeline (IPS/exefs, under {@code dev/peanuts/build/} and {@code dm/peanuts/}). A mod lives at
 * {@code <game home>/mods/<modName>/} (ModRuntime.modsRoot: the folder is the game's own, so it has no title ID level), with any of {@code romfs/}, {@code exefs/}, {@code exefs_patches/**}, {@code cheats/*.txt} and a
 * {@code mod.json} describing it: {@code {"name":..., "version":..., "enabled":bool, "order":int, "kind":"files"|"code"}}. "files": only romfs and/or
 * cheats, applies at play time on the normal build (no rebuild). "code": the mod also ships exefs or exefs_patches, which needs a modded build
 * (dev.peanuts.build.ModBuild) — and all of a "code" mod, its files and cheats too, applies only to a start that plays that build ({@link #contributing}).
 * <p>
 * This class is plain Java (no {@code android.*}, no {@code org.json}) so it can be exercised by a JVM-only test the same way
 * {@code dev.peanuts.build.RuntimeShaderMap} is ({@code peanuts/tests/test_mod_store.py}): javac it alone, run a small driver against a real temp
 * directory. The hand-rolled JSON in {@link #readJson}/{@link #writeJson} covers exactly the five flat fields {@code mod.json} ever has — never a
 * general-purpose parser, and never used for anything else in this package.
 */
public final class ModStore {
    private ModStore() {}

    public static final String KIND_FILES = "files";
    public static final String KIND_CODE = "code";

    /** One installed mod: its manifest fields plus the folder it lives in (so a caller can look at romfs/exefs/cheats directly) */
    public static final class Mod {
        public final File dir;      // <game home>/mods/<modName>/
        public String name;
        public String version;
        public boolean enabled;
        public int order;           // lower = applied/checked first = wins first (DM_ROMFS_OVERLAY's first-match-wins order)
        public String kind;         // KIND_FILES or KIND_CODE

        Mod(File dir) { this.dir = dir; }

        public File romfs() { return new File(dir, "romfs"); }
        public File exefs() { return new File(dir, "exefs"); }
        public File exefsPatches() { return new File(dir, "exefs_patches"); }
        public File cheats() { return new File(dir, "cheats"); }

        public boolean hasRomfs() { return nonEmpty(romfs()); }
        public boolean hasCheats() { return nonEmpty(cheats()); }
        public boolean hasCode() { return KIND_CODE.equals(kind); }

        // PEANUTS: build-side helpers (dev.peanuts.build.ModBuild) added alongside the UI/play-time half above; additive only, nothing above this
        // comment is changed by them. A "code" mod's exefs/ (whole replacement or added modules) and exefs_patches/<name>/<build id>.ips(32) (byte
        // patches, dm/tools/patch_ips.py) are independent: a mod may ship either, or both.
        public boolean hasExefs() { return nonEmpty(exefs()); }
        public boolean hasExefsPatches() { return nonEmpty(exefsPatches()); }
    }

    /** A folder with something in it, in one listing (list() is null for what is not a folder): the Mods section asks this of every mod at every render */
    private static boolean nonEmpty(File dir) {
        String[] names = dir.list();
        return names != null && names.length > 0;
    }

    /** The game's own mods folder: modsRoot is already per game (<game home>/mods, ModRuntime.modsRoot), so no title-ID level below it */
    public static File gameDir(File modsRoot, String titleId) { return modsRoot; }

    /** Every mod under this title, sorted by {@code order} (ties broken by folder name, so the list order is always stable and deterministic) */
    public static List<Mod> list(File modsRoot, String titleId) {
        List<Mod> out = new ArrayList<>();
        File dir = gameDir(modsRoot, titleId);
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) {
            if (!k.isDirectory() || k.getName().startsWith(".")) continue;   // a mod being deleted (remove); no mod's own folder has a dot name (sanitizeName)
            File manifest = new File(k, "mod.json");
            if (!manifest.isFile()) continue;
            Mod m = new Mod(k);
            try { readJson(manifest, m); } catch (IOException e) { continue; }   // a manifest that fails to parse is skipped, not a crash
            if (m.name == null || m.name.isEmpty()) m.name = k.getName();
            if (m.kind == null) m.kind = KIND_FILES;
            out.add(m);
        }
        out.sort(Comparator.comparingInt((Mod m) -> m.order).thenComparing(m -> m.dir.getName()));
        normalizeOrder(out);
        return out;
    }

    /** Renumbers 0..n-1 in list order, without touching anything on disk (the in-memory order {@link #list} always returns) */
    private static void normalizeOrder(List<Mod> mods) { for (int i = 0; i < mods.size(); i++) mods.get(i).order = i; }

    /** Writes one mod's manifest back (enabled/order changes; name/version/kind are set at import and not re-derived here) */
    public static void save(Mod m) throws IOException {
        File manifest = new File(m.dir, "mod.json");
        writeJson(manifest, m);
    }

    /** Enables/disables a mod by folder name and persists it; does not reorder */
    public static void setEnabled(File modsRoot, String titleId, String modFolderName, boolean enabled) throws IOException {
        for (Mod m : list(modsRoot, titleId)) if (m.dir.getName().equals(modFolderName)) { m.enabled = enabled; save(m); return; }
    }

    /** Deletes a mod's whole folder (the confirm dialog is the UI's job, not this method's) */
    public static void remove(File modsRoot, String titleId, String modFolderName) throws IOException { remove(modsRoot, titleId, modFolderName, null); }

    /** How far a delete is: files gone of the files counted (total 0 while counting) */
    public interface DeleteProgress { void on(long done, long total); }

    /**
     * As above, saying how far it is. The folder is first renamed to a dot name (".deleting-..."), which {@link #list} does not read (no mod.json it looks at)
     * and the game does not load: from that moment the mod is gone for the list and for a start of the game, and the files, which can be GBs on a memory card
     * and take minutes, go after. A process that ends in the middle leaves the dot folder; {@link #sweepDeleted} removes such leftovers.
     */
    public static void remove(File modsRoot, String titleId, String modFolderName, DeleteProgress progress) throws IOException {
        File root = gameDir(modsRoot, titleId), dir = new File(root, modFolderName);
        if (!dir.exists()) return;
        File trash = new File(root, DELETING + modFolderName + "-" + System.currentTimeMillis());
        if (!dir.renameTo(trash)) { new File(dir, "mod.json").delete(); trash = dir; }   // no rename: without its manifest it is not a mod either
        if (progress != null) progress.on(0, 0);
        long[] n = {0, countTree(trash)};
        deleteTree(trash, progress, n);
    }

    private static final String DELETING = ".deleting-";

    /** The file a mod's folder holds while the mod is being installed (ModImporter.importFrom writes it first and takes it out before the manifest goes in):
     *  a folder with it is an install that did not end, by an error or by the end of the process */
    static final String INSTALLING = ".installing";

    /** Removes what an interrupted delete (see remove) or install (INSTALLING: a half-copied mod, GBs that no list shows) left; quiet, for a background thread.
     *  Not to be started while a mod is being installed in this folder: its folder is such a leftover until it is complete */
    public static void sweepDeleted(File modsRoot, String titleId) {
        File[] kids = gameDir(modsRoot, titleId).listFiles();
        if (kids != null) for (File k : kids) if (k.isDirectory() && (k.getName().startsWith(DELETING) || new File(k, INSTALLING).isFile())) deleteQuietly(k);
    }

    /** A folder and all in it, as far as that goes; what stays is for the next sweep */
    public static void deleteQuietly(File f) { try { deleteTree(f); } catch (IOException e) { /* the next sweep */ } }

    private static long countTree(File f) {
        File[] kids = f.listFiles();
        long n = 1;
        if (kids != null) for (File k : kids) n += countTree(k);
        return n;
    }

    private static void deleteTree(File f, DeleteProgress progress, long[] n) throws IOException {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k, progress, n);
        if (!f.delete() && f.exists()) throw new IOException("could not delete " + f);
        if (progress != null && (++n[0] & 31) == 0) progress.on(n[0], n[1]);
    }

    private static void deleteTree(File f) throws IOException {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        if (!f.delete() && f.exists()) throw new IOException("could not delete " + f);
    }

    /** True when at least one enabled mod is a code mod (its exefs/exefs_patches part needs a modded build, not play-time) */
    public static boolean needsBuild(List<Mod> mods) {
        for (Mod m : mods) if (m.enabled && m.hasCode()) return true;
        return false;
    }

    /**
     * Whether packDir, the pack a start of the game loads, is the build with mods of exactly the code mods that are on (codeMods: {@link #enabledCodeMods}):
     * the folder the app gives that variant, packs/&lt;variant id&gt; in its storage or &lt;title ID&gt;/mod-&lt;hash&gt;/pack in the build folder
     * (dev.nxnative.android.AppStorage.pack), or one that holds the variant's mark with that id (".variant", dev.peanuts.build.ModdedBuild.markVariant).
     * Any other pack, the game's own first of all, runs the game's own code. No code mods on: false
     */
    public static boolean playsVariant(String titleId, List<Mod> codeMods, File packDir) {
        String id = packDir == null ? null : variantId(titleId, codeMods);
        if (id == null) return false;
        if (packDir.getName().equals(id)) return true;
        File parent = packDir.getParentFile();
        if (packDir.getName().equals("pack") && parent != null && parent.getName().equals("mod-" + codeModsHash(codeMods))) return true;
        try {
            File mark = new File(packDir, ".variant");
            return mark.isFile() && new String(Files.readAllBytes(mark.toPath()), StandardCharsets.UTF_8).trim().equals(id);
        } catch (IOException e) { return false; }
    }

    /**
     * The mods a start of the game takes its files and cheats from. variant: the start plays the build made for the code mods that are on
     * ({@link #playsVariant}). A code mod's files belong to its code: Luminescent Platinum's romfs on the game's own code (its variant not built yet, not ready,
     * refused, or still being built) ran a mix that neither is, with the mod's memory needs and without its memory numbers, and crashed at the starter. So a
     * mod of kind code gives a start nothing at all unless the start plays that variant; mods of files and cheats alone apply to every start. In list order
     */
    public static List<Mod> contributing(List<Mod> mods, boolean variant) {
        List<Mod> out = new ArrayList<>();
        for (Mod m : mods) if (m.enabled && (variant || !m.hasCode())) out.add(m);
        return out;
    }

    /** The enabled mods' romfs directories, in priority order (first wins — {@code DM_ROMFS_OVERLAY}'s own order), for every enabled mod that actually
     *  ships a romfs (the caller gives the mods of this start, {@link #contributing}: a code mod only with its build) */
    public static List<File> enabledRomfsDirs(List<Mod> mods) {
        List<File> out = new ArrayList<>();
        for (Mod m : mods) if (m.enabled && m.hasRomfs()) out.add(m.romfs());
        return out;
    }

    /** The enabled mods' cheats directories, in priority order (first wins when two mods both ship a same-named {@code <build id>.txt}) */
    public static List<File> enabledCheatsDirs(List<Mod> mods) {
        List<File> out = new ArrayList<>();
        for (Mod m : mods) if (m.enabled && m.hasCheats()) out.add(m.cheats());
        return out;
    }

    /** The enabled "code" mods, in the same first-wins priority order as {@link #enabledRomfsDirs} (dev.peanuts.build.ModBuild: the order a later
     *  mod's exefs/exefs_patches apply over an earlier one's, same rule as the romfs overlay) */
    public static List<Mod> enabledCodeMods(List<Mod> mods) {
        List<Mod> out = new ArrayList<>();
        for (Mod m : mods) if (m.enabled && m.hasCode() && (m.hasExefs() || m.hasExefsPatches())) out.add(m);
        return out;
    }

    /** A short, stable id for a set of enabled code mods (their folder name, version and place among the code mods, in the order they apply): the modded
     *  build's own variant tag, {@code <TITLEID>-mod-<this>} (dev.peanuts.build.ModBuild), so a different set of enabled code mods always gets its own build
     *  and the same set reuses one already made. The place is the one in codeMods, not {@code Mod.order}: that counts every mod of the game, so a files mod
     *  deleted before a code mod (or one installed after a delete) moved the code mod's number, and the same code mods then had another id and no build.
     *  For a code mod that is the game's first mod the two numbers are the same, so its build keeps its id. Never a secret or a security boundary — just
     *  short and collision-resistant for a handful of mods */
    public static String codeModsHash(List<Mod> codeMods) {
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < codeMods.size(); i++) {
            Mod m = codeMods.get(i);
            in.append(m.dir.getName()).append('\u0000').append(m.version == null ? "" : m.version).append('\u0000').append(i).append('\n');
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(in.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) hex.append(String.format(Locale.ROOT, "%02x", d[i] & 0xff));   // 12 hex chars: short, plenty for a handful of mods
            return hex.toString();
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }   // SHA-256 is mandatory on every JVM and every Android API level this app targets
    }

    /** The modded build's variant id (a pack/library entry of its own, never overwriting titleId's normal build — DESIGN-MODS.md "a SEPARATE modded
     *  build"): {@code <TITLEID>-mod-<codeModsHash>}, or null when codeMods is empty (nothing to build: play the normal pack) */
    public static String variantId(String titleId, List<Mod> codeMods) {
        return codeMods.isEmpty() ? null : titleId.toUpperCase(Locale.ROOT) + "-mod-" + codeModsHash(codeMods);
    }

    /** The most code mods {@link #reachableVariantIds} goes through the sets of (2^n ids) */
    private static final int REACHABLE_MAX = 12;

    /**
     * Every variant id a choice of switches can give with the mods installed now (mods: {@link #list}): one per non-empty set of the code mods that have exefs
     * files or patches, on or off, in their order. A build with mods under any other id is of a mod that was deleted (or deleted and installed again: the same
     * name, other files) and no start of the game takes it again: dev.peanuts.build.ModdedBuild.sweepVariants removes those. null with more than REACHABLE_MAX
     * code mods (nothing is told then, and nothing removed)
     */
    public static Set<String> reachableVariantIds(String titleId, List<Mod> mods) {
        List<Mod> code = new ArrayList<>();
        for (Mod m : mods) if (m.hasCode() && (m.hasExefs() || m.hasExefsPatches())) code.add(m);
        if (code.size() > REACHABLE_MAX) return null;
        Set<String> out = new HashSet<>();
        for (int set = 1; set < 1 << code.size(); set++) {
            List<Mod> on = new ArrayList<>();
            for (int i = 0; i < code.size(); i++) if ((set >> i & 1) != 0) on.add(code.get(i));
            out.add(variantId(titleId, on));
        }
        return out;
    }

    /** A free, unused folder name under {@code gameDir} for a new mod called {@code wanted} ("Name", "Name (2)", "Name (3)", …) */
    public static String freeName(File modsRoot, String titleId, String wanted) {
        String base = sanitizeName(wanted);
        File dir = gameDir(modsRoot, titleId);
        String candidate = base;
        int n = 2;
        while (new File(dir, candidate).exists()) candidate = base + " (" + (n++) + ")";
        return candidate;
    }

    /** A folder-name-safe version of a mod's display name: no path separators, no leading/trailing dots or spaces, never empty */
    static String sanitizeName(String name) {
        String s = name == null ? "" : name.trim();
        s = s.replaceAll("[\\\\/:*?\"<>|]+", " ").trim();
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1).trim();
        while (s.startsWith(".")) s = s.substring(1).trim();   // a dot name is a folder list() does not read (a mod being deleted)
        return s.isEmpty() ? "Mod" : s;
    }

    // ---- mod.json: five flat fields, hand-rolled so this class stays javac-able with no classpath beyond the JDK ----

    private static void readJson(File f, Mod out) throws IOException {
        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        out.name = jsonString(text, "name");
        out.version = jsonString(text, "version");
        String en = jsonRaw(text, "enabled");
        out.enabled = "true".equals(en);
        String ord = jsonRaw(text, "order");
        try { out.order = ord == null ? 0 : Integer.parseInt(ord.trim()); } catch (NumberFormatException e) { out.order = 0; }
        out.kind = jsonString(text, "kind");
    }

    private static void writeJson(File f, Mod m) throws IOException {
        File dir = f.getParentFile();
        if (dir != null) dir.mkdirs();
        StringBuilder b = new StringBuilder();
        b.append("{\n");
        b.append("  \"name\": ").append(jsonQuote(m.name)).append(",\n");
        b.append("  \"version\": ").append(jsonQuote(m.version == null ? "" : m.version)).append(",\n");
        b.append("  \"enabled\": ").append(m.enabled ? "true" : "false").append(",\n");
        b.append("  \"order\": ").append(m.order).append(",\n");
        b.append("  \"kind\": ").append(jsonQuote(m.kind == null ? KIND_FILES : m.kind)).append("\n");
        b.append("}\n");
        File tmp = new File(f.getPath() + ".tmp");
        Files.write(tmp.toPath(), b.toString().getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(f)) throw new IOException("could not write " + f);
    }

    /** A JSON string field's value, unescaped for the two sequences {@code mod.json} ever needs ({@code \"} and {@code \\}); null when the key is absent
     *  or its value is not a quoted string (so a malformed manifest degrades to nulls/defaults, never an exception that would hide the whole mod list) */
    private static String jsonString(String text, String key) {
        String raw = jsonRaw(text, key);
        if (raw == null || raw.isEmpty() || raw.charAt(0) != '"') return null;
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') break;
            if (c == '\\' && i + 1 < raw.length()) { i++; char n = raw.charAt(i); out.append(n == 'n' ? '\n' : n); }
            else out.append(c);
        }
        return out.toString();
    }

    /** The raw text after {@code "key":} up to (not including) the next top-level comma or closing brace; null when the key is not found at all */
    private static String jsonRaw(String text, String key) {
        String needle = "\"" + key + "\"";
        int k = text.indexOf(needle);
        if (k < 0) return null;
        int colon = text.indexOf(':', k + needle.length());
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        if (i < text.length() && text.charAt(i) == '"') {
            int j = i + 1;
            while (j < text.length() && text.charAt(j) != '"') { if (text.charAt(j) == '\\') j++; j++; }
            return text.substring(i, Math.min(j + 1, text.length()));
        }
        int j = i;
        while (j < text.length() && text.charAt(j) != ',' && text.charAt(j) != '}' && text.charAt(j) != '\n') j++;
        return text.substring(i, j).trim();
    }

    private static String jsonQuote(String s) {
        if (s == null) s = "";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\');
            if (c == '\n') { b.append("\\n"); continue; }
            b.append(c);
        }
        return b.append('"').toString();
    }
}
