package dev.peanuts.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
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
 * Banana's Home is the library (SPEC.md, SPEC3.md): {@link #fill} is the one call MainActivity.refresh makes for its game box. It lays out the filter row (All ·
 * Building · Ready · Build needed · Not in this app version, Find), then one cover grid (Eden's shape in Banana's clothes: PUi.tile at cell size with the NACP icon,
 * the name under it, the state on the cover, four columns on a handheld), ordered Building → Ready by last played → Build needed A–Z → Not in this app version A–Z,
 * no sections. A tap plays a ready game (Banana's launch) or opens the game sheet; a long press (or Y) opens Banana's Manage sheet for a listed game. The Building
 * filter exists only while something runs and holds the full cards: the build card (Ondevice's shape: status, a sentence, the bar, Stop/Retry and a disabled Play)
 * and upstream's progress card (Ondevice); everywhere else the building game is a cover with a progress strip and a "Building · N %" chip that opens that filter.
 * The games come from {@link LibraryScanner} (every NSP/XCI/.nup/dump in the game folder) merged with upstream's own list: a scanned game that is ready is put
 * into upstream's list so Play and Manage are Banana's. With no game folder set, the first-run card (emptyCard's shape with three Setting rows) is Home's one card.
 */
public final class Library {
    private static final int ALL = 0, BUILDING = 1, READY = 2, BUILD = 3, NO_PROFILE = 4;
    private static final int[] FILTERS = {ALL, BUILDING, READY, BUILD, NO_PROFILE};
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
    private Runnable refresh = () -> {};
    private List<LibraryScanner.Title> titles = lastTitles;
    private boolean scanning;
    private int filter = ALL;
    private String find = "";
    private final Map<String, TextView> tiles = new HashMap<>();   // the tiles of the covers showing, by title ID (icons land while they show)
    private final Map<String, Boolean> companions = new HashMap<>();   // title ID → a companion is installed (read once per pass; cleared by a scan and a build's end)
    // the build card's live parts (null when it is not on screen)
    private TextView buildStatus, buildBody, buildPct;
    private ProgressBar buildBar;
    // the building game's cover marks (null when the Building filter shows the card instead)
    private TextView coverChip;
    private ProgressBar coverStrip;
    private View firstCover;   // the grid's first cover of this render (focused after a shoulder-button filter change)
    private boolean focusGrid;

    private Library(Activity a) { this.a = a; }

    private final BuildListener listener = new BuildListener() {
        @Override public void progress(String step, int percent, String detail) {
            Build b = build;
            if (b == null) return;
            boolean stepChanged = !step.equals(b.step);
            b.step = step; b.percent = percent; b.detail = detail == null ? "" : detail;
            if (stepChanged || (buildStatus == null && coverChip == null) || box == null) render(); else updateBuildCard(b);
        }
        @Override public void done(boolean ok, String message) {
            Build b = build;
            if (b == null) return;
            if (ok) {
                build = null; companions.clear();
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
     * game's (Ondevice.shows), card makes a game's own card (gameCard; unused since the covers, kept for the hook's shape), manage opens its Manage sheet, refresh is refresh() itself
     */
    @SuppressWarnings("unchecked")
    public static <G> void fill(Activity a, LinearLayout box, List<G> games, View ondeviceCard, Predicate<G> shows, Function<G, View> card, Consumer<G> manage, Runnable refresh) {
        if (current == null || current.a != a) current = new Library(a);
        Library l = current;
        l.box = box; l.games = new ArrayList<>(games); l.ondeviceCard = ondeviceCard;
        l.shows = (Predicate<Object>) shows; l.card = (Function<Object, View>) card; l.manage = (Consumer<Object>) manage; l.refresh = refresh;
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

    /** The game sheet of a title (MainActivity's Manage › Build again, a cover's tap): the scanned title, else one made from upstream's list entry */
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
                titles = t; scanning = false; companions.clear();
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

    /** One cover of the grid: upstream's game or a scanned title, or both when they are the same game */
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

    /** Whether this row's game is being built now: upstream's Prepare shows its card for it, or the Peanuts build (running, or stopped until retried) is its */
    private boolean isBuilding(Row r) {
        if (r.game != null && shows.test(r.game)) return true;
        Build b = build;
        return b != null && r.id().equalsIgnoreCase(b.titleId);
    }

    private void render() {
        if (box == null) return;
        box.removeAllViews();
        tiles.clear();
        buildStatus = null; buildBody = null; buildPct = null; buildBar = null; coverChip = null; coverStrip = null; firstCover = null;
        View pc = ondeviceCard;
        if (pc != null && pc.getParent() instanceof ViewGroup) ((ViewGroup) pc.getParent()).removeView(pc);
        boolean setup = PeanutsPrefs.gameDir(a) == null || !PeanutsPrefs.setupDone(a);
        if (setup) PUi.add(box, setupCard(), 0);
        List<Row> rows = rows();
        if (setup && rows.isEmpty() && pc == null) return;

        Build b = build;
        boolean buildCard = b != null && !(b.step.equals("prepare") && pc != null && !b.failed);   // during Prepare upstream's own card is the card
        int building = (pc != null ? 1 : 0) + (buildCard ? 1 : 0);
        if (building == 0 && filter == BUILDING) filter = ALL;   // the last job finished: the filter goes away
        int all = rows.size(), ready = 0, toBuild = 0, noProfile = 0;
        List<Row> bl = new ArrayList<>(), rd = new ArrayList<>(), bd = new ArrayList<>(), np = new ArrayList<>();
        for (Row r : rows) {
            boolean shown = find.isEmpty() || r.name().toLowerCase(Locale.ROOT).contains(find.toLowerCase(Locale.ROOT));
            if (isBuilding(r)) { if (shown) bl.add(r); continue; }   // the other filters never show it
            switch (r.status()) {
                case READY: ready++; if (shown) rd.add(r); break;
                case BUILD: toBuild++; if (shown) bd.add(r); break;
                default: noProfile++; if (shown) np.add(r);
            }
        }
        Collections.sort(bl, (x, y) -> Boolean.compare(y.game != null && shows.test(y.game), x.game != null && shows.test(x.game)));   // upstream's Prepare first, like its card
        Collections.sort(rd, (x, y) -> x.used() != y.used() ? Long.compare(y.used(), x.used()) : x.name().compareToIgnoreCase(y.name()));
        Collections.sort(bd, (x, y) -> x.name().compareToIgnoreCase(y.name()));
        Collections.sort(np, (x, y) -> x.name().compareToIgnoreCase(y.name()));

        // the filter row
        LinearLayout top = PUi.row(a);
        HorizontalScrollView chips = new HorizontalScrollView(a);
        chips.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = PUi.row(a);
        chip(chipRow, "All " + all, ALL);
        if (building > 0) {
            SpannableString s = new SpannableString("Building " + building);
            if (filter != BUILDING) s.setSpan(new ForegroundColorSpan(PUi.col(a, R.color.bn_banana)), 9, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);   // the count in banana: something runs
            chip(chipRow, s, BUILDING);
        }
        chip(chipRow, "Ready " + ready, READY);
        chip(chipRow, "Build needed " + toBuild, BUILD);
        chip(chipRow, "Not in this app version " + noProfile, NO_PROFILE);
        chips.addView(chipRow, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        top.addView(chips, PUi.weight());
        Button findB = PUi.button(a, find.isEmpty() ? "Find" : "Find · " + find, PUi.Kind.QUIET, v -> findDialog());
        findB.setCompoundDrawablesRelativeWithIntrinsicBounds(PUi.icon(a, R.drawable.ic_search, R.color.bn_accent), null, null, null);
        findB.setCompoundDrawablePadding(PUi.dp(a, 6));
        findB.setOnKeyListener(shoulders);
        LinearLayout.LayoutParams flp = PUi.wrap(); flp.leftMargin = PUi.dp(a, 8);
        top.addView(findB, flp);
        PUi.add(box, top, setup ? 16 : 2);
        if (scanning) {
            int n = PeanutsPrefs.roots(a).size();
            PUi.add(box, PUi.small(a, "Looking in " + n + (n == 1 ? " folder…" : " folders…")), 8);
        } else if (rows.isEmpty() && pc == null && !setup) {
            PUi.add(box, PUi.small(a, "No games found in " + PeanutsPrefs.gameDir(a) + ". Put NSP, XCI, .nup files or dump folders there, then Rescan (Settings › Library)."), 8);
        }

        // the Building filter: the full cards, two per row
        if (filter == BUILDING) {
            List<View> full = new ArrayList<>();
            if (pc != null) full.add(pc);
            if (buildCard) full.add(buildCard(b));
            grid(full, fullColumns(), PUi.GAP);
            PUi.add(box, PUi.small(a, "When a build finishes, its game moves to Ready and this filter goes away."), 10);
            focusAfterRender(chipRow);
            return;
        }

        // the cover grid: one grid, the filters are the sections
        List<Row> show = new ArrayList<>();
        switch (filter) {
            case READY: show.addAll(rd); break;
            case BUILD: show.addAll(bd); break;
            case NO_PROFILE: show.addAll(np); break;
            default: show.addAll(bl); show.addAll(rd); show.addAll(bd); show.addAll(np);
        }
        int cols = coverColumns();
        int cell = (Math.min(widthDp(), 900) - 40 - PUi.GAP * (cols - 1)) / cols;   // Banana's 20 dp gutters and 12 dp gaps
        List<View> cells = new ArrayList<>();
        for (Row r : show) cells.add(coverCell(r, cell));
        grid(cells, cols, 16);
        focusAfterRender(chipRow);
    }

    /** After a shoulder-button filter change the first cover (else the chosen chip) takes the focus, so the controller stays in the grid */
    private void focusAfterRender(LinearLayout chipRow) {
        if (!focusGrid) return;
        focusGrid = false;
        View f = firstCover;
        if (f == null) for (int i = 0; i < chipRow.getChildCount(); i++) if (chipRow.getChildAt(i).isSelected()) f = chipRow.getChildAt(i);
        if (f == null && chipRow.getChildCount() > 0) f = chipRow.getChildAt(0);
        if (f != null) { View v = f; v.post(v::requestFocus); }
    }

    private void chip(LinearLayout row, CharSequence label, int which) {
        boolean on = filter == which;
        Button c = PUi.button(a, label, on ? PUi.Kind.PRIMARY : PUi.Kind.TONAL, v -> { filter = which; render(); });
        c.setSelected(on);
        c.setOnKeyListener(shoulders);
        LinearLayout.LayoutParams lp = PUi.wrap();
        if (row.getChildCount() > 0) lp.leftMargin = PUi.dp(a, 8);
        row.addView(c, lp);
    }

    /** L/R shoulder on any of the library's own focusable views (covers, chips, Find): the previous/next filter chip */
    private final View.OnKeyListener shoulders = (v, code, ev) -> {
        if (ev.getAction() != KeyEvent.ACTION_DOWN || ev.getRepeatCount() != 0) return false;
        if (code == KeyEvent.KEYCODE_BUTTON_L1) { stepFilter(-1); return true; }
        if (code == KeyEvent.KEYCODE_BUTTON_R1) { stepFilter(1); return true; }
        return false;
    };

    private void stepFilter(int d) {
        Build b = build;
        boolean buildCard = b != null && !(b.step.equals("prepare") && ondeviceCard != null && !b.failed);
        boolean hasBuilding = ondeviceCard != null || buildCard;
        List<Integer> avail = new ArrayList<>();
        for (int f : FILTERS) if (f != BUILDING || hasBuilding) avail.add(f);
        int i = Math.max(0, avail.indexOf(filter));
        filter = avail.get(((i + d) % avail.size() + avail.size()) % avail.size());
        focusGrid = true;
        render();
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
    private int coverColumns() { return widthDp() >= 700 ? 4 : widthDp() >= 540 ? 3 : 2; }

    /** Views in rows of cols weighted cells, 12 dp apart, rowGap dp between rows; an odd last cell keeps its width (empty cells fill the rest) */
    private void grid(List<View> cards, int cols, int rowGap) {
        for (int i = 0; i < cards.size(); i += cols) {
            LinearLayout r = new LinearLayout(a);
            r.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = 0; k < cols; k++) {
                View v = i + k < cards.size() ? cards.get(i + k) : new View(a);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                if (k > 0) lp.leftMargin = PUi.dp(a, PUi.GAP);
                r.addView(v, lp);
            }
            PUi.add(box, r, i == 0 ? 10 : rowGap);
        }
    }

    // ---- The covers ----

    /**
     * One cell of the grid: the cover (PUi's tile at cell size, the NACP icon once it has landed) with the state on it, the name under it. The cover is the focusable,
     * clickable thing: a tap plays a ready game, opens the Building filter for a building one, the sheet or Manage otherwise; a long press (or Y) opens Manage
     * (listed) or the sheet (scanned); a 3 dp banana ring and a banana name show the focus
     */
    private View coverCell(Row r, int cellDp) {
        int st = r.status();
        Build b = build;
        boolean building = isBuilding(r);
        boolean ownBuild = building && b != null && r.id().equalsIgnoreCase(b.titleId);   // the Peanuts build's game (else upstream's Prepare: no percent to show)
        boolean stopped = ownBuild && b.failed;
        String name = r.name();
        boolean companion = companionInstalled(r);
        String state = building ? (stopped ? "Build stopped" : ownBuild ? "Building · " + b.percent + " %" : "Building · preparing")
                : st == READY ? "Ready" : st == BUILD ? "Build needed" : "Not in this app version";

        LinearLayout cell = new LinearLayout(a);
        cell.setOrientation(LinearLayout.VERTICAL);
        FrameLayout cover = new FrameLayout(a);
        int p4 = PUi.dp(a, 4), p8 = PUi.dp(a, 8);
        cover.setPadding(p4, p4, p4, p4);
        cover.setClickable(true);
        cover.setFocusable(true);
        cover.setContentDescription(name + ", " + state + (companion ? ", companion installed" : ""));
        // the ripple under the cover (masked to its 16 dp corners) and the focus ring, 3 dp outside the cover's edge
        StateListDrawable ring = new StateListDrawable();
        ring.addState(new int[] {android.R.attr.state_focused}, PUi.round(Color.TRANSPARENT, PUi.dp(a, 20), PUi.col(a, R.color.bn_banana), PUi.dp(a, 3)));
        ring.addState(new int[0], new ColorDrawable(Color.TRANSPARENT));
        RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(0x336B4423), null, new InsetDrawable(PUi.round(Color.BLACK, PUi.dp(a, 16), 0, 0), p4));
        cover.setForeground(new LayerDrawable(new Drawable[] {ripple, ring}));

        int coverDp = cellDp - 8;   // the cell less the 4 dp the focus ring needs on each side: the cover stays square
        TextView tile = PUi.tile(a, name, coverDp);
        tile.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, PUi.dp(a, coverDp)));
        Bitmap bm = icons.get(r.id());
        if (bm != null) PUi.tileIcon(tile, bm);
        if (!building && st == NO_PROFILE) tile.setAlpha(0.45f);
        tiles.put(r.id(), tile);
        cover.addView(tile);

        // the state on the cover
        if (building) {
            coverChip = mark(state, PUi.col(a, R.color.bn_card), stopped ? R.color.bn_danger : R.color.bn_accent, true, Gravity.TOP | Gravity.START);
            cover.addView(coverChip);
            coverStrip = strip(!ownBuild);
            if (stopped) coverStrip.setVisibility(View.GONE); else if (ownBuild) coverStrip.setProgress(Math.max(0, Math.min(100, b.percent)));
            cover.addView(coverStrip);
        } else if (st == READY) {
            TextView ok = PUi.text(a, "✓", 12, R.color.bn_on_leaf, 700);
            ok.setGravity(Gravity.CENTER);
            ok.setIncludeFontPadding(false);
            ok.setBackground(PUi.round(PUi.col(a, R.color.bn_leaf), PUi.dp(a, 999), 0, 0));
            ok.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(PUi.dp(a, 22), PUi.dp(a, 22), Gravity.TOP | Gravity.END);
            lp.setMargins(p8, p8, p8, p8);
            cover.addView(ok, lp);
        } else if (st == BUILD) {
            cover.addView(mark("Build", PUi.col(a, R.color.bn_banana), R.color.bn_on_banana, false, Gravity.TOP | Gravity.START));
        } else {
            cover.addView(mark("Not in this app version", PUi.col(a, R.color.bn_danger_tint), R.color.bn_danger, false, Gravity.TOP | Gravity.START));
        }
        if (companion) cover.addView(companionBadge(building && !stopped));

        // the name
        int nameColor = !building && st == NO_PROFILE ? R.color.bn_text_2 : R.color.bn_text;
        TextView n = PUi.text(a, name, 14, nameColor, 600);
        n.setMaxLines(2);
        n.setEllipsize(TextUtils.TruncateAt.END);
        n.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        n.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        n.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams nlp = PUi.fill();
        nlp.setMargins(p4, p4, p4, 0);
        cover.setOnFocusChangeListener((v, f) -> n.setTextColor(PUi.col(a, f ? R.color.bn_banana : nameColor)));

        // tap, long press, Y, shoulders
        Runnable tap, hold;
        Runnable manageOrSheet = r.game != null ? () -> manage.accept(r.game) : () -> sheet(r.title);
        if (building) { tap = () -> { filter = BUILDING; render(); }; hold = manageOrSheet; }
        else if (st == READY) { tap = r.game != null ? () -> play(r) : () -> sheet(r.title); hold = manageOrSheet; }
        else if (st == BUILD) { tap = () -> sheet(r.title); hold = tap; }
        else { tap = manageOrSheet; hold = manageOrSheet; }   // listed: Banana's Manage (the sentence, Remove from list); scanned: the sheet (Profile.missing's sentence)
        cover.setOnClickListener(v -> tap.run());
        cover.setOnLongClickListener(v -> { hold.run(); return true; });
        cover.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0 && code == KeyEvent.KEYCODE_BUTTON_Y) { hold.run(); return true; }
            return shoulders.onKey(v, code, ev);
        });

        cell.addView(cover, PUi.fill());
        cell.addView(n, nlp);
        if (firstCover == null) firstCover = cover;
        return cell;
    }

    /** Play for a listed, ready game: Banana's own launch (the pack search, the checks, its dialogs); its Manage sheet if that cannot be reached */
    private void play(Row r) { if (!Upstream.launch(a, r.game)) manage.accept(r.game); }

    /** A pill on the cover, 8 dp in from its corner: 12 sp 700, a 1 dp shadow so it reads on any art */
    private TextView mark(String s, int fill, int textRes, boolean stroke, int gravity) {
        TextView t = PUi.text(a, s, 12, textRes, 700);
        t.setSingleLine(true);
        t.setBackground(PUi.round(fill, PUi.dp(a, 999), stroke ? PUi.col(a, R.color.bn_card_stroke) : 0, stroke ? Math.max(1, PUi.dp(a, 1)) : 0));
        t.setPadding(PUi.dp(a, 10), PUi.dp(a, 5), PUi.dp(a, 10), PUi.dp(a, 5));
        t.setElevation(PUi.dp(a, 1));
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
        int p8 = PUi.dp(a, 8);
        lp.setMargins(p8, p8, p8, p8);
        t.setLayoutParams(lp);
        return t;
    }

    /** The building cover's 6 dp progress strip along the bottom edge, 8 dp inset: banana deep on 70 % of the screen's colour; indeterminate for upstream's Prepare */
    private ProgressBar strip(boolean indeterminate) {
        ProgressBar p = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        p.setMax(100);
        p.setIndeterminate(indeterminate);
        int track = (PUi.col(a, R.color.bn_bg) & 0x00FFFFFF) | 0xB3000000;
        GradientDrawable fill = PUi.round(PUi.col(a, R.color.bn_banana_deep), PUi.dp(a, 3), 0, 0);
        LayerDrawable ld = new LayerDrawable(new Drawable[] {PUi.round(track, PUi.dp(a, 3), 0, 0), new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)});
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        p.setProgressDrawable(ld);
        p.setIndeterminateTintList(ColorStateList.valueOf(PUi.col(a, R.color.bn_banana_deep)));
        p.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, PUi.dp(a, 6), Gravity.BOTTOM);
        int p8 = PUi.dp(a, 8);
        lp.setMargins(p8, 0, p8, p8);
        p.setLayoutParams(lp);
        return p;
    }

    /** The green two-screen badge, bottom-right of a cover whose game has a companion installed (above the strip while it builds) */
    private View companionBadge(boolean aboveStrip) {
        LinearLayout v = new LinearLayout(a);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER);
        v.setBackground(PUi.round(PUi.col(a, R.color.bn_leaf), PUi.dp(a, 6), 0, 0));
        v.setElevation(PUi.dp(a, 1));
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        for (int i = 0; i < 2; i++) {
            View s = new View(a);
            s.setBackground(PUi.round(PUi.col(a, R.color.bn_on_leaf), PUi.dp(a, 1), 0, 0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(PUi.dp(a, 12), PUi.dp(a, 5));
            if (i > 0) lp.topMargin = PUi.dp(a, 2);
            v.addView(s, lp);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(PUi.dp(a, 22), PUi.dp(a, 22), Gravity.BOTTOM | Gravity.END);
        int p8 = PUi.dp(a, 8);
        lp.setMargins(p8, p8, p8, aboveStrip ? PUi.dp(a, 20) : p8);
        v.setLayoutParams(lp);
        return v;
    }

    /** Whether a companion is installed for this title (Installer.installedVersion under the title's companion root), cached until the next scan or build */
    private boolean companionInstalled(Row r) {
        Boolean c = companions.get(r.id());
        if (c == null) {
            CompanionPort.Item it = CompanionPort.forTitle(catalog, r.id());
            File pack = r.title == null && !r.entry.packDir.isEmpty() ? new File(r.entry.packDir) : null;
            File root = r.title != null ? r.title.companionRoot() : pack == null || pack.getParentFile() == null ? null : new File(pack.getParentFile(), "peanuts");
            c = it != null && root != null && CompanionPort.installedVersion(root, it.packageName) != null;
            companions.put(r.id(), c);
        }
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
        if (coverChip != null) {   // the building cover's chip and strip (the Building filter not chosen)
            coverChip.setText(b.failed ? "Build stopped" : "Building · " + b.percent + " %");
            coverChip.setTextColor(PUi.col(a, b.failed ? R.color.bn_danger : R.color.bn_accent));
            if (coverStrip != null) { coverStrip.setVisibility(b.failed ? View.GONE : View.VISIBLE); if (!coverStrip.isIndeterminate()) coverStrip.setProgress(Math.max(0, Math.min(100, b.percent))); }
        }
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
