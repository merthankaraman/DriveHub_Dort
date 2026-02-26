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
    private float mSimulatedThrottle = 0f;
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

    // --- TURBO DEĞİŞKENLERİ ---
    private int mTurboSoundId = -1;
    private int mTurboStreamId = -1;
    private float mCurrentTurboBoost = 0f; // 0.0 - 1.0 arası iç basınç simülasyonu
    private float mTurboMaxSound = 0.7f;

    public enum SoundMode { VIRTUAL_GEAR_V2 }
    public static class VehicleProfile {
        public final String name;
        public final int[] resIds;
        public final float idleRpm;
        public final float maxRpm;
        public final float idleVolumeScale;
        // Her vitesin hız limitleri: { {vites1Min, vites1Max}, {vites2Min, vites2Max}, ... }
        public final float[][] gearRanges;
        public final boolean hasTurbo;

        public VehicleProfile(String name, float idleRpm, float maxRpm, float idleVolumeScale, boolean hasTurbo, float[][] gearRanges, int... resIds) {
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

    /** Manuel gaz kullanılıyor mu (sim paneli açıkken true). */
    public void setUseManualThrottle(boolean use) {
        mUseManualThrottle = use;
    }

    public float getCurrentRpm() {
        return mCurrentRpm;
    }

    private static final float VIRTUAL_GEAR_HYSTERESIS_KMH = 4f;

    // ==========================================
    // HAZIR ARAÇ TANIMLARI (STATİK)
    // ==========================================
    public static VehicleProfile PROFILE_LFA() {
        return new VehicleProfile("Lexus LFA", 1000f, 9500f, 1,
                false,
                new float[][]{
                        {0f, 60f},   // 1. Vites
                        {40f, 100f},  // 2. Vites
                        {70f, 140f},  // 3. Vites
                        {110f, 190f}, // 4. Vites
                        {160f, 250f}, // 5. Vites
                        {220f, 325f}  // 6. Vites
                },
                R.raw.lfa_idle,
                R.raw.lfa_3784,
                R.raw.lfa_6301,
                R.raw.lfa_7076,
                R.raw.lfa_8135,
                R.raw.lfa_5333
        );
    }
    public static VehicleProfile PROFILE_MCLAREN_P1() {
        return new VehicleProfile("McLaren P1", 800f, 9000f, 1,
                true,
                new float[][]{
                        {0f, 60f},   // 1. Vites
                        {40f, 100f},  // 2. Vites
                        {70f, 140f},  // 3. Vites
                        {110f, 190f}, // 4. Vites
                        {160f, 250f}, // 5. Vites
                        {220f, 325f}  // 6. Vites
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
        return new VehicleProfile("Lamborghini Aventador", 800f, 8500f, 1,
                false,
                new float[][]{
                        {0f, 75f},    // 1. Vites (V12'de 1. vites uzundur)
                        {50f, 115f},  // 2. Vites
                        {90f, 165f},  // 3. Vites
                        {130f, 220f}, // 4. Vites
                        {180f, 275f}, // 5. Vites
                        {240f, 320f}, // 6. Vites
                        {290f, 355f}  // 7. Vites (ISR Şanzıman)
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
                true,
                new float[][]{
                        {0f, 60f},   // 1. Vites
                        {40f, 100f},  // 2. Vites
                        {70f, 140f},  // 3. Vites
                        {110f, 190f}, // 4. Vites
                        {160f, 250f}, // 5. Vites
                        {220f, 325f}  // 6. Vites
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
                false,
                new float[][]{
                        {0f, 60f},   // 1. Vites
                        {40f, 100f},  // 2. Vites
                        {70f, 140f},  // 3. Vites
                        {110f, 190f}, // 4. Vites
                        {160f, 250f}, // 5. Vites
                        {220f, 325f}  // 6. Vites
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

        Log.i(TAG, "Profil yüklendi: " + profile.name);
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

        int maxStreams = mCurrentSamples.length + 3;

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
                // Turbo yüklendiğinde sonsuz döngüde ve 0 sesle başlat
                mTurboStreamId = mSoundPool.play(mTurboSoundId, 0f, 0f, 1, -1, 1.0f);
                return;
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
        mTurboSoundId = mSoundPool.load(mContext, R.raw.turbo, 1);
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
            mSimulatedThrottle = 0.5f;
        }

        updateGearAndRpm();
        updateAudioMixer();
    }

    private void updateGearAndRpm() {
        if (mActiveProfile == null) return;
        float speed = mCurrentSpeedKmh;
        float throttle = mSimulatedThrottle;

        if (speed < 1.0f) {
            mCurrentGear = 0;
            mCurrentRpm = mIdleRpm;
            return;
        }

        if (mCurrentGear < 1) mCurrentGear = 1;

        int targetGear = mCurrentGear;
        float[] currentRange = mActiveProfile.gearRanges[mCurrentGear - 1];
        float minV = currentRange[0];
        float maxV = currentRange[1];

        // --- KARARLILIK GÜNCELLEMESİ ---

        // 1. Vites Büyütme (Upshift) kararı için minimum bir eşik ekleyelim
        // Gazı bıraksan bile vitesin max hızının %85'inden önce vites büyütemesin.
        float upshiftMinLimit = maxV * 0.85f;
        float upshiftPoint = maxV * (0.85f + (throttle * 0.15f));

        if (speed > upshiftPoint && mCurrentGear < mActiveProfile.gearRanges.length) {
            targetGear++;
        }

        // 2. Vites Düşürme (Downshift) kararı
        // Araba 140'la giderken vites düşürüp RPM'i 1500'e çekmesini engellemek için
        // Mevcut vitesin min hızının altına düşmeden ASLA vites küçültme (kickdown hariç)
        if (mCurrentGear > 1) {
            float lowerGearMax = mActiveProfile.gearRanges[mCurrentGear - 2][1];

            // Kickdown: Sadece gaza çok basılırsa vites düşür
            boolean kickdownTrigger = (throttle > 0.85f && speed < lowerGearMax * 0.9f);
            // Koruma: Hız vitesin minimumunun altına düşerse (Stall protection)
            boolean engineStallProtect = (speed < minV * 0.95f);

            if (kickdownTrigger || engineStallProtect) {
                targetGear--;
            }
        }

        mCurrentGear = Math.min(Math.max(1, targetGear), mActiveProfile.gearRanges.length);

        // --- RPM HESAPLAMA (Daha Kararlı) ---
        float[] finalRange = mActiveProfile.gearRanges[mCurrentGear - 1];
        // Hızın vites içindeki yeri (Clamping yapıyoruz ki 1.0'ı geçmesin)
        float speedRatio = (speed - finalRange[0]) / (finalRange[1] - finalRange[0]);
        speedRatio = Math.max(0.1f, Math.min(1.0f, speedRatio));

        // dynamicMinRpm'i %10'dan az gazda rölantiye yakın tutalım
        float dynamicMinRpm;
        if (throttle < 0.10f) {
            dynamicMinRpm = mIdleRpm; // Gaz yoksa vitesin en düşük devrinde kalsın
        } else {
            dynamicMinRpm = mIdleRpm + (throttle * (mMaxRpm * mDriveModeAggressiveness));
        }

        // RPM'in çok hızlı düşmesini engellemek için mevcut RPM ile yeni RPM'i hafifçe harmanla (Smoothing)
        float targetRpm = dynamicMinRpm + (speedRatio * (mMaxRpm - dynamicMinRpm));
        mCurrentRpm = (mCurrentRpm * 0.8f) + (targetRpm * 0.2f); // %20 yumuşatma

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
            if (mActiveProfile == null || !mActiveProfile.hasTurbo) {
                mSoundPool.setVolume(mTurboStreamId, 0f, 0f);
                mCurrentTurboBoost = 0f;
            } else {
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

        EngineSample lower = mCurrentSamples[1];
        EngineSample upper = mCurrentSamples[mCurrentSamples.length - 1];

        for (int i = 1; i < mCurrentSamples.length - 1; i++) {
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

            if (i > 0) { // Rölanti (0) sürüşte hep kapalı
                rawVol = (s == lower) ? (1f - blend) : (s == upper ? blend : 0f);
            }

            // 2. SİHİRLİ DOKUNUŞ: Karekök alarak Eşit Güç eğrisine çevir
            // Bu, iki sesin birleştiği noktada toplam gücün sabit kalmasını sağlar ve "pıt pıt"ı bitirir.
            float shapedVol = (float) Math.sqrt(rawVol);
            if (Float.isNaN(shapedVol)) shapedVol = 0f;

            // 3. Rölanti ölçeğini uygula
            if (s == mCurrentSamples[0]) shapedVol *= mCurrentIdleVolumeScale;

            // 4. Master Volume uygula ve sınırla (Clamping)
            float finalVolume = Math.max(0.0f, Math.min(1.0f, shapedVol * masterVol));

            float pitch = (s.baseRpm <= 0) ? 1.0f : rpm / s.baseRpm;
            pitch = Math.max(0.5f, Math.min(2.0f, pitch));
            if (Float.isNaN(pitch)) pitch = 1.0f;

            mSoundPool.setVolume(s.streamId, finalVolume, finalVolume);
            mSoundPool.setRate(s.streamId, pitch);
        }
    }

    public boolean isPlaying() { return mIsPlaying; }
}