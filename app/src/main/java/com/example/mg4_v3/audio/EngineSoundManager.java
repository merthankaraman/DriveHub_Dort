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
    private int mCurrentGear = 0;
    private float mCurrentRpm = 1000f;
    private float mDriveModeAggressiveness = 0.4f; // Varsayılan Normal
    private VehicleProfile mActiveProfile;
    private EngineSample[] mCurrentSamples;
    private float mIdleRpm = 1000f;
    private float mCurrentIdleVolumeScale = 1;
    private float mMaxRpm = 9000f;
    private Gearbox mActiveGearbox;
    private float mMasterVolume = 0.6f;

    public enum SoundMode { VIRTUAL_GEAR_V2 }
    public enum GearProfile { CRUISER_4, SPORT_6, RALLY_8 }
    public static class VehicleProfile {
        public final String name;
        public final int[] resIds;
        public final float idleRpm;
        public final float maxRpm;
        public final float idleVolumeScale;
        // Her vitesin hız limitleri: { {vites1Min, vites1Max}, {vites2Min, vites2Max}, ... }
        public final float[][] gearRanges;

        public VehicleProfile(String name, float idleRpm, float maxRpm, float idleVolumeScale, float[][] gearRanges, int... resIds) {
            this.name = name;
            this.idleRpm = idleRpm;
            this.maxRpm = maxRpm;
            this.idleVolumeScale = idleVolumeScale;
            this.gearRanges = gearRanges;
            this.resIds = resIds;
        }
    }

    private static class Gearbox {
        final float[] maxSpeeds;
        Gearbox(float... speeds) { this.maxSpeeds = speeds; }
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

    public float getCurrentRpm() {
        return mCurrentRpm;
    }

    // --- SABİT ŞANZIMANLAR ---
    private final Gearbox mGearSport6 = new Gearbox(20f, 45f, 75f, 110f, 140f, 160f);
    private final Gearbox mGearRally8 = new Gearbox(15f, 30f, 45f, 65f, 85f, 110f, 135f, 160f);
    private final Gearbox mGearCruiser4 = new Gearbox(35f, 80f, 125f, 160f);

    private static final float VIRTUAL_GEAR_HYSTERESIS_KMH = 4f;

    // ==========================================
    // HAZIR ARAÇ TANIMLARI (STATİK)
    // ==========================================
    public static VehicleProfile PROFILE_LFA() {
        return new VehicleProfile("Lexus LFA", 1000f, 9500f, 1,
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
        return new VehicleProfile("McLaren P1", 800f, 6000f, 1,
                new float[][]{
                        {0f, 60f},   // 1. Vites
                        {40f, 100f},  // 2. Vites
                        {70f, 140f},  // 3. Vites
                        {110f, 190f}, // 4. Vites
                        {160f, 250f}, // 5. Vites
                        {220f, 325f}  // 6. Vites
                },
                R.raw.mclaren_p1_idle,
                R.raw.mclaren_p1_2000,
                R.raw.mclaren_p1_4000,
                R.raw.mclaren_p1_5000,
                R.raw.mclaren_p1_6000
        );
    }
    public static VehicleProfile PROFILE_Lamborghini_Aventador() {
        return new VehicleProfile("Lamborghini Aventador", 800f, 8000f, 1,
                new float[][]{
                        {0f, 60f},   // 1. Vites
                        {40f, 100f},  // 2. Vites
                        {70f, 140f},  // 3. Vites
                        {110f, 190f}, // 4. Vites
                        {160f, 250f}, // 5. Vites
                        {220f, 325f}  // 6. Vites
                },
                R.raw.lamborghini_aventador_idle,
                R.raw.lamborghini_aventador_2000,
                R.raw.lamborghini_aventador_4000,
                R.raw.lamborghini_aventador_6000,
                R.raw.lamborghini_aventador_8000
        );
    }
    public static VehicleProfile PROFILE_BMW_Z4() {
        return new VehicleProfile("BMW Z4", 800f, 6836, 1,
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
        this.mActiveGearbox = mGearSport6; // Varsayılan şanzıman
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

    public void setGearProfile(GearProfile profile) {
        switch (profile) {
            case CRUISER_4: mActiveGearbox = mGearCruiser4; break;
            case RALLY_8:   mActiveGearbox = mGearRally8;   break;
            default:        mActiveGearbox = mGearSport6;   break;
        }
        mCurrentGear = 0;
    }

    // ==========================================
    // SES MOTORU (START / STOP)
    // ==========================================
    public void start() {
        if (mIsPlaying || mCurrentSamples == null) return;
        mIsPlaying = true;
        mLoadedSamplesCount = 0;

        int maxStreams = mCurrentSamples.length + 2;

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

        float speed = (Float.isNaN(speedKmh) || speedKmh < 0f) ? 0f : speedKmh;
        float delta = speed - mCurrentSpeedKmh;
        mSimulatedThrottle = (delta > 0.3f) ? 1.0f : (delta > 0f ? 0.5f : 0.0f);
        mCurrentSpeedKmh = speed;

        updateGearAndRpm();
        updateAudioMixer();
    }

    private void updateGearAndRpm() {
        if (mActiveProfile == null) return;
        float speed = mCurrentSpeedKmh;

        if (speed < 1.0f) {
            mCurrentGear = 0;
            mCurrentRpm = mIdleRpm;
            return;
        }

        // 1. Hangi vitesteyiz? (Histerezis ile)
        int targetGear = mCurrentGear;
        // Vites büyütme kontrolü
        if (mCurrentGear < mActiveProfile.gearRanges.length) {
            float currentGearMax = mActiveProfile.gearRanges[mCurrentGear - 1][1];
            if (speed > currentGearMax) targetGear++;
        }
        // Vites küçültme kontrolü
        if (mCurrentGear > 1) {
            float currentGearMin = mActiveProfile.gearRanges[mCurrentGear - 1][0];
            if (speed < currentGearMin) targetGear--;
        }

        if (targetGear == 0) targetGear = 1;
        mCurrentGear = targetGear;

        // 2. RPM Hesapla (Gaza Duyarlı)
        float[] range = mActiveProfile.gearRanges[mCurrentGear - 1];
        float minV = range[0];
        float maxV = range[1];

        // Hızın vites içindeki oranı (0.0 - 1.0)
        float speedRatio = (speed - minV) / (maxV - minV);
        speedRatio = Math.max(0f, Math.min(1f, speedRatio));

        // EKO SÜRÜŞ SİHİRİ:
        // Alt devir (vitesin başladığı devir) hıza göre değil, gaza göre değişsin.
        // Az gazda araba 2000 devirde mırıldansın, tam gazda 5000 devirden başlasın.
        float dynamicMinRpm = mIdleRpm + (mSimulatedThrottle * (mMaxRpm * mDriveModeAggressiveness));

        // Final RPM: Alt devir ile Max devir arasında hız oranına göre belirle
        mCurrentRpm = dynamicMinRpm + (speedRatio * (mMaxRpm - dynamicMinRpm));

        // Güvenlik sınırlaması
        mCurrentRpm = Math.max(mIdleRpm, Math.min(mCurrentRpm, mMaxRpm));
    }

    private void updateAudioMixer() {
        if (mSoundPool == null || mCurrentSamples == null || mLoadedSamplesCount < mCurrentSamples.length) return;

        long currentTime = System.currentTimeMillis();
        if (currentTime - mLastMixerUpdateTime < MIXER_UPDATE_INTERVAL_MS) return;
        mLastMixerUpdateTime = currentTime;

        float rpm = Math.max(mIdleRpm, Math.min(mCurrentRpm, mMaxRpm));
        float masterVol = Math.max(0f, Math.min(1.0f, mMasterVolume));

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
    /**
     * Ham (matematiksel) vitesi bulur.
     */
    private int getRawGear(float speedKmh) {
        for (int i = 0; i < mActiveGearbox.maxSpeeds.length; i++) {
            if (speedKmh <= mActiveGearbox.maxSpeeds[i]) {
                return i;
            }
        }
        return mActiveGearbox.maxSpeeds.length - 1; // Maksimum vites
    }

    /**
     * Histerezis (Tolerans) ile vites seçer.
     * Vites sınırında sesin sürekli gidip gelmesini (Gear Hunting) önler.
     */
    private int getGearWithHysteresis(float speedKmh, int currentGear) {
        int rawGear = getRawGear(speedKmh);

        // Eğer henüz vites atanmamışsa ham vitesi döndür
        if (currentGear < 0) return rawGear;

        if (rawGear > currentGear) {
            // YUKARI VİTES: Yeni vitesin hızını histerezis kadar "net" geçmeli
            float newGearMinSpeed = mActiveGearbox.maxSpeeds[rawGear - 1];
            if (speedKmh >= newGearMinSpeed + VIRTUAL_GEAR_HYSTERESIS_KMH) {
                return rawGear;
            }
            return currentGear; // Sınırı tam geçemediği için eski viteste tut
        }
        else if (rawGear < currentGear) {
            // AŞAĞI VİTES: Mevcut vitesin hızının histerezis kadar altına düşmeli
            float currentGearMinSpeed = mActiveGearbox.maxSpeeds[currentGear - 1];
            if (speedKmh <= currentGearMinSpeed - VIRTUAL_GEAR_HYSTERESIS_KMH) {
                return rawGear;
            }
            return currentGear;
        }

        return currentGear; // Zaten aynı vitesteyiz
    }

    public boolean isPlaying() { return mIsPlaying; }
}