package com.example.mg4_v3.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.Build;
import android.util.Log;

import com.example.mg4_v3.R;

/**
 * Yapay motor sesi yöneticisi.
 * * LFA motor sesini kullanır, hıza göre pitch ve volume ayarlar.
 * - Hız 0-150 km/h arasında pitch/volume değişir
 * - 150'yi aşsa bile maksimum seviyede kalır
 * - İki mod: Loop (vites yok) veya Tam (vites var)
 */
public class EngineSoundManager {

    /** Ses modu (sadeleştirilmiş) */
    public enum SoundMode {
        FULL,           // Assetto tarzı: sürekli devir, vites hissi yok
        VIRTUAL_GEAR_V2 // Sanal vites: SoundPool ile pürüzsüz vites atışı
    }

    /** Şanzıman Profilleri */
    public enum GearProfile {
        CRUISER_4, // 4-İleri Uzun
        SPORT_6,   // 6-İleri Gerçek LFA
        RALLY_8    // 8-İleri Kısa
    }

    private GearProfile mCurrentGearProfile = GearProfile.SPORT_6; // Varsayılan

    // 1. Profil: 4-İleri Cruiser
    private static final VirtualGear[] GEARS_CRUISER_4 = {
            new VirtualGear(0f,   35f,  "V1"),
            new VirtualGear(35f,  80f,  "V2"),
            new VirtualGear(80f,  125f, "V3"),
            new VirtualGear(125f, 160f, "V4")
    };

    // 2. Profil: 6-İleri Gerçek LFA
    private static final VirtualGear[] GEARS_SPORT_6 = {
            new VirtualGear(0f,   20f,  "V1"),
            new VirtualGear(20f,  45f,  "V2"),
            new VirtualGear(45f,  75f,  "V3"),
            new VirtualGear(75f,  110f, "V4"),
            new VirtualGear(110f, 140f, "V5"),
            new VirtualGear(140f, 160f, "V6")
    };

    // 3. Profil: 8-İleri Ralli
    private static final VirtualGear[] GEARS_RALLY_8 = {
            new VirtualGear(0f,   15f,  "V1"),
            new VirtualGear(15f,  30f,  "V2"),
            new VirtualGear(30f,  45f,  "V3"),
            new VirtualGear(45f,  65f,  "V4"),
            new VirtualGear(65f,  85f,  "V5"),
            new VirtualGear(85f,  110f, "V6"),
            new VirtualGear(110f, 135f, "V7"),
            new VirtualGear(135f, 160f, "V8")
    };

    /** Seçili olan aktif şanzıman dizisini döndürür */
    private VirtualGear[] getActiveGearArray() {
        switch (mCurrentGearProfile) {
            case CRUISER_4: return GEARS_CRUISER_4;
            case RALLY_8:   return GEARS_RALLY_8;
            case SPORT_6:
            default:        return GEARS_SPORT_6;
        }
    }

    /** Dışarıdan şanzıman profilini değiştirmek için */
    public void setGearProfile(GearProfile profile) {
        mCurrentGearProfile = profile;
        mCurrentVirtualGear = -1; // Vites değiştirdiğimizde mevcut vitesi sıfırla
        Log.i(TAG, "Şanzıman profili değişti: " + profile.name());
    }

    /** Yapay vites sistemi — her aralıkta hız 1000–10000 RPM'ye eşlenir */
    private static class VirtualGear {
        final float minSpeed;
        final float maxSpeed;
        final String name;

        VirtualGear(float min, float max, String n) {
            minSpeed = min;
            maxSpeed = max;
            name = n;
        }
    }

    private int mCurrentVirtualGear = -1;

    /** Motor güç oranı 0..1 (0= gaz yok/coasting, 1= tam gaz). -1 = bilinmiyor (güç yoksa 1 kabul edilir). */
    private float mPowerRatio = -1f;

    private static final String TAG = "EngineSound";

    // Hız limiti
    private static final float MAX_SPEED_KMH = 150f;

    // Pitch aralığı (0.5x = yavaş, 1.5x = hızlı)
    private static final float PITCH_MIN = 0.5f;  // 0 km/h için
    private static final float PITCH_MAX = 1.5f;  // 150 km/h için

