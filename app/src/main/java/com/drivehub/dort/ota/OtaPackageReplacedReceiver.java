package com.drivehub.dort.ota;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Uygulama OTA ile yenilendikten sonra İndirilenler’deki eski APK’ları siler. */
public final class OtaPackageReplacedReceiver extends BroadcastReceiver {

    private static final String TAG = "MG4_OTA";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        Log.i(TAG, "MY_PACKAGE_REPLACED → eski OTA APK temizliği");
        OtaCleanup.deleteAllOtaApks(context);
    }
}
