package com.hy300.keystone;

import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Shell-privileged keystone applier for the HY300 (ControlCenter keystone mode 3, any installmode).
 *
 * Started with:  CLASSPATH=/data/local/tmp/ks-helper.dex app_process /system/bin com.hy300.keystone.Helper
 * Listens on 127.0.0.1 only.
 *
 * Shell can write persist.display.keystone_* but cannot call SurfaceFlinger transaction 1050 (EPERM).
 * So an apply writes the props, opens ControlCenter's 4-corner screen (it loads all 8 props on create)
 * and injects one or two arrow presses on the top-left corner that net to zero: every press makes the
 * system-uid app push all 8 values to SurfaceFlinger and persist them.
 */
public final class Helper {
    static final int VERSION = 1;
    static final String[] KEYS = {"ltx", "lty", "rtx", "rty", "lbx", "lby", "rbx", "rby"};
    static final String PROP_PREFIX = "persist.display.keystone_";
    /**
     * Stored values per persist.sys.installmode (from ControlCenter's AdjustFourActivity): each axis is
     * either direct (0 = no correction, up to 250 = 25% inward) or inverted (1000 = none, down to 750).
     * X_INVERTED/Y_INVERTED are indexed by installmode.
     */
    static final boolean[] X_INVERTED = {false, true, true, false}, Y_INVERTED = {false, false, true, true};
    static final int RANGE = 250;

    static int lo(boolean inverted) { return inverted ? 1000 - RANGE : 0; }
    static int hi(boolean inverted) { return inverted ? 1000 : RANGE; }

    static final String CONSOLE_MAIN = "com.cptp.console/.MainActivity";
    static final String FOUR_CORNER = "com.cptp.console/.AdjustFourActivity";
    // Centre of the "four corner" row (R.id.mode_rl) on ControlCenter's main screen at 1280x720.
    static int tapX = 640, tapY = 431;

    static final int KEY_LEFT = KeyEvent.KEYCODE_DPAD_LEFT, KEY_RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT;
    static final int INJECT_WAIT_FOR_FINISH = 2;

    static String token = "";
    static Method propGet, propSet, inject;
    static Object inputManager;
    static final Object applyLock = new Object();

    public static void main(String[] args) throws Exception {
        int port = 38300;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port")) port = Integer.parseInt(args[++i]);
            else if (args[i].equals("--token")) token = args[++i];
            else if (args[i].equals("--tap")) { tapX = Integer.parseInt(args[++i]); tapY = Integer.parseInt(args[++i]); }
        }
        Looper.prepareMainLooper();
        Class<?> sp = Class.forName("android.os.SystemProperties");
        propGet = sp.getMethod("get", String.class, String.class);
        propSet = sp.getMethod("set", String.class, String.class);
        Class<?> imClass = Class.forName("android.hardware.input.InputManager");
        inputManager = imClass.getMethod("getInstance").invoke(null);
        inject = imClass.getMethod("injectInputEvent", InputEvent.class, int.class);

