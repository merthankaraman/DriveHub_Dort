package com.drivehub.dort.audio;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.drivehub.dort.R;
import com.drivehub.dort.hardware.MG4Hardware;

import java.util.ArrayList;
import java.util.List;

/**
 * DriveHub Dort — Simülasyon Motoru (Hibrit Sistem: Single-Layer + FMOD Dual-Layer)
 * Modüler Yapı: Araç Profilleri -> TCU (Şanzıman Beyni) -> SoundPool Mikser
 */
public class EngineSoundManager {

    private static final String TAG = "EngineSoundV3";
    private static EngineSoundManager sInstance = null;
    private final Context mContext;
    private final Handler mHandler;

    // --- DURUM DEĞİŞKENLERİ ---
    private boolean mIsPlaying = false;
    private SoundPool mSoundPool;
    private int mLoadedSamplesCount = 0;
    private long mLastMixerUpdateTime = 0;
    private static final long MIXER_UPDATE_INTERVAL_MS = 16; // 60Hz güncelleme hızı

    private float mCurrentSpeedKmh = 0f;
    private float mSimulatedThrottle = 0f; // 0–1
    private float mMotorMaxPower = 130f;
    private float mCurrentDcPowerKw = 0f;
    private boolean mUseManualThrottle = false;
    private int mCurrentGear = 0;
    private float mCurrentRpm = 1000f;
    private float mDriveModeAggressiveness = 0.4f;
    private VehicleProfile mActiveProfile;
    private String mCurrentProfileLabel = "Lotus Exige 240";

    // --- ESKİ SİSTEM (Tek Katmanlılar İçin) ---
    private EngineSample[] mCurrentSamples;

    // --- YENİ SİSTEM (Çift Katmanlılar İçin ON/OFF) ---
    private boolean mIsDualLayer = false;
    private EngineSample[] mCurrentSamplesOn;
    private EngineSample[] mCurrentSamplesOff;

    private float mIdleRpm = 1000f;
    private float mCurrentIdleVolumeScale = 1;
    private float mUserIdleVolumeScale = 1f;
    private float mIdlePitch = 1f;
    private float mMaxRpm = 9000f;
    private float mMasterVolume = 0.6f;
    private int mGearWhineSoundId = -1;
    private int mGearWhineStreamId = -1;
    private float mGearWhineMaxVol = 0.15f;
    private float mWhineMaxSpeed = 200;

    private int mTurboSoundId = -1;
    private int mTurboStreamId = -1;
    private float mCurrentTurboBoost = 0f;
    private float mTurboMaxSound = 0.3f;
    // Supercharger (kompresör) genel master seviyesi – motor sesine göre baskınlık buradan ayarlanıyor
    private float mCompressorMaxVol = 0.7f;

    private long mLastShiftTime = 0;
    private boolean mEnableRevMatch = true;
    private boolean mGearWhineEnabled = true;
    // SUBWAVE (DERİN BAS) DEĞİŞKENLERİ
    private int mSubwaveSoundId = -1;
    private int mSubwaveStreamId = -1;
    private boolean mSubwaveEnabled = true;
    private int mFlutterSoundId = -1;
    private float mFlutterSoundmultiplier = 0.4f;
    private long mLastFlutterTime = 0;
    private float mLastThrottleForFlutter = 0f;

    // START/STOP MARŞ DEĞİŞKENLERİ
    private int mStartSoundId = -1;
    private int mStopSoundId = -1;
    private long mEngineStartTime = 0; // Marşın basıldığı anı tutar
    private long mAutoStartupDelayMs = 0; // Dosyadan otomatik okunan marş süresi
    private boolean mExhaustPopEnabled = true; // Arayüzden buna bağlanacak
    private int[] mGlobalPopIds = new int[3];
    private int mPopsRemaining = 0;
    private long mNextPopTime = 0;
    private float mLastThrottleForPop = 0f;

    public enum SoundMode { VIRTUAL_GEAR_V2 }
    public static class VehicleProfile {
        public final String name;
        public final int[][] onSounds;  // Zorunlu (ON sesleri ve RPM'leri)
        public final int[][] offSounds; // Opsiyonel (Yoksa null gönder)
        public final float idleRpm;
        public final float maxRpm;
        public final float idleVolumeScale;
        public final float[][] gearRanges;
        /** 0: No turbo, 1: Supercharger 2: Normal Turbo, 3: Turbo GTR */
        public final int hasTurbo;

        public final float rpmOnSmooth;
        public final float rpmOffSmooth;
        public final long shiftDurationMs;
        public final float wobbleMagnitude;

        public final int startSoundResId;
        public final int stopSoundResId;

        public VehicleProfile(String name, float idleRpm, float maxRpm, float idleVolumeScale, int hasTurbo, float[][] gearRanges,
                              float rpmOnSmooth, float rpmOffSmooth, long shiftDurationMs, float wobbleMagnitude,
                              int startSoundResId, int stopSoundResId, int[][] onSounds, int[][] offSounds) {
            this.name = name;
            this.idleRpm = idleRpm;
            this.maxRpm = maxRpm;
            this.idleVolumeScale = idleVolumeScale;
            this.hasTurbo = hasTurbo;
            this.gearRanges = gearRanges;
            this.rpmOnSmooth = rpmOnSmooth;
            this.rpmOffSmooth = rpmOffSmooth;
            this.shiftDurationMs = shiftDurationMs;
            this.wobbleMagnitude = wobbleMagnitude;
            this.startSoundResId = startSoundResId;
            this.stopSoundResId = stopSoundResId;
            this.onSounds = onSounds;
            this.offSounds = offSounds;
        }
    }

    private static class EngineSample {
        final int baseRpm;
        final int resourceId;
        /**
         * Opsiyonel aralık bilgisi (FMOD tarzı min/max RPM penceresi).
         * Eğer minRpm/maxRpm < 0 ise, eski komşu tabanlı crossfade mantığı kullanılır.
         */
        final int minRpm;
        final int maxRpm;
        int soundId = -1;
        int streamId = -1;

        EngineSample(int baseRpm, int resourceId) {
            this(baseRpm, resourceId, -1, -1);
        }

        EngineSample(int baseRpm, int resourceId, int minRpm, int maxRpm) {
            this.baseRpm = baseRpm;
            this.resourceId = resourceId;
            this.minRpm = minRpm;
            this.maxRpm = maxRpm;
        }

        boolean hasWindow() {
            return minRpm >= 0 && maxRpm > minRpm;
        }
    }

    public void setMasterVolume(float volume01) { mMasterVolume = Math.max(0f, Math.min(1f, volume01)); }
    public void setMotorMaxPower(float powerKw) { mMotorMaxPower = (Math.max(50f, Math.min(200f, powerKw)) - 20f); }

    public void initFromPreferences(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences("drivehub_dort", Context.MODE_PRIVATE);
        String profile = prefs.getString("sound_profile", "Lotus Exige 240");
        applyProfileLabel(profile);
        loadIdleSettingsForProfile(context, profile);
        applySoundCharacterFromString(prefs.getString("sound_character", "SPORT"));
        setMasterVolume(Math.max(0, Math.min(100, prefs.getInt("sound_master", 60))) / 100f);
    }

    public String getCurrentProfileName() { return mCurrentProfileLabel != null ? mCurrentProfileLabel : "Lotus Exige 240"; }
    public static String profileToPrefsSuffix(String profileName) { return profileName != null ? profileName.replace(" ", "_") : "Lotus_Exige"; }

