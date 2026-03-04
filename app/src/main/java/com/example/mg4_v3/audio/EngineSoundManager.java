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
    private String mCurrentProfileLabel = "Lotus Exige";

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
    private boolean mEnableTurboSound = true;
    private float mCompressorMaxVol = 0.5f;

    private long mLastShiftTime = 0;
    private boolean mEnableRevMatch = true;
    private float mRevMatchBoost = 0f;
    private boolean mGearWhineEnabled = true;

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
        String profile = prefs.getString("sound_profile", "Lotus Exige");
        applyProfileLabel(profile);
        loadIdleSettingsForProfile(context, profile);
        applySoundCharacterFromString(prefs.getString("sound_character", "NORMAL"));
        setMasterVolume(Math.max(0, Math.min(100, prefs.getInt("sound_master", 60))) / 100f);
    }

    public String getCurrentProfileName() { return mCurrentProfileLabel != null ? mCurrentProfileLabel : "Lotus Exige"; }
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
            "Lotus Exige",
            "Porsche GT3 997",
            "Lexus LFA",
            "Dodge Hellcat"
    };

    public static String[] getProfileLabels() { return PROFILE_LABELS; }

    public void applyProfileLabel(String profile) {
        mCurrentProfileLabel = (profile != null && !profile.isEmpty()) ? profile : "Lotus Exige";
        if ("Porsche GT3 997".equals(profile)) setVehicleProfile(PROFILE_PORSCHE_GT3());
        else if ("Lexus LFA".equals(profile)) setVehicleProfile(PROFILE_LEXUS_LFA());
        else if ("Dodge Hellcat".equals(profile)) setVehicleProfile(PROFILE_DODGE_HELLCAT());
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
        return new VehicleProfile("Lotus Exige", 800, 9000f, 1,
                2,
                new float[][]{
                        {0f, 40f},
                        {20f, 60f},
                        {40f, 80f},
                        {60f, 100f},
                        {70f, 110f},
                        {80f, 120f},
                        {100f, 160f},
                        {120f, 180f}
                },
                1f, 1f, 150, 150f, // Hafif ve atik (Moderate Wobble)
                0,0,
                new int[][]{
                        {R.raw.lotus_exige_idle, 800},
                        {R.raw.lotus_exige_3000, 3000},
                        {R.raw.lotus_exige_4750, 4750},
                        {R.raw.lotus_exige_8115, 8115},
                        {R.raw.lotus_exige_9649, 9000}
                },
                null
        );
    }
    public static VehicleProfile PROFILE_PORSCHE_GT3() {
        return new VehicleProfile("Porsche GT3 997", 2000f, 9400f, 0.7f,
                0,
                new float[][]{
                        {0f, 40f},
                        {20f, 60f},
                        {40f, 80f},
                        {60f, 100f},
                        {70f, 110f},
                        {80f, 120f},
                        {100f, 160f},
                        {120f, 180f}
                },
                1f, 1f, 110, 250f,
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
                984f,   // Orijinal Rölanti (IdleRPM)
                9550f,  // Orijinal Kesici (MaxRPM)
                0.6f,
                0,      // Atmosferik (Turbo yok)

                // LFA'nın SpeedPerThousandRPM değerlerinin 9.55 (Max RPM) ile çarpılmış gerçek vites hızları
                new float[][]{
                        {0f, 40f},
                        {20f, 60f},
                        {40f, 80f},
                        {60f, 100f},
                        {70f, 110f},
                        {80f, 120f},
                        {100f, 160f},
                        {120f, 180f}
                },

                // Orijinal LFA Fizik Karakteristiği
                1f,  // Çılgın bir devir yükselme hızı
                1f,  // Çok daha hızlı devir düşüşü (Hafif volan)
                200,      // 200ms Vites Geçişi (Tek kavramalı ASG şanzıman hissi)
                180f,     // 180 RPM Wobble (Porsche kadar sarsıntılı değil, daha pürüzsüz)

                // Marş ve İstop Sesleri
                R.raw.lfa_in_startup,
                R.raw.lfa_in_stop,

                // ON Katmanı (Gaza Basıldığında V10 Çığlığı)
                new int[][]{
                        {R.raw.lfa_in_onidle, 984},
                        {R.raw.lfa_in_onverylow_1, 2500},
                        {R.raw.lfa_in_onlow, 4500},
                        {R.raw.lfa_in_onmid, 6500},
                        {R.raw.lfa_in_onhigh, 8500},
                        {R.raw.lfa_in_limiter, 9550}
                },
                // OFF Katmanı (Gaz Çekildiğinde Gelen Yırtıcı Kompresyon)
                new int[][]{
                        {R.raw.lfa_in_offidle, 984},
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

                // txt'deki SpeedPerThousandRPM değerlerinin 6.1 (Max RPM) ile çarpılmış gerçek vites hızları [cite: 7, 8]
                new float[][]{
                        {0f, 40f},
                        {20f, 60f},
                        {40f, 80f},
                        {60f, 100f},
                        {70f, 110f},
                        {80f, 120f},
                        {100f, 160f},
                        {120f, 180f}
                },

                // Orijinal Hellcat Fizik Karakteristiği
                1f,  // İvmelenme hızı
                1f,  // Çok yavaş devir düşüşü (Ağır V8 volan hissi)
                110,      // 110ms Vites Geçişi (ZF 8 ileri otomatik hızında)
                250f,     // 250 RPM Wobble (Şiddetli şanzıman sarsıntısı)

                // Marş ve İstop Sesleri
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
        int extras = 2; // Turbo + Whine
        if (mActiveProfile.name.contains("R34")) extras++;
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

                // MARŞ SESİ BURADA OYNATILIYOR
                if (mStartSoundId != -1 && MG4Hardware.getLastGear() == 1) {
                    mSoundPool.play(mStartSoundId, mMasterVolume, mMasterVolume, 2, 0, 1.0f);
                    mEngineStartTime = System.currentTimeMillis(); // Marş süresince rölantiyi beklet (Fade-in)
                } else {
                    // Marş sesi yoksa VEYA vites P'de değilse (örn. yolda gidiyorsak):
                    // Marşı atla ve motor seslerini gecikmesiz, anında başlat!
                    mEngineStartTime = 0;
                }

                onSpeedChanged(mCurrentSpeedKmh);
            }
        });

        // Yardımcı sesleri yükle
        mTurboSoundId = mSoundPool.load(mContext, (mActiveProfile.hasTurbo == 2) ? R.raw.supercharge : R.raw.turbo, 1);
        mGearWhineSoundId = mSoundPool.load(mContext, R.raw.transmission, 1);
        if (mActiveProfile.name.contains("R34")) mFlutterSoundId = mSoundPool.load(mContext, R.raw.rb26_bf2, 1); else mFlutterSoundId = -1;
        if (mActiveProfile.startSoundResId != 0) mStartSoundId = mSoundPool.load(mContext, mActiveProfile.startSoundResId, 1); else mStartSoundId = -1;
        if (mActiveProfile.stopSoundResId != 0) mStopSoundId = mSoundPool.load(mContext, mActiveProfile.stopSoundResId, 1); else mStopSoundId = -1;

        // Motor seslerini yükle
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
            mCurrentRpm = mIdleRpm;
            return;
        }

        float targetRpmRange = mMaxRpm - mIdleRpm;
        float baseTarget = 1500f + (mDriveModeAggressiveness * 3500f);
        float desiredRpm = baseTarget + (throttle * (mMaxRpm - baseTarget));
        desiredRpm = Math.max(mIdleRpm, Math.min(mMaxRpm, desiredRpm));

        if (currentTime - mLastShiftTime > 1200) {
            int bestGear = mCurrentGear > 0 ? mCurrentGear : 1;
            float bestRpmDiff = Float.MAX_VALUE;

            for (int g = 1; g <= mActiveProfile.gearRanges.length; g++) {
                float[] range = mActiveProfile.gearRanges[g - 1];
                if (speed < range[0] * 1.2f || speed > range[1] * 1.05f) continue;

                float ratio = (speed - range[0]) / (range[1] - range[0]);
                float estimatedRpm = mIdleRpm + (ratio * targetRpmRange);
                float diff = Math.abs(estimatedRpm - desiredRpm);
                float threshold = (g > mCurrentGear) ? (1000f + mDriveModeAggressiveness * 6000f) : 800f;

                if (diff < bestRpmDiff - threshold) {
                    bestRpmDiff = diff;
                    bestGear = g;
                }
            }

            if (bestGear != mCurrentGear) {
                if (bestGear < mCurrentGear) {
                    if (mEnableRevMatch) {
                        float aggressivenessFactor = 0.05f + (mDriveModeAggressiveness * 0.10f);
                        mRevMatchBoost = mMaxRpm * aggressivenessFactor;
                        if (mCurrentRpm > mMaxRpm * 0.8f) mRevMatchBoost *= 0.5f;
                    } else mCurrentRpm *= 1.10f;
                } else {
                    mRevMatchBoost = 0;
                    mCurrentRpm *= 0.88f;
                }
                mLastShiftTime = currentTime;
                mCurrentGear = bestGear;
            }
        }

        if (mCurrentGear > 0) {
            float[] finalRange = mActiveProfile.gearRanges[mCurrentGear - 1];
            float speedRatio = (speed - finalRange[0]) / (finalRange[1] - finalRange[0]);
            speedRatio = Math.max(0.05f, Math.min(0.95f, speedRatio));

            float dynamicMax = mIdleRpm + (targetRpmRange * (0.5f + throttle * 0.5f));
            float targetRpmFinal = mIdleRpm + (speedRatio * (dynamicMax - mIdleRpm));

            mRevMatchBoost *= 0.85f;
            if (mRevMatchBoost < 5f) mRevMatchBoost = 0;

            float currentSmooth = (throttle > 0.05f) ? mActiveProfile.rpmOnSmooth : mActiveProfile.rpmOffSmooth;
            mCurrentRpm = (mCurrentRpm * (1.0f - currentSmooth)) + (targetRpmFinal * currentSmooth);

            if (mEnableRevMatch && mRevMatchBoost > 0) mCurrentRpm += mRevMatchBoost;

            long timeSinceShift = currentTime - mLastShiftTime;
            if (timeSinceShift < 500 && mActiveProfile.wobbleMagnitude > 0) {
                float timeSec = timeSinceShift / 1000f;
                float dampening = Math.max(0f, 1.0f - (timeSec / 0.5f));
                float wobbleOffset = (float) Math.sin(timeSec * 9.0f * Math.PI * 2) * mActiveProfile.wobbleMagnitude * dampening;
                mCurrentRpm += wobbleOffset;
            }
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
            if (timeSinceStart < mAutoStartupDelayMs) {
                startupFade = 0f; // Marş dönüyor, motor rölantisi sessiz!
            } else if (timeSinceStart < mAutoStartupDelayMs + 600) {
                startupFade = (timeSinceStart - mAutoStartupDelayMs) / 600f; // Marş bitince rölanti 600ms içinde "har" diye yükselsin
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
            } else if(mEnableTurboSound && mActiveProfile.hasTurbo == 1) {
                float boostFactor = Math.max(0f, Math.min(1f, (rpmRatio > 0.10f) ? (rpmRatio - 0.10f) * 1.12f : 0f));
                mCurrentTurboBoost = (mCurrentTurboBoost * 0.90f) + ((mSimulatedThrottle * boostFactor) * 0.10f);
                mSoundPool.setVolume(mTurboStreamId, mCurrentTurboBoost * masterVol * mTurboMaxSound, mCurrentTurboBoost * masterVol * mTurboMaxSound);
                mSoundPool.setRate(mTurboStreamId, 0.8f + (rpmRatio * 1.2f));
            } else if(mEnableTurboSound && mActiveProfile.hasTurbo == 2) {
                float compVol = (0.2f + (mSimulatedThrottle * 0.8f)) * rpmRatio * masterVol * mCompressorMaxVol;
                mSoundPool.setVolume(mTurboStreamId, compVol, compVol);
                mSoundPool.setRate(mTurboStreamId, 0.8f + (rpmRatio * 1.7f));
            }
        }

        // --- MİKSER: HİBRİT KARAR MEKANİZMASI ---
        // --- MİKSER: HİBRİT KARAR MEKANİZMASI ---
        if (mIsDualLayer) {
            float onWeight = (float) Math.sqrt(mSimulatedThrottle);
            float offWeight = (float) Math.sqrt(Math.max(0f, 1.0f - mSimulatedThrottle));

            // DÜZELTME: Kısılmış masterVol değerini de fonksiyona yolluyoruz
            processLayer(mCurrentSamplesOn, rpm, onWeight * masterVol, masterVol, true);
            processLayer(mCurrentSamplesOff, rpm, offWeight * masterVol, masterVol, false);

        } else {
            if (mCurrentSpeedKmh < 1.0f) {
                for (int i = 0; i < mCurrentSamples.length; i++) {
                    EngineSample s = mCurrentSamples[i];
                    if (s.streamId == -1) continue;
                    float targetVol = (i == 0) ? (masterVol * mCurrentIdleVolumeScale) : 0f;
                    mSoundPool.setVolume(s.streamId, targetVol, targetVol);
                    mSoundPool.setRate(s.streamId, mIdlePitch);
                }
            } else {
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

                    mSoundPool.setVolume(s.streamId, finalVolume, finalVolume);
                    mSoundPool.setRate(s.streamId, Float.isNaN(pitch) ? 1.0f : pitch);
                }
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

    /** YENİ SİSTEM İÇİN DUAL-LAYER KATMAN İŞLEYİCİ */
    // DÜZELTME: Parametrelere 'float fadedMasterVol' eklendi
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

            if (mCurrentSpeedKmh < 1.0f) {
                finalVolume = (i == 0) ? (fadedMasterVol * mCurrentIdleVolumeScale) : 0f;
                pitch = mIdlePitch;
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