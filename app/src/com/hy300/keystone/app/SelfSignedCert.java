package com.hy300.keystone.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * Self-signed TLS certificate made on the projector the first time it's needed and then kept, so a
 * phone only has to accept the browser warning once. (Browsers only allow the live camera and motion
 * sensors on HTTPS pages.) Android has no public certificate builder, so this writes the DER by hand.
 */
final class SelfSignedCert {
    private static final char[] PASSWORD = "keystone".toCharArray();

    /** SHA-256 of the certificate (hex), put in the QR link so the iOS app can pin exactly this cert. */
    static volatile String fingerprint = "";

    /** Fingerprint of the saved certificate, read without loading the key (fast; for the QR at boot). */
    static void loadFingerprint(Context ctx) {
        try {
            File certFile = new File(ctx.getFilesDir(), "tls.crt");
            if (!certFile.exists() || !fingerprint.isEmpty()) return;
            StringBuilder fp = new StringBuilder();
            for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(certFile.toPath())))
                fp.append(String.format("%02x", b));
            fingerprint = fp.toString();
        } catch (Exception ignored) {}
    }

    static SSLContext sslContext(Context ctx, String ip) throws Exception {
        File keyFile = new File(ctx.getFilesDir(), "tls.pk8"), certFile = new File(ctx.getFilesDir(), "tls.crt");
        if (!keyFile.exists() || !certFile.exists()) create(ip, keyFile, certFile);
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile.toPath())));
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(Files.readAllBytes(certFile.toPath())));
        StringBuilder fp = new StringBuilder();
        for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(cert.getEncoded())) fp.append(String.format("%02x", b));
        fingerprint = fp.toString();
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("tls", key, PASSWORD, new Certificate[]{cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASSWORD);
        SSLContext c = SSLContext.getInstance("TLS");
        c.init(kmf.getKeyManagers(), null, null);
        return c;
    }

    private static void create(String ip, File keyFile, File certFile) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();

        byte[] sha256WithRsa = seq(oid(1, 2, 840, 113549, 1, 1, 11), new byte[]{0x05, 0x00});
        byte[] name = seq(set(seq(oid(2, 5, 4, 3), tlv(0x0c, "HY300 Keystone".getBytes("UTF-8")))));
        long now = System.currentTimeMillis();
        byte[] validity = seq(utcTime(new Date(now - 86_400_000L)), utcTime(new Date(now + 800L * 86_400_000L)));
        byte[] serial = new byte[16];
        new SecureRandom().nextBytes(serial);
        serial[0] &= 0x7f;

        ByteArrayOutputStream exts = new ByteArrayOutputStream();
        if (ip != null) {
            byte[] san = seq(tlv(0x87, InetAddress.getByName(ip).getAddress()));   // [7] iPAddress
            exts.write(seq(oid(2, 5, 29, 17), tlv(0x04, san)));
        }
        exts.write(seq(oid(2, 5, 29, 37), tlv(0x04, seq(oid(1, 3, 6, 1, 5, 5, 7, 3, 1)))));   // EKU serverAuth

        byte[] tbs = seq(
                tlv(0xa0, tlv(0x02, new byte[]{2})),          // version v3
                tlv(0x02, serial),
                sha256WithRsa,
                name,
                validity,
                name,
                kp.getPublic().getEncoded(),                   // SubjectPublicKeyInfo
                tlv(0xa3, seq(exts.toByteArray())));

        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bitString = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bitString, 1, sig.length);
        byte[] cert = seq(tbs, sha256WithRsa, tlv(0x03, bitString));

        write(keyFile, kp.getPrivate().getEncoded());
        write(certFile, cert);
    }

    private static void write(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(data); }
        if (!tmp.renameTo(f)) throw new IOException("could not write " + f);
    }

    // ---------------------------------------------------------------- DER

    static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int n = value.length;
        if (n < 0x80) o.write(n);
        else if (n < 0x100) { o.write(0x81); o.write(n); }
        else if (n < 0x10000) { o.write(0x82); o.write(n >> 8); o.write(n & 0xff); }
        else { o.write(0x83); o.write(n >> 16); o.write((n >> 8) & 0xff); o.write(n & 0xff); }
        o.write(value, 0, n);
        return o.toByteArray();
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    static byte[] seq(byte[]... parts) { return tlv(0x30, concat(parts)); }

    static byte[] set(byte[]... parts) { return tlv(0x31, concat(parts)); }

    static byte[] oid(int... arcs) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(arcs[0] * 40 + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            long v = arcs[i];
            byte[] tmp = new byte[10];
            int n = 0;
            do { tmp[n++] = (byte) (v & 0x7f); v >>= 7; } while (v > 0);
            for (int j = n - 1; j >= 0; j--) o.write(tmp[j] | (j > 0 ? 0x80 : 0));
        }
        return tlv(0x06, o.toByteArray());
    }

    static byte[] utcTime(Date d) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(d).getBytes("US-ASCII"));
    }

    private SelfSignedCert() {}
}