    public void loadIdleSettingsForProfile(Context context, String profileName) {
        if (context == null || profileName == null) return;
        android.content.SharedPreferences prefs = context.getSharedPreferences("drivehub_dort", Context.MODE_PRIVATE);
        String suffix = profileToPrefsSuffix(profileName);
        int vol = prefs.getInt("idle_volume_scale_" + suffix, 100);
        float pitch = prefs.getFloat("idle_pitch_" + suffix, 1f);
        setUserIdleVolumeScale(Math.max(0, Math.min(100, vol)) / 100f);
        setIdlePitch(Math.max(0.5f, Math.min(2f, pitch)));
    }

    public void setUserIdleVolumeScale(float scale01) {
        mUserIdleVolumeScale = Math.max(0f, Math.min(1f, scale01));
        if (mActiveProfile != null) mCurrentIdleVolumeScale = mActiveProfile.idleVolumeScale * mUserIdleVolumeScale;
    }
    public void setIdlePitch(float pitch) { mIdlePitch = Math.max(0.5f, Math.min(2f, pitch)); }

    public void applySoundCharacterFromString(String character) {
        float agg = 0.7f;
        if ("ECO".equals(character)) {
            agg = 0.25f;
        } else if ("SPORT".equals(character)) {
            agg = 0.7f;
        } else if ("NORMAL".equals(character)) {
            // NORMAL opsiyonu kaldırıldı; geriye dönük uyumluluk için SPORT'a eşliyoruz.
            agg = 0.7f;
        } else if ("AUTO".equals(character)) {
            // AUTO'da sürüş moduna göre: Eco -> Eco, Eco değil -> Sport
            int dm = com.drivehub.dort.hardware.MG4Hardware.getDriveMode();
            com.drivehub.dort.model.DriveMode mode = com.drivehub.dort.model.DriveMode.fromValue(dm);
            agg = (mode == com.drivehub.dort.model.DriveMode.ECO) ? 0.25f : 0.7f;
        }

        // Eğer sürüş modu GERÇEKTEN değiştiyse şanzımanı uyandır!
        if (mDriveModeAggressiveness != agg) {
            setDriveModeAggressiveness(agg);
            mLastShiftTime = 0;        // Vites bekleme süresini sıfırla (Anında vites atabilsin diye)
        }
    }

    public static final String[] PROFILE_LABELS = {
            "Lotus Exige 240",
            "Lexus LFA",
            "GTR R34",
            "Ferrari F2004",
            "McLaren P1",
            "BMW F30 328i",
            "BMW F30 328i V2"
    };

    public static String[] getProfileLabels() { return PROFILE_LABELS; }

    public void applyProfileLabel(String profile) {
        mCurrentProfileLabel = (profile != null && !profile.isEmpty()) ? profile : "Lotus Exige 240";
        if ("Lexus LFA".equals(profile)) setVehicleProfile(PROFILE_LEXUS_LFA());
        else if ("GTR R34".equals(profile)) setVehicleProfile(PROFILE_GTRR34());
        else if ("Ferrari F2004".equals(profile)) setVehicleProfile(PROFILE_FERRARI_F2004());
        else if ("McLaren P1".equals(profile)) setVehicleProfile(PROFILE_MCLAREN_P1());
        else if ("BMW F30 328i".equals(profile)) setVehicleProfile(PROFILE_BMW_328I());
        else if ("BMW F30 328i V2".equals(profile)) setVehicleProfile(PROFILE_BMW_328I_V2());
        else setVehicleProfile(PROFILE_LOTUS_EXIGE());
    }

    public void setDriveModeAggressiveness(float aggressiveness01) { mDriveModeAggressiveness = Math.max(0f, Math.min(1f, aggressiveness01)); }
    public void setSimulatedThrottle(float throttle01) { mSimulatedThrottle = Math.max(0.01f, Math.min(1f, throttle01)); }
    public float getSimulatedThrottle() { return mSimulatedThrottle; }
    public boolean isRevMatchEnabled() { return mEnableRevMatch; }
    public void setRevMatchEnabled(boolean enabled) { mEnableRevMatch = enabled; }
    public boolean isGearWhineEnabled() { return mGearWhineEnabled; }
    public void setGearWhineEnabled(boolean enabled) { mGearWhineEnabled = enabled; }

    public boolean isExhaustPopEnabled() { return mExhaustPopEnabled; }

    public void setExhaustPopEnabled(boolean enabled) {
        mExhaustPopEnabled = enabled;
        if (!enabled) {
            // Kapalıyken anlık olarak burble/patlama tetiklerini sıfırla ki gecikmeli ses kalmasın.
            mPopsRemaining = 0;
            mNextPopTime = 0;
            mLastThrottleForPop = 0f;
        }
    }
    public void setUseManualThrottle(boolean use) { mUseManualThrottle = use; }
    public float getCurrentRpm() { return mCurrentRpm; }
    public int getCurrentGear() { return mCurrentGear; }

    // ==========================================
    // HAZIR ARAÇ TANIMLARI (Hepsi 0,0 Marş/İstop güncellendi)
    // ==========================================
    public static VehicleProfile PROFILE_LOTUS_EXIGE() {
        return new VehicleProfile("Lotus Exige 240", 800, 9000f, 1,
                1,
                new float[][]{
                        {0f,   55f},   // 1. Vites
                        {20f,  85f},   // 2. Vites
                        {45f,  120f},  // 3. Vites
                        {65f,  155f},  // 4. Vites
                        {85f,  195f},  // 5. Vites
                        {105f, 235f},  // 6. Vites
                        {122f, 280f},  // 7. Vites
                        {138f, 360f}   // 8. Vites
                },
                0.18f, 0.08f, 150, 150f,
                R.raw.elisec_startup, R.raw.igniton_stop,
                // ON Katmanı
                new int[][]{
                        {R.raw.elisesc_idle, 800, 0, 3500},           // 3500'e kadar sönerek devam eder
                        {R.raw.elisesc_on_3000, 3000, 1500, 5500},    // 1500'de başlar, 3000'de tam güç, 5500'de biter
                        {R.raw.elisesc_on_4750, 4750, 3500, 8500},    // 3500-5500 arası idle ve 3000 ile karışır
                        {R.raw.elisesc_on_8115, 8115, 6000, 9000},    // 6000'de başlar, çok daha erken sızar
                        {R.raw.elisesc_on_9649, 8800, 8000, 9200},
                        {R.raw.elisesc_on_9649, 9000, 8500, 9500}
                },
                // OFF Katmanı
                new int[][]{
                        {R.raw.elisesc_idle, 800, 0, 3500},
                        {R.raw.elisesc_off_2500, 2500, 1200, 4500},
                        {R.raw.elisesc_off_3750, 3750, 2800, 6000},
                        {R.raw.elisesc_off_5000, 5000, 4000, 8800},
                        {R.raw.elisesc_off_8500, 8500, 7500, 9500}
                }
        );
    }
    public static VehicleProfile PROFILE_LEXUS_LFA() {
        return new VehicleProfile("Lexus LFA", 984f, 9550f, 1f, 0,
                new float[][]{
                        {0f,   55f},   // 1. Vites
                        {20f,  85f},   // 2. Vites
                        {45f,  120f},  // 3. Vites
                        {65f,  155f},  // 4. Vites
                        {85f,  195f},  // 5. Vites
                        {105f, 235f},  // 6. Vites
                        {122f, 280f},  // 7. Vites
                        {138f, 360f}   // 8. Vites
                },
                0.25f, 0.15f, 200, 180f,
                R.raw.lfa_in_startup, R.raw.igniton_stop,
                // ON Katmanı
                new int[][]{
                        {R.raw.lfa_in_idle, 984, 0, 4000},            // Idle sesi 4000'e kadar alttan duyulmaya devam eder
                        {R.raw.lfa_in_onverylow_1, 2500, 1500, 6000}, // 1500-4000 arası yumuşak geçiş başlar
                        {R.raw.lfa_in_onlow, 4500, 3000, 8000},       // Low sesi 3000'de sızmaya başlar
                        {R.raw.lfa_in_onmid, 6500, 5000, 9000},
                        {R.raw.lfa_in_onhigh, 8500, 7000, 9550},
                        {R.raw.lfa_in_onhigh, 9550, 8000, 10500}
                },
                // OFF Katmanı
                new int[][]{
                        {R.raw.lfa_in_idle, 984, 0, 4000},
                        {R.raw.lfa_in_offverylow_2, 2500, 1500, 6000},
                        {R.raw.lfa_in_offlow, 4500, 3000, 8000},
                        {R.raw.lfa_in_offmid, 6500, 5000, 9000},
                        {R.raw.lfa_in_offhigh, 8500, 7000, 9550}
                }
        );
    }
    public static VehicleProfile PROFILE_FERRARI_F2004() {
        return new VehicleProfile("Ferrari F2004",
                4200f,
                19000f,
                0.6f,
                0,
                new float[][]{
                        {0f, 45f},
                        {12f, 72f},
                        {33f, 93f},
                        {54f, 114f},
                        {75f, 135f},
                        {96f, 156f},
                        {117f, 180f}
                },
                0.25f, 0.15f, 50, 300f,
                0, R.raw.igniton_stop,

                // ON katmanı
                new int[][]{
                        {R.raw.f2004_in_idle,            4200, 0, 7000},     // 4200-7000 arası yavaşça söner
                        {R.raw.f2004_in_verylow_2,       5500, 3500, 11500},  // 3500'de sızmaya başlar
                        {R.raw.f2004_in_on_mid,          9500, 5000, 14000},
                        {R.raw.f2004_in_on_low,         11500, 9000, 16000},
                        {R.raw.f2004_in_on_mid2,        14000, 11000, 18000},
                        {R.raw.f2004_in_on_high_mix,    16000, 13000, 19500},
                        {R.raw.f2004_in_on_veryhigh_mix,18500, 15500, 21000}
                },
                null
        );
    }
    public static VehicleProfile PROFILE_MCLAREN_P1() {
        return new VehicleProfile("McLaren P1",
                1000f, 8500f,
                0.8f,
                2,
                new float[][]{
                        {0f,   55f},   // 1. Vites
                        {20f,  85f},   // 2. Vites
                        {45f,  120f},  // 3. Vites
                        {65f,  155f},  // 4. Vites
                        {85f,  195f},  // 5. Vites
                        {105f, 235f},  // 6. Vites
                        {122f, 280f},  // 7. Vites
                        {138f, 360f}   // 8. Vites
                },
                0.18f, 0.08f,120,220f,
                0, 0,
                // ON Katmanı
                new int[][]{
                        {R.raw.p1_in_idle,          1000, 0, 3500},
                        {R.raw.p1_in_on_verylow2,   2500, 1000, 5500},
                        {R.raw.p1_in_on_low2,       4000, 2000, 7000},
                        {R.raw.p1_in_on_lowmid_b,   5500, 3500, 8500},
                        {R.raw.p1_in_on_mid_c,      7000, 5000, 9000},
                        {R.raw.p1_in_on_high_b_2,   8000, 6500, 9500},
                        {R.raw.p1_in_on_veryhigh_b, 8500, 7500, 10000}
                },
                new int[][]{
                        {R.raw.p1_in_idle,          1000, 0, 3500},
                        {R.raw.p1_in_off_verylow,   2500, 1000, 5500},
                        {R.raw.p1_in_off_low_2,     4000, 2000, 7500},
                        {R.raw.p1_in_off_mid_2,     6000, 4000, 9000},
                        {R.raw.p1_in_off_high,      8500, 6000, 10000}
                }
        );
    }
    public static VehicleProfile PROFILE_GTRR34() {
        return new VehicleProfile("GTR R34", 1000f, 8200, 0.5f,
                3,
                new float[][]{
                        {0f,   55f},   // 1. Vites
                        {20f,  85f},   // 2. Vites
                        {45f,  120f},  // 3. Vites
                        {65f,  155f},  // 4. Vites
                        {85f,  195f},  // 5. Vites
                        {105f, 235f},  // 6. Vites
                        {122f, 280f},  // 7. Vites
                        {138f, 360f}   // 8. Vites
                },
                0.18f, 0.08f,110,250f,
                0,0,
                // ON Katmanı
                new int[][]{
                        {R.raw.rb26_4_ex_idle, 1000},
                        {R.raw.rb26_2_in_on_verylow2, 1500},
                        {R.raw.rb26_2_in_on_verylow, 3500},
                        {R.raw.rb26_2_in_on_low3, 5000},
                        {R.raw.rb26_2_in_on_mid3, 6500},
                        {R.raw.rb26_in_on_high2, 7500},
                        {R.raw.rb26_in_on_veryhigh, 8200}
                },
                // OFF Katmanı
                new int[][]{
                        {R.raw.rb26_4_ex_idle, 1000},
                        {R.raw.rb26_ex_5_offverylow, 1500},
                        {R.raw.rb26_ex_5_offlow, 4000},
                        {R.raw.rb26_ex_5_offmid, 6500}
                }
        );
    }
    public static VehicleProfile PROFILE_BMW_328I() {
        return new VehicleProfile("BMW F30 328i",
                800, 7500f,
                1f, 2,
                new float[][]{
                        {0f,   55f},   // 1. Vites
                        {20f,  85f},   // 2. Vites
                        {45f,  120f},  // 3. Vites
                        {65f,  155f},  // 4. Vites
                        {85f,  195f},  // 5. Vites
                        {105f, 235f},  // 6. Vites
                        {122f, 280f},  // 7. Vites
                        {138f, 360f}   // 8. Vites
                },
                0.20f, 0.10f, 150, 150f,
                0, 0,
                new int[][]{
                        {R.raw.f30_enga_1290, 1290},
                        {R.raw.f30_enga_1980, 1980},
                        {R.raw.f30_enga_2661, 2661},
                        {R.raw.f30_enga_3951, 3951},
                        {R.raw.f30_enga_5031, 5031},
                        {R.raw.f30_enga_6141, 6141},
                        {R.raw.f30_enga_7200, 7200}
                },
                null
        );
    }
    public static VehicleProfile PROFILE_BMW_328I_V2() {
        return new VehicleProfile("BMW F30 328i V2",
                800, 7500f,
                1f, 2,
                new float[][]{
                        {0f,   55f},   // 1. Vites
                        {20f,  85f},   // 2. Vites
                        {45f,  120f},  // 3. Vites
                        {65f,  155f},  // 4. Vites
                        {85f,  195f},  // 5. Vites
                        {105f, 235f},  // 6. Vites
                        {122f, 280f},  // 7. Vites
                        {138f, 360f}   // 8. Vites
                },
                0.20f, 0.10f, 150, 150f,
                0, 0,
                new int[][]{
                        {R.raw.f30_enga_1290, 1290},
                        {R.raw.f30_enga_1740, 1740},
                        {R.raw.f30_enga_1980, 1980},
                        {R.raw.f30_enga_2280, 2280},
                        {R.raw.f30_enga_2556, 2556},
                        {R.raw.f30_enga_2661, 2661},
                        {R.raw.f30_enga_3321, 3321},
                        {R.raw.f30_enga_3951, 3951},
                        {R.raw.f30_enga_5031, 5031},
                        {R.raw.f30_enga_6141, 6141},
                        {R.raw.f30_enga_7200, 7200}
                },
                null
        );
    }

