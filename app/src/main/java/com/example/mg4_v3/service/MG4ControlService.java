package com.example.mg4_v3.service;

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

import com.example.mg4_v3.hardware.MG4Hardware;
import com.example.mg4_v3.model.DriveMode;
import com.example.mg4_v3.model.RegenLevel;

import java.util.List;

public class MG4ControlService extends Service {

    private static final String TAG = "MG4_SERVICE";
    private static final String CHANNEL_ID = "mg4_channel";
    private static final int    NOTIF_ID   = 1001;

    // Hardkey broadcast — logcat'ten doğrulandı (1902260031.txt):
    //   action: com.saic.keyevent.hardkey.report
    //   extras: android.intent.extra.hardkey.keycode (int)
    //           android.intent.extra.hardkey.down (boolean)
    //           android.intent.extra.hardkey.longpress (boolean)
    //
    // ★ tuşu (keycode=17) — broadcast GELİYOR ✓ (log 1902260100)
    // Vol↑ (24) ve Vol↓ (25) — broadcast GELİYOR ✓
    //
    // Kontrol şeması:
    //   ★ tuşu (17)             → regen döngüsü (SystemUI da aynı tuşla regen yapıyor;
    //                              biz 150ms geciktirip araçtan mevcut değeri okuyup +1 yazıyoruz)
    //   Vol↑+Vol↓ combo (300ms) → müzik pause/play
    private static final String HARDKEY_ACTION      = "com.saic.keyevent.hardkey.report";
    private static final int    KEYCODE_STAR        = 17;
    private static final int    KEYCODE_VOLUME_UP   = 24;
    private static final int    KEYCODE_VOLUME_DOWN = 25;
    private static final long   DEBOUNCE_MS         = 500;

    // Vol↑+Vol↓ combo → müzik pause/play (300ms pencere)
    private static final long COMBO_WINDOW_MS = 300;
    private long mVolUpDownTime   = 0L;
    private long mVolDownDownTime = 0L;

    // ★ Tuşu uzun basış eşiği (ms) — kullanıcı ayarlayabilir
    private static final long STAR_LONG_PRESS_MS = 2000;
    private long mStarDownTime = 0L; // down=true anındaki timestamp

    // Regen döngüsü — YORUM SATIRINDA (aracın kendi özelliği kullanılıyor)
    // private static final int[] REGEN_CYCLE_VALUES = { 0, 1, 2, 3, -1 };
    // private static final String[] REGEN_CYCLE_LABELS = { "Düşük", "Orta", "Yüksek", "Adaptif", "Tek Pedal" };
    // private int mRegenStep = 2;

    private DriveMode mCurrentDriveMode = DriveMode.NORMAL;
    private long      mLastStarKeyTime  = 0L;
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
                // Gelen TÜM extra'ları logla — hangi key adının kullanıldığını bulmak için
                android.os.Bundle extras = intent.getExtras();
                if (extras != null) {
                    StringBuilder sb = new StringBuilder("HARDKEY extras: ");
                    for (String key : extras.keySet()) {
                        sb.append("[").append(key).append("=").append(extras.get(key)).append("] ");
                    }
                    Log.i(TAG, sb.toString());
                }

                // Logcat'te doğrulanan gerçek key adları (1902260031.txt):
                //   android.intent.extra.hardkey.keycode
                //   android.intent.extra.hardkey.down
                //   android.intent.extra.hardkey.longpress
                int keyCode = intent.getIntExtra("android.intent.extra.hardkey.keycode", -1);
                if (keyCode == -1) keyCode = intent.getIntExtra("keycode", -1);
                if (keyCode == -1) keyCode = intent.getIntExtra("keyCode", -1);

                boolean isDown = intent.getBooleanExtra("android.intent.extra.hardkey.down", false);
                if (!isDown) isDown = intent.getBooleanExtra("down", false);

                boolean isLong = intent.getBooleanExtra("android.intent.extra.hardkey.longpress", false);
                if (!isLong) isLong = intent.getBooleanExtra("longpress", false);

                Log.i(TAG, "HARDKEY >>> keycode=" + keyCode
                        + " down=" + isDown
                        + " longpress=" + isLong
                        + " label=" + keycodeLabel(keyCode));