        if (!token.isEmpty()) RemoteInput.start(token);   // phone-as-remote input, relayed by the app
        ServerSocket server = new ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"));
        log("listening on 127.0.0.1:" + port + " state=" + state());
        while (true) {
            Socket s = server.accept();
            new Thread(() -> handle(s)).start();
        }
    }

    // ---------------------------------------------------------------- HTTP

    static void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(10000);
            InputStream in = sock.getInputStream();
            String requestLine = readLine(in);
            if (requestLine == null) return;
            int contentLength = 0;
            String reqToken = "";
            for (String h; (h = readLine(in)) != null && !h.isEmpty(); ) {
                int c = h.indexOf(':');
                if (c <= 0) continue;
                String name = h.substring(0, c).trim(), value = h.substring(c + 1).trim();
                if (name.equalsIgnoreCase("content-length")) contentLength = Integer.parseInt(value);
                else if (name.equalsIgnoreCase("x-token")) reqToken = value;
            }
            if (contentLength > 4096) { respond(sock, 413, err("body too large")); return; }
            byte[] body = new byte[contentLength];
            for (int n = 0; n < contentLength; ) {
                int r = in.read(body, n, contentLength - n);
                if (r < 0) break;
                n += r;
            }
            String[] parts = requestLine.split(" ");
            String method = parts[0], path = parts.length > 1 ? parts[1] : "/";

            JSONObject out;
            int code = 200;
            if (!path.equals("/health") && !token.isEmpty()
                    && !java.security.MessageDigest.isEqual(token.getBytes(), reqToken.getBytes())) {
                respond(sock, 401, err("bad token"));
                return;
            }
            if (method.equals("GET") && path.equals("/health")) {
                out = new JSONObject().put("ok", true).put("version", VERSION);
            } else if (method.equals("GET") && path.equals("/bootinfo")) {
                out = bootInfo().put("ok", true);
            } else if (method.equals("GET") && path.equals("/state")) {
                out = state().put("ok", true);
            } else if (method.equals("POST") && path.equals("/apply")) {
                JSONObject req;
                try {
                    req = new JSONObject(new String(body, StandardCharsets.UTF_8));
                } catch (org.json.JSONException e) {
                    respond(sock, 400, err("body is not a JSON object"));
                    return;
                }
                out = apply(req);
                if (!out.optBoolean("ok")) code = out.has("status") ? out.getInt("status") : 500;
                out.remove("status");
            } else {
                code = 404;
                out = err("not found");
            }
            respond(sock, code, out);
        } catch (Exception e) {
            log("request failed: " + e);
        }
    }

    static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c; (c = in.read()) != -1; ) {
            if (c == '\n') return b.toString("UTF-8").replace("\r", "");
            if (b.size() > 8192) throw new Exception("header too long");
            b.write(c);
        }
        return b.size() > 0 ? b.toString("UTF-8") : null;
    }

    static void respond(Socket s, int code, JSONObject body) throws Exception {
        byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " X\r\nContent-Type: application/json\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n";
        OutputStream o = s.getOutputStream();
        o.write(head.getBytes(StandardCharsets.UTF_8));
        o.write(b);
        o.flush();
    }

    static JSONObject err(String msg) throws Exception {
        return new JSONObject().put("ok", false).put("error", msg);
    }

    // ---------------------------------------------------------------- keystone

    static JSONObject state() throws Exception {
        JSONObject o = new JSONObject();
        for (String k : KEYS) o.put(k, Integer.parseInt(prop(PROP_PREFIX + k, "0")));
        o.put("mode", Integer.parseInt(prop("persist.sys.keystone.console.height", "0")));
        o.put("installmode", Integer.parseInt(prop("persist.sys.installmode", "0")));
        return o;
    }

    /**
     * What the startup screen shows (needs shell to read): Android services that are running this boot
     * (init.svc.*) and the last lines of the kernel log.
     */
    static JSONObject bootInfo() throws Exception {
        org.json.JSONArray services = new org.json.JSONArray();
        for (String line : sh("getprop").split("\n")) {
            // [init.svc.surfaceflinger]: [running]
            if (!line.startsWith("[init.svc.")) continue;
            int end = line.indexOf(']');
            if (end < 0) continue;
            String name = line.substring(10, end);
            String state = line.substring(line.lastIndexOf('[') + 1, line.length() - 1);
            services.put(new JSONObject().put("name", name).put("state", state));
        }
        org.json.JSONArray kernel = new org.json.JSONArray();
        // The kernel's own startup log (right after boot it still starts at "Booting Linux...").
        int n = 0;
        for (String line : sh("dmesg").split("\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.contains("avc:") || l.contains("audit(")) continue;
            kernel.put(l.length() > 110 ? l.substring(0, 110) : l);
            if (++n >= 120) break;
        }
        return new JSONObject().put("services", services).put("kernel", kernel)
                .put("uptime", sh("cat /proc/uptime").trim());
    }

    static JSONObject apply(JSONObject req) throws Exception {
        int mode;
        try {
            mode = Integer.parseInt(prop("persist.sys.installmode", ""));
        } catch (NumberFormatException e) {
            mode = -1;
        }
        if (mode < 0 || mode > 3) return err("unknown installmode").put("status", 409);
        int[] v = new int[8];
        for (int i = 0; i < 8; i++) {
            if (!req.has(KEYS[i])) return err("missing " + KEYS[i]).put("status", 400);
            v[i] = req.getInt(KEYS[i]);
            boolean inv = i % 2 == 0 ? X_INVERTED[mode] : Y_INVERTED[mode];
            if (v[i] < lo(inv) || v[i] > hi(inv))
                return err(KEYS[i] + "=" + v[i] + " outside " + lo(inv) + ".." + hi(inv) + " for installmode " + mode).put("status", 400);
        }
        String returnTo = req.optString("return", "");
        if (!returnTo.isEmpty() && !returnTo.matches("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+"))
            return err("bad return component").put("status", 400);

        synchronized (applyLock) {
            if (!prop("persist.sys.keystone.console.height", "").equals("3"))
                return err("only ControlCenter keystone mode 3 is supported").put("status", 409);

            long t0 = System.currentTimeMillis();
            String lastError = null;
            for (int attempt = 1; attempt <= 2; attempt++) {
                lastError = applyOnce(v, mode);
                if (lastError == null) break;
                log("attempt " + attempt + " failed: " + lastError);
            }
            if (returnTo.isEmpty()) key(KeyEvent.KEYCODE_HOME);
            else sh("am start -n " + returnTo);

            JSONObject out = state();
            out.put("ok", lastError == null).put("ms", System.currentTimeMillis() - t0);
            if (lastError != null) out.put("error", lastError);
            log("apply " + req + " -> " + out);
            return out;
        }
    }

    /** Returns null on success, or a description of what went wrong. */
    static String applyOnce(int[] v, int mode) throws Exception {
        long since = System.currentTimeMillis();
        for (int i = 0; i < 8; i++) propSet.invoke(null, PROP_PREFIX + KEYS[i], String.valueOf(v[i]));

        // NEW_TASK | CLEAR_TASK: never reuse a stale 4-corner screen holding old values.
        sh("am start -W -f 0x10008000 -n " + CONSOLE_MAIN);
        if (!waitResumed(CONSOLE_MAIN, 4000)) return "ControlCenter did not open";
        SystemClock.sleep(300);
        tap(tapX, tapY);
        if (!waitResumed(FOUR_CORNER, 4000)) return "4-corner screen did not open";
        SystemClock.sleep(300);

        // Top-left is selected on open. For TL: RIGHT = stored x+5, LEFT = x-5; a move outside the
        // installmode's range is ignored. Press first in the direction that stays in range, then back,
        // so the net change is zero and each press pushes all 8 values.
        int presses = 2;
        boolean rightFirst = v[0] + 5 <= hi(X_INVERTED[mode]);
        key(rightFirst ? KEY_RIGHT : KEY_LEFT);
        key(rightFirst ? KEY_LEFT : KEY_RIGHT);

        boolean pushed = waitFor(() -> countFlingerWrites(since) >= presses, 3000);

        // Back opens "Save current changes and exit?"; OK has default focus.
        for (int i = 0; i < 3 && isResumed(FOUR_CORNER); i++) {
            key(KeyEvent.KEYCODE_BACK);
            SystemClock.sleep(500);
            key(KeyEvent.KEYCODE_DPAD_CENTER);
            SystemClock.sleep(500);
        }

        for (int i = 0; i < 8; i++) {
            String got = prop(PROP_PREFIX + KEYS[i], "");
            if (!got.equals(String.valueOf(v[i]))) return KEYS[i] + " is " + got + ", wanted " + v[i];
        }
        return pushed ? null : "no SurfaceFlinger write seen in logcat";
    }

    static int countFlingerWrites(long sinceMs) {
        String t = String.format(Locale.US, "%d.%03d", sinceMs / 1000, sinceMs % 1000);
        String out = sh("logcat -d -T " + t + " -s keystone-sensor:I");
        int n = 0;
        for (int i = out.indexOf("writeParcelToFlinger"); i >= 0; i = out.indexOf("writeParcelToFlinger", i + 1)) n++;
        return n;
    }

    static boolean isResumed(String component) {
        for (String line : sh("dumpsys activity activities").split("\n"))
            if (line.contains("mResumedActivity")) return line.contains(component);
        return false;
    }

    static boolean waitResumed(String component, long timeoutMs) {
        return waitFor(() -> isResumed(component), timeoutMs);
    }

    interface Cond { boolean test(); }

    static boolean waitFor(Cond c, long timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        do {
            if (c.test()) return true;
            SystemClock.sleep(150);
        } while (SystemClock.uptimeMillis() < end);
        return false;
    }

    // ---------------------------------------------------------------- platform

    static String prop(String key, String def) throws Exception {
        return (String) propGet.invoke(null, key, def);
    }

    static void key(int code) throws Exception {
        long now = SystemClock.uptimeMillis();
        for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            KeyEvent e = new KeyEvent(now, now, action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                    InputDevice.SOURCE_KEYBOARD);
            inject.invoke(inputManager, e, INJECT_WAIT_FOR_FINISH);
        }
    }

    /** Injects a prepared key event (used for typed text). */
    static void injectKey(KeyEvent e) throws Exception {
        if (e.getSource() == 0 || e.getSource() == InputDevice.SOURCE_UNKNOWN) e.setSource(InputDevice.SOURCE_KEYBOARD);
        inject.invoke(inputManager, e, INJECT_WAIT_FOR_FINISH);
    }

    static void tap(int x, int y) throws Exception {
        long now = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent e = MotionEvent.obtain(now, now, action, x, y, 0);
            e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            inject.invoke(inputManager, e, INJECT_WAIT_FOR_FINISH);
            e.recycle();
        }
    }

    static String sh(String cmd) {
        try {
            Process p = new ProcessBuilder("/system/bin/sh", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                for (String l; (l = r.readLine()) != null; ) sb.append(l).append('\n');
            }
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static void log(String msg) {
        System.out.println(new java.util.Date() + " " + msg);
    }
}
