package dev.peanuts.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.CompoundButton;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The launcher's haptics and motion ({@link Haptics}, {@link Motion}: the same six words and six tokens, the same two settings) for the pages upstream draws:
 * a game's page (held cover: its tabs, rows, switches, buttons, choice dialogs) and the Peanuts sections inside it (dev.peanuts.settings). Upstream's widgets
 * call in from one marked line each (Ui.Setting, Ui.button, Ui.Segmented, Ui.navItem, PadFocus.Builder; the list is in dev/peanuts/settings/HOOKS.md), so
 * every screen built from them gets the same feedback and no call site is edited. Nothing here changes what a press does: a listener is run exactly as it
 * was, with a word before it and a dip beside it.
 * <ul>
 * <li>A row that opens something (a chevron row, a mod's bin): TICK. A button, a choice picked in a dialog, a tab or a section picked, a dialog's
 * confirm: CLICK. A switch: CLICK on, TICK off. Play, Build with mods: HEAVY. A refusal or a failed job: REJECT. A build that ended well: DONE.</li>
 * <li>Quiet, as everywhere in the launcher: Back, Cancel, a dialog that only says something (OK), a control that is off, and the focus moving.</li>
 * <li>One word per press: a listener that has its word already ({@link #launch}, {@link #heavy}, {@link #row}) is not wrapped again by the widget.</li>
 * <li>Only in the launcher's process: the game's and the readiness check's processes use the same widgets and get nothing (the in-game pad has its own).</li>
 * </ul>
 */
public final class Feel {
    private Feel() {}

    private static int state;   // 0: not asked yet; 1: the launcher's process; -1: another process of the app (the game, the readiness check): nothing plays or moves

    /** Whether this process is the launcher's; the first call reads the settings, as a game's page can be the first thing built (the activity made again after a fold) */
    private static boolean ready(Context c) {
        if (state == 0) {
            String p = Application.getProcessName();
            state = p != null && p.indexOf(':') >= 0 ? -1 : 1;
            if (state > 0) { Haptics.refresh(c); Motion.refresh(c); }
        }
        return state > 0;
    }

    private static Activity activity(Context c) {
        for (; c instanceof ContextWrapper; c = ((ContextWrapper) c).getBaseContext()) if (c instanceof Activity) return (Activity) c;
        return null;
    }

    // ---- Presses ----

    /** A listener with its word: the word and the dip, then the listener as it was; launch: a game may start, so Home fades out over it (Motion.launched) */
    private static final class Click implements View.OnClickListener {
        final Haptics.Word word;
        final View.OnClickListener l;
        final boolean launch;

        Click(Haptics.Word word, View.OnClickListener l, boolean launch) { this.word = word; this.l = l; this.launch = launch; }

        @Override public void onClick(View v) {
            boolean on = ready(v.getContext());
            if (on) { Haptics.play(v, word); Motion.pressed(v); }
            l.onClick(v);
            Activity a = on && launch ? activity(v.getContext()) : null;
            if (a != null) Motion.launched(a);
        }
    }

    private static View.OnClickListener click(Haptics.Word w, View.OnClickListener l, boolean launch) { return l == null || l instanceof Click ? l : new Click(w, l, launch); }

    /** A row's tap (Ui.Setting, a mod's bin): it opens a dialog, a picker or another page, so TICK; null stays null (a row without an action, a switch row) */
    public static View.OnClickListener row(View.OnClickListener l) { return click(Haptics.Word.TICK, l, false); }

    /** A button's tap (Ui.button): CLICK */
    public static View.OnClickListener button(View.OnClickListener l) { return click(Haptics.Word.CLICK, l, false); }

    /** A weighty commit (Build with mods): HEAVY */
    public static View.OnClickListener heavy(View.OnClickListener l) { return click(Haptics.Word.HEAVY, l, false); }

    /** Play: HEAVY at the press, whatever follows (a gate dialog is the app asking, not refusing), and the launch's fade */
    public static View.OnClickListener launch(View.OnClickListener l) { return click(Haptics.Word.HEAVY, l, true); }

    /** A switch changed under the user's finger: CLICK on, TICK off. One set by code says nothing: while its page is built (not attached), or on a resume (no window focus) */
    public static void toggled(CompoundButton b, boolean on) {
        if (b.isAttachedToWindow() && b.isShown() && b.hasWindowFocus() && ready(b.getContext())) Haptics.toggle(b, on);
    }

    /** Another choice of a segmented control was picked (Recommended | Custom): CLICK */
    public static void picked(View v) { if (ready(v.getContext())) Haptics.play(v, Haptics.Word.CLICK); }

    // ---- A page, its tabs and its rail ----

    /** The page's column (Ui.page: a ScrollView over the background, one column in it) */
    private static ViewGroup column(View root) {
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) root;
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v instanceof ScrollView && ((ScrollView) v).getChildCount() > 0 && ((ScrollView) v).getChildAt(0) instanceof ViewGroup) return (ViewGroup) ((ScrollView) v).getChildAt(0);
        }
        return null;
    }

    /** A page was opened under root: what shows of its column from child first on enters (Motion.enterPage). Not for a page built again after a choice */
    public static void page(View root, int first) {
        if (!ready(root.getContext())) return;
        ViewGroup c = column(root);
        if (c != null) Motion.enterPage(c, first);
    }

    /** Another tab of a page was picked, by a tap or by L1 / R1: CLICK, the page built again (build, always run), and what is under the tabs (from child first on) enters */
    public static void tab(View root, int first, Runnable build) {
        boolean on = ready(root.getContext());
        if (on) Haptics.play(root, Haptics.Word.CLICK);
        build.run();
        if (on) page(root, first);
    }

    /**
     * An entry of a page's side rail (Ui.navItem): CLICK, the listener (it builds the page again with that section), then the section's pane enters: the view
     * beside the rail the entry is in now, found by the entry's label. The entry that is selected already is run as it was, with nothing
     */
    public static View.OnClickListener nav(View.OnClickListener l) {
        if (l == null) return null;
        return v -> {
            if (v.isSelected() || !ready(v.getContext())) { l.onClick(v); return; }
            Haptics.play(v, Haptics.Word.CLICK);
            CharSequence label = v instanceof TextView ? ((TextView) v).getText() : null;
            Activity a = activity(v.getContext());
            l.onClick(v);
            View now = a == null || label == null ? null : selected(a.findViewById(android.R.id.content), v, label.toString());
            if (now == null || !(now.getParent() instanceof ViewGroup) || !(now.getParent().getParent() instanceof ViewGroup)) return;
            ViewGroup rail = (ViewGroup) now.getParent(), body = (ViewGroup) rail.getParent();
            View pane = body.getChildAt(body.indexOfChild(rail) + 1);
            if (pane instanceof ViewGroup) Motion.enterPage((ViewGroup) pane, 0);
        };
    }

    private static View selected(View root, View not, String label) {
        if (root == null || root == not) return null;
        if (root instanceof TextView) return root.isSelected() && label.contentEquals(((TextView) root).getText()) ? root : null;
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) root;
        for (int i = 0; i < g.getChildCount(); i++) { View f = selected(g.getChildAt(i), not, label); if (f != null) return f; }
        return null;
    }

    /** Rows that came after their card was on screen (a list read off the main thread): the first eight fade in (ENTER), once. Not for a section that draws itself again on a progress tick */
    public static void added(ViewGroup box) {
        if (!box.isShown() || !ready(box.getContext())) return;
        for (int i = 0; i < Math.min(box.getChildCount(), 8); i++) Motion.fadeIn(box.getChildAt(i));
    }

    /** One view that was not there before (a section's buttons, once its catalog is read): it fades in (ENTER) */
    public static void appeared(View v) { if (ready(v.getContext())) Motion.fadeIn(v); }

    // ---- Dialogs ----

    private static View view(DialogInterface d) { return d instanceof Dialog && ((Dialog) d).getWindow() != null ? ((Dialog) d).getWindow().getDecorView() : null; }

    /** A choice dialog was made (PadFocus.Builder.create): a tap or A on one of its list's items is CLICK, then the dialog's own listener. The dialog opens and closes as it did */
    public static void dialog(AlertDialog d) {
        ListView list = d.getListView();
        if (list == null || !ready(d.getContext())) return;
        AdapterView.OnItemClickListener was = list.getOnItemClickListener();
        if (was != null) list.setOnItemClickListener((p, v, at, id) -> { Haptics.play(p, Haptics.Word.CLICK); was.onItemClick(p, v, at, id); });
    }

    /** A choice was picked in a dialog whose list is not PadFocus.Builder's: CLICK */
    public static void picked(DialogInterface d) {
        View v = view(d);
        if (v != null && ready(v.getContext())) Haptics.play(v, Haptics.Word.CLICK);
    }

    /** A dialog's confirm button (Delete, Remove, Reset): CLICK, then the listener. null stays null: an OK that only closes is as quiet as Cancel */
    public static DialogInterface.OnClickListener confirm(DialogInterface.OnClickListener l) {
        if (l == null) return null;
        return (d, w) -> { picked(d); l.onClick(d, w); };
    }

    // ---- What a job says when it ends (on the main thread; nothing while the activity does not show) ----

    private static void say(Context c, Haptics.Word w) {
        Activity a = activity(c);
        if (a == null || a.isFinishing() || a.isDestroyed() || a.getWindow() == null) return;
        View v = a.getWindow().getDecorView();
        if (v.getWindowVisibility() == View.VISIBLE && ready(a)) Haptics.play(v, w);
    }

    /** A short job the user started ended well (a companion or a mod installed): CLICK */
    public static void accepted(Context c) { say(c, Haptics.Word.CLICK); }

    /** The app refused, or a job failed: REJECT */
    public static void refused(Context c) { say(c, Haptics.Word.REJECT); }

    /** A long job ended well while its page shows (a build with mods): DONE */
    public static void done(Context c) { say(c, Haptics.Word.DONE); }
}
