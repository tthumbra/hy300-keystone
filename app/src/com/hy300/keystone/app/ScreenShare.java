package com.hy300.keystone.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

/**
 * Laptop screen sharing ("cast a tab/window/screen"): a browser page at /share captures the screen
 * with getDisplayMedia and streams it by WebRTC to a WebView player on the projector (ShareActivity).
 * This class is the server side: pairing a computer with a PIN shown on the projector, and passing the
 * WebRTC offer/answer between the two pages.
 *
 * Browsers hide their LAN addresses in WebRTC candidates behind random ".local" names; the projector
 * may not resolve those, so the SDP passing through here gets the real addresses filled in.
 */
final class ScreenShare {
    static final String TAG = "KeystoneShare";
    private static final SecureRandom rnd = new SecureRandom();

    /** Token the projector's own player page uses (it's loaded from 127.0.0.1 with this in the URL). */
    static final String playerToken = ServerService.randomHex(16);

    private static String pin, pinRequest;
    private static int pinAttempts;
    private static long pinExpires;

    private static String offer, answer, offerFrom;
    private static long session;

    // ---------------------------------------------------------------- pairing a computer

    static synchronized JSONObject requestPin(Context ctx) throws Exception {
        pin = String.format("%04d", rnd.nextInt(10000));
        pinRequest = ServerService.randomHex(8);
        pinAttempts = 0;
        pinExpires = System.currentTimeMillis() + 120_000;
        CornerNote.show(ctx, "Screen share", "PIN  " + pin, 120_000);
        return new JSONObject().put("ok", true).put("request", pinRequest);
    }

    static synchronized JSONObject checkPin(Context ctx, JSONObject body) throws Exception {
        if (pin == null || System.currentTimeMillis() > pinExpires || !pinRequest.equals(body.optString("request")))
            return new JSONObject().put("ok", false).put("error", "The PIN expired — ask for a new one.");
        if (++pinAttempts > 5) {
            pin = null;
            CornerNote.hide(ctx);
            return new JSONObject().put("ok", false).put("error", "Too many wrong PINs — ask for a new one.");
        }
        if (!MessageDigest.isEqual(pin.getBytes(), body.optString("pin").trim().getBytes()))
            return new JSONObject().put("ok", false).put("error", "Wrong PIN.");
        pin = null;
        CornerNote.hide(ctx);
        String token = ServerService.randomHex(16);
        SharedPreferences p = ctx.getSharedPreferences("share", Context.MODE_PRIVATE);
        Set<String> tokens = new HashSet<>(p.getStringSet("tokens", new HashSet<>()));
        tokens.add(token);
        p.edit().putStringSet("tokens", tokens).apply();
        return new JSONObject().put("ok", true).put("token", token);
    }

    static boolean isPaired(Context ctx, String token) {
        if (token == null || token.isEmpty()) return false;
        for (String t : ctx.getSharedPreferences("share", Context.MODE_PRIVATE).getStringSet("tokens", new HashSet<>()))
            if (MessageDigest.isEqual(t.getBytes(), token.getBytes())) return true;
        return false;
    }

    // ---------------------------------------------------------------- WebRTC signalling

    /** Laptop sends its offer: open the player, which picks it up. */
    static synchronized JSONObject offer(Context ctx, JSONObject body, String peerIp) throws Exception {
        offer = fillHostAddresses(body.getString("sdp"), peerIp);
        offerFrom = peerIp;
        answer = null;
        session++;
        Log.i(TAG, "offer from " + peerIp + ", session " + session);
        ctx.startActivity(new Intent(ctx, ShareActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        return new JSONObject().put("ok", true).put("session", session);
    }

    static synchronized JSONObject pendingOffer() throws Exception {
        JSONObject o = new JSONObject().put("ok", true).put("session", session);
        if (offer != null && answer == null) o.put("sdp", offer);
        return o;
    }

    /** Player's answer, with the projector's LAN address filled in. */
    static synchronized JSONObject answer(JSONObject body, String projectorIp) throws Exception {
        if (body.optLong("session") != session) return new JSONObject().put("ok", false).put("error", "stale session");
        answer = fillHostAddresses(body.getString("sdp"), projectorIp);
        return new JSONObject().put("ok", true);
    }

    static synchronized JSONObject pollAnswer(long forSession) throws Exception {
        JSONObject o = new JSONObject().put("ok", true);
        if (forSession == session && answer != null) o.put("sdp", answer);
        if (forSession != session) o.put("replaced", true);
        return o;
    }

    static synchronized void stop(Context ctx) {
        offer = null;
        answer = null;
        session++;
        ShareActivity.finishIfOpen();
    }

    /** Replaces ".local" (mDNS) host candidate addresses with the given IPv4 address. */
    static String fillHostAddresses(String sdp, String ip) {
        if (ip == null || !ip.matches("[0-9.]+")) return sdp;
        StringBuilder out = new StringBuilder();
        for (String line : sdp.split("\r\n")) {
            if (line.startsWith("a=candidate:")) {
                String[] f = line.split(" ");
                // a=candidate:<foundation> <component> <proto> <priority> <address> <port> typ <type> ...
                if (f.length > 7 && f[4].endsWith(".local")) {
                    f[4] = ip;
                    line = String.join(" ", f);
                }
                if (f.length > 4 && f[4].contains(":")) continue;   // IPv6: not needed on the LAN
            }
            out.append(line).append("\r\n");
        }
        return out.toString();
    }

    private ScreenShare() {}
}
