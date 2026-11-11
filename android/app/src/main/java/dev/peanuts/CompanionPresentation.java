package dev.peanuts;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

/**
 * The companion page on the second display. With a companion package loaded, the host's DSMod runtime rasterises the page and this shows it as a bitmap, scaled to
 * fit the display, and sends touches back in page pixels. Without one, the host's snapshot (frames, build, chains, scan) is shown as text: the development view.
 */
final class CompanionPresentation extends Presentation {
    private static final int POLL_MS = 33;        // frames: the page follows the game's rate
    private static final int TEXT_POLL_MS = 250;  // the text view
    private final String gameName;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView values;
    private ImageView page;
    private LinearLayout textRoot;
    private Bitmap bitmap;
    private ByteBuffer pixels;
    private int pw, ph;   // the page's size
    private int pollTick;

    CompanionPresentation(Context outer, Display display, String gameName) {
        super(outer, display);
        this.gameName = gameName == null ? "" : gameName;
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(getContext());
        root.setBackgroundColor(Color.BLACK);
        page = new ImageView(getContext());
        page.setScaleType(ImageView.ScaleType.FIT_CENTER);
        page.setVisibility(View.GONE);
        page.setOnTouchListener(this::onPageTouch);
        root.addView(page, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        textRoot = new LinearLayout(getContext());
        textRoot.setOrientation(LinearLayout.VERTICAL);
        textRoot.setGravity(Gravity.CENTER_HORIZONTAL);
        textRoot.setPadding(32, 48, 32, 32);
        textRoot.setBackgroundColor(Color.rgb(0x14, 0x12, 0x0c));
        textRoot.addView(text("Peanuts Native", 30, Color.rgb(0xff, 0xd9, 0x5a), Typeface.DEFAULT_BOLD));
        textRoot.addView(text(gameName, 22, Color.WHITE, Typeface.DEFAULT));
        values = text("", 18, Color.LTGRAY, Typeface.MONOSPACE);
        values.setGravity(Gravity.START);
        textRoot.addView(values);
        root.addView(textRoot, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    @Override protected void onStart() { super.onStart(); poll(); }
    @Override protected void onStop() { ui.removeCallbacksAndMessages(null); super.onStop(); }

    private void poll() {
        int w = Bridge.pageWidth(), h = Bridge.pageHeight();
        if (w > 0 && h > 0) {
            if (w != pw || h != ph) {
                pw = w; ph = h;
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
                page.setImageBitmap(bitmap);
            }
            boolean fresh;
            try { fresh = Bridge.frame(pixels, w, h); } catch (UnsatisfiedLinkError e) { fresh = false; }
            if (fresh) { pixels.rewind(); bitmap.copyPixelsFromBuffer(pixels); page.invalidate(); }
            if (page.getVisibility() != View.VISIBLE) { page.setVisibility(View.VISIBLE); textRoot.setVisibility(View.GONE); }
            ui.postDelayed(this::poll, POLL_MS);
            return;
        }
        if (++pollTick % (TEXT_POLL_MS / POLL_MS) == 0 || values.length() == 0) {
            Map<String, String> v = Bridge.values();
            StringBuilder sb = new StringBuilder();
            if (v.isEmpty()) sb.append("waiting for the host…");
            for (Map.Entry<String, String> e : v.entrySet()) sb.append(String.format("%-12s %s\n", e.getKey(), e.getValue()));
            values.setText(sb.toString());
        }
        ui.postDelayed(this::poll, POLL_MS);
    }

    /** A touch on the scaled page → page pixels (FIT_CENTER: one scale, centred) */
    private boolean onPageTouch(View v, MotionEvent e) {
        if (pw == 0 || ph == 0) return false;
        float sx = (float) v.getWidth() / pw, sy = (float) v.getHeight() / ph, s = Math.min(sx, sy);
        float ox = (v.getWidth() - pw * s) / 2, oy = (v.getHeight() - ph * s) / 2;
        int action = e.getActionMasked();
        boolean down = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_MOVE;
        boolean up = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL;
        if (!down && !up) return true;
        int idx = action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP ? e.getActionIndex() : -1;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (idx >= 0 && i != idx) continue;
            try { Bridge.touch(e.getPointerId(i), (e.getX(i) - ox) / s, (e.getY(i) - oy) / s, down); } catch (UnsatisfiedLinkError ignored) { return false; }
        }
        return true;
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
