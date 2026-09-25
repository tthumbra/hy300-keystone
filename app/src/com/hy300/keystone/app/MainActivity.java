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
        // For testing the boot overlay: am start -n com.hy300.keystone.app/.MainActivity --ez corner_qr true
        if (getIntent().getBooleanExtra("corner_qr", false)) {
            AppState.bootQrPending = true;
            finish();
        }
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
