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

    private float mCurrentSpeedKmh = 0f;
    private float mSimulatedThrottle = 0f;
    private int mCurrentGear = 0;
    private float mCurrentRpm = 1000f;

    // --- MEVCUT ARAÇ VERİLERİ ---
    private EngineSample[] mCurrentSamples;
    private float mIdleRpm = 1000f;
    private float mCurrentIdleVolumeScale = 1;
    private float mMaxRpm = 9000f;
    private Gearbox mActiveGearbox;
    private float mMasterVolume = 0.6f;

    public enum SoundMode { VIRTUAL_GEAR_V2 }
    public enum GearProfile { CRUISER_4, SPORT_6, RALLY_8 }

    // --- İÇ SINIFLAR ---
    public static class VehicleProfile {
        public final String name;
        public final int[] resIds;
        public final float idleRpm;
        public final float maxRpm;
        public final float idleVolumeScale;

        public VehicleProfile(String name, float idleRpm, float maxRpm, float idleVolumeScale, int... resIds) {
            this.name = name;
            this.idleRpm = idleRpm;
            this.maxRpm = maxRpm;
            this.idleVolumeScale = idleVolumeScale;
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
                R.raw.lfa_eng_idle,
                R.raw.lfa_exh_acc_3784,
                R.raw.lfa_exh_hi_detail_6301,
                R.raw.lfa_exh_hi_detail_7076,
                R.raw.lfa_exh_hi_detail_8135,
                R.raw.lfa_exh_acc_5333
        );
    }
    public static VehicleProfile PROFILE_MCLAREN_P1() {
        return new VehicleProfile("McLaren P1", 800f, 6000f, 1,
                R.raw.mclaren_p1_in_idle,
                R.raw.mclaren_p1_in_on_low_2000,
                R.raw.mclaren_p1_in_on_lowmid_b_4000,
                R.raw.mclaren_p1_in_on_high_b_2_5000,
                R.raw.mclaren_p1_in_on_veryhigh_b_6000
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

        this.mIdleRpm = profile.idleRpm;
        this.mMaxRpm = profile.maxRpm;
        this.mCurrentIdleVolumeScale = profile.idleVolumeScale;
        this.mCurrentSamples = buildSamples(profile.resIds);

        Log.i(TAG, "Profil yüklendi: " + profile.name);
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
        if (mCurrentSpeedKmh < 3f) {
            mCurrentGear = 0; mCurrentRpm = mIdleRpm; return;
        }

        int targetGear = 0;
        for (int i = 0; i < mActiveGearbox.maxSpeeds.length; i++) {
            if (mCurrentSpeedKmh <= mActiveGearbox.maxSpeeds[i]) { targetGear = i; break; }
        }
        // Histerezis ve Kickdown buraya eklenebilir (Önceki mantıkla aynı)
        mCurrentGear = targetGear;

        float minS = (mCurrentGear == 0) ? 0f : mActiveGearbox.maxSpeeds[mCurrentGear - 1];
        float maxS = mActiveGearbox.maxSpeeds[mCurrentGear];
        float ratio = Math.max(0f, Math.min(1f, (mCurrentSpeedKmh - minS) / (maxS - minS)));
        float baseRpm = (mCurrentGear == 0) ? mIdleRpm : 4500f;
        mCurrentRpm = baseRpm + (ratio * (mMaxRpm - baseRpm));
    }

    private void updateAudioMixer() {
        if (mSoundPool == null || mCurrentSamples == null || mLoadedSamplesCount < mCurrentSamples.length) return;

        float rpm = Math.max(mIdleRpm, Math.min(mCurrentRpm, mMaxRpm));
        float masterVol = mMasterVolume;

        // HIZ 1 KM/H ALTINDAYSA: TAM İZOLASYON MODU
        boolean strictlyIdle = (mCurrentSpeedKmh < 1.0f);

        if (strictlyIdle) {
            for (int i = 0; i < mCurrentSamples.length; i++) {
                EngineSample s = mCurrentSamples[i];
                if (s.streamId == -1) continue;

                if (i == 0) { // Sadece ilk dosya (Rölanti)
                    float finalVol = masterVol * mCurrentIdleVolumeScale;
                    mSoundPool.setVolume(s.streamId, finalVol, finalVol);
                    mSoundPool.setRate(s.streamId, 1.0f); // Orijinal hız/ton
                } else {
                    mSoundPool.setVolume(s.streamId, 0f, 0f); // Diğer her şeyi sustur
                }
            }
            return; // Fonksiyondan çık, aşağıdaki karmaşık matematiğe girme
        }

        // --- HAREKET HALİNDEYSE (ESKİ MATEMATİK DEVAM) ---
        EngineSample lower = mCurrentSamples[0];
        EngineSample upper = mCurrentSamples[mCurrentSamples.length - 1];

        for (int i = 0; i < mCurrentSamples.length - 1; i++) {
            if (rpm >= mCurrentSamples[i].baseRpm && rpm <= mCurrentSamples[i+1].baseRpm) {
                lower = mCurrentSamples[i];
                upper = mCurrentSamples[i+1];
                break;
            }
        }

        float blend = (upper.baseRpm == lower.baseRpm) ? 0 : (rpm - lower.baseRpm) / (upper.baseRpm - lower.baseRpm);

        for (EngineSample s : mCurrentSamples) {
            if (s.streamId == -1) continue;

            float vol = (s == lower) ? (1f - blend) : (s == upper ? blend : 0f);

            // Rölanti ölçeğini hareket halindeyken de uygula (geçiş yumuşak olsun diye)
            if (s == mCurrentSamples[0]) vol *= mCurrentIdleVolumeScale;

            float finalVolume = Math.max(0.0f, Math.min(1.0f, vol * masterVol));
            float pitch = Math.max(0.5f, Math.min(2.0f, rpm / s.baseRpm));
            mSoundPool.setVolume(s.streamId, finalVolume, finalVolume);
            mSoundPool.setRate(s.streamId, pitch);
        }
    }
    public boolean isPlaying() { return mIsPlaying; }
}