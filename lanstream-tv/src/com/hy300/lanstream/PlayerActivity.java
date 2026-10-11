package com.hy300.lanstream;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Plays one video from the LAN Stream server with the projector's own player (hardware decoding), driven
 * by the remote:
 *
 *   OK            play / pause
 *   left, right   back / forward 10 s per press; hold to go further (the jump happens when you let go,
 *                 because every jump makes the PC restart its encoder at the new spot)
 *   up, down, menu   options: quality, audio track, subtitles, start over
 *   back          stop (the position is remembered)
 */
public class PlayerActivity extends Activity {
    private static final int SEEK_STEP_S = 10, SEEK_COMMIT_MS = 700, OVERLAY_MS = 4000, TICK_MS = 250;
    private static final int PROJECTOR_HEIGHT = 720;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private String base, id, title;

    private VideoView video;
    private TextView message, subtitle, clock, hint, heading;
    private ProgressBar bar;
    private View overlay;

    private JSONObject info;
    private JSONArray quals = new JSONArray(), audios = new JSONArray(), subs = new JSONArray();
    private int quality, audio;         // what the stream URL is built from
    private String subId = "";          // "" = off
    private Vtt cues;
    private int subGeneration;

    private long durationMs;
    private boolean prepared, wantPlaying = true, finished;
    private long seekTargetMs = -1;     // while left/right presses are being collected
    private long resumeAtMs;            // where to start once the stream is prepared
    private long lastSavedMs = -1, overlayUntil;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("lanstream", MODE_PRIVATE);
        base = getIntent().getStringExtra("base");
        id = getIntent().getStringExtra("id");
        title = getIntent().getStringExtra("title");
        buildViews();

        resumeAtMs = prefs.getLong("pos:" + id, 0) * 1000;
        say("Loading…");
        new Thread(() -> {
            try {
                final JSONObject j = new JSONObject(Server.get(base + "/api/media/" + id, 3000, 70000));
                ui.post(() -> { if (!isFinishing()) onInfo(j); });
            } catch (Exception e) {
                ui.post(() -> say("The server didn't answer.\nPress Back, then check LAN Stream is running on the PC."));
            }
        }, "info").start();
    }

    private void buildViews() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        video = new VideoView(this);
        video.setFocusable(false);
        root.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        subtitle = text(30, Color.WHITE);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setTypeface(Typeface.DEFAULT_BOLD);
        subtitle.setShadowLayer(6, 0, 0, Color.BLACK);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sp.setMargins(120, 0, 120, 56);
        root.addView(subtitle, sp);

        message = text(26, Ui.TEXT);
        message.setGravity(Gravity.CENTER);
        message.setShadowLayer(8, 0, 0, Color.BLACK);
        root.addView(message, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        LinearLayout o = new LinearLayout(this);
        o.setOrientation(LinearLayout.VERTICAL);
        o.setBackgroundColor(Color.argb(200, 0, 0, 0));
        o.setPadding(48, 18, 48, 18);
        heading = text(24, Ui.TEXT);
        heading.setSingleLine(true);
        heading.setText(title);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        clock = text(20, Ui.TEXT);
        hint = text(16, Ui.MUTED);
        hint.setText("OK play/pause   ◀ ▶ skip 10 s   ▲ ▼ quality, audio, subtitles   Back stop");
        o.addView(heading);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 10);
        bp.setMargins(0, 12, 0, 8);
        o.addView(bar, bp);
        o.addView(clock);
        o.addView(hint);
        overlay = o;
        root.addView(o, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        setContentView(root);

        video.setOnPreparedListener(mp -> {
            prepared = true;
            if (durationMs <= 0) durationMs = mp.getDuration();
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) say("Buffering…");
                else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) say("");
                return false;
            });
            mp.setOnSeekCompleteListener(p -> { if (wantPlaying) say(""); });
            if (resumeAtMs > 0) video.seekTo((int) resumeAtMs);
            if (wantPlaying) video.start();
            say(wantPlaying ? "" : "Paused");
            showOverlay();
        });
        video.setOnCompletionListener(mp -> {
            finished = true;
            prefs.edit().remove("pos:" + id).apply();
            finish();
        });
        video.setOnErrorListener((mp, what, extra) -> {
            prepared = false;
            resumeAtMs = Math.max(resumeAtMs, lastSavedMs);
            say("Playback stopped (error " + what + "/" + extra + ").\nPress OK to try again.");
            return true;
        });
    }

    private TextView text(int size, int color) {
        TextView t = new TextView(this);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    // ---------------------------------------------------------------- stream

    private void onInfo(JSONObject j) {
        info = j;
        quals = j.optJSONArray("quals") != null ? j.optJSONArray("quals") : quals;
        audios = j.optJSONArray("audio") != null ? j.optJSONArray("audio") : audios;
        subs = j.optJSONArray("subs") != null ? j.optJSONArray("subs") : subs;
        durationMs = (long) (j.optDouble("duration", 0) * 1000);
        if (durationMs > 0 && resumeAtMs > durationMs * 0.97) resumeAtMs = 0;   // was at the credits: start over

        // Sharpest picture the projector can show: sending more than its 720 lines only costs Wi-Fi.
        // A saved choice wins, if this video offers it.
        int best = -1, bestH = -1, smallest = 0, smallestH = Integer.MAX_VALUE;
        int savedQ = prefs.getInt("quality", -1);
        boolean savedOffered = false;
        for (int i = 0; i < quals.length(); i++) {
            JSONObject q = quals.optJSONObject(i);
            int h = q.optInt("h"), code = q.optInt("q");
            if (h <= PROJECTOR_HEIGHT && h > bestH) { best = code; bestH = h; }
            if (h < smallestH) { smallest = code; smallestH = h; }
            savedOffered |= code == savedQ;
        }
        quality = savedOffered ? savedQ : best >= 0 ? best : smallest;
        audio = prefs.getInt("audio:" + id, j.optInt("defAudio", 0));
        subId = prefs.getString("sub:" + id, j.optString("defSub", ""));
        loadSubtitles();
        openStream();
    }

    private void openStream() {
        prepared = false;
        say("Loading…");
        video.setVideoURI(Uri.parse(base + "/hls/" + id + "/" + audio + "/" + quality + "/index.m3u8"));
        final String label = qualityLabel(quality);
        new Thread(() -> {   // shows up on the PC at /api/stats
            try { Server.get(base + "/api/hello?mode=tvapp&q=" + Uri.encode(label) + "&at=" + resumeAtMs / 1000, 2000, 4000); } catch (Exception ignored) {}
        }, "hello").start();
    }

    /** Restarts the stream at the current position (after changing quality or audio track). */
    private void reopen() {
        resumeAtMs = position();
        video.stopPlayback();
        openStream();
    }

    private long position() {
        if (seekTargetMs >= 0) return seekTargetMs;
        return prepared ? video.getCurrentPosition() : resumeAtMs;
    }

    private void loadSubtitles() {
        final int gen = ++subGeneration;
        cues = null;
        subtitle.setText("");
        if (subId.isEmpty()) return;
        final String url = base + "/subs/" + id + "/" + subId + ".vtt";
        new Thread(() -> {
            try {
                // The first request makes the PC pull the subtitles out of the file, which can take a while.
                final Vtt v = Vtt.parse(Server.get(url, 3000, 310000));
                ui.post(() -> { if (gen == subGeneration) cues = v; });
            } catch (Exception ignored) {}
        }, "subs").start();
    }

    // ---------------------------------------------------------------- remote

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean seekKey = k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_MEDIA_REWIND || k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return seekKey || super.dispatchKeyEvent(e);
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                if (e.getRepeatCount() == 0) playPause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (!wantPlaying) playPause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (wantPlaying) playPause();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                nudge(-SEEK_STEP_S, e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                nudge(SEEK_STEP_S, e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MENU:
                if (e.getRepeatCount() == 0) options();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void playPause() {
        if (info == null) return;
        if (!prepared) {                       // after an error: OK tries again
            wantPlaying = true;
            video.stopPlayback();
            openStream();
            return;
        }
        wantPlaying = !wantPlaying;
        if (wantPlaying) video.start(); else video.pause();
        say(wantPlaying ? "" : "Paused");
        showOverlay();
    }

    /** One left/right press. Presses add up; the stream jumps once, shortly after the last one. */
    private void nudge(int seconds, int repeat) {
        if (!prepared || durationMs <= 0) return;
        long step = seconds * 1000L * (repeat > 20 ? 6 : repeat > 8 ? 3 : 1);   // holding the key speeds up
        long from = seekTargetMs >= 0 ? seekTargetMs : video.getCurrentPosition();
        seekTargetMs = Math.max(0, Math.min(durationMs - 5000, from + step));
        showOverlay();
        ui.removeCallbacks(commitSeek);
        ui.postDelayed(commitSeek, SEEK_COMMIT_MS);
    }

    private final Runnable commitSeek = () -> {
        if (seekTargetMs < 0 || !prepared) { seekTargetMs = -1; return; }
        say("Jumping to " + Ui.time(seekTargetMs / 1000) + "…");
        video.seekTo((int) seekTargetMs);
        seekTargetMs = -1;
        showOverlay();
    };

    // ---------------------------------------------------------------- options

    private void options() {
        if (info == null) return;
        final String[] items = {
                "Quality: " + qualityLabel(quality),
                "Audio: " + audioLabel(audio),
                "Subtitles: " + subLabel(subId),
                "Start from the beginning",
        };
        dialog(title, items, -1, which -> {
            if (which == 0) chooseQuality();
            else if (which == 1) chooseAudio();
            else if (which == 2) chooseSubtitles();
            else if (prepared) { video.seekTo(0); showOverlay(); }
        });
    }

    private void chooseQuality() {
        String[] labels = new String[quals.length()];
        int current = -1;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = quals.optJSONObject(i).optString("label");
            if (quals.optJSONObject(i).optInt("q") == quality) current = i;
        }
        dialog("Quality (lower if it keeps buffering)", labels, current, which -> {
            int q = quals.optJSONObject(which).optInt("q");
            prefs.edit().putInt("quality", q).apply();
            if (q != quality) { quality = q; reopen(); }
        });
    }

    private void chooseAudio() {
        if (audios.length() == 0) return;
        String[] labels = new String[audios.length()];
        int current = -1;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = audioLabel(audios.optJSONObject(i).optInt("idx"));
            if (audios.optJSONObject(i).optInt("idx") == audio) current = i;
        }
        dialog("Audio track", labels, current, which -> {
            int a = audios.optJSONObject(which).optInt("idx");
            prefs.edit().putInt("audio:" + id, a).apply();
            if (a != audio) { audio = a; reopen(); }
        });
    }

    private void chooseSubtitles() {
        String[] labels = new String[subs.length() + 1];
        labels[0] = "Off";
        int current = 0;
        for (int i = 0; i < subs.length(); i++) {
            labels[i + 1] = subLabel(subs.optJSONObject(i).optString("id"));
            if (subs.optJSONObject(i).optString("id").equals(subId)) current = i + 1;
        }
        dialog("Subtitles", labels, current, which -> {
            subId = which == 0 ? "" : subs.optJSONObject(which - 1).optString("id");
            prefs.edit().putString("sub:" + id, subId).apply();
            loadSubtitles();
        });
    }

    private interface Choice { void picked(int which); }

    private void dialog(String heading, String[] items, int checked, final Choice choice) {
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(heading);
        if (checked >= 0) b.setSingleChoiceItems(items, checked, (d, w) -> { d.dismiss(); choice.picked(w); });
        else b.setItems(items, (d, w) -> choice.picked(w));
        b.show();
    }

    private String qualityLabel(int q) {
        for (int i = 0; i < quals.length(); i++)
            if (quals.optJSONObject(i).optInt("q") == q) return quals.optJSONObject(i).optString("label");
        return q == 0 ? "Full" : q + "p";
    }

    private String audioLabel(int idx) {
        for (int i = 0; i < audios.length(); i++) {
            JSONObject a = audios.optJSONObject(i);
            if (a.optInt("idx") == idx) return a.optString("label").isEmpty() ? "Track " + (idx + 1) : a.optString("label");
        }
        return audios.length() == 0 ? "only one" : "Track " + (idx + 1);
    }

    private String subLabel(String sid) {
        if (sid.isEmpty()) return subs.length() == 0 ? "none in this video" : "Off";
        for (int i = 0; i < subs.length(); i++) {
            JSONObject s = subs.optJSONObject(i);
            if (s.optString("id").equals(sid)) return s.optString("label").isEmpty() ? "Track " + (i + 1) : s.optString("label");
        }
        return "Off";
    }

    // ---------------------------------------------------------------- screen

    private void say(String text) {
        message.setText(text);
        message.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showOverlay() {
        overlayUntil = System.currentTimeMillis() + OVERLAY_MS;
        tick.run();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            ui.removeCallbacks(this);
            long pos = position();
            boolean show = System.currentTimeMillis() < overlayUntil || !wantPlaying || seekTargetMs >= 0;
            overlay.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                clock.setText(Ui.time(pos / 1000) + (durationMs > 0 ? " / " + Ui.time(durationMs / 1000) : "")
                        + (seekTargetMs >= 0 ? "   ◀▶" : wantPlaying ? "" : "   paused") + "     " + qualityLabel(quality));
                bar.setProgress(durationMs > 0 ? (int) (pos * 1000 / durationMs) : 0);
            }
            // Subtitles sit above the bar while it shows.
            FrameLayout.LayoutParams sp = (FrameLayout.LayoutParams) subtitle.getLayoutParams();
            int bottom = show ? 170 : 56;
            if (sp.bottomMargin != bottom) { sp.bottomMargin = bottom; subtitle.setLayoutParams(sp); }
            String cue = cues != null && prepared ? cues.at(video.getCurrentPosition()) : "";
            if (!cue.contentEquals(subtitle.getText())) subtitle.setText(cue);

            if (prepared && seekTargetMs < 0 && Math.abs(pos - lastSavedMs) >= 5000) save(pos);
            ui.postDelayed(this, TICK_MS);
        }
    };

    private void save(long posMs) {
        lastSavedMs = posMs;
        if (!finished) prefs.edit().putLong("pos:" + id, posMs / 1000).apply();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
        ui.removeCallbacks(commitSeek);
        if (prepared && !finished) save(position());
        // Leaving (Home, another app) ends the stream; coming back to the video list resumes from here.
        video.stopPlayback();
        subGeneration++;
        finish();
    }
}
