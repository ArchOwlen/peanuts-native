package dev.peanuts;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs a companion package: downloads the .dsmod.zip, checks that it holds package.json and dualscreen/manifest.json for the expected title, extracts it into
 * <companion root>/<package name>/ (a fresh folder; the old one is replaced only once the new one is complete) and writes installed.json with the version. The
 * host (dm/peanuts/peanuts.c) finds the package there at the next game start.
 */
public final class Installer {
    public interface Progress { void on(String what, long done, long total); }

    private Installer() {}

    public static File installedDir(File root, String packageName) { return new File(root, packageName); }

    /** The installed version of a package in root, or null */
    public static String installedVersion(File root, String packageName) {
        File f = new File(installedDir(root, packageName), "installed.json");
        if (!f.isFile()) return new File(installedDir(root, packageName), "package.json").isFile() ? "?" : null;
        try { return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).optString("version", "?"); } catch (Exception e) { return "?"; }
    }

    public static void install(Catalog.Item item, File root, File cacheDir, Progress progress) throws IOException {
        File zip = new File(cacheDir, item.titleId + "-" + item.packageName + "-" + item.version + ".dsmod.zip");
        download(item.url, zip, item.size, progress);
        File fresh = new File(root, item.packageName + ".new"), done = installedDir(root, item.packageName), old = new File(root, item.packageName + ".old");
        delete(fresh);
        if (!fresh.mkdirs()) throw new IOException("cannot create " + fresh);
        try {
            extract(zip, fresh, progress);
            if (!new File(fresh, "package.json").isFile() || !new File(fresh, "dualscreen/manifest.json").isFile()) throw new IOException("not a companion package (no package.json + dualscreen/manifest.json)");
            String manifest = new String(Files.readAllBytes(new File(fresh, "dualscreen/manifest.json").toPath()), StandardCharsets.UTF_8);
            String title = new JSONObject(manifest).optString("title_id", "").toUpperCase();
            if (!title.isEmpty() && !title.equals(item.titleId)) throw new IOException("package is for title " + title + ", not " + item.titleId);
            try (OutputStream o = new FileOutputStream(new File(fresh, "installed.json"))) {
                o.write(new JSONObject().put("version", item.version).put("tag", item.tag).put("url", item.url).toString(2).getBytes(StandardCharsets.UTF_8));
            }
            delete(old);
            if (done.exists() && !done.renameTo(old)) throw new IOException("cannot move the old package aside");
            if (!fresh.renameTo(done)) { old.renameTo(done); throw new IOException("cannot place the new package"); }
            delete(old);
        } catch (org.json.JSONException e) {
            throw new IOException("manifest: " + e.getMessage());
        } finally {
            delete(fresh);
            zip.delete();
        }
    }

    public static void remove(File root, String packageName) { delete(installedDir(root, packageName)); }

    private static void download(String url, File to, long total, Progress progress) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "PeanutsNative");
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000); c.setReadTimeout(60000);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            byte[] buf = new byte[65536]; long done = 0; int n;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); done += n; if (progress != null) progress.on("download", done, total); }
        } finally { c.disconnect(); }
    }

    /** Unzips into dir; an entry escaping dir (zip slip) fails the install */
    private static void extract(File zip, File dir, Progress progress) throws IOException {
        String base = dir.getCanonicalPath() + File.separator;
        try (ZipInputStream z = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e; long files = 0;
            while ((e = z.getNextEntry()) != null) {
                File f = new File(dir, e.getName());
                if (!f.getCanonicalPath().startsWith(base)) throw new IOException("zip entry escapes the package: " + e.getName());
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(f)) { byte[] buf = new byte[65536]; int n; while ((n = z.read(buf)) > 0) o.write(buf, 0, n); }
                if (progress != null) progress.on("extract", ++files, 0);
            }
        }
    }

    private static void delete(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) delete(k);
        f.delete();
    }
}