    private EngineSoundManager(Context context) {
        this.mContext = context.getApplicationContext();
        this.mHandler = new Handler(Looper.getMainLooper());
    }

    public static synchronized EngineSoundManager getInstance(Context context) {
        if (sInstance == null) sInstance = new EngineSoundManager(context);
        return sInstance;
    }

    public void setVehicleProfile(VehicleProfile profile) {
        boolean wasPlaying = mIsPlaying;
        if (mIsPlaying) stop();

        this.mActiveProfile = profile;
        this.mIdleRpm = profile.idleRpm;
        // MARŞ SÜRESİNİ OTOMATİK HESAPLA
        if (profile.startSoundResId != 0) {
            mAutoStartupDelayMs = getSoundDurationMs(profile.startSoundResId);
            if (MG4Hardware.isLogEnabled()) Log.i(TAG, "Marş süresi otomatik hesaplandı: " + mAutoStartupDelayMs + "ms");
        } else {
            mAutoStartupDelayMs = 0;
        }
        this.mMaxRpm = profile.maxRpm;
        this.mCurrentIdleVolumeScale = profile.idleVolumeScale * mUserIdleVolumeScale;
        mCurrentSamplesOn = buildSamples(profile.onSounds);
        mCurrentSamples = mCurrentSamplesOn; // Eski mixerin çalışması için referans (Single Layer için)

        if (profile.offSounds != null) {
            mIsDualLayer = true;
            mCurrentSamplesOff = buildSamples(profile.offSounds);
        } else {
            mIsDualLayer = false;
            mCurrentSamplesOff = null;
        }

        if (MG4Hardware.isLogEnabled()) Log.i(TAG, "Profil yüklendi: " + profile.name);
        mCurrentGear = 0;
        if (wasPlaying) start();
    }

    private EngineSample[] buildSamples(int[][] soundMap) {
        List<EngineSample> samples = new ArrayList<>();
        if (soundMap != null) {
            for (int[] map : soundMap) {
                // Eski format: {resId, baseRpm}
                // Yeni FMOD-benzeri format: {resId, baseRpm, minRpm, maxRpm}
                if (map.length >= 4) {
                    samples.add(new EngineSample(map[1], map[0], map[2], map[3]));
                } else {
                    samples.add(new EngineSample(map[1], map[0]));
                }
            }
            samples.sort((s1, s2) -> Integer.compare(s1.baseRpm, s2.baseRpm));
        }
        return samples.toArray(new EngineSample[0]);
    }

    public void start() {
        if (mIsPlaying || mCurrentSamples == null) return;
        mIsPlaying = true;
        mLoadedSamplesCount = 0;

        int baseStreams = mIsDualLayer ? (mCurrentSamplesOn.length + mCurrentSamplesOff.length) : mCurrentSamples.length;
        int extras = 3; // Turbo ve Whine Subwave
        extras += 3;    //Pop sounds
        if (mActiveProfile.hasTurbo >= 2) extras++;
        if (mActiveProfile.startSoundResId != 0) extras++;
        if (mActiveProfile.stopSoundResId != 0) extras++;

        final int finalExtras = extras;

        int maxStreams = baseStreams + finalExtras + 5;

        boolean useMedia = false;
        try {
            SharedPreferences prefs = mContext.getSharedPreferences("drivehub_dort", Context.MODE_PRIVATE);
            useMedia = "media".equals(prefs.getString("sound_source", "notification"));
        } catch (Throwable ignored) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(useMedia ? AudioAttributes.USAGE_MEDIA : AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            mSoundPool = new SoundPool.Builder().setMaxStreams(maxStreams).setAudioAttributes(attrs).build();
        } else {
            int stream = useMedia ? AudioManager.STREAM_MUSIC : AudioManager.STREAM_NOTIFICATION;
            mSoundPool = new SoundPool(maxStreams, stream, 0);
        }

        mSoundPool.setOnLoadCompleteListener((soundPool, sampleId, status) -> {
            if (status != 0) return;
            int totalExpected = baseStreams + finalExtras;
            mLoadedSamplesCount++;

            if (mLoadedSamplesCount >= totalExpected && mIsPlaying) {

                if (mIsDualLayer) {
                    for (EngineSample sample : mCurrentSamplesOn) sample.streamId = mSoundPool.play(sample.soundId, 0f, 0f, 1, -1, 1.0f);
                    for (EngineSample sample : mCurrentSamplesOff) sample.streamId = mSoundPool.play(sample.soundId, 0f, 0f, 1, -1, 1.0f);
                } else {
                    for (EngineSample sample : mCurrentSamples) sample.streamId = mSoundPool.play(sample.soundId, 0f, 0f, 1, -1, 1.0f);
                }

                mTurboStreamId = mSoundPool.play(mTurboSoundId, 0f, 0f, 1, -1, 1.0f);
                mGearWhineStreamId = mSoundPool.play(mGearWhineSoundId, 0f, 0f, 1, -1, 1.0f);
                mSubwaveStreamId = mSoundPool.play(mSubwaveSoundId, 0f, 0f, 1, -1, 1.0f);

                // Marş: sadece vites 1 veya sim aktifken ve araç READY/sim ile (boot’ta READY olmadan çalmasın)
                boolean mayPlayStart = mStartSoundId != -1
                        && (MG4Hardware.getLastGear() == 1 || MG4Hardware.isSimSpeedActive())
                        && (MG4Hardware.isVehicleReady() || MG4Hardware.isSimSpeedActive())
                        && mCurrentSpeedKmh == 0f;
                if (mayPlayStart) {
                    mSoundPool.play(mStartSoundId, mMasterVolume, mMasterVolume, 2, 0, 1.0f);
                    mEngineStartTime = System.currentTimeMillis(); // Marş süresince rölantiyi beklet (Fade-in)
                } else {
                    mEngineStartTime = 0;
                }

                onSpeedChanged(mCurrentSpeedKmh);
            }
        });

        // Yardımcı sesleri yükle
        mTurboSoundId = mSoundPool.load(mContext, (mActiveProfile.hasTurbo == 1) ? R.raw.elisesc_compressor : R.raw.turbo, 1);
        mGearWhineSoundId = mSoundPool.load(mContext, R.raw.transmission, 1);
        mSubwaveSoundId = mSoundPool.load(mContext, R.raw.dp_in_subwave, 1);
        if (mActiveProfile.hasTurbo == 3) mFlutterSoundId = mSoundPool.load(mContext, R.raw.blowoff2, 1);
        else if (mActiveProfile.hasTurbo == 2) mFlutterSoundId = mSoundPool.load(mContext, R.raw.blowoff_mclaren, 1);
        else mFlutterSoundId = -1;
        if (mActiveProfile.startSoundResId != 0) mStartSoundId = mSoundPool.load(mContext, mActiveProfile.startSoundResId, 1); else mStartSoundId = -1;
        if (mActiveProfile.stopSoundResId != 0) mStopSoundId = mSoundPool.load(mContext, mActiveProfile.stopSoundResId, 1); else mStopSoundId = -1;

        mGlobalPopIds[0] = mSoundPool.load(mContext, R.raw.backfire_5, 1);  // Hafif çatırtı
        mGlobalPopIds[1] = mSoundPool.load(mContext, R.raw.backfire_9, 1);  // Orta patlama
        mGlobalPopIds[2] = mSoundPool.load(mContext, R.raw.backfire_11, 1); // Sert gümleme

        if (mIsDualLayer) {
            for (EngineSample sample : mCurrentSamplesOn) sample.soundId = mSoundPool.load(mContext, sample.resourceId, 1);
            for (EngineSample sample : mCurrentSamplesOff) sample.soundId = mSoundPool.load(mContext, sample.resourceId, 1);
        } else {
            for (EngineSample sample : mCurrentSamples) sample.soundId = mSoundPool.load(mContext, sample.resourceId, 1);
        }
    }

    public void stop() {
        boolean wasPlaying = mIsPlaying;
        mIsPlaying = false;

        if (mSoundPool != null) {
            // 1. Mevcut tüm motor ve yardımcı döngü seslerini anında kapat
            if (mCurrentSamples != null) for (EngineSample s : mCurrentSamples) if(s.streamId != -1) mSoundPool.stop(s.streamId);
            if (mCurrentSamplesOn != null) for (EngineSample s : mCurrentSamplesOn) if(s.streamId != -1) mSoundPool.stop(s.streamId);
            if (mCurrentSamplesOff != null) for (EngineSample s : mCurrentSamplesOff) if(s.streamId != -1) mSoundPool.stop(s.streamId);
            if (mTurboStreamId != -1) mSoundPool.stop(mTurboStreamId);
            if (mGearWhineStreamId != -1) mSoundPool.stop(mGearWhineStreamId);
            if (mSubwaveStreamId != -1) mSoundPool.stop(mSubwaveStreamId);

            if (wasPlaying && mStopSoundId != -1 && mCurrentSpeedKmh == 0f) {
                mSoundPool.play(mStopSoundId, mMasterVolume, mMasterVolume, 2, 0, 1.0f);
            }

            // 3. İstop sesinin yarım kalmaması için SoundPool'u GECİKMELİ Kapat
            final SoundPool poolToRelease = mSoundPool;
            mSoundPool = null; // Bağlantıyı hemen kopar, böylece mixer bir daha işleyemez.

            mHandler.postDelayed(() -> {
                if (poolToRelease != null) {
                    poolToRelease.release();
                }
            }, 2500); // İstop sesinin uzunluğuna göre bu süreyi (2.5 saniye) artırabilir/azaltabilirsin.
        }

        mTurboStreamId = -1; mFlutterSoundId = -1; mStartSoundId = -1; mStopSoundId = -1;
        mSubwaveStreamId = -1; mSubwaveSoundId = -1;
    }

    public void setSubwaveEnabled(boolean enabled) {
        mSubwaveEnabled = enabled;
    }

    public boolean isSubwaveEnabled() {
        return mSubwaveEnabled;
    }

    public void onSpeedChanged(float speedKmh) {
        if (mUseManualThrottle) {
            onSpeedChanged(speedKmh, Float.NaN);
            return;
        }
        float dcVolt = MG4Hardware.getDcVoltGlobal();
        float dcAmpAct = MG4Hardware.getDcAmpGlobal();
        float kw = (Float.isNaN(dcVolt) || Float.isNaN(dcAmpAct)) ? 0f : (dcVolt * dcAmpAct) / 1000f;
        onSpeedChanged(speedKmh, kw);
    }

    public void onSpeedChanged(float speedKmh, float dcPowerKw) {
        if (!mIsPlaying || mCurrentSamples == null || mLoadedSamplesCount < (mIsDualLayer ? mCurrentSamplesOn.length : mCurrentSamples.length)) return;

        mCurrentSpeedKmh = (Float.isNaN(speedKmh) || speedKmh < 0f) ? 0f : speedKmh;
        if (!mUseManualThrottle) {
            if (!Float.isNaN(dcPowerKw)) {
                mCurrentDcPowerKw = dcPowerKw;
            } else {
                float dcVolt = MG4Hardware.getDcVoltGlobal();
                float dcAmpAct = MG4Hardware.getDcAmpGlobal();
                mCurrentDcPowerKw = (Float.isNaN(dcVolt) || Float.isNaN(dcAmpAct)) ? 0f : (dcVolt * dcAmpAct) / 1000f;
            }
            mSimulatedThrottle = Math.min(1f, Math.max(0f, (mCurrentDcPowerKw < 5f ? 0f : mCurrentDcPowerKw / mMotorMaxPower)));
        }
        updateGearAndRpm();
        updateAudioMixer();
    }
    // 1. KUSURSUZ MATEMATİK (Sadece senin Min ve Max değerlerinle oran kurar)
    private float calculateMechanicalRpm(int gear, float speed) {
        if (gear < 1 || gear > mActiveProfile.gearRanges.length) return mIdleRpm;

        float minSpeed = mActiveProfile.gearRanges[gear - 1][0];
        float maxSpeed = mActiveProfile.gearRanges[gear - 1][1];

        if (speed <= minSpeed) return mIdleRpm; // Hız alt limitin altındaysa rölanti
        if (speed >= maxSpeed) return mMaxRpm;  // Hız üst limitin üstündeyse kesici

        // Hızın o vites aralığındaki yüzdesini bulur
        float ratio = (speed - minSpeed) / (maxSpeed - minSpeed);
        return mIdleRpm + (ratio * (mMaxRpm - mIdleRpm));
    }

    // 2. DÜMDÜZ, BASİT VE HATASIZ ŞANZIMAN
    private void updateGearAndRpm() {
        if (mActiveProfile == null) return;
        float speed = mCurrentSpeedKmh;
        float throttle = mSimulatedThrottle;
        long currentTime = System.currentTimeMillis();

        // 1. BOŞTA GAZ VERME
        if (speed < 1.0f) {
            mCurrentGear = 0;
            float targetRpm = mIdleRpm + (throttle * (mMaxRpm - mIdleRpm));
            mCurrentRpm = (mCurrentRpm * 0.8f) + (targetRpm * 0.2f);
            return;
        }

        if (mCurrentGear == 0) mCurrentGear = 1;

        // --- KANUN 1: SERT HIZ KORUMASI (2km/h TOLERANSLI) ---
        while (mCurrentGear > 1) {
            float currentMinSpeed = mActiveProfile.gearRanges[mCurrentGear - 1][0];
            if (speed < (currentMinSpeed - 2.0f)) {
                mCurrentGear--;
                mLastShiftTime = currentTime;
            } else {
                break;
            }
        }

        // --- KANUN 2: VİTES KARAR MEKANİZMASI ---
        if (currentTime - mLastShiftTime > mActiveProfile.shiftDurationMs) {
            float rpmRange = mMaxRpm - mIdleRpm;
            float currentGearRpm = calculateMechanicalRpm(mCurrentGear, speed);

            float upshiftThreshold;
            float downshiftThreshold;

            if (throttle <= 0.05f) {
                // AYAK GAZDAN ÇEKİLDİĞİNDE (0 Pedal)
                if (mDriveModeAggressiveness > 0.5f) {
                    // SPORT MOD: Vites yükseltmeyi engelle (Eşiği %85'e çek)
                    // ve motor freni için daha yüksek devirde vites düşür (%40)
                    upshiftThreshold = mIdleRpm + (rpmRange * 0.85f);
                    downshiftThreshold = mIdleRpm + (rpmRange * 0.40f);
                } else {
                    // ECO/NORMAL: Klasik sakin vites büyütme
                    upshiftThreshold = mIdleRpm + (rpmRange * 0.30f);
                    downshiftThreshold = mIdleRpm + (rpmRange * 0.1f);
                }
            }else {
                // PEDALA BASILDIĞINDA
                if (mDriveModeAggressiveness > 0.5f) {
                    // SPORT MOD: Pedala az basılsa bile devri öldürme (Baseline %80)
                    // %80 (az pedal) ile %98 (tam pedal) arasında vites büyütür.
                    upshiftThreshold = mIdleRpm + rpmRange * (0.80f + (throttle * 0.18f));
                    downshiftThreshold = mIdleRpm + rpmRange * 0.45f; // Sport'ta küçültme hep agresif
                } else {
                    // NORMAL/ECO MOD
                    upshiftThreshold = mIdleRpm + rpmRange * (0.60f + (throttle * 0.35f));
                    downshiftThreshold = mIdleRpm + rpmRange * 0.25f;
                    if (throttle > 0.8f) downshiftThreshold = mIdleRpm + rpmRange * 0.45f;
                }
            }

            // VİTES BÜYÜTME (İleriyi Görme + 600 RPM Tamponu)
            if (currentGearRpm > upshiftThreshold && mCurrentGear < mActiveProfile.gearRanges.length) {
                float nextMinSpeed = mActiveProfile.gearRanges[mCurrentGear][0];
                float nextGearRpm = calculateMechanicalRpm(mCurrentGear + 1, speed);

                if (speed >= nextMinSpeed && nextGearRpm > (downshiftThreshold + 600f)) {
                    mCurrentGear++;
                    mLastShiftTime = currentTime;
                }
            }
            // VİTES KÜÇÜLTME
            else if (currentGearRpm < downshiftThreshold && mCurrentGear > 1) {
                mCurrentGear--;
                mLastShiftTime = currentTime;
            }
        }

        // --- KANUN 3: DEVRİ HESAPLA VE WOBBLE UYGULA ---
        if (mCurrentGear > 0) {
            float targetRpm = calculateMechanicalRpm(mCurrentGear, speed);
            long timeSinceShift = currentTime - mLastShiftTime;

            if (timeSinceShift < mActiveProfile.shiftDurationMs && mActiveProfile.wobbleMagnitude > 0) {
                float timeSec = timeSinceShift / 1000f;
                float durSec = mActiveProfile.shiftDurationMs / 1000f;
                float dampening = Math.max(0f, 1.0f - (timeSec / durSec));
                targetRpm += (float) Math.sin(timeSec * 15.0f * Math.PI * 2) * mActiveProfile.wobbleMagnitude * dampening;
            }

            float smooth = (throttle > 0.05f) ? mActiveProfile.rpmOnSmooth : mActiveProfile.rpmOffSmooth;
            if (timeSinceShift < mActiveProfile.shiftDurationMs) smooth = 0.3f;

            mCurrentRpm = (mCurrentRpm * (1.0f - smooth)) + (targetRpm * smooth);
        }

        mCurrentRpm = Math.max(mIdleRpm, Math.min(mCurrentRpm, mMaxRpm));
    }
    private void updateAudioMixer() {
        if (mSoundPool == null || (!mIsDualLayer && mCurrentSamples == null) || (mIsDualLayer && mCurrentSamplesOn == null)) return;

        long currentTime = System.currentTimeMillis();
        if (currentTime - mLastMixerUpdateTime < MIXER_UPDATE_INTERVAL_MS) return;
        mLastMixerUpdateTime = currentTime;

        // --- MARŞ SÜRESİ FADE-IN KONTROLÜ ---
        float startupFade = 1.0f;
        if (mActiveProfile != null && mActiveProfile.startSoundResId != 0 && mAutoStartupDelayMs > 0 && mEngineStartTime > 0) {
            long timeSinceStart = currentTime - mEngineStartTime;

            // Marş sesi bitmeden kaç milisaniye önce rölanti sızmaya başlasın?
            long overlapMs = 500;
            // Rölantinin 0'dan %100 sese ulaşma süresi (Yumuşaklık ayarı)
            long fadeDurationMs = 800;

            long fadeStartTime = mAutoStartupDelayMs - overlapMs;
            if (fadeStartTime < 0) fadeStartTime = 0; // Dosya çok kısaysa hata vermemesi için güvenlik

            if (timeSinceStart < fadeStartTime) {
                startupFade = 0f; // Marşın ilk kısımları, rölanti hala tam sessiz
            } else if (timeSinceStart < fadeStartTime + fadeDurationMs) {
                // Marşın son kısmı sönümlenirken rölanti sesi alttan yavaşça yükselir (Mükemmel Karışım)
                startupFade = (timeSinceStart - fadeStartTime) / (float)fadeDurationMs;
            }
        }

        float rpm = Math.max(mIdleRpm, Math.min(mCurrentRpm, mMaxRpm));

        // FADE-IN ÇARPANI BURADA UYGULANIYOR!
        float masterVol = Math.max(0f, Math.min(1.0f, mMasterVolume)) * startupFade;

        float rpmRatio = (rpm - mIdleRpm) / (mMaxRpm - mIdleRpm);
        rpmRatio = Math.max(0f, Math.min(1f, rpmRatio));

        if (mFlutterSoundId != -1 && mActiveProfile.hasTurbo >= 2) {
            if (rpm > 3500f && mLastThrottleForFlutter > 0.6f && mSimulatedThrottle < 0.1f
                    && (currentTime - mLastFlutterTime > 800)) {

                float flutterVol = (rpm / mMaxRpm) * masterVol * mFlutterSoundmultiplier;
                flutterVol = Math.max(0f, Math.min(1f, flutterVol));

                float flutterPitch = 0.85f + (float)(Math.random() * 0.2f);
                mSoundPool.play(mFlutterSoundId, flutterVol, flutterVol, 1, 0, flutterPitch);

                mLastFlutterTime = currentTime;
            }
            mLastThrottleForFlutter = mSimulatedThrottle;
        }

        // --- TURBO SESİ ---
        if (mTurboStreamId != -1) {
            if (mActiveProfile == null || (mActiveProfile.hasTurbo == 0)) {
                mSoundPool.setVolume(mTurboStreamId, 0f, 0f);
            } else if(mActiveProfile.hasTurbo == 1) {
                float compVol = (0.2f + (mSimulatedThrottle * 0.8f)) * rpmRatio * masterVol * mCompressorMaxVol;
                mSoundPool.setVolume(mTurboStreamId, compVol, compVol);
                mSoundPool.setRate(mTurboStreamId, 0.8f + (rpmRatio * 1.7f));
            } else {
                float boostFactor = Math.max(0f, Math.min(1f, (rpmRatio > 0.10f) ? (rpmRatio - 0.10f) * 1.12f : 0f));
                mCurrentTurboBoost = (mCurrentTurboBoost * 0.90f) + ((mSimulatedThrottle * boostFactor) * 0.10f);
                mSoundPool.setVolume(mTurboStreamId, mCurrentTurboBoost * masterVol * mTurboMaxSound, mCurrentTurboBoost * masterVol * mTurboMaxSound);
                mSoundPool.setRate(mTurboStreamId, 0.8f + (rpmRatio * 1.2f));
            }
        }
        // --- SUBWAVE (ALT BAS / GÖĞÜS TİTRETEN TOKLUK) KONTROLÜ ---
        if (mSubwaveStreamId != -1) {
            if (mSubwaveEnabled && mCurrentGear > 0) {

                // 1. YÜK EĞRİSİ: Lineer değil, karesel (Exponential) artış.
                // Gaza az basarken fısıldar, %70'den sonra "göğsü titretir".
                float subLoadFactor = (float) Math.pow(mSimulatedThrottle, 1.5f);

                // 2. RPM PENCERESİ: Bas her devirde aynı olmaz.
                // Alt-orta devirlerde (torkun geldiği yer) pik yapar.
                float rpmBellCurve = 1.0f - (Math.abs(rpmRatio - 0.35f) * 0.7f);
                rpmBellCurve = Math.max(0.4f, rpmBellCurve); // Hiçbir zaman tamamen kaybolmasın

                float subVolume = masterVol * subLoadFactor * 1.8f * rpmBellCurve;

                // 3. PİTCH (RESONANCE): Devir arttıkça çok tizleşmemeli.
                // Alt frekansı korumak için rpmRatio etkisini %40'a indirdik.
                float subPitch = 0.55f + (rpmRatio * 0.45f);

                // Boşta gaz verme (N-Rev) durumu için özel ayar
                if (mCurrentSpeedKmh < 1.0f && mSimulatedThrottle > 0.05f) {
                    subVolume = masterVol * 0.8f;
                    subPitch = 0.5f + (rpmRatio * 0.3f);
                }

                mSoundPool.setVolume(mSubwaveStreamId, Math.min(1.0f, subVolume), Math.min(1.0f, subVolume));
                mSoundPool.setRate(mSubwaveStreamId, subPitch);
            } else {
                mSoundPool.setVolume(mSubwaveStreamId, 0f, 0f);
            }
        }
        // updateAudioMixer içinde, Pop & Bang kısmını bu "akıllı" versiyonla değiştirebilirsin:
        if (mExhaustPopEnabled && mDriveModeAggressiveness > 0.5f) {

            // --- DOKUNUŞ 1: VİTES ATMA PATLAMASI (Upshift Crack) ---
            // Vites büyüdüğü an (ilk 50ms içinde) ve devir yüksekse (4000+)
            if (currentTime - mLastShiftTime < 50 && mCurrentGear > 1 && rpm > 4000f) {
                // En sert sesi (backfire_11) seç ve vites atma hatırına sesi %30 artır
                mSoundPool.play(mGlobalPopIds[2], masterVol * 1.3f, masterVol * 1.3f, 5, 0, 0.95f);
                mLastShiftTime -= 50; // Tekrar tetiklenmesin diye zamanı kaydır
            }

            // --- DOKUNUŞ 2: GAZ BIRAKMA (Lift-off Burble) ---
            if (mLastThrottleForPop > 0.6f && mSimulatedThrottle < 0.1f && rpm > 3500f) {
                mPopsRemaining = 3 + (int)(Math.random() * 5); // 3-8 arası patlama
                mNextPopTime = currentTime;
            }
            mLastThrottleForPop = mSimulatedThrottle;

            if (mPopsRemaining > 0 && currentTime >= mNextPopTime && mSimulatedThrottle < 0.2f) {

                // --- DOKUNUŞ 3: RPM'E GÖRE SES SEÇİMİ ---
                int popId;
                if (rpm > 6500f) popId = mGlobalPopIds[2];      // Sert (Backfire_11)
                else if (rpm > 4500f) popId = mGlobalPopIds[1]; // Orta (Backfire_9)
                else popId = mGlobalPopIds[0];                  // Hafif (Backfire_5)

                float popVol = (0.3f + (float)Math.random() * 0.4f) * masterVol;
                float popPitch = 0.85f + (float)Math.random() * 0.3f;

                mSoundPool.play(popId, popVol, popVol, 2, 0, popPitch);

                mPopsRemaining--;

                // DİNAMİK GECİKME: Patlama azaldıkça aradaki süre uzasın (Sönümlenme hissi)
                // İlk patlamalar 80ms, sonrakiler 250ms'ye kadar uzar.
                int delayBase = 80 + (int)(Math.random() * 70);
                int coolingFactor = (8 - mPopsRemaining) * 25; // Her patlamada +25ms ekle
                mNextPopTime = currentTime + delayBase + coolingFactor;
            }
        }

        // --- MİKSER: HİBRİT KARAR MEKANİZMASI ---
        if (mIsDualLayer) {
            float onWeight = (float) Math.sqrt(mSimulatedThrottle);
            float offWeight = (float) Math.sqrt(Math.max(0f, 1.0f - mSimulatedThrottle));

            // Araç dururken ve gaz verilirken sadece ON katmanı duyulsun (N-revving)
            if (mCurrentSpeedKmh < 1.0f && mSimulatedThrottle > 0.05f) {
                onWeight = 1.0f;
                offWeight = 0.0f;
            }

            // DÜZELTME: Kısılmış masterVol değerini de fonksiyona yolluyoruz
            processLayer(mCurrentSamplesOn, rpm, onWeight * masterVol, masterVol, true);
            processLayer(mCurrentSamplesOff, rpm, offWeight * masterVol, masterVol, false);

        } else {
            // SİNGLE LAYER MİKSERİ (Dual Layer olmayan profiller için)
            if (mCurrentSamples == null || mCurrentSamples.length == 0) return;

            float loadVolumeFactor = 0.5f + (mSimulatedThrottle * 0.5f);
            float loadPitchFactor = 0.98f + (mSimulatedThrottle * 0.04f);
            float modeVolumeBoost = (mDriveModeAggressiveness > 0.5f) ? 1.2f : 1.0f;

            for (int i = 0; i < mCurrentSamples.length; i++) {
                EngineSample s = mCurrentSamples[i];
                if (s.streamId == -1) continue;

                float rawWeight = 0f;

                // FMOD Üçgen Pencere Mantığı
                if (s.hasWindow()) {
                    if (rpm > s.minRpm && rpm < s.maxRpm) {
                        if (rpm <= s.baseRpm) {
                            rawWeight = (rpm - s.minRpm) / (float) (s.baseRpm - s.minRpm);
                        } else {
                            rawWeight = (s.maxRpm - rpm) / (float) (s.maxRpm - s.baseRpm);
                        }
                    }
                } else {
                    // ESKİ KOMŞU TABANLI SİSTEM (Eğer min/max belirtilmemişse geriye dönük uyumluluk)
                    EngineSample lower = mCurrentSamples[0];
                    EngineSample upper = mCurrentSamples[mCurrentSamples.length - 1];
                    if (rpm >= upper.baseRpm) {
                        lower = upper;
                    } else {
                        for (int j = 0; j < mCurrentSamples.length - 1; j++) {
                            if (rpm >= mCurrentSamples[j].baseRpm && rpm <= mCurrentSamples[j+1].baseRpm) {
                                lower = mCurrentSamples[j];
                                upper = mCurrentSamples[j+1];
                                break;
                            }
                        }
                    }
                    float rpmDiff = upper.baseRpm - lower.baseRpm;
                    float t = (rpmDiff <= 0) ? 0f : (rpm - lower.baseRpm) / rpmDiff;
                    t = Math.max(0f, Math.min(1f, t));
                    float blend = t * t * (3f - 2f * t); // smoothstep
                    rawWeight = (s == lower) ? (1f - blend) : ((s == upper) ? blend : 0f);
                }

                // Normalizasyon İPTAL! Doğrudan weight kullanıyoruz.
                float shapedVol = Math.max(0f, Math.min(1f, rawWeight));
                if (i == 0) shapedVol *= mCurrentIdleVolumeScale;

                float finalVolume = shapedVol * masterVol * loadVolumeFactor * modeVolumeBoost;
                finalVolume = Math.max(0.0f, Math.min(1.0f, finalVolume));

                float pitch = Math.max(0.5f, Math.min(2.0f, (rpm / s.baseRpm) * loadPitchFactor));

                if (i == 0 && mCurrentSpeedKmh < 1.0f && mSimulatedThrottle < 0.05f) {
                    pitch *= mIdlePitch;
                }

                mSoundPool.setVolume(s.streamId, finalVolume, finalVolume);
                mSoundPool.setRate(s.streamId, Float.isNaN(pitch) ? 1.0f : pitch);
            }
        }

        if (mGearWhineStreamId != -1 && mGearWhineEnabled) {
            float speedRatio = Math.max(0f, Math.min(1f, mCurrentSpeedKmh / mWhineMaxSpeed));
            mSoundPool.setVolume(mGearWhineStreamId, speedRatio * masterVol * mGearWhineMaxVol, speedRatio * masterVol * mGearWhineMaxVol);
            mSoundPool.setRate(mGearWhineStreamId, 0.5f + (speedRatio * 1.5f));
        } else if(!mGearWhineEnabled){
            mSoundPool.setVolume(mGearWhineStreamId, 0, 0);
        }
    }

    // Kadran uygulaması (com.drivehub.kadran): ContentProvider üzerinden okur. Servisteki 100ms task'tan, sadece ekran açıkken çağrılır.
    public static void broadcastTelemetryIfNeeded(Context context) {
        if (context == null) return;
        getInstance(context).broadcastTelemetryIfNeeded();
    }
    public void broadcastTelemetryIfNeeded() {

        try {
            float lMotorMaxPower = mMotorMaxPower + 20f;
            com.drivehub.dort.telemetry.TelemetryHolder.update(
                    mCurrentRpm,
                    mCurrentSpeedKmh,
                    mCurrentGear,
                    mSimulatedThrottle,
                    mCurrentDcPowerKw,
                    mMaxRpm,
                    lMotorMaxPower
            );
            mContext.getContentResolver().notifyChange(com.drivehub.dort.telemetry.TelemetryProvider.CONTENT_URI, null);
        } catch (Throwable ignored) {
        }
    }

    /** Ses kapalıyken Kadran için sadece hız ve güç gönderilir; RPM = 0. */
    public static void broadcastTelemetryFromRaw(Context context, float speedKmh, float dcPowerKw) {
        if (context == null) return;
        getInstance(context).broadcastTelemetryFromRaw(speedKmh, dcPowerKw);
    }
    public void broadcastTelemetryFromRaw(float speedKmh, float dcPowerKw) {
        try {
            float lMotorMaxPower = mMotorMaxPower + 20f;
            com.drivehub.dort.telemetry.TelemetryHolder.update(
                    -1f, speedKmh, 0, 0f, dcPowerKw, mMaxRpm, lMotorMaxPower
            );
            mContext.getContentResolver().notifyChange(com.drivehub.dort.telemetry.TelemetryProvider.CONTENT_URI, null);
        } catch (Throwable ignored) {
        }
    }
    private void processLayer(EngineSample[] layer, float rpm, float weightVol, float fadedMasterVol, boolean isOnLayer) {
        if (layer == null || layer.length == 0) return;

        for (int i = 0; i < layer.length; i++) {
            EngineSample s = layer[i];
            if (s.streamId == -1) continue;

            float rawWeight = 0f;

            if (s.hasWindow()) {
                // Sesin kendi tanımlı penceresindeysek
                if (rpm >= s.minRpm && rpm <= s.maxRpm) {
                    rawWeight = 1.0f; // Varsayılan olarak tam güç

                    // 1. FADE IN: Önceki sesin bitişiyle (maxRpm) senin başlangıcın (minRpm) arası
                    if (i > 0) {
                        EngineSample prev = layer[i - 1];
                        if (rpm < prev.maxRpm) {
                            // Önceki ses hala çalıyor, o bitene kadar biz sesimizi açıyoruz
                            rawWeight = (rpm - s.minRpm) / (float)(prev.maxRpm - s.minRpm);
                        }
                    }

                    // 2. FADE OUT: Sonraki sesin başlangıcı (minRpm) ile senin bitişin (maxRpm) arası
                    if (i < layer.length - 1) {
                        EngineSample next = layer[i + 1];
                        if (rpm > next.minRpm) {
                            // Sonraki ses başladı, o tam güce çıkana kadar biz sesimizi kısıyoruz
                            rawWeight = (s.maxRpm - rpm) / (float)(s.maxRpm - next.minRpm);
                        }
                    }
                }
            } else {
                // Eski sistem (Geriye dönük uyumluluk için)
                EngineSample lower = layer[0];
                EngineSample upper = layer.length > 1 ? layer[1] : layer[0];
                if (rpm >= layer[layer.length - 1].baseRpm) {
                    lower = layer[layer.length - 1];
                    upper = lower;
                } else {
                    for (int j = 0; j < layer.length - 1; j++) {
                        if (rpm >= layer[j].baseRpm && rpm <= layer[j+1].baseRpm) {
                            lower = layer[j];
                            upper = layer[j+1];
                            break;
                        }
                    }
                }
                float rpmDiff = upper.baseRpm - lower.baseRpm;
                float t = (rpmDiff <= 0) ? 0f : (rpm - lower.baseRpm) / rpmDiff;
                t = Math.max(0f, Math.min(1f, t));
                float blend = t * t * (3f - 2f * t); // smoothstep
                rawWeight = (s == lower) ? (1f - blend) : ((s == upper) ? blend : 0f);
            }

            // FMOD "Constant Power" (Sabit Güç) Geçişi:
            // Seslerin kesiştiği yerde toplam gücün düşmemesi için karekök alıyoruz.
            float shapedVol = (float) Math.sqrt(Math.max(0f, Math.min(1f, rawWeight)));

            if (i == 0 && rpm < s.baseRpm) shapedVol *= mCurrentIdleVolumeScale;

            float finalVolume = shapedVol * weightVol;
            float pitch = rpm / s.baseRpm;

            if (isOnLayer) pitch *= (0.98f + (mSimulatedThrottle * 0.04f));
            pitch = Math.max(0.6f, Math.min(1.8f, pitch));

            if (i == 0 && mCurrentSpeedKmh < 1.0f && mSimulatedThrottle < 0.05f) {
                pitch *= mIdlePitch;
            }

            mSoundPool.setVolume(s.streamId, finalVolume, finalVolume);
            mSoundPool.setRate(s.streamId, pitch);
        }
    }
    public boolean isPlaying() { return mIsPlaying; }
    // --- OTOMATİK MARŞ SÜRESİ HESAPLAYICI ---
    private long getSoundDurationMs(int rawResId) {
        if (rawResId == 0 || rawResId == -1) return 0;
        try {
            android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
            android.content.res.AssetFileDescriptor afd = mContext.getResources().openRawResourceFd(rawResId);
            mmr.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            String durationStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            mmr.release();
            afd.close();
            // Dosyanın tam süresini alıyoruz. (İstersen sonundan 200ms kırparak rölantinin daha erken girmesini sağlayabilirsin)
            return Long.parseLong(durationStr);
        } catch (Exception e) {
            Log.e(TAG, "Süre okunamadı, varsayılan 2500ms kullanılıyor.", e);
            return 2500;
        }
    }
}