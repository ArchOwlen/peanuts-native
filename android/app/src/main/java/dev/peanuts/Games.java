package dev.peanuts;

import android.content.Context;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** The launcher's game list (dev.nxnative.android.Game, package-private) read through reflection, so no upstream class needs a hook. */
final class Games {
    static final class Entry {
        final String titleId, name, gameDir, packDir;
        Entry(String titleId, String name, String gameDir, String packDir) { this.titleId = titleId; this.name = name; this.gameDir = gameDir; this.packDir = packDir; }
        /** Where this title's companion packages live: <game>/<title ID>-work/peanuts (beside android-pack), or null without a pack */
        File companionRoot() { return packDir == null || packDir.isEmpty() ? null : new File(new File(packDir).getParentFile(), "peanuts"); }
    }

    private Games() {}

    static List<Entry> load(Context c) {
        List<Entry> out = new ArrayList<>();
        try {
            Class<?> game = Class.forName("dev.nxnative.android.Game");
            Method load = game.getDeclaredMethod("load", Context.class);
            load.setAccessible(true);
            Field titleId = field(game, "titleId"), name = field(game, "name"), gameDir = field(game, "gameDir"), packDir = field(game, "packDir");
            for (Object g : (List<?>) load.invoke(null, c)) {
                out.add(new Entry(str(titleId.get(g)), str(name.get(g)), str(gameDir.get(g)), str(packDir.get(g))));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            android.util.Log.w(Companion.TAG, "game list unreadable: " + e);
        }
        return out;
    }

    private static Field field(Class<?> k, String n) throws NoSuchFieldException { Field f = k.getDeclaredField(n); f.setAccessible(true); return f; }
    private static String str(Object o) { return o == null ? "" : o.toString(); }
}
