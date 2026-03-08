package com.drivehub.dort.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.drivehub.dort.service.MG4ControlService;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                "android.intent.action.LOCKED_BOOT_COMPLETED".equals(action) ||
                "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
                "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            context.startForegroundService(new Intent(context, MG4ControlService.class));
        }
    }
}