package com.hy300.keystone.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

public class MainActivity extends Activity implements AppState.Listener {
    private ScreenView view;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new ScreenView(this);
        setContentView(view);
        startForegroundService(new Intent(this, ServerService.class));
        closeUnlessCalibrating();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        closeUnlessCalibrating();
    }

    /**
     * This screen is only for the calibration pattern (a phone's /api/session opens it). Opened any other
     * way, e.g. from the launcher, it just shows the small pairing QR in a corner and goes away.
     */
    private void closeUnlessCalibrating() {
        // From the launcher: always just the QR, even if a calibration was left open on the phone.
        boolean fromLauncher = getIntent() != null && getIntent().hasCategory(Intent.CATEGORY_LAUNCHER);
        if (fromLauncher && AppState.mode == AppState.Mode.PATTERN) AppState.setMode(AppState.Mode.QR);
        if (AppState.mode == AppState.Mode.PATTERN) return;
        AppState.bootQrPending = true;    // the server shows it now, or as soon as the link is ready
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        AppState.addListener(this);
        view.invalidate();
    }

    @Override
    protected void onPause() {
        AppState.removeListener(this);
        super.onPause();
    }

    @Override
    public void onStateChanged() {
        if (AppState.mode != AppState.Mode.PATTERN) { finish(); return; }   // calibration ended
        view.invalidate();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Menu: new pairing code (phones paired before must scan again).
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            ServerService.resetPairing(this);
            return true;
        }
        // Remote: OK toggles QR <-> pattern (handy for checking the pattern by eye).
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            AppState.setMode(AppState.mode == AppState.Mode.QR ? AppState.Mode.PATTERN : AppState.Mode.QR);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        // The server keeps running (phone remote); only the screen closes.
        AppState.setMode(AppState.Mode.QR);
        finish();
    }
}