                if (keyCode == KEYCODE_STAR) {
                    // ★ tuşu: hem down hem up olayını işle (basış süresi ölçümü için)
                    onStarKey(isDown);
                } else {
                    if (!isDown) return; // Diğer tuşlar için sadece down olayına bak
                    if (keyCode == KEYCODE_VOLUME_UP) {
                        onVolumeUp();
                    } else if (keyCode == KEYCODE_VOLUME_DOWN) {
                        onVolumeDown();
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter(HARDKEY_ACTION);
        ContextCompat.registerReceiver(this, mHardkeyReceiver, filter,
                ContextCompat.RECEIVER_EXPORTED);
        Log.i(TAG, "Hardkey receiver kayıt edildi — action=" + HARDKEY_ACTION);
        Log.i(TAG, "  ★ kısa (keycode=17)      → Tek Pedal açıksa KAPAT");
        Log.i(TAG, "  ★ uzun ≥2sn (keycode=17) → Tek Pedal AÇ");
        Log.i(TAG, "  Vol↑+Vol↓ combo (300ms)  → müzik pause/play");
    }

    // -------------------------------------------------------------------------
    // ★ Tuşu — basış süresi ile kısa/uzun ayırt et
    //   Uzun basış (≥2sn) → Tek Pedal AÇ
    //   Kısa basış         → Tek Pedal açıksa KAPAT, kapalıysa hiçbir şey yapma
    //                        (regen döngüsü aracın kendi özelliğine bırakıldı)
    // -------------------------------------------------------------------------

    private void onStarKey(boolean isDown) {
        long now = System.currentTimeMillis();

        if (isDown) {
            // Tuşa basıldı — zamanı kaydet, debounce uygula
            if (now - mLastStarKeyTime < DEBOUNCE_MS) {
                Log.d(TAG, "  ★ DOWN debounce atlandı");
                mStarDownTime = 0L; // debounce'da down'ı geçersiz say
                return;
            }
            mStarDownTime = now;
            Log.i(TAG, "★ DOWN — süre ölçümü başladı");
        } else {
            // Tuş bırakıldı — süreyi hesapla
            if (mStarDownTime == 0L) {
                Log.d(TAG, "  ★ UP — geçersiz down (debounce atlandı), yoksayılıyor");
                return;
            }
            long pressDuration = now - mStarDownTime;
            mStarDownTime = 0L;
            mLastStarKeyTime = now;
            Log.i(TAG, "★ UP — basış süresi=" + pressDuration + "ms");

            if (pressDuration >= STAR_LONG_PRESS_MS) {
                // Uzun basış → Tek Pedal AÇ
                Log.i(TAG, "  → UZUN basış → Tek Pedal AÇIK");
                MG4Hardware.setOnePedal(true);
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    Log.i(TAG, "  → Tek Pedal AÇIK (takviye, 250ms)");
                    MG4Hardware.setOnePedal(true);
                }, 250);
                updateNotification("Tek Pedal: Açık");
            } else {
                // Kısa basış → Tek Pedal açıksa kapat
                Log.i(TAG, "  → KISA basış → onePedal kontrol ediliyor");
                int onePedal = MG4Hardware.getOnePedal();
                Log.i(TAG, "  → onePedal=" + onePedal);
                if (onePedal == 1) {
                    Log.i(TAG, "  → Tek Pedal KAPATILIYOR");
                    MG4Hardware.setOnePedal(false);
                    updateNotification("Tek Pedal: Kapalı");
                } else {
                    // Tek pedal zaten kapalı — regen döngüsü aracın kendisinde
                    Log.i(TAG, "  → Tek Pedal zaten kapalı, regen araçta");
                }
            }
        }
    }

    // findNextStep ve cycleRegenInternal — regen döngüsü aracın kendi özelliğine bırakıldı
    // Gerekirse yorum kaldırılabilir
    //
    // private int findNextStep(int currentValue) { ... }
    // private void cycleRegenInternal() { ... }

    // -------------------------------------------------------------------------
    // Volume tuşları
    // -------------------------------------------------------------------------

    private void onVolumeUp() {
        mVolUpDownTime = System.currentTimeMillis();
        Log.d(TAG, "Vol↑ down");
        checkCombo();
    }

    private void onVolumeDown() {
        mVolDownDownTime = System.currentTimeMillis();
        Log.d(TAG, "Vol↓ down");
        checkCombo();
    }

    private void checkCombo() {
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
            // case "REGEN_CYCLE": // devre dışı — regen döngüsü aracın kendi özelliğinde
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

    // cycleRegenInternal — devre dışı (regen döngüsü aracın kendi özelliğine bırakıldı)

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