    // Volume aralığı (0.0 = sessiz, 1.0 = tam ses)
    private static final float VOLUME_MIN = 0.0f;
    private static final float VOLUME_MAX = 0.50f;

    // Minimum hız eşiği — bu hızın altında ses çalmaz
    private static final float MIN_SPEED_TO_PLAY = 5f; // km/h

    // Loop için dosya zaman aralığı — 0–8 sn = 1000 RPM → 10000 RPM (içerik zaten ramp)
    private static final int LOOP_START_MS = 0;
    private static final int LOOP_END_MS = 8000;

    // Yapay viteste sabit hız için: istenen RPM'deki kısa dilimi loop'la
    private static final int RPM_SLICE_HALF_MS = 350;
    /** Vites geçişinde histerezis (km/h): sınırda sürekli atlama olmasın */
    private static final float VIRTUAL_GEAR_HYSTERESIS_KMH = 5f;

    // Pozisyon kontrolü için timer interval
    private static final int POSITION_CHECK_INTERVAL_MS = 80;

    private static EngineSoundManager sInstance = null;

    private Context mContext;
    private boolean mIsPlaying = false;
    private float mCurrentSpeed = 0f;
    private SoundMode mCurrentMode = SoundMode.VIRTUAL_GEAR_V2; // Varsayılan: Sanal vites
    private android.os.Handler mHandler;

    // --- SES MOTORLARI ---
    private MediaPlayer mMediaPlayer;
    private SoundPool mSoundPool;     // V2 İÇİN YENİ OYUN MOTORU
    private int mV2SoundId = -1;
    private int mV2StreamId = -1;

    // Eski V1/Loop modları için kullanılan pozisyon denetleyici artık gereksiz.

    private EngineSoundManager(Context context) {
        mContext = context.getApplicationContext();
        mHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    }

