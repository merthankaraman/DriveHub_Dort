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

    // HARİCİ KADRAN: ContentProvider ile telemetri (broadcast sistem UID'de "non-protected" uyarısı veriyor)
    public static final String ACTION_TELEMETRY = "com.drivehub.dort.TELEMETRY"; // kadran uyumluluk; artık Provider kullan
    public static final String EXTRA_RPM = "rpm";
    public static final String EXTRA_SPEED_KMH = "speedKmh";
    public static final String EXTRA_GEAR = "gear";
    public static final String EXTRA_THROTTLE = "throttle01";
    public static final String EXTRA_DC_POWER_KW = "dcPowerKw";
    // Maksimum değerler (profil + kullanıcı ayarlarından etkilenenler)
    public static final String EXTRA_RPM_MAX = "rpmMax";
    public static final String EXTRA_MOTOR_MAX_POWER_KW = "motorMaxPowerKw";
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
    private float mRevMatchBoost = 0f;
    private boolean mGearWhineEnabled = true;
    // SUBWAVE (DERİN BAS) DEĞİŞKENLERİ
    private int mSubwaveSoundId = -1;
    private int mSubwaveStreamId = -1;
    private boolean mSubwaveEnabled = true;
    private int mFlutterSoundId = -1;
    private float mFlutterSoundmultiplier = 0.4f;
    private long mLastFlutterTime = 0;
    private float mLastThrottleForFlutter = 0f;

    // Ses karışım modu (daha agresif ON/OFF ve load tepkisi)
    private boolean mRealisticMixEnabled = false;

    // START/STOP MARŞ DEĞİŞKENLERİ
    private int mStartSoundId = -1;
    private int mStopSoundId = -1;
    private long mEngineStartTime = 0; // Marşın basıldığı anı tutar
    private long mAutoStartupDelayMs = 0; // Dosyadan otomatik okunan marş süresi

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
        applySoundCharacterFromString(prefs.getString("sound_character", "NORMAL"));
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
    public float getUserIdleVolumeScale() { return mUserIdleVolumeScale; }
    public void setIdlePitch(float pitch) { mIdlePitch = Math.max(0.5f, Math.min(2f, pitch)); }
    public float getIdlePitch() { return mIdlePitch; }

    public void applySoundCharacterFromString(String character) {
        float agg = 0.4f;
        if ("ECO".equals(character)) agg = 0.25f;
        else if ("SPORT".equals(character)) agg = 0.7f;
        setDriveModeAggressiveness(agg);
    }

    public static final String[] PROFILE_LABELS = {
            "Lotus Exige 240",
            "Lexus LFA",
            "GTR R34",
            "Modern F1 V10",
            "Ferrari F2004",
            "McLaren P1",
            "Audi RS6 AVANT",
            "Audi RS6 AVANT MONO",
            "BMW M3 E92 V8",
            "BMW M3 E92 V8 2",
            "BMW M3 E92 V8 MONO"
    };

    public static String[] getProfileLabels() { return PROFILE_LABELS; }

    public void applyProfileLabel(String profile) {
        mCurrentProfileLabel = (profile != null && !profile.isEmpty()) ? profile : "Lotus Exige 240";
        if ("Lexus LFA".equals(profile)) setVehicleProfile(PROFILE_LEXUS_LFA());
        else if ("GTR R34".equals(profile)) setVehicleProfile(PROFILE_GTRR34());
        else if ("Modern F1 V10".equals(profile)) setVehicleProfile(PROFILE_F1_V10());
        else if ("Ferrari F2004".equals(profile)) setVehicleProfile(PROFILE_FERRARI_F2004());
        else if ("McLaren P1".equals(profile)) setVehicleProfile(PROFILE_MCLAREN_P1());
        else if ("Audi RS6 AVANT".equals(profile)) setVehicleProfile(PROFILE_AUDI_RS6_AVANT());
        else if ("Audi RS6 AVANT MONO".equals(profile)) setVehicleProfile(PROFILE_AUDI_RS6_AVANT_MONO());
        else if ("BMW M3 E92 V8".equals(profile)) setVehicleProfile(PROFILE_BMW_M3_E92());
        else if ("BMW M3 E92 V8 MONO".equals(profile)) setVehicleProfile(PROFILE_BMW_M3_E92_MONO());
        else if ("BMW M3 E92 V8 2".equals(profile)) setVehicleProfile(PROFILE_BMW_M3_E92_2());
        else setVehicleProfile(PROFILE_LOTUS_EXIGE());
    }

    public void setDriveModeAggressiveness(float aggressiveness01) { mDriveModeAggressiveness = Math.max(0f, Math.min(1f, aggressiveness01)); }
    public void setSimulatedThrottle(float throttle01) { mSimulatedThrottle = Math.max(0.01f, Math.min(1f, throttle01)); }
    public float getSimulatedThrottle() { return mSimulatedThrottle; }
    public void setRealisticMixEnabled(boolean enabled) { mRealisticMixEnabled = enabled; }
    public boolean isRealisticMixEnabled() { return mRealisticMixEnabled; }
    public boolean isRevMatchEnabled() { return mEnableRevMatch; }
    public void setRevMatchEnabled(boolean enabled) { mEnableRevMatch = enabled; }
    public boolean isGearWhineEnabled() { return mGearWhineEnabled; }
    public void setGearWhineEnabled(boolean enabled) { mGearWhineEnabled = enabled; }
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
                        {0f, 60f},
                        {30f, 90f},
                        {60f, 120f},
                        {90f, 150f},
                        {110f, 180f},
                        {130f, 210f},
                        {150f, 320f}
                },
                0.18f, 0.08f, 150, 150f, // Hafif ve atik (Moderate Wobble)
                R.raw.elisec_startup,R.raw.key_removed,
                // ON Katmanı (Gaza Basıldığında)
                new int[][]{
                        {R.raw.elisesc_idle, 800},
                        {R.raw.elisesc_on_3000, 3000},
                        {R.raw.elisesc_on_4750, 4750},
                        {R.raw.elisesc_on_8115, 8115},
                        {R.raw.elisesc_on_9649, 8800},
                        {R.raw.elisesc_on_9649, 9000}
                },

                // OFF Katmanı (Gaz Çekildiğinde)
                new int[][]{
                        {R.raw.elisesc_idle, 800},
                        {R.raw.elisesc_off_2500, 2500},
                        {R.raw.elisesc_off_3750, 3750},
                        {R.raw.elisesc_off_5000, 5000},
                        {R.raw.elisesc_off_8500, 8500},
                        {R.raw.elisesc_off_8500, 9000}
                }
        );
    }
    public static VehicleProfile PROFILE_LEXUS_LFA() {
        return new VehicleProfile("Lexus LFA",
                984f,
                9550f,
                1f,
                0,

                new float[][]{
                        {0f, 60f},
                        {30f, 90f},
                        {60f, 120f},
                        {90f, 150f},
                        {110f, 180f},
                        {130f, 210f},
                        {150f, 320f}
                },

                0.25f,0.15f,200,180f,
                R.raw.lfa_in_startup,
                R.raw.igniton_stop,

                // ON Katmanı (Gaza Basıldığında V10 Çığlığı)
                new int[][]{
                        {R.raw.lfa_in_idle, 984},
                        {R.raw.lfa_in_onverylow_1, 2500},
                        {R.raw.lfa_in_onlow, 4500},
                        {R.raw.lfa_in_onmid, 6500},
                        {R.raw.lfa_in_onhigh, 8500},
                        {R.raw.lfa_in_onhigh, 9550}
                },
                // OFF Katmanı (Gaz Çekildiğinde Gelen Yırtıcı Kompresyon)
                new int[][]{
                        {R.raw.lfa_in_idle, 984},
                        {R.raw.lfa_in_offverylow_2, 2500},
                        {R.raw.lfa_in_offlow, 4500},
                        {R.raw.lfa_in_offmid, 6500},
                        {R.raw.lfa_in_offhigh, 8500},
                        {R.raw.lfa_in_offhigh, 9550}
                }
        );
    }
    public static VehicleProfile PROFILE_F1_V10() {
        return new VehicleProfile("Modern F1 V10",
                4000f,
                20050f,
                0.6f,
                0,
                new float[][]{
                        {0f, 60f},
                        {30f, 90f},
                        {60f, 120f},
                        {90f, 150f},
                        {110f, 180f},
                        {130f, 210f},
                        {150f, 320f}
                },
                0.25f, 0.15f,80,380f,

                R.raw.modgpv10_startup,
                0,

                // ON Katmanı (İnanılmaz Yüksek Devir Çığlıkları)
                new int[][]{
                        {R.raw.modgpv10_onidle, 4000},
                        {R.raw.modgpv10_onverylow, 6500},
                        {R.raw.modgpv10_onlow, 9000},
                        {R.raw.modgpv10_onlowmid_2_manual_11000, 11000},
                        {R.raw.modgpv10_onmid, 13000},
                        {R.raw.modgpv10_onmidhigh_auto_14000, 14000},
                        {R.raw.modgpv10_onhigh, 18000},
                        {R.raw.modgpv10_onhigh, 20050}
                },
                // OFF Katmanı (Gaz Çekildiğindeki Yırtıcı V10 Kompresyonu)
                new int[][]{
                        {R.raw.modgpv10_idle, 4000},
                        {R.raw.modgpv10_offidle_auto_6000, 6000},
                        {R.raw.modgpv10_offverylow, 7000},
                        {R.raw.modgpv10_offverylow_2_manual_8000, 8000},
                        {R.raw.modgpv10_offlow, 11000},
                        {R.raw.modgpv10_offmid, 15000},
                        {R.raw.modgpv10_offhigh, 20050}
                }
        );
    }
    public static VehicleProfile PROFILE_FERRARI_F2004() {
        return new VehicleProfile("Ferrari F2004",
                4000f,   // Tahmini rölanti devri
                19000f,  // Tahmini kesici
                0.6f,
                0,
                new float[][]{
                        {0f, 60f},
                        {30f, 90f},
                        {60f, 120f},
                        {90f, 150f},
                        {110f, 180f},
                        {130f, 210f},
                        {150f, 320f}
                },
                0.25f, 0.15f, 80, 300f,
                0, 0,
                // ON katmanı (kokpit içi / iç mekan yükte sesler)
                // Format: {resId, baseRpm, minRpm, maxRpm}
                new int[][]{
                        {R.raw.f2004_in_idle,        4000},   // idle, 3–5.2k
                        {R.raw.f2004_in_verylow_2,     5500},   // 4.2–6.5k (idle ile overlap)
                        {R.raw.f2004_in_verylow,   7000},   // 5.5–7.8k
                        {R.raw.f2004_in_on_mid,      9000},   // 7–9.2k
                        {R.raw.f2004_in_on_low,      11500},   // 8.7–11k
                        {R.raw.f2004_in_on_mid2,    14000},   // 10.5–13.8k
                        {R.raw.f2004_in_on_high_mix,16000},   // 13.5–17.2k
                        {R.raw.f2004_in_on_veryhigh_mix,18500} // 16.8–19.5k
                },
                // OFF katmanı (kokpit içi gaz kesme / kompresyon)
                new int[][]{
                        {R.raw.f2004_in_idle,            4000},   // coast düşük devir kompresyon
                        {R.raw.f2004_in_off_low,         6000},   // 4.8–7.2k
                        {R.raw.f2004_in_offmid_pitchare, 8042},   // 7.2–10.2k (8042 merkez)
                        {R.raw.f2004_in_off_midhigh_mix,12000},   // 9.8–14.5k
                        {R.raw.f2004_in_off_high,       17000},  // 14–18.2k
                        {R.raw.f2004_in_off_high,       19000}   // 17.8–19.5k
                }
        );
    }
    public static VehicleProfile PROFILE_MCLAREN_P1() {
        return new VehicleProfile("McLaren P1",
                1000f,   // idle
                8500f,   // approximate redline
                0.8f,
                2,       // turbo
                new float[][]{
                        {0f, 60f},
                        {30f, 90f},
                        {60f, 120f},
                        {90f, 150f},
                        {110f, 180f},
                        {130f, 210f},
                        {150f, 320f}
                },
                0.18f, 0.08f,120,220f,
                0, 0,
                // ON katmanı (iç mekan yükte sesler)
                new int[][]{
                        {R.raw.p1_in_idle,          1000},
                        {R.raw.p1_in_on_verylow2,   2500},
                        {R.raw.p1_in_on_low2,       4000},
                        {R.raw.p1_in_on_lowmid_b,   5500},
                        {R.raw.p1_in_on_mid_c,      7000},
                        {R.raw.p1_in_on_high_b_2,   8000},
                        {R.raw.p1_in_on_veryhigh_b, 8500}
                },
                // OFF katmanı (iç mekan gaz kesme)
                new int[][]{
                        {R.raw.p1_in_idle,          1000},
                        {R.raw.p1_in_off_verylow,   2500},
                        {R.raw.p1_in_off_low_2,     4000},
                        {R.raw.p1_in_off_mid_2,     6000},
                        {R.raw.p1_in_off_high,      8500}
                }
        );
    }
    public static VehicleProfile PROFILE_GTRR34() {
        return new VehicleProfile("GTR R34", 1000f, 8200, 0.5f,
                3,
                new float[][]{
                        {0f, 60f},
                        {30f, 90f},
                        {60f, 120f},
                        {90f, 150f},
                        {110f, 180f},
                        {130f, 210f},
                        {150f, 320f}
                },
                0.18f, 0.08f,110,250f,
                0,0,
                // ON Katmanı
                new int[][]{
                        {R.raw.rb26_4_ex_idle, 1000},
                        {R.raw.rb26_in_2_onverylow, 1500},
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
    public static VehicleProfile PROFILE_AUDI_RS6_AVANT() {
        return new VehicleProfile("Audi RS6 AVANT",
                800f, 7400f, 1.0f,
                0,
                new float[][]{
                        {0f, 60f},
                        {30f, 90f},
                        {60f, 120f},
                        {90f, 150f},
                        {110f, 180f},
                        {130f, 210f},
                        {150f, 320f}
                },
                0.15f, 0.08f, 180, 450f,
                R.raw.rs6_startup, R.raw.igniton_stop,

                // ON Katmanı (İç Mekan - Rs6_in_1_on)
                new int[][]{
                        {R.raw.rs6_4_in_idle_rpm937, 800, 600, 1864},   // Senin uyarınla 800'e çekildi
                        {R.raw.rs6_in_1_on_rpm1864, 1864, 800, 2800},
                        {R.raw.rs6_in_1_on_rpm2800, 2800, 1864, 3117},
                        {R.raw.rs6_in_1_on_rpm3117, 3117, 2800, 3747},
                        {R.raw.rs6_in_1_on_rpm3747, 3747, 3117, 4917},
                        {R.raw.rs6_in_1_on_rpm4917, 4917, 3747, 5608},
                        {R.raw.rs6_in_1_on_rpm5608, 5608, 4917, 5816},
                        {R.raw.rs6_in_1_on_rpm5816, 5816, 5608, 7400},
                        {R.raw.rs6_in_1_on_rpm5816, 7400, 5816, 7500}
                },

                // OFF Katmanı (İç Mekan - Rs6_in_1_off)
                new int[][]{
                        {R.raw.rs6_4_in_idle_rpm937, 800, 600, 2882},
                        {R.raw.rs6_in_1_off_rpm2882, 2882, 800, 4075},
                        {R.raw.rs6_in_1_off_rpm4075, 4075, 2882, 4991},
                        {R.raw.rs6_in_1_off_rpm4991, 4991, 4075, 7400}
                }
        );
    }
    public static VehicleProfile PROFILE_AUDI_RS6_AVANT_MONO() {
        return new VehicleProfile("Audi RS6 AVANT MONO",
                800f, 7400f, 1.0f,
                0,
                new float[][]{
                        {0f, 60f},
                        {30f, 90f},
                        {60f, 120f},
                        {90f, 150f},
                        {110f, 180f},
                        {130f, 210f},
                        {150f, 320f}
                },
                0.15f, 0.08f, 180, 450f,
                R.raw.rs6_startup, R.raw.igniton_stop,

                // ON Katmanı (İç Mekan - Rs6_in_1_on)
                new int[][]{
                        {R.raw.rs6_4_in_idle_rpm937, 800},   // Senin uyarınla 800'e çekildi
                        {R.raw.rs6_in_1_on_rpm1864, 1864},
                        {R.raw.rs6_in_1_on_rpm2800, 2800},
                        {R.raw.rs6_in_1_on_rpm3117, 3117},
                        {R.raw.rs6_in_1_on_rpm3747, 3747},
                        {R.raw.rs6_in_1_on_rpm4917, 4917},
                        {R.raw.rs6_in_1_on_rpm5608, 5608},
                        {R.raw.rs6_in_1_on_rpm5816, 5816},
                        {R.raw.rs6_in_1_on_rpm5816, 7400}
                },

                // OFF Katmanı (İç Mekan - Rs6_in_1_off)
                new int[][]{
                        {R.raw.rs6_4_in_idle_rpm937, 800},
                        {R.raw.rs6_in_1_off_rpm2882, 2882},
                        {R.raw.rs6_in_1_off_rpm4075, 4075},
                        {R.raw.rs6_in_1_off_rpm4991, 4991}
                }
        );
    }
    public static VehicleProfile PROFILE_BMW_M3_E92() {
        return new VehicleProfile("BMW M3 E92 V8",
                800f, 8400f, 1.0f,
                0, // Atmosferik S65 V8
                new float[][]{
                        {0f, 60f},
                        {30f, 90f},
                        {60f, 120f},
                        {90f, 150f},
                        {110f, 180f},
                        {130f, 210f},
                        {150f, 320f}
                },
                0.18f, 0.08f, 120, 300f, // Seri vites geçişleri
                0, 0, // Startup/Stop seslerini genel listeden seçebilirsin

                // ON Katmanı (Seslerin iç içe geçtiği yoğun doku)
                new int[][]{
                        // {resId, baseRpm, minRpm, maxRpm}
                        {R.raw.m3e92_idle, 800, 600, 3200},             // Rölanti 3200'e kadar alttan destek verir
                        {R.raw.m3e92_on_3000, 3000, 800, 5500},         // 3k sesi rölantiyle başlar, 5.5k'ya kadar sürer
                        {R.raw.m3e92_on_4000, 4000, 2500, 6500},        // 4k sesi tam ortada devreye girer
                        {R.raw.m3e92_on_4198, 4198, 3000, 7200},        // 4198'i tekrar açtım, ara tınıyı doldurur
                        {R.raw.m3e92_on_6000, 6000, 4000, 8400},        // 6k yırtılması 4k'da başlar
                        {R.raw.m3e92_on_8500, 8400, 5500, 9000},        // Zirve sesi 5.5k'dan itibaren gelmeye başlar
                        {R.raw.limiter, 8400, 8200, 8600}               // Kesici sadece en tepede
                },

// OFF Katmanı (Kompresyonun hissedildiği iç içe yapı)
                new int[][]{
                        {R.raw.m3e92_idle, 800, 600, 3500},             // Gaz kesince rölanti homurtusu hemen gelir
                        {R.raw.m3e92_off_2800, 2800, 800, 6500},        // Orta devir kompresyonu geniş tutuldu
                        {R.raw.m3e92_off_6000, 6000, 2500, 8500},       // 6k OFF sesi 2.5k'ya kadar süzülür
                        {R.raw.m3e92_off_8500, 8400, 5000, 9500}        // En üst devir kompresyonu
                }
        );
    }
    public static VehicleProfile PROFILE_BMW_M3_E92_2() {
        return new VehicleProfile("BMW M3 E92 V8 2",
                800f, 8400f, 1.0f,
                0, // Atmosferik S65 V8
                new float[][]{
                        {0f, 60f},
                        {30f, 90f},
                        {60f, 120f},
                        {90f, 150f},
                        {110f, 180f},
                        {130f, 210f},
                        {150f, 320f}
                },
                0.18f, 0.08f, 120, 300f, // Seri vites geçişleri
                0, 0, // Startup/Stop seslerini genel listeden seçebilirsin

                // ON Katmanı (İç Mekan - m3e92_on_...)
                new int[][]{
                        {R.raw.m3e92_idle, 800, 600, 3000},
                        {R.raw.m3e92_on_3000, 3000, 800, 4000},
                        {R.raw.m3e92_on_4000, 4000, 3000, 6000},
                        //{R.raw.m3e92_on_4198, 4198, 4000, 6000},
                        {R.raw.m3e92_on_6000, 6000, 4000, 8500},
                        {R.raw.m3e92_on_8500, 8400, 6000, 8500}, // 8400 Redline
                        {R.raw.m3e92_on_8500, 8400, 8300, 8600}
                },

                // OFF Katmanı (İç Mekan - m3e92_off_...)
                new int[][]{
                        {R.raw.m3e92_idle, 800, 600, 2800},
                        {R.raw.m3e92_off_2800, 2800, 800, 6000},
                        {R.raw.m3e92_off_6000, 6000, 2800, 8500},
                        {R.raw.m3e92_off_8500, 8400, 6000, 9000}
                }
        );
    }
    public static VehicleProfile PROFILE_BMW_M3_E92_MONO() {
        return new VehicleProfile("BMW M3 E92 V8 MONO",
                800f, 8400f, 1.0f,
                0, // Atmosferik S65 V8
                new float[][]{
                        {0f, 60f},
                        {30f, 90f},
                        {60f, 120f},
                        {90f, 150f},
                        {110f, 180f},
                        {130f, 210f},
                        {150f, 320f}
                },
                0.18f, 0.08f, 120, 300f, // Seri vites geçişleri
                0, 0, // Startup/Stop seslerini genel listeden seçebilirsin

                // ON Katmanı (İç Mekan - m3e92_on_...)
                new int[][]{
                        {R.raw.m3e92_idle, 800},
                        {R.raw.m3e92_on_3000, 3000},
                        {R.raw.m3e92_on_4000, 4000},
                        //{R.raw.m3e92_on_4198, 4198},
                        {R.raw.m3e92_on_6000, 6000},
                        {R.raw.m3e92_on_8500, 8400}, // 8400 Redline
                        {R.raw.m3e92_on_8500, 8400}
                },

                // OFF Katmanı (İç Mekan - m3e92_off_...)
                new int[][]{
                        {R.raw.m3e92_idle, 800},
                        {R.raw.m3e92_off_2800, 2800},
                        {R.raw.m3e92_off_6000, 6000},
                        {R.raw.m3e92_off_8500, 84000}
                }
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
                        && (MG4Hardware.isVehicleReady() || MG4Hardware.isSimSpeedActive());
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

        if (mIsDualLayer) {
            for (EngineSample sample : mCurrentSamplesOn) sample.soundId = mSoundPool.load(mContext, sample.resourceId, 1);
            for (EngineSample sample : mCurrentSamplesOff) sample.soundId = mSoundPool.load(mContext, sample.resourceId, 1);
        } else {
            for (EngineSample sample : mCurrentSamples) sample.soundId = mSoundPool.load(mContext, sample.resourceId, 1);
        }
    }

    public void stop() {
        mIsPlaying = false;

        if (mSoundPool != null) {
            // 1. Mevcut tüm motor ve yardımcı döngü seslerini anında kapat
            if (mCurrentSamples != null) for (EngineSample s : mCurrentSamples) if(s.streamId != -1) mSoundPool.stop(s.streamId);
            if (mCurrentSamplesOn != null) for (EngineSample s : mCurrentSamplesOn) if(s.streamId != -1) mSoundPool.stop(s.streamId);
            if (mCurrentSamplesOff != null) for (EngineSample s : mCurrentSamplesOff) if(s.streamId != -1) mSoundPool.stop(s.streamId);
            if (mTurboStreamId != -1) mSoundPool.stop(mTurboStreamId);
            if (mGearWhineStreamId != -1) mSoundPool.stop(mGearWhineStreamId);
            if (mSubwaveStreamId != -1) mSoundPool.stop(mSubwaveStreamId);

            // 2. İSTOP SESİNİ ÇAL
            if (mStopSoundId != -1) {
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

    private void updateGearAndRpm() {
        if (mActiveProfile == null) return;
        float speed = mCurrentSpeedKmh;
        float throttle = mSimulatedThrottle;
        long currentTime = System.currentTimeMillis();

        if (speed < 1.0f) {
            mCurrentGear = 0;
            // ARAÇ DURURKEN BOŞTA GAZ VERME (N-Revving)
            // Hız 0 olsa bile boş viteste gaza basınca devir yükselsin
            float targetIdleRpm = mIdleRpm + (throttle * (mMaxRpm - mIdleRpm));
            float currentSmooth = (throttle > 0.05f) ? mActiveProfile.rpmOnSmooth : mActiveProfile.rpmOffSmooth;
            mCurrentRpm = (mCurrentRpm * (1.0f - currentSmooth)) + (targetIdleRpm * currentSmooth);
            return;
        }

        long shiftCooldown = Math.max(400L, mActiveProfile.shiftDurationMs);
        if (currentTime - mLastShiftTime > shiftCooldown) {
            int targetGear = mCurrentGear > 0 ? mCurrentGear : 1;

            // 1. Vites Büyütme (Up Shift) - Eşikler daha gerçekçi ayarlandı
            float modeBonus = mDriveModeAggressiveness * 0.20f;
            float shiftUpRpmThreshold = mIdleRpm + (mMaxRpm - mIdleRpm) * Math.min(0.96f, (0.25f + modeBonus + (throttle * 0.60f)));

            // 2. Vites Küçültme (Down Shift) - Eşik ÇOK DÜŞÜRÜLDÜ ki araba hemen vites düşürmesin (Ping-Pong olmasın)
            float shiftDownRpmThreshold = mIdleRpm + (mMaxRpm - mIdleRpm) * (0.05f + (mDriveModeAggressiveness * 0.15f));

            // KICKDOWN: Dip gazda (%80 üstü) vites düşürme eşiğini artır
            if (throttle > 0.8f) {
                shiftDownRpmThreshold = mIdleRpm + (mMaxRpm - mIdleRpm) * 0.45f;
            }

            // Mevcut vitesteki mekanik devrimiz nedir?
            float currentGearRpm = calculateMechanicalRpm(targetGear, speed);

            // --- VİTES KARARLARI (GELECEĞİ GÖREN PING-PONG KORUMASI VE BLOCK-SHIFT) ---
            if (currentGearRpm > shiftUpRpmThreshold && targetGear < mActiveProfile.gearRanges.length) {

                // İLERİYİ GÖRME: Eğer 2. vitese geçersem devir beni hemen geri 1'e atacak kadar düşük mü olacak?
                float nextGearRpm = calculateMechanicalRpm(targetGear + 1, speed);

                // Eğer yeni vitesin devri, düşürme eşiğinden en az 250 RPM yüksekse (güvendeysek) vites büyüt!
                if (nextGearRpm > shiftDownRpmThreshold + 250f) {
                    targetGear++;
                }
            }
            else if (currentGearRpm < shiftDownRpmThreshold && targetGear > 1) {
                // BLOCK-SHIFT (Atlayarak Vites Küçültme): Hız aniden 170'ten 89'a düşerse...
                // Şanzıman doğru vitesi bulana kadar aradaki tüm vitesleri saniyenin binde biri hızında atlar!
                while (targetGear > 1) {
                    float prevGearRpm = calculateMechanicalRpm(targetGear - 1, speed);

                    // Alt vites motoru patlatmayacaksa (Kesicinin altındaysa) o vitese atla
                    if (prevGearRpm < mMaxRpm * 0.96f) {
                        targetGear--;
                        currentGearRpm = prevGearRpm; // Hesaplamayı yeni vitese göre güncelle

                        // Eğer indiğimiz bu yeni vitesin devri artık güç üretmek için yeterliyse döngüyü kır!
                        if (currentGearRpm >= shiftDownRpmThreshold) {
                            break;
                        }
                    } else {
                        // Bir alt vites motoru patlatacaksa, mecburen bulunduğumuz viteste kalıp freni bekleyeceğiz.
                        break;
                    }
                }
            }

            // Eğer vites değiştiyse aksiyon al
            if (targetGear != mCurrentGear) {
                if (targetGear < mCurrentGear && mEnableRevMatch) {
                    // Vites düşürürken o meşhur Ara Gazı (Rev Match) ver
                    float aggressivenessFactor = 0.10f + (mDriveModeAggressiveness * 0.15f);
                    mRevMatchBoost = mMaxRpm * aggressivenessFactor;
                } else {
                    mRevMatchBoost = 0;
                }
                mCurrentGear = targetGear;
                mLastShiftTime = currentTime;
            }
        }

        // --- 2. HIZA MEKANİK OLARAK KİLİTLENMİŞ DEVİR HESAPLAMASI ---
        if (mCurrentGear > 0) {
            // İŞTE ÇÖZÜM: Devir artık sadece HIZ'a ve VİTES'e bağlı! Gazla alakası kalmadı.
            float rawRpm = calculateMechanicalRpm(mCurrentGear, speed);

            // Ara gazı etkisini yavaşça söndür
            mRevMatchBoost *= 0.90f;
            if (mRevMatchBoost < 5f) mRevMatchBoost = 0;

            float targetRpmFinal = rawRpm + mRevMatchBoost;

            // --- VİTES SARSINTISI (WOBBLE) ARTIK PROFİLDEKİ SÜREYİ KULLANIYOR ---
            long timeSinceShift = currentTime - mLastShiftTime;
            long shiftDur = mActiveProfile.shiftDurationMs;

            if (timeSinceShift < shiftDur && mActiveProfile.wobbleMagnitude > 0) {
                float timeSec = timeSinceShift / 1000f;
                float durSec = shiftDur / 1000f;
                float dampening = Math.max(0f, 1.0f - (timeSec / durSec));
                float wobbleOffset = (float) Math.sin(timeSec * 15.0f * Math.PI * 2) * mActiveProfile.wobbleMagnitude * dampening;
                targetRpmFinal += wobbleOffset;
            }

            float currentSmooth;

            if (mRevMatchBoost > 50f) {
                currentSmooth = 0.25f;
            }
            else if (timeSinceShift < shiftDur) {
                currentSmooth = 16f / (float) shiftDur;
                currentSmooth = Math.max(0.002f, Math.min(0.2f, currentSmooth));
            }
            else {
                currentSmooth = 0.8f;
            }

            mCurrentRpm = (mCurrentRpm * (1.0f - currentSmooth)) + (targetRpmFinal * currentSmooth);
        }

        // Sınırların dışına çıkmasını engelle
        mCurrentRpm = Math.max(mIdleRpm, Math.min(mCurrentRpm, mMaxRpm));
    }

    // YENİ YARDIMCI METOT (Bunu updateGearAndRpm'in hemen altına yapıştır)
    // YENİ VE KUSURSUZ MEKANİK DEVİR HESAPLAYICI
    private float calculateMechanicalRpm(int gear, float speed) {
        if (gear < 1 || gear > mActiveProfile.gearRanges.length) return mIdleRpm;

        float[] range = mActiveProfile.gearRanges[gear - 1];
        float minSpeedInGear = range[0]; // Vitesin alt hızı (Listendeki sol değer)
        float maxSpeedInGear = range[1]; // Vitesin üst hızı (Listendeki sağ değer)

        // Hızın bu vites aralığındaki gerçek yüzdesini bul
        // Eğer hız minSpeed'den düşükse (örneğin 15. viteste 89 kmh ile gidiyorsan) bu oran EKSİ (-) çıkar!
        float speedRatio = (speed - minSpeedInGear) / (maxSpeedInGear - minSpeedInGear);

        // Devri hesapla (Burada Math.max ile sınır KOYMUYORUZ ki devir eksiye düşsün ve şanzıman panikleyip vites düşürsün)
        return mIdleRpm + (speedRatio * (mMaxRpm - mIdleRpm));
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
                if (mRealisticMixEnabled) compVol *= 1.2f;
                mSoundPool.setVolume(mTurboStreamId, compVol, compVol);
                mSoundPool.setRate(mTurboStreamId, 0.8f + (rpmRatio * 1.7f));
            } else {
                float boostFactor = Math.max(0f, Math.min(1f, (rpmRatio > 0.10f) ? (rpmRatio - 0.10f) * 1.12f : 0f));
                if (mRealisticMixEnabled) boostFactor *= 1.2f;
                mCurrentTurboBoost = (mCurrentTurboBoost * 0.90f) + ((mSimulatedThrottle * boostFactor) * 0.10f);
                mSoundPool.setVolume(mTurboStreamId, mCurrentTurboBoost * masterVol * mTurboMaxSound, mCurrentTurboBoost * masterVol * mTurboMaxSound);
                mSoundPool.setRate(mTurboStreamId, 0.8f + (rpmRatio * 1.2f));
            }
        }
        // --- SUBWAVE (ALT BAS / GÖĞÜS TİTRETEN TOKLUK) KONTROLÜ ---
        if (mSubwaveStreamId != -1) {
            if (mSubwaveEnabled) {
                // Şalter AÇIKSA: Motora yük bindikçe bas katmanı belirginleşir
                float loadFactor;
                float subPitchBaseMin;
                float subPitchBaseMax;
                if (mRealisticMixEnabled) {
                    // Daha geniş dinamik: düşük gazda sakin, yüksek gazda güçlü alt bas
                    loadFactor = 0.2f + (mSimulatedThrottle * 0.9f);
                    subPitchBaseMin = 0.55f;
                    subPitchBaseMax = 1.35f;
                } else {
                    loadFactor = 0.4f + (mSimulatedThrottle * 0.6f);
                    subPitchBaseMin = 0.6f;
                    subPitchBaseMax = 1.3f;
                }
                float subVolume = masterVol * loadFactor * 1.5f; // Bas çarpanı

                // Derinliği korumak için pitch çok tizleşmemeli
                float subPitch = subPitchBaseMin + (rpmRatio * (subPitchBaseMax - subPitchBaseMin));

                // Araç dururken ve gaza basılmıyorken rölanti tokluğu
                if (mCurrentSpeedKmh < 1.0f && mSimulatedThrottle < 0.05f) {
                    subVolume = masterVol * mCurrentIdleVolumeScale * 0.6f;
                    subPitch = mIdlePitch * 0.6f;
                }

                // Patlamaları önlemek için güvenlik limiti
                subVolume = Math.max(0f, Math.min(1.0f, subVolume));

                mSoundPool.setVolume(mSubwaveStreamId, subVolume, subVolume);
                mSoundPool.setRate(mSubwaveStreamId, subPitch);
            } else {
                // Şalter KAPALIYSA: Derin bası tamamen sustur
                mSoundPool.setVolume(mSubwaveStreamId, 0f, 0f);
            }
        }

        // --- MİKSER: HİBRİT KARAR MEKANİZMASI ---
        if (mIsDualLayer) {
            float onWeight;
            float offWeight;
            if (mRealisticMixEnabled) {
                float t = Math.max(0f, Math.min(1f, mSimulatedThrottle));
                onWeight = (float) Math.pow(t, 0.5f);                 // dip gazda hızlıca aç
                offWeight = (float) Math.pow(1f - t, 1.2f);           // gaz artınca OFF hızlıca sön
                // yüksek devirde OFF'u ekstra kısmak
                if (rpmRatio > 0.6f) {
                    float k = Math.min(1f, (rpmRatio - 0.6f) / 0.3f); // 0.6–0.9 arası
                    offWeight *= (1f - 0.7f * k);                     // %70'e kadar azalt
                }
            } else {
                onWeight = (float) Math.sqrt(mSimulatedThrottle);
                offWeight = (float) Math.sqrt(Math.max(0f, 1.0f - mSimulatedThrottle));
            }

            // Araç dururken ve gaz verilirken sadece ON katmanı duyulsun (N-revving)
            if (mCurrentSpeedKmh < 1.0f && mSimulatedThrottle > 0.05f) {
                onWeight = 1.0f;
                offWeight = 0.0f;
            }

            // DÜZELTME: Kısılmış masterVol değerini de fonksiyona yolluyoruz
            processLayer(mCurrentSamplesOn, rpm, onWeight * masterVol, masterVol, true);
            processLayer(mCurrentSamplesOff, rpm, offWeight * masterVol, masterVol, false);

        } else {
            // SİNGLE LAYER MİKSERİ (Eski hız sıfırsa rölantiye kilitleyen blok TAMAMEN silindi)
            if (mCurrentSamples == null || mCurrentSamples.length == 0) return;

            // 1) Her sample için rpm'e bağlı ham ağırlıkları hesapla
            float[] weights = new float[mCurrentSamples.length];
            float weightSum = 0f;

            EngineSample lower = mCurrentSamples[0], upper = mCurrentSamples[mCurrentSamples.length - 1];
            // Komşu tabanlı blend sadece min/max tanımı olmayanlar için kullanılacak
            for (int i = 0; i < mCurrentSamples.length; i++) {
                EngineSample s = mCurrentSamples[i];
                float raw;
                if (s.hasWindow()) {
                    if (rpm <= s.minRpm || rpm >= s.maxRpm) {
                        raw = 0f;
                    } else if (rpm <= s.baseRpm) {
                        raw = (rpm - s.minRpm) / (float) (s.baseRpm - s.minRpm);
                    } else {
                        raw = (s.maxRpm - rpm) / (float) (s.maxRpm - s.baseRpm);
                    }
                    raw = Math.max(0f, Math.min(1f, raw));
                } else {
                    // Eski komşu tabanlı crossfade
                    lower = mCurrentSamples[0];
                    upper = mCurrentSamples[mCurrentSamples.length - 1];
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
                    // smoothstep: daha yumuşak crossfade
                    float blend = t * t * (3f - 2f * t);
                    raw = (s == lower) ? (1f - blend) : ((s == upper) ? blend : 0f);
                }
                weights[i] = raw;
                weightSum += raw;
            }

            if (weightSum <= 0f) return;

            // 2) Ağırlıkları normalize et (toplam sabit kalsın, rpm sadece karışımı değiştirir)
            float invSum = 1.0f / weightSum;

            float loadVolumeFactor = mRealisticMixEnabled
                    ? (0.2f + (mSimulatedThrottle * 0.8f))   // daha agresif: gazla hızlı art
                    : (0.5f + (mSimulatedThrottle * 0.5f));
            float loadPitchFactor = 0.98f + (mSimulatedThrottle * 0.04f);
            float modeVolumeBoost = (mDriveModeAggressiveness > 0.5f) ? 1.2f : 1.0f;

            for (int i = 0; i < mCurrentSamples.length; i++) {
                EngineSample s = mCurrentSamples[i];
                if (s.streamId == -1) continue;

                float mixWeight = weights[i] * invSum; // sadece rpm'e göre dağılım
                float shapedVol = mixWeight;
                if (Float.isNaN(shapedVol)) shapedVol = 0f;
                if (i == 0) shapedVol *= mCurrentIdleVolumeScale;

                // Toplam volume neredeyse tamamen gaz karakterinden geliyor
                float finalVolume = Math.max(0.0f, Math.min(1.0f, shapedVol * masterVol * loadVolumeFactor * modeVolumeBoost));
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

        // 1) Ham rpm ağırlıklarını topla
        float[] weights = new float[layer.length];
        float weightSum = 0f;
        for (int i = 0; i < layer.length; i++) {
            EngineSample s = layer[i];
            float raw;
            if (s.hasWindow()) {
                // FMOD tarzı üçgen pencere: minRpm–baseRpm–maxRpm
                if (rpm <= s.minRpm || rpm >= s.maxRpm) {
                    raw = 0f;
                } else if (rpm <= s.baseRpm) {
                    raw = (rpm - s.minRpm) / (float) (s.baseRpm - s.minRpm);
                } else {
                    raw = (s.maxRpm - rpm) / (float) (s.maxRpm - s.baseRpm);
                }
                raw = Math.max(0f, Math.min(1f, raw));
            } else {
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
                raw = (s == lower) ? (1f - blend) : ((s == upper) ? blend : 0f);
            }
            weights[i] = raw;
            weightSum += raw;
        }

        if (weightSum <= 0f) return;
        float invSum = 1.0f / weightSum;

        for (int i = 0; i < layer.length; i++) {
            EngineSample s = layer[i];
            if (s.streamId == -1) continue;

            float mixWeight = weights[i] * invSum;  // rpm sadece karışımı belirler
            float shapedVol = mixWeight;
            if (Float.isNaN(shapedVol)) shapedVol = 0f;
            if (i == 0) shapedVol *= mCurrentIdleVolumeScale;

            float finalVolume = shapedVol * weightVol; // weightVol zaten throttle + masterVol içeriyor
            float pitch = rpm / s.baseRpm;

            if (isOnLayer) pitch *= (0.98f + (mSimulatedThrottle * 0.04f));

            if (mRealisticMixEnabled) {
                // ON katmanında gaz + devirle hafif ekstra tizleşme ve mikro jitter
                float rpmRatioLocal = (rpm - mIdleRpm) / (mMaxRpm - mIdleRpm);
                rpmRatioLocal = Math.max(0f, Math.min(1f, rpmRatioLocal));
                if (isOnLayer) {
                    pitch *= 1.0f + 0.03f * mSimulatedThrottle * rpmRatioLocal;
                    float jitter = (float) ((Math.random() - 0.5) * 0.02); // ±1% civarı
                    pitch *= 1.0f + jitter * rpmRatioLocal;
                    // Load'a daha sert tepki: ON katmanını boost et, OFF'u hafif bastır
                    finalVolume *= (0.8f + 0.4f * mSimulatedThrottle);
                } else {
                    finalVolume *= (1.0f - 0.4f * mSimulatedThrottle * rpmRatioLocal);
                }
            }

            pitch = Math.max(0.6f, Math.min(1.8f, pitch));

            // Sadece araç dururken ve hiç gaz verilmiyorken kullanıcı pitch (frekans) ayarını uygula
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