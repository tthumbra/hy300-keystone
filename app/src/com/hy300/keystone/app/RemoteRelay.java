package com.hy300.keystone.app;

import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Phone remote: a WebSocket at /api/remote (on the HTTPS server, pairing code in ?k=) whose text
 * messages are newline-separated input commands ("m dx dy", "b buttons", "w v h", "k keycode",
 * "t text" — see helper RemoteInput). They're relayed to the helper's input channel on 127.0.0.1:38301.
 */
final class RemoteRelay {
    static final String TAG = "KeystoneRemote";
    static final int HELPER_INPUT_PORT = 38301;
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    static volatile int connectedPhones = 0;

    static boolean isUpgrade(ServerService.Request req) {
        return "GET".equals(req.method) && "/api/remote".equals(req.path)
                && "websocket".equalsIgnoreCase(req.headers.getOrDefault("upgrade", ""));
    }

    /**
     * Runs the WebSocket session until either side closes. The request parser reads headers a byte at
     * a time, so the socket's stream is positioned right at the first WebSocket frame.
     */
    static void run(Socket sock, ServerService.Request req) throws IOException {
        String key = req.headers.get("sec-websocket-key");
        OutputStream out = sock.getOutputStream();
        if (key == null) {
            out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            return;
        }
        String accept;
        try {
            accept = Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII)), Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IOException(e);
        }
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        sock.setSoTimeout(60_000);   // the phone pings every 15 s

        connectedPhones++;
        AppState.phoneConnected();
        Socket helper = null;
        try {
            DataInputStream din = new DataInputStream(sock.getInputStream());
            while (true) {
                int b0 = din.readUnsignedByte(), b1 = din.readUnsignedByte();
                int opcode = b0 & 0x0f;
                long len = b1 & 0x7f;
                if (len == 126) len = din.readUnsignedShort();
                else if (len == 127) len = din.readLong();
                if (len > 65536) throw new IOException("frame too large");
                byte[] mask = new byte[4];
                if ((b1 & 0x80) != 0) din.readFully(mask);
                byte[] payload = new byte[(int) len];
                din.readFully(payload);
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];

                if (opcode == 0x8) {                       // close
                    sendFrame(out, 0x8, new byte[0]);
                    return;
                } else if (opcode == 0x9) {                // ping
                    sendFrame(out, 0xA, payload);
                } else if (opcode == 0x1) {                // text: commands
                    if (helper == null || helper.isClosed()) helper = openHelper();
                    try {
                        writeCommands(helper, payload);
                    } catch (IOException e) {
                        helper.close();
                        helper = openHelper();             // helper restarted: reconnect once
                        writeCommands(helper, payload);
                    }
                }
            }
        } finally {
            connectedPhones--;
            if (helper != null) try { helper.close(); } catch (IOException ignored) {}
        }
    }

    private static Socket openHelper() throws IOException {
        Socket s = new Socket("127.0.0.1", HELPER_INPUT_PORT);
        s.setTcpNoDelay(true);
        s.getOutputStream().write((HelperClient.token + "\n").getBytes(StandardCharsets.UTF_8));
        s.setSoTimeout(3000);
        String reply = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
        s.setSoTimeout(0);
        if (!"ok".equals(reply)) {
            s.close();
            throw new IOException("helper refused the remote channel");
        }
        Log.i(TAG, "phone remote connected to helper");
        return s;
    }

    private static void writeCommands(Socket helper, byte[] payload) throws IOException {
        String text = new String(payload, StandardCharsets.UTF_8);
        if (!text.endsWith("\n")) text += "\n";
        helper.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        out.write(0x80 | opcode);
        if (payload.length < 126) out.write(payload.length);
        else { out.write(126); out.write(payload.length >> 8); out.write(payload.length & 0xff); }
        out.write(payload);
        out.flush();
    }

    private RemoteRelay() {}
}
