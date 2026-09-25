package com.hy300.keystone.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Startup screen shown right after boot, styled as a plain terminal: the real kernel startup log, the
 * Android services that started, and this app's services, then "Welcome!" with a small credit line.
 * Any key skips it. (The stock boot logo before it is on a read-only partition.)
 */
public class BootIntroActivity extends Activity {
    static volatile boolean running;
    private IntroView view;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        running = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        view = new IntroView(this);
        setContentView(view);
        new Thread(view::loadBootInfo, "intro-bootinfo").start();
        new Thread(view::followLiveLog, "intro-livelog").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        finish();   // any key skips
        return true;
    }

    @Override
    protected void onDestroy() {
        running = false;
        super.onDestroy();
    }

    // ------------------------------------------------------------------------------------------------

    static final class Line {
        final String tag, text;
        final int tagColor, textColor;
        Line(String tag, int tagColor, String text, int textColor) {
            this.tag = tag; this.tagColor = tagColor; this.text = text; this.textColor = textColor;
        }
    }

    private static final int FG = 0xFFC8C8C8, DIM = 0xFF7A7A7A, LIVE = 0xFF6E8C9E, OK = 0xFF4CAF50, WARN = 0xFFE0B040;

    final class IntroView extends View {
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), big = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint();
        private final Random rnd = new Random();
        private final List<Line> script = new ArrayList<>();   // everything to print, in order
        private final List<Line> shown = new ArrayList<>();
        private final List<Long> delays = new ArrayList<>();   // ms before each script line
        private volatile JSONObject bootInfo;
        private long phaseStart, nextLineAt;
        private int phase;          // 0 waiting for data, 1 log, 2 prompt, 3 welcome
        /** Live system log lines (what's running right now), from the helper; drained into the screen. */
        private final java.util.concurrent.ConcurrentLinkedQueue<String> live = new java.util.concurrent.ConcurrentLinkedQueue<>();
        private long liveUntil;

        IntroView(Context c) {
            super(c);
            Typeface mono = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL);
            text.setTypeface(mono);
            big.setTypeface(mono);
            big.setTextAlign(Paint.Align.CENTER);
            phaseStart = SystemClock.uptimeMillis();
        }

        /** Real startup data from the shell helper (it starts a few seconds after boot). */
        void loadBootInfo() {
            for (int i = 0; i < 20 && bootInfo == null; i++) {
                try {
                    HelperClient.Result r = HelperClient.call("GET", "/bootinfo", null, 3000);
                    if (r.code == 200) bootInfo = new JSONObject(r.body);
                } catch (Exception ignored) {}
                if (bootInfo == null) SystemClock.sleep(400);
            }
        }

        void followLiveLog() {
            long after = -1;
            while (running) {
                try {
                    HelperClient.Result r = HelperClient.call("GET", "/livelog?after=" + after, null, 3000);
                    if (r.code == 200) {
                        JSONObject j = new JSONObject(r.body);
                        after = j.optLong("last", after);
                        JSONArray lines = j.optJSONArray("lines");
                        for (int i = 0; lines != null && i < lines.length(); i++) live.add(lines.optString(i));
                    }
                } catch (Exception ignored) {}
                SystemClock.sleep(200);
            }
        }

        /** A few live log lines per frame, so they scroll by rather than jump. */
        private void drainLive() {
            for (int i = 0; i < 2 && !live.isEmpty(); i++) shown.add(new Line("", 0, live.poll(), LIVE));
            while (live.size() > 200) live.poll();
        }

        private void buildScript() {
            int w = 22;
            JSONObject info = bootInfo;
            if (info != null) {
                JSONArray k = info.optJSONArray("kernel");
                for (int i = 0; k != null && i < k.length(); i++) add("", 0, k.optString(i), DIM, 12);
                JSONArray s = info.optJSONArray("services");
                for (int i = 0; s != null && i < s.length(); i++) {
                    JSONObject svc = s.optJSONObject(i);
                    if (svc == null || !"running".equals(svc.optString("state"))) continue;
                    add("[  OK  ]", OK, "Started " + svc.optString("name") + ".", FG, 35);
                }
            }
            String ip = ServerService.lanAddress();
            add("[  OK  ]", OK, pad("Started keystone helper", w + 8) + (AppState.helperUp ? "(shell)" : ""), FG, 90);
            add(ip != null ? "[  OK  ]" : "[ WAIT ]", ip != null ? OK : WARN,
                    pad("Network", w + 8) + (ip != null ? ip : "waiting for Wi-Fi"), FG, 90);
            add("[  OK  ]", OK, pad("Started remote server", w + 8) + ":" + ServerService.HTTPS_PORT, FG, 90);
            add("[  OK  ]", OK, "Started phone remote.", FG, 90);
            add("[  OK  ]", OK, "Started screen share.", FG, 90);
            add("", 0, "", 0, 250);
        }

        private String pad(String s, int n) {
            StringBuilder b = new StringBuilder(s);
            while (b.length() < n) b.append(' ');
            return b.toString();
        }

        private void add(String tag, int tagColor, String t, int color, long delay) {
            script.add(new Line(tag, tagColor, t, color));
            delays.add(delay);
        }

        @Override
        protected void onDraw(Canvas c) {
            int W = getWidth(), H = getHeight();
            long now = SystemClock.uptimeMillis();
            float lineH = H / 32f;
            c.drawColor(Color.BLACK);

            if (phase == 0) {                                   // brief wait for the helper's data
                drawLog(c, W, H, lineH, true);
                if (bootInfo != null || now - phaseStart > 3000) {
                    buildScript();
                    phase = 1;
                    nextLineAt = now;
                }
            } else if (phase == 1) {                            // startup log, with the live log running
                while (!script.isEmpty() && now >= nextLineAt) {
                    shown.add(script.remove(0));
                    nextLineAt += delays.remove(0);
                }
                drainLive();
                drawLog(c, W, H, lineH, false);
                if (script.isEmpty() && now >= nextLineAt) {
                    if (liveUntil == 0) liveUntil = now + 2500;   // then a moment of just the live log
                    if (now >= liveUntil) { phase = 2; phaseStart = now; }
                }
            } else if (phase == 2) {                            // prompt with a blinking cursor
                drawLog(c, W, H, lineH, false);
                if (now - phaseStart > 700) { phase = 3; phaseStart = now; }
            } else {                                            // Welcome!
                long p = now - phaseStart;
                big.setTextSize(H * 0.14f);
                // Screen glitch as it appears (and a short hiccup after), then steady.
                float strength = p < 550 ? 1 - p / 550f : (p > 1500 && p < 1620) ? 0.35f : 0;
                if (strength > 0) drawGlitched(c, W, H, "Welcome!", H * 0.52f, strength);
                else {
                    big.setColor(Color.WHITE);
                    c.drawText("Welcome!", W / 2f, H * 0.52f, big);
                }
                big.setTextSize(H * 0.028f);
                big.setColor(DIM);
                c.drawText("Tanish Thumbraguddi's Custom Loader", W / 2f, H * 0.94f, big);
                if (p > 2600) { finish(); return; }
            }
            postInvalidateOnAnimation();
        }

        /** Text torn into horizontally shifted bands with red/cyan fringes, plus a few noise bars. */
        private void drawGlitched(Canvas c, int W, int H, String s, float y, float strength) {
            int bands = 10;
            float top = y - big.getTextSize(), bottom = y + big.getTextSize() * 0.3f, bandH = (bottom - top) / bands;
            for (int b = 0; b < bands; b++) {
                float dx = (rnd.nextFloat() - 0.5f) * H * 0.08f * strength * (rnd.nextFloat() < 0.4f ? 1 : 0.2f);
                float fringe = H * 0.006f * strength + 1;
                c.save();
                c.clipRect(0, top + b * bandH, W, top + (b + 1) * bandH);
                big.setColor(0xB4FF3050);
                c.drawText(s, W / 2f + dx - fringe, y, big);
                big.setColor(0xB430E0FF);
                c.drawText(s, W / 2f + dx + fringe, y, big);
                big.setColor(Color.WHITE);
                c.drawText(s, W / 2f + dx, y, big);
                c.restore();
            }
            for (int i = 0; i < 4; i++) {
                float by = rnd.nextFloat() * H, bh = rnd.nextFloat() * H * 0.012f + 1;
                fill.setColor(Color.argb((int) (60 * strength), 255, 255, 255));
                c.drawRect(0, by, W, by + bh, fill);
            }
        }

        private void drawLog(Canvas c, int W, int H, float lineH, boolean waiting) {
            text.setTextSize(lineH * 0.8f);
            int visible = (int) (H / lineH) - 1;
            int from = Math.max(0, shown.size() - visible);
            float x0 = W * 0.02f, y = lineH;
            for (int i = from; i < shown.size(); i++, y += lineH) {
                Line l = shown.get(i);
                float x = x0;
                if (!l.tag.isEmpty()) {
                    text.setColor(l.tagColor);
                    c.drawText(l.tag, x, y, text);
                    x += text.measureText(l.tag + " ");
                }
                text.setColor(l.textColor == 0 ? DIM : l.textColor);
                c.drawText(l.text, x, y, text);
            }
            boolean cursor = (SystemClock.uptimeMillis() / 500) % 2 == 0;
            text.setColor(FG);
            String prompt = waiting || phase < 2 ? "" : "hy300:~$ ";
            c.drawText(prompt + (cursor ? "_" : ""), x0, y, text);
        }
    }
}
