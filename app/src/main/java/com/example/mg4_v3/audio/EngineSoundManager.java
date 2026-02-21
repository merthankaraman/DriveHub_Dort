package com.example.mg4_v3.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.util.Log;

import com.example.mg4_v3.R;

/**
 * Yapay motor sesi yöneticisi.
 * 
 * LFA motor sesini kullanır, hıza göre pitch ve volume ayarlar.
 * - Hız 0-150 km/h arasında pitch/volume değişir
 * - 150'yi aşsa bile maksimum seviyede kalır
 * - İki mod: Loop (vites yok) veya Tam (vites var)
 */
public class EngineSoundManager {
    
    /** Ses modu */
    public enum SoundMode {
        LOOP,      // 3-6 saniye arası loop, vites değişimi yok
        FULL,      // Tüm dosya, vites değişimleri dahil
        VIRTUAL_GEAR // Yapay vites: hız aralıklarına göre sabit devirler
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
    
    // Yapay vites tablosu: her aralıkta min hız → 1000 RPM, max hız → 10000 RPM (linear)
    private static final VirtualGear[] VIRTUAL_GEARS = {
        new VirtualGear(0f,   10f,  "V1"),   // 0-10 km/h
        new VirtualGear(11f,  40f,  "V2"),   // 11-40 km/h
        new VirtualGear(41f,  70f,  "V3"),   // 41-70 km/h
        new VirtualGear(71f,  90f,  "V4"),   // 71-90 km/h
        new VirtualGear(91f,  130f, "V5"),   // 91-130 km/h
        new VirtualGear(131f, 150f, "V6")   // 131–150 km/h → 150’de 10000 RPM
    };
    
    // 3sn=1000 RPM, 6sn=10000 RPM → dosya içinde konum (ms) = 3000 + 3000 * (rpm-1000)/9000
    private static final int RPM_RANGE = 9000;  // 10000 - 1000
    
    private int mCurrentVirtualGear = -1;
    private int mVirtualGearLoopStartMs = LOOP_START_MS;
    private int mVirtualGearLoopEndMs = LOOP_END_MS;
    
    /** Motor güç oranı 0..1 (0= gaz yok/coasting, 1= tam gaz). -1 = bilinmiyor (güç yoksa 1 kabul edilir). */
    private float mPowerRatio = -1f;

    private static final String TAG = "EngineSound";
    
    // Hız limiti
    private static final float MAX_SPEED_KMH = 150f;
    
    // Pitch aralığı (0.5x = yavaş, 1.5x = hızlı)
    private static final float PITCH_MIN = 0.5f;  // 0 km/h için
    private static final float PITCH_MAX = 1.5f;  // 150 km/h için
    
    // Volume aralığı (0.0 = sessiz, 1.0 = tam ses)
    // Müzikle birlikte çalması için düşük tutuyoruz
    private static final float VOLUME_MIN = 0.0f;   // 0 km/h için (sessiz)
    private static final float VOLUME_MAX = 0.35f; // 150 km/h için (müziği bastırmayacak seviye)
    
    // Minimum hız eşiği — bu hızın altında ses çalmaz
    private static final float MIN_SPEED_TO_PLAY = 5f; // km/h
    
    // Loop için dosya zaman aralığı — 3–6 sn = 1000 RPM → 10000 RPM (içerik zaten ramp)
    private static final int LOOP_START_MS = 3000;   // 3 sn = 1000 RPM
    private static final int LOOP_END_MS = 6000;     // 6 sn = 10000 RPM
    
    // Yapay viteste sabit hız için: istenen RPM'deki kısa dilimi loop'la (pitch 1.0 = doğru tını)
    private static final int RPM_SLICE_HALF_MS = 200; // Dilim = merkez ±200ms (400ms loop)
    
    // Pozisyon kontrolü için timer interval
    private static final int POSITION_CHECK_INTERVAL_MS = 80;
    
    private static EngineSoundManager sInstance = null;
    
