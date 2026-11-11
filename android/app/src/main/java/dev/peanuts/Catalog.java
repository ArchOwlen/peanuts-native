package dev.peanuts;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The companion catalog: the Eden Duo companions' GitHub releases, whose assets are `<TITLEID>-<Name>-<version>.dsmod.zip` (older ones `<TITLEID>.dsmod.zip`); the
 * newest version per title wins. Names come from the repository's catalog.json (title id → game name). Same packages, same format as Eden Duo (PACKAGE_FORMAT.md).
 */
final class Catalog {
    static final String RELEASES = "https://api.github.com/repos/igawa6/eden-duo-companions/releases?per_page=100";
    static final String NAMES = "https://raw.githubusercontent.com/igawa6/eden-duo-companions/main/catalog.json";
    private static final Pattern ASSET = Pattern.compile("^([0-9A-Fa-f]{16})(?:-([A-Za-z0-9]+)-([0-9][0-9A-Za-z.\\-]*))?\\.dsmod\\.zip$");

    static final class Item {
        final String titleId, packageName, version, url, tag, gameName;
        final long size;
        Item(String titleId, String packageName, String version, String url, String tag, long size, String gameName) {
            this.titleId = titleId; this.packageName = packageName; this.version = version; this.url = url; this.tag = tag; this.size = size; this.gameName = gameName;
        }
    }

    private Catalog() {}

    /** Every title with a companion, newest version first per title; network, call off the main thread */
    static List<Item> fetch() throws IOException {
        Map<String, String> names = new HashMap<>();
        try {
            JSONArray a = new JSONArray(get(NAMES));
            for (int i = 0; i < a.length(); i++) { JSONObject o = a.getJSONObject(i); names.put(o.optString("titleId").toUpperCase(Locale.ROOT), o.optString("name")); }
        } catch (Exception e) { android.util.Log.w(Companion.TAG, "catalog names: " + e); }
        Map<String, Item> best = new LinkedHashMap<>();
        try {
            JSONArray rels = new JSONArray(get(RELEASES));
            for (int i = 0; i < rels.length(); i++) {   // newest release first
                JSONObject r = rels.getJSONObject(i);
                if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue;
                JSONArray assets = r.optJSONArray("assets");
                for (int j = 0; assets != null && j < assets.length(); j++) {
                    JSONObject as = assets.getJSONObject(j);
                    Matcher m = ASSET.matcher(as.optString("name"));
                    if (!m.matches()) continue;
                    String title = m.group(1).toUpperCase(Locale.ROOT);
                    String pkg = m.group(2) != null ? m.group(2) : "DualScreen-" + title;
                    String ver = m.group(3) != null ? m.group(3) : r.optString("tag_name").replaceAll(".*-(\\d[\\d.]*)$", "$1");
                    Item it = new Item(title, pkg, ver, as.optString("browser_download_url"), r.optString("tag_name"), as.optLong("size"), names.getOrDefault(title, title));
                    Item have = best.get(title);
                    if (have == null || compareVersions(ver, have.version) > 0) best.put(title, it);
                }
            }
        } catch (org.json.JSONException e) { throw new IOException("releases: " + e.getMessage()); }
        return new ArrayList<>(best.values());
    }

    static int compareVersions(String a, String b) {
        String[] x = a.split("[.\\-]"), y = b.split("[.\\-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }
    private static int num(String s) { try { return Integer.parseInt(s.replaceAll("[^0-9].*", "")); } catch (NumberFormatException e) { return 0; } }

    static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "PeanutsNative");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " for " + url);
            return out.toString("UTF-8");
        } finally { c.disconnect(); }
    }
}
