package com.hy300.keystone.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/** Draws either the pairing QR screen or the calibration pattern, depending on AppState.mode. */
final class ScreenView extends View {
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint();
    private BitMatrix qr;
    private String qrFor = "";

    ScreenView(Context ctx) {
        super(ctx);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (AppState.mode == AppState.Mode.PATTERN) {
            Pattern.draw(c, w, h, AppState.applying ? "applying…" : "calibrating");
            return;
        }
        drawQrScreen(c, w, h);
    }

    private void drawQrScreen(Canvas c, int w, int h) {
        c.drawColor(0xFF101418);
        String url = AppState.pairUrl;

        text.setTextSize(h * 0.055f);
        c.drawText("Keystone calibration", w / 2f, h * 0.11f, text);

        if (url.isEmpty()) {
            text.setTextSize(h * 0.04f);
            c.drawText("Waiting for network…", w / 2f, h / 2f, text);
            return;
        }
        if (!url.equals(qrFor)) {
            qr = encode(url);
            qrFor = url;
        }
        if (qr != null) {
            int side = Math.round(h * 0.58f);
            int modules = qr.getWidth();
            float cell = (float) side / modules;
            float left = (w - side) / 2f, top = h * 0.16f;
            fill.setColor(Color.WHITE);
            c.drawRect(left, top, left + side, top + side, fill);
            fill.setColor(Color.BLACK);
            for (int y = 0; y < modules; y++)
                for (int x = 0; x < modules; x++)
                    if (qr.get(x, y))
                        c.drawRect(left + x * cell, top + y * cell, left + (x + 1) * cell, top + (y + 1) * cell, fill);
        }

        text.setTextSize(h * 0.035f);
        c.drawText("Scan with your phone's camera — no app needed", w / 2f, h * 0.82f, text);
        text.setTextSize(h * 0.028f);
        text.setColor(AppState.helperUp ? 0xFF6FCF97 : 0xFFFFC857);
        c.drawText("Keystone helper: " + AppState.helperStatus, w / 2f, h * 0.90f, text);
        text.setColor(Color.WHITE);
    }

    private static BitMatrix encode(String s) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 4);
            return new QRCodeWriter().encode(s, BarcodeFormat.QR_CODE, 0, 0, hints);
        } catch (Exception e) {
            return null;
        }
    }
}
