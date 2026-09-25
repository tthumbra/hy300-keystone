package com.hy300.keystone.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.res.AssetManager;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;


/**
 * HTTPS server on :8443 for the phone: the calibration web page and its API (API calls need the
 * pairing code from the QR). HTTPS because browsers only allow the live camera and motion sensors on
 * secure pages; the certificate is self-signed and made on the projector (see SelfSignedCert). Plain
 * HTTP on :8080 just redirects there. Plus the
 * helper manager: if the shell-privileged helper isn't running, start it through the device's own
 * adbd on 127.0.0.1:5555 (one-time "Allow USB debugging" approval on the projector).
 */
public class ServerService extends Service {
    static final String TAG = "KeystoneServer";
    static final int HTTPS_PORT = 8443, HTTP_PORT = 8080;
    static final int MAX_BODY = 4 * 1024 * 1024;   // debug frames
    static final String SELF = "com.hy300.keystone.app/.MainActivity";
    static final long ADB_APPROVAL_WAIT_MS = 120_000;
    static final long IDLE_BACK_TO_QR_MS = 3 * 60 * 1000;

    private volatile boolean running;
    private ServerSocket https, http;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("server", "Calibration server", NotificationManager.IMPORTANCE_LOW));
        startForeground(1, new Notification.Builder(this, "server")
                .setContentTitle("Keystone calibration server")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .build());

        AppState.pairCode = randomHex(6);
        HelperClient.token = randomHex(16);

        running = true;
        new Thread(this::serveHttps, "https").start();
        new Thread(this::serveHttpRedirect, "http").start();
        new Thread(this::watchdog, "watchdog").start();
    }

    @Override
    public void onDestroy() {
        running = false;
        closeQuietly(https);
        closeQuietly(http);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------------------------------------------------------------- servers

    private void serveHttps() {
        try {
            String ip;
            while ((ip = lanAddress()) == null && running) SystemClock.sleep(1000);
            https = SelfSignedCert.sslContext(this, ip).getServerSocketFactory().createServerSocket(HTTPS_PORT);
            acceptLoop(https, true);
        } catch (Exception e) {
            Log.e(TAG, "https server failed", e);
            setHelperStatus("HTTPS server failed: " + e.getMessage());
        }
    }

    private void serveHttpRedirect() {
        try {
            http = new ServerSocket(HTTP_PORT);
            acceptLoop(http, false);
        } catch (Exception e) {
            Log.e(TAG, "http server failed", e);
        }
    }

    private void acceptLoop(ServerSocket server, boolean secure) {
        while (running) {
            try {
                Socket s = server.accept();
                new Thread(() -> handle(s, secure)).start();
            } catch (Exception e) {
                if (running) Log.w(TAG, "accept: " + e);
            }
        }
    }

    private void watchdog() {
        long nextHelperAttempt = 0;
        while (running) {
            String ip = lanAddress();
            // fp: certificate fingerprint for the iOS app to pin (the web page ignores it). Left out until the
            // HTTPS server has loaded the certificate.
            String fp = SelfSignedCert.fingerprint;
            String pair = ip == null || fp.isEmpty() ? "" : "https://" + ip + ":" + HTTPS_PORT + "/?k=" + AppState.pairCode + "&fp=" + fp;
            if (!pair.equals(AppState.pairUrl)) {
                Log.i(TAG, "pairing link: " + pair);
                AppState.pairUrl = pair;
                AppState.changed();
            }

            boolean up = HelperClient.accepted();
            if (up != AppState.helperUp) {
                AppState.helperUp = up;
                if (up) AppState.helperStatus = "running";
                AppState.changed();
            }
            if (!up && System.currentTimeMillis() >= nextHelperAttempt) {
                boolean started = startHelper();
                nextHelperAttempt = System.currentTimeMillis() + (started ? 5_000 : 20_000);
            }

            if (AppState.mode == AppState.Mode.PATTERN && !AppState.applying
                    && System.currentTimeMillis() - AppState.lastApiRequest > IDLE_BACK_TO_QR_MS) {
                AppState.setMode(AppState.Mode.QR);
            }
            SystemClock.sleep(up ? 3000 : 1000);
        }
    }

    /** Launches the helper (bundled in this APK) as the shell user via the device's own adbd. */
    private boolean startHelper() {
        setHelperStatus("connecting to adb… if the projector asks \"Allow USB debugging?\", tick Always allow and press OK");
        try (AdbClient adb = AdbClient.connect(this, "127.0.0.1", 5555, ADB_APPROVAL_WAIT_MS)) {
            setHelperStatus("starting helper…");
            String apk = getApplicationInfo().sourceDir;
            // Separate shells: pkill -f would match a shell whose command line also holds the launch.
            // The launching shell must stay up a few seconds: if it exits (and adb closes the stream)
            // while app_process is still starting, the helper dies.
            adb.shell("pkill -f '[c]om.hy300.keystone.Helper'; true", 5000);
            adb.shell("CLASSPATH=" + apk + " setsid nohup app_process /system/bin com.hy300.keystone.Helper"
                    + " --token " + HelperClient.token
                    + " >/data/local/tmp/ks-helper.log 2>&1 </dev/null & sleep 3; true", 10000);
            for (int i = 0; i < 20; i++) {
                SystemClock.sleep(250);
                if (HelperClient.accepted()) return true;
            }
            setHelperStatus("helper did not start (see /data/local/tmp/ks-helper.log)");
            return false;
        } catch (AdbClient.NeedsApproval e) {
            setHelperStatus("waiting for adb approval on the projector — retrying");
            return false;
        } catch (java.net.ConnectException e) {
            setHelperStatus("adb is not listening on port 5555 — enable network debugging");
            return false;
        } catch (Exception e) {
            Log.w(TAG, "startHelper", e);
            setHelperStatus("could not start helper: " + e.getMessage());
            return false;
        }
    }

    private static void setHelperStatus(String s) {
        AppState.helperStatus = s;
        AppState.changed();
    }

    // ---------------------------------------------------------------- requests

    static final class Request {
        String method, path, query = "";
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];

        String param(String name) {
            for (String kv : query.split("&")) {
                int i = kv.indexOf('=');
                if (i > 0 && kv.substring(0, i).equals(name)) return kv.substring(i + 1);
            }
            return "";
        }
    }

    static final class Response {
        final int code;
        final String type;
        final byte[] body;
        String location;
        Response(int code, String type, byte[] body) { this.code = code; this.type = type; this.body = body; }

        static Response json(int code, JSONObject o) {
            return new Response(code, "application/json", o.toString().getBytes(StandardCharsets.UTF_8));
        }

        static Response json(int code, String raw) {
            return new Response(code, "application/json", raw.getBytes(StandardCharsets.UTF_8));
        }

        static Response error(int code, String msg) {
            try {
                return json(code, new JSONObject().put("ok", false).put("error", msg));
            } catch (Exception e) {
                return new Response(500, "text/plain", new byte[0]);
            }
        }
    }

    private void handle(Socket sock, boolean secure) {
        try (Socket s = sock) {
            s.setSoTimeout(15000);
            Request req = parse(s.getInputStream());
            if (req == null) return;
            Response res;
            try {
                res = secure ? route(req) : redirect(req);
            } catch (Exception e) {
                Log.e(TAG, "handler", e);
                res = Response.error(500, String.valueOf(e.getMessage()));
            }
            write(s.getOutputStream(), res);
        } catch (Exception e) {
            Log.w(TAG, "connection: " + e);
        }
    }

    private Response redirect(Request req) {
        String ip = lanAddress();
        String target = "https://" + (ip == null ? "127.0.0.1" : ip) + ":" + HTTPS_PORT + req.path
                + (req.query.isEmpty() ? "" : "?" + req.query);
        Response r = new Response(301, "text/plain", ("Moved to " + target).getBytes(StandardCharsets.UTF_8));
        r.location = target;
        return r;
    }

    private Response route(Request req) throws Exception {
        if (!req.path.startsWith("/api/")) {
            if (!req.method.equals("GET")) return Response.error(405, "method not allowed");
            return staticFile("web", req.path.equals("/") ? "/index.html" : req.path);
        }

        String given = req.headers.containsKey("x-pair") ? req.headers.get("x-pair") : req.param("k");
        if (!MessageDigest.isEqual(given.getBytes(), AppState.pairCode.getBytes()))
            return Response.error(401, "bad pairing code: rescan the QR on the projector");
        AppState.lastApiRequest = System.currentTimeMillis();

        String route = req.method + " " + req.path;
        switch (route) {
            case "POST /api/session": {
                AppState.setMode(AppState.Mode.PATTERN);
                DisplayMetrics m = new DisplayMetrics();
                getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(m);
                JSONObject out = new JSONObject()
                        .put("ok", true)
                        .put("pattern", Pattern.spec(m.widthPixels, m.heightPixels))
                        .put("helperUp", HelperClient.health())
                        .put("layouts", layouts());
                try {
                    out.put("keystone", new JSONObject(HelperClient.call("GET", "/state", null, 3000).body));
                } catch (Exception e) {
                    out.put("keystone", JSONObject.NULL);
                }
                return Response.json(200, out);
            }
            case "POST /api/pattern": {
                boolean show = new JSONObject(new String(req.body, StandardCharsets.UTF_8)).optBoolean("show", true);
                AppState.setMode(show ? AppState.Mode.PATTERN : AppState.Mode.QR);
                return Response.json(200, new JSONObject().put("ok", true));
            }
            case "GET /api/layout":
                return Response.json(200, new JSONObject().put("ok", true).put("layouts", layouts()));
            case "POST /api/layout": {
                // The page/app measured which wall corner each value moves (test nudge) for an installmode;
                // remembered so later calibrations can skip the nudge. {"installmode", "flipX", "flipY"} or
                // {"installmode", "forget": true}.
                JSONObject b = new JSONObject(new String(req.body, StandardCharsets.UTF_8));
                int mode = b.getInt("installmode");
                if (mode < 0 || mode > 3) return Response.error(400, "bad installmode");
                android.content.SharedPreferences.Editor e = getSharedPreferences("layouts", MODE_PRIVATE).edit();
                if (b.optBoolean("forget")) e.remove("mode" + mode);
                else e.putString("mode" + mode, (b.getBoolean("flipX") ? "1" : "0") + (b.getBoolean("flipY") ? "1" : "0"));
                e.apply();
                return Response.json(200, new JSONObject().put("ok", true).put("layouts", layouts()));
            }
            case "POST /api/debug-frame":
                return Response.json(200, new JSONObject().put("ok", true).put("saved", saveDebugFrame(req)));
            case "GET /api/debug-list": {
                org.json.JSONArray names = new org.json.JSONArray();
                java.io.File[] files = debugDir().listFiles();
                if (files != null) {
                    java.util.Arrays.sort(files);
                    for (java.io.File f : files) names.put(f.getName());
                }
                return Response.json(200, new JSONObject().put("ok", true).put("files", names));
            }
            case "GET /api/debug-get": {
                String name = req.param("name");
                if (!name.matches("frame-[0-9]+\\.(jpg|txt)")) return Response.error(400, "bad name");
                java.io.File f = new java.io.File(debugDir(), name);
                if (!f.isFile()) return Response.error(404, "not found");
                return new Response(200, name.endsWith(".jpg") ? "image/jpeg" : "text/plain; charset=utf-8",
                        java.nio.file.Files.readAllBytes(f.toPath()));
            }
            case "POST /api/ping":
                return Response.json(200, new JSONObject().put("ok", true));
            case "POST /api/done":
                AppState.setMode(AppState.Mode.QR);
                return Response.json(200, new JSONObject().put("ok", true));
            case "GET /api/state": {
                HelperClient.Result r = HelperClient.call("GET", "/state", null, 3000);
                return Response.json(r.code, r.body);
            }
            case "POST /api/apply": {
                JSONObject body = new JSONObject(new String(req.body, StandardCharsets.UTF_8));
                JSONObject fwd = new JSONObject();
                for (String k : new String[]{"ltx", "lty", "rtx", "rty", "lbx", "lby", "rbx", "rby"}) {
                    if (!body.has(k)) return Response.error(400, "missing " + k);
                    fwd.put(k, body.getInt(k));
                }
                fwd.put("return", SELF);
                AppState.applying = true;
                AppState.changed();
                try {
                    HelperClient.Result r = HelperClient.call("POST", "/apply", fwd.toString(), 60000);
                    return Response.json(r.code, r.body);
                } finally {
                    AppState.applying = false;
                    AppState.lastApiRequest = System.currentTimeMillis();
                    AppState.changed();
                }
            }
            default:
                return Response.error(404, "not found");
        }
    }

    private Response staticFile(String dir, String path) {
        if (path.contains("..") || !path.matches("/[A-Za-z0-9_./-]+")) return Response.error(400, "bad path");
        String name = (dir.isEmpty() ? "" : dir) + path;
        if (name.startsWith("/")) name = name.substring(1);
        try (InputStream in = getAssets().open(name, AssetManager.ACCESS_STREAMING)) {
            return new Response(200, contentType(path), readAll(in));
        } catch (Exception e) {
            return Response.error(404, "not found");
        }
    }

    static String contentType(String p) {
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    // ---------------------------------------------------------------- plumbing

    static Request parse(InputStream in) throws Exception {
        String line = readLine(in);
        if (line == null || line.isEmpty()) return null;
        String[] parts = line.split(" ");
        if (parts.length < 2) return null;
        Request r = new Request();
        r.method = parts[0];
        String target = parts[1];
        int q = target.indexOf('?');
        r.path = q >= 0 ? target.substring(0, q) : target;
        if (q >= 0) r.query = target.substring(q + 1);
        for (String h; (h = readLine(in)) != null && !h.isEmpty(); ) {
            int c = h.indexOf(':');
            if (c > 0) r.headers.put(h.substring(0, c).trim().toLowerCase(), h.substring(c + 1).trim());
        }
        int len = Integer.parseInt(r.headers.getOrDefault("content-length", "0"));
        if (len < 0 || len > MAX_BODY) throw new Exception("body too large");
        r.body = new byte[len];
        for (int n = 0; n < len; ) {
            int k = in.read(r.body, n, len - n);
            if (k < 0) break;
            n += k;
        }
        return r;
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

    static void write(OutputStream o, Response r) throws Exception {
        String head = "HTTP/1.1 " + r.code + " X\r\n"
                + "Content-Type: " + r.type + "\r\n"
                + "Content-Length: " + r.body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + (r.location != null ? "Location: " + r.location + "\r\n" : "")
                + "Connection: close\r\n\r\n";
        o.write(head.getBytes(StandardCharsets.UTF_8));
        o.write(r.body);
        o.flush();
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static String lanAddress() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    if (ni.getName().startsWith("wlan") || ni.getName().startsWith("eth")) return a.getHostAddress();
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Development aid: the page uploads frames it couldn't analyse, or all frames while "Share camera view" is on (newest 200 kept, private storage).
     * Fetch them with the pairing code: GET /api/debug-list, GET /api/debug-get?name=<file>.
     */
    private java.io.File debugDir() { return new java.io.File(getFilesDir(), "debug"); }

    private String saveDebugFrame(Request req) throws Exception {
        java.io.File dir = debugDir();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("no debug dir");
        String base = "frame-" + System.currentTimeMillis();
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(dir, base + ".jpg"))) { o.write(req.body); }
        String info = req.headers.getOrDefault("x-debug-info", "");
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(dir, base + ".txt"))) {
            o.write(java.net.URLDecoder.decode(info, "UTF-8").getBytes(StandardCharsets.UTF_8));
        }
        java.io.File[] files = dir.listFiles();
        if (files != null && files.length > 400) {
            java.util.Arrays.sort(files);
            for (int i = 0; i < files.length - 400; i++) files[i].delete();
        }
        return base;
    }

    /** Remembered layouts: {"<installmode>": {"flipX": bool, "flipY": bool}}. */
    private JSONObject layouts() throws Exception {
        JSONObject out = new JSONObject();
        android.content.SharedPreferences prefs = getSharedPreferences("layouts", MODE_PRIVATE);
        for (int mode = 0; mode < 4; mode++) {
            String v = prefs.getString("mode" + mode, null);
            if (v != null && v.length() == 2) {
                out.put(String.valueOf(mode), new JSONObject().put("flipX", v.charAt(0) == '1').put("flipY", v.charAt(1) == '1'));
            }
        }
        return out;
    }

    static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static void closeQuietly(ServerSocket s) {
        try { if (s != null) s.close(); } catch (Exception ignored) {}
    }
}
