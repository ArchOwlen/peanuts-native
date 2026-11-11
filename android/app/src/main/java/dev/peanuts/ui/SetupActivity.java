package dev.peanuts.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import dev.peanuts.PeanutsPrefs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Setup (the first start, or ⋮ › Setup): prod.keys (the system file picker; copied into files/keys/prod.keys and checked by the importer when it is in the build),
 * the firmware folder (optional; the importer extracts the shared fonts into files/firmware/fonts), the game folder (required: the library's root), and the update and
 * DLC folders (optional extra roots). Folders come from the system folder picker as /storage paths (PathUtil, as upstream's Folders), which the app reads with All
 * files access: without it the same settings page upstream opens is offered.
 */
public final class SetupActivity extends Activity {
    private static final int PICK_KEYS = 1, PICK_FIRMWARE = 2, PICK_GAME = 3, PICK_UPDATE = 4, PICK_DLC = 5;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout box;
    private TextView note;
    private String fontsState;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(LibraryActivity.BG);
        root.setPadding(dp(20), dp(20), dp(20), dp(12));
        root.addView(text("Setup", 26, LibraryActivity.ACCENT, Typeface.DEFAULT_BOLD));
        root.addView(text("Keys, firmware and folders stay on this device. Nothing is uploaded.", 14, Color.LTGRAY, Typeface.DEFAULT));
        ScrollView scroll = new ScrollView(this);
        box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(box);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        note = text("", 13, Color.rgb(0xff, 0x8a, 0x65), Typeface.DEFAULT);
        root.addView(note);
        Button done = new Button(this); done.setText("Done"); done.setBackgroundColor(LibraryActivity.ACCENT); done.setTextColor(LibraryActivity.BG); done.setTypeface(Typeface.DEFAULT_BOLD);
        done.setOnClickListener(v -> done());
        root.addView(done);
        setContentView(root);
    }

    @Override protected void onResume() { super.onResume(); render(); }

    private void render() {
        box.removeAllViews();
        if (!Environment.isExternalStorageManager()) {
            card("All files access", "The app reads your game folder by its path. Allow All files access, then come back.", "Open settings", v -> openAccessSettings(), null, null);
        }
        boolean keys = PeanutsPrefs.hasKeys(this);
        card("1 · prod.keys", keys ? "Copied to the app (" + PeanutsPrefs.keysFile(this).length() + " bytes)." + (ImporterPort.get().available() ? "" : " The importer is not in this build: the keys are checked when it is.") : "Needed to import NSP/XCI files. A .nup or a dump folder plays without it.",
                keys ? "Replace…" : "Pick prod.keys…", v -> pickFile(PICK_KEYS, "Pick your prod.keys"), null, null);
        String fw = PeanutsPrefs.firmwareDir(this);
        card("2 · Firmware (optional)", (fw == null ? "No folder picked. The firmware's shared fonts let companions draw the system font." : fw) + (fontsState != null ? "\n" + fontsState : PeanutsPrefs.hasFonts(this) ? "\nFonts extracted." : ""),
                "Pick folder…", v -> pickFolder(PICK_FIRMWARE, "Pick your firmware folder"), fw == null ? null : "Clear", v -> { PeanutsPrefs.firmwareDir(this, null); fontsState = null; render(); });
        String game = PeanutsPrefs.gameDir(this);
        card("3 · Game folder", game == null ? "Required. The library shows every NSP, XCI, .nup and dump folder inside it (two levels deep). Imports and packs are written beside the games." : game,
                "Pick folder…", v -> pickFolder(PICK_GAME, "Pick your game folder"), null, null);
        String up = PeanutsPrefs.updateDir(this);
        card("4 · Update folder (optional)", up == null ? "Another folder with update NSPs, matched to their games by title ID." : up,
                "Pick folder…", v -> pickFolder(PICK_UPDATE, "Pick your update folder"), up == null ? null : "Clear", v -> { PeanutsPrefs.updateDir(this, null); render(); });
        String dlc = PeanutsPrefs.dlcDir(this);
        card("5 · DLC folder (optional)", dlc == null ? "Another folder with DLC NSPs, matched to their games by title ID." : dlc,
                "Pick folder…", v -> pickFolder(PICK_DLC, "Pick your DLC folder"), dlc == null ? null : "Clear", v -> { PeanutsPrefs.dlcDir(this, null); render(); });
        note.setText(game == null ? "Pick the game folder to finish." : "");
    }

    private void done() {
        if (PeanutsPrefs.gameDir(this) == null) { Toast.makeText(this, "Pick the game folder first", Toast.LENGTH_LONG).show(); return; }
        boolean first = !PeanutsPrefs.setupDone(this);
        PeanutsPrefs.setupDone(this, true);
        if (first) startActivity(new Intent(this, LibraryActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    // ---- Pickers ----

    private void pickFile(int request, String title) {
        Toast.makeText(this, title, Toast.LENGTH_LONG).show();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i, request);
    }

    /** As upstream's MainActivity.pickFolder: the system folder picker opened at the shared storage's root */
    private void pickFolder(int request, String title) {
        Toast.makeText(this, title, Toast.LENGTH_LONG).show();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"));
        startActivityForResult(i, request);
    }

    /** As upstream's MainActivity.openAccessSettings: the app's own All files access page, else the list */
    private void openAccessSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        if (request == PICK_KEYS) { copyKeys(u); return; }
        String p = Upstream.treeToPath(u);
        if (p == null) { error("That folder is not on the phone's storage (a cloud provider, say). Pick one on the phone or its SD card."); return; }
        switch (request) {
            case PICK_FIRMWARE: PeanutsPrefs.firmwareDir(this, p); extractFonts(p); break;
            case PICK_GAME: PeanutsPrefs.gameDir(this, p); break;
            case PICK_UPDATE: PeanutsPrefs.updateDir(this, p); break;
            case PICK_DLC: PeanutsPrefs.dlcDir(this, p); break;
            default: return;
        }
        if (!Environment.isExternalStorageManager()) {
            new AlertDialog.Builder(this).setTitle("Allow file access").setMessage("The app needs All files access to read " + p + ". Allow it, then come back.")
                    .setPositiveButton("Open settings", (d, w) -> openAccessSettings()).setNegativeButton("Later", null).show();
        }
        render();
    }

    /** The picked document → files/keys/prod.keys (through the content resolver: no path needed), then the importer's check when it is in the build */
    private void copyKeys(Uri u) {
        File out = PeanutsPrefs.keysFile(this);
        out.getParentFile().mkdirs();
        File tmp = new File(out.getPath() + ".tmp");
        try (InputStream in = getContentResolver().openInputStream(u); OutputStream o = new FileOutputStream(tmp)) {
            if (in == null) throw new IOException("the picker gave no file");
            byte[] buf = new byte[65536]; int n; long total = 0;
            while ((n = in.read(buf)) > 0) { o.write(buf, 0, n); total += n; if (total > 1 << 20) throw new IOException("that file is too large for a prod.keys"); }
        } catch (IOException e) { tmp.delete(); error("Could not copy the keys: " + e.getMessage()); return; }
        if (!tmp.renameTo(out)) { tmp.delete(); error("Could not place the keys in " + out); return; }
        ImporterPort imp = ImporterPort.get();
        if (imp.available()) {
            new Thread(() -> {
                int r = imp.keys(out.getPath());
                String err = imp.lastError();
                ui.post(() -> { if (r != 0) error("The importer does not accept these keys: " + err + "\nThe file is kept; pick another to replace it."); else Toast.makeText(this, "Keys accepted", Toast.LENGTH_SHORT).show(); render(); });
            }, "peanuts-keys").start();
        } else render();
    }

    /** The firmware's shared fonts → files/firmware/fonts (the importer; a note when it is not in the build) */
    private void extractFonts(String dir) {
        ImporterPort imp = ImporterPort.get();
        if (!imp.available()) { fontsState = "The importer is not in this build: the fonts are extracted when it is."; return; }
        fontsState = "Extracting the fonts…";
        File out = PeanutsPrefs.fontsDir(this);
        out.mkdirs();
        new Thread(() -> {
            int r = imp.firmwareFonts(dir, out.getPath());
            String err = imp.lastError();
            ui.post(() -> { fontsState = r == 0 ? "Fonts extracted." : "Fonts not extracted: " + err; render(); });
        }, "peanuts-fonts").start();
    }

    // ---- Views ----

    private void card(String title, String value, String b1, View.OnClickListener l1, String b2, View.OnClickListener l2) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(LibraryActivity.CARD); bg.setCornerRadius(dp(14));
        c.setBackground(bg);
        c.setPadding(dp(16), dp(12), dp(16), dp(12));
        c.addView(text(title, 16, Color.WHITE, Typeface.DEFAULT_BOLD));
        TextView v = text(value, 13, Color.LTGRAY, Typeface.DEFAULT);
        v.setTextIsSelectable(true);
        c.addView(v);
        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.END);
        if (b2 != null) { Button b = new Button(this); b.setText(b2); b.setOnClickListener(l2); r.addView(b); }
        Button b = new Button(this); b.setText(b1); b.setOnClickListener(l1); r.addView(b);
        c.addView(r);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        box.addView(c, lp);
    }

    private void error(String msg) { new AlertDialog.Builder(this).setTitle("Setup").setMessage(msg).setPositiveButton("OK", null).show(); }

    private TextView text(String s, int sp, int color, Typeface face) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setTypeface(face); t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
