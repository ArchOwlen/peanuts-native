package dev.peanuts.launch;

import android.content.Context;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import dev.peanuts.PeanutsPrefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Dynamic shortcuts for the last 3 games played (LAUNCHERS.md "Dynamic shortcuts", owner request item 3, optional). {@link #touch} is the one-line hook
 * in {@code GameActivity.onCreate}: every run that reaches the game's own host (not a failed load) moves that title to the front of a small list this
 * class keeps in its own file ({@code files/peanuts-recent.json}, independent of upstream's {@code Game.used} and of {@link dev.peanuts.PeanutsPrefs}'s
 * other keys, so this stays entirely inside the files Peanuts already owns). {@link #refresh} then hands the launcher the top 3 as
 * {@link ShortcutManager#setDynamicShortcuts}, each one the same explicit title-ID intent {@link GameShortcuts} uses for a pinned shortcut, so the same
 * validation applies: only a title ID this device actually started reaches the list, and {@code FrontendLaunch.byTitleId} re-checks it against the
 * library before launching anything.
 */
public final class RecentGames {
    private static final int MAX = 3;

    private RecentGames() { }

    /** Record that {@code titleId} was just started, and push the refreshed top-3 to the launcher. Safe to call off or on the main thread; does its own I/O */
    public static void touch(Context c, String titleId, String name) {
        if (titleId == null || titleId.isEmpty() || "null".equals(titleId)) return;
        new Thread(() -> {
            List<Entry> list = load(c);
            list.removeIf(e -> e.titleId.equalsIgnoreCase(titleId));
            list.add(0, new Entry(titleId.toUpperCase(java.util.Locale.ROOT), name == null ? titleId : name));
            while (list.size() > MAX) list.remove(list.size() - 1);
            save(c, list);
            publish(c, list);
        }, "peanuts-recent").start();
    }

    /** Re-publishes the current top 3 (e.g. after a rename is noticed elsewhere); normally {@link #touch} does this on its own */
    public static void refresh(Context c) { publish(c, load(c)); }

    private static void publish(Context c, List<Entry> list) {
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        if (sm == null) return;
        List<android.content.pm.ShortcutInfo> infos = new ArrayList<>();
        for (Entry e : list) {
            File icon = new File(PeanutsPrefs.iconsDir(c), e.titleId + ".png");
            Bitmap cover = icon.isFile() && icon.length() > 0 ? BitmapFactory.decodeFile(icon.getPath()) : null;
            infos.add(GameShortcuts.build(c, e.titleId, e.name, cover));
        }
        try { sm.setDynamicShortcuts(infos); } catch (RuntimeException ex) { android.util.Log.w("peanuts", "RecentGames: publish failed: " + ex); }
    }

    private static final class Entry {
        final String titleId, name;
        Entry(String titleId, String name) { this.titleId = titleId; this.name = name; }
    }

    private static File file(Context c) { return new File(c.getFilesDir(), "peanuts-recent.json"); }

    private static List<Entry> load(Context c) {
        List<Entry> out = new ArrayList<>();
        File f = file(c);
        if (!f.isFile()) return out;
        try {
            JSONArray a = new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Entry(o.getString("title_id"), o.optString("name", o.getString("title_id"))));
            }
        } catch (Exception e) { android.util.Log.w("peanuts", "RecentGames: load " + f + ": " + e); }
        return out;
    }

    private static void save(Context c, List<Entry> list) {
        try {
            JSONArray a = new JSONArray();
            for (Entry e : list) a.put(new JSONObject().put("title_id", e.titleId).put("name", e.name));
            Files.write(file(c).toPath(), a.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { android.util.Log.w("peanuts", "RecentGames: save: " + e); }
    }
}
