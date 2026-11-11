package dev.peanuts;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Map;

/** The companion page on the second display. v0: the host's snapshot (frames, build, main's base, the chains file's values), polled a few times a second; the real pages come next. */
final class CompanionPresentation extends Presentation {
    private static final int POLL_MS = 250;
    private final String gameName;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView values;

    CompanionPresentation(Context outer, Display display, String gameName) {
        super(outer, display);
        this.gameName = gameName == null ? "" : gameName;
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(32, 48, 32, 32);
        root.setBackgroundColor(Color.rgb(0x14, 0x12, 0x0c));
        root.addView(text("Peanuts Native", 30, Color.rgb(0xff, 0xd9, 0x5a), Typeface.DEFAULT_BOLD));
        root.addView(text(gameName, 22, Color.WHITE, Typeface.DEFAULT));
        values = text("", 18, Color.LTGRAY, Typeface.MONOSPACE);
        values.setGravity(Gravity.START);
        root.addView(values);
        setContentView(root);
    }

    @Override protected void onStart() { super.onStart(); poll(); }
    @Override protected void onStop() { ui.removeCallbacksAndMessages(null); super.onStop(); }

    private void poll() {
        Map<String, String> v = Bridge.values();
        StringBuilder sb = new StringBuilder();
        if (v.isEmpty()) sb.append("waiting for the host…");
        for (Map.Entry<String, String> e : v.entrySet()) sb.append(String.format("%-12s %s\n", e.getKey(), e.getValue()));
        values.setText(sb.toString());
        ui.postDelayed(this::poll, POLL_MS);
    }

    private TextView text(String s, int sp, int color, Typeface face) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setGravity(Gravity.CENTER);
        t.setPadding(16, 8, 16, 8);
        return t;
    }
}
