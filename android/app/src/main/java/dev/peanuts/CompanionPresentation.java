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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** The companion page on the second display. v0: a placeholder that proves the screen is ours and alive (name, display, a ticking clock); the game's data comes next. */
final class CompanionPresentation extends Presentation {
    private final String gameName;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView clock;

    CompanionPresentation(Context outer, Display display, String gameName) {
        super(outer, display);
        this.gameName = gameName == null ? "" : gameName;
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(0x14, 0x12, 0x0c));
        root.addView(text("Peanuts Native", 34, Color.rgb(0xff, 0xd9, 0x5a), Typeface.BOLD));
        root.addView(text(gameName, 24, Color.WHITE, Typeface.NORMAL));
        Display d = getDisplay();
        root.addView(text("display " + d.getDisplayId() + " · " + d.getName() + " · " + d.getMode().getPhysicalWidth() + "x" + d.getMode().getPhysicalHeight(), 16, Color.LTGRAY, Typeface.NORMAL));
        clock = text("", 20, Color.LTGRAY, Typeface.MONOSPACE.getStyle());
        clock.setTypeface(Typeface.MONOSPACE);
        root.addView(clock);
        setContentView(root);
    }

    @Override protected void onStart() { super.onStart(); tick(); }
    @Override protected void onStop() { ui.removeCallbacksAndMessages(null); super.onStop(); }

    private void tick() {
        clock.setText(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));
        ui.postDelayed(this::tick, 1000);
    }

    private TextView text(String s, int sp, int color, int style) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, style);
        t.setGravity(Gravity.CENTER);
        t.setPadding(24, 12, 24, 12);
        return t;
    }
}
