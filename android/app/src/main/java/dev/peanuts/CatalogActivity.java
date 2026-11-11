package dev.peanuts;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Peanuts Companions: the catalog of Eden Duo companion packages for the games in the launcher's list, with Install / Update / Remove. A package installs beside the
 * game's pack (<title ID>-work/peanuts/<Name>/); the game picks it up at its next start. Games without a pack yet are listed but cannot take a package.
 */
public final class CatalogActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView status;
    private List<Games.Entry> games = new ArrayList<>();
    private List<Catalog.Item> items = new ArrayList<>();

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(0x14, 0x12, 0x0c));
        root.setPadding(32, 32, 32, 32);
        TextView title = text("Peanuts Companions", 26, Color.rgb(0xff, 0xd9, 0x5a), Typeface.DEFAULT_BOLD);
        root.addView(title);
        status = text("", 15, Color.LTGRAY, Typeface.DEFAULT);
        root.addView(status);
        Button refresh = new Button(this); refresh.setText("Refresh"); refresh.setOnClickListener(v -> load());
        root.addView(refresh);
        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        load();
    }

    private void load() {
        status.setText("Loading the catalog…");
        list.removeAllViews();
        new Thread(() -> {
            List<Games.Entry> g = Games.load(this);
            List<Catalog.Item> it; String err = null;
            try { it = Catalog.fetch(); } catch (Exception e) { it = new ArrayList<>(); err = e.toString(); }
            final List<Catalog.Item> fit = it; final String ferr = err;
            ui.post(() -> { games = g; items = fit; status.setText(ferr != null ? "Catalog unavailable: " + ferr : fit.size() + " companions in the catalog · " + g.size() + " games in your list"); render(); });
        }, "peanuts-catalog").start();
    }

    private void render() {
        list.removeAllViews();
        // The user's games first, then every other companion
        List<Catalog.Item> rest = new ArrayList<>(items);
        for (Games.Entry g : games) {
            Catalog.Item hit = null;
            for (Catalog.Item i : items) if (i.titleId.equalsIgnoreCase(g.titleId)) { hit = i; rest.remove(i); }
            list.addView(row(g, hit));
        }
        if (!rest.isEmpty()) {
            list.addView(text("Other companions (add the game first)", 16, Color.GRAY, Typeface.DEFAULT_BOLD));
            for (Catalog.Item i : rest) list.addView(row(null, i));
        }
        if (games.isEmpty() && items.isEmpty()) list.addView(text("Nothing to show.", 16, Color.GRAY, Typeface.DEFAULT));
    }

    private View row(Games.Entry g, Catalog.Item item) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(16, 18, 16, 18);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        String name = g != null ? g.name : item.gameName;
        col.addView(text(name, 18, Color.WHITE, Typeface.DEFAULT_BOLD));
        File root = g != null ? g.companionRoot() : null;
        String installed = root != null && item != null ? Installer.installedVersion(root, item.packageName) : null;
        String line = item == null ? "no companion in the catalog"
                    : installed == null ? "available: " + item.version + " (" + String.format(Locale.US, "%.0f KB", item.size / 1024.0) + ")"
                    : installed.equals(item.version) ? "installed " + installed : "installed " + installed + " · update " + item.version;
        if (g != null && root == null) line += " · prepare the game first";
        col.addView(text(line, 14, Color.LTGRAY, Typeface.DEFAULT));
        r.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (item != null && root != null) {
            Button b = new Button(this);
            b.setText(installed == null ? "Install" : installed.equals(item.version) ? "Remove" : "Update");
            File froot = root;
            b.setOnClickListener(v -> {
                if ("Remove".contentEquals(b.getText())) { Installer.remove(froot, item.packageName); Toast.makeText(this, "Removed", Toast.LENGTH_SHORT).show(); render(); return; }
                b.setEnabled(false);
                install(item, froot, b);
            });
            r.addView(b);
        }
        return r;
    }

    private void install(Catalog.Item item, File root, Button b) {
        status.setText("Installing " + item.packageName + " " + item.version + "…");
        new Thread(() -> {
            String err = null;
            try {
                if (!root.isDirectory() && !root.mkdirs()) throw new java.io.IOException("cannot create " + root);
                Installer.install(item, root, getCacheDir(), (what, done, total) -> ui.post(() -> status.setText(what + " " + (total > 0 ? (done * 100 / total) + "%" : done + " files"))));
            } catch (Exception e) { err = e.getMessage() == null ? e.toString() : e.getMessage(); }
            final String ferr = err;
            ui.post(() -> {
                if (ferr == null) { status.setText("Installed " + item.packageName + " " + item.version + " into " + root); Toast.makeText(this, "Start the game to use it", Toast.LENGTH_LONG).show(); }
                else new AlertDialog.Builder(this).setTitle("Install failed").setMessage(ferr).setPositiveButton("OK", null).show();
                render();
            });
        }, "peanuts-install").start();
    }

    private TextView text(String s, int sp, int color, Typeface face) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setTypeface(face); t.setPadding(0, 6, 0, 6);
        return t;
    }
}
