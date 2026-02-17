package com.example.mg4_2.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.mg4_2.hardware.MG4Hardware;
import com.example.mg4_2.model.DriveMode;
import com.example.mg4_2.model.RegenLevel;

public class MG4ControlService extends Service {

    private static final String TAG = "MG4_SERVICE";
    private static final String CHANNEL_ID = "mg4_channel";
    private static final int    NOTIF_ID   = 1001;

    // Hardkey broadcast — logcat'ten doğrulandı:
    //   action: com.saic.keyevent.hardkey.report
    //   extras: "keycode" (int), "down" (boolean), "longpress" (boolean)
    //   Sol direksiyon * tuşu = keycode 17
    private static final String HARDKEY_ACTION   = "com.saic.keyevent.hardkey.report";
    private static final int    HARDKEY_STAR_KEY = 17;
    private static final long   DEBOUNCE_MS      = 500;

    private DriveMode  mCurrentDriveMode = DriveMode.NORMAL;
    private RegenLevel mCurrentRegen     = RegenLevel.MEDIUM;
    private long       mLastKeyDownTime  = 0L;
    private BroadcastReceiver mHardkeyReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Başlatılıyor..."));

        // CarPropertyManager başlat (Android 9: async, onServiceConnected'da hazır olur)
        MG4Hardware.init(this);
        Log.i(TAG, "Car.connect() çağrıldı");

        updateNotification("Bağlanıyor...");
        registerHardkeyReceiver();
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
        MG4Hardware.destroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void registerHardkeyReceiver() {
        mHardkeyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // logcat doğrulaması: extra adları "keycode" ve "down" (boolean)
                int     keyCode = intent.getIntExtra("keycode", -1);
                boolean isDown  = intent.getBooleanExtra("down", false);

                Log.d(TAG, "Hardkey: keycode=" + keyCode + " down=" + isDown);

                if (keyCode == HARDKEY_STAR_KEY && isDown) {
                    long now = System.currentTimeMillis();
                    if (now - mLastKeyDownTime < DEBOUNCE_MS) return;
                    mLastKeyDownTime = now;
                    onStarKeyPressed();
                }
            }
        };

        IntentFilter filter = new IntentFilter(HARDKEY_ACTION);
        ContextCompat.registerReceiver(this, mHardkeyReceiver, filter,
                ContextCompat.RECEIVER_EXPORTED);
        Log.i(TAG, "Hardkey receiver kayıt edildi. keycode=" + HARDKEY_STAR_KEY);
    }

    private void onStarKeyPressed() {
        DriveMode next = mCurrentDriveMode.next();
        Log.i(TAG, "★ tuşu basıldı: " + mCurrentDriveMode.label + " → " + next.label);
        if (MG4Hardware.setDriveMode(next)) {
            mCurrentDriveMode = next;
            updateNotification("Sürüş: " + mCurrentDriveMode.label
                    + " | Regen: " + mCurrentRegen.label);
        }
    }

    private void handleCommand(String action, Intent intent) {
        switch (action) {
            case "DRIVE_CYCLE":
                mCurrentDriveMode = mCurrentDriveMode.next();
                MG4Hardware.setDriveMode(mCurrentDriveMode);
                updateNotification("Sürüş: " + mCurrentDriveMode.label);
                break;
            case "DRIVE_SET":
                DriveMode dm = DriveMode.fromValue(
                        intent.getIntExtra("driveValue", DriveMode.NORMAL.value));
                MG4Hardware.setDriveMode(dm);
                mCurrentDriveMode = dm;
                updateNotification("Sürüş: " + mCurrentDriveMode.label);
                break;
            case "REGEN_CYCLE":
                mCurrentRegen = mCurrentRegen.next();
                MG4Hardware.setRegenLevel(mCurrentRegen);
                updateNotification("Regen: " + mCurrentRegen.label);
                break;
            case "PEDAL_ON":
                MG4Hardware.setOnePedal(true);
                updateNotification("OPD: Açık");
                break;
            case "PEDAL_OFF":
                MG4Hardware.setOnePedal(false);
                updateNotification("OPD: Kapalı");
                break;
        }
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "MG4 Kontrol", NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("MG4 Controller")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true).setSilent(true).build();
    }
}
