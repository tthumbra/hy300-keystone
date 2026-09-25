package com.hy300.keystone;

import android.os.SystemClock;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Phone-as-remote input, on 127.0.0.1:38301 (the projector app relays the phone's WebSocket here).
 * The first line must be the helper's token; then one command per line:
 *
 *   m dx dy          move the mouse pointer (relative)
 *   b buttons        mouse buttons held: bit 1 left, 2 right, 4 middle
 *   w v h            scroll: v vertical (+ up), h horizontal (+ right), in wheel notches
 *   k keycode        press and release an Android key (BACK, HOME, DPAD_*, VOLUME_*, ...)
 *   t text           type text into the focused field
 *
 * The mouse is a virtual USB HID device created through /dev/uhid (shell is in the uhid group), so
 * Android treats it as a real mouse and shows its pointer. Keys and text are injected key events.
 */
final class RemoteInput {
    static final int PORT = 38301;

    // linux/uhid.h
    static final int UHID_DESTROY = 1, UHID_CREATE2 = 11, UHID_INPUT2 = 12;
    static final int UHID_EVENT_SIZE = 4 + 128 + 64 + 64 + 2 + 2 + 4 + 4 + 4 + 4 + 4096;   // struct uhid_event
    static final int BUS_USB = 0x03;

    /** 3 buttons, relative X/Y, vertical wheel, horizontal wheel (AC Pan). Report: 5 bytes. */
    static final byte[] MOUSE_DESCRIPTOR = bytes(
            0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x09, 0x01, 0xA1, 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x05, 0x81, 0x03,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
            0x05, 0x0C, 0x0A, 0x38, 0x02, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
            0xC0, 0xC0);

    private static RandomAccessFile uhid;
    private static int buttons;

    static void start(String token) {
        Thread t = new Thread(() -> {
            try (ServerSocket server = new ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"))) {
                Helper.log("remote input on 127.0.0.1:" + PORT);
                while (true) {
                    Socket s = server.accept();
                    new Thread(() -> serve(s, token), "remote-client").start();
                }
            } catch (Exception e) {
                Helper.log("remote input server failed: " + e);
            }
        }, "remote-input");
        t.setDaemon(true);
        t.start();
    }

    private static void serve(Socket s, String token) {
        try (Socket sock = s; BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))) {
            String first = in.readLine();
            if (first == null || !java.security.MessageDigest.isEqual(token.getBytes(), first.trim().getBytes())) return;
            sock.getOutputStream().write("ok\n".getBytes(StandardCharsets.UTF_8));
            for (String line; (line = in.readLine()) != null; ) {
                try {
                    handle(line);
                } catch (Exception e) {
                    Helper.log("remote command '" + line + "' failed: " + e);
                }
            }
        } catch (Exception e) {
            Helper.log("remote client: " + e);
        } finally {
            synchronized (RemoteInput.class) {
                if (buttons != 0) { buttons = 0; try { mouseReport(0, 0, 0, 0); } catch (Exception ignored) {} }
            }
        }
    }

    static void handle(String line) throws Exception {
        if (line.isEmpty()) return;
        char c = line.charAt(0);
        String[] p = line.split(" ");
        switch (c) {
            case 'm': move(Integer.parseInt(p[1]), Integer.parseInt(p[2])); break;
            case 'b': synchronized (RemoteInput.class) { buttons = Integer.parseInt(p[1]) & 7; mouseReport(0, 0, 0, 0); } break;
            case 'w': synchronized (RemoteInput.class) { mouseReport(0, 0, clamp(Integer.parseInt(p[1])), p.length > 2 ? clamp(Integer.parseInt(p[2])) : 0); } break;
            case 'k': Helper.key(Integer.parseInt(p[1])); break;
            case 't': if (line.length() > 2) typeText(line.substring(2)); break;
            default: break;
        }
    }

    static synchronized void move(int dx, int dy) throws Exception {
        // A HID report carries at most ±127 per axis.
        while (dx != 0 || dy != 0) {
            int sx = clamp(dx), sy = clamp(dy);
            mouseReport(sx, sy, 0, 0);
            dx -= sx;
            dy -= sy;
        }
    }

    static int clamp(int v) { return Math.max(-127, Math.min(127, v)); }

    private static void mouseReport(int dx, int dy, int wheel, int pan) throws Exception {
        ensureMouse();
        ByteBuffer ev = ByteBuffer.allocate(UHID_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        ev.putInt(UHID_INPUT2);
        ev.putShort((short) 5);
        ev.put((byte) buttons).put((byte) dx).put((byte) dy).put((byte) wheel).put((byte) pan);
        uhid.write(ev.array());
    }

    private static void ensureMouse() throws Exception {
        if (uhid != null) return;
        RandomAccessFile f = new RandomAccessFile("/dev/uhid", "rw");
        ByteBuffer ev = ByteBuffer.allocate(UHID_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        ev.putInt(UHID_CREATE2);
        putFixed(ev, "HY300 Phone Remote Mouse", 128);
        putFixed(ev, "hy300-remote", 64);
        putFixed(ev, "", 64);
        ev.putShort((short) MOUSE_DESCRIPTOR.length);
        ev.putShort((short) BUS_USB);
        ev.putInt(0x1209);   // vendor: pid.codes (open-source test IDs)
        ev.putInt(0x0001);   // product
        ev.putInt(1);        // version
        ev.putInt(0);        // country
        ev.put(MOUSE_DESCRIPTOR);
        f.write(ev.array());
        uhid = f;
        Helper.log("virtual mouse created");
        // Keeping the file open keeps the device; it disappears when the helper exits.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                ByteBuffer d = ByteBuffer.allocate(UHID_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
                d.putInt(UHID_DESTROY);
                f.write(d.array());
                f.close();
            } catch (Exception ignored) {}
        }));
    }

    private static void putFixed(ByteBuffer b, String s, int size) {
        byte[] v = s.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(v.length, size - 1);
        b.put(v, 0, n);
        for (int i = n; i < size; i++) b.put((byte) 0);
    }

    /** Types text as key events from the virtual keyboard's character map (like `input text`). */
    static void typeText(String text) throws Exception {
        KeyCharacterMap kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            i += Character.charCount(cp);
            KeyEvent[] events = kcm.getEvents(ch.toCharArray());
            if (events != null) {
                for (KeyEvent e : events) Helper.injectKey(e);
            } else {
                // Not on the keyboard map (emoji, accents, ...): a single "characters" key event.
                long now = SystemClock.uptimeMillis();
                Helper.injectKey(new KeyEvent(now, ch, KeyCharacterMap.VIRTUAL_KEYBOARD, 0));
            }
        }
    }

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    private RemoteInput() {}
}
