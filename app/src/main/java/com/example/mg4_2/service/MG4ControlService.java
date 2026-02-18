package com.example.mg4_2.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.mg4_2.hardware.MG4Hardware;
import com.example.mg4_2.model.DriveMode;
import com.example.mg4_2.model.RegenLevel;

import java.util.List;

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

    // Volume tuş kodları (doğrulanacak — araçta farklı olabilir)
    private static final int KEYCODE_VOLUME_UP   = 24;
    private static final int KEYCODE_VOLUME_DOWN = 25;

    // Volume combo: 300ms içinde ikisi de basılırsa müzik pause/play
    private static final long COMBO_WINDOW_MS = 300;
    private long mVolUpDownTime   = 0L;
    private long mVolDownDownTime = 0L;

    // Regen döngüsü adımları (sırayla): Düşük → Orta → Yüksek → Adaptif → Tek Pedal → Düşük
    // Tek Pedal adımında: regen KAPALI + onePedal AÇIK
    // Diğer adımlarda: onePedal KAPALI + ilgili regen seviyesi
    private static final int[] REGEN_CYCLE_VALUES = { 1, 2, 3, 4, -1 }; // -1 = Tek Pedal
    private static final String[] REGEN_CYCLE_LABELS = { "Düşük", "Orta", "Yüksek", "Adaptif", "Tek Pedal" };
    private int mRegenStep = 2; // Başlangıç: Yüksek (index 2)

    private DriveMode mCurrentDriveMode = DriveMode.NORMAL;
    private long      mLastKeyDownTime  = 0L;
    private BroadcastReceiver mHardkeyReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "=== onCreate başladı ===");
        Log.i(TAG, "  Android SDK: " + android.os.Build.VERSION.SDK_INT
                + " (" + android.os.Build.VERSION.RELEASE + ")");
        Log.i(TAG, "  Cihaz: " + android.os.Build.MODEL + " / " + android.os.Build.DEVICE);

        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Başlatılıyor..."));

        MG4Hardware.init(this);
        Log.i(TAG, "MG4Hardware.init() çağrıldı");

        updateNotification("Bağlanıyor...");
        registerHardkeyReceiver();
        Log.i(TAG, "=== onCreate tamamlandı ===");
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

    // -------------------------------------------------------------------------
    // Hardkey receiver
    // -------------------------------------------------------------------------

    private void registerHardkeyReceiver() {
        mHardkeyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int     keyCode  = intent.getIntExtra("keycode", -1);
                boolean isDown   = intent.getBooleanExtra("down", false);
                boolean isLong   = intent.getBooleanExtra("longpress", false);

                // Tüm tuşları logla — tuş kodlarını keşfetmek için
                Log.i(TAG, "HARDKEY >>> keycode=" + keyCode
                        + " down=" + isDown
                        + " longpress=" + isLong
                        + " label=" + keycodeLabel(keyCode));

                if (!isDown) return; // Sadece key-down olaylarını işle

                if (keyCode == HARDKEY_STAR_KEY) {
                    onStarKeyPressed();
                } else if (keyCode == KEYCODE_VOLUME_UP) {
                    onVolumeUpDown(true);
                } else if (keyCode == KEYCODE_VOLUME_DOWN) {
                    onVolumeUpDown(false);
                }
            }
        };

        IntentFilter filter = new IntentFilter(HARDKEY_ACTION);
        ContextCompat.registerReceiver(this, mHardkeyReceiver, filter,
                ContextCompat.RECEIVER_EXPORTED);
        Log.i(TAG, "Hardkey receiver kayıt edildi — action=" + HARDKEY_ACTION);
        Log.i(TAG, "  ★ tuşu (keycode=" + HARDKEY_STAR_KEY + ") → sürüş modu");
        Log.i(TAG, "  Vol↑+" + KEYCODE_VOLUME_UP + " + Vol↓+" + KEYCODE_VOLUME_DOWN
                + " combo → müzik pause/play");
    }

    // -------------------------------------------------------------------------
    // ★ Tuşu — sürüş modu değiştir
    // -------------------------------------------------------------------------

    private void onStarKeyPressed() {
        long now     = System.currentTimeMillis();
        long elapsed = now - mLastKeyDownTime;
        if (elapsed < DEBOUNCE_MS) {
            Log.d(TAG, "  ★ debounce: " + elapsed + "ms < " + DEBOUNCE_MS + "ms, atlanıyor");
            return;
        }
        mLastKeyDownTime = now;

        DriveMode next = mCurrentDriveMode.next();
        Log.i(TAG, "★ tuşu: " + mCurrentDriveMode.label + " → " + next.label
                + " | HW hazır=" + MG4Hardware.isReady());
        boolean ok = MG4Hardware.setDriveMode(next);
        if (ok) {
            mCurrentDriveMode = next;
            updateNotification("Sürüş: " + mCurrentDriveMode.label
                    + " | Regen: " + REGEN_CYCLE_LABELS[mRegenStep]);
        }
    }

    // -------------------------------------------------------------------------
    // Volume combo — pause/play, uzun basış → önceki/sonraki şarkı
    // -------------------------------------------------------------------------

    private void onVolumeUpDown(boolean isUp) {
        long now = System.currentTimeMillis();

        if (isUp) {
            mVolUpDownTime = now;
            Log.d(TAG, "Vol↑ down — combo penceresi başladı");
        } else {
            mVolDownDownTime = now;
            Log.d(TAG, "Vol↓ down — combo penceresi başladı");
        }

        // Combo kontrolü: ikisi de basılı mı?
        long gap = Math.abs(mVolUpDownTime - mVolDownDownTime);
        if (mVolUpDownTime > 0 && mVolDownDownTime > 0 && gap <= COMBO_WINDOW_MS) {
            Log.i(TAG, "Vol↑+Vol↓ COMBO tetiklendi (gap=" + gap + "ms) → toggleMusicPlayback");
            mVolUpDownTime   = 0;
            mVolDownDownTime = 0;
            toggleMusicPlayback();
        }
    }

    // -------------------------------------------------------------------------
    // Medya kontrolü — MediaSessionManager
    // -------------------------------------------------------------------------

    private MediaController getActiveMediaController() {
        try {
            MediaSessionManager msm =
                    (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) {
                Log.w(TAG, "MediaSessionManager null");
                return null;
            }
            List<MediaController> controllers =
                    msm.getActiveSessions(null);
            if (controllers == null || controllers.isEmpty()) {
                Log.w(TAG, "Aktif medya oturumu yok");
                return null;
            }
            Log.d(TAG, "Aktif medya oturumu sayısı: " + controllers.size()
                    + " — ilk=" + controllers.get(0).getPackageName());
            return controllers.get(0);
        } catch (SecurityException e) {
            Log.w(TAG, "getActiveSessions izin yok (MEDIA_CONTENT_CONTROL gerekebilir): "
                    + e.getMessage());
            return null;
        } catch (Exception e) {
            Log.e(TAG, "getActiveMediaController hata: " + e.getMessage());
            return null;
        }
    }

    private void toggleMusicPlayback() {
        MediaController mc = getActiveMediaController();
        if (mc == null) {
            Log.w(TAG, "toggleMusicPlayback: medya kontrolcüsü yok");
            updateNotification("Müzik: aktif oturum bulunamadı");
            return;
        }
        PlaybackState state = mc.getPlaybackState();
        if (state == null) {
            Log.w(TAG, "toggleMusicPlayback: PlaybackState null");
            return;
        }
        int ps = state.getState();
        Log.i(TAG, "toggleMusicPlayback: playbackState=" + ps
                + " package=" + mc.getPackageName());
        if (ps == PlaybackState.STATE_PLAYING) {
            mc.getTransportControls().pause();
            Log.i(TAG, "  → pause gönderildi");
            updateNotification("Müzik: ⏸ Durduruldu");
        } else {
            mc.getTransportControls().play();
            Log.i(TAG, "  → play gönderildi");
            updateNotification("Müzik: ▶ Oynatılıyor");
        }
    }

    // -------------------------------------------------------------------------
    // Komut yönetimi (MainActivity'den intent ile)
    // -------------------------------------------------------------------------

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
                cycleRegen();
                break;
            case "PEDAL_ON":
                MG4Hardware.setOnePedal(true);
                updateNotification("OPD: Açık");
                break;
            case "PEDAL_OFF":
                MG4Hardware.setOnePedal(false);
                updateNotification("OPD: Kapalı");
                break;
            case "HEAT_ON":
                MG4Hardware.setSteeringHeat(true);
                updateNotification("Direksiyon: Isıtma Açık");
                break;
            case "HEAT_OFF":
                MG4Hardware.setSteeringHeat(false);
                updateNotification("Direksiyon: Isıtma Kapalı");
                break;
        }
    }

    // -------------------------------------------------------------------------
    // Regen döngüsü
    // -------------------------------------------------------------------------

    private void cycleRegen() {
        mRegenStep = (mRegenStep + 1) % REGEN_CYCLE_VALUES.length;
        int value  = REGEN_CYCLE_VALUES[mRegenStep];
        String label = REGEN_CYCLE_LABELS[mRegenStep];
        Log.i(TAG, "cycleRegen → step=" + mRegenStep + " label=" + label + " value=" + value);

        if (value == -1) {
            // Tek Pedal adımı: regen kapat, tek pedal aç
            MG4Hardware.setOnePedal(true);
            Log.i(TAG, "  → Tek Pedal AÇIK, regen kapalı");
        } else {
            // Normal regen adımı: önce tek pedali kapat, sonra regen seviyesini ayarla
            MG4Hardware.setOnePedal(false);
            MG4Hardware.setRegenLevel(RegenLevel.fromValue(value));
            Log.i(TAG, "  → onePedal KAPALI, regen=" + label);
        }
        updateNotification("Regen: " + label);
    }

    // -------------------------------------------------------------------------
    // Yardımcılar
    // -------------------------------------------------------------------------

    /**
     * Bilinen keycode'lar için okunabilir isim döner.
     * Araçtaki gerçek tuş kodlarını keşfetmek için logcat'te gözlemle.
     */
    private static String keycodeLabel(int keycode) {
        switch (keycode) {
            case 17:  return "STAR/FAV";
            case 24:  return "VOLUME_UP";
            case 25:  return "VOLUME_DOWN";
            case 66:  return "ENTER";
            case 4:   return "BACK";
            case 3:   return "HOME";
            case 164: return "MUTE";
            case 85:  return "MEDIA_PLAY_PAUSE";
            case 87:  return "MEDIA_NEXT";
            case 88:  return "MEDIA_PREV";
            case 126: return "MEDIA_PLAY";
            case 127: return "MEDIA_PAUSE";
            default:  return "UNKNOWN(" + keycode + ")";
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
