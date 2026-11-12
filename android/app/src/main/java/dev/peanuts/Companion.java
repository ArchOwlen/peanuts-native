package dev.peanuts;

import android.app.Activity;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.peanuts.settings.CompanionSection;

/**
 * Peanuts Native: the companion screen of a running game, shown on a second display (the AYN Thor's "Screen-2") as a Presentation owned by the
 * game's activity, in the game's own process (":game"), so the host's memory and input are a function call away. GameActivity calls start() once its
 * window is up and stop() when it is destroyed (the two PEANUTS hooks); without a second display nothing is shown and the game is unchanged.
 *
 * Taking the display at all is gated on the host's companion runtime (dm/peanuts/peanuts.c, Bridge.rtState): with no companion package for this title
 * (or one that failed to load), the second display is left alone entirely, exactly as it is without Peanuts - Android's own launcher/home stays on it
 * - unless the developer readout is explicitly turned on (PeanutsPrefs.developerReadout). RT_PENDING (the host has not decided yet, usually because the
 * game's main module has not loaded) is polled until it resolves or the game ends.
 */
public final class Companion {
    static final String TAG = "peanuts";
    private static final int DECIDE_POLL_MS = 200;
    private static CompanionPresentation shown;
    private static DisplayManager.DisplayListener listener;
    private static DisplayManager listenerDm;   // where the listener is registered: DisplayManager's listeners are process-wide, stop() must unregister it
    private static android.content.Context held;   // the game hold's context while it runs (GameHold)
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static Activity decidingGame;
    private static String decidingGameName;
    private static boolean stopped;
    /** Set once decide() resolves: whether this run is allowed to take the second display at all. Until then, a hot-plugged display is ignored. */
    private static boolean allowed;

    private Companion() {}

    /** Starts the game hold and, once the host has decided whether a companion loaded, shows it (or leaves the display alone). Keeps trying as displays come and go. */
    public static void start(Activity game, String gameName) {
        if (listener != null) return;
        stopped = false;
        allowed = false;
        DisplayManager dm = (DisplayManager) game.getSystemService(Activity.DISPLAY_SERVICE);
        listener = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int id) { if (allowed) show(game, gameName, dm); }
            @Override public void onDisplayRemoved(int id) { if (allowed && shown != null && shown.getDisplay().getDisplayId() == id) { shown = null; show(game, gameName, dm); } }
            @Override public void onDisplayChanged(int id) {}
        };
        dm.registerDisplayListener(listener, null);
        listenerDm = dm;
        decidingGame = game; decidingGameName = gameName;
        decide(dm);
        GameHold.start(game, gameName);
        held = game.getApplicationContext();
    }

    public static void stop() {
        stopped = true;
        decidingGame = null;
        ui.removeCallbacksAndMessages(null);
        if (held != null) { GameHold.stop(held); held = null; }
        if (shown != null) { try { shown.dismiss(); } catch (RuntimeException ignored) {} shown = null; }
        if (listenerDm != null && listener != null) { try { listenerDm.unregisterDisplayListener(listener); } catch (RuntimeException ignored) {} }
        listener = null; listenerDm = null;   // unregistered: the listener holds the game activity, and DisplayManager's listeners outlive it
    }

    /** Polls Bridge.rtState() until it leaves RT_PENDING, then decides once whether to take the display. A title update/reinstall never changes this mid-run,
     * so one decision for the game's whole life is enough - onDisplayAdded/Removed above keep tracking the physical display after that. */
    private static void decide(DisplayManager dm) {
        Activity game = decidingGame;
        if (game == null || stopped || game.isFinishing() || game.isDestroyed()) return;
        int state = Bridge.rtStateSafe();
        boolean developerReadout = PeanutsPrefs.developerReadout(game);
        if (state == Bridge.RT_PENDING && !developerReadout) {
            ui.postDelayed(() -> decide(dm), DECIDE_POLL_MS);
            return;
        }
        if (CompanionSection.secondScreen(game, String.valueOf(game.getIntent().getStringExtra("title_id"))).equals(CompanionSection.OFF)) {
            Log.i(TAG, "second screen is Off in this title's settings: companion not shown");
            return;
        }
        if (state != Bridge.RT_LOADED && !developerReadout) {
            Log.i(TAG, "no companion loaded for this title (state=" + state + "): the second display is left to Android, not taken over");
            return;
        }
        if (state == Bridge.RT_FAILED) Log.w(TAG, "a companion package was found but the runtime refused it (see earlier \"peanuts\" logs); " +
                (developerReadout ? "showing the developer readout instead" : "second display left to Android"));
        allowed = true;
        show(game, decidingGameName, dm);
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
