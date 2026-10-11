package com.hy300.lanstream;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;

/** Colours and the row highlight shared by both screens. Sizes are in pixels of the 1280x720 picture. */
final class Ui {
    static final int BG = Color.rgb(14, 17, 22), PANEL = Color.rgb(23, 27, 34), TEXT = Color.rgb(232, 236, 241),
            MUTED = Color.rgb(152, 162, 176), ACCENT = Color.rgb(90, 169, 255);

    /** Row background: a panel that turns blue-outlined when the remote's focus is on it. */
    static StateListDrawable rowBackground() {
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(Color.rgb(31, 52, 82));
        focused.setStroke(3, ACCENT);
        focused.setCornerRadius(12);
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(PANEL);
        normal.setCornerRadius(12);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{android.R.attr.state_selected}, focused);
        s.addState(new int[]{android.R.attr.state_pressed}, focused);
        s.addState(new int[]{}, normal);
        return s;
    }

    static String time(long seconds) {
        long h = seconds / 3600, m = seconds % 3600 / 60, s = seconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    private Ui() {}
}