    private Context mContext;
    private MediaPlayer mMediaPlayer;
    private boolean mIsPlaying = false;
    private float mCurrentSpeed = 0f;
    private SoundMode mCurrentMode = SoundMode.LOOP; // Varsayılan: Loop modu
    private android.os.Handler mHandler;
    private final Runnable mPositionChecker = new Runnable() {
        @Override
        public void run() {
            if (mMediaPlayer != null && mIsPlaying && mMediaPlayer.isPlaying()) {
                try {
                    // LOOP ve VIRTUAL_GEAR modlarında pozisyon kontrolü yap
                    if (mCurrentMode == SoundMode.LOOP) {
                        int currentPos = mMediaPlayer.getCurrentPosition();
                        if (currentPos >= LOOP_END_MS) {
                            mMediaPlayer.seekTo(LOOP_START_MS);
                        }
                    } else if (mCurrentMode == SoundMode.VIRTUAL_GEAR) {
                        int currentPos = mMediaPlayer.getCurrentPosition();
                        if (currentPos >= mVirtualGearLoopEndMs) {
                            mMediaPlayer.seekTo(mVirtualGearLoopStartMs);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Position check hata: " + e.getMessage());
                }
            }
            // Tekrar kontrol et
            if (mIsPlaying) {
                mHandler.postDelayed(this, POSITION_CHECK_INTERVAL_MS);
            }
        }
    };
    
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
    
    /**
     * Ses modunu değiştirir (Loop veya Tam).
     * Çalışırken değiştirilebilir.
     */
    public void setMode(SoundMode mode) {
        if (mCurrentMode == mode) {
            Log.d(TAG, "setMode() — zaten " + mode + " modunda");
            return;
        }
        
        mCurrentMode = mode;
        mCurrentVirtualGear = -1; // Vites sıfırla
        Log.i(TAG, "Ses modu değiştirildi: " + mode);
        
        // Çalıyorsa yeniden başlat (mod değişikliği için)
        if (mIsPlaying && mMediaPlayer != null) {
            try {
                boolean wasPlaying = mMediaPlayer.isPlaying();
                int currentPos = mMediaPlayer.getCurrentPosition();
                
                // Durdur
                mMediaPlayer.pause();
                
                // Moda göre pozisyon ayarla
                if (mode == SoundMode.LOOP || mode == SoundMode.VIRTUAL_GEAR) {
                    mMediaPlayer.seekTo(LOOP_START_MS);
                } else {
                    mMediaPlayer.seekTo(0); // FULL mod: baştan başla
                }
                
                // Tekrar başlat
                if (wasPlaying) {
                    mMediaPlayer.start();
                }
                
                // Yapay vites modunda pozisyon kontrolünü başlat
                if (mode == SoundMode.VIRTUAL_GEAR) {
                    mHandler.post(mPositionChecker);
                }
                
                // Hız güncellemesini tekrar uygula
                onSpeedChanged(mCurrentSpeed);
                
                Log.i(TAG, "Mod değişikliği uygulandı: " + mode);
            } catch (Exception e) {
                Log.e(TAG, "setMode() hata: " + e.getMessage());
            }
        }
    }
    
    /**
     * Mevcut ses modunu döndürür.
     */
    public SoundMode getMode() {
        return mCurrentMode;
    }
    
    /**
     * Ses motorunu başlatır (loop modunda).
     * onCreate veya onResume'da çağrılmalı.
     */
    public void start() {
        if (mIsPlaying) {
            Log.d(TAG, "start() — zaten çalıyor");
            return;
        }
        
        try {
            // MediaPlayer oluştur
            mMediaPlayer = new MediaPlayer();
            
            // Ses dosyasını yükle
            android.content.res.AssetFileDescriptor afd = mContext.getResources()
                    .openRawResourceFd(R.raw.lfa_engine_acceleration);
            if (afd == null) {
                Log.e(TAG, "start() — ses dosyası bulunamadı: lfa_engine_acceleration.mp3");
                return;
            }
            
            mMediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            
            // Audio attributes — müzikle birlikte çalması için
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build();
                mMediaPlayer.setAudioAttributes(attrs);
            } else {
                mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            }
            
            // Loop ayarla — moda göre
            if (mCurrentMode == SoundMode.LOOP) {
                mMediaPlayer.setLooping(false); // Manuel loop yapacağız
            } else {
                mMediaPlayer.setLooping(true); // FULL mod: tüm dosyayı loop et
            }
            
            // Completion listener — moda göre davran
            mMediaPlayer.setOnCompletionListener(mp -> {
                if (mIsPlaying) {
                    if (mCurrentMode == SoundMode.LOOP) {
                        Log.d(TAG, "MediaPlayer completion — loop başına dönülüyor");
                        mp.seekTo(LOOP_START_MS);
                        mp.start();
                    } else {
                        // FULL mod: MediaPlayer zaten looping=true olduğu için otomatik başa döner
                        Log.d(TAG, "MediaPlayer completion — FULL mod (otomatik loop)");
                    }
                }
            });
            
            // Error listener
            mMediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error: what=" + what + " extra=" + extra);
                stop();
                return true;
            });
            
            // Hazır olunca başlat
            mMediaPlayer.setOnPreparedListener(mp -> {
                Log.i(TAG, "MediaPlayer hazır — başlatılıyor (mod: " + mCurrentMode + ")");
                
                // Moda göre başlangıç pozisyonu
                if (mCurrentMode == SoundMode.LOOP || mCurrentMode == SoundMode.VIRTUAL_GEAR) {
                    mp.seekTo(LOOP_START_MS);
                    // Pozisyon kontrolü başlat (belirli aralığı loop etmek için)
                    mHandler.post(mPositionChecker);
                } else {
                    mp.seekTo(0); // FULL mod: baştan başla
                }
                
                mp.setVolume(VOLUME_MIN, VOLUME_MIN);
                mp.start();
                mIsPlaying = true;
                
                // İlk hız güncellemesini uygula
                onSpeedChanged(mCurrentSpeed);
            });
            
            mMediaPlayer.prepareAsync();
            
        } catch (Exception e) {
            Log.e(TAG, "start() hata: " + e.getMessage(), e);
            stop();
        }
    }
    
