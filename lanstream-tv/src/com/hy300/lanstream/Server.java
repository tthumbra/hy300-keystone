package com.hy300.lanstream;

import android.text.Html;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Talks to a LAN Stream server (lanstream.py on the PC): finds it on the Wi-Fi, reads its library.
 *
 * The server has no JSON list of videos, so the library comes from its home page, whose cards look like
 *   <a class="card" data-id="ID" href="/watch/ID"><b>TITLE</b><small>FILE · SIZE</small>...
 * Per-video details are JSON at /api/media/ID and the stream is HLS at /hls/ID/AUDIO/QUALITY/index.m3u8.
 */
final class Server {
    static final int DEFAULT_PORT = 8090;
    private static final Pattern CARD = Pattern.compile(
            "<a class=\"card\" data-id=\"([0-9a-f]+)\"[^>]*><b>(.*?)</b><small>(.*?)</small>", Pattern.DOTALL);

    static final class Video {
        final String id, title, detail;
        Video(String id, String title, String detail) { this.id = id; this.title = title; this.detail = detail; }
    }

    static String get(String url, int connectMs, int readMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    /** "host:port" (port optional) -> "http://host:port", or null if it isn't an address. */
    static String baseUrl(String typed) {
        String s = typed.trim().replaceFirst("^https?://", "").replaceFirst("/.*$", "");
        if (!s.matches("[A-Za-z0-9.-]+(:\\d{1,5})?")) return null;
        return "http://" + (s.contains(":") ? s : s + ":" + DEFAULT_PORT);
    }

    static boolean isLanStream(String base) {
        try {
            return get(base + "/", 1500, 6000).contains("<title>LAN Stream</title>");
        } catch (Exception e) {
            return false;
        }
    }

    static List<Video> library(String base) throws Exception {
        String page = get(base + "/", 3000, 20000);
        if (!page.contains("<title>LAN Stream</title>")) throw new Exception("That address isn't a LAN Stream server");
        List<Video> out = new ArrayList<>();
        Matcher m = CARD.matcher(page);
        while (m.find()) out.add(new Video(m.group(1), text(m.group(2)), text(m.group(3))));
        return out;
    }

    private static String text(String html) { return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim(); }

    /** This device's Wi-Fi/Ethernet IPv4 address, or null when it has none. */
    static String ownAddress() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Every LAN Stream server among the 254 neighbours of this device (same first three numbers of the
     * address), on the given port. Takes about two seconds.
     */
    static List<String> scan(int port) {
        final List<String> found = Collections.synchronizedList(new ArrayList<String>());
        String own = ownAddress();
        if (own == null) return found;
        final String prefix = own.substring(0, own.lastIndexOf('.') + 1);
        ExecutorService pool = Executors.newFixedThreadPool(48);
        for (int i = 1; i <= 254; i++) {
            final String host = prefix + i;
            pool.execute(() -> {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(host, port), 700);
                } catch (Exception e) {
                    return;
                }
                String base = "http://" + host + ":" + port;
                if (isLanStream(base)) found.add(base);
            });
        }
        pool.shutdown();
        try { pool.awaitTermination(20, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        Collections.sort(found);
        return found;
    }

    private Server() {}
}
