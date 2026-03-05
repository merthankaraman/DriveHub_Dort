package com.example.mg4_v3.audio;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.mg4_v3.R;
import com.example.mg4_v3.hardware.MG4Hardware;

import java.util.ArrayList;
import java.util.List;

/**
 * MG4 V3 Simülasyon Motoru (Hibrit Sistem: Single-Layer + FMOD Dual-Layer)
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
    private float mCompressorMaxVol = 0.5f;

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
        int soundId = -1;
        int streamId = -1;

        EngineSample(int baseRpm, int resourceId) {
            this.baseRpm = baseRpm;
            this.resourceId = resourceId;
        }
    }

    public void setMasterVolume(float volume01) { mMasterVolume = Math.max(0f, Math.min(1f, volume01)); }
    public void setMotorMaxPower(float powerKw) { mMotorMaxPower = (Math.max(50f, Math.min(200f, powerKw)) - 20f); }

    public void initFromPreferences(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences("mg4_v3", Context.MODE_PRIVATE);
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
        android.content.SharedPreferences prefs = context.getSharedPreferences("mg4_v3", Context.MODE_PRIVATE);
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
            "Porsche GT3 997",
            "Lexus LFA",
            "Dodge Hellcat",
            "Nissan GT-R GT3",
            "McLaren GT3",
            "BMW Z4 GT3",
            "GTR R34",
            "Mazda 3-Rotor",
            "Modern F1 V10"
    };

    public static String[] getProfileLabels() { return PROFILE_LABELS; }

    public void applyProfileLabel(String profile) {
        mCurrentProfileLabel = (profile != null && !profile.isEmpty()) ? profile : "Lotus Exige 240";
        if ("Porsche GT3 997".equals(profile)) setVehicleProfile(PROFILE_PORSCHE_GT3());
        else if ("Lexus LFA".equals(profile)) setVehicleProfile(PROFILE_LEXUS_LFA());
        else if ("Dodge Hellcat".equals(profile)) setVehicleProfile(PROFILE_DODGE_HELLCAT());
        else if ("Nissan GT-R GT3".equals(profile)) setVehicleProfile(PROFILE_NISSAN_GT3());
        else if ("McLaren GT3".equals(profile)) setVehicleProfile(PROFILE_MCLAREN_GT3());
        else if ("BMW Z4 GT3".equals(profile)) setVehicleProfile(PROFILE_BMW_Z4_GT3());
        else if ("GTR R34".equals(profile)) setVehicleProfile(PROFILE_GTRR34());
        else if ("Mazda 3-Rotor".equals(profile)) setVehicleProfile(PROFILE_MAZDA_ROTOR());
        else if ("Modern F1 V10".equals(profile)) setVehicleProfile(PROFILE_F1_V10());
        else setVehicleProfile(PROFILE_LOTUS_EXIGE());
    }

    public void setDriveModeAggressiveness(float aggressiveness01) { mDriveModeAggressiveness = Math.max(0f, Math.min(1f, aggressiveness01)); }
    public void setSimulatedThrottle(float throttle01) { mSimulatedThrottle = Math.max(0.01f, Math.min(1f, throttle01)); }
    public float getSimulatedThrottle() { return mSimulatedThrottle; }
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
                2,
                new float[][]{
                        {0f, 40f},    // 1. Vites (Dururken 0 km/h - Kesicide 40 km/h)
                        {5f, 70f},    // 2. Vites (Rölantide 5 km/h - Kesicide 70 km/h)
                        {9f, 100f},   // 3. Vites (Rölantide 9 km/h - Kesicide 100 km/h)
                        {12f, 130f},  // 4. Vites (Rölantide 12 km/h - Kesicide 130 km/h)
                        {15f, 155f},  // 5. Vites (Rölantide 15 km/h - Kesicide 155 km/h)
                        {18f, 180f},   // 6. Vites (Rölantide 18 km/h - Kesicide 180 km/h)
                        {20f, 320f}   // 7. VİTES (OVERDRIVE): Uzun yol vitesidir. 175'te devri 5000'e, 120'de 3500'e düşürür!
                },
                0.18f, 0.08f, 150, 150f, // Hafif ve atik (Moderate Wobble)
                0,0,
                // ON Katmanı (Gaza Basıldığında)
                new int[][]{
                        {R.raw.elisesc_idle, 800},
                        {R.raw.elisesc_on_3000, 3000},
                        {R.raw.elisesc_on_4750, 4750},
                        {R.raw.elisesc_on_8115, 8115},
                        {R.raw.elisesc_on_9649, 8800},
                        {R.raw.elisesc_limiter, 9000}
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
    public static VehicleProfile PROFILE_PORSCHE_GT3() {
        return new VehicleProfile("Porsche GT3 997", 2000f, 9400f, 0.7f,
                0,
                new float[][]{
                        {0f, 75f},    // 1. Vites
                        {6f, 120f},   // 2. Vites
                        {10f, 165f},  // 3. Vites
                        {15f, 210f},  // 4. Vites
                        {20f, 260f},  // 5. Vites
                        {25f, 315f},  // 6. Vites (Son Hız)
                        {30f, 370f}   // 7. VİTES (OVERDRIVE)
                },
                0.18f, 0.08f, 110, 250f,
                R.raw.por911rsr_on_start, R.raw.por911rsr_on_stop,
                new int[][]{
                        {R.raw.por911rsr_on_idle, 2000},
                        {R.raw.por911rsr_on_onverylow, 3500},
                        {R.raw.por911rsr_on_onlow, 5000},
                        {R.raw.por911rsr_on_onmid, 6500},
                        {R.raw.por911rsr_on_onhigh, 8000},
                        {R.raw.por911rsr_on_limiter, 9400}
                },
                new int[][]{
                        {R.raw.por911rsr_on_offidle, 2000},
                        {R.raw.por911rsr_on_offverylow, 3500},
                        {R.raw.por911rsr_on_offlow, 5000},
                        {R.raw.por911rsr_on_offmid, 6500},
                        {R.raw.por911rsr_on_offhigh, 8000},
                        {R.raw.por911rsr_on_offlimiter, 9400}
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
                        {0f, 80f},    // 1. Vites
                        {6f, 125f},   // 2. Vites
                        {10f, 175f},  // 3. Vites
                        {15f, 225f},  // 4. Vites
                        {20f, 275f},  // 5. Vites
                        {25f, 330f},  // 6. Vites (Redline V10 Çığlığı ve Son hız)
                        {30f, 400f}   // 7. VİTES (OVERDRIVE): Uzun yol fısıltısı.
                },

                0.25f,0.15f,200,180f,
                R.raw.lfa_in_startup,
                R.raw.lfa_in_stop,

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
                        // LFA paketinde 'offlimiter' olmadığı için üst devir kompresyonunu sonuna kadar sündürüyoruz:
                        {R.raw.lfa_in_offhigh, 9550}
                }
        );
    }
    public static VehicleProfile PROFILE_DODGE_HELLCAT() {
        return new VehicleProfile("Dodge Hellcat",
                700f,   // Orijinal Rölanti
                6100f,  // Orijinal Kesici
                0.7f,
                0,
                new float[][]{
                        {0f, 65f},    // 1. Vites (Patinaj canavarı)
                        {5f, 105f},   // 2. Vites
                        {8f, 145f},   // 3. Vites
                        {12f, 190f},  // 4. Vites
                        {16f, 235f},  // 5. Vites
                        {20f, 280f},  // 6. Vites
                        {25f, 325f},  // 7. Vites (Son hız)
                        {30f, 420f}   // 8. VİTES (OVERDRIVE): 160 ile giderken V8 mırıldanarak çalışır.
                },
                0.12f,0.05f,110,250f,
                R.raw.hellcat_ex_startup_1,
                R.raw.hellcat_ex_stop_1,

                // ON Katmanı (Gaza Basıldığında Gelen V8 Kükremesi)
                new int[][]{
                        {R.raw.hellcat_ex_idle_1, 700},
                        {R.raw.hellcat_in_onload_1000_1600_1, 1600},
                        {R.raw.hellcat_in_onload_1500_2500_2, 2500},
                        {R.raw.hellcat_in_onload_2000_3500_1, 3500},
                        {R.raw.hellcat_in_onload_3000_4400_1, 4400},
                        {R.raw.hellcat_in_onload_4000_5000_2, 5000},
                        {R.raw.hellcat_in_onload_4800_5400_1, 5400},
                        {R.raw.hellcat_limiter, 6100}
                },
                // OFF Katmanı (Gaz Çekildiğinde Gelen Tok V8 Kompresyonu)
                new int[][]{
                        {R.raw.hellcat_ex_idle_1, 700},
                        {R.raw.hellcat_ex_offload_2400_1400_1, 1400},
                        {R.raw.hellcat_ex_offload_2800_2000_1, 2000},
                        {R.raw.hellcat_in_offload_3500_2500_1, 2500},
                        {R.raw.hellcat_in_offload_4000_3000_1, 3000},
                        {R.raw.hellcat_in_offload_4500_3500_1, 3500},
                        {R.raw.hellcat_in_offload_5400_4700_1, 4700},
                        {R.raw.hellcat_in_offload_5400_4700_1, 6100} // Kesiciye kadar son off dosyasını sündürüyoruz
                }
        );
    }
    public static VehicleProfile PROFILE_NISSAN_GT3() {
        return new VehicleProfile("Nissan GT-R GT3",
                880f,
                7250f,
                1.0f,
                1,
                new float[][]{
                        {0f, 85f},
                        {10f, 130f},
                        {15f, 175f},
                        {20f, 220f},
                        {25f, 265f},
                        {30f, 305f}   // 6. Vites Son
                },

                0.18f, 0.08f,200,250f,
                R.raw.nisgt3_startup,
                R.raw.nisgt3_stop,

                // ON Katmanı (Çok Detaylı 9 Katmanlı İvmelenme)
                new int[][]{
                        {R.raw.nisgt3_idle, 880},
                        {R.raw.nisgt3_loop0, 1500},
                        {R.raw.nisgt3_loop01, 2200},
                        {R.raw.nisgt3_loop02, 2900},
                        {R.raw.nisgt3_loop03, 3600},
                        {R.raw.nisgt3_loop04, 4300},
                        {R.raw.nisgt3_loop05, 5000},
                        {R.raw.nisgt3_loop06, 5700},
                        {R.raw.nisgt3_loop07, 6400},
                        {R.raw.nisgt3_loop08, 7000},
                        {R.raw.nisgt3_onlimiter, 7250}
                },
                // OFF Katmanı (Gaz Çekildiğinde Gelen Yırtıcı V6 Kompresyonu)
                new int[][]{
                        {R.raw.nisgt3_idle, 880},
                        {R.raw.nisgt3_off_loop01, 2000},
                        {R.raw.nisgt3_off_loop02, 3500},
                        {R.raw.nisgt3_off_loop03, 5000},
                        {R.raw.nisgt3_off_loop04, 6000},
                        {R.raw.nisgt3_off_loop05, 7250}
                }
        );
    }
    public static VehicleProfile PROFILE_MCLAREN_GT3() {
        return new VehicleProfile("McLaren GT3",
                1476f,   // Orijinal Rölanti
                7800f,   // Orijinal Kesici
                0.7f,
                1,
                new float[][]{
                        {0f, 85f},
                        {10f, 130f},
                        {15f, 175f},
                        {20f, 220f},
                        {25f, 260f},
                        {30f, 300f}   // 6. Vites Son
                },
                0.18f, 0.08f,100,250f,
                R.raw.mp412c_start,
                R.raw.mp412c_stop,

                // ON Katmanı (Gaza Basıldığında V8 Kükremesi)
                new int[][]{
                        {R.raw.mp412c_idle, 1476},
                        {R.raw.mp412c_onidle_3, 2500},
                        {R.raw.mp412c_onlow, 3800},
                        {R.raw.mp412c_onmid, 5000},
                        {R.raw.mp412c_onmidhigh, 6200},
                        {R.raw.mp412c_onhigh, 7400},
                        {R.raw.mp412c_limiter, 7800}
                },
                // OFF Katmanı (Gaz Çekildiğinde Gelen Tok Kompresyon)
                new int[][]{
                        {R.raw.mp412c_idle, 1476},
                        {R.raw.mp412c_offverylow_1, 2500},
                        {R.raw.mp412c_offlow, 3800},
                        {R.raw.mp412c_offmid, 5000},
                        {R.raw.mp412c_offmidhigh, 6200},
                        {R.raw.mp412c_offhigh, 7800}
                }
        );
    }
    public static VehicleProfile PROFILE_BMW_Z4_GT3() {
        return new VehicleProfile("BMW Z4 GT3",
                2000f,
                8900f,
                0.8f,
                0,

                new float[][]{
                        {0f, 80f},
                        {8f, 125f},
                        {12f, 170f},
                        {18f, 215f},
                        {22f, 255f},
                        {26f, 295f}   // 6. Vites Son
                },
                0.18f, 0.08f,200,250f,
                // Marş ve İstop Sesleri
                R.raw.bmwz4gt3_startup,
                R.raw.bmwz4gt3_stop,

                // ON Katmanı (İnanılmaz Detaylı 8 Katmanlı V8 Sesi)
                new int[][]{
                        {R.raw.bmwz4gt3_onidle_1, 2000},
                        {R.raw.bmwz4gt3_onverylow_1, 3000},
                        {R.raw.bmwz4gt3_onverylow_2, 4000},
                        {R.raw.bmwz4gt3_onlow, 5000},
                        {R.raw.bmwz4gt3_onmid, 6000},
                        {R.raw.bmwz4gt3_onmidhigh, 7000},
                        {R.raw.bmwz4gt3_onhigh, 8000},
                        {R.raw.bmwz4gt3_onveryhigh, 8800},
                        {R.raw.bmwz4gt3_limiter, 8900}
                },
                // OFF Katmanı (Gaz Çekildiğinde Gelen Mekanik Kompresyon)
                new int[][]{
                        {R.raw.bmwz4gt3_offidle, 2000},
                        {R.raw.bmwz4gt3_offverylow, 3500},
                        {R.raw.bmwz4gt3_offlow, 5000},
                        {R.raw.bmwz4gt3_offmid, 6500},
                        {R.raw.bmwz4gt3_offhigh, 7800},
                        {R.raw.bmwz4gt3_offveryhigh, 8900}
                }
        );
    }
    public static VehicleProfile PROFILE_MAZDA_ROTOR() {
        return new VehicleProfile("Mazda 3-Rotor",
                1980f,
                9550f,
                1.0f,
                0,

                new float[][]{
                        {0f, 75f},
                        {8f, 125f},
                        {12f, 175f},
                        {18f, 225f},
                        {22f, 275f},
                        {26f, 320f},
                        {30f, 390f}   // 7. VİTES (OVERDRIVE)
                },
                0.18f, 0.08f,200,180f,
                R.raw.mazda3rotor_startup,
                0,
                new int[][]{
                        {R.raw.mazda3rotor_idle, 1980},
                        {R.raw.mazda3rotor_onverylow, 3000},
                        {R.raw.mazda3rotor_onlow, 4500},
                        {R.raw.mazda3rotor_onlowmid, 6000},
                        {R.raw.mazda3rotor_onmid, 7500},
                        {R.raw.mazda3rotor_onhigh, 9200},
                        {R.raw.mazda3rotor_limiter, 9550}
                },
                // OFF Katmanı (Gaz Çekildiğinde Gelen Egzantrik Kompresyon)
                new int[][]{
                        {R.raw.mazda3rotor_idle, 1980},
                        {R.raw.mazda3rotor_offverylow_1, 3000},
                        {R.raw.mazda3rotor_offlow, 4500},
                        {R.raw.mazda3rotor_offlowmid, 6000},
                        {R.raw.mazda3rotor_offmid, 7500},
                        {R.raw.mazda3rotor_offhigh, 9550}
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
                        {0f, 76f},    // F1 aracı 76'da anında kesiciye girer
                        {10f, 110f},
                        {15f, 160f},
                        {20f, 215f},
                        {25f, 255f},
                        {30f, 290f},
                        {35f, 335f}   // 7. Vites Son Hız (Sürekli Bağırtı)
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
    public static VehicleProfile PROFILE_GTRR34() {
        return new VehicleProfile("GTR R34", 1000f, 8200, 0.5f,
                1,
                new float[][]{
                        {0f, 70f},
                        {5f, 115f},
                        {10f, 160f},
                        {15f, 210f},
                        {20f, 260f},
                        {25f, 310f},  // 6. Vites
                        {30f, 380f}   // 7. VİTES (OVERDRIVE)
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
                samples.add(new EngineSample(map[1], map[0])); // map[1] = RPM, map[0] = resId
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
        if (mActiveProfile.hasTurbo == 1) extras++;
        if (mActiveProfile.startSoundResId != 0) extras++;
        if (mActiveProfile.stopSoundResId != 0) extras++;

        final int finalExtras = extras;

        int maxStreams = baseStreams + finalExtras + 5;

        boolean useMedia = false;
        try {
            SharedPreferences prefs = mContext.getSharedPreferences("mg4_v3", Context.MODE_PRIVATE);
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
        mTurboSoundId = mSoundPool.load(mContext, (mActiveProfile.hasTurbo == 2) ? R.raw.supercharge : R.raw.turbo, 1);
        mGearWhineSoundId = mSoundPool.load(mContext, R.raw.transmission, 1);
        mSubwaveSoundId = mSoundPool.load(mContext, R.raw.dp_in_subwave, 1);
        if (mActiveProfile.hasTurbo == 1) mFlutterSoundId = mSoundPool.load(mContext, R.raw.blowoff2, 1); else mFlutterSoundId = -1;
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
                mCurrentDcPowerKw = dcPowerKw < 5f ? 0f : dcPowerKw;
            } else {
                float dcVolt = MG4Hardware.getDcVoltGlobal();
                float dcAmpAct = MG4Hardware.getDcAmpGlobal();
                mCurrentDcPowerKw = (Float.isNaN(dcVolt) || Float.isNaN(dcAmpAct)) ? 0f : (dcVolt * dcAmpAct) / 1000f;
                mCurrentDcPowerKw = mCurrentDcPowerKw < 5f ? 0f : mCurrentDcPowerKw;
            }
            mSimulatedThrottle = Math.min(1f, Math.max(0f, (mCurrentDcPowerKw / mMotorMaxPower)));
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

        if (mFlutterSoundId != -1) {
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
                float boostFactor = Math.max(0f, Math.min(1f, (rpmRatio > 0.10f) ? (rpmRatio - 0.10f) * 1.12f : 0f));
                mCurrentTurboBoost = (mCurrentTurboBoost * 0.90f) + ((mSimulatedThrottle * boostFactor) * 0.10f);
                mSoundPool.setVolume(mTurboStreamId, mCurrentTurboBoost * masterVol * mTurboMaxSound, mCurrentTurboBoost * masterVol * mTurboMaxSound);
                mSoundPool.setRate(mTurboStreamId, 0.8f + (rpmRatio * 1.2f));
            } else if(mActiveProfile.hasTurbo == 2) {
                float compVol = (0.2f + (mSimulatedThrottle * 0.8f)) * rpmRatio * masterVol * mCompressorMaxVol;
                mSoundPool.setVolume(mTurboStreamId, compVol, compVol);
                mSoundPool.setRate(mTurboStreamId, 0.8f + (rpmRatio * 1.7f));
            }
        }
        // --- SUBWAVE (ALT BAS / GÖĞÜS TİTRETEN TOKLUK) KONTROLÜ ---
        if (mSubwaveStreamId != -1) {
            if (mSubwaveEnabled) {
                // Şalter AÇIKSA: Motora yük bindikçe bas katmanı belirginleşir
                float loadFactor = 0.4f + (mSimulatedThrottle * 0.6f);
                float subVolume = masterVol * loadFactor * 1.5f; // Bas çarpanı

                // Derinliği korumak için pitch çok tizleşmemeli (0.6 - 1.3 arası)
                float subPitch = 0.6f + (rpmRatio * 0.7f);

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
            float onWeight = (float) Math.sqrt(mSimulatedThrottle);
            float offWeight = (float) Math.sqrt(Math.max(0f, 1.0f - mSimulatedThrottle));

            // DÜZELTME: Kısılmış masterVol değerini de fonksiyona yolluyoruz
            processLayer(mCurrentSamplesOn, rpm, onWeight * masterVol, masterVol, true);
            processLayer(mCurrentSamplesOff, rpm, offWeight * masterVol, masterVol, false);

        } else {
            // SİNGLE LAYER MİKSERİ (Eski hız sıfırsa rölantiye kilitleyen blok TAMAMEN silindi)
            EngineSample lower = mCurrentSamples[0], upper = mCurrentSamples[1];
            if (rpm >= mCurrentSamples[mCurrentSamples.length - 1].baseRpm) {
                lower = mCurrentSamples[mCurrentSamples.length - 1];
                upper = lower;
            } else {
                for (int i = 0; i < mCurrentSamples.length - 1; i++) {
                    if (rpm >= mCurrentSamples[i].baseRpm && rpm <= mCurrentSamples[i+1].baseRpm) {
                        lower = mCurrentSamples[i]; upper = mCurrentSamples[i+1]; break;
                    }
                }
            }
            float rpmDiff = upper.baseRpm - lower.baseRpm;
            float blend = Math.max(0f, Math.min(1f, (rpmDiff <= 0) ? 0 : (rpm - lower.baseRpm) / rpmDiff));

            for (int i = 0; i < mCurrentSamples.length; i++) {
                EngineSample s = mCurrentSamples[i];
                if (s.streamId == -1) continue;
                float rawVol = (s == lower) ? (1f - blend) : ((s == upper) ? blend : 0f);
                float shapedVol = (float) Math.sqrt(rawVol);
                if (Float.isNaN(shapedVol)) shapedVol = 0f;
                if (s == mCurrentSamples[0]) shapedVol *= mCurrentIdleVolumeScale;

                float loadVolumeFactor = 0.5f + (mSimulatedThrottle * 0.5f);
                float loadPitchFactor = 0.98f + (mSimulatedThrottle * 0.04f);
                float modeVolumeBoost = (mDriveModeAggressiveness > 0.5f) ? 1.2f : 1.0f;

                float finalVolume = Math.max(0.0f, Math.min(1.0f, shapedVol * masterVol * loadVolumeFactor * modeVolumeBoost));
                float pitch = Math.max(0.5f, Math.min(2.0f, (rpm / s.baseRpm) * loadPitchFactor));

                // Sadece araç dururken ve hiç gaz vermiyorken kullanıcı rölanti ayarını uygula
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
    private void processLayer(EngineSample[] layer, float rpm, float weightVol, float fadedMasterVol, boolean isOnLayer) {
        if (layer == null || layer.length == 0) return;

        EngineSample lower = layer[0];
        EngineSample upper = layer.length > 1 ? layer[1] : layer[0];

        if (rpm >= layer[layer.length - 1].baseRpm) {
            lower = layer[layer.length - 1];
            upper = lower;
        } else {
            for (int i = 0; i < layer.length - 1; i++) {
                if (rpm >= layer[i].baseRpm && rpm <= layer[i+1].baseRpm) {
                    lower = layer[i];
                    upper = layer[i+1];
                    break;
                }
            }
        }

        float rpmDiff = upper.baseRpm - lower.baseRpm;
        float blend = Math.max(0f, Math.min(1f, (rpmDiff <= 0) ? 0 : (rpm - lower.baseRpm) / rpmDiff));

        for (int i = 0; i < layer.length; i++) {
            EngineSample s = layer[i];
            if (s.streamId == -1) continue;

            float rawVol = (s == lower) ? (1f - blend) : ((s == upper) ? blend : 0f);
            float shapedVol = (float) Math.sqrt(rawVol);
            if (Float.isNaN(shapedVol)) shapedVol = 0f;
            if (s == layer[0]) shapedVol *= mCurrentIdleVolumeScale;

            float finalVolume = shapedVol * weightVol;
            float pitch = rpm / s.baseRpm;

            if (isOnLayer) pitch *= (0.98f + (mSimulatedThrottle * 0.04f));
            pitch = Math.max(0.6f, Math.min(1.8f, pitch));

            // HATALI KİLİT BURADAN TAMAMEN SİLİNDİ!
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