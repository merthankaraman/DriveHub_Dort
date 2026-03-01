package com.example.mg4_v3.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.mg4_v3.R;
import com.example.mg4_v3.hardware.MG4Hardware;
import com.example.mg4_v3.audio.EngineSoundManager;
import com.example.mg4_v3.model.DriveMode;
import com.example.mg4_v3.model.RegenLevel;

import java.util.List;

public class MG4ControlService extends Service {

    private static final String TAG = "MG4_SERVICE";
    private static final String CHANNEL_ID = "mg4_channel";
    private static final int    NOTIF_ID   = 1001;
    /** Ekrandan müzik duraklat/devam için Activity'nin servise gönderdiği action */
    public static final String ACTION_TOGGLE_MUSIC = "com.example.mg4_v3.TOGGLE_MUSIC";

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
    //   Direksiyon müzik tuşu (log’da keycode=301) → müzik pause/play
    private static final String HARDKEY_ACTION      = "com.saic.keyevent.hardkey.report";
    private static final int    KEYCODE_PHONE       = 5;
    private static final int    KEYCODE_STAR        = 17;
    private static final int    KEYCODE_STAR_RIGHT  = 18;
    private static final int    KEYCODE_VOLUME_UP   = 24;
    private static final int    KEYCODE_VOLUME_DOWN = 25;
    // Vol↑+Vol↓ combo → müzik pause/play (300ms pencere)
    private static final long COMBO_WINDOW_MS = 300;
    private long mVolUpDownTime   = 0L;
    private long mVolDownDownTime = 0L;

    // Tek pedal atama (Regen panelinden ayarlanır)
    private static final String PREF_ONE_PEDAL_KEY = "one_pedal_key";
    private static final String PREF_ONE_PEDAL_PRESS_TYPE = "one_pedal_press_type";
    /** -1 = kapalı (varsayılan); hiçbir tuş tek pedal tetiklemez */
    private static final int DEFAULT_ONE_PEDAL_KEY = -1;
    private static final String DEFAULT_ONE_PEDAL_PRESS_TYPE = "long";
    private static final long ONE_PEDAL_LONG_PRESS_MS = 1200;
    private static final long ONE_PEDAL_DOUBLE_TAP_MS = 400;

    private volatile boolean mOnePedalKeyPressed = false;
    private volatile boolean mOnePedalLongTriggered = false;
    private long mOnePedalLastTapTime = 0L;
    private int mOnePedalLastTapKeyCode = -1;

    private DriveMode mCurrentDriveMode = DriveMode.NORMAL;
    private BroadcastReceiver mHardkeyReceiver;

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

    // Sistem medya sesini kontrol etmek için
    private AudioManager mAudioManager;

    // Yapay motor sesi (sanal ses) – servis tarafında da yönet
    private EngineSoundManager mEngineSound;
    private final Handler mSoundHandler = new Handler(Looper.getMainLooper());
    private final Runnable mSoundRunnable = new Runnable() {
        @Override
        public void run() {
            if (mEngineSound == null) {
                mSoundHandler.postDelayed(this, 1000);
                return;
            }

            // Hız tek kaynak: sim açıksa sim, değilse hattan tek okuma (getSpeedForEngine)
            float speed = MG4Hardware.getSpeedForEngine();
            boolean ready = MG4Hardware.isVehicleReady();
            float dcVolt = MG4Hardware.getDcVoltage();
            float dcAmpAct = MG4Hardware.getDcCurrentActual();
            float dcPowerKw = (Float.isNaN(dcVolt) || Float.isNaN(dcAmpAct)) ? 0f : (dcVolt * dcAmpAct) / 1000f;

            SharedPreferences prefs = getSharedPreferences("mg4_v3", MODE_PRIVATE);
            boolean soundEnabled = prefs.getBoolean("sound_enabled", false);

            if (soundEnabled && ready) {
                if (!mEngineSound.isPlaying()) {
                    mEngineSound.start();
                }
                mEngineSound.onSpeedChanged(speed, dcPowerKw);
            } else {
                if (mEngineSound.isPlaying()) {
                    mEngineSound.stop();
                }
            }

            // ~10 Hz güncelle (MainActivity ile uyumlu)
            mSoundHandler.postDelayed(this, 100);
        }
    };

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

        SharedPreferences prefs = getSharedPreferences("mg4_v3", MODE_PRIVATE);
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
        String device = android.os.Build.DEVICE != null ? android.os.Build.DEVICE : "";
        boolean isEmulator = device.contains("emu") || device.contains("sdk_gphone");
        if (!isEmulator) {
            mSoundHandler.post(mSoundRunnable);
        } else {
            if (MG4Hardware.isLogEnabled()) {
                Log.i(TAG, "EngineSound: emulator tespit edildi (" + device + "), servis ses döngüsü devre dışı. Simülasyon sadece Activity tarafında çalışacak.");
            }
        }

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
        removeOverlay();
        MG4Hardware.destroy();
        mSoundHandler.removeCallbacks(mSoundRunnable);
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

                SharedPreferences prefs = getSharedPreferences("mg4_v3", Context.MODE_PRIVATE);
                int assignedKey = prefs.getInt(PREF_ONE_PEDAL_KEY, DEFAULT_ONE_PEDAL_KEY);
                if (assignedKey >= 0 && keyCode == assignedKey) {
                    String pressType = prefs.getString(PREF_ONE_PEDAL_PRESS_TYPE, DEFAULT_ONE_PEDAL_PRESS_TYPE);
                    onOnePedalKey(keyCode, isDown, isLong, pressType);
                } else {
                    if (!isDown) return;
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
        if (MG4Hardware.isLogEnabled()) {
            Log.i(TAG, "Hardkey receiver kayıt edildi — action=" + HARDKEY_ACTION);
            Log.i(TAG, "  Tek Pedal tuşu/basma tipi Regen panelinden atanır.");
            Log.i(TAG, "  Vol↑+Vol↓ combo (300ms)  → müzik pause/play");
        }
    }

    // -------------------------------------------------------------------------
    // Tek Pedal atanmış tuş — Regen panelinde seçilen tuş (Telefon/Sol yıldız/Sağ yıldız) ve basma tipi (Tek/Uzun/Çift)
    // -------------------------------------------------------------------------

    private void onOnePedalKey(int keyCode, boolean isDown, boolean isLong, String pressType) {
        if (isDown) {
            mOnePedalKeyPressed = true;
            mOnePedalLongTriggered = false;
            if ("long".equals(pressType)) {
                new Thread(() -> {
                    long start = System.currentTimeMillis();
                    while (mOnePedalKeyPressed) {
                        if (System.currentTimeMillis() - start >= ONE_PEDAL_LONG_PRESS_MS) {
                            mOnePedalLongTriggered = true;
                            MG4Hardware.setOnePedal(true);
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> MG4Hardware.setOnePedal(true), 250);
                            updateNotification("Tek Pedal: Açık");
                            mOnePedalKeyPressed = false;
                            break;
                        }
                        try { Thread.sleep(50); } catch (Exception ignored) {}
                    }
                }).start();
            }
        } else {
            mOnePedalKeyPressed = false;
            if ("single".equals(pressType)) {
                if (!mOnePedalLongTriggered) {
                    if (MG4Hardware.getOnePedal() == 1) {
                        MG4Hardware.setOnePedal(false);
                        updateNotification("Tek Pedal: Kapalı");
                    } else {
                        MG4Hardware.setOnePedal(true);
                        updateNotification("Tek Pedal: Açık");
                    }
                }
            } else if ("long".equals(pressType)) {
                // Uzun basma seçiliyken: kısa basış = tek pedalı kapat
                if (!mOnePedalLongTriggered && MG4Hardware.getOnePedal() == 1) {
                    MG4Hardware.setOnePedal(false);
                    updateNotification("Tek Pedal: Kapalı");
                }
            } else if ("double".equals(pressType)) {
                long now = System.currentTimeMillis();
                if (mOnePedalLastTapKeyCode == keyCode && (now - mOnePedalLastTapTime) <= ONE_PEDAL_DOUBLE_TAP_MS) {
                    mOnePedalLastTapTime = 0;
                    mOnePedalLastTapKeyCode = -1;
                    if (MG4Hardware.getOnePedal() == 1) {
                        MG4Hardware.setOnePedal(false);
                        updateNotification("Tek Pedal: Kapalı");
                    } else {
                        MG4Hardware.setOnePedal(true);
                        updateNotification("Tek Pedal: Açık");
                    }
                } else {
                    mOnePedalLastTapTime = now;
                    mOnePedalLastTapKeyCode = keyCode;
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
            if (MG4Hardware.isLogEnabled()) {
                Log.i(TAG, "Vol↑+Vol↓ COMBO tetiklendi (gap=" + gap + "ms) → toggleMusicPlayback");
            }
            mVolUpDownTime   = 0;
            mVolDownDownTime = 0;
            toggleMusicPlayback();
        }
    }

    /** Log’da direksiyon müzik tuşu keycode=301 ile geliyor; müzik uygulaması buna tepki veriyor. */

    /**
     * Ses kısma+açma (Vol↑+Vol↓) combo’da müzik duraklat/başlat.
     * MG4 müzik uygulaması SAIC hardkey 301’i dinliyor, o yüzden önce onu gönderiyoruz.
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
                break;
            case "DRIVE_SET":
                DriveMode dm = DriveMode.fromValue(
                        intent.getIntExtra("driveValue", DriveMode.NORMAL.value));
                MG4Hardware.setDriveMode(dm);
                mCurrentDriveMode = dm;
                updateNotification("Sürüş: " + mCurrentDriveMode.label);
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
            case 5:   return "PHONE";
            case 17:  return "STAR/FAV";
            case 18:  return "STAR_RIGHT";
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
