package dev.peanuts;

import android.app.Activity;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Peanuts Native: the companion screen of a running game, shown on a second display (the AYN Thor's "Screen-2") as a Presentation owned by the
 * game's activity, in the game's own process (":game"), so the host's memory and input are a function call away. GameActivity calls start() once its
 * window is up and stop() when it is destroyed (the two PEANUTS hooks); without a second display nothing is shown and the game is unchanged.
 */
public final class Companion {
    static final String TAG = "peanuts";
    private static CompanionPresentation shown;
    private static DisplayManager.DisplayListener listener;
    private static android.content.Context held;   // the game hold's context while it runs (GameHold)

    private Companion() {}

    /** Shows the companion on the first second display, and keeps trying as displays come and go. */
    public static void start(Activity game, String gameName) {
        if (listener != null) return;
        DisplayManager dm = (DisplayManager) game.getSystemService(Activity.DISPLAY_SERVICE);
        listener = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int id) { show(game, gameName, dm); }
            @Override public void onDisplayRemoved(int id) { if (shown != null && shown.getDisplay().getDisplayId() == id) { shown = null; show(game, gameName, dm); } }
            @Override public void onDisplayChanged(int id) {}
        };
        dm.registerDisplayListener(listener, null);
        show(game, gameName, dm);
        GameHold.start(game, gameName);
        held = game.getApplicationContext();
    }

    public static void stop() {
        if (held != null) { GameHold.stop(held); held = null; }
        if (shown != null) { try { shown.dismiss(); } catch (RuntimeException ignored) {} shown = null; }
        listener = null;   // the DisplayManager drops it with the activity's context
    }

    private static void show(Activity game, String gameName, DisplayManager dm) {
        if (shown != null || game.isFinishing() || game.isDestroyed()) return;
        Display d = secondDisplay(game, dm);
        if (d == null) { Log.i(TAG, "no second display: companion not shown"); return; }
        try {
            shown = new CompanionPresentation(game, d, gameName);
            shown.show();
            Log.i(TAG, "companion on display " + d.getDisplayId() + " \"" + d.getName() + "\"");
        } catch (RuntimeException e) {   // the display refuses app windows, or went away between the query and show()
            Log.w(TAG, "companion not shown on display " + d.getDisplayId() + ": " + e);
            shown = null;
        }
    }

    /** The first display that is not the game's own: the Thor lists its Screen-2 only under DISPLAY_CATEGORY_PRESENTATION, an emulator's overlay only in the plain list, so both are read. */
    static Display secondDisplay(Activity game, DisplayManager dm) {
        int own = game.getDisplay() != null ? game.getDisplay().getDisplayId() : Display.DEFAULT_DISPLAY;
        Map<Integer, Display> all = new LinkedHashMap<>();
        for (Display d : dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) all.put(d.getDisplayId(), d);
        for (Display d : dm.getDisplays()) all.put(d.getDisplayId(), d);
        for (Display d : all.values()) if (d.getDisplayId() != own && d.getDisplayId() != Display.DEFAULT_DISPLAY && d.isValid()) return d;
        return null;
    }
}
