package com.hy300.keystone.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/** Small note in the bottom-right corner over whatever is on screen (e.g. a screen-share PIN). */
final class CornerNote {
    private static View shown;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Runnable pendingHide;

    static void show(Context ctx, String title, String text, long ms) {
        main.post(() -> {
            hideNow(ctx);
            if (!Settings.canDrawOverlays(ctx)) return;
            int h = ctx.getResources().getDisplayMetrics().heightPixels;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    Math.round(h * 0.42f), Math.round(h * 0.2f),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.END;
            lp.x = lp.y = Math.round(h * 0.03f);
            View v = new NoteView(ctx, title, text);
            try {
                ctx.getSystemService(WindowManager.class).addView(v, lp);
                shown = v;
                pendingHide = () -> hideNow(ctx);
                main.postDelayed(pendingHide, ms);
            } catch (Exception e) {
                android.util.Log.w(ServerService.TAG, "note overlay: " + e);
            }
        });
    }

    static void hide(Context ctx) { main.post(() -> hideNow(ctx)); }

    private static void hideNow(Context ctx) {
        if (pendingHide != null) main.removeCallbacks(pendingHide);
        pendingHide = null;
        if (shown == null) return;
        try { ctx.getSystemService(WindowManager.class).removeView(shown); } catch (Exception ignored) {}
        shown = null;
    }

    private static final class NoteView extends View {
        private final String title, text;
        private final Paint fill = new Paint(), paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        NoteView(Context ctx, String title, String text) {
            super(ctx);
            this.title = title;
            this.text = text;
            paint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            fill.setColor(0xE6101418);
            c.drawRoundRect(0, 0, w, h, h * 0.12f, h * 0.12f, fill);
            paint.setColor(0xFFAAB4BE);
            paint.setTextSize(h * 0.17f);
            c.drawText(title, w / 2f, h * 0.34f, paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(h * 0.34f);
            c.drawText(text, w / 2f, h * 0.78f, paint);
        }
    }

    private CornerNote() {}
}
