package com.hy300.keystone.app;

import android.app.Activity;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.lang.ref.WeakReference;
import java.security.MessageDigest;

/**
 * Full-screen player for a laptop's shared screen: a WebView running share-player.html, which answers
 * the laptop's WebRTC offer and plays the stream. Opened by ScreenShare when an offer arrives; closes
 * when the laptop stops sharing (or on Back).
 */
public class ShareActivity extends Activity {
    private static WeakReference<ShareActivity> current = new WeakReference<>(null);
    private WebView web;

    static void finishIfOpen() {
        ShareActivity a = current.get();
        if (a != null) a.runOnUiThread(a::finish);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        current = new WeakReference<>(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            // The page comes from the projector's own server: trust exactly its self-signed certificate.
            @Override
            public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) {
                try {
                    byte[] der = e.getCertificate().getX509Certificate().getEncoded();
                    StringBuilder fp = new StringBuilder();
                    for (byte x : MessageDigest.getInstance("SHA-256").digest(der)) fp.append(String.format("%02x", x));
                    if (e.getUrl().startsWith("https://127.0.0.1:") && fp.toString().equals(SelfSignedCert.fingerprint)) {
                        h.proceed();
                        return;
                    }
                } catch (Exception ignored) {}
                h.cancel();
            }
        });
        setContentView(web);
        web.loadUrl("https://127.0.0.1:" + ServerService.HTTPS_PORT + "/share-player.html?t=" + ScreenShare.playerToken);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            ScreenShare.stop(this);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (current.get() == this) current = new WeakReference<>(null);
        web.destroy();
        super.onDestroy();
    }
}
