package com.drivehub.dort.service;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.UiModeManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.drivehub.dort.R;
import com.drivehub.dort.hardware.MG4Hardware;
import com.drivehub.dort.audio.EngineSoundManager;
import com.drivehub.dort.model.DriveMode;
import com.drivehub.dort.model.RegenLevel;
import com.drivehub.dort.util.ChargingHistory;

import java.util.List;
import java.lang.reflect.Method;

public class MG4ControlService extends Service {

    private static final String TAG = "MG4_SERVICE";
    private static final String CHANNEL_ID = "drivehub_dort_channel";
    private static final int    NOTIF_ID   = 1001;
    /** Ekrandan müzik duraklat/devam için Activity'nin servise gönderdiği action */
    public static final String ACTION_TOGGLE_MUSIC = "com.drivehub.dort.TOGGLE_MUSIC";
    /** Ayarlarda switch açıldığında veya ekran uyandığında ADB'yi tekrar aktifleştirme isteği. */
    public static final String ACTION_ENSURE_USB_DEBUG = "com.drivehub.dort.ENSURE_USB_DEBUG";
    private static final String PREF_ALWAYS_USB_DEBUG = "always_usb_debug";

    // Hardkey broadcast — logcat'ten doğrulandı (1902260031.txt):
    //   action: com.saic.keyevent.hardkey.report
    //   extras: android.intent.extra.hardkey.keycode (int)
    //           android.intent.extra.hardkey.down (boolean)
    //           android.intent.extra.hardkey.longpress (boolean)
    //
    // InputReader (tag:InputReader, system_server) — D-Bus com.yfve.ivi.hmi.respond, processKey (doğrulandı 2026-03-01):
    //   keyCode=17  scanCode=89  dbus 0x96 → Sol yıldız
    //   keyCode=286 scanCode=86  dbus 0x98 → Sağ yıldız
    //   keyCode=291 scanCode=149 dbus 0x95 → Telefon
    //   keyCode=287 scanCode=87  dbus 0x97 → Sesli asistan
    //   keyCode=301 scanCode=68  dbus 0x44 → Duraklat / Devam ettir (müzik)
    // ★ tuşu (keycode=17) — broadcast GELİYOR ✓ (log 1902260100)
    // Vol↑ (24) ve Vol↓ (25) — broadcast GELİYOR ✓
    //
    // Kontrol şeması:
    //   ★ tuşu (17)             → regen döngüsü (SystemUI da aynı tuşla regen yapıyor;
    //                              biz 150ms geciktirip araçtan mevcut değeri okuyup +1 yazıyoruz)
    //   Vol↑+Vol↓ combo (600ms pencere — uzun basınca da tutsun)
    //   Direksiyon müzik tuşu (log’da keycode=301) → müzik pause/play
    private static final String HARDKEY_ACTION      = "com.saic.keyevent.hardkey.report";
    private static final int    KEYCODE_VOLUME_UP   = 24;
    private static final int    KEYCODE_VOLUME_DOWN = 25;
    /** Direksiyondaki duraklat/devam tuşu — SAIC hardkey keyCode (log’da 301). */
    private static final int    KEYCODE_SAIC_MEDIA_PLAY_PAUSE = 301;
    /** Ana ekran tuşu (KeyEvent.KEYCODE_HOME). Vol− ile birlikte basılınca motor sesi toggle. */
    private static final int    KEYCODE_ANA_EKRAN = 3;
    // Vol↑+Vol↓ combo: iki tuşa da dokunuldu → "toggle bekliyor"; ikisi de bırakılınca komut gönder
    private boolean mVolUpPressed   = false;
    private boolean mVolDownPressed = false;
    private boolean mVolComboPending = false;
    // Vol↓+Ana ekran combo: ikisi bırakılınca motor sesi aç/kapa (müzik toggle gibi)
    private boolean mAnaEkranPressed = false;
    private boolean mVolDownAnaEkranComboPending = false;
    // Yeni sabit:
    private static final long SHORTCUT_COMBO_TIMEOUT_MS = 1500;
    private long mVolDownAnaEkranComboStartMs = 0L;
    private long mVolComboStartMs = 0L;
    // Tek pedal atama (Regen panelinden ayarlanır)
    private static final String PREF_ONE_PEDAL_KEY = "one_pedal_key";
    private static final String PREF_ONE_PEDAL_PRESS_TYPE = "one_pedal_press_type";
    private static final String PREF_ONE_PEDAL_PRESS_TYPE_OFF = "one_pedal_press_type_off";
    /** -1 = kapalı (varsayılan); hiçbir tuş tek pedal tetiklemez */
    private static final int DEFAULT_ONE_PEDAL_KEY = -1;
    private static final String DEFAULT_ONE_PEDAL_PRESS_TYPE = "long";
    private static final String DEFAULT_ONE_PEDAL_PRESS_TYPE_OFF = "single";
    private static final long ONE_PEDAL_LONG_PRESS_MS = 1200;
    /** Herhangi bir atanmış tuş için çift basma penceresi (ms). */
    private static final long SHORTCUT_DOUBLE_PRESS_MS = 700;

    /** 360 kamera tuş atama (Düğme kısayolları panelinden) */
    private static final String PREF_CAMERA_360_KEY = "camera_360_key";
    private static final String PREF_CAMERA_360_PRESS_ON  = "camera_360_press_on";
    private static final String PREF_CAMERA_360_PRESS_OFF = "camera_360_press_off";
    private static final int    DEFAULT_CAMERA_360_KEY = -1;

    /** Sürüş modunu hatırla (Düğme kısayolları panelindeki switch) */
    public static final String PREF_REMEMBER_DRIVE_MODE = "remember_drive_mode";
    /** Son seçilen sürüş modu (DriveMode.value) */
    public static final String PREF_LAST_DRIVE_MODE     = "last_drive_mode";
    /** Regen seviyesini hatırla (Düğme kısayolları panelindeki switch) */
    public static final String PREF_REMEMBER_REGEN      = "remember_regen_level";
    /** Son seçilen regen seviyesi (RegenLevel.value) */
    public static final String PREF_LAST_REGEN_LEVEL    = "last_regen_level";
    /** Vol↑+Vol↓ müzik duraklat/devam tuş kombinasyonu aktif mi (kısayollar panelindeki switch) */
    public static final String PREF_SHORTCUT_MEDIA_COMBO_ENABLED  = "shortcut_media_combo_enabled";
    /** Vol↓+Ana ekran motor sesi aç/kapa tuş kombinasyonu aktif mi (kısayollar panelindeki switch) */
    public static final String PREF_SHORTCUT_ENGINE_COMBO_ENABLED = "shortcut_engine_combo_enabled";

    private volatile boolean mOnePedalKeyPressed = false;
    private volatile boolean mOnePedalLongTriggered = false;
    private long mOnePedalLastTapTime = 0L;
    private int mOnePedalLastTapKeyCode = -1;

    private volatile boolean mCamera360KeyPressed = false;
    private volatile boolean mCamera360LongTriggered = false;
    private long mCamera360LastTapTime = 0L;
    private int  mCamera360LastTapKeyCode = -1;

    private DriveMode mCurrentDriveMode = DriveMode.NORMAL;
    private BroadcastReceiver mHardkeyReceiver;
    private BroadcastReceiver mScreenWakeReceiver;

    // Overlay
    private static final int COLOR_HEAT_ON  = 0xFF9E3333;
    private static final int COLOR_INACTIVE = 0xFF21262D;
    // Merkez offseti (px) — araca yükleyince deneme/yanılma ile ayarla
    private static final int OVERLAY_OFFSET_PX = 200;

    private WindowManager mWindowManager;
    private View           mOverlayLeft;   // DIR + SOL koltuk
    private View           mOverlayRight;  // SAĞ koltuk
    private Button         mBtnOvSteer;
    private View           mBtnOvSeatL;
    private View           mBtnOvSeatR;
    private View           mOvSeatLBar1, mOvSeatLBar2, mOvSeatLBar3;
    private View           mOvSeatRBar1, mOvSeatRBar2, mOvSeatRBar3;
    private int            mSteerLevel = 0;
    private int            mSeatLLevel = 0;
    private int            mSeatRLevel = 0;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    // Kontak (ignition) düşüp tekrar RUN olursa profilleri yeniden uygula
    private int mLastIgnitionStateForRemember = -1;
    private boolean mDriveRegenRememberInitialized = false;
    /** Boot sonrası sürüş modu / regen uygulaması için gecikme (ms). Debug için biraz kısaltıldı. */
    private static final long REMEMBER_APPLY_START_UP_DELAY_MS = 5_000L;
    private static final long REMEMBER_APPLY_LOOP_DELAY_MS = 1_000L;
    /** Ekran uyandıktan sonra profil denemesinden önce bekle (HAL/property otursun). */
    private static final long REMEMBER_APPLY_AFTER_SCREEN_ON_SETTLE_MS = 2_000L;
    private static final long REMEMBER_APPLY_SETTLE_POLL_MS = 200L;
    /** Son döngüde ekran interactive miydi (uyanınca 2 sn settle için). */
    private boolean mRememberLastScreenInteractive = true;
    /** Bu süre dolmadan sync/apply yapma (elapsedRealtime). */
    private long mRememberSettleUntilElapsedMs = 0L;
    /** One-pedal geri yükleme için ek deneme ayarları (uzaktan uyandırma senaryosunda geç hazır olabiliyor). */
    private static final int ONE_PEDAL_RESTORE_MAX_RETRIES = 8;
    private static final long ONE_PEDAL_RESTORE_RETRY_DELAY_MS = 1_200L;
    private int mOnePedalRestoreRetryCount = 0;

