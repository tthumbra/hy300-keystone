package com.hy300.keystone.app;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.RSAPublicKeySpec;

import javax.crypto.Cipher;

/**
 * Minimal ADB client (legacy TCP transport, as used on port 5555) so the app can get a shell on its
 * own device via 127.0.0.1 — used only to start the helper with app_process. The first connection
 * shows the "Allow USB debugging?" prompt on the projector; after "Always allow" it is silent.
 */
final class AdbClient implements AutoCloseable {
    static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    static final int VERSION = 0x01000001, MAX_DATA = 256 * 1024;
    static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    static final byte[] SHA1_DIGEST_INFO = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a,
            0x05, 0x00, 0x04, 0x14};

    static final class Message {
        int command, arg0, arg1;
        byte[] data;
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextLocalId = 1;

    /** Thrown when adbd is waiting for the user to accept this app's key on the projector. */
    static final class NeedsApproval extends IOException {
        NeedsApproval() { super("waiting for 'Allow USB debugging' on the projector"); }
    }

    /**
     * Connects and authenticates. If the key is not yet trusted, offers it (which shows the prompt)
     * and waits up to approvalWaitMs for the user; throws NeedsApproval if that runs out.
     */
    static AdbClient connect(Context ctx, String host, int port, long approvalWaitMs) throws Exception {
        KeyPair keys = loadOrCreateKey(ctx);
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 3000);
        AdbClient c = new AdbClient(s);
        try {
            c.send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
            boolean offeredKey = false;
            while (true) {
                s.setSoTimeout(offeredKey ? (int) approvalWaitMs : 5000);
                Message m;
                try {
                    m = c.read();
                } catch (java.net.SocketTimeoutException e) {
                    throw offeredKey ? new NeedsApproval() : e;
                }
                if (m.command == CNXN) {
                    s.setSoTimeout(0);
                    return c;
                }
                if (m.command != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("unexpected adb message " + m.command);
                if (!offeredKey && !c.signedOnce) {
                    c.signedOnce = true;
                    c.send(AUTH, AUTH_SIGNATURE, 0, sign(keys.getPrivate(), m.data));
                } else {
                    offeredKey = true;
                    c.send(AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBlob((RSAPublicKey) keys.getPublic()));
                }
            }
        } catch (Exception e) {
            c.close();
            throw e;
        }
    }

    private boolean signedOnce;

    private AdbClient(Socket s) throws IOException {
        socket = s;
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();
    }

    /** Runs a shell command and returns its combined output. */
    String shell(String command, int timeoutMs) throws Exception {
        socket.setSoTimeout(timeoutMs);
        int localId = nextLocalId++;
        send(OPEN, localId, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int remoteId = 0;
        while (true) {
            Message m = read();
            if (m.arg1 != localId) continue;
            if (m.command == OKAY) {
                remoteId = m.arg0;
            } else if (m.command == WRTE) {
                result.write(m.data);
                send(OKAY, localId, m.arg0, new byte[0]);
            } else if (m.command == CLSE) {
                if (remoteId != 0) send(CLSE, localId, remoteId, new byte[0]);
                return result.toString("UTF-8");
            }
        }
    }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignored) {}
    }

    // ---------------------------------------------------------------- wire format

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte b : data) sum += b & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~command);
        out.write(h.array());
        out.write(data);
        out.flush();
    }

    private Message read() throws IOException {
        byte[] head = new byte[24];
        in.readFully(head);
        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int len = h.getInt();
        h.getInt(); // checksum: not checked
        if (h.getInt() != ~m.command) throw new IOException("bad adb magic");
        if (len < 0 || len > MAX_DATA) throw new IOException("bad adb length " + len);
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    // ---------------------------------------------------------------- keys

    static KeyPair loadOrCreateKey(Context ctx) throws Exception {
        File f = new File(ctx.getFilesDir(), "adbkey.pk8");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (f.exists()) {
            PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(f.toPath())));
            // Conscrypt only exposes the modulus; KeyPairGenerator("RSA") always uses exponent 65537.
            RSAPrivateKeySpec spec = kf.getKeySpec(priv, RSAPrivateKeySpec.class);
            RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(new RSAPublicKeySpec(spec.getModulus(), RSAKeyGenParameterSpec.F4));
            return new KeyPair(pub, priv);
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        File tmp = new File(ctx.getFilesDir(), "adbkey.pk8.tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(kp.getPrivate().getEncoded()); }
        if (!tmp.renameTo(f)) throw new IOException("could not save adb key");
        return kp;
    }

    /** adbd verifies RSA PKCS#1 v1.5 over the 20-byte token as if it were a SHA-1 digest. */
    static byte[] sign(PrivateKey key, byte[] token) throws Exception {
        byte[] payload = new byte[SHA1_DIGEST_INFO.length + token.length];
        System.arraycopy(SHA1_DIGEST_INFO, 0, payload, 0, SHA1_DIGEST_INFO.length);
        System.arraycopy(token, 0, payload, SHA1_DIGEST_INFO.length, token.length);
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, key);
        return c.doFinal(payload);
    }

    /** Android's adb public key format: base64(struct RSAPublicKey from android_pubkey.c) + " name\0". */
    static byte[] publicKeyBlob(RSAPublicKey pub) {
        final int words = 64; // 2048-bit
        BigInteger n = pub.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(words * 32 * 2).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words).putInt(n0inv.intValue());
        b.put(littleEndian(n, 256)).put(littleEndian(rr, 256));
        b.putInt(pub.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " keystone-calibrate@hy300\0";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] littleEndian(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] le = new byte[len];
        for (int i = 0; i < len && i < be.length; i++) le[i] = be[be.length - 1 - i];
        return le;
    }

    static byte[] readAll(InputStream s) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = s.read(buf)) > 0; ) o.write(buf, 0, n);
        return o.toByteArray();
    }
}
