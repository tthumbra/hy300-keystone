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

import com.google.zxing.common.BitMatrix;

/**
 * Small QR code in the bottom-right corner, over whatever is on screen, shown at boot so a phone can
 * pair as a remote. Goes away after a while or when a phone connects. Needs the overlay permission,
 * which the app grants itself through adb (see ServerService.startHelper).
 */
final class CornerQr {
    static final long SHOW_MS = 120_000;   // or until a phone connects
    private static View shown;
    private static final Handler main = new Handler(Looper.getMainLooper());

    static void show(Context ctx, String url) {
        main.post(() -> {
            if (shown != null || url.isEmpty() || !Settings.canDrawOverlays(ctx)) return;
            WindowManager wm = ctx.getSystemService(WindowManager.class);
            int h = ctx.getResources().getDisplayMetrics().heightPixels;
            int side = Math.round(h * 0.30f);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    side, Math.round(side * 1.18f),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.END;
            lp.x = lp.y = Math.round(h * 0.03f);
            View v = new QrView(ctx, url);
            try {
                wm.addView(v, lp);
                shown = v;
                main.postDelayed(() -> hide(ctx), SHOW_MS);
            } catch (Exception e) {
                android.util.Log.w(ServerService.TAG, "overlay: " + e);
            }
        });
    }

    static void hide(Context ctx) {
        main.post(() -> {
            if (shown == null) return;
            try { ctx.getSystemService(WindowManager.class).removeView(shown); } catch (Exception ignored) {}
            shown = null;
        });
    }

    private static final class QrView extends View {
        private final BitMatrix qr;
        private final Paint fill = new Paint(), text = new Paint(Paint.ANTI_ALIAS_FLAG);

        QrView(Context ctx, String url) {
            super(ctx);
            qr = ScreenView.encode(url);
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            fill.setColor(0xE6101418);
            c.drawRoundRect(0, 0, w, h, w * 0.06f, w * 0.06f, fill);
            float pad = w * 0.07f, side = w - 2 * pad;
            if (qr != null) {
                fill.setColor(Color.WHITE);
                c.drawRect(pad, pad, pad + side, pad + side, fill);
                fill.setColor(Color.BLACK);
                int n = qr.getWidth();
                float cell = side / n;
                for (int y = 0; y < n; y++)
                    for (int x = 0; x < n; x++)
                        if (qr.get(x, y)) c.drawRect(pad + x * cell, pad + y * cell, pad + (x + 1) * cell, pad + (y + 1) * cell, fill);
            }
            text.setTextSize(w * 0.075f);
            c.drawText("Phone remote", w / 2f, pad + side + (h - pad - side) * 0.62f, text);
        }
    }

    private CornerQr() {}
}
