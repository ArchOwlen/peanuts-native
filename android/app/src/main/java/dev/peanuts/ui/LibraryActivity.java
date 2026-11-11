package dev.peanuts.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import dev.peanuts.PeanutsPrefs;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Peanuts Native's home: the library. Every game the scan finds (LibraryScanner) as a card: icon, name, status chip (Ready / Build / No profile), version. A Ready
 * game starts as upstream's adb path does (MainActivity --es game <dump or .nup> --ez run true, the Intent built here); any other opens its GameSheet. The ⋮ menu has
 * Setup, Refresh and the Companions catalog (CatalogActivity). The first start goes to Setup.
 */
public final class LibraryActivity extends Activity {
    static final int BG = Color.rgb(0x14, 0x12, 0x0c), ACCENT = Color.rgb(0xff, 0xd9, 0x5a), CARD = Color.rgb(0x1f, 0x1c, 0x14);
    private static final String MAIN = "dev.nxnative.android.MainActivity";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private GridView grid;
    private Cards cards;
    private List<LibraryScanner.Title> titles = new ArrayList<>();
    private List<CompanionPort.Item> catalog = new ArrayList<>();
    private boolean catalogFetched;
    private final Map<String, Bitmap> icons = new HashMap<>();
    private boolean scanning, sentToSetup;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(16), dp(16), dp(8));
        LinearLayout head = new LinearLayout(this); head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text("Peanuts Native", 26, ACCENT, Typeface.DEFAULT_BOLD));
        status = text("", 14, Color.LTGRAY, Typeface.DEFAULT);
        col.addView(status);
        head.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button menu = new Button(this); menu.setText("⋮"); menu.setTextSize(22); menu.setTextColor(ACCENT); menu.setBackgroundColor(Color.TRANSPARENT);
        menu.setOnClickListener(this::menu);
        head.addView(menu);
        root.addView(head);
        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(170));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(dp(12)); grid.setHorizontalSpacing(dp(12));
        grid.setPadding(0, dp(12), 0, dp(12));
        grid.setClipToPadding(false);
        cards = new Cards();
        grid.setAdapter(cards);
        grid.setOnItemClickListener((p, v, i, id) -> open(titles.get(i)));
        root.addView(grid, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        if (!PeanutsPrefs.setupDone(this)) {
            if (!sentToSetup) { sentToSetup = true; startActivity(new Intent(this, SetupActivity.class)); }
            else status.setText("Finish the setup (⋮ › Setup) to see your games.");
            return;
        }
        refresh(false);
    }

    private void menu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Setup");
        m.getMenu().add(0, 2, 1, "Refresh");
        m.getMenu().add(0, 3, 2, "Companions catalog");
        m.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1: startActivity(new Intent(this, SetupActivity.class)); return true;
                case 2: refresh(true); return true;
                case 3: startActivity(new Intent().setClassName(getPackageName(), "dev.peanuts.CatalogActivity")); return true;
                default: return false;
            }
        });
        m.show();
    }

    /** Scans the roots and (once, or again on Refresh) the catalog, then the icons; everything off the main thread */
    void refresh(boolean full) {
        if (scanning) return;
        scanning = true;
        if (full) { CompanionPort.resetNames(); catalogFetched = false; }
        status.setText("Scanning " + PeanutsPrefs.roots(this).size() + (PeanutsPrefs.roots(this).size() == 1 ? " folder…" : " folders…"));
        new Thread(() -> {
            List<LibraryScanner.Title> t = new LibraryScanner(this).scan();
            List<CompanionPort.Item> c = catalogFetched ? catalog : CompanionPort.fetch();
            ui.post(() -> {
                titles = t; catalog = c; catalogFetched = true; scanning = false;
                int ready = 0; for (LibraryScanner.Title x : t) if (x.status == LibraryScanner.Status.READY) ready++;
                status.setText(t.isEmpty() ? "No games found in " + PeanutsPrefs.gameDir(this) + ". Put NSP, XCI, .nup files or dump folders there." : t.size() + (t.size() == 1 ? " game" : " games") + "  ·  " + ready + " ready");
                cards.notifyDataSetChanged();
            });
            for (LibraryScanner.Title x : t) {
                if (icons.containsKey(x.titleId)) continue;
                File f = LibraryScanner.icon(this, x);
                Bitmap b = f == null ? null : BitmapFactory.decodeFile(f.getPath());
                if (b != null) ui.post(() -> { icons.put(x.titleId, b); cards.notifyDataSetChanged(); });
            }
        }, "peanuts-scan").start();
    }

    private void open(LibraryScanner.Title t) {
        if (t.status == LibraryScanner.Status.READY) { launch(t); return; }
        GameSheet.show(this, t, CompanionPort.forTitle(catalog, t.titleId), () -> refresh(false));
    }

    /** Starts a Ready game as adb does: am start -n <package>/dev.nxnative.android.MainActivity --es game <dump or .nup> [--es pack <pack>] --ez run true */
    private void launch(LibraryScanner.Title t) {
        Intent i = new Intent().setClassName(getPackageName(), MAIN).putExtra("game", t.launch.getPath()).putExtra("run", true);
        if (t.packDir != null) i.putExtra("pack", t.packDir.getPath());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    /** The sheet's Choose…/Add…: a file from the system picker (a .nsp/.xci on the phone's storage; its path as PathUtil derives it) */
    void pickFile(int request, String title) {
        Toast.makeText(this, title, Toast.LENGTH_LONG).show();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"));
        startActivityForResult(i, request);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request != GameSheet.REQ_UPDATE && request != GameSheet.REQ_DLC) return;
        String p = Upstream.documentToPath(data.getData());
        if (p == null || !new File(p).isFile()) {
            new AlertDialog.Builder(this).setTitle("Not on the phone's storage").setMessage("Pick the file from the phone's storage (not Recent or a cloud provider), so the app can open it by its path.").setPositiveButton("OK", null).show();
            return;
        }
        if (GameSheet.current != null) GameSheet.current.picked(request, new File(p));
    }

    // ---- The cards ----

    private final class Cards extends BaseAdapter {
        @Override public int getCount() { return titles.size(); }
        @Override public Object getItem(int i) { return titles.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View old, ViewGroup parent) {
            LibraryScanner.Title t = titles.get(i);
            LinearLayout card = old instanceof LinearLayout ? (LinearLayout) old : card();
            ImageView icon = (ImageView) card.getChildAt(0);
            Bitmap b = icons.get(t.titleId);
            if (b != null) icon.setImageBitmap(b); else icon.setImageResource(getApplicationInfo().icon);
            ((TextView) card.getChildAt(1)).setText(t.name);
            TextView chip = (TextView) ((LinearLayout) card.getChildAt(2)).getChildAt(0);
            TextView ver = (TextView) ((LinearLayout) card.getChildAt(2)).getChildAt(1);
            switch (t.status) {
                case READY: chip.setText("Ready"); chip(chip, Color.rgb(0x3d, 0xdc, 0x84), BG); break;
                case BUILD: chip.setText("Build"); chip(chip, ACCENT, BG); break;
                default: chip.setText("No profile"); chip(chip, Color.rgb(0x55, 0x50, 0x44), Color.LTGRAY); break;
            }
            ver.setText(t.versionText());
            return card;
        }

        private LinearLayout card() {
            LinearLayout c = new LinearLayout(LibraryActivity.this);
            c.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = new GradientDrawable(); bg.setColor(CARD); bg.setCornerRadius(dp(14));
            c.setBackground(bg);
            c.setPadding(dp(12), dp(12), dp(12), dp(12));
            ImageView icon = new ImageView(LibraryActivity.this);
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            icon.setBackgroundColor(Color.rgb(0x2a, 0x26, 0x1c));
            c.addView(icon, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(146)));
            TextView name = text("", 15, Color.WHITE, Typeface.DEFAULT_BOLD);
            name.setMaxLines(2); name.setEllipsize(android.text.TextUtils.TruncateAt.END); name.setMinLines(2);
            c.addView(name);
            LinearLayout row = new LinearLayout(LibraryActivity.this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            TextView chip = text("", 12, BG, Typeface.DEFAULT_BOLD);
            chip.setPadding(dp(10), dp(3), dp(10), dp(3));
            row.addView(chip);
            TextView ver = text("", 12, Color.LTGRAY, Typeface.DEFAULT);
            ver.setGravity(Gravity.END);
            row.addView(ver, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            c.addView(row);
            return c;
        }

        private void chip(TextView t, int bg, int fg) {
            GradientDrawable d = new GradientDrawable(); d.setColor(bg); d.setCornerRadius(dp(10));
            t.setBackground(d); t.setTextColor(fg);
        }
    }

    private TextView text(String s, int sp, int color, Typeface face) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setTypeface(face); t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
