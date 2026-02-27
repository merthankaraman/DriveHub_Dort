package com.example.mg4_v3.audio;

import android.content.Context;
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
 * MG4 V3 Simülasyon Motoru (Assetto Corsa FMOD Mantığı)
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
    private float mMotorMaxPower = 130f;   // kW, pedal oranına map için üst sınır
    private float mCurrentDcPowerKw = 0f;  // (dcVolt * dcAmpAct) / 1000f
    private boolean mUseManualThrottle = false; // Sim panelinden gaz verildiğinde true
    private int mCurrentGear = 0;
    private float mCurrentRpm = 1000f;
    private float mDriveModeAggressiveness = 0.4f; // Varsayılan Normal
    private VehicleProfile mActiveProfile;
    private EngineSample[] mCurrentSamples;
    private float mIdleRpm = 1000f;
    private float mCurrentIdleVolumeScale = 1;
    private float mMaxRpm = 9000f;
    private float mMasterVolume = 0.6f;
    private int mGearWhineSoundId = -1;
    private int mGearWhineStreamId = -1;
    private float mGearWhineMaxVol = 0.15f;
    private float mWhineMaxSpeed = 200;

    // --- TURBO DEĞİŞKENLERİ ---
    private int mTurboSoundId = -1;
    private int mTurboStreamId = -1;
    private float mCurrentTurboBoost = 0f; // 0.0 - 1.0 arası iç basınç simülasyonu
    private float mTurboMaxSound = 0.5f;
    private boolean mEnableTurboSound = true;
    private float mCompressorMaxVol = 0.5f; // Kompresör sesi baskın olmalı
    private long mLastShiftTime = 0;
    private boolean mEnableRevMatch = true;
    private float mRevMatchBoost = 0f;
    private boolean mGearWhineEnabled = true;

    public enum SoundMode { VIRTUAL_GEAR_V2 }
    public static class VehicleProfile {
        public final String name;
        public final int[] resIds;
        public final float idleRpm;
        public final float maxRpm;
        public final float idleVolumeScale;
        // Her vitesin hız limitleri: { {vites1Min, vites1Max}, {vites2Min, vites2Max}, ... }
        public final float[][] gearRanges;
        public final int hasTurbo; //0:No, 1:YES, 2:Comppressor

        public VehicleProfile(String name, float idleRpm, float maxRpm, float idleVolumeScale, int hasTurbo, float[][] gearRanges, int... resIds) {
            this.name = name;
            this.idleRpm = idleRpm;
            this.maxRpm = maxRpm;
            this.idleVolumeScale = idleVolumeScale;
            this.hasTurbo = hasTurbo;
            this.gearRanges = gearRanges;
            this.resIds = resIds;
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

    public void setMasterVolume(float volume01) {
        mMasterVolume = Math.max(0f, Math.min(1f, volume01));
    }

    /** Ses karakteri: Eco=0.25, Normal=0.4, Sport=0.7 (vites devir davranışı). */
    public void setDriveModeAggressiveness(float aggressiveness01) {
        mDriveModeAggressiveness = Math.max(0f, Math.min(1f, aggressiveness01));
    }

    /** Simüle gaz pedalı (0–1). Manuel modda onSpeedChanged throttle'ı güncellemez. */
    public void setSimulatedThrottle(float throttle01) {
        mSimulatedThrottle = Math.max(0.01f, Math.min(1f, throttle01));
    }

    public float getSimulatedThrottle() {
        return mSimulatedThrottle;
    }

    public boolean isRevMatchEnabled() {
        return mEnableRevMatch;
    }

    public void setRevMatchEnabled(boolean enabled) {
        mEnableRevMatch = enabled;
    }

    public boolean isGearWhineEnabled() {
        return mGearWhineEnabled;
    }

    public void setGearWhineEnabled(boolean enabled) {
        mGearWhineEnabled = enabled;
    }

    /** Manuel gaz kullanılıyor mu (sim paneli açıkken true). */
    public void setUseManualThrottle(boolean use) {
        mUseManualThrottle = use;
    }

    public float getCurrentRpm() {
        return mCurrentRpm;
    }

    public int getCurrentGear() {
        return mCurrentGear;
    }

    // ==========================================
    // HAZIR ARAÇ TANIMLARI (STATİK)
    // ==========================================
    public static VehicleProfile PROFILE_LFA() {
        return new VehicleProfile("Lexus LFA", 1000f, 8300f, 1,
                0,
                new float[][]{
                        {0f, 30f},
                        {15f, 60f},
                        {20f, 80f},
                        {40f, 100f},
                        {60f, 140f},
                        {100f, 170f},
                        {130f, 200f},
                        {140f, 250f},
                },
                R.raw.lfa_idle,
                R.raw.lfa_3784,
                R.raw.lfa_5333,
                R.raw.lfa_6301,
                R.raw.lfa_7076,
                R.raw.lfa_8135
        );
    }
    public static VehicleProfile PROFILE_LOTUS_EXIGE() {
        return new VehicleProfile("Lotus Exige", 800, 9000f, 1,
                2,
                new float[][]{
                        {0f, 35f},
                        {20f, 65f},
                        {45f, 95f},
                        {75f, 125f},
                        {105f, 155f},
                        {135f, 185f}
                },
                R.raw.lotus_exige_idle,
                R.raw.lotus_exige_3000,
                R.raw.lotus_exige_4750,
                R.raw.lotus_exige_4750,
                R.raw.lotus_exige_8115,
                R.raw.lotus_exige_9649
        );
    }
    public static VehicleProfile PROFILE_MCLAREN_P1() {
        return new VehicleProfile("McLaren P1", 800f, 9000f, 1,
                1,
                new float[][]{
                        {0f, 30f},
                        {15f, 60f},
                        {20f, 80f},
                        {40f, 100f},
                        {60f, 140f},
                        {100f, 170f},
                        {130f, 200f},
                        {140f, 250f},
                },
                R.raw.mclaren_p1_idle,
                R.raw.mclaren_p1_1750,
                R.raw.mclaren_p1_2750,
                R.raw.mclaren_p1_4500,
                R.raw.mclaren_p1_6000,
                R.raw.mclaren_p1_7500,
                R.raw.mclaren_p1_9000
        );
    }
    public static VehicleProfile PROFILE_AVENTADOR() {
        return new VehicleProfile("Lamborghini Aventador", 800f, 8000f, 1,
                0,
                new float[][]{
                        {0f, 30f},
                        {15f, 60f},
                        {20f, 80f},
                        {40f, 100f},
                        {60f, 140f},
                        {100f, 170f},
                        {130f, 200f},
                        {140f, 250f},
                },
                R.raw.lamborghini_aventador_idle,
                R.raw.lamborghini_aventador_2500,
                R.raw.lamborghini_aventador_4500,
                R.raw.lamborghini_aventador_6500,
                R.raw.lamborghini_aventador_8250
        );
    }
    public static VehicleProfile PROFILE_BMW_Z4() {
        return new VehicleProfile("BMW Z4", 800f, 6836, 1,
                1,
                new float[][]{
                        {0f, 30f},
                        {15f, 60f},
                        {20f, 80f},
                        {40f, 100f},
                        {60f, 140f},
                        {100f, 170f},
                        {130f, 200f},
                        {140f, 250f},
                },
                R.raw.bmw_z4_idle,
                R.raw.bmw_z4_2800,
                R.raw.bmw_z4_4000,
                R.raw.bmw_z4_6000,
                R.raw.bmw_z4_6029,
                R.raw.bmw_z4_6836
        );
    }
    public static VehicleProfile PROFILE_ZONDA_R() {
        return new VehicleProfile("Pagani Zonda R", 1000f, 8250, 1,
                0,
                new float[][]{
                        {0f, 30f},
                        {15f, 60f},
                        {20f, 80f},
                        {40f, 100f},
                        {60f, 140f},
                        {100f, 170f},
                        {130f, 200f},
                        {140f, 250f},
                },
                R.raw.zonda_r_idle,
                R.raw.zonda_r_3500,
                R.raw.zonda_r_4250,
                R.raw.zonda_r_5000,
                R.raw.zonda_r_5500,
                R.raw.zonda_r_6250,
                R.raw.zonda_r_7000,
                R.raw.zonda_r_8250
        );
    }

    // ==========================================
    // CONSTRUCTOR & INSTANCE
    // ==========================================
    private EngineSoundManager(Context context) {
        this.mContext = context.getApplicationContext();
        this.mHandler = new Handler(Looper.getMainLooper());
    }

    public static synchronized EngineSoundManager getInstance(Context context) {
        if (sInstance == null) sInstance = new EngineSoundManager(context);
        return sInstance;
    }

    // ==========================================
    // MODÜLER AYARLAR (DIŞARIDAN ÇAĞRILIR)
    // ==========================================
    public void setVehicleProfile(VehicleProfile profile) {
        boolean wasPlaying = mIsPlaying;
        if (mIsPlaying) stop();

        this.mActiveProfile = profile; // Kritik: Artık fizik motoru hangi aracı kullandığını biliyor.
        this.mIdleRpm = profile.idleRpm;
        this.mMaxRpm = profile.maxRpm;
        this.mCurrentIdleVolumeScale = profile.idleVolumeScale;
        this.mCurrentSamples = buildSamples(profile.resIds);

        if (MG4Hardware.isLogEnabled()) {
            Log.i(TAG, "Profil yüklendi: " + profile.name);
        }
        mCurrentGear = 0; // Vitesi rölantiye çek
        if (wasPlaying) start();
    }

    // ==========================================
    // SES MOTORU (START / STOP)
    // ==========================================
    public void start() {
        if (mIsPlaying || mCurrentSamples == null) return;
        mIsPlaying = true;
        mLoadedSamplesCount = 0;

        int maxStreams = mCurrentSamples.length + 5;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            mSoundPool = new SoundPool.Builder().setMaxStreams(maxStreams).setAudioAttributes(attrs).build();
        } else {
            mSoundPool = new SoundPool(maxStreams, AudioManager.STREAM_MUSIC, 0);
        }

        mSoundPool.setOnLoadCompleteListener((soundPool, sampleId, status) -> {
            if (sampleId == mTurboSoundId) {
                mTurboStreamId = mSoundPool.play(mTurboSoundId, 0f, 0f, 1, -1, 1.0f);
                return;
            }
            if (sampleId == mGearWhineSoundId) {
                mGearWhineStreamId = mSoundPool.play(mGearWhineSoundId, 0f, 0f, 1, -1, 1.0f);
            }
            mLoadedSamplesCount++;
            if (mLoadedSamplesCount == mCurrentSamples.length && mIsPlaying) {
                for (EngineSample sample : mCurrentSamples) {
                    sample.streamId = mSoundPool.play(sample.soundId, 0f, 0f, 1, -1, 1.0f);
                }
                onSpeedChanged(mCurrentSpeedKmh);
            }
        });

        for (EngineSample sample : mCurrentSamples) {
            sample.soundId = mSoundPool.load(mContext, sample.resourceId, 1);
        }
        if (mActiveProfile.hasTurbo == 2)
            mTurboSoundId = mSoundPool.load(mContext, R.raw.compressor, 1);
        else
            mTurboSoundId = mSoundPool.load(mContext, R.raw.turbo, 1);
        mGearWhineSoundId = mSoundPool.load(mContext, R.raw.transmission, 1);

    }

    public void stop() {
        mIsPlaying = false;
        if (mSoundPool != null) {
            mSoundPool.release();
            mSoundPool = null;
        }
        if (mCurrentSamples != null) {
            for (EngineSample s : mCurrentSamples) s.streamId = -1;
        }
        mTurboStreamId = -1; // Turbo sıfırla
    }

    // ==========================================
    // YARDIMCI METODLAR (RPM PARSER)
    // ==========================================
    private EngineSample[] buildSamples(int... resourceIds) {
        List<EngineSample> samples = new ArrayList<>();
        for (int id : resourceIds) {
            String fileName = mContext.getResources().getResourceEntryName(id);
            int rpm = 1000;
            if (!fileName.contains("idle")) {
                String[] parts = fileName.split("_");
                try {
                    rpm = Integer.parseInt(parts[parts.length - 1]);
                } catch (Exception e) { rpm = 1000; }
            }
            samples.add(new EngineSample(rpm, id));
        }
        samples.sort((s1, s2) -> Integer.compare(s1.baseRpm, s2.baseRpm));
        return samples.toArray(new EngineSample[0]);
    }

    // ==========================================
    // TCU & MIXER (ANA MANTIK)
    // ==========================================
    public void onSpeedChanged(float speedKmh) {
        if (!mIsPlaying || mCurrentSamples == null || mLoadedSamplesCount < mCurrentSamples.length) return;

        mCurrentSpeedKmh = (Float.isNaN(speedKmh) || speedKmh < 0f) ? 0f : speedKmh;
        if (!mUseManualThrottle) {
            float dcVolt = MG4Hardware.getDcVoltage();
            float dcAmpAct = MG4Hardware.getDcCurrentActual();
            mCurrentDcPowerKw = (Float.isNaN(dcVolt) || Float.isNaN(dcAmpAct)) ? 0f : (dcVolt * dcAmpAct) / 1000f;
            mCurrentDcPowerKw = mCurrentDcPowerKw < 5f ? 0f : mCurrentDcPowerKw;
            mSimulatedThrottle = Math.min(1f,(Math.max(0f,(mCurrentDcPowerKw / mMotorMaxPower))));
        }

        updateGearAndRpm();
        updateAudioMixer();
    }

    private void updateGearAndRpm() {
        if (mActiveProfile == null) return;
        float speed = mCurrentSpeedKmh;
        float throttle = mSimulatedThrottle; // 0.01 - 1.0
        long currentTime = System.currentTimeMillis();

        if (speed < 1.0f) {
            mCurrentGear = 0;
            mCurrentRpm = mIdleRpm;
            return;
        }

        // 1. ADIM: Sürücünün İstediği "İdeal Devir" (Target RPM)
        // Gaz %10 ise -> 2500 RPM civarı (Ekonomik)
        // Gaz %50 ise -> 5000 RPM civarı (Orta/Canlı)
        // Gaz %100 ise -> 9000 RPM (Maksimum Güç)
        float targetRpmRange = mMaxRpm - mIdleRpm;
        float baseTarget = 1500f + (mDriveModeAggressiveness * 3500f);
        float desiredRpm = baseTarget + (throttle * (mMaxRpm - baseTarget));
        desiredRpm = Math.max(mIdleRpm, Math.min(mMaxRpm, desiredRpm));

        // 2. ADIM: Vites Kararı (Sadece 1 saniyede bir karar verir)
        if (currentTime - mLastShiftTime > 1200) {
            int bestGear = mCurrentGear > 0 ? mCurrentGear : 1;
            float bestRpmDiff = Float.MAX_VALUE;

            // Tüm vitesleri tara, hangisi bizi "desiredRpm"e en yakın tutuyor?
            for (int g = 1; g <= mActiveProfile.gearRanges.length; g++) {
                float[] range = mActiveProfile.gearRanges[g - 1];

                // Eğer hız bu vitesin limitleri dışındaysa bu vitesi geç (Toleranslı)
                if (speed < range[0] * 1.2f || speed > range[1] * 1.05f) continue;

                // Bu vitesteki tahmini devri hesapla
                float ratio = (speed - range[0]) / (range[1] - range[0]);
                float estimatedRpm = mIdleRpm + (ratio * targetRpmRange);

                float diff = Math.abs(estimatedRpm - desiredRpm);

                // Vites büyütme eğilimi (Histerezis):
                // Mevcut vitesten memnunsa, çok büyük fark yoksa vites değiştirme
                float threshold = (g > mCurrentGear) ? (1000f + mDriveModeAggressiveness * 6000f) : 800f;

                if (diff < bestRpmDiff - threshold) {
                    bestRpmDiff = diff;
                    bestGear = g;
                }
            }

            if (bestGear != mCurrentGear) {
                // --- VİTES KÜÇÜLTME (Downshift) ---
                if (bestGear < mCurrentGear) {
                    if (mEnableRevMatch) {
                        // 0.15f yerine daha kontrollü bir oran (Örn: 0.08f)
                        float aggressivenessFactor = 0.05f + (mDriveModeAggressiveness * 0.10f);
                        mRevMatchBoost = mMaxRpm * aggressivenessFactor;

                        // KORUMA: Eğer devir zaten çok yüksekse ara gazını kıs
                        if (mCurrentRpm > mMaxRpm * 0.8f) {
                            mRevMatchBoost *= 0.5f; // Kesiciye yakınken %50 azalt
                        }
                    } else {
                        // Rev-match kapalıysa sadece normal vites küçültme artışı yap
                        mCurrentRpm *= 1.10f;
                    }
                }
                // --- VİTES BÜYÜTME (Upshift) ---
                else {
                    mRevMatchBoost = 0;
                    mCurrentRpm *= 0.88f;
                }

                mLastShiftTime = currentTime;
                mCurrentGear = bestGear;
            }
        }

        // 3. ADIM: Mevcut Vitesteki Gerçek RPM'i Hesapla
        if (mCurrentGear > 0) {
            float[] finalRange = mActiveProfile.gearRanges[mCurrentGear - 1];
            float speedRatio = (speed - finalRange[0]) / (finalRange[1] - finalRange[0]);
            speedRatio = Math.max(0.05f, Math.min(0.95f, speedRatio));

            float dynamicMax = mIdleRpm + (targetRpmRange * (0.5f + throttle * 0.5f));
            float targetRpmFinal = mIdleRpm + (speedRatio * (dynamicMax - mIdleRpm));

            // 1. Önce ara gazı sıçramasını (boost) sönümlendir
            mRevMatchBoost *= 0.85f;
            if (mRevMatchBoost < 5f) mRevMatchBoost = 0;

            // 2. RPM Yumuşatması (Normal motor karakteri)
            mCurrentRpm = (mCurrentRpm * 0.85f) + (targetRpmFinal * 0.15f);

            // 3. Ara gazı etkisini final RPM'e ekle (Eğer açıksa)
            if (mEnableRevMatch && mRevMatchBoost > 0) {
                mCurrentRpm += mRevMatchBoost;
            }
        }

        mCurrentRpm = Math.max(mIdleRpm, Math.min(mCurrentRpm, mMaxRpm));
    }

    private void updateAudioMixer() {
        if (mSoundPool == null || mCurrentSamples == null || mLoadedSamplesCount < mCurrentSamples.length) return;

        long currentTime = System.currentTimeMillis();
        if (currentTime - mLastMixerUpdateTime < MIXER_UPDATE_INTERVAL_MS) return;
        mLastMixerUpdateTime = currentTime;

        float rpm = Math.max(mIdleRpm, Math.min(mCurrentRpm, mMaxRpm));
        float masterVol = Math.max(0f, Math.min(1.0f, mMasterVolume));
        float rpmRatio = (rpm - mIdleRpm) / (mMaxRpm - mIdleRpm);
        rpmRatio = Math.max(0f, Math.min(1f, rpmRatio));

        // --- TURBO HESAPLAMASI (Hızdan Bağımsız, RPM'e Tam Bağımlı) ---
        if (mTurboStreamId != -1) {
            if (mActiveProfile == null || (mActiveProfile.hasTurbo == 0)) {
                mSoundPool.setVolume(mTurboStreamId, 0f, 0f);
                mCurrentTurboBoost = 0f;
            } else if(mEnableTurboSound && mActiveProfile.hasTurbo == 1){
                float throttle = mSimulatedThrottle;

                // RPM 0.10 oranının altındaysa boost her zaman 0 olur (Fiziksel kural)
                float boostFactor = (rpmRatio > 0.10f) ? (rpmRatio - 0.10f) * 1.12f : 0f;
                boostFactor = Math.max(0f, Math.min(1f, boostFactor));

                float targetBoost = throttle * boostFactor;

                // Hız 0 olsa bile bu hesaplama çalışır, rölantide boost 0'a iner
                mCurrentTurboBoost = (mCurrentTurboBoost * 0.90f) + (targetBoost * 0.10f);

                float turboVol = mCurrentTurboBoost * masterVol * mTurboMaxSound;
                float turboPitch = 0.8f + (rpmRatio * 1.2f);

                mSoundPool.setVolume(mTurboStreamId, turboVol, turboVol);
                mSoundPool.setRate(mTurboStreamId, turboPitch);
            } else if(mEnableTurboSound && mActiveProfile.hasTurbo == 2){
                // 1. Ses Seviyesi: Gaz pedalıyla artar ama gaz bırakılınca da %20 duyulmaya devam eder
                float compVol = (0.2f + (mSimulatedThrottle * 0.8f)) * rpmRatio * masterVol * mCompressorMaxVol;

                // 2. Perde (Pitch): Kompresörler çok tizleşir.
                // 0.8'den başlasın, kesicide 2.5 (çok tiz) olsun.
                float compPitch = 0.8f + (rpmRatio * 1.7f);

                mSoundPool.setVolume(mTurboStreamId, compVol, compVol);
                mSoundPool.setRate(mTurboStreamId, compPitch);
            }
            else {
                mSoundPool.setVolume(mTurboStreamId, 0, 0);
                mSoundPool.setRate(mTurboStreamId, 1);
            }
        }
        if (mCurrentSpeedKmh < 1.0f) {
            for (int i = 0; i < mCurrentSamples.length; i++) {
                EngineSample s = mCurrentSamples[i];
                if (s.streamId == -1) continue;

                float targetVol = (i == 0) ? (masterVol * mCurrentIdleVolumeScale) : 0f;

                mSoundPool.setVolume(s.streamId, targetVol, targetVol);
                mSoundPool.setRate(s.streamId, 1.0f);
            }
            return;
        }

        EngineSample lower = mCurrentSamples[0];
        EngineSample upper = mCurrentSamples[1];

        for (int i = 0; i < mCurrentSamples.length - 1; i++) {
            if (rpm >= mCurrentSamples[i].baseRpm && rpm <= mCurrentSamples[i+1].baseRpm) {
                lower = mCurrentSamples[i];
                upper = mCurrentSamples[i+1];
                break;
            }
        }

        float rpmDiff = upper.baseRpm - lower.baseRpm;
        float blend = (rpmDiff <= 0) ? 0 : (rpm - lower.baseRpm) / rpmDiff;
        blend = Math.max(0f, Math.min(1f, blend));

        for (int i = 0; i < mCurrentSamples.length; i++) {
            EngineSample s = mCurrentSamples[i];
            if (s.streamId == -1) continue;
            float rawVol = 0f;

            if (s == lower) {
                rawVol = 1f - blend;
            } else if (s == upper) {
                rawVol = blend;
            }

            // 2. SİHİRLİ DOKUNUŞ: Karekök alarak Eşit Güç eğrisine çevir
            // Bu, iki sesin birleştiği noktada toplam gücün sabit kalmasını sağlar ve "pıt pıt"ı bitirir.
            float shapedVol = (float) Math.sqrt(rawVol);
            if (Float.isNaN(shapedVol)) shapedVol = 0f;

            // 3. Rölanti ölçeğini uygula
            if (s == mCurrentSamples[0]) shapedVol *= mCurrentIdleVolumeScale;

            // 1. YÜK HACMİ (Volume Load):
            // Gaz bırakıldığında ses tamamen ölmez ama daha derinden gelir.
            // Gaz %100 olduğunda ise tam kapasite çalar.
            float loadVolumeFactor = 0.5f + (mSimulatedThrottle * 0.5f);

            // 2. YÜK PERDESİ (Pitch Load):
            // Gaza basıldığında motorun "zorlanma" sesini taklit etmek için
            // pitch'i çok hafif (maksimum %3) yukarı esnetiyoruz.
            float loadPitchFactor = 0.98f + (mSimulatedThrottle * 0.04f);

            // Sport modunda motor sesi %20 daha yüksek ve ham gelir
            float modeVolumeBoost = (mDriveModeAggressiveness > 0.5f) ? 1.2f : 1.0f;
            float finalVolume = Math.max(0.0f, Math.min(1.0f, shapedVol * masterVol * loadVolumeFactor * modeVolumeBoost));
            float pitch = (rpm / s.baseRpm) * loadPitchFactor;

            pitch = Math.max(0.5f, Math.min(2.0f, pitch));
            if (Float.isNaN(pitch)) pitch = 1.0f;

            mSoundPool.setVolume(s.streamId, finalVolume, finalVolume);
            mSoundPool.setRate(s.streamId, pitch);
        }
        if (mGearWhineStreamId != -1 && mGearWhineEnabled) {
            float speedRatio = mCurrentSpeedKmh / mWhineMaxSpeed;
            speedRatio = Math.max(0f, Math.min(1f, speedRatio));

            // Ses seviyesi: Hızlandıkça artar, master volume ile çarpılır
            float whineVol = speedRatio * masterVol * mGearWhineMaxVol;
            float whinePitch = 0.5f + (speedRatio * 1.5f);

            mSoundPool.setVolume(mGearWhineStreamId, whineVol, whineVol);
            mSoundPool.setRate(mGearWhineStreamId, whinePitch);
        } else if(!mGearWhineEnabled){
            mSoundPool.setVolume(mGearWhineStreamId, 0, 0);
            mSoundPool.setRate(mGearWhineStreamId, 1);

        }
    }

    public boolean isPlaying() { return mIsPlaying; }
}