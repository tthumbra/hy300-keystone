package com.hy300.keystone.app;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/** Process-wide state shared between the server threads and the activity. */
final class AppState {
    enum Mode { QR, PATTERN }

    interface Listener { void onStateChanged(); }

    static volatile Mode mode = Mode.QR;
    static volatile String pairUrl = "";   // https://ip:8443/?k=pairCode&fp=certSha256 (the phone page / iOS app)
    static volatile String pairCode = "";
    static volatile boolean helperUp = false;
    static volatile String helperStatus = "starting…";
    static volatile boolean applying = false;
    static volatile long lastApiRequest = 0;

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    static void addListener(Listener l) { listeners.add(l); }
    static void removeListener(Listener l) { listeners.remove(l); }

    static void setMode(Mode m) {
        if (mode == m) return;
        mode = m;
        changed();
    }

    static void changed() {
        main.post(() -> { for (Listener l : listeners) l.onStateChanged(); });
    }

    private AppState() {}
}
