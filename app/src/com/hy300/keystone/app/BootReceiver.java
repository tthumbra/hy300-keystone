package com.hy300.keystone.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Starts the server (and so the helper) at boot, and shows the phone-remote QR in a corner. Also after
 * the app is updated, since an update stops the running server.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)) {
            ctx.startForegroundService(new Intent(ctx, ServerService.class).putExtra("boot", true));
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            ctx.startForegroundService(new Intent(ctx, ServerService.class));
        }
    }
}
