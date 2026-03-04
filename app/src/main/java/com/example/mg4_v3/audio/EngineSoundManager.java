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
    /** Dialog’da kayıtlı profil etiketi (sound_profile ile aynı). Rölanti prefs anahtarı bununla oluşturulur. */
    private String mCurrentProfileLabel = "Lotus Exige";
    private EngineSample[] mCurrentSamples;
    private float mIdleRpm = 1000f;
    private float mCurrentIdleVolumeScale = 1;
    /** Kullanıcı ayarı: rölanti ses seviyesi çarpanı (0..1). Profil idleVolumeScale ile çarpılır. */
    private float mUserIdleVolumeScale = 1f;
    /** Kullanıcı ayarı: rölanti pitch (0.5–2.0, 1 = orijinal). */
    private float mIdlePitch = 1f;
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
    private float mTurboMaxSound = 0.3f;
    private boolean mEnableTurboSound = true;
    private float mCompressorMaxVol = 0.5f; // Kompresör sesi baskın olmalı
    private long mLastShiftTime = 0;
    private boolean mEnableRevMatch = true;
    private float mRevMatchBoost = 0f;
    private boolean mGearWhineEnabled = true;
    // --- FLUTTER (STUTUTU) DEĞİŞKENLERİ ---
    private int mFlutterSoundId = -1;
    private int mFlutterStreamId = -1;
    private float mCurrentFlutterVol = 0f; // Anlık flutter ses seviyesi
    private float mLastThrottleForFlutter = 0f;

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

        // --- YENİ EKLENEN FİZİK PARAMETRELERİ (RevHeadz Spec) ---
        public final float rpmOnSmooth;      // Gaza basınca devir ivmelenme hızı
        public final float rpmOffSmooth;     // Gazı çekince devir düşme hızı
        public final long shiftDurationMs;   // Vites geçiş/patlama süresi
        public final float wobbleMagnitude;  // Vites atınca devir saatinin sarsılma şiddeti

        public VehicleProfile(String name, float idleRpm, float maxRpm, float idleVolumeScale, int hasTurbo, float[][] gearRanges,
                              float rpmOnSmooth, float rpmOffSmooth, long shiftDurationMs, float wobbleMagnitude,
                              int... resIds) {
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

    public void setMotorMaxPower(float powerKw) {
        mMotorMaxPower = (Math.max(50f, Math.min(200f, powerKw)) - 20f);
    }

    /**
     * SharedPreferences içinden ses profilini, şanzıman karakterini ve master volume'ü yükler.
     * Böylece profil eşleme mantığı tek yerde tutulur.
     */
    public void initFromPreferences(Context context) {
        android.content.SharedPreferences prefs =
                context.getSharedPreferences("mg4_v3", Context.MODE_PRIVATE);

        // Ses profili (araç sesi)
        String profile = prefs.getString("sound_profile", "Lotus Exige");
        applyProfileLabel(profile);
        loadIdleSettingsForProfile(context, profile);

        // Şanzıman karakteri (Eco / Normal / Sport / Araç) — mapping tek yerde dursun
        String charStr = prefs.getString("sound_character", "NORMAL");
        applySoundCharacterFromString(charStr);

        // Master volume
        int master = prefs.getInt("sound_master", 60);
        float masterClamped = Math.max(0, Math.min(100, master)) / 100f;
        setMasterVolume(masterClamped);
    }

    /** Seçili ses profilinin etiketi (dialog ile aynı; rölanti prefs anahtarı bununla eşleşir). */
    public String getCurrentProfileName() {
        return mCurrentProfileLabel != null ? mCurrentProfileLabel : "Lotus Exige";
    }

    /** Profil adından prefs anahtar soneki (boşluk → alt çizgi; bazı cihazlarda boşluklu anahtar sorun çıkarabiliyor). */
    public static String profileToPrefsSuffix(String profileName) {
        return profileName != null ? profileName.replace(" ", "_") : "Lotus_Exige";
    }

    /** Verilen araç profilinin rölanti ayarlarını yükler (hafızalı, araç özelinde). */
    public void loadIdleSettingsForProfile(Context context, String profileName) {
        if (context == null || profileName == null) return;
        android.content.SharedPreferences prefs = context.getSharedPreferences("mg4_v3", Context.MODE_PRIVATE);
        String suffix = profileToPrefsSuffix(profileName);
        String volKey = "idle_volume_scale_" + suffix;
        String pitchKey = "idle_pitch_" + suffix;
        String volKeyOld = "idle_volume_scale_" + profileName;
        String pitchKeyOld = "idle_pitch_" + profileName;
        int vol = prefs.contains(volKey) ? prefs.getInt(volKey, 100)
                : prefs.getInt(volKeyOld, prefs.getInt("idle_volume_scale", 100));
        float pitch = prefs.contains(pitchKey) ? prefs.getFloat(pitchKey, 1f)
                : prefs.getFloat(pitchKeyOld, prefs.getFloat("idle_pitch", 1f));
        setUserIdleVolumeScale(Math.max(0, Math.min(100, vol)) / 100f);
        setIdlePitch(Math.max(0.5f, Math.min(2f, pitch)));
        if (prefs.contains(volKeyOld) || prefs.contains(pitchKeyOld)) {
            prefs.edit().putInt(volKey, vol).putFloat(pitchKey, pitch).remove(volKeyOld).remove(pitchKeyOld).apply();
        }
    }

    /** Rölanti ses seviyesi çarpanı (0..1). Profil değişince tekrar uygulanır. */
    public void setUserIdleVolumeScale(float scale01) {
        mUserIdleVolumeScale = Math.max(0f, Math.min(1f, scale01));
        if (mActiveProfile != null) {
            mCurrentIdleVolumeScale = mActiveProfile.idleVolumeScale * mUserIdleVolumeScale;
        }
    }

    public float getUserIdleVolumeScale() {
        return mUserIdleVolumeScale;
    }

    /** Rölanti pitch (0.5–2.0). 1 = orijinal perde. */
    public void setIdlePitch(float pitch) {
        mIdlePitch = Math.max(0.5f, Math.min(2f, pitch));
    }

    public float getIdlePitch() {
        return mIdlePitch;
    }

    /**
     * Verilen karakter string'ine göre şanzıman agresifliğini belirler.
     * ECO/NORMAL/SPORT/AUTO mapping'ini tek yerde toplar.
     */
    public void applySoundCharacterFromString(String character) {
        float agg = 0.4f;
        if ("ECO".equals(character)) {
            agg = 0.25f;
        } else if ("SPORT".equals(character)) {
            agg = 0.7f;
        } else if ("AUTO".equals(character)) {
            int modeVal = MG4Hardware.getDriveMode();
            com.example.mg4_v3.model.DriveMode dm =
                    com.example.mg4_v3.model.DriveMode.fromValue(modeVal);
            switch (dm) {
                case ECO:    agg = 0.25f; break;
                case SPORT:  agg = 0.7f;  break;
                case SNOW:
                case NORMAL:
                case CUSTOM:
                default:     agg = 0.4f;  break;
            }
        }
        setDriveModeAggressiveness(agg);
    }

    /**
     * Araç sesi profil listesi (tek kaynak — yeni araç eklerken sadece buraya ekleyin).
     * Sıra = seçim dialog'undaki sıra. İsimler applyProfileLabel ile birebir eşleşmeli.
     */
    public static final String[] PROFILE_LABELS = {
            "McLaren P1",
            "Lamborghini Aventador",
            "BMW Z4",
            "Pagani Zonda R",
            "Lotus Exige",
            "GTR R34",
    };

    /** Dialog ve liste için profil isimlerini döner (hafıza sıfırlanmaz, sadece liste tek yerde). */
    public static String[] getProfileLabels() {
        return PROFILE_LABELS;
    }

    /**
     * Verilen profil etiketine göre uygun VehicleProfile'ı seçer.
     * Activity ve Service tarafı aynı mapping'i paylaşsın diye burada tutuluyor.
     */
    public void applyProfileLabel(String profile) {
        mCurrentProfileLabel = (profile != null && !profile.isEmpty()) ? profile : "Lotus Exige";
        if ("McLaren P1".equals(profile)) {
            setVehicleProfile(PROFILE_MCLAREN_P1());
        } else if ("Lamborghini Aventador".equals(profile)) {
            setVehicleProfile(PROFILE_AVENTADOR());
        } else if ("BMW Z4".equals(profile)) {
            setVehicleProfile(PROFILE_BMW_Z4());
        } else if ("Pagani Zonda R".equals(profile)) {
            setVehicleProfile(PROFILE_ZONDA_R());
        } else if ("Lotus Exige".equals(profile)) {
            setVehicleProfile(PROFILE_LOTUS_EXIGE());
        } else if ("GTR R34".equals(profile)) {
            setVehicleProfile(PROFILE_GTRR34());
        } else {
            setVehicleProfile(PROFILE_LOTUS_EXIGE());
        }
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
                0.08f, 0.05f, 150, 150f, // Hafif ve atik (Moderate Wobble)
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
                        {0f, 40f},
                        {20f, 60f},
                        {40f, 80f},
                        {60f, 100f},
                        {70f, 110f},
                        {80f, 120f},
                        {100f, 160f},
                        {120f, 180f}
                },
                0.12f, 0.08f, 80, 50f, // Çift kavrama, çok hızlı devir, az sarsıntı
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
                        {0f, 40f},
                        {20f, 60f},
                        {40f, 80f},
                        {60f, 100f},
                        {70f, 110f},
                        {80f, 120f},
                        {100f, 160f},
                        {120f, 180f}
                },
                0.10f, 0.06f, 120, 250f, // ISR Şanzıman, inanılmaz şiddetli Wobble!
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
                        {0f, 40f},
                        {20f, 60f},
                        {40f, 80f},
                        {60f, 100f},
                        {70f, 110f},
                        {80f, 120f},
                        {100f, 160f},
                        {120f, 180f}
                },
                0.06f, 0.04f, 150, 100f, // Standart tork konvertör hissi
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
                        {0f, 40f},
                        {20f, 60f},
                        {40f, 80f},
                        {60f, 100f},
                        {70f, 110f},
                        {80f, 120f},
                        {100f, 160f},
                        {120f, 180f}
                },
                0.15f, 0.10f, 100, 300f, // Safkan yarış makinesi, vahşi ve kontrolsüz
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
    public static VehicleProfile PROFILE_GTRR34() {
        return new VehicleProfile("GTR R34", 1000f, 8200, 1,
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
                0.06f, 0.045f, 110, 250f, // RevHeadz GTR/GT3 Spec atalet ve sarsıntı!
                R.raw.rb26_idle,
                R.raw.rb26_2000,
                R.raw.rb26_3500,
                R.raw.rb26_4000,
                R.raw.rb26_5500,
                R.raw.rb26_6500,
                R.raw.rb26_8300
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
        this.mCurrentIdleVolumeScale = profile.idleVolumeScale * mUserIdleVolumeScale;
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

        // Audio focus istemiyoruz: müzik focus'ta kalsın, motor sesi müzikle birlikte mixlensin.
        int maxStreams = mCurrentSamples.length + 5;

        // Ses kaynağı: bildirim veya medya (kullanıcı seçimine göre).
        boolean useMedia = false;
        try {
            SharedPreferences prefs = mContext.getSharedPreferences("mg4_v3", Context.MODE_PRIVATE);
            String source = prefs.getString("sound_source", "notification");
            useMedia = "media".equals(source);
        } catch (Throwable ignored) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(useMedia ? AudioAttributes.USAGE_MEDIA : AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            mSoundPool = new SoundPool.Builder()
                    .setMaxStreams(maxStreams)
                    .setAudioAttributes(attrs)
                    .build();
        } else {
            int stream = useMedia ? AudioManager.STREAM_MUSIC : AudioManager.STREAM_NOTIFICATION;
            mSoundPool = new SoundPool(maxStreams, stream, 0);
        }

        mSoundPool.setOnLoadCompleteListener((soundPool, sampleId, status) -> {
            if (status != 0) return; // Yükleme hatası varsa çık

            // Toplam beklenen dosya sayısı: EngineSamples + Turbo + Transmission
            int totalExpected = mCurrentSamples.length + 2;

            mLoadedSamplesCount++;

            // TÜM DOSYALAR YÜKLENDİĞİNDE:
            if (mLoadedSamplesCount >= totalExpected && mIsPlaying) {
                // Ana Motor Sesleri
                for (EngineSample sample : mCurrentSamples) {
                    sample.streamId = mSoundPool.play(sample.soundId, 0f, 0f, 1, -1, 1.0f);
                }
                // Turbo / Compressor
                mTurboStreamId = mSoundPool.play(mTurboSoundId, 0f, 0f, 1, -1, 1.0f);
                // Şanzıman Islığı
                mGearWhineStreamId = mSoundPool.play(mGearWhineSoundId, 0f, 0f, 1, -1, 1.0f);

                // start() metodu içinde, onLoadCompleteListener'ın içine (Tüm dosyalar yüklendiğinde kısmına) ekle:
                mFlutterStreamId = mSoundPool.play(mFlutterSoundId, 0f, 0f, 1, -1, 1.0f); // -1 = Sonsuz Döngü
                onSpeedChanged(mCurrentSpeedKmh);
            }
        });

        // 3. YÜKLEME SIRALAMASI (Load): ID'leri şimdi alıyoruz
        if (mActiveProfile.hasTurbo == 2)
            mTurboSoundId = mSoundPool.load(mContext, R.raw.supercharge, 1);
        else
            mTurboSoundId = mSoundPool.load(mContext, R.raw.turbo, 1);

        mGearWhineSoundId = mSoundPool.load(mContext, R.raw.transmission, 1);

        if (mActiveProfile.name.contains("R34")) {
            mFlutterSoundId = mSoundPool.load(mContext, R.raw.rb26_bf2, 1);
        } else {
            mFlutterSoundId = -1; // R34 değilse şimdilik kapalı
        }

        // Sonra motor seslerini yükle
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
        mTurboStreamId = -1;
        mFlutterStreamId = -1;
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
        if (!mIsPlaying || mCurrentSamples == null || mLoadedSamplesCount < mCurrentSamples.length) return;

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
        float throttle = mSimulatedThrottle; // 0.01 - 1.0
        long currentTime = System.currentTimeMillis();

        if (speed < 1.0f) {
            mCurrentGear = 0;
            mCurrentRpm = mIdleRpm;
            return;
        }

        // 1. ADIM: Sürücünün İstediği "İdeal Devir" (Target RPM)
        float targetRpmRange = mMaxRpm - mIdleRpm;
        float baseTarget = 1500f + (mDriveModeAggressiveness * 3500f);
        float desiredRpm = baseTarget + (throttle * (mMaxRpm - baseTarget));
        desiredRpm = Math.max(mIdleRpm, Math.min(mMaxRpm, desiredRpm));

        // 2. ADIM: Vites Kararı
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
                // --- VİTES KÜÇÜLTME (Downshift) ---
                if (bestGear < mCurrentGear) {
                    if (mEnableRevMatch) {
                        float aggressivenessFactor = 0.05f + (mDriveModeAggressiveness * 0.10f);
                        mRevMatchBoost = mMaxRpm * aggressivenessFactor;
                        if (mCurrentRpm > mMaxRpm * 0.8f) mRevMatchBoost *= 0.5f;
                    } else {
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

            // 2. RPM YUMUŞATMASI (Atalet Sistemi)
            // Gaza basılıysa OnSmooth, çekiliyse OffSmooth değerini kullan
            float currentSmooth = (throttle > 0.05f) ? mActiveProfile.rpmOnSmooth : mActiveProfile.rpmOffSmooth;
            mCurrentRpm = (mCurrentRpm * (1.0f - currentSmooth)) + (targetRpmFinal * currentSmooth);

            // 3. Ara gazı etkisini final RPM'e ekle (Eğer açıksa)
            if (mEnableRevMatch && mRevMatchBoost > 0) {
                mCurrentRpm += mRevMatchBoost;
            }

            // 4. WOBBLE (ŞANZIMAN SARSINTISI) HİLESİ!
            // Vites atıldıktan sonraki ilk yarım saniye (500ms) içinde çalışır
            long timeSinceShift = currentTime - mLastShiftTime;
            if (timeSinceShift < 500 && mActiveProfile.wobbleMagnitude > 0) {
                float timeSec = timeSinceShift / 1000f; // Saniye cinsinden zaman
                float dampening = Math.max(0f, 1.0f - (timeSec / 0.5f)); // 500ms içinde etki sıfırlanır

                // 9.0Hz frekansla (saniyede 9 kez) sinüs dalgası oluşturarak devri titret
                float wobbleOffset = (float) Math.sin(timeSec * 9.0f * Math.PI * 2) * mActiveProfile.wobbleMagnitude * dampening;
                mCurrentRpm += wobbleOffset;
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

        // --- CONTINUOUS FLUTTER (DÖNGÜSEL STUTUTU) ---
        if (mFlutterStreamId != -1 && false) {
            if (rpm > 3500f && mLastThrottleForFlutter > 0.6f && mSimulatedThrottle < 0.1f) {
                mCurrentFlutterVol = (rpm / mMaxRpm);
            }

            if (mCurrentFlutterVol > 0f) {
                mCurrentFlutterVol *= 0.92f;
                if (mCurrentFlutterVol < 0.02f) mCurrentFlutterVol = 0f;

                float finalFlutterVol = mCurrentFlutterVol * masterVol * 1.5f;
                finalFlutterVol = Math.max(0f, Math.min(1f, finalFlutterVol));
                float flutterPitch = 0.8f + (mCurrentFlutterVol * 0.4f);

                mSoundPool.setVolume(mFlutterStreamId, finalFlutterVol, finalFlutterVol);
                mSoundPool.setRate(mFlutterStreamId, flutterPitch);
            } else {
                mSoundPool.setVolume(mFlutterStreamId, 0f, 0f);
            }

            mLastThrottleForFlutter = mSimulatedThrottle;
        }

        // --- TURBO HESAPLAMASI (Hızdan Bağımsız, RPM'e Tam Bağımlı) ---
        if (mTurboStreamId != -1) {
            if (mActiveProfile == null || (mActiveProfile.hasTurbo == 0)) {
                mSoundPool.setVolume(mTurboStreamId, 0f, 0f);
                mCurrentTurboBoost = 0f;
            } else if(mEnableTurboSound && mActiveProfile.hasTurbo == 1){
                float throttle = mSimulatedThrottle;
                float boostFactor = (rpmRatio > 0.10f) ? (rpmRatio - 0.10f) * 1.12f : 0f;
                boostFactor = Math.max(0f, Math.min(1f, boostFactor));

                float targetBoost = throttle * boostFactor;
                mCurrentTurboBoost = (mCurrentTurboBoost * 0.90f) + (targetBoost * 0.10f);

                float turboVol = mCurrentTurboBoost * masterVol * mTurboMaxSound;
                float turboPitch = 0.8f + (rpmRatio * 1.2f);

                mSoundPool.setVolume(mTurboStreamId, turboVol, turboVol);
                mSoundPool.setRate(mTurboStreamId, turboPitch);
            } else if(mEnableTurboSound && mActiveProfile.hasTurbo == 2){
                float compVol = (0.2f + (mSimulatedThrottle * 0.8f)) * rpmRatio * masterVol * mCompressorMaxVol;
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
                mSoundPool.setRate(s.streamId, mIdlePitch);
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

            float shapedVol = (float) Math.sqrt(rawVol);
            if (Float.isNaN(shapedVol)) shapedVol = 0f;

            if (s == mCurrentSamples[0]) shapedVol *= mCurrentIdleVolumeScale;

            float loadVolumeFactor = 0.5f + (mSimulatedThrottle * 0.5f);
            float loadPitchFactor = 0.98f + (mSimulatedThrottle * 0.04f);
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