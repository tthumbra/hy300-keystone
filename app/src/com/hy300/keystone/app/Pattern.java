package com.hy300.keystone.app;

import android.graphics.Canvas;
import android.graphics.Paint;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Calibration pattern: the whole frame lit in solid green. The phone finds the picture's outline by
 * "greenness" (green minus the larger of red and blue), which lit walls, ceilings and lamps don't have
 * — real frames from a lit room showed plain brightness can't always tell the picture from the wall.
 * Green is also the projector's brightest primary and what phone cameras are most sensitive to.
 */
final class Pattern {
    static final int FILL = 0xFF00FF00;
    static final int LABEL = 0xFF004000;

    static void draw(Canvas c, int w, int h, String label) {
        c.drawColor(FILL);
        if (label != null && !label.isEmpty()) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(LABEL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(h * 0.045f);
            c.drawText(label, w / 2f, h / 2f, p);
        }
    }

    static JSONObject spec(int w, int h) throws JSONException {
        return new JSONObject().put("type", "solid-green").put("width", w).put("height", h);
    }

    private Pattern() {}
}
