package com.hy300.lanstream;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Finds the LAN Stream server and lists its videos. The server is remembered; when it doesn't answer
 * (PC off, new address) the screen offers the servers found on the Wi-Fi and a box to type an address.
 */
public class MainActivity extends Activity {
    private SharedPreferences prefs;
    private TextView status;
    private LinearLayout rows;
    private String base = "";       // http://host:port of the server whose library is showing
    private int generation;         // drops results of background work the user has moved on from

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("lanstream", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(56, 36, 56, 24);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("LAN Stream");
        title.setTextColor(Ui.TEXT);
        title.setTextSize(34);
        status = new TextView(this);
        status.setTextColor(Ui.MUTED);
        status.setTextSize(20);
        status.setPadding(28, 6, 0, 0);
        header.addView(title);
        header.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(header);

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, 18, 0, 18);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(rows);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        String saved = prefs.getString("server", "");
        if (saved.isEmpty()) findServers(); else openLibrary(saved, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshResumeLines();
    }

    // ---------------------------------------------------------------- servers

    private void findServers() {
        final int gen = ++generation;
        base = "";
        rows.removeAllViews();
        status.setText("Looking for LAN Stream on the Wi-Fi…");
        new Thread(() -> {
            final List<String> found = Server.scan(Server.DEFAULT_PORT);
            runOnUiThread(() -> {
                if (gen != generation) return;
                if (found.size() == 1) { openLibrary(found.get(0), false); return; }
                showServers(found);
            });
        }, "scan").start();
    }

    private void showServers(List<String> found) {
        rows.removeAllViews();
        String own = Server.ownAddress();
        status.setText(own == null ? "This projector isn't on a network"
                : found.isEmpty() ? "No server found — is LAN Stream running on the PC?" : "Choose a server");
        for (final String b : found) addRow(b.substring(7), "LAN Stream server", () -> openLibrary(b, false));
        addRow("Search again", "Scans the Wi-Fi for LAN Stream on port " + Server.DEFAULT_PORT, this::findServers);
        addRow("Type an address", "For example 192.168.1.130:8090 — LAN Stream prints it when it starts", this::askAddress);
        focusFirst();
    }

    private void askAddress() {
        final EditText box = new EditText(this);
        box.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.setSingleLine(true);
        String own = Server.ownAddress();
        String last = prefs.getString("server", "");
        box.setText(!last.isEmpty() ? last.substring(7) : own != null ? own.substring(0, own.lastIndexOf('.') + 1) : "");
        box.setSelection(box.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("Server address")
                .setMessage("IP address and port, like 192.168.1.130:8090")
                .setView(box)
                .setPositiveButton("Connect", (d, w) -> {
                    String b = Server.baseUrl(box.getText().toString());
                    if (b == null) status.setText("That doesn't look like an address");
                    else openLibrary(b, true);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------------------------------------------------------------- library

    /** @param fallBackToSearch when the server doesn't answer: search the Wi-Fi instead of showing an error row */
    private void openLibrary(final String b, final boolean fallBackToSearch) {
        final int gen = ++generation;
        if (!b.equals(base)) rows.removeAllViews();
        status.setText("Connecting to " + b.substring(7) + "…");
        new Thread(() -> {
            List<Server.Video> videos = null;
            String error = "";
            try {
                videos = Server.library(b);
            } catch (Exception e) {
                error = e.getMessage() != null && e.getMessage().startsWith("That address") ? e.getMessage()
                        : "No answer from " + b.substring(7);
            }
            final List<Server.Video> v = videos;
            final String err = error;
            runOnUiThread(() -> {
                if (gen != generation) return;
                if (v != null) {
                    prefs.edit().putString("server", b).apply();
                    showLibrary(b, v);
                } else if (fallBackToSearch && b.equals(prefs.getString("server", ""))) {
                    findServers();
                } else {
                    base = "";
                    rows.removeAllViews();
                    status.setText(err);
                    addRow("Try again", b.substring(7), () -> openLibrary(b, false));
                    addRow("Search the Wi-Fi", "Looks for LAN Stream on port " + Server.DEFAULT_PORT, this::findServers);
                    addRow("Type an address", "", this::askAddress);
                    focusFirst();
                }
            });
        }, "library").start();
    }

    private void showLibrary(final String b, List<Server.Video> videos) {
        View focused = getCurrentFocus();
        Object keep = focused != null && focused.getTag() instanceof Server.Video ? ((Server.Video) focused.getTag()).id : null;
        base = b;
        rows.removeAllViews();
        status.setText(videos.size() + (videos.size() == 1 ? " video" : " videos") + " on " + b.substring(7));
        View refocus = null;
        for (final Server.Video v : videos) {
            View row = addRow(v.title, detail(v), () -> startActivity(new Intent(this, PlayerActivity.class)
                    .putExtra("base", b).putExtra("id", v.id).putExtra("title", v.title)));
            row.setTag(v);
            if (v.id.equals(keep)) refocus = row;     // keep the remote's place across a refresh
        }
        if (videos.isEmpty()) addRow("No videos on the server", "LAN Stream shows the videos in the folder it was started with", null);
        addRow("Refresh", "", () -> openLibrary(b, false));
        addRow("Change server", "", this::findServers);
        if (refocus != null) refocus.requestFocus(); else focusFirst();
    }

    private String detail(Server.Video v) {
        long pos = prefs.getLong("pos:" + v.id, 0);
        return pos >= 30 ? "Resume at " + Ui.time(pos) + "  ·  " + v.detail : v.detail;
    }

    /** Back from the player: update the "Resume at" lines in place, so the remote's place in the list stays. */
    private void refreshResumeLines() {
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            if (row.getTag() instanceof Server.Video && ((ViewGroup) row).getChildCount() > 1)
                ((TextView) ((ViewGroup) row).getChildAt(1)).setText(detail((Server.Video) row.getTag()));
        }
    }

    // ---------------------------------------------------------------- rows

    private View addRow(String title, String detail, final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(Ui.rowBackground());
        row.setPadding(26, 16, 26, 16);
        row.setFocusable(action != null);
        row.setClickable(action != null);
        if (action != null) row.setOnClickListener(v -> action.run());
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Ui.TEXT);
        t.setTextSize(24);
        t.setSingleLine(true);
        row.addView(t);
        if (!detail.isEmpty()) {
            TextView d = new TextView(this);
            d.setText(detail);
            d.setTextColor(Ui.MUTED);
            d.setTextSize(17);
            d.setSingleLine(true);
            row.addView(d);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 10;
        rows.addView(row, lp);
        return row;
    }

    private void focusFirst() {
        for (int i = 0; i < rows.getChildCount(); i++) {
            if (rows.getChildAt(i).isFocusable()) { rows.getChildAt(i).requestFocus(); return; }
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) { findServers(); return true; }
        return super.onKeyUp(keyCode, event);
    }
}
