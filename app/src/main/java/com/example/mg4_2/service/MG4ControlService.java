package com.example.mg4_2.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.example.mg4_2.hardware.MG4Hardware;
import com.example.mg4_2.model.DriveMode;
import com.example.mg4_2.model.RegenLevel;

public class MG4ControlService extends Service {

    private static final String TAG = "MG4_SERVICE";
    private static final String CHANNEL_ID = "mg4_channel";
    private static final int    NOTIF_ID   = 1001;

    private static final String HARDKEY_ACTION   = "com.saic.keyevent.hardkey.report";
    private static final int    HARDKEY_FAVORITE = 66;
    private static final int    KEY_DOWN         = 0;
    private static final long   DEBOUNCE_MS      = 500;

    private DriveMode  mCurrentDriveMode = DriveMode.NORMAL;
    private RegenLevel mCurrentRegen     = RegenLevel.MEDIUM;
    private long       mLastKeyDownTime  = 0L;
    private BroadcastReceiver mHardkeyReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Hazır", "Sürüş: " + mCurrentDriveMode.label));
        registerHardkeyReceiver();

        Log.i(TAG, "vehiclesetting: " + (MG4Hardware.isServiceAvailable(MG4Hardware.SERVICE_VEHICLE_SETTING) ? "✓" : "✗"));
        Log.i(TAG, "aircondition  : " + (MG4Hardware.isServiceAvailable(MG4Hardware.SERVICE_AIR_CONDITION) ? "✓" : "✗"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            handleCommand(intent.getAction(), intent);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mHardkeyReceiver != null) {
            try { unregisterReceiver(mHardkeyReceiver); } catch (Exception ignored) {}
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void registerHardkeyReceiver() {
        mHardkeyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int keyCode   = intent.getIntExtra("keyCode",   -1);
                int keyAction = intent.getIntExtra("keyAction", -1);

                Log.d(TAG, "Hardkey: code=" + keyCode + " action=" + keyAction);

                if (keyCode == HARDKEY_FAVORITE && keyAction == KEY_DOWN) {
                    long now = System.currentTimeMillis();
                    if (now - mLastKeyDownTime < DEBOUNCE_MS) return;
                    mLastKeyDownTime = now;
                    onFavoriteKeyPressed();
                }
            }
        };

        IntentFilter filter = new IntentFilter(HARDKEY_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mHardkeyReceiver, filter, RECEIVER_EXPORTED);
        } else {
            registerReceiver(mHardkeyReceiver, filter);
        }
        Log.i(TAG, "Hardkey receiver kayıt edildi.");
    }

    private void onFavoriteKeyPressed() {
        DriveMode next = mCurrentDriveMode.next();
        Log.i(TAG, "Hardkey 66 → " + mCurrentDriveMode.label + " → " + next.label);
        if (MG4Hardware.setDriveMode(next)) {
            mCurrentDriveMode = next;
            updateNotification("Aktif", "Sürüş: " + mCurrentDriveMode.label);
        }
    }

    private void handleCommand(String action, Intent intent) {
        switch (action) {
            case "DRIVE_CYCLE":
                mCurrentDriveMode = mCurrentDriveMode.next();
                MG4Hardware.setDriveMode(mCurrentDriveMode);
                updateNotification("Aktif", "Sürüş: " + mCurrentDriveMode.label);
                break;
            case "DRIVE_SET":
                DriveMode dm = DriveMode.fromValue(intent.getIntExtra("driveValue", DriveMode.NORMAL.value));
                MG4Hardware.setDriveMode(dm);
                mCurrentDriveMode = dm;
                updateNotification("Aktif", "Sürüş: " + dm.label);
                break;
            case "REGEN_CYCLE":
                mCurrentRegen = mCurrentRegen.next();
                MG4Hardware.setRegenLevel(mCurrentRegen);
                updateNotification("Aktif", "Regen: " + mCurrentRegen.label);
                break;
            case "PEDAL_ON":  MG4Hardware.setOnePedal(true);   break;
            case "PEDAL_OFF": MG4Hardware.setOnePedal(false);  break;
            case "HEAT_ON":   MG4Hardware.setSteeringHeat(true);  break;
            case "HEAT_OFF":  MG4Hardware.setSteeringHeat(false); break;
        }
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "MG4 Kontrol", NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String title, String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("MG4 — " + title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true).setSilent(true).build();
    }

    private void updateNotification(String title, String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(title, text));
    }
}