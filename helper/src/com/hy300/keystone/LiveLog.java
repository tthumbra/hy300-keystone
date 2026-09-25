package com.hy300.keystone;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayDeque;

/**
 * Live system log (what's running on the projector right now) for the startup screen: a background
 * `logcat` whose newest lines are kept in a small numbered buffer. GET /livelog?after=N returns lines
 * after number N. Started on first use; stops by itself once nobody has asked for a while.
 */
final class LiveLog {
    private static final int KEEP = 400;
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static long next = 0;                 // number of the next line
    private static Process proc;
    private static volatile long lastAsked;

    /** Lines numbered after `after` (up to 80); after < 0 means "the latest 20". "last" is the last one sent. */
    static synchronized JSONObject since(long after) throws Exception {
        lastAsked = System.currentTimeMillis();
        if (proc == null) start();
        long first = next - lines.size();                       // number of the oldest line kept
        long from = after < 0 ? Math.max(first, next - 20) : Math.max(first, after + 1);
        JSONArray out = new JSONArray();
        long n = first, last = from - 1;
        for (String l : lines) {
            if (n >= from && out.length() < 80) { out.put(l); last = n; }
            n++;
        }
        return new JSONObject().put("last", last).put("lines", out);
    }

    private static void start() throws Exception {
        final Process p = new ProcessBuilder("/system/bin/logcat", "-v", "brief", "-T", "1").redirectErrorStream(true).start();
        proc = p;
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                for (String l; (l = r.readLine()) != null; ) {
                    if (l.startsWith("-----")) continue;
                    String line = l.length() > 120 ? l.substring(0, 120) : l;
                    synchronized (LiveLog.class) {
                        lines.addLast(line);
                        next++;
                        if (lines.size() > KEEP) lines.removeFirst();
                    }
                    if (System.currentTimeMillis() - lastAsked > 60_000) break;   // nobody watching
                }
            } catch (Exception ignored) {
            } finally {
                p.destroy();
                synchronized (LiveLog.class) { if (proc == p) proc = null; }
            }
        }, "livelog");
        t.setDaemon(true);
        t.start();
    }

    private LiveLog() {}
}