    /**
     * Ses motorunu durdurur ve kaynakları temizler.
     * onDestroy veya onPause'da çağrılmalı.
     */
    public void stop() {
        if (!mIsPlaying && mMediaPlayer == null) {
            return;
        }
        
        mIsPlaying = false;
        
        // Pozisyon kontrolünü durdur
        mHandler.removeCallbacks(mPositionChecker);
        
        if (mMediaPlayer != null) {
            try {
                if (mMediaPlayer.isPlaying()) {
                    mMediaPlayer.stop();
                }
                mMediaPlayer.release();
            } catch (Exception e) {
                Log.w(TAG, "stop() release hata: " + e.getMessage());
            }
            mMediaPlayer = null;
        }
        
        Log.i(TAG, "stop() — ses durduruldu");
    }
    
    /**
     * Motor güç oranını verir (0 = gaz yok, 1 = tam gaz).
     * Araçtan güç/kW bilgisi alındığında buraya 0..1 aralığında beslenebilir.
     * Bilinmiyorsa -1 bırakın; ses tam volume ile çalar.
     * 
     * @param powerRatio 0f = güç yok (coasting), 1f = max güç, -1f = bilinmiyor
     */
    public void setPowerRatio(float powerRatio) {
        if (powerRatio < -0.01f) mPowerRatio = -1f;
        else mPowerRatio = Math.max(0f, Math.min(1f, powerRatio));
    }
    
    /**
     * Hız değiştiğinde çağrılır.
     * MainActivity'deki hız döngüsünden çağrılacak.
     * 
     * @param speedKmh Araç hızı (km/h)
     */
    public void onSpeedChanged(float speedKmh) {
        // 1. Önce değerleri kontrol et ve geçici bir değişkende sakla
        float processedSpeed = speedKmh;

        if (Float.isNaN(processedSpeed) || processedSpeed < 0) {
            processedSpeed = 0f;
        }

        if (processedSpeed > MAX_SPEED_KMH) {
            processedSpeed = MAX_SPEED_KMH;
        }

        mCurrentSpeed = processedSpeed;

        if (mMediaPlayer == null || !mIsPlaying) {
            return;
        }

        // 2. Lambda içinde kullanmak için "final" bir kopya oluştur
        final float finalSpeed = processedSpeed;

        // 3. Artık güvenle post edebilirsin
        mHandler.post(() -> updatePlaybackParams(finalSpeed));
    }
    