    // Sistem medya sesini kontrol etmek için
    private AudioManager mAudioManager;

    // Yapay motor sesi (sanal ses) – servis tarafında da yönet
    private EngineSoundManager mEngineSound;

    /** Şarj bittiğinde oturumu hafızaya kaydet; uygulama kapalı veya başka ekrandayken de çalışır. */
    private static final long CHARGING_CHECK_INTERVAL_MS = 10_000L;
    private boolean mAUTO_NIGHT_MODE_ENABLED = true;
    private boolean mSystemWasAuto = false;
    private final Handler mChargingCheckHandler = new Handler(Looper.getMainLooper());
    private final Runnable mChargingCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (ChargingHistory.checkAndSaveSessionIfEnded(MG4ControlService.this)) {
                if (MG4Hardware.isLogEnabled()) {
                    Log.i(TAG, "Şarj oturumu arka planda kaydedildi.");
                }
            }
            mChargingCheckHandler.postDelayed(this, CHARGING_CHECK_INTERVAL_MS);
        }
    };

    /** 100 ms'lik ana görev periyodu (enerji, mesafe, hazır/şarj durumu vb.). */
    private static final int MAIN_TASK_INTERVAL_MS = 30;
    private static final int SOUND_TASK_MS = 30;
    private static final long TELEMETRY_INTERVAL_MS = 30L;
    /** Hayat boyu km/kWh'ı bu kadar integrasyon sonrası bir kez hafızaya yaz (≈30 sn). */
    private static final int LIFETIME_PERSIST_MS = 30000;
    private final Handler mConsumptionHandler = new Handler(Looper.getMainLooper());
    private static long mDriveStartWallMs = 0L;
    /** Motor çalışır (READY) oturumu başlarken SOC; kayıt için. */
    private static float mDriveSessionStartSoc = Float.NaN;
    private static volatile boolean sDriveSessionActive = false;
    private static volatile long    sDriveSessionEndWallMs = 0L;
    private long mLastConsumptionLoop = 0;
    private long mLastTelemetryBroadcastTime = 0L;

    private final Runnable mConsumptionIntegrationRunnable = new Runnable() {
        @Override
        public void run() {
            MG4Hardware.runMainTask();

            long now = System.currentTimeMillis();
            if (now - mLastTelemetryBroadcastTime >= TELEMETRY_INTERVAL_MS) {
                if (MG4Hardware.isSoundEnabled() && MG4Hardware.isVehicleReady()) {
                    EngineSoundManager.broadcastTelemetryIfNeeded(MG4ControlService.this);
                } else {
                    float speed = MG4Hardware.getSpeedForEngine();
                    float dcKw = MG4Hardware.getDcKwGlobal();
                    float dcKw_kadran = Float.isNaN(dcKw) ? 0f : (speed == 0 && dcKw < 0f ? 0f : dcKw);

                    EngineSoundManager.broadcastTelemetryFromRaw(MG4ControlService.this, Float.isNaN(speed) ? 0f : speed, dcKw_kadran);
                }
                mLastTelemetryBroadcastTime = now;
            }

            if (now - mLastConsumptionLoop >= LIFETIME_PERSIST_MS) {
                mLastConsumptionLoop = now;
                MG4Hardware.persistLifetimeToPrefs(MG4ControlService.this);
            }
            updateDriveSessionFromReady();
            mConsumptionHandler.postDelayed(this, MAIN_TASK_INTERVAL_MS);
        }
    };
    private final Handler mSoundHandler = new Handler(Looper.getMainLooper());
    private final Runnable mOnePedalRestoreRetryRunnable = new Runnable() {
        @Override
        public void run() {
            attemptRememberedOnePedalRestore("retry");
        }
    };

    private final Runnable mSoundRunnable = new Runnable() {
        @Override
        public void run() {
            if (mEngineSound == null) {
                mSoundHandler.postDelayed(this, 1000);
                return;
            }

            // Hız tek kaynak: sim açıksa sim, değilse hattan tek okuma (getSpeedForEngine)
            float speed = MG4Hardware.getSpeedForEngine();
            boolean ready = MG4Hardware.isVehicleReady() || MG4Hardware.isSimSpeedActive();

            boolean soundEnabled = MG4Hardware.isSoundEnabled();

            if (soundEnabled && ready) {
                if (!mEngineSound.isPlaying()) {
                    mEngineSound.start();
                }
                mEngineSound.onSpeedChanged(speed);
            } else {
                if (mEngineSound.isPlaying()) {
                    mEngineSound.stop();
                }
            }
            mSoundHandler.postDelayed(this, SOUND_TASK_MS);
        }
    };

    private void updateDriveSessionFromReady() {
        boolean ready = MG4Hardware.isVehicleReady();
        long nowWall = System.currentTimeMillis();

        if (!sDriveSessionActive && ready) {
            sDriveSessionActive = true;
            mDriveStartWallMs = nowWall;
            sDriveSessionEndWallMs = 0L;
            mDriveSessionStartSoc = MG4Hardware.getSoc();
            MG4Hardware.resetDriveGraphCounters();
            return;
        }

        if (sDriveSessionActive && !ready) {
            sDriveSessionActive = false;
            sDriveSessionEndWallMs = nowWall;

            double distKm = MG4Hardware.getDriveGraphDistanceKm();
            double energyKwh = MG4Hardware.getDriveGraphEnergyKwh();
            double durationMinutes = (nowWall - mDriveStartWallMs) / 60000.0;

            if ((distKm > 0.01) && durationMinutes >= 5.0) {
                float endSoc = MG4Hardware.getSoc();
                com.drivehub.dort.util.DrivingHistory.addSession(
                        getApplicationContext(),
                        mDriveStartWallMs,
                        nowWall,
                        (float) distKm,
                        (float) energyKwh,
                        mDriveSessionStartSoc,
                        endSoc
                );
            }
            mDriveSessionStartSoc = Float.NaN;
        }
    }

    public static long getDriveSessionDurationMs() {
        long start = mDriveStartWallMs;
        if (start == 0L) {
            return 0L;
        }
        long end;
        if (sDriveSessionActive) {
            end = System.currentTimeMillis();
        } else {
            end = sDriveSessionEndWallMs;
            if (end <= 0L) {
                return 0L;
            }
        }
        long dur = end - start;
        return Math.max(dur, 0L);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (MG4Hardware.isLogEnabled()) {
            Log.i(TAG, "=== onCreate başladı ===");
            Log.i(TAG, "  Android SDK: " + android.os.Build.VERSION.SDK_INT
                    + " (" + android.os.Build.VERSION.RELEASE + ")");
            Log.i(TAG, "  Cihaz: " + android.os.Build.MODEL + " / " + android.os.Build.DEVICE);
        }

        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Başlatılıyor..."));

        MG4Hardware.init(this);
        if (MG4Hardware.isLogEnabled()) {
            Log.i(TAG, "MG4Hardware.init() çağrıldı");
        }

        updateNotification("Bağlanıyor...");
        registerHardkeyReceiver();
        registerScreenWakeReceiver();
        mMainHandler.postDelayed(() -> ensureUsbDebugEnabled("service_start"), 1500);

        SharedPreferences prefs = getSharedPreferences("drivehub_dort", MODE_PRIVATE);
        MG4Hardware.setSoundEnabled(prefs.getBoolean("sound_enabled", false));
        boolean overlayEnabled = prefs.getBoolean("overlay_enabled", false);
        if (overlayEnabled) {
            showOverlay();
        } else {
            if (MG4Hardware.isLogEnabled()) {
                Log.i(TAG, "Overlay kullanıcı ayarı nedeniyle kapalı (overlay_enabled=false)");
            }
        }

        // Araçtaki gerçek sürüş modu değişince haberdar ol (Eco/Normal/Sport düğmesi dışından da)
        MG4Hardware.setDriveModeListener(modeValue -> {
            mCurrentDriveMode = DriveMode.fromValue(modeValue);
            updateNotification("Sürüş: " + mCurrentDriveMode.label);

            // Ses "Araç(AUTO)" modundayken, sürüş modu Eco ise Eco, değilse sürekli Sport'e kilitle.
            SharedPreferences sp = getSharedPreferences("drivehub_dort", MODE_PRIVATE);
            String soundChar = sp.getString("sound_character", "ECO");
            if ("AUTO".equals(soundChar) && mEngineSound != null) {
                mEngineSound.applySoundCharacterFromString(
                        (mCurrentDriveMode == DriveMode.ECO) ? "ECO" : "SPORT"
                );
            }

            // Son modu sadece araç READY iken hafızaya yaz. Araç kapanırken son anda Normal/regen 3'e
            // çektiği değerleri yazmayalım (hatırlama yanlış değerle üzerine yazılmasın).
            if (mDriveRegenRememberInitialized && (MG4Hardware.getVehicleIgnition() >= 2)) {
                sp.edit().putInt(PREF_LAST_DRIVE_MODE, modeValue).apply();

                int regenVal = MG4Hardware.getRegenLevel();
                if (regenVal >= 0) {
                    sp.edit().putInt(PREF_LAST_REGEN_LEVEL, regenVal).apply();
                }
            }
        });

        // EngineSoundManager'ı her durumda servis tarafında başlat.
        // Sesin açık/kapalı olması her 100 ms'de PREF_SOUND_ENABLED'den okunuyor.
        if (MG4Hardware.isLogEnabled()) {
            Log.i(TAG, "EngineSound: service içinde init ediliyor");
        }
        mEngineSound = EngineSoundManager.getInstance(this);
        // Tüm ses ayarlarını (profil, karakter, master) EngineSoundManager yönetsin
        mEngineSound.initFromPreferences(this);

        // Hız / READY durumuna göre sesi arka planda yönet.
        // Gerçek araçta servis döngüsü aktif olsun; emülatörde (sdk_gphone / emu64xa) sadece Activity simülasyonu kullansın.
        //String device = android.os.Build.DEVICE != null ? android.os.Build.DEVICE : "";merthan
        //boolean isEmulator = device.contains("emu") || device.contains("sdk_gphone");
        //if (!isEmulator) {
            mSoundHandler.post(mSoundRunnable);
        /*} else {
            if (MG4Hardware.isLogEnabled()) {
                Log.i(TAG, "EngineSound: emulator tespit edildi (" + device + "), servis ses döngüsü devre dışı. Simülasyon sadece Activity tarafında çalışacak.");
            }
        }*/

        // Şarj bittiğinde oturumu hafızaya kaydet (uygulama kapalı veya başka ekrandayken de)
        mChargingCheckHandler.post(mChargingCheckRunnable);

        // Tüketim: boot'tan itibaren 100ms'de bir oku + enerji integre et (uygulama açılmasa da)
        MG4Hardware.ensureConsumptionTripStarted();
        MG4Hardware.loadLifetimeFromPrefs(this);
        mConsumptionHandler.post(mConsumptionIntegrationRunnable);

        // Profil remember:
        //  - İlk deneme: servis başladıktan 5 sn sonra
        //  - Kontak KAPALIYSA: her 1 sn'de bir tekrar dene
        //  - Kontak AÇIKKEN (ignition >= 2) VE ekran AÇIKKEN: sürüş/regen profillerini uygula
        //  - Ignition düşüp tekrar RUN olursa yeniden uygula (sadece ekran açıksa)
        //  - Ekran yeni uyandıysa: 2 sn bekle (kendine gelsin), sonra sync/apply
        mMainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                boolean interactive = isScreenOnForProfileRemember();
                if (!interactive) {
                    mRememberLastScreenInteractive = false;
                    mMainHandler.postDelayed(this, REMEMBER_APPLY_LOOP_DELAY_MS);
                    return;
                }
                // Kapalı → açık: kısa settle penceresi başlat
                if (!mRememberLastScreenInteractive) {
                    mRememberLastScreenInteractive = true;
                    mRememberSettleUntilElapsedMs =
                            SystemClock.elapsedRealtime() + REMEMBER_APPLY_AFTER_SCREEN_ON_SETTLE_MS;
                }
                if (SystemClock.elapsedRealtime() < mRememberSettleUntilElapsedMs) {
                    if(isSystemNightModeAuto()) {
                        mSystemWasAuto = true;
                        applySystemNightMode(UiModeManager.MODE_NIGHT_YES);
                    }
                    mMainHandler.postDelayed(this, REMEMBER_APPLY_SETTLE_POLL_MS);
                    return;
                }

                int ign = MG4Hardware.getVehicleIgnition();
                boolean isRun = ign >= 2;
                boolean wasRun = mLastIgnitionStateForRemember >= 2;

                // İlk durum veya ignition değişimi için state'i güncelle
                mLastIgnitionStateForRemember = ign;

                // RUN'a yeni geçişte (OFF/ACC -> RUN) yeniden uygula
                if (isRun && !wasRun) {
                    if(mSystemWasAuto){
                        applySystemNightMode(UiModeManager.MODE_NIGHT_AUTO);
                    }
                    applyRememberedDriveModeIfNeeded();
                    applyRememberedRegenIfNeeded();

                    // Profil uygulandıktan kısa süre sonra kaydetme flag'ini aç
                    mMainHandler.postDelayed(
                            () -> mDriveRegenRememberInitialized = true,
                            REMEMBER_APPLY_START_UP_DELAY_MS
                    );
                }

                // Sürekli izlemeye devam et (ignition düşüp tekrar RUN olursa tekrar uygulasın)
                mMainHandler.postDelayed(this, REMEMBER_APPLY_LOOP_DELAY_MS);
            }
        }, REMEMBER_APPLY_START_UP_DELAY_MS);

        if (MG4Hardware.isLogEnabled()) {
            Log.i(TAG, "=== onCreate tamamlandı ===");
        }
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
        if (mScreenWakeReceiver != null) {
            try { unregisterReceiver(mScreenWakeReceiver); } catch (Exception ignored) {}
        }
        removeOverlay();
        MG4Hardware.destroy();
        mSoundHandler.removeCallbacks(mSoundRunnable);
        mMainHandler.removeCallbacks(mOnePedalRestoreRetryRunnable);
        mChargingCheckHandler.removeCallbacks(mChargingCheckRunnable);
        mConsumptionHandler.removeCallbacks(mConsumptionIntegrationRunnable);
    }

    /** Profil remember denemeleri için ekranın gerçekten açık olup olmadığını kontrol et. */
    private boolean isScreenOnForProfileRemember() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Android sistem gece/gündüz modu ({@link UiModeManager#setNightMode(int)}).
     * API 23+; Android 10+ bazı cihazlarda {@code android.permission.MODIFY_DAY_NIGHT_MODE} gerekebilir (sistem uygulaması genelde sorun olmaz).
     * <p>
     * Modlar: {@link UiModeManager#MODE_NIGHT_AUTO} (0), {@link UiModeManager#MODE_NIGHT_NO} (açık), {@link UiModeManager#MODE_NIGHT_YES} (koyu).
     * <p>
     */
    private void applySystemNightMode(int mode) {
        if(!mAUTO_NIGHT_MODE_ENABLED){
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        try {
            UiModeManager uim = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
            if (uim != null) {
                uim.setNightMode(mode);
                if (MG4Hardware.isLogEnabled()) {
                    Log.i(TAG, "UiModeManager.setNightMode(" + mode + ")");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "UiModeManager.setNightMode: " + t.getMessage());
        }
    }

    /**
     * {@link UiModeManager#getNightMode()} — kullanıcının seçtiği mod (auto / gündüz / gece).
     * API &lt; 23 veya servis yok: {@code -1}.
     */
    private int getSystemNightMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return -1;
        }
        try {
            UiModeManager uim = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
            if (uim != null) {
                return uim.getNightMode();
            }
        } catch (Throwable t) {
            Log.w(TAG, "UiModeManager.getNightMode: " + t.getMessage());
        }
        return -1;
    }

    /** {@link #getSystemNightMode()} {@link UiModeManager#MODE_NIGHT_AUTO} ise true. */
    private boolean isSystemNightModeAuto() {
        return getSystemNightMode() == UiModeManager.MODE_NIGHT_AUTO;
    }

    private boolean isAlwaysUsbDebugEnabled() {
        return getSharedPreferences("drivehub_dort", MODE_PRIVATE)
                .getBoolean(PREF_ALWAYS_USB_DEBUG, false);
    }

    private void registerScreenWakeReceiver() {
        mScreenWakeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent != null ? intent.getAction() : "";
                if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                    mMainHandler.postDelayed(() -> ensureUsbDebugEnabled(action), 1500);
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(mScreenWakeReceiver, f);
    }

    /** Android 9 sistem uygulamasında ADB/USB debug ayarlarını tekrar aktif etmeyi dener. */
    private void ensureUsbDebugEnabled(String reason) {
        if (!isAlwaysUsbDebugEnabled()) {
            return;
        }
        try {
            Settings.Global.putInt(getContentResolver(), "development_settings_enabled", 1);
            Settings.Global.putInt(getContentResolver(), "adb_enabled", 1);
        } catch (Throwable t) {
            Log.w(TAG, "ADB global settings yazılamadı (" + reason + "): " + t);
        }

        // OEM'e göre sys.usb.config veya persist.sys.usb.config gerekebiliyor.
        setSystemPropertyIfPossible("sys.usb.config", "mtp,adb");
        setSystemPropertyIfPossible("persist.sys.usb.config", "mtp,adb");
        setSystemPropertyIfPossible("sys.usb.config", "adb");

        try {
            int adb = Settings.Global.getInt(getContentResolver(), "adb_enabled", 0);
            if (MG4Hardware.isLogEnabled()) {
                Log.i(TAG, "ADB ensure(" + reason + "): adb_enabled=" + adb);
            }
        } catch (Throwable ignored) {}
    }

    private void setSystemPropertyIfPossible(String key, String value) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Method set = c.getMethod("set", String.class, String.class);
            set.invoke(null, key, value);
        } catch (Throwable t) {
            if (MG4Hardware.isLogEnabled()) {
                Log.w(TAG, "SystemProperties set başarısız: " + key + "=" + value + " err=" + t);
            }
        }
    }

    /** Boot sonrası sürüş modunu otomatik geri yükle (kullanıcı \"sürüş modunu hatırla\" switch'ini açtıysa). */
    private void applyRememberedDriveModeIfNeeded() {
        SharedPreferences prefs = getSharedPreferences("drivehub_dort", MODE_PRIVATE);
        boolean rememberEnabled = prefs.getBoolean(PREF_REMEMBER_DRIVE_MODE, false);
        int lastValue = prefs.getInt(PREF_LAST_DRIVE_MODE, DriveMode.NORMAL.value);
        
        if (!rememberEnabled) {
            return;
        }

        DriveMode dm = DriveMode.fromValue(lastValue);
        if (dm == null) {
            return;
        }

        boolean ok = MG4Hardware.setDriveMode(dm);
        if (!ok) {
            return;
        }
        mCurrentDriveMode = dm;
        updateNotification("Sürüş: " + mCurrentDriveMode.label);
    }

    /** Boot sonrası regen seviyesini otomatik geri yükle (kullanıcı \"Regen seviyesini hatırla\" switch'ini açtıysa). */
    private void applyRememberedRegenIfNeeded() {
        // Eski retry kuyruğu varsa temizle (yeni ignition geçişinde temiz başlangıç).
        mMainHandler.removeCallbacks(mOnePedalRestoreRetryRunnable);
        mOnePedalRestoreRetryCount = 0;

        SharedPreferences prefs = getSharedPreferences("drivehub_dort", MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_REMEMBER_REGEN, false)) return;

        int lastValue = prefs.getInt(PREF_LAST_REGEN_LEVEL, RegenLevel.HIGH.value);
        RegenLevel rl = RegenLevel.fromValue(lastValue);
        if (rl == null) {
            return;
        }

        boolean ok = MG4Hardware.setRegenLevel(rl);
        if (ok) {
            updateNotification("Regen: " + rl.label);
            if (MG4Hardware.isLogEnabled()) {
                Log.i(TAG, "RememberRegen: uygulandı → " + rl + " (" + rl.value + ")");
            }

            // Uzaktan uyandırma sonrası OPD komutu bazen ilk anda boşa düşüyor.
            // Tek pedal kayıtlıysa, kısa gecikmeli doğrulama + retry zinciri başlat.
            if (rl == RegenLevel.ONE_PEDAL) {
                mOnePedalRestoreRetryCount = 0;
                mMainHandler.postDelayed(
                        mOnePedalRestoreRetryRunnable,
                        ONE_PEDAL_RESTORE_RETRY_DELAY_MS
                );
            }
        } else {
            if (MG4Hardware.isLogEnabled()) {
                Log.w(TAG, "RememberRegen: setRegenLevel(" + rl + ") başarısız");
            }

            if (rl == RegenLevel.ONE_PEDAL) {
                mOnePedalRestoreRetryCount = 0;
                mMainHandler.postDelayed(
                        mOnePedalRestoreRetryRunnable,
                        ONE_PEDAL_RESTORE_RETRY_DELAY_MS
                );
            }
        }
    }

    /** Tek pedal restore denemesi: doğrula, gerekiyorsa sınırlı sayıda yeniden gönder. */
    private void attemptRememberedOnePedalRestore(String reason) {
        SharedPreferences prefs = getSharedPreferences("drivehub_dort", MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_REMEMBER_REGEN, false)) return;

        int lastValue = prefs.getInt(PREF_LAST_REGEN_LEVEL, RegenLevel.HIGH.value);
        if (lastValue != RegenLevel.ONE_PEDAL.value) return;

        // Kontak RUN değilken zorlamayalım; bir sonraki loop'ta ignition geçişi tekrar tetikler.
        if (MG4Hardware.getVehicleIgnition() < 2) return;

        int onePedalState = MG4Hardware.getOnePedal(); // 1=açık, 0=kapalı, -1=okunamadı
        if (onePedalState == 1) {
            if (MG4Hardware.isLogEnabled()) {
                Log.i(TAG, "RememberRegen(OPD): doğrulandı (reason=" + reason + ")");
            }
            mOnePedalRestoreRetryCount = 0;
            mMainHandler.removeCallbacks(mOnePedalRestoreRetryRunnable);
            return;
        }

        if (mOnePedalRestoreRetryCount >= ONE_PEDAL_RESTORE_MAX_RETRIES) {
            if (MG4Hardware.isLogEnabled()) {
                Log.w(TAG, "RememberRegen(OPD): max retry aşıldı, state=" + onePedalState);
            }
            return;
        }

        mOnePedalRestoreRetryCount++;
        boolean ok = MG4Hardware.setRegenLevel(RegenLevel.ONE_PEDAL);
        if (MG4Hardware.isLogEnabled()) {
            Log.i(TAG, "RememberRegen(OPD) retry#" + mOnePedalRestoreRetryCount
                    + " reason=" + reason + " state=" + onePedalState + " ok=" + ok);
        }

        mMainHandler.postDelayed(
                mOnePedalRestoreRetryRunnable,
                ONE_PEDAL_RESTORE_RETRY_DELAY_MS
        );
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // -------------------------------------------------------------------------
    // Floating overlay — ısıtma hızlı erişim
    // -------------------------------------------------------------------------

    // inflate(..., null) zorunlu — overlay view'lar WindowManager'a eklenir, parent yoktur
    @SuppressLint("InflateParams")
    private void showOverlay() {
        try {
            // Eski overlay varsa temizle
            removeOverlay();

            mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            LayoutInflater inflater = LayoutInflater.from(this);

            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;

            // --- SOL GRUP (DIR + SOL koltuk) ---
            mOverlayLeft = inflater.inflate(R.layout.overlay_left, null);

            // Ekran genişliği — sol grubun x konumunu hesaplamak için
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int centerX = screenW / 2;

            WindowManager.LayoutParams lpLeft = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    flags, PixelFormat.TRANSLUCENT);
            // Sol grup: merkez - offset - tahmini grup genişliği (~120dp→px)
            int groupWidthPx = Math.round(120 * getResources().getDisplayMetrics().density);
            lpLeft.gravity = Gravity.TOP | Gravity.START;
            lpLeft.x = centerX - OVERLAY_OFFSET_PX - groupWidthPx;
            lpLeft.y = 0;

            mBtnOvSteer = mOverlayLeft.findViewById(R.id.btnOvSteer);
            mBtnOvSeatL = mOverlayLeft.findViewById(R.id.btnOvSeatL);
            mOvSeatLBar1 = mOverlayLeft.findViewById(R.id.ovSeatLBar1);
            mOvSeatLBar2 = mOverlayLeft.findViewById(R.id.ovSeatLBar2);
            mOvSeatLBar3 = mOverlayLeft.findViewById(R.id.ovSeatLBar3);

            mBtnOvSteer.setOnClickListener(v -> {
                mSteerLevel = (mSteerLevel == 0) ? 1 : 0;
                ovSteer(mSteerLevel);
            });
            mBtnOvSeatL.setOnClickListener(v -> showSeatPopup(v, true));

            mWindowManager.addView(mOverlayLeft, lpLeft);

            // --- SAĞ GRUP (SAĞ koltuk) ---
            mOverlayRight = inflater.inflate(R.layout.overlay_right, null);

            WindowManager.LayoutParams lpRight = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    flags, PixelFormat.TRANSLUCENT);
            // Sağ grup: merkez + offset
            lpRight.gravity = Gravity.TOP | Gravity.START;
            lpRight.x = centerX + OVERLAY_OFFSET_PX;
            lpRight.y = 0;

            mBtnOvSeatR = mOverlayRight.findViewById(R.id.btnOvSeatR);
            mOvSeatRBar1 = mOverlayRight.findViewById(R.id.ovSeatRBar1);
            mOvSeatRBar2 = mOverlayRight.findViewById(R.id.ovSeatRBar2);
            mOvSeatRBar3 = mOverlayRight.findViewById(R.id.ovSeatRBar3);

            mBtnOvSeatR.setOnClickListener(v -> showSeatPopup(v, false));

            mWindowManager.addView(mOverlayRight, lpRight);

            // CarHvacManager callback'ini kayıt et
            MG4Hardware.setHvacListener(mHvacListener);

            if (MG4Hardware.isLogEnabled()) {
                Log.i(TAG, "Overlay eklendi — sol x=" + (-OVERLAY_OFFSET_PX)
                        + "  sağ x=+" + OVERLAY_OFFSET_PX);
            }
        } catch (Exception e) {
            Log.e(TAG, "Overlay eklenemedi: " + e.getMessage());
        }
    }

    // Açık olan seat popup view'u (ikisi aynı anda açılmasın)
    private View mSeatPopupView;

    @SuppressLint("InflateParams")
    private void showSeatPopup(View anchor, boolean isLeft) {
        // Önceki popup varsa kapat
        dismissSeatPopup();

        LayoutInflater inflater = LayoutInflater.from(this);
        mSeatPopupView = inflater.inflate(R.layout.overlay_seat_popup, null);

        int currentLevel = isLeft ? mSeatLLevel : mSeatRLevel;

        int[] ids = { R.id.popupOff, R.id.popup1, R.id.popup2, R.id.popup3 };
        for (int i = 0; i < ids.length; i++) {
            Button b = mSeatPopupView.findViewById(ids[i]);
            boolean active = (i == currentLevel);
            b.setBackgroundTintList(ColorStateList.valueOf(active ? COLOR_HEAT_ON : COLOR_INACTIVE));
            b.setTextColor(active ? 0xFFFFFFFF : 0xFF8B949E);
        }

        // Popup item tıklamaları
        for (int i = 0; i < ids.length; i++) {
            final int level = i;
            mSeatPopupView.findViewById(ids[i]).setOnClickListener(v -> {
                if (isLeft) ovSeatL(level);
                else        ovSeatR(level);
                dismissSeatPopup();
            });
        }

        // Anchor konumunu bul (sağ taraftan offset hesapla)
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        // x: sağdan mesafe (sağ üst yerleşim için)
        int xFromRight = screenW - loc[0] - anchor.getWidth();

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = xFromRight;
        lp.y = loc[1] + anchor.getHeight() + 4;

        mWindowManager.addView(mSeatPopupView, lp);
    }

    private void dismissSeatPopup() {
        if (mSeatPopupView != null) {
            try { mWindowManager.removeView(mSeatPopupView); } catch (Exception ignored) {}
            mSeatPopupView = null;
        }
    }

    private void removeOverlay() {
        MG4Hardware.setHvacListener(null);
        dismissSeatPopup();
        if (mWindowManager != null) {
            if (mOverlayLeft  != null) try { mWindowManager.removeView(mOverlayLeft);  } catch (Exception ignored) {}
            if (mOverlayRight != null) try { mWindowManager.removeView(mOverlayRight); } catch (Exception ignored) {}
        }
        mOverlayLeft = null;
        mOverlayRight = null;
    }

    // -------------------------------------------------------------------------
    // HVAC callback — CarHvacManager property değişikliklerini dinle
    // -------------------------------------------------------------------------

    /**
     * MG4Hardware'e kayıt edilir. CarHvacManager bir property değiştiğinde çağrılır.
     * propId: PROP_STEERING_HEAT / PROP_SEAT_HEAT_L / PROP_SEAT_HEAT_R
     * value : yeni değer (int)
     */
    private final MG4Hardware.HvacListener mHvacListener =
            (propId, value) -> mMainHandler.post(() -> onHvacChanged(propId, value));

    private void onHvacChanged(int propId, int value) {
        if (mOverlayLeft == null || mOverlayRight == null) return;
        Log.d(TAG, "onHvacChanged propId=0x" + Integer.toHexString(propId) + " value=" + value);

        switch (propId) {
            case MG4Hardware.PROP_STEERING_HEAT_PUB: {
                int newSteer = (value > 0) ? 1 : 0;
                if (newSteer != mSteerLevel) {
                    mSteerLevel = newSteer;
                    applySteerUi(mSteerLevel);
                }
                break;
            }
            case MG4Hardware.PROP_SEAT_HEAT_L_PUB:
                if (value != mSeatLLevel) {
                    mSeatLLevel = value;
                    applySeatLUi(mSeatLLevel);
                }
                break;
            case MG4Hardware.PROP_SEAT_HEAT_R_PUB:
                if (value != mSeatRLevel) {
                    mSeatRLevel = value;
                    applySeatRUi(mSeatRLevel);
                }
                break;
        }
    }

    /** Direksiyon ısıtma UI'ı — sadece görsel (yazma yok) */
    private void applySteerUi(int level) {
        if (mBtnOvSteer == null) return;
        mBtnOvSteer.setBackgroundTintList(
                ColorStateList.valueOf(level > 0 ? COLOR_HEAT_ON : 0xFFFFA657));
        mBtnOvSteer.setTextColor(0xFFFFFFFF);
    }

    /** Sol koltuk UI'ı — sadece görsel */
    private void applySeatLUi(int level) {
        if (mBtnOvSeatL == null) return;
        mBtnOvSeatL.setBackgroundColor(level > 0 ? COLOR_HEAT_ON : 0xFFFFA657);
        updateSeatBars(mOvSeatLBar1, mOvSeatLBar2, mOvSeatLBar3, level);
    }

    /** Sağ koltuk UI'ı — sadece görsel */
    private void applySeatRUi(int level) {
        if (mBtnOvSeatR == null) return;
        mBtnOvSeatR.setBackgroundColor(level > 0 ? COLOR_HEAT_ON : 0xFFFFA657);
        updateSeatBars(mOvSeatRBar1, mOvSeatRBar2, mOvSeatRBar3, level);
    }

    private void ovSteer(int level) {
        new Thread(() -> MG4Hardware.setSteeringHeat(level > 0)).start();
        applySteerUi(level);
        updateNotification("Direksiyon: " + (level == 0 ? "Kapalı" : "Açık"));
    }

    private void ovSeatL(int level) {
        mSeatLLevel = level;
        final int l = level;
        new Thread(() -> MG4Hardware.setSeatHeatLeft(l)).start();
        applySeatLUi(level);
        updateNotification("Sol Koltuk: " + (level == 0 ? "Kapalı" : "Sev." + level));
    }

    private void ovSeatR(int level) {
        mSeatRLevel = level;
        final int l = level;
        new Thread(() -> MG4Hardware.setSeatHeatRight(l)).start();
        applySeatRUi(level);
        updateNotification("Sağ Koltuk: " + (level == 0 ? "Kapalı" : "Sev." + level));
    }

    /** Seviye çubuklarını güncelle: level=0→hepsi gri, 1→1 kırmızı, 2→2, 3→3 */
    private void updateSeatBars(View bar1, View bar2, View bar3, int level) {
        int on  = 0xFFBB3333;
        int off = 0xFF3D2020;
        bar1.setBackgroundColor(level >= 1 ? on : off);
        bar2.setBackgroundColor(level >= 2 ? on : off);
        bar3.setBackgroundColor(level >= 3 ? on : off);
    }

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
                    if (MG4Hardware.isLogEnabled()) {
                        Log.i(TAG, sb.toString());
                    }
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

                if (MG4Hardware.isLogEnabled()) {
                    Log.i(TAG, "HARDKEY >>> keycode=" + keyCode
                            + " down=" + isDown
                            + " longpress=" + isLong
                            + " label=" + keycodeLabel(keyCode));
                }

                SharedPreferences prefs = getSharedPreferences("drivehub_dort", Context.MODE_PRIVATE);
                int assignedKey = prefs.getInt(PREF_ONE_PEDAL_KEY, DEFAULT_ONE_PEDAL_KEY);
                // Eski tercih: 18→286 (Sağ yıldız), 5→291 (Telefon) — araç InputReader keyCode ile eşleşir
                int keyToMatch = (assignedKey == 18) ? 286 : (assignedKey == 5) ? 291 : assignedKey;
                if (assignedKey >= 0 && keyCode == keyToMatch) {
                    String pressOn = prefs.getString(PREF_ONE_PEDAL_PRESS_TYPE, DEFAULT_ONE_PEDAL_PRESS_TYPE);
                    String pressOff = prefs.getString(PREF_ONE_PEDAL_PRESS_TYPE_OFF, DEFAULT_ONE_PEDAL_PRESS_TYPE_OFF);
                    onOnePedalKey(keyCode, isDown, pressOn, pressOff);
                } else {
                    int camera360Key = prefs.getInt(PREF_CAMERA_360_KEY, DEFAULT_CAMERA_360_KEY);
                    int camera360KeyToMatch = (camera360Key == 18) ? 286 : (camera360Key == 5) ? 291 : camera360Key;
                    if (camera360Key >= 0 && keyCode == camera360KeyToMatch) {
                        String pressOn  = prefs.getString(PREF_CAMERA_360_PRESS_ON,  DEFAULT_ONE_PEDAL_PRESS_TYPE);
                        String pressOff = prefs.getString(PREF_CAMERA_360_PRESS_OFF, DEFAULT_ONE_PEDAL_PRESS_TYPE_OFF);
                        onCamera360Key(keyCode, isDown, pressOn, pressOff);
                    } else if (keyCode == KEYCODE_VOLUME_UP) {
                        onVolumeKey(KEYCODE_VOLUME_UP, isDown);
                    } else if (keyCode == KEYCODE_VOLUME_DOWN) {
                        onVolumeKey(KEYCODE_VOLUME_DOWN, isDown);
                    } else if (keyCode == KEYCODE_ANA_EKRAN) {
                        onAnaEkranKey(isDown);
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter(HARDKEY_ACTION);
        ContextCompat.registerReceiver(this, mHardkeyReceiver, filter,
                ContextCompat.RECEIVER_EXPORTED);
        if (MG4Hardware.isLogEnabled()) {
            Log.i(TAG, "Hardkey receiver kayıt edildi — action=" + HARDKEY_ACTION);
            Log.i(TAG, "  Tek Pedal tuşu/basma tipi Regen panelinden atanır.");
            Log.i(TAG, "  Vol↑+Vol↓: iki tuşa dokun → ikisini bırakınca müzik toggle");
        }
    }

    // -------------------------------------------------------------------------
    // Tek Pedal atanmış tuş — Regen panelinde seçilen tuş (Telefon/Sol yıldız/Sağ yıldız) ve basma tipi (Tek/Uzun/Çift)
    // -------------------------------------------------------------------------

    private void onOnePedalKey(int keyCode, boolean isDown, String pressTypeOn, String pressTypeOff) {
        if (isDown) {
            mOnePedalKeyPressed = true;
            mOnePedalLongTriggered = false;
            boolean needLong = "long".equals(pressTypeOn) || "long".equals(pressTypeOff);
            if (needLong) {
                new Thread(() -> {
                    long start = System.currentTimeMillis();
                    while (mOnePedalKeyPressed) {
                        if (System.currentTimeMillis() - start >= ONE_PEDAL_LONG_PRESS_MS) {
                            mOnePedalLongTriggered = true;
                            if ("long".equals(pressTypeOn) && MG4Hardware.getOnePedal() != 1) {
                                MG4Hardware.setRegenLevel(RegenLevel.ONE_PEDAL);
                                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> MG4Hardware.setRegenLevel(RegenLevel.ONE_PEDAL), 250);
                                updateNotification("Tek Pedal: Açık");
                            }
                            if ("long".equals(pressTypeOff) && MG4Hardware.getOnePedal() == 1) {
                                MG4Hardware.setRegenLevel(RegenLevel.HIGH);
                                updateNotification("Tek Pedal: Kapalı");
                            }
                            mOnePedalKeyPressed = false;
                            break;
                        }
                        // Küçük bir uyku ile CPU'yu yorma (50 ms)
                        android.os.SystemClock.sleep(50);
                    }
                }).start();
            }
        } else {
            mOnePedalKeyPressed = false;
            if (!mOnePedalLongTriggered) {
                long now = System.currentTimeMillis();
                boolean isDouble = (mOnePedalLastTapKeyCode == keyCode && (now - mOnePedalLastTapTime) <= SHORTCUT_DOUBLE_PRESS_MS);
                if (isDouble) {
                    mOnePedalLastTapTime = 0;
                    mOnePedalLastTapKeyCode = -1;
                    if ("double".equals(pressTypeOn) && MG4Hardware.getOnePedal() != 1) {
                        MG4Hardware.setRegenLevel(RegenLevel.ONE_PEDAL);
                        updateNotification("Tek Pedal: Açık");
                    } else if ("double".equals(pressTypeOff) && MG4Hardware.getOnePedal() == 1) {
                        MG4Hardware.setRegenLevel(RegenLevel.HIGH);
                        updateNotification("Tek Pedal: Kapalı");
                    }
                } else {
                    mOnePedalLastTapTime = now;
                    mOnePedalLastTapKeyCode = keyCode;
                    if ("single".equals(pressTypeOn) && MG4Hardware.getOnePedal() != 1) {
                        MG4Hardware.setRegenLevel(RegenLevel.ONE_PEDAL);
                        updateNotification("Tek Pedal: Açık");
                    } else if ("single".equals(pressTypeOff) && MG4Hardware.getOnePedal() == 1) {
                        MG4Hardware.setRegenLevel(RegenLevel.HIGH);
                        updateNotification("Tek Pedal: Kapalı");
                    }
                }
            }
        }
    }

    /** 360 kamera atanmış tuş — açma/kapama basma tipi (Tek/Uzun/Çift) ile tetiklenir. */
    private void onCamera360Key(int keyCode, boolean isDown, String pressTypeOn, String pressTypeOff) {
        if (isDown) {
            mCamera360KeyPressed = true;
            mCamera360LongTriggered = false;
            boolean needLong = "long".equals(pressTypeOn) || "long".equals(pressTypeOff);
            if (needLong) {
                new Thread(() -> {
                    long start = System.currentTimeMillis();
                    while (mCamera360KeyPressed) {
                        if (System.currentTimeMillis() - start >= ONE_PEDAL_LONG_PRESS_MS) {
                            mCamera360LongTriggered = true;
                            // Hem açma hem kapama için "long" desteklenebilir
                            if ("long".equals(pressTypeOn)) {
                                open360();
                            }
                            if ("long".equals(pressTypeOff)) {
                                close360();
                            }
                            mCamera360KeyPressed = false;
                            break;
                        }
                        // Küçük bir uyku ile CPU'yu yorma (50 ms)
                        android.os.SystemClock.sleep(50);
                    }
                }).start();
            }
        } else {
            mCamera360KeyPressed = false;
            if (!mCamera360LongTriggered) {
                long now = System.currentTimeMillis();
                long delta = now - mCamera360LastTapTime;
                boolean isDouble = (mCamera360LastTapKeyCode == keyCode
                        && delta <= SHORTCUT_DOUBLE_PRESS_MS);
                if (isDouble) {
                    mCamera360LastTapTime = 0;
                    mCamera360LastTapKeyCode = -1;
                    if ("double".equals(pressTypeOn)) open360();
                    else if ("double".equals(pressTypeOff)) close360();
                } else {
                    mCamera360LastTapTime = now;
                    mCamera360LastTapKeyCode = keyCode;
                    if ("single".equals(pressTypeOn)) open360();
                    else if ("single".equals(pressTypeOff)) close360();
                }
            }
        }
    }

    /** 360 kamera — önce modlu varsa onu, yoksa orijinal 360'ı aç. */
    private void open360() {
        Intent intent = new Intent();
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        // Varsayılan: orijinal sistem 360
        String pkg = "com.saicmotor.hmi.aroundview";
        String cls = "com.saicmotor.hmi.aroundview.AVMActivity";

        try {
            // Eğer modlu 360 yüklüyse, onu kullan
            getPackageManager().getPackageInfo("com.saicmotor.hmi.cam360v2", 0);
            pkg = "com.saicmotor.hmi.cam360v2";
            // Modlu APK'nın manifest paketi cam360v2 ama AVMActivity sınıfı hâlâ
            // com.saicmotor.hmi.aroundview.AVMActivity tam adını kullanıyor.
            cls = "com.saicmotor.hmi.aroundview.AVMActivity";
        } catch (Exception ignored) {
            // Modlu paket yoksa, orijinale düş
        }

        intent.setComponent(new ComponentName(pkg, cls));
        try {
            startActivity(intent);
            updateNotification("360 kamera açıldı");
        } catch (Exception e) {
            Log.w(TAG, "360 Activity başlatılamadı: " + e.getMessage());
            updateNotification("360 açılamadı");
        }
    }

    /** 360 (AVMActivity) şu an en üstteki ekran mı kontrol eder. */
    @SuppressWarnings("deprecation")
    private boolean is360ActivityOnTop() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) return false;
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks != null && !tasks.isEmpty()) {
                ComponentName top = tasks.get(0).topActivity;
                if (top != null) {
                    String pkg = top.getPackageName();
                    String cls = top.getClassName();
                    boolean isOriginal = "com.saicmotor.hmi.aroundview".equals(pkg)
                            && "com.saicmotor.hmi.aroundview.AVMActivity".equals(cls);
                    boolean isMod = "com.saicmotor.hmi.cam360v2".equals(pkg)
                            && "com.saicmotor.hmi.aroundview.AVMActivity".equals(cls);
                    if (isOriginal || isMod) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "360 üstte mi kontrol edilemedi: " + e.getMessage());
        }
        return false;
    }

    /** 360 kapatma — sadece 360 üstteyse geri tuşu simüle (tuş taklidi yok). */
    private void close360() {
        if (!is360ActivityOnTop()) {
            Log.d(TAG, "360 zaten kapalı, geri tuşu gönderilmedi");
            return;
        }
        try {
            Runtime.getRuntime().exec(new String[]{"input", "keyevent", "4"});
            updateNotification("360 kapatıldı (geri)");
        } catch (Exception e) {
            Log.w(TAG, "360 kapatma (geri tuşu): " + e.getMessage());
            updateNotification("360 kapatılamadı");
        }
    }

    // -------------------------------------------------------------------------
    // Volume tuşları — iki tuşa dokunulduğunda "toggle bekliyor", ikisi de bırakılınca komut
    // -------------------------------------------------------------------------

    private void onVolumeKey(int keyCode, boolean isDown) {
        if (keyCode == KEYCODE_VOLUME_UP) {
            mVolUpPressed = isDown;
        } else if (keyCode == KEYCODE_VOLUME_DOWN) {
            mVolDownPressed = isDown;
        }

        long now = System.currentTimeMillis();

        if (isDown) {
            // Vol↑+Vol↓ (müzik toggle) — başlangıç zamanını kaydet
            if (mVolUpPressed && mVolDownPressed) {
                mVolComboPending = true;
                mVolComboStartMs = now;
                if (MG4Hardware.isLogEnabled()) Log.d(TAG, "Vol↑+Vol↓ ikisi basılı → toggle bekliyor");
            }
            // Vol↓+Ana ekran (motor sesi toggle) — sadece bu combo'ya timeout uygulanacak
            if (mVolDownPressed && mAnaEkranPressed) {
                mVolDownAnaEkranComboPending = true;
                mVolDownAnaEkranComboStartMs = now;
                if (MG4Hardware.isLogEnabled()) Log.d(TAG, "Vol↓+Ana ekran ikisi basılı → motor sesi toggle bekliyor");
            }
        } else {
            // Vol↑+Vol↓ combo'su için süre aşımı: çok geç basılan ikinci tuşla müzik toggle etme
            boolean volComboTimedOut = (mVolComboPending
                    && (now - mVolComboStartMs > SHORTCUT_COMBO_TIMEOUT_MS));
            if (volComboTimedOut) {
                if (MG4Hardware.isLogEnabled()) Log.d(TAG, "Vol↑+Vol↓ combo süresi aşıldı, temizlendi");
                mVolComboPending = false;
            }

            // Müzik combo: sadece iki tuş aynı anda bırakıldığında ve süre aşılmamışsa tetiklenir
            if (!mVolUpPressed && !mVolDownPressed && mVolComboPending) {
                mVolComboPending = false;
                if (MG4Hardware.isLogEnabled()) Log.i(TAG, "Vol↑+Vol↓ ikisi bırakıldı → müzik toggle");
                SharedPreferences prefs = getSharedPreferences("drivehub_dort", MODE_PRIVATE);
                boolean enabled = prefs.getBoolean(PREF_SHORTCUT_MEDIA_COMBO_ENABLED, true);
                if (enabled) {
                    sendSaicHardkeyMediaPlayPause();
                } else if (MG4Hardware.isLogEnabled()) {
                    Log.i(TAG, "MEDIA combo devre dışı (PREF_SHORTCUT_MEDIA_COMBO_ENABLED=false)");
                }
            }

            // Vol↓+HOME combo'su için süre aşımı: çok geç basılan ikinci tuşla motor sesini kapatma
            boolean evComboTimedOut = (mVolDownAnaEkranComboPending
                    && (now - mVolDownAnaEkranComboStartMs > SHORTCUT_COMBO_TIMEOUT_MS));
            if (evComboTimedOut) {
                if (MG4Hardware.isLogEnabled()) Log.d(TAG, "Vol↓+Ana ekran combo süresi aşıldı, temizlendi");
                mVolDownAnaEkranComboPending = false;
            }

            if (!mVolDownPressed && !mAnaEkranPressed && mVolDownAnaEkranComboPending) {
                mVolDownAnaEkranComboPending = false;
                if (MG4Hardware.isLogEnabled()) Log.i(TAG, "Vol↓+Ana ekran ikisi bırakıldı → motor sesi toggle");
                SharedPreferences prefs = getSharedPreferences("drivehub_dort", MODE_PRIVATE);
                boolean enabled = prefs.getBoolean(PREF_SHORTCUT_ENGINE_COMBO_ENABLED, true);
                if (enabled) {
                    onMotorSoundToggleFromKey();
                } else if (MG4Hardware.isLogEnabled()) {
                    Log.i(TAG, "Motor sesi combo devre dışı (PREF_SHORTCUT_ENGINE_COMBO_ENABLED=false)");
                }
            }
        }
    }

    /** Ana ekran tuşu (HOME) — Vol↓ ile birlikte basılıp bırakılınca motor sesi aç/kapa. */
    private void onAnaEkranKey(boolean isDown) {
        mAnaEkranPressed = isDown;
        long now = System.currentTimeMillis();

        if (mAnaEkranPressed) {
            if (mVolDownPressed) {
                mVolDownAnaEkranComboPending = true;
                mVolDownAnaEkranComboStartMs = now;
                if (MG4Hardware.isLogEnabled()) Log.d(TAG, "Vol↓+Ana ekran ikisi basılı → motor sesi toggle bekliyor");
            }
        } else {
            boolean evComboTimedOut = (mVolDownAnaEkranComboPending
                    && (now - mVolDownAnaEkranComboStartMs > SHORTCUT_COMBO_TIMEOUT_MS));
            if (evComboTimedOut) {
                if (MG4Hardware.isLogEnabled()) Log.d(TAG, "Vol↓+Ana ekran combo süresi aşıldı, temizlendi (HOME bırakılırken)");
                mVolDownAnaEkranComboPending = false;
            }

            if (!mVolDownPressed && mVolDownAnaEkranComboPending) {
                mVolDownAnaEkranComboPending = false;
                if (MG4Hardware.isLogEnabled()) Log.i(TAG, "Vol↓+Ana ekran ikisi bırakıldı → motor sesi toggle");
                SharedPreferences prefs = getSharedPreferences("drivehub_dort", MODE_PRIVATE);
                boolean enabled = prefs.getBoolean(PREF_SHORTCUT_ENGINE_COMBO_ENABLED, true);
                if (enabled) {
                    onMotorSoundToggleFromKey();
                } else if (MG4Hardware.isLogEnabled()) {
                    Log.i(TAG, "Motor sesi combo devre dışı (PREF_SHORTCUT_ENGINE_COMBO_ENABLED=false)");
                }
            }
        }
    }

    /** Tuş kombo ile tetiklenen motor sesi aç/kapa (SharedPreferences + global flag + EngineSoundManager). */
    private void onMotorSoundToggleFromKey() {
        boolean next = !MG4Hardware.isSoundEnabled();
        MG4Hardware.setSoundEnabled(next);
        getSharedPreferences("drivehub_dort", MODE_PRIVATE).edit().putBoolean("sound_enabled", next).apply();
        if (mEngineSound != null) {
            if (next) {
                if (!mEngineSound.isPlaying()) mEngineSound.start();
            } else {
                if (mEngineSound.isPlaying()) mEngineSound.stop();
            }
        }
        String msg = next ? getString(R.string.toast_motor_sound_on) : getString(R.string.toast_motor_sound_off);
        updateNotification(msg);
        // Ekranda altta kısa süreli yazı (bildirim çubuğu metni ayrıca güncellenir)
        Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
    }

    /**
     * Direksiyondaki duraklat/devam tuşunu taklit eder: SAIC hardkey broadcast (keyCode=301).
     * DOWN + kısa gecikme + UP gönderiyoruz (tek basış; sadece DOWN bazı cihazlarda tepki vermiyor).
     */
    private void sendSaicHardkeyMediaPlayPause() {
        Intent down = new Intent(HARDKEY_ACTION);
        down.putExtra("android.intent.extra.hardkey.keycode", KEYCODE_SAIC_MEDIA_PLAY_PAUSE);
        down.putExtra("android.intent.extra.hardkey.down", true);
        down.putExtra("android.intent.extra.hardkey.longpress", false);
        sendBroadcast(down);
        mChargingCheckHandler.postDelayed(() -> {
            Intent up = new Intent(HARDKEY_ACTION);
            up.putExtra("android.intent.extra.hardkey.keycode", KEYCODE_SAIC_MEDIA_PLAY_PAUSE);
            up.putExtra("android.intent.extra.hardkey.down", false);
            up.putExtra("android.intent.extra.hardkey.longpress", false);
            sendBroadcast(up);
        }, 120);
        if (MG4Hardware.isLogEnabled()) {
            Log.i(TAG, "MEDIA: SAIC hardkey 301 DOWN+UP (duraklat/devam) gönderildi");
        }
        updateNotification("Müzik: ⏯ duraklat/devam (direksiyon tuşu taklidi)");
    }

    /**
     * Standart medya tuşu (KEYCODE_MEDIA_PLAY_PAUSE) — SAIC dinleyicisi yoksa fallback.
     */
    private void sendMediaPlayPauseKey() {
        if (mAudioManager == null) {
            mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }
        long now = System.currentTimeMillis();
        KeyEvent down = new KeyEvent(now, now,
                KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0);
        KeyEvent up = new KeyEvent(now + 50, now + 50,
                KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0);
        if (mAudioManager != null) {
            mAudioManager.dispatchMediaKeyEvent(down);
            mAudioManager.dispatchMediaKeyEvent(up);
        }
        sendMediaButtonBroadcast(down);
        sendMediaButtonBroadcast(up);
        if (MG4Hardware.isLogEnabled()) {
            Log.i(TAG, "MEDIA: PLAY_PAUSE gönderildi (dispatch + broadcast)");
        }
    }

    /** ACTION_MEDIA_BUTTON broadcast — MG4 dahil birçok araç müzik uygulaması bunu dinler. */
    private void sendMediaButtonBroadcast(KeyEvent keyEvent) {
        Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        intent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
        intent.setPackage(null); // Tüm dinleyicilere gitmesi için
        sendOrderedBroadcast(intent, null);
    }

    // -------------------------------------------------------------------------
    // Medya kontrolü — MediaSessionManager
    // -------------------------------------------------------------------------

    private MediaController getActiveMediaController() {
        MediaSessionManager msm =
                (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (msm == null) {
            Log.w(TAG, "MEDIA: MediaSessionManager null — sistem servisi bulunamadı");
            return null;
        }

        // Yöntem 1: MEDIA_CONTENT_CONTROL izniyle getActiveSessions(null)
        // android.uid.system ile bu genellikle çalışır
        try {
            List<MediaController> controllers = msm.getActiveSessions(null);
            if (!controllers.isEmpty()) {
                if (MG4Hardware.isLogEnabled()) {
                    Log.i(TAG, "MEDIA: getActiveSessions(null) → " + controllers.size()
                            + " oturum bulundu");
                }
                for (int i = 0; i < controllers.size(); i++) {
                    MediaController mc = controllers.get(i);
                    PlaybackState ps = mc.getPlaybackState();
                    int st = (ps != null) ? ps.getState() : -1;
                    if (MG4Hardware.isLogEnabled()) {
                        Log.i(TAG, "  [" + i + "] pkg=" + mc.getPackageName()
                                + " state=" + playbackStateLabel(st));
                    }
                }
                // Önce PLAYING durumundaki oturumu bul
                for (MediaController mc : controllers) {
                    PlaybackState ps = mc.getPlaybackState();
                    if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) {
                        if (MG4Hardware.isLogEnabled()) {
                            Log.i(TAG, "MEDIA: PLAYING oturum seçildi → " + mc.getPackageName());
                        }
                        return mc;
                    }
                }
                // PLAYING yoksa ilkini döndür (pause → play için)
                if (MG4Hardware.isLogEnabled()) {
                    Log.i(TAG, "MEDIA: PLAYING yok, ilk oturum seçildi → "
                            + controllers.get(0).getPackageName());
                }
                return controllers.get(0);
            } else {
                Log.w(TAG, "MEDIA: getActiveSessions(null) → boş liste döndü");
            }
        } catch (SecurityException e) {
            Log.w(TAG, "MEDIA: getActiveSessions(null) SecurityException: " + e.getMessage()
                    + " — MEDIA_CONTENT_CONTROL izni yeterli değil mi?");
        } catch (Exception e) {
            Log.e(TAG, "MEDIA: getActiveSessions(null) hata: " + e.getMessage());
        }

        Log.w(TAG, "MEDIA: Aktif medya oturumu bulunamadı");
        return null;
    }

    private void toggleMusicPlayback() {
        if (MG4Hardware.isLogEnabled()) {
            Log.i(TAG, "MEDIA: toggleMusicPlayback çağrıldı");
        }
        MediaController mc = getActiveMediaController();
        if (mc == null) {
            Log.w(TAG, "MEDIA: medya kontrolcüsü yok → KEYCODE_MEDIA_PLAY_PAUSE fallback");
            updateNotification("Müzik: aktif oturum yok, global PLAY/PAUSE gönderildi");
            sendMediaPlayPauseKey();
            return;
        }
        PlaybackState state = mc.getPlaybackState();
        if (state == null) {
            Log.w(TAG, "MEDIA: PlaybackState null (pkg=" + mc.getPackageName()
                    + ") — yine de play() gönderiliyor");
            mc.getTransportControls().play();
            updateNotification("Müzik: ▶ play gönderildi");
            return;
        }
        int ps = state.getState();
        if (MG4Hardware.isLogEnabled()) {
            Log.i(TAG, "MEDIA: playbackState=" + playbackStateLabel(ps)
                    + " (" + ps + ") pkg=" + mc.getPackageName());
        }
        if (ps == PlaybackState.STATE_PLAYING) {
            mc.getTransportControls().pause();
            if (MG4Hardware.isLogEnabled()) {
                Log.i(TAG, "MEDIA: → pause() gönderildi");
            }
            updateNotification("Müzik: ⏸ Durduruldu");
        } else {
            mc.getTransportControls().play();
            if (MG4Hardware.isLogEnabled()) {
                Log.i(TAG, "MEDIA: → play() gönderildi");
            }
            updateNotification("Müzik: ▶ Oynatılıyor");
        }
    }

    private static String playbackStateLabel(int state) {
        switch (state) {
            case PlaybackState.STATE_PLAYING:    return "PLAYING";
            case PlaybackState.STATE_PAUSED:     return "PAUSED";
            case PlaybackState.STATE_STOPPED:    return "STOPPED";
            case PlaybackState.STATE_BUFFERING:  return "BUFFERING";
            case PlaybackState.STATE_NONE:       return "NONE";
            case PlaybackState.STATE_ERROR:      return "ERROR";
            default:                             return "UNKNOWN(" + state + ")";
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
                // Son seçilen modu kaydet (sadece flag aktifse)
                if (mDriveRegenRememberInitialized && (MG4Hardware.getVehicleIgnition() >= 2)) {
                    getSharedPreferences("drivehub_dort", MODE_PRIVATE)
                            .edit().putInt(PREF_LAST_DRIVE_MODE, mCurrentDriveMode.value).apply();
                }
                break;
            case "DRIVE_SET":
                DriveMode dm = DriveMode.fromValue(
                        intent.getIntExtra("driveValue", DriveMode.NORMAL.value));
                MG4Hardware.setDriveMode(dm);
                mCurrentDriveMode = dm;
                updateNotification("Sürüş: " + mCurrentDriveMode.label);
                // Son seçilen modu kaydet (sadece flag aktifse)
                if (mDriveRegenRememberInitialized && (MG4Hardware.getVehicleIgnition() >= 2)) {
                    getSharedPreferences("drivehub_dort", MODE_PRIVATE)
                            .edit().putInt(PREF_LAST_DRIVE_MODE, mCurrentDriveMode.value).apply();
                }
                break;
            case "REGEN_SET":
                RegenLevel rl = RegenLevel.fromValue(
                        intent.getIntExtra("regenValue", RegenLevel.MEDIUM.value));
                boolean regenOk = MG4Hardware.setRegenLevel(rl);
                if (rl == RegenLevel.OFF && !regenOk) {
                    Log.w(TAG, "Regen KAPALI başarısız (Binder null) — araç desteklemiyor olabilir");
                    updateNotification("Regen: Kapalı uygulanamadı");
                } else {
                    updateNotification("Regen: " + rl.label);
                }
                // Son seçilen regen seviyesini kaydet (hatırlama açıksa boot sonrasında tekrar göndereceğiz)
                getSharedPreferences("drivehub_dort", MODE_PRIVATE)
                        .edit().putInt(PREF_LAST_REGEN_LEVEL, rl.value).apply();
                break;
            case "PEDAL_ON":
                MG4Hardware.setRegenLevel(RegenLevel.ONE_PEDAL);
                updateNotification("OPD: Açık");
                break;
            case "PEDAL_OFF":
                MG4Hardware.setRegenLevel(RegenLevel.HIGH);
                updateNotification("OPD: Kapalı");
                break;
            case "HEAT_ON":
                new Thread(() -> MG4Hardware.setSteeringHeat(true)).start();
                updateNotification("Direksiyon: Isıtma Açık");
                break;
            case "HEAT_OFF":
                new Thread(() -> MG4Hardware.setSteeringHeat(false)).start();
                updateNotification("Direksiyon: Isıtma Kapalı");
                break;
            case "HEAT_STEER_SET": {
                int steerLevel = intent.getIntExtra("heatLevel", 0);
                final boolean sl = (steerLevel > 0);
                new Thread(() -> MG4Hardware.setSteeringHeat(sl)).start();
                updateNotification("Direksiyon Isıtma: " + (steerLevel == 0 ? "Kapalı" : "Açık."));
                break;
            }
            case "HEAT_SEAT_L_SET": {
                int seatLLevel = intent.getIntExtra("heatLevel", 0);
                final int ll = seatLLevel;
                new Thread(() -> MG4Hardware.setSeatHeatLeft(ll)).start();
                updateNotification("Sol Koltuk: " + (seatLLevel == 0 ? "Kapalı" : "Sev." + seatLLevel));
                break;
            }
            case "HEAT_SEAT_R_SET": {
                int seatRLevel = intent.getIntExtra("heatLevel", 0);
                final int rl2 = seatRLevel;
                new Thread(() -> MG4Hardware.setSeatHeatRight(rl2)).start();
                updateNotification("Sağ Koltuk: " + (seatRLevel == 0 ? "Kapalı" : "Sev." + seatRLevel));
                break;
            }
            case ACTION_TOGGLE_MUSIC:
                toggleMusicPlayback();
                break;
            case ACTION_ENSURE_USB_DEBUG:
                ensureUsbDebugEnabled("manual");
                break;
            case "OVERLAY_ON":
                showOverlay();
                updateNotification("Overlay: Açık");
                break;
            case "OVERLAY_OFF":
                removeOverlay();
                updateNotification("Overlay: Kapalı");
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
            case 5:
            case 291: return "PHONE";
            case 17:  return "STAR_LEFT";
            case 18:  return "STAR_RIGHT(alt)";
            case 24:  return "VOLUME_UP";
            case 25:  return "VOLUME_DOWN";
            case 286: return "STAR_RIGHT";
            case 287: return "VOICE_ASSISTANT";
            case 297: return "KEY_297";
            case 298: return "KEY_298";
            case 301:
            case 85:  return "MEDIA_PLAY_PAUSE";
            case 87:  return "MEDIA_NEXT";
            case 88:  return "MEDIA_PREV";
            case 126: return "MEDIA_PLAY";
            case 127: return "MEDIA_PAUSE";
            case 66:  return "ENTER";
            case 4:   return "BACK";
            case 3:   return "HOME";
            case 164: return "MUTE";
            default:  return "UNKNOWN(" + keycode + ")";
        }
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_MIN);
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
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true).setSilent(true).build();
    }
}
