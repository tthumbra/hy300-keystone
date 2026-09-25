package com.hy300.keystone.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the server (and so the helper) at boot, and shows the phone-remote QR in a corner. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            ctx.startForegroundService(new Intent(ctx, ServerService.class).putExtra("boot", true));
        }
    }
}
