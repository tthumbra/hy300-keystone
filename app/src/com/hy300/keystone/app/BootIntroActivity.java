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
 * Startup screen shown right after boot: "Tanish Thumbraguddi's Home Server" coming up as a terminal —
 * the real kernel startup log, the Android services that started, and this app's services — ending in a
 * big "Welcome Tanish!". Any key skips it. (The stock boot logo before it is on a read-only partition.)
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

    private static final int GREEN = 0xFF3DFF7A, DIM = 0xFF1F8F48, WHITE = 0xFFD8FFE4, CYAN = 0xFF4FD8FF,
            YELLOW = 0xFFFFD84F, RED = 0xFFFF5A5A;

    final class IntroView extends View {
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), rain = new Paint(Paint.ANTI_ALIAS_FLAG),
                big = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint();
        private final Random rnd = new Random();
        private final List<Line> script = new ArrayList<>();   // everything to print, in order
        private final List<Line> shown = new ArrayList<>();
        private final List<Long> delays = new ArrayList<>();   // ms before each script line
        private volatile JSONObject bootInfo;
        private long start, nextLineAt;
        private int phase;          // 0 banner, 1 log, 2 access granted, 3 welcome, 4 fade
        private long phaseStart;
        private float[] drops;
        private static final String TITLE = "TANISH THUMBRAGUDDI'S HOME SERVER";
        private static final String RAIN = "01ABCDEF<>/\\|#$%&*+=?アイウエオカキクケコサシスセソ";

        IntroView(Context c) {
            super(c);
            Typeface mono = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL);
            text.setTypeface(mono);
            rain.setTypeface(mono);
            big.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            big.setTextAlign(Paint.Align.CENTER);
            start = SystemClock.uptimeMillis();
            phaseStart = start;
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

        private void buildScript() {
            int w = 28;
            add("", 0, "Booting HY300 · Allwinner H713 · Android " + Build.VERSION.RELEASE + " · kernel " + System.getProperty("os.version"), WHITE, 120);
            add("", 0, String.format(Locale.US, "cpu %d cores · ram %d MB · uptime %.1f s", Runtime.getRuntime().availableProcessors(),
                    totalRamMb(), SystemClock.elapsedRealtime() / 1000.0), DIM, 120);
            add("", 0, "", 0, 60);
            JSONObject info = bootInfo;
            if (info != null) {
                JSONArray k = info.optJSONArray("kernel");
                for (int i = 0; k != null && i < k.length(); i++) add("", 0, k.optString(i), DIM, 14);
                add("", 0, "", 0, 80);
                JSONArray s = info.optJSONArray("services");
                for (int i = 0; s != null && i < s.length(); i++) {
                    JSONObject svc = s.optJSONObject(i);
                    if (svc == null || !"running".equals(svc.optString("state"))) continue;
                    add("[  OK  ]", GREEN, "Started " + svc.optString("name"), WHITE, 45);
                }
            } else {
                add("[ WAIT ]", YELLOW, "Shell helper still starting — skipping the kernel log", WHITE, 150);
            }
            add("", 0, "", 0, 80);
            String ip = ServerService.lanAddress();
            add("[  OK  ]", GREEN, pad("Keystone helper", w) + (AppState.helperUp ? "uid 2000 (shell)" : "starting…"), WHITE, 140);
            add(ip != null ? "[  OK  ]" : "[ WAIT ]", ip != null ? GREEN : YELLOW,
                    pad("Network", w) + (ip != null ? ip : "waiting for Wi-Fi"), WHITE, 140);
            add("[  OK  ]", GREEN, pad("Remote server", w) + "https://" + (ip != null ? ip : "…") + ":" + ServerService.HTTPS_PORT, WHITE, 140);
            add("[  OK  ]", GREEN, pad("Phone remote", w) + "/dev/uhid virtual mouse", WHITE, 140);
            add("[  OK  ]", GREEN, pad("Bonjour", w) + "_hykeystone._tcp", WHITE, 140);
            add("[  OK  ]", GREEN, pad("Screen share", w) + "/share (WebRTC)", WHITE, 140);
            add("[ INFO ]", CYAN, "All systems nominal.", GREEN, 400);
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

        private long totalRamMb() {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
            return mi.totalMem / (1024 * 1024);
        }

        @Override
        protected void onDraw(Canvas c) {
            int W = getWidth(), H = getHeight();
            long now = SystemClock.uptimeMillis(), t = now - start;
            float lineH = H / 30f;
            c.drawColor(0xFF020604);
            drawRain(c, W, H, lineH);

            if (phase == 0) {                                   // title types itself
                int chars = (int) Math.min(TITLE.length(), (now - phaseStart) / 45);
                big.setTextSize(H * 0.062f);
                big.setColor(GREEN);
                big.setShadowLayer(H * 0.02f, 0, 0, GREEN);
                String shownTitle = TITLE.substring(0, chars) + ((now / 300) % 2 == 0 ? "█" : " ");
                c.drawText(shownTitle, W / 2f, H * 0.45f, big);
                big.setShadowLayer(0, 0, 0, 0);
                text.setTextSize(lineH * 0.8f);
                text.setColor(DIM);
                text.setTextAlign(Paint.Align.CENTER);
                if (chars == TITLE.length()) c.drawText("initializing…", W / 2f, H * 0.55f, text);
                text.setTextAlign(Paint.Align.LEFT);
                // Hold the title a moment, waiting for the helper's data up to ~3.5 s, then start printing.
                long typed = TITLE.length() * 45L;
                if (chars == TITLE.length() && now - phaseStart > typed + 1100
                        && (bootInfo != null || now - phaseStart > typed + 3500)) {
                    buildScript();
                    phase = 1;
                    phaseStart = now;
                    nextLineAt = now;
                }
            } else if (phase == 1) {                            // scrolling startup log
                while (!script.isEmpty() && now >= nextLineAt) {
                    shown.add(script.remove(0));
                    nextLineAt += delays.remove(0);
                }
                drawLog(c, W, H, lineH);
                if (script.isEmpty() && now >= nextLineAt) { phase = 2; phaseStart = now; }
            } else if (phase == 2) {                            // ACCESS GRANTED flash
                drawLog(c, W, H, lineH);
                long p = now - phaseStart;
                if ((p / 120) % 2 == 0) {
                    fill.setColor(0xCC000000);
                    c.drawRect(0, H * 0.42f, W, H * 0.58f, fill);
                    big.setTextSize(H * 0.07f);
                    big.setColor(GREEN);
                    big.setShadowLayer(H * 0.02f, 0, 0, GREEN);
                    c.drawText("ACCESS GRANTED", W / 2f, H * 0.525f, big);
                    big.setShadowLayer(0, 0, 0, 0);
                }
                if (p > 900) { phase = 3; phaseStart = now; }
            } else {                                            // Welcome Tanish!
                long p = now - phaseStart;
                float alpha = phase == 4 ? Math.max(0, 1 - (now - phaseStart) / 700f) : Math.min(1, p / 250f);
                big.setTextSize(H * 0.15f);
                String msg = "Welcome Tanish!";
                float y = H * 0.56f;
                if (phase == 3 && p < 450) {                    // glitch: offset colour copies
                    float j = (450 - p) / 450f * H * 0.015f;
                    big.setColor(Color.argb((int) (160 * alpha), 255, 40, 80));
                    c.drawText(msg, W / 2f - j * rnd.nextFloat() * 2, y + j * (rnd.nextFloat() - 0.5f), big);
                    big.setColor(Color.argb((int) (160 * alpha), 40, 220, 255));
                    c.drawText(msg, W / 2f + j * rnd.nextFloat() * 2, y - j * (rnd.nextFloat() - 0.5f), big);
                }
                big.setColor(Color.argb((int) (255 * alpha), 0x3D, 0xFF, 0x7A));
                big.setShadowLayer(H * 0.035f, 0, 0, Color.argb((int) (200 * alpha), 0x3D, 0xFF, 0x7A));
                c.drawText(msg, W / 2f, y, big);
                big.setShadowLayer(0, 0, 0, 0);
                text.setTextAlign(Paint.Align.CENTER);
                text.setTextSize(lineH * 0.85f);
                text.setColor(Color.argb((int) (200 * alpha), 0x1F, 0x8F, 0x48));
                c.drawText("home server online", W / 2f, y + H * 0.09f, text);
                text.setTextAlign(Paint.Align.LEFT);
                if (phase == 3 && p > 2800) { phase = 4; phaseStart = now; }
                if (phase == 4 && now - phaseStart > 700) { finish(); return; }
            }
            drawScanlines(c, W, H);
            postInvalidateOnAnimation();
        }

        private void drawLog(Canvas c, int W, int H, float lineH) {
            text.setTextSize(lineH * 0.78f);
            int visible = (int) (H / lineH) - 1;
            int from = Math.max(0, shown.size() - visible);
            float x0 = W * 0.03f, y = lineH;
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
            // blinking cursor on the next line
            if ((SystemClock.uptimeMillis() / 300) % 2 == 0) {
                text.setColor(GREEN);
                c.drawText("█", x0, y, text);
            }
        }

        private void drawRain(Canvas c, int W, int H, float lineH) {
            float size = lineH * 0.7f;
            int cols = (int) (W / size);
            if (drops == null || drops.length != cols) {
                drops = new float[cols];
                for (int i = 0; i < cols; i++) drops[i] = rnd.nextFloat() * H;
            }
            rain.setTextSize(size);
            for (int i = 0; i < cols; i++) {
                drops[i] += size * (0.25f + (i % 5) * 0.08f);
                if (drops[i] > H + size * 8) drops[i] = -rnd.nextFloat() * H * 0.5f;
                for (int k = 0; k < 8; k++) {
                    int a = phase >= 3 ? 14 : 38 - k * 4;
                    rain.setColor(Color.argb(Math.max(0, a), 0x3D, 0xFF, 0x7A));
                    char ch = RAIN.charAt(rnd.nextInt(RAIN.length()));
                    c.drawText(String.valueOf(ch), i * size, drops[i] - k * size, rain);
                }
            }
        }

        private void drawScanlines(Canvas c, int W, int H) {
            fill.setColor(0x22000000);
            for (int y = 0; y < H; y += 3) c.drawRect(0, y, W, y + 1, fill);
            // faint CRT flicker
            fill.setColor(Color.argb(rnd.nextInt(10), 0, 0, 0));
            c.drawRect(0, 0, W, H, fill);
        }
    }
}