    /**
     * Pitch ve volume'ü hıza göre günceller.
     */
    private void updatePlaybackParams(float speedKmh) {
        if (mMediaPlayer == null || !mIsPlaying) {
            return;
        }
        
        try {
            // Hız çok düşükse sesi kapat
            if (speedKmh < MIN_SPEED_TO_PLAY) {
                mMediaPlayer.setVolume(0f, 0f);
                mCurrentVirtualGear = -1;
                return;
            }
            
            float pitch;
            float volume;
            
            // Yapay vites modunda: 3–6 sn = 1000→10000 RPM; istenen RPM'deki DİLİMİ loop'la, pitch=1.0
            if (mCurrentMode == SoundMode.VIRTUAL_GEAR) {
                int gearIndex = findVirtualGear(speedKmh);
                if (gearIndex >= 0) {
                    VirtualGear gear = VIRTUAL_GEARS[gearIndex];
                    if (mCurrentVirtualGear != gearIndex && mCurrentVirtualGear >= 0) {
                        Log.i(TAG, String.format("Vites: %s → %s (%.0f km/h)", 
                                VIRTUAL_GEARS[mCurrentVirtualGear].name, gear.name, speedKmh));
                    }
                    mCurrentVirtualGear = gearIndex;
                    
                    float span = gear.maxSpeed - gear.minSpeed;
                    if (span < 1f) span = 1f;
                    float t = (speedKmh - gear.minSpeed) / span;
                    t = Math.max(0f, Math.min(1f, t));
                    
                    // İstenen RPM (1000–10000)
                    float desiredRpm = 1000f + 9000f * t;
                    // Dosyada bu RPM'nin olduğu an: 3sn=1000, 6sn=10000 → konum (ms)
                    int centerMs = 3000 + (int) (3000f * (desiredRpm - 1000f) / RPM_RANGE);
                    centerMs = Math.max(LOOP_START_MS, Math.min(LOOP_END_MS, centerMs));
                    
                    mVirtualGearLoopStartMs = Math.max(LOOP_START_MS, centerMs - RPM_SLICE_HALF_MS);
                    mVirtualGearLoopEndMs = Math.min(LOOP_END_MS, centerMs + RPM_SLICE_HALF_MS);
                    if (mVirtualGearLoopEndMs - mVirtualGearLoopStartMs < 100) {
                        mVirtualGearLoopStartMs = centerMs - 50;
                        mVirtualGearLoopEndMs = centerMs + 50;
                    }
                    
                    pitch = 1.0f; // Doğru anı çalıyoruz, pitch değiştirme
                    volume = VOLUME_MIN + t * (VOLUME_MAX - VOLUME_MIN);
                    
                    // Şu an loop diliminin dışındaysak, dilim başına seek et
                    int pos = mMediaPlayer.getCurrentPosition();
                    if (pos < mVirtualGearLoopStartMs || pos > mVirtualGearLoopEndMs) {
                        mMediaPlayer.seekTo(mVirtualGearLoopStartMs);
                    }
                } else {
                    pitch = 1.0f;
                    volume = VOLUME_MIN;
                }
            } else {
                // Normal mod: linear interpolation
                float t = speedKmh / MAX_SPEED_KMH;
                t = Math.max(0f, Math.min(1f, t));
                pitch = PITCH_MIN + t * (PITCH_MAX - PITCH_MIN);
                volume = VOLUME_MIN + t * (VOLUME_MAX - VOLUME_MIN);
            }
            
            // Güç oranı varsa: 0% güç = sessiz/çok düşük, 100% = hesaplanan volume (tam gaz sesi)
            float powerFactor = getPowerVolumeFactor();
            volume *= powerFactor;
            // İsteğe bağlı: yük altında hafif pitch artışı (tam gazda biraz daha tiz)
            if (mPowerRatio >= 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                float loadPitch = 0.98f + 0.04f * mPowerRatio; // 0% → 0.98x, 100% → 1.02x
                pitch *= loadPitch;
            }
            
            // PlaybackParams ile pitch ayarla (API 23+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.media.PlaybackParams params = mMediaPlayer.getPlaybackParams();
                if (params != null) {
                    params.setSpeed(pitch);
                    mMediaPlayer.setPlaybackParams(params);
                }
            } else {
                // API 23 altında pitch ayarı yok, sadece volume
                Log.d(TAG, "Pitch ayarı desteklenmiyor (API < 23)");
            }
            
            // Volume ayarla
            mMediaPlayer.setVolume(volume, volume);
            
            if (mCurrentMode == SoundMode.VIRTUAL_GEAR && mCurrentVirtualGear >= 0) {
                Log.d(TAG, String.format("Hız: %.0f km/h → %s (Pitch: %.2fx, Volume: %.2f)", 
                        speedKmh, VIRTUAL_GEARS[mCurrentVirtualGear].name, pitch, volume));
            } else {
                Log.d(TAG, String.format("Hız: %.0f km/h → Pitch: %.2fx, Volume: %.2f", 
                        speedKmh, pitch, volume));
            }
            
        } catch (Exception e) {
            Log.e(TAG, "updatePlaybackParams hata: " + e.getMessage());
        }
    }
    
    /**
     * Güç oranına göre volume çarpanı. Bilinmiyorsa 1.0.
     * 0% güç = çok düşük ses (rölanti hissi), 100% = tam ses.
     */
    private float getPowerVolumeFactor() {
        if (mPowerRatio < 0f) return 1f;
        return 0.2f + 0.8f * mPowerRatio;
    }
    
    /**
     * Hıza göre yapay vites bulur.
     */
    private int findVirtualGear(float speedKmh) {
        for (int i = 0; i < VIRTUAL_GEARS.length; i++) {
            VirtualGear gear = VIRTUAL_GEARS[i];
            if (speedKmh >= gear.minSpeed && speedKmh <= gear.maxSpeed) {
                return i;
            }
        }
        // Son vites (maksimum)
        return VIRTUAL_GEARS.length - 1;
    }
    
    /**
     * Ses motorunun çalışıp çalışmadığını kontrol eder.
     */
    public boolean isPlaying() {
        return mIsPlaying && mMediaPlayer != null && mMediaPlayer.isPlaying();
    }
}
