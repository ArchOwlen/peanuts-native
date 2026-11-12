package dev.peanuts.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import dev.nxnative.android.R;
import dev.peanuts.PeanutsPrefs;
import dev.peanuts.build.BuildListener;
import dev.peanuts.build.BuildPipeline;
import dev.peanuts.build.BuildRequest;
import dev.peanuts.build.GameEnv;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Banana's Home is the library (SPEC.md): {@link #fill} is the one call MainActivity.refresh makes for its game box. It lays out the filter row (All · Ready · Build
 * needed · Not in this app version, Find, Add game), then the sections: READY (Banana's own full cards, two per row, a building game's card first and full width),
 * BUILD NEEDED (compact cards, three per row, A–Z; a tap opens the game sheet) and NOT IN THIS APP VERSION (compact, folded behind "Show N"). The games come from
 * {@link LibraryScanner} (every NSP/XCI/.nup/dump in the game folder) merged with upstream's own list: a scanned game that is ready is put into upstream's list so
 * its card is Banana's (Play, ⋯ Manage); upstream's progress card (Ondevice) is placed first, full width, as refresh() did. The build (import → Prepare → companion,
 * BuildPipeline) shows on a card of Ondevice's shape: status, a sentence, the bar, Stop and a disabled Play; during Prepare upstream's own card is that card.
 * With no game folder set, the first-run card (emptyCard's shape with three Setting rows) is Home's one card.
 */
public final class Library {
    private static final int ALL = 0, READY = 1, BUILD = 2, NO_PROFILE = 3;
    private static final String GOES_ON = " The build goes on if you leave the app.";

    /** The library of the activity that is showing (one MainActivity at a time; a recreated one gets a new instance that keeps the scan) */
    private static Library current;
    private static List<LibraryScanner.Title> lastTitles = new ArrayList<>();
    private static List<CompanionPort.Item> catalog = new ArrayList<>();
    private static boolean catalogFetched, everScanned;
    private static final Map<String, Bitmap> icons = new HashMap<>();
    private static final Set<String> listed = new HashSet<>();   // launch paths handed to upstream's list already (Upstream.addToList), so a failing add is not retried every refresh
    private static Build build;   // the running (or stopped) build, kept across activity recreation (the service runs on)
    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** A build as the card shows it */
    static final class Build {
        final int id; final String titleId, name;
        String step = "import", detail = "Starting"; int percent; boolean failed; String error = "";
        Build(int id, String titleId, String name) { this.id = id; this.titleId = titleId; this.name = name; }
    }

    private final Activity a;
    private LinearLayout box;
    private List<?> games = Collections.emptyList();
    private View ondeviceCard;
    private Predicate<Object> shows = g -> false;
    private Function<Object, View> card = g -> null;
    private Consumer<Object> manage = g -> {};
    private Runnable addGame = () -> {}, refresh = () -> {};
    private List<LibraryScanner.Title> titles = lastTitles;
    private boolean scanning;
    private int filter = ALL;
    private String find = "";
    private boolean showNoProfile;
    private final Map<String, TextView> tiles = new HashMap<>();   // the tiles of the cards showing, by title ID (icons land while they show)
    // the build card's live parts (null when it is not on screen)
    private TextView buildStatus, buildBody, buildPct;
    private ProgressBar buildBar;

    private Library(Activity a) { this.a = a; }

    private final BuildListener listener = new BuildListener() {
        @Override public void progress(String step, int percent, String detail) {
            Build b = build;
            if (b == null) return;
            boolean stepChanged = !step.equals(b.step);
            b.step = step; b.percent = percent; b.detail = detail == null ? "" : detail;
            if (stepChanged || buildStatus == null || box == null) render(); else updateBuildCard(b);
        }
        @Override public void done(boolean ok, String message) {
            Build b = build;
            if (b == null) return;
            if (ok) {
                build = null;
                Toast.makeText(a, b.name + " is ready to play.", Toast.LENGTH_LONG).show();
                scan();
            } else {
                b.failed = true; b.error = message == null ? "" : message;
                render();
            }
        }
    };

    // ---- The hook ----

    /**
     * MainActivity.refresh's game box: games is upstream's list, ondeviceCard its progress card when it shows (Ondevice.cardView), shows says whether that card is a
     * game's (Ondevice.shows), card makes a game's own card (gameCard), manage opens its Manage sheet, addGame is Add game, refresh is refresh() itself
     */
    @SuppressWarnings("unchecked")
    public static <G> void fill(Activity a, LinearLayout box, List<G> games, View ondeviceCard, Predicate<G> shows, Function<G, View> card, Consumer<G> manage, Runnable addGame, Runnable refresh) {
        if (current == null || current.a != a) current = new Library(a);
        Library l = current;
        l.box = box; l.games = new ArrayList<>(games); l.ondeviceCard = ondeviceCard;
        l.shows = (Predicate<Object>) shows; l.card = (Function<Object, View>) card; l.manage = (Consumer<Object>) manage; l.addGame = addGame; l.refresh = refresh;
        if (build != null) {
            if (BuildPipeline.running() == build.id) BuildPipeline.attach(build.id, l.listener);   // an activity made anew while the service builds
            else if (!build.failed) build = null;   // the service ended while no listener heard it
        }
        l.render();
        if (!everScanned && !l.scanning && PeanutsPrefs.gameDir(a) != null) l.scan();
    }

    /** Settings › Library › Rescan, a folder change: the folders are read again */
    static void rescan(Activity a) {
        if (current == null || current.a != a) return;
        CompanionPort.resetNames(); catalogFetched = false;
        current.scan();
    }

    /** Something the library shows changed (a folder set, keys added): drawn again, and scanned when that is new */
    static void changed(Activity a) {
        if (current == null || current.a != a) return;
        current.render();
        if (!current.scanning) current.scan();
    }

    /** The game sheet of a title (MainActivity's Manage › Build again, a compact card's tap): the scanned title, else one made from upstream's list entry */
    static void openSheet(Activity a, String titleId) {
        if (current == null || current.a != a) return;
        current.sheet(current.titleFor(titleId));
    }

    // ---- The scan ----

    private void scan() {
        if (scanning) return;
        scanning = true;
        render();
        new Thread(() -> {
            List<LibraryScanner.Title> t = new LibraryScanner(a).scan();
            List<CompanionPort.Item> c = catalogFetched ? catalog : CompanionPort.fetch();
            // a scanned game that is ready and not in upstream's list goes into it: its card is then Banana's own
            List<Upstream.Entry> list = Upstream.games(a);
            for (LibraryScanner.Title x : t) {
                if (x.status != LibraryScanner.Status.READY || x.launch == null || x.packDir == null || listedIn(list, x)) continue;
                if (!listed.add(x.launch.getPath())) continue;
                Upstream.addToList(a, x.launch, x.packDir, x.titleId, x.name);
            }
            UI.post(() -> {
                lastTitles = t; catalog = c; catalogFetched = true; everScanned = true;
                if (current != this) return;
                titles = t; scanning = false;
                if (box != null && box.isAttachedToWindow()) refresh.run(); else render();   // upstream's list again too (Prepare and the add above put games into it)
            });
            for (LibraryScanner.Title x : t) {   // the fonts and build id every ready game's env needs (GameEnv), and the icons
                if (x.status == LibraryScanner.Status.READY && x.launch != null) { GameEnv.ensureFonts(a, x.titleId); GameEnv.ensureBuildId(a, x.titleId, x.launch); }
                if (icons.containsKey(x.titleId)) continue;
                File f = LibraryScanner.icon(a, x);
                Bitmap b = f == null ? null : BitmapFactory.decodeFile(f.getPath());
                if (b == null) continue;
                UI.post(() -> { icons.put(x.titleId, b); TextView tile = tiles.get(x.titleId); if (tile != null) PUi.tileIcon(tile, b); });
            }
        }, "peanuts-scan").start();
    }

    private static boolean listedIn(List<Upstream.Entry> list, LibraryScanner.Title t) {
        for (Upstream.Entry e : list) if (samePath(e.gameDir, t.launch.getPath())) return true;
        return false;
    }

    private static final Map<String, String> canon = new HashMap<>();   // path → canonical path (a render compares every listed game with every scanned one)

    private static String canonical(String p) {
        String c = canon.get(p);
        if (c == null) {
            try { c = new File(p).getCanonicalPath(); } catch (java.io.IOException | RuntimeException e) { c = p; }
            canon.put(p, c);
        }
        return c;
    }

    private static boolean samePath(String x, String y) { return x.equals(y) || canonical(x).equals(canonical(y)); }

    /** The scanned title of an ID, else one made from upstream's list entry (a game added with Add game, outside the game folder) */
    private LibraryScanner.Title titleFor(String titleId) {
        for (LibraryScanner.Title t : titles) if (t.titleId.equalsIgnoreCase(titleId)) return t;
        for (Object g : games) {
            Upstream.Entry e = Upstream.entryOf(g);
            if (e == null || !e.titleId.equalsIgnoreCase(titleId)) continue;
            LibraryScanner.Title t = new LibraryScanner.Title(e.titleId);
            File game = new File(e.gameDir);
            t.name = e.name.isEmpty() ? e.titleId : e.name;
            t.base = new LibraryScanner.Source(game, LibraryScanner.BASE, e.titleId, e.titleId, 0, "", "");
            t.home = game.isDirectory() ? game : game.getParentFile();
            t.launch = game;
            t.packDir = e.packDir.isEmpty() ? null : new File(e.packDir);
            t.status = Upstream.hasProfile(e.titleId) ? LibraryScanner.Status.READY : LibraryScanner.Status.NO_PROFILE;
            return t;
        }
        return null;
    }

    // ---- Home ----

    /** One card of the list: upstream's game (a full card) or a scanned title (compact), or both when they are the same game */
    private static final class Row {
        final Object game; final Upstream.Entry entry; final LibraryScanner.Title title;
        Row(Object game, Upstream.Entry entry, LibraryScanner.Title title) { this.game = game; this.entry = entry; this.title = title; }
        String id() { return title != null ? title.titleId : entry.titleId; }
        String name() { return title != null ? title.name : entry.name; }
        int status() {
            if (game != null) return Upstream.hasProfile(entry.titleId) ? READY : NO_PROFILE;
            return title.status == LibraryScanner.Status.READY ? READY : title.status == LibraryScanner.Status.BUILD ? BUILD : NO_PROFILE;
        }
        long used() { return entry != null ? entry.used : 0; }
    }

    private List<Row> rows() {
        List<Row> out = new ArrayList<>();
        Set<LibraryScanner.Title> taken = new HashSet<>();
        for (Object g : games) {
            Upstream.Entry e = Upstream.entryOf(g);
            if (e == null) continue;
            LibraryScanner.Title hit = null;
            for (LibraryScanner.Title t : titles) if (t.launch != null && samePath(e.gameDir, t.launch.getPath())) { hit = t; break; }
            if (hit != null) taken.add(hit);
            out.add(new Row(g, e, hit));
        }
        for (LibraryScanner.Title t : titles) if (!taken.contains(t)) out.add(new Row(null, null, t));
        return out;
    }

    private void render() {
        if (box == null) return;
        box.removeAllViews();
        tiles.clear();
        buildStatus = null; buildBody = null; buildPct = null; buildBar = null;
        View pc = ondeviceCard;
        if (pc != null && pc.getParent() instanceof ViewGroup) ((ViewGroup) pc.getParent()).removeView(pc);
        boolean setup = PeanutsPrefs.gameDir(a) == null || !PeanutsPrefs.setupDone(a);
        if (setup) PUi.add(box, setupCard(), 0);
        List<Row> rows = rows();
        if (setup && rows.isEmpty() && pc == null) return;

        int all = rows.size(), ready = 0, toBuild = 0, noProfile = 0;
        for (Row r : rows) { switch (r.status()) { case READY: ready++; break; case BUILD: toBuild++; break; default: noProfile++; } }

        // the filter row
        LinearLayout top = PUi.row(a);
        HorizontalScrollView chips = new HorizontalScrollView(a);
        chips.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = PUi.row(a);
        chip(chipRow, "All " + all, ALL);
        chip(chipRow, "Ready " + ready, READY);
        chip(chipRow, "Build needed " + toBuild, BUILD);
        chip(chipRow, "Not in this app version " + noProfile, NO_PROFILE);
        chips.addView(chipRow, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        top.addView(chips, PUi.weight());
        Button findB = PUi.button(a, find.isEmpty() ? "Find" : "Find · " + find, PUi.Kind.QUIET, v -> findDialog());
        findB.setCompoundDrawablesRelativeWithIntrinsicBounds(PUi.icon(a, R.drawable.ic_search, R.color.bn_accent), null, null, null);
        findB.setCompoundDrawablePadding(PUi.dp(a, 6));
        LinearLayout.LayoutParams flp = PUi.wrap(); flp.leftMargin = PUi.dp(a, 8);
        top.addView(findB, flp);
        Button add = PUi.button(a, "Add game", PUi.Kind.QUIET, v -> addGame.run());
        add.setCompoundDrawablesRelativeWithIntrinsicBounds(PUi.icon(a, R.drawable.ic_add, R.color.bn_accent), null, null, null);
        add.setCompoundDrawablePadding(PUi.dp(a, 6));
        top.addView(add);
        PUi.add(box, top, setup ? 16 : 2);
        if (scanning) {
            int n = PeanutsPrefs.roots(a).size();
            PUi.add(box, PUi.small(a, "Looking in " + n + (n == 1 ? " folder…" : " folders…")), 8);
        } else if (rows.isEmpty() && pc == null && !setup) {
            PUi.add(box, PUi.small(a, "No games found in " + PeanutsPrefs.gameDir(a) + ". Put NSP, XCI, .nup files or dump folders there, then Rescan (Settings › Library)."), 8);
        }

        // the sections
        List<Row> rd = new ArrayList<>(), bd = new ArrayList<>(), np = new ArrayList<>();
        for (Row r : rows) {
            if (!find.isEmpty() && !r.name().toLowerCase(Locale.ROOT).contains(find.toLowerCase(Locale.ROOT))) continue;
            switch (r.status()) { case READY: rd.add(r); break; case BUILD: bd.add(r); break; default: np.add(r); }
        }
        Collections.sort(rd, (x, y) -> x.used() != y.used() ? Long.compare(y.used(), x.used()) : x.name().compareToIgnoreCase(y.name()));
        Collections.sort(bd, (x, y) -> x.name().compareToIgnoreCase(y.name()));
        Collections.sort(np, (x, y) -> x.name().compareToIgnoreCase(y.name()));

        Build b = build;
        boolean buildCard = b != null && !(b.step.equals("prepare") && pc != null && !b.failed);   // during Prepare upstream's own card is the card
        if (filter == ALL || filter == READY) {
            List<View> full = new ArrayList<>();
            if (pc != null) full.add(pc);
            if (buildCard) full.add(buildCard(b));
            List<View> cards = new ArrayList<>();
            for (Row r : rd) {
                if (r.game != null && shows.test(r.game)) continue;   // its card is the progress card
                if (b != null && buildCard && r.id().equalsIgnoreCase(b.titleId)) continue;   // its card is the build card
                cards.add(r.game != null ? fullCard(r) : compactCard(r.title, "Ready", R.color.bn_leaf));
            }
            if (!full.isEmpty() || !cards.isEmpty()) {
                PUi.add(box, PUi.section(a, "Ready" + (rd.isEmpty() ? "" : " · " + rd.size())), 20);
                for (View v : full) PUi.add(box, v, 10);
                grid(cards, fullColumns());
            }
        }
        if (filter == ALL || filter == BUILD) {
            List<View> cards = new ArrayList<>();
            for (Row r : bd) { if (b != null && buildCard && r.id().equalsIgnoreCase(b.titleId)) continue; cards.add(compactCard(r.title, "Build needed", R.color.bn_accent)); }
            if (!cards.isEmpty()) { PUi.add(box, PUi.section(a, "Build needed · " + bd.size()), 20); grid(cards, compactColumns()); }
        }
        if ((filter == ALL || filter == NO_PROFILE) && !np.isEmpty()) {
            PUi.add(box, PUi.section(a, "Not in this app version · " + np.size()), 20);
            if (filter == NO_PROFILE || showNoProfile) {
                List<View> cards = new ArrayList<>();
                for (Row r : np) {
                    if (r.game != null) { Object g = r.game; cards.add(compactCard(r.name(), r.id(), "Not in this app version", R.color.bn_danger, v -> manage.accept(g))); }   // upstream's own Manage sheet: the sentence, Remove from list
                    else cards.add(compactCard(r.title, "Not in this app version", R.color.bn_danger));
                }
                grid(cards, compactColumns());
            } else {
                PUi.add(box, PUi.button(a, "Show " + np.size(), PUi.Kind.QUIET, v -> { showNoProfile = true; render(); }), 6, PUi.wrap());
            }
        }
    }

    private void chip(LinearLayout row, String label, int which) {
        boolean on = filter == which;
        Button c = PUi.button(a, label, on ? PUi.Kind.PRIMARY : PUi.Kind.TONAL, v -> { filter = which; showNoProfile = false; render(); });
        LinearLayout.LayoutParams lp = PUi.wrap();
        if (row.getChildCount() > 0) lp.leftMargin = PUi.dp(a, 8);
        row.addView(c, lp);
    }

    private void findDialog() {
        EditText e = new EditText(a);
        e.setHint("A game's name");
        e.setText(find);
        e.setSingleLine(true);
        e.setSelection(find.length());
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(e, PUi.fill());
        AlertDialog d = PUi.sheet(a, "Find", wrap).setPositiveButton("Find", (x, w) -> { find = e.getText().toString().trim(); render(); })
                .setNegativeButton("Cancel", null).setNeutralButton(find.isEmpty() ? null : "Clear", (x, w) -> { find = ""; render(); }).create();
        e.setOnEditorActionListener((v, id, ev) -> { find = e.getText().toString().trim(); d.dismiss(); render(); return true; });
        d.show();
        e.requestFocus();
    }

    private int widthDp() { Configuration c = a.getResources().getConfiguration(); return c.screenWidthDp; }
    private int fullColumns() { return widthDp() >= 700 ? 2 : 1; }
    private int compactColumns() { return widthDp() >= 800 ? 3 : widthDp() >= 540 ? 2 : 1; }

    /** Cards in rows of cols weighted cells, 12 dp apart; an odd last card keeps its cell's width (empty cells fill the rest) */
    private void grid(List<View> cards, int cols) {
        for (int i = 0; i < cards.size(); i += cols) {
            LinearLayout r = new LinearLayout(a);
            r.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = 0; k < cols; k++) {
                View v = i + k < cards.size() ? cards.get(i + k) : new View(a);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                if (k > 0) lp.leftMargin = PUi.dp(a, PUi.GAP);
                r.addView(v, lp);
            }
            PUi.add(box, r, i == 0 ? 10 : PUi.GAP);
        }
    }

    /** Upstream's own card for a listed game, its tile showing the scanned icon (the NACP's, LibraryScanner.icon) in place of the letter when there is one */
    private View fullCard(Row r) {
        View v = card.apply(r.game);
        TextView tile = tileOf(v);
        if (tile != null) {
            tiles.put(r.id(), tile);
            Bitmap b = icons.get(r.id());
            if (b != null) PUi.tileIcon(tile, b);
        }
        return v;
    }

    /** The tile of upstream's game card (gameCard: the card's first row's first child, Ui.tile's 52 dp TextView) */
    private TextView tileOf(View card) {
        if (!(card instanceof ViewGroup) || ((ViewGroup) card).getChildCount() == 0) return null;
        View top = ((ViewGroup) card).getChildAt(0);
        if (!(top instanceof ViewGroup) || ((ViewGroup) top).getChildCount() == 0) return null;
        View t = ((ViewGroup) top).getChildAt(0);
        return t instanceof TextView && t.getLayoutParams() != null && t.getLayoutParams().width == PUi.dp(a, 52) ? (TextView) t : null;
    }

    private View compactCard(LibraryScanner.Title t, String state, int colorRes) { return compactCard(t.name, t.titleId, state, colorRes, v -> sheet(t)); }

    /** A game that is not playable yet (or not in this app): its tile, name over its state, and a chevron; the whole card opens the sheet (or, for a listed game without a profile, its Manage) */
    private View compactCard(String name, String titleId, String state, int colorRes, View.OnClickListener open) {
        LinearLayout c = PUi.card(a);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        int p = PUi.dp(a, 12);
        c.setPadding(p, p, p, p);
        c.setMinimumHeight(PUi.dp(a, 80));
        c.setBackground(PUi.ripple(a, PUi.round(PUi.col(a, R.color.bn_card), PUi.dp(a, 20), PUi.col(a, R.color.bn_card_stroke), PUi.dp(a, 1))));
        c.setClickable(true);
        c.setFocusable(true);
        c.setOnClickListener(open);
        c.setOnLongClickListener(v -> { open.onClick(v); return true; });
        c.setContentDescription(name + ", " + state);
        TextView tile = PUi.tile(a, name, 44);
        c.addView(tile);
        Bitmap b = icons.get(titleId);
        if (b != null) PUi.tileIcon(tile, b);
        tiles.put(titleId, tile);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(PUi.dp(a, 12), 0, PUi.dp(a, 6), 0);
        TextView n = PUi.text(a, name, 15, R.color.bn_text, 600);
        n.setMaxLines(2);
        n.setEllipsize(TextUtils.TruncateAt.END);
        n.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        n.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        col.addView(n);
        TextView st = PUi.status(a, state, colorRes);
        st.setPadding(0, PUi.dp(a, 2), 0, 0);
        col.addView(st);
        c.addView(col, PUi.weight());
        ImageView ch = new ImageView(a);
        ch.setImageDrawable(PUi.icon(a, R.drawable.ic_chevron, R.color.bn_text_2));
        ch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        c.addView(ch, new LinearLayout.LayoutParams(PUi.dp(a, 20), PUi.dp(a, 20)));
        return c;
    }

    // ---- The build card (Ondevice's shape) ----

    private static String stepWords(Build b) {
        if (b.failed) return "Build stopped";
        switch (b.step) {
            case "prepare": return "Building · preparing the game";
            case "companion": return "Building · installing the companion";
            case "done": return "Ready";
            default: return "Building · importing the game";
        }
    }

    private View buildCard(Build b) {
        LibraryScanner.Title t = titleFor(b.titleId);
        String name = t != null ? t.name : b.name;
        LinearLayout c = PUi.card(a);
        int p16 = PUi.dp(a, 16);
        c.setPadding(p16, p16, p16, p16);
        LinearLayout top = PUi.row(a);
        TextView tile = PUi.tile(a, name, 52);
        Bitmap bm = icons.get(b.titleId);
        if (bm != null) PUi.tileIcon(tile, bm);
        tiles.put(b.titleId, tile);
        top.addView(tile);
        LinearLayout info = new LinearLayout(a);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(PUi.dp(a, 14), 0, PUi.dp(a, 4), 0);
        TextView n = PUi.text(a, name, 17, R.color.bn_text, 700);
        n.setMaxLines(2);
        n.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(n);
        String about = t == null ? "" : t.versionText() + (t.base != null && !t.base.publisher.isEmpty() ? (t.versionText().isEmpty() ? "" : " · ") + t.base.publisher : "");
        if (!about.isEmpty()) info.addView(PUi.small(a, about));
        buildStatus = PUi.status(a, "", R.color.bn_accent);
        buildStatus.setPadding(0, PUi.dp(a, 2), 0, 0);
        info.addView(buildStatus);
        top.addView(info, PUi.weight());
        top.addView(PUi.more(a, "Manage " + name, v -> { if (t != null) sheet(t); }));
        c.addView(top);
        buildBody = PUi.body(a, "");
        PUi.add(c, buildBody, 12);
        buildBar = PUi.progress(a);
        buildBar.setMax(100);
        PUi.add(c, buildBar, 10);
        buildPct = PUi.small(a, "");
        PUi.add(c, buildPct, 6);
        PUi.Details details = PUi.details(a, b.failed ? b.error : "");
        PUi.add(c, details, 4);
        View left = b.failed
                ? PUi.button(a, "Retry", PUi.Kind.TONAL, v -> { if (t != null) sheet(t); })
                : PUi.button(a, "Stop", PUi.Kind.TONAL, v -> PUi.confirmDanger(a, "Stop the build?", "The build of " + name + " stops. What is written so far stays, and the next Build picks up what it can.", "Stop", () -> BuildPipeline.cancel(a)));
        PUi.Action play = PUi.action(a, R.drawable.ic_play, "Play", PUi.Kind.PRIMARY, null);
        play.setEnabled(false);
        PUi.add(c, PUi.pair(a, left, play), 14);
        updateBuildCard(b);
        return c;
    }

    private void updateBuildCard(Build b) {
        if (buildStatus == null) return;
        PUi.status(buildStatus, stepWords(b), b.failed ? R.color.bn_danger : b.step.equals("done") ? R.color.bn_leaf : R.color.bn_accent);
        if (b.failed) {
            String first = b.error.split("\n")[0].trim();
            buildBody.setText((first.isEmpty() ? "The build stopped." : first) + " Tap Retry to open the sheet and build again.");
            buildBar.setVisibility(View.GONE);
            buildPct.setVisibility(View.GONE);
        } else {
            String d = b.detail.trim();
            buildBody.setText((d.isEmpty() ? "Working" : d.endsWith(".") ? d.substring(0, d.length() - 1) : d) + "." + GOES_ON);
            buildBar.setVisibility(View.VISIBLE);
            buildBar.setProgress(Math.max(0, Math.min(100, b.percent)));
            buildPct.setVisibility(View.VISIBLE);
            buildPct.setText(b.percent + " %");
        }
    }

    // ---- The sheet and the build ----

    private void sheet(LibraryScanner.Title t) {
        if (t == null) return;
        GameSheet.show(a, t, CompanionPort.forTitle(catalog, t.titleId), this);
    }

    /** Whether this title is being built now */
    boolean building(String titleId) { Build b = build; return b != null && !b.failed && b.titleId.equalsIgnoreCase(titleId); }

    /** The sheet's Build: the state to the pipeline; the card shows the rest. Returns why it could not start, or null */
    String startBuild(GameSheet.State s) {
        if (BuildPipeline.running() != 0) return "A build is running now. Wait for it to finish.";
        BuildRequest r = new BuildRequest();
        r.titleId = s.titleId; r.name = s.name; r.base = s.base; r.update = s.update;
        r.dlcs = new ArrayList<>(s.dlcs);
        String root = PeanutsPrefs.gameDir(a);
        r.gameDir = root == null ? null : new File(root);
        r.companionPackage = s.installCompanion ? s.companionPackage : null;
        int id = BuildPipeline.start(a, r, new BuildListener() {   // a refusal comes back through this (id 0); a started build's listener is attached below
            @Override public void progress(String step, int percent, String detail) { listener.progress(step, percent, detail); }
            @Override public void done(boolean ok, String message) {
                if (build != null) { listener.done(ok, message); return; }
                new AlertDialog.Builder(a).setTitle("Build").setMessage(message).setPositiveButton("OK", null).show();
            }
        });
        if (id == 0) return null;   // the pipeline says why, in its own dialog
        build = new Build(id, s.titleId, s.name);
        BuildPipeline.attach(id, listener);
        render();
        return null;
    }

    // ---- First run ----

    /** emptyCard's shape: the badge, a heading, the three steps, then the three Setting rows on the card itself and the action row */
    private View setupCard() {
        LinearLayout c = PUi.card(a);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(PUi.dp(a, 20), PUi.dp(a, 28), PUi.dp(a, 20), PUi.dp(a, 20));
        ImageView im = new ImageView(a);
        im.setImageResource(R.drawable.ic_banana_happy);
        im.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        c.addView(im, new LinearLayout.LayoutParams(PUi.dp(a, 80), PUi.dp(a, 80)));
        TextView h = PUi.heading(a, "Set up your games");
        h.setGravity(Gravity.CENTER);
        PUi.add(c, h, 12, PUi.wrap());
        TextView t = PUi.small(a, "Keys, firmware and folders stay on this device. Nothing is uploaded.");
        t.setGravity(Gravity.CENTER);
        PUi.add(c, t, 6, PUi.wrap());
        LinearLayout steps = PUi.row(a);
        steps.setGravity(Gravity.CENTER);
        String[] s = {"1  Keys", "2  Game folder", "3  Show my games"};
        for (int k = 0; k < s.length; k++) {
            LinearLayout.LayoutParams lp = PUi.wrap();
            if (k > 0) lp.leftMargin = PUi.dp(a, 6);
            TextView chip = PUi.chip(a, s[k]);
            chip.setSingleLine(true);
            steps.addView(chip, lp);
        }
        PUi.add(c, steps, 16, PUi.wrap());
        boolean keys = PeanutsPrefs.hasKeys(a);
        String game = PeanutsPrefs.gameDir(a);
        PUi.add(c, new PUi.Setting(a, "🔑", "Keys", v -> LibrarySettings.pickKeys(a))
                .value(keys ? "Added: prod.keys is in the app." : "Optional. Needed for NSP and XCI files; a .nup or a dump folder plays without them.").chevron(a).view, 16);
        PUi.add(c, new PUi.Setting(a, "📁", "Game folder", v -> LibrarySettings.pickFolder(a, LibrarySettings.REQ_GAME, "Pick your game folder"))
                .value(game != null ? game : "Required. Every NSP, XCI, .nup and dump folder inside is shown on this screen.").chevron(a).view, 0);
        PUi.add(c, new PUi.Setting(a, "🔤", "Firmware fonts", v -> LibrarySettings.pickFolder(a, LibrarySettings.REQ_FIRMWARE, "Pick your firmware folder"))
                .value(LibrarySettings.fontsValue(a, "Optional. Lets companions draw the system font.")).chevron(a).view, 0);
        PUi.Action show = PUi.action(a, R.drawable.ic_add, "Show my games", PUi.Kind.PRIMARY, v -> {
            if (PeanutsPrefs.gameDir(a) == null) { Toast.makeText(a, "Pick the game folder first", Toast.LENGTH_LONG).show(); return; }
            PeanutsPrefs.setupDone(a, true);
            changed(a);
        });
        show.setEnabled(game != null);
        LinearLayout actions = PUi.row(a);
        actions.addView(show, PUi.weight());
        LinearLayout.LayoutParams lp = PUi.wrap();
        lp.leftMargin = PUi.dp(a, 10);
        actions.addView(PUi.button(a, "Update and DLC folders…", PUi.Kind.QUIET, v -> LibrarySettings.extraFolders(a)), lp);
        PUi.add(c, actions, 16);
        return c;
    }
}