    public static synchronized EngineSoundManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new EngineSoundManager(context);
        }
        return sInstance;
    }

    /** Ses modunu değiştirir. */
    public void setMode(SoundMode mode) {
        if (mCurrentMode == mode) return;

        mCurrentMode = mode;
        mCurrentVirtualGear = -1;
        Log.i(TAG, "Ses modu değiştirildi: " + mode);

        // Mod değiştiğinde motoru baştan başlat
        if (mIsPlaying) {
            stop();
            start();
        }
    }

    public SoundMode getMode() {
        return mCurrentMode;
    }

    public void start() {
        if (mIsPlaying) {
            Log.d(TAG, "start() — zaten çalıyor");
            return;
        }
        mIsPlaying = true;

        try {
            if (mCurrentMode == SoundMode.VIRTUAL_GEAR_V2) {
                // ==========================================
                // YENİ V2: SOUNDPOOL (SIFIR GECİKME, RAM'DEN)
                // ==========================================
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    AudioAttributes attrs = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build();
                    mSoundPool = new SoundPool.Builder()
                            .setMaxStreams(1)
                            .setAudioAttributes(attrs)
                            .build();
                } else {
                    mSoundPool = new SoundPool(1, AudioManager.STREAM_MUSIC, 0);
                }

                mSoundPool.setOnLoadCompleteListener((soundPool, sampleId, status) -> {
                    if (status == 0 && mIsPlaying && mCurrentMode == SoundMode.VIRTUAL_GEAR_V2) {
                        // -1 parametresi sonsuz kusursuz döngüyü temsil eder
                        mV2StreamId = soundPool.play(mV2SoundId, VOLUME_MIN, VOLUME_MIN, 1, -1, 1.0f);
                        Log.i(TAG, "SoundPool V2 hazır ve başladı.");
                        onSpeedChanged(mCurrentSpeed);
                    }
                });

                // WAV dosyasını RAM'e yükle (Mükemmel döngü için)
                mV2SoundId = mSoundPool.load(mContext, R.raw.aa2, 1);

            } else {
                // ==========================================
                // ESKİ MODLAR (V1, LOOP, FULL): MEDIAPLAYER
                // ==========================================
                mMediaPlayer = new MediaPlayer();

                android.content.res.AssetFileDescriptor afd = mContext.getResources()
                        .openRawResourceFd(R.raw.aa2);
                if (afd == null) return;
                mMediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                afd.close();

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    AudioAttributes attrs = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build();
                    mMediaPlayer.setAudioAttributes(attrs);
                } else {
                    mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
                }

                // Assetto modu: tüm dosya doğal loop
                mMediaPlayer.setLooping(true);

                // Tam modda completion özel iş yapmıyor; MediaPlayer kendi loop'unu yönetir.
                mMediaPlayer.setOnCompletionListener(mp -> {});

                mMediaPlayer.setOnErrorListener((mp, what, extra) -> {
                    stop();
                    return true;
                });

                mMediaPlayer.setOnPreparedListener(mp -> {
                    mp.seekTo(0);
                    mp.setVolume(VOLUME_MIN, VOLUME_MIN);
                    mp.start();
                    onSpeedChanged(mCurrentSpeed);
                });
                mMediaPlayer.prepareAsync();
            }

        } catch (Exception e) {
            Log.e(TAG, "start() hata: " + e.getMessage(), e);
            stop();
        }
    }

    public void stop() {
        if (!mIsPlaying) return;
        mIsPlaying = false;

        // MediaPlayer Temizliği
        if (mMediaPlayer != null) {
            try {
                if (mMediaPlayer.isPlaying()) mMediaPlayer.stop();
                mMediaPlayer.release();
            } catch (Exception e) {
                Log.w(TAG, "stop() release hata: " + e.getMessage());
            }
            mMediaPlayer = null;
        }

        // SoundPool Temizliği
        if (mSoundPool != null) {
            try {
                if (mV2StreamId != -1) mSoundPool.stop(mV2StreamId);
                mSoundPool.release();
            } catch (Exception e) {
                Log.w(TAG, "SoundPool stop hata: " + e.getMessage());
            }
            mSoundPool = null;
            mV2SoundId = -1;
            mV2StreamId = -1;
        }

        Log.i(TAG, "stop() — ses durduruldu");
    }

    public void setPowerRatio(float powerRatio) {
        if (powerRatio < -0.01f) mPowerRatio = -1f;
        else mPowerRatio = Math.max(0f, Math.min(1f, powerRatio));
    }

    public void onSpeedChanged(float speedKmh) {
        float processedSpeed = speedKmh;
        if (Float.isNaN(processedSpeed) || processedSpeed < 0) processedSpeed = 0f;
        if (processedSpeed > MAX_SPEED_KMH) processedSpeed = MAX_SPEED_KMH;

        mCurrentSpeed = processedSpeed;

        if (!mIsPlaying) return;
        final float finalSpeed = processedSpeed;
        mHandler.post(() -> updatePlaybackParams(finalSpeed));
    }

    private void updatePlaybackParams(float speedKmh) {
        if (!mIsPlaying) return;

        try {
            float pitch;
            float volume;

            // ==============================================================
            // V2 MODU (SOUNDPOOL KONTROLÜ - MEDIAPLAYER'A GİRMEDEN ÇIKAR)
            // ==============================================================
            if (mCurrentMode == SoundMode.VIRTUAL_GEAR_V2) {
                if (mSoundPool == null || mV2StreamId == -1) return; // Henüz RAM'e yüklenmediyse bekle

                if (speedKmh < MIN_SPEED_TO_PLAY) {
                    mSoundPool.setVolume(mV2StreamId, 0f, 0f);
                    mCurrentVirtualGear = -1;
                    return;
                }

                int gearIndex = findVirtualGearWithHysteresisV2(speedKmh, mCurrentVirtualGear);
                if (gearIndex >= 0) {
                    VirtualGear gear = getActiveGearArray()[gearIndex];
                    if (mCurrentVirtualGear != gearIndex && mCurrentVirtualGear >= 0) {
                        Log.i(TAG, String.format("V2 Vites: %s → %s (%.0f km/h)",
                                getActiveGearArray()[mCurrentVirtualGear].name, gear.name, speedKmh));
                    }
                    mCurrentVirtualGear = gearIndex;

                    float span = gear.maxSpeed - gear.minSpeed;
                    if (span < 1f) span = 1f;
                    float t = (speedKmh - gear.minSpeed) / span;
                    t = Math.max(0f, Math.min(1f, t));

                    float minPitch = (gearIndex == 0) ? 0.5f : 1.0f;
                    float maxPitch = 1.7f;

                    pitch = minPitch + (t * (maxPitch - minPitch));
                    volume = VOLUME_MIN + (t * (VOLUME_MAX - VOLUME_MIN));
                } else {
                    pitch = 1.0f;
                    volume = VOLUME_MIN;
                }

                // Yük (Load) simülasyonu
                float powerFactor = getPowerVolumeFactor();
                volume *= powerFactor;
                if (mPowerRatio >= 0f) {
                    pitch *= (0.98f + 0.04f * mPowerRatio);
                }

                // SoundPool pitch değerleri sınırlandırılmıştır (0.5x - 2.0x)
                float spPitch = Math.max(0.5f, Math.min(2.0f, pitch));

                mSoundPool.setRate(mV2StreamId, spPitch);
                mSoundPool.setVolume(mV2StreamId, volume, volume);
                return; // MEDIAPLAYER KODLARINA GİRMEDEN ÇIK
            }

            // ==============================================================
            // ASSETTO MODU (MEDIAPLAYER KONTROLÜ)
            // ==============================================================
            if (mMediaPlayer == null) return;

            if (speedKmh < MIN_SPEED_TO_PLAY) {
                mMediaPlayer.setVolume(0f, 0f);
                mCurrentVirtualGear = -1;
                return;
            }

            // Assetto: hız → pitch/volume lineer map
            float t = speedKmh / MAX_SPEED_KMH;
            t = Math.max(0f, Math.min(1f, t));
            pitch = PITCH_MIN + t * (PITCH_MAX - PITCH_MIN);
            volume = VOLUME_MIN + t * (VOLUME_MAX - VOLUME_MIN);

            float powerFactor = getPowerVolumeFactor();
            volume *= powerFactor;
            if (mPowerRatio >= 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                float loadPitch = 0.98f + 0.04f * mPowerRatio;
                pitch *= loadPitch;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.media.PlaybackParams params = mMediaPlayer.getPlaybackParams();
                if (params != null) {
                    params.setSpeed(pitch);
                    mMediaPlayer.setPlaybackParams(params);
                }
            }

            mMediaPlayer.setVolume(volume, volume);

        } catch (Exception e) {
            Log.e(TAG, "updatePlaybackParams hata: " + e.getMessage());
        }
    }

    private float getPowerVolumeFactor() {
        if (mPowerRatio < 0f) return 1f;
        return 0.2f + 0.8f * mPowerRatio;
    }

    private int findVirtualGearV2(float speedKmh) {
        VirtualGear[] activeGears = getActiveGearArray();
        for (int i = 0; i < activeGears.length; i++) {
            if (speedKmh >= activeGears[i].minSpeed && speedKmh <= activeGears[i].maxSpeed) {
                return i;
            }
        }
        return activeGears.length - 1;
    }

    private int findVirtualGearWithHysteresisV2(float speedKmh, int currentGearIndex) {
        VirtualGear[] activeGears = getActiveGearArray();
        int rawGear = findVirtualGearV2(speedKmh);
        if (currentGearIndex < 0) return rawGear;

        if (rawGear > currentGearIndex) {
            float upThreshold = activeGears[rawGear].minSpeed + VIRTUAL_GEAR_HYSTERESIS_KMH;
            if (speedKmh >= upThreshold) return rawGear;
            return currentGearIndex;
        }
        if (rawGear < currentGearIndex) {
            float downThreshold = activeGears[currentGearIndex].minSpeed - VIRTUAL_GEAR_HYSTERESIS_KMH;
            if (speedKmh <= downThreshold) return rawGear;
            return currentGearIndex;
        }
        return currentGearIndex;
    }

    public boolean isPlaying() {
        if (mCurrentMode == SoundMode.VIRTUAL_GEAR_V2) {
            return mIsPlaying && mSoundPool != null && mV2StreamId != -1;
        }
        return mIsPlaying && mMediaPlayer != null && mMediaPlayer.isPlaying();
    }
}