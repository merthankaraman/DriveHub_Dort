package com.example.mg4_v3.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import androidx.appcompat.widget.SwitchCompat;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.os.LocaleListCompat;

import com.example.mg4_v3.R;
import com.example.mg4_v3.audio.EngineSoundManager;
import com.example.mg4_v3.hardware.MG4Hardware;
import com.example.mg4_v3.model.ChargingRecord;
import com.example.mg4_v3.model.DriveMode;
import com.example.mg4_v3.model.RegenLevel;
import com.example.mg4_v3.service.MG4ControlService;
import com.example.mg4_v3.util.ChargingHistory;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static com.example.mg4_v3.audio.EngineSoundManager.SoundMode;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MG4_UI";
    private static final String PREF_THEME_MODE = "theme_mode";
    /** Dil: \"tr\", \"en\" veya \"\" (sistem) */
    private static final String PREF_LANGUAGE = "app_language";
    private static final String PREF_SOUND_ENABLED = "sound_enabled";
    private static final String PREF_SOUND_MODE = "sound_mode";
    private static final String PREF_OVERLAY_ENABLED = "overlay_enabled";
    private static final String PREF_SOUND_PROFILE = "sound_profile";
    private static final String PREF_SOUND_MASTER = "sound_master";
    private static final String PREF_MOTOR_POWER = "motor_power_kw";
    /** Tek pedal atanacak tuş: -1=Kapalı, 17=Sol yıldız, 286=Sağ yıldız (InputReader keyCode, hafızalı) */
    private static final String PREF_ONE_PEDAL_KEY = "one_pedal_key";
    private static final int[] ONE_PEDAL_KEY_VALUES = { -1, 17, 286 };
    /** Tek pedal / 360 kamera tuş seçenekleri: string-array one_pedal_key_labels (Kapalı, Sol yıldız, Sağ yıldız) */
    /** Tek pedal açmak için basma tipi: "single", "long", "double" */
    private static final String PREF_ONE_PEDAL_PRESS_TYPE = "one_pedal_press_type";
    /** Tek pedaldan kapatmak için basma tipi (varsayılan: tek) */
    private static final String PREF_ONE_PEDAL_PRESS_TYPE_OFF = "one_pedal_press_type_off";
    private static final int DEFAULT_ONE_PEDAL_KEY = -1;
    private static final String DEFAULT_ONE_PEDAL_PRESS_TYPE = "long";
    private static final String DEFAULT_ONE_PEDAL_PRESS_TYPE_OFF = "single";
    private static final String[] PRESS_TYPE_VALUES = { "single", "long", "double" };
    /** Basma tipi seçenekleri: string-array press_type_labels (Tek, Uzun, Çift) */
    /** 360 kamera tuş atama (aynı key değerleri) */
    private static final String PREF_CAMERA_360_KEY = "camera_360_key";
    private static final String PREF_CAMERA_360_PRESS_ON  = "camera_360_press_on";
    private static final String PREF_CAMERA_360_PRESS_OFF  = "camera_360_press_off";
    private static final int    DEFAULT_CAMERA_360_KEY = -1;
    private static final int COLOR_ACTIVE   = 0xFF1F6FEB; // mavi — seçili
    private static final int COLOR_INACTIVE = 0xFF21262D; // koyu gri — seçilmemiş
    private static final int COLOR_HEAT_ON  = 0xFF9E3333; // kırmızı — ısıtma aktif

    // Tema modu (AppCompatDelegate sabitleriyle aynı)
    private int mThemeMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM; // Oto
    /** recreate() tema yüzünden tetiklendiyse onCreate'de sese dokunma (ses kesilmesin). */
    private static boolean sRecreatedDueToThemeChange = false;

    private TextView mTvStatus;
    private TextView mTvBinder;
    private TextView mTvSpeed;
    private TextView mTvSpeedTestLabel;
    private TextView mTvGaugeRpm;
    private TextView mTvGaugeSpeed;
    private TextView mTvGaugeGear;
    private TextView mTvGaugePower;
    private TextView mTvGaugeThrottle;
    private TextView mTvMotorState;

    // Yapay motor sesi
    private EngineSoundManager mEngineSound;
    private Button mBtnSoundToggle;
    private Button mBtnSoundProfile;
    private Button mBtnGearProfile;
    private Button mBtnRevMatch;
    private Button mBtnGearWhine;
    private Button mBtnSpeedTest;
    private SeekBar mSeekSoundVolume;
    private TextView mTvAlarmVolumeHint;
    private boolean mSoundEnabled = false; // Varsayılan: kapalı (kalıcı tercih SharedPreferences'tan yüklenecek)
    private final Handler mAlarmVolumeHandler = new Handler(Looper.getMainLooper());
    private final Runnable mAlarmVolumeRunnable = new Runnable() {
        @Override
        public void run() {
            if (mCurrentPanel == PANEL_SOUND && mTvAlarmVolumeHint != null) {
                refreshAlarmVolumeHint();
                mAlarmVolumeHandler.postDelayed(this, 1500);
            }
        }
    };

    // Ana ekran
    private View mLayoutMain;
    private View mLayoutSpeedTest;
    private View mLayoutSoundPanel;
    private View mLayoutIdlePanel;
    private Button mBtnMotorPower;

    // Hız simülasyonu (bilgisayarda test için)
    private boolean mSimSpeedActive = false;
    private float   mSimSpeedKmh   = 0f;

    // Regen paneli
    private View   mLayoutRegenPanel;
    private Button mBtnRegenOff;
    private Button mBtnRegenLow;
    private Button mBtnRegenMedium;
    private Button mBtnRegenHigh;
    private Button mBtnRegenAdaptive;
    private Button mBtnRegenOnePedal;
    private TextView mTvRegenCurrent;
    private Spinner mSpinnerOnePedalKey;
    private Spinner mSpinnerOnePedalPressOn;
    private Spinner mSpinnerOnePedalPressOff;
    private View   mLayoutOnePedalOptions;
    private View   mLayoutCamera360Options;
    private Spinner mSpinnerCamera360Key;
    private Spinner mSpinnerCamera360PressOn;
    private Spinner mSpinnerCamera360PressOff;

    // Şarj paneli
    private View     mLayoutStatusPanel;
    private boolean  mChargingPanelOpen = false;
    private final Handler mChargingHandler = new Handler();
    private final Runnable mChargingRunnable = new Runnable() {
        @Override
        public void run() {
            if (mChargingPanelOpen) {
                refreshStatusPanel();
                mChargingHandler.postDelayed(this, 100);
            }
        }
    };
    private TextView mTvChargingDuration;
    private TextView mTvChargingStatus;
    private TextView mTvExpectedPower;
    private TextView mTvAcVolt;
    private TextView mTvAcAmp;
    private TextView mTvAcKw;
    private TextView mTvAcEnergy;
    private TextView mTvDcVolt;
    private TextView mTvDcAmpAct;
    private TextView mTvDcKwAct;
    private TextView mTvDcEnergy;
    private LineChart mChartChargingPower;
    private final ArrayList<Entry> mChartEntriesMaxDc = new ArrayList<>();
    private final ArrayList<Entry> mChartEntriesAc = new ArrayList<>();
    private final ArrayList<Entry> mChartEntriesBatt = new ArrayList<>();
    private float mChargingChartIndex = 0f;
        /** 100 ms'de bir nokta → 6000 nokta = 10 dakika */
    private static final int CHARGING_CHART_MAX_POINTS = 6000;

    // Hangi panel açık? (yeniden yaratmada aynı ekrana dönmek için)
    private static final String STATE_PANEL = "current_panel";
    private static final int PANEL_MAIN    = 0;
    private static final int PANEL_STATUS  = 1;
    private static final int PANEL_REGEN   = 2;
    private static final int PANEL_CLIMATE = 3;
    private static final int PANEL_SOUND   = 4;
    private static final int PANEL_SHORTCUTS = 5;
    private static final int PANEL_CONSUMPTION = 6;
    private static final int PANEL_IDLE = 7;
    private int mCurrentPanel = PANEL_MAIN;

    // Tüketim paneli
    private View mLayoutConsumptionPanel;
    private TextView mTvConsumptionGear;
    private TextView mTvConsumptionTotalKm;
    private TextView mTvConsumptionKwhPerKm;
    private TextView mTvConsumptionSpeed;
    private TextView mTvConsumptionPower;
    private TextView mTvConsumptionTripKm;
    private TextView mTvConsumptionEnergy;
    private TextView mTvConsumptionAvgKwhPer100km;

    private final Handler mConsumptionHandler = new Handler();
    /** Sadece panel açıkken UI güncellemesi (seyrek yeterli); veri serviste 100ms'de bir zaten okunuyor. */
    private final Runnable mConsumptionUiRunnable = new Runnable() {
        @Override
        public void run() {
            if (mCurrentPanel == PANEL_CONSUMPTION) {
                refreshConsumptionPanel();
                mConsumptionHandler.postDelayed(this, 500);
            }
        }
    };

    // Hız güncelleme
    private final Handler mSpeedHandler = new Handler();
    private final Runnable mSpeedRunnable = new Runnable() {
        @Override
        public void run() {
            // Hız tek kaynak: MG4Hardware (servis oradan okuyor). UI için sim açıksa sim, değilse son okunan gerçek hız.
            MG4Hardware.setSimSpeed(mSimSpeedActive, mSimSpeedKmh);
            float speedForDisplay = mSimSpeedActive ? mSimSpeedKmh : MG4Hardware.getLastSpeedForDisplay();

            if (mTvSpeed != null) {
                if (Float.isNaN(speedForDisplay)) {
                    mTvSpeed.setText(getString(R.string.speed_placeholder));
                } else {
                    mTvSpeed.setText(getString(R.string.speed_format, speedForDisplay));
                }
            }
            if (!Float.isNaN(speedForDisplay) && mTvGaugeSpeed != null) {
                mTvGaugeSpeed.setText(String.format("%.2f", speedForDisplay));
            } else {
                mTvGaugeSpeed.setText(getString(R.string.speed_placeholder));
            }
            if (mEngineSound != null) {
                float dcVolt = MG4Hardware.getDcVoltage();
                float dcAmpAct = MG4Hardware.getDcCurrentActual();
                float dcPowerKw = (Float.isNaN(dcVolt) || Float.isNaN(dcAmpAct)) ? 0f : (dcVolt * dcAmpAct) / 1000f;
                if (mTvGaugeRpm != null) {
                    float rpm = mEngineSound.getCurrentRpm();
                    mTvGaugeRpm.setText(String.format("%.0f", rpm));
                }
                if (mTvGaugeGear != null) {
                    mTvGaugeGear.setText("A" + mEngineSound.getCurrentGear());
                }
                if (mTvGaugePower != null) {
                    mTvGaugePower.setText(String.format("%.2f", dcPowerKw));
                }
                if (mTvGaugeThrottle != null) {
                    float throttle = mEngineSound.getSimulatedThrottle();
                    int pct = Math.round(throttle * 100f);
                    mTvGaugeThrottle.setText(pct + "%");
                }
            }
            boolean ready = (MG4Hardware.isVehicleReady() || mSimSpeedActive);
            if (mTvMotorState != null) {
                String motorStr = ready ? getString(R.string.motor_state_ready) : getString(R.string.motor_state_off);
                int modeVal = MG4Hardware.getDriveMode();
                String modeStr = (modeVal >= 0) ? DriveMode.fromValue(modeVal).label : "";
                mTvMotorState.setText(modeStr.isEmpty() ? motorStr : motorStr + getString(R.string.motor_state_drive_suffix, modeStr));
            }
            // Ses çalma: sadece ses açıksa ve (READY veya sim) ise start, değilse stop
            if (mEngineSound != null && mSimSpeedActive) {
                if (mSoundEnabled) {
                    if (!mEngineSound.isPlaying()) {
                        mEngineSound.start();
                    }
                    // Sim modunda hız ve throttle'ı doğrudan buradan ver (RPM artsın; throttle zaten slider'dan setSimulatedThrottle ile ayarlı)
                    mEngineSound.onSpeedChanged(mSimSpeedKmh, Float.NaN);
                } else {
                    if (mEngineSound.isPlaying()) {
                        mEngineSound.stop();
                    }
                }
            }
            // 2 Hz yerine ~10 Hz güncelle (daha akıcı ses için)
            mSpeedHandler.postDelayed(this, 100);
        }
    };

    // Kısayollar paneli
    private View   mLayoutShortcutsPanel;
    // Klima paneli
    private View   mLayoutClimatePanel;
    // Direksiyon
    private Button mBtnSteerOff;
    private Button mBtnSteerL1;
    // Sol koltuk
    private Button mBtnSeatLOff;
    private Button mBtnSeatLL1;
    private Button mBtnSeatLL2;
    private Button mBtnSeatLL3;
    // Sağ koltuk
    private Button mBtnSeatROff;
    private Button mBtnSeatRL1;
    private Button mBtnSeatRL2;
    private Button mBtnSeatRL3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences prefs = getSharedPreferences("mg4_v3", MODE_PRIVATE);
        mThemeMode = prefs.getInt(PREF_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        AppCompatDelegate.setDefaultNightMode(mThemeMode);
        String lang = prefs.getString(PREF_LANGUAGE, "");
        if (!lang.isEmpty()) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang));
        }

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mTvStatus = findViewById(R.id.tvStatus);
        mTvBinder = findViewById(R.id.tvBinderStatus);
        mTvSpeed  = findViewById(R.id.tvSpeed);
        mTvSpeedTestLabel = findViewById(R.id.tvSpeedTestLabel);
        mTvGaugeRpm = findViewById(R.id.tvGaugeRpm);
        mTvGaugeSpeed = findViewById(R.id.tvGaugeSpeed);
        mTvGaugeGear = findViewById(R.id.tvGaugeGear);
        mTvGaugePower = findViewById(R.id.tvGaugePower);
        mTvGaugeThrottle = findViewById(R.id.tvGaugeThrottle);
        mTvMotorState = findViewById(R.id.tvMotorState);

        // Motor sesi butonları (ses paneli içinde)
        mBtnSoundToggle = findViewById(R.id.btnSoundToggle);
        mBtnSoundProfile = findViewById(R.id.btnSoundProfile);
        mBtnGearProfile = findViewById(R.id.btnGearProfile);
        mBtnRevMatch = findViewById(R.id.btnRevMatch);
        mBtnGearWhine = findViewById(R.id.btnGearWhine);
        mBtnSpeedTest = findViewById(R.id.btnSpeedTest);
        mLayoutSpeedTest = findViewById(R.id.layoutSpeedTest);
        mSeekSoundVolume = findViewById(R.id.seekSoundVolume);
        mTvAlarmVolumeHint = findViewById(R.id.tvAlarmVolumeHint);
        Button btnAlarmVolumeMax = findViewById(R.id.btnAlarmVolumeMax);
        if (btnAlarmVolumeMax != null) {
            btnAlarmVolumeMax.setOnClickListener(v -> trySetAlarmVolumeMax());
        }
        mBtnMotorPower = findViewById(R.id.btnMotorPower);

        // Yapay motor sesi yöneticisini başlat (önce instance al)
        mEngineSound = EngineSoundManager.getInstance(this);

        SharedPreferences prefsApp = getSharedPreferences("mg4_v3", MODE_PRIVATE);

        // Şanzıman karakteri: Eco / Normal / Sport (döngüsel)
        String savedChar = prefsApp.getString("sound_character", "NORMAL");
        applySoundCharacter(savedChar);
        updateSoundCharacterButtonText(savedChar);
        mBtnGearProfile.setOnClickListener(v -> cycleSoundCharacter());

        // Araç servislerini başlat
        MG4Hardware.init(this);

        // Son kaydedilen motor sesi / log ayarlarını yükle (kalıcı)
        SharedPreferences prefsSound = prefsApp;
        mSoundEnabled = prefsSound.getBoolean(PREF_SOUND_ENABLED, false);
        SoundMode savedMode = soundModeFromString(prefsSound.getString(PREF_SOUND_MODE, "VIRTUAL_GEAR_V2"));
        // Loglar varsayılan olarak KAPALI olsun
        boolean logsEnabled = prefsSound.getBoolean("logs_enabled", false);
        int savedMaster = prefsSound.getInt(PREF_SOUND_MASTER, 60);
        float savedMotorPower = prefsSound.getFloat(PREF_MOTOR_POWER, 150f);
        MG4Hardware.setLogEnabled(logsEnabled);
        updateSoundToggleButton();

        // Motor gücü düğmesi (125 kW / 150 kW, hafızalı)
        if (mBtnMotorPower != null) {
            // Başlangıç text'i
            float initialPower = savedMotorPower;
            if (initialPower < 50f || initialPower > 200f) {
                initialPower = 150f;
            }
            int displayKw = (int) initialPower;
            mBtnMotorPower.setText(getString(R.string.motor_power_format, displayKw));
            if (mEngineSound != null) {
                mEngineSound.setMotorMaxPower(initialPower);
            }

            final float currentPowerInit = initialPower;
            mBtnMotorPower.setOnClickListener(v -> {
                SharedPreferences p = getSharedPreferences("mg4_v3", MODE_PRIVATE);
                float current = p.getFloat(PREF_MOTOR_POWER, currentPowerInit);
                float next = (current >= 149f) ? 125f : 150f;
                int showKw = (int) next;
                mBtnMotorPower.setText(getString(R.string.motor_power_format, showKw));
                if (mEngineSound != null) {
                    mEngineSound.setMotorMaxPower(next);
                }
                p.edit().putFloat(PREF_MOTOR_POWER, next).apply();
            });
        }

        // Hız test paneli (simülasyon) — Ses Kapa'nın üstünde; simüle hız + simüle gaz
        SeekBar seekSpeedTest = findViewById(R.id.seekSpeedTest);
        SeekBar seekThrottleTest = findViewById(R.id.seekThrottleTest);
        if (mBtnSpeedTest != null && seekSpeedTest != null && mLayoutSpeedTest != null) {
            mBtnSpeedTest.setOnClickListener(v -> {
                boolean opening = mLayoutSpeedTest.getVisibility() != View.VISIBLE;
                mLayoutSpeedTest.setVisibility(opening ? View.VISIBLE : View.GONE);
                mSimSpeedActive = opening;
                if (opening && seekSpeedTest != null) {
                    mSimSpeedKmh = seekSpeedTest.getProgress();
                }
                if (mEngineSound != null) {
                    mEngineSound.setUseManualThrottle(opening);
                    if (opening && seekThrottleTest != null) {
                        mEngineSound.setSimulatedThrottle(seekThrottleTest.getProgress() / 100f);
                    }
                }
                if (!opening) {
                    mSimSpeedKmh = 0f;
                }
            });

            seekSpeedTest.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    float speed = progress;
                    mSimSpeedActive = true;
                    mSimSpeedKmh = speed;
                    if (mTvSpeedTestLabel != null) {
                        mTvSpeedTestLabel.setText(getString(R.string.sim_speed_format, speed));
                    }
                    if (mSoundEnabled && mEngineSound != null && !mEngineSound.isPlaying()) {
                        mEngineSound.start();
                    }
                }

                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });

            if (seekThrottleTest != null && mEngineSound != null) {
                seekThrottleTest.setProgress(50); // Varsayılan %50
                seekThrottleTest.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        float throttle = progress / 100f;
                        mEngineSound.setSimulatedThrottle(throttle);
                        if (mSoundEnabled && mEngineSound != null && !mEngineSound.isPlaying()) {
                            mEngineSound.start();
                        }
                    }

                    @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                    @Override public void onStopTrackingTouch(SeekBar seekBar) {}
                });
            }
        }

        // Motor sesi aç/kapa butonu
        mBtnSoundToggle.setOnClickListener(v -> {
            mSoundEnabled = !mSoundEnabled;
            getSharedPreferences("mg4_v3", MODE_PRIVATE).edit().putBoolean(PREF_SOUND_ENABLED, mSoundEnabled).apply();
            if (mSoundEnabled) {
                // Ses sadece araç READY olduğunda gerçekten çalsın;
                // değilse tercih ON kalır, READY olunca mSpeedRunnable içinde otomatik başlar.
                if ((MG4Hardware.isVehicleReady() || mSimSpeedActive) && !mEngineSound.isPlaying()) {
                    mEngineSound.start();
                }
                Toast.makeText(this, getString(R.string.toast_motor_sound_on), Toast.LENGTH_SHORT).show();
            } else {
                if (mEngineSound.isPlaying()) {
                    mEngineSound.stop();
                }
                Toast.makeText(this, getString(R.string.toast_motor_sound_off), Toast.LENGTH_SHORT).show();
            }
            updateSoundToggleButton();
        });

        if (mBtnSoundProfile != null) {
            mBtnSoundProfile.setOnClickListener(v -> showSoundProfileDialog());
            String savedSoundProfile = prefsSound.getString(PREF_SOUND_PROFILE, "McLaren P1");
            mBtnSoundProfile.setText(getString(R.string.vehicle_sound_format, savedSoundProfile));
            // Tema değişimi (kullanıcı butonu veya Oto'da araç/sistem teması) ile recreate olduysa sese dokunma
            boolean skipSoundBecauseRecreate = sRecreatedDueToThemeChange || (savedInstanceState != null);
            if (!skipSoundBecauseRecreate) {
                mEngineSound.applyProfileLabel(savedSoundProfile);
            }
            if (sRecreatedDueToThemeChange) sRecreatedDueToThemeChange = false;
        }

        // Devir eşleme toggle (hafızalı)
        if (mBtnRevMatch != null) {
            boolean revMatchEnabled = prefsApp.getBoolean("rev_match_enabled", true);
            mEngineSound.setRevMatchEnabled(revMatchEnabled);
            updateRevMatchButtonText(revMatchEnabled);
            mBtnRevMatch.setOnClickListener(v -> {
                boolean current = mEngineSound.isRevMatchEnabled();
                boolean next = !current;
                mEngineSound.setRevMatchEnabled(next);
                prefsApp.edit().putBoolean("rev_match_enabled", next).apply();
                updateRevMatchButtonText(next);
            });
        }

        // Dişli ıslığı toggle (hafızalı)
        if (mBtnGearWhine != null) {
            boolean whineEnabled = prefsApp.getBoolean("gear_whine_enabled", false);
            mEngineSound.setGearWhineEnabled(whineEnabled);
            updateGearWhineButtonText(whineEnabled);
            mBtnGearWhine.setOnClickListener(v -> {
                boolean current = mEngineSound.isGearWhineEnabled();
                boolean next = !current;
                mEngineSound.setGearWhineEnabled(next);
                prefsApp.edit().putBoolean("gear_whine_enabled", next).apply();
                updateGearWhineButtonText(next);
            });
        }

        // Master volume slider (0–100) — hafızalı
        if (mSeekSoundVolume != null) {
            int clamped = Math.max(0, Math.min(100, savedMaster));
            mSeekSoundVolume.setProgress(clamped);
            mEngineSound.setMasterVolume(clamped / 100f);

            mSeekSoundVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int p = Math.max(0, Math.min(100, progress));
                    mEngineSound.setMasterVolume(p / 100f);
                    prefsSound.edit().putInt(PREF_SOUND_MASTER, p).apply();
                }

                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        // Hız döngüsünü başlat (UI açık/kapalı fark etmeksizin sürekli çalışsın)
        mSpeedHandler.post(mSpeedRunnable);
        // Tüketim integrasyonu serviste 100ms'de bir (boot'tan itibaren); burada sadece UI döngüsü panel açıkken

        // Log (detay) switch'i
        SwitchCompat swLogs = findViewById(R.id.switchLogs);
        if (swLogs != null) {
            swLogs.setChecked(logsEnabled);
            swLogs.setOnCheckedChangeListener((buttonView, isChecked) -> {
                getSharedPreferences("mg4_v3", MODE_PRIVATE)
                        .edit().putBoolean("logs_enabled", isChecked).apply();
                MG4Hardware.setLogEnabled(isChecked);
            });
        }

        // Tema butonları: Gündüz / Gece / Oto (araç sistemine göre)
        Button btnThemeDay  = findViewById(R.id.btnThemeDay);
        Button btnThemeNight = findViewById(R.id.btnThemeNight);
        Button btnThemeAuto  = findViewById(R.id.btnThemeAuto);
        btnThemeDay.setOnClickListener(v  -> applyThemeMode(AppCompatDelegate.MODE_NIGHT_NO));
        btnThemeNight.setOnClickListener(v -> applyThemeMode(AppCompatDelegate.MODE_NIGHT_YES));
        btnThemeAuto.setOnClickListener(v  -> applyThemeMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));
        updateThemeButtons();
        Button btnLangTr = findViewById(R.id.btnLangTr);
        Button btnLangEn = findViewById(R.id.btnLangEn);
        if (btnLangTr != null) btnLangTr.setOnClickListener(v -> applyLanguage("tr"));
        if (btnLangEn != null) btnLangEn.setOnClickListener(v -> applyLanguage("en"));
        updateLanguageButtons();

        // Versiyon numarasını göster
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            TextView tvVersion = findViewById(R.id.tvVersion);
            tvVersion.setText(getString(R.string.version_format, pInfo.versionName));
        } catch (Exception ignored) {}

        // Servisi otomatik başlat
        startForegroundService(new Intent(this, MG4ControlService.class));
        mTvStatus.setText("✅ " + getString(R.string.status_service_ok));

        // Ana layout referansı
        mLayoutMain = findViewById(R.id.layoutMain);
        mLayoutSoundPanel = findViewById(R.id.layoutSoundPanel);

        // Ana ekran butonları
        findViewById(R.id.btnShortcuts).setOnClickListener(v -> openShortcutsPanel());
        findViewById(R.id.btnDrive).setOnClickListener(v     -> sendDriveMode(DriveMode.CUSTOM));
findViewById(R.id.btnEco).setOnClickListener(v       -> sendDriveMode(DriveMode.ECO));
        findViewById(R.id.btnNormal).setOnClickListener(v    -> sendDriveMode(DriveMode.NORMAL));
        findViewById(R.id.btnSport).setOnClickListener(v     -> sendDriveMode(DriveMode.SPORT));
        findViewById(R.id.btnSnow).setOnClickListener(v      -> sendDriveMode(DriveMode.SNOW));
        findViewById(R.id.btnSoundPanel).setOnClickListener(v -> openSoundPanel());
        findViewById(R.id.btnSoundBack).setOnClickListener(v -> closeSoundPanel());
        mLayoutIdlePanel = findViewById(R.id.layoutIdlePanel);
        findViewById(R.id.btnIdleSettings).setOnClickListener(v -> openIdlePanel());
        findViewById(R.id.btnIdlePanelBack).setOnClickListener(v -> closeIdlePanel());
        findViewById(R.id.btnIdleResetAll).setOnClickListener(v -> resetAllIdleProfiles());

        // Şarj paneli
        mLayoutStatusPanel  = findViewById(R.id.layoutStatusPanel);
        mTvChargingDuration = findViewById(R.id.tvChargingDuration);
        mTvChargingStatus   = findViewById(R.id.tvChargingStatus);
        mTvExpectedPower    = findViewById(R.id.tvExpectedPower);
        mTvAcVolt          = findViewById(R.id.tvAcVolt);
        mTvAcAmp           = findViewById(R.id.tvAcAmp);
        mTvAcKw            = findViewById(R.id.tvAcKw);
        mTvAcEnergy        = findViewById(R.id.tvAcEnergy);
        mTvDcVolt          = findViewById(R.id.tvDcVolt);
        mTvDcAmpAct        = findViewById(R.id.tvDcAmpAct);
        mTvDcKwAct         = findViewById(R.id.tvDcKwAct);
        mTvDcEnergy        = findViewById(R.id.tvDcEnergy);
        mChartChargingPower = findViewById(R.id.chartChargingPower);
        setupChargingCharts();

        findViewById(R.id.btnStatusPanel).setOnClickListener(v -> openStatusPanel());
        findViewById(R.id.btnStatusBack).setOnClickListener(v  -> closeStatusPanel());
        findViewById(R.id.btnClimatePanel).setOnClickListener(v -> openClimatePanel());

        // ---- Tüketim paneli ----
        mLayoutConsumptionPanel   = findViewById(R.id.layoutConsumptionPanel);
        mTvConsumptionGear        = findViewById(R.id.tvConsumptionGear);
        mTvConsumptionTotalKm     = findViewById(R.id.tvConsumptionTotalKm);
        mTvConsumptionKwhPerKm    = findViewById(R.id.tvConsumptionKwhPerKm);
        mTvConsumptionSpeed       = findViewById(R.id.tvConsumptionSpeed);
        mTvConsumptionPower       = findViewById(R.id.tvConsumptionPower);
        mTvConsumptionTripKm      = findViewById(R.id.tvConsumptionTripKm);
        mTvConsumptionEnergy      = findViewById(R.id.tvConsumptionEnergy);
        findViewById(R.id.btnConsumptionPanel).setOnClickListener(v -> openConsumptionPanel());
        findViewById(R.id.btnConsumptionBack).setOnClickListener(v -> closeConsumptionPanel());
        findViewById(R.id.btnConsumptionResetTrip).setOnClickListener(v -> resetConsumptionTrip());

        // ---- Regen paneli ----
        mLayoutRegenPanel = findViewById(R.id.layoutRegenPanel);
        mBtnRegenOff      = findViewById(R.id.btnRegenOff);
        mBtnRegenLow      = findViewById(R.id.btnRegenLow);
        mBtnRegenMedium   = findViewById(R.id.btnRegenMedium);
        mBtnRegenHigh     = findViewById(R.id.btnRegenHigh);
        mBtnRegenAdaptive = findViewById(R.id.btnRegenAdaptive);
        mBtnRegenOnePedal = findViewById(R.id.btnRegenOnePedal);
        mTvRegenCurrent   = findViewById(R.id.tvRegenCurrent);

        findViewById(R.id.btnRegenPanel).setOnClickListener(v -> openRegenPanel());
        findViewById(R.id.btnRegenBack).setOnClickListener(v  -> closeRegenPanel());

        mLayoutShortcutsPanel = findViewById(R.id.layoutShortcutsPanel);
        findViewById(R.id.btnShortcutsBack).setOnClickListener(v -> closeShortcutsPanel());

        mBtnRegenOff.setOnClickListener(v      -> selectRegen(RegenLevel.OFF));
        mBtnRegenLow.setOnClickListener(v      -> selectRegen(RegenLevel.LOW));
        mBtnRegenMedium.setOnClickListener(v   -> selectRegen(RegenLevel.MEDIUM));
        mBtnRegenHigh.setOnClickListener(v     -> selectRegen(RegenLevel.HIGH));
        mBtnRegenAdaptive.setOnClickListener(v -> selectRegen(RegenLevel.ADAPTIVE));
        mBtnRegenOnePedal.setOnClickListener(v -> {
            sendCommand("PEDAL_ON");
            Toast.makeText(this, getString(R.string.toast_one_pedal_on), Toast.LENGTH_SHORT).show();
            highlightRegenButton(mBtnRegenOnePedal);
            mTvRegenCurrent.setText(getString(R.string.one_pedal_active));
        });

        mSpinnerOnePedalKey     = findViewById(R.id.spinnerOnePedalKey);
        mSpinnerOnePedalPressOn = findViewById(R.id.spinnerOnePedalPressOn);
        mSpinnerOnePedalPressOff= findViewById(R.id.spinnerOnePedalPressOff);
        mLayoutOnePedalOptions  = findViewById(R.id.layoutOnePedalOptions);
        mLayoutCamera360Options = findViewById(R.id.layoutCamera360Options);
        mSpinnerCamera360Key    = findViewById(R.id.spinnerCamera360Key);
        mSpinnerCamera360PressOn = findViewById(R.id.spinnerCamera360PressOn);
        mSpinnerCamera360PressOff= findViewById(R.id.spinnerCamera360PressOff);
        setupOnePedalKeyPrefs();
        setupCamera360KeyPrefs();
        updateShortcutsPanelVisibility();

        // ---- Klima paneli ----
        mLayoutClimatePanel = findViewById(R.id.layoutClimatePanel);
        // Direksiyon
        mBtnSteerOff = findViewById(R.id.btnSteerOff);
        mBtnSteerL1  = findViewById(R.id.btnSteerL1);
        // Sol koltuk
        mBtnSeatLOff = findViewById(R.id.btnSeatLOff);
        mBtnSeatLL1  = findViewById(R.id.btnSeatLL1);
        mBtnSeatLL2  = findViewById(R.id.btnSeatLL2);
        mBtnSeatLL3  = findViewById(R.id.btnSeatLL3);
        // Sağ koltuk
        mBtnSeatROff = findViewById(R.id.btnSeatROff);
        mBtnSeatRL1  = findViewById(R.id.btnSeatRL1);
        mBtnSeatRL2  = findViewById(R.id.btnSeatRL2);
        mBtnSeatRL3  = findViewById(R.id.btnSeatRL3);

        // Overlay görünürlüğü (klima ekranı üstünde)
        SwitchCompat swOverlay = findViewById(R.id.switchOverlay);
        if (swOverlay != null) {
            SharedPreferences p = getSharedPreferences("mg4_v3", MODE_PRIVATE);
            boolean enabled = p.getBoolean(PREF_OVERLAY_ENABLED, false);
            swOverlay.setChecked(enabled);
            swOverlay.setOnCheckedChangeListener((buttonView, isChecked) -> {
                p.edit().putBoolean(PREF_OVERLAY_ENABLED, isChecked).apply();
                Intent i = new Intent(this, MG4ControlService.class);
                i.setAction(isChecked ? "OVERLAY_ON" : "OVERLAY_OFF");
                startService(i);
            });
        }

        // Şarj durumu paneli: uyuma engelle toggle (hafızalı) + şarj geçmişi butonu
        SwitchCompat swChargingWakeLock = findViewById(R.id.switchChargingWakeLock);
        if (swChargingWakeLock != null) {
            SharedPreferences p = getSharedPreferences("mg4_v3", MODE_PRIVATE);
            boolean wakeLockEnabled = p.getBoolean(MG4ControlService.PREF_CHARGING_WAKE_LOCK, false);
            swChargingWakeLock.setChecked(wakeLockEnabled);
            swChargingWakeLock.setOnCheckedChangeListener((buttonView, isChecked) -> {
                p.edit().putBoolean(MG4ControlService.PREF_CHARGING_WAKE_LOCK, isChecked).apply();
            });
        }
        Button btnShowHistory = findViewById(R.id.btnShowChargingHistory);
        if (btnShowHistory != null) {
            btnShowHistory.setOnClickListener(v -> startActivity(new Intent(this, ChargingHistoryActivity.class)));
        }

        findViewById(R.id.btnClimateBack).setOnClickListener(v -> closeClimatePanel());

        // Direksiyon buton listener'ları
        mBtnSteerOff.setOnClickListener(v -> selectSteerHeat(0));
        mBtnSteerL1.setOnClickListener(v  -> selectSteerHeat(1));

        // Sol koltuk listener'ları
        mBtnSeatLOff.setOnClickListener(v -> selectSeatLeft(0));
        mBtnSeatLL1.setOnClickListener(v  -> selectSeatLeft(1));
        mBtnSeatLL2.setOnClickListener(v  -> selectSeatLeft(2));
        mBtnSeatLL3.setOnClickListener(v  -> selectSeatLeft(3));

        // Sağ koltuk listener'ları
        mBtnSeatROff.setOnClickListener(v -> selectSeatRight(0));
        mBtnSeatRL1.setOnClickListener(v  -> selectSeatRight(1));
        mBtnSeatRL2.setOnClickListener(v  -> selectSeatRight(2));
        mBtnSeatRL3.setOnClickListener(v  -> selectSeatRight(3));

        // Yeniden yaratıldıysa hangi panelde kaldıysak oraya dön
        if (savedInstanceState != null) {
            mCurrentPanel = savedInstanceState.getInt(STATE_PANEL, PANEL_MAIN);
            restorePanelAfterRecreate();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_PANEL, mCurrentPanel);
    }

    /** Tema değişimi / rotate sonrası hangi panelde kaldıysak oraya geri dön. */
    private void restorePanelAfterRecreate() {
        switch (mCurrentPanel) {
            case PANEL_STATUS:
                openStatusPanel();
                break;
            case PANEL_REGEN:
                openRegenPanel();
                break;
            case PANEL_CLIMATE:
                openClimatePanel();
                break;
            case PANEL_SHORTCUTS:
                openShortcutsPanel();
                break;
            case PANEL_CONSUMPTION:
                openConsumptionPanel();
                break;
            case PANEL_MAIN:
            default:
                mLayoutMain.setVisibility(View.VISIBLE);
                mLayoutStatusPanel.setVisibility(View.GONE);
                if (mLayoutRegenPanel != null) mLayoutRegenPanel.setVisibility(View.GONE);
                if (mLayoutShortcutsPanel != null) mLayoutShortcutsPanel.setVisibility(View.GONE);
                if (mLayoutClimatePanel != null) mLayoutClimatePanel.setVisibility(View.GONE);
                if (mLayoutConsumptionPanel != null) mLayoutConsumptionPanel.setVisibility(View.GONE);
                mChargingPanelOpen = false;
                mChargingHandler.removeCallbacks(mChargingRunnable);
                mConsumptionHandler.removeCallbacks(mConsumptionUiRunnable);
                break;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mTvStatus.setText("✅ " + getString(R.string.status_service_ok));
        // Yapay motor sesini başlat (eğer açıksa)
        if (mEngineSound != null && mSoundEnabled) {
            mEngineSound.start();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mChargingHandler.removeCallbacks(mChargingRunnable);
        mConsumptionHandler.removeCallbacks(mConsumptionUiRunnable);
        mSpeedHandler.removeCallbacks(mSpeedRunnable);
        // Motor sesini durdurmuyoruz: açıksa arkada çalmaya devam etsin (sadece kullanıcı kapatınca dursun)
        mEngineSound = null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Arka plana geçince ekran uyuma flag'ini kaldır (şarj ekranı açık olsa bile)
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); //new_flag
        // Bilerek hız/ses döngüsünü durdurmuyoruz: arka planda da devam etsin.
    }

    // -------------------------------------------------------------------------
    // Regen paneli
    // -------------------------------------------------------------------------

    private void openRegenPanel() {
        mCurrentPanel = PANEL_REGEN;
        mLayoutMain.setVisibility(View.GONE);
        mLayoutRegenPanel.setVisibility(View.VISIBLE);

        // Mevcut regen seviyesini oku ve vurgula
        int rg = MG4Hardware.getRegenLevel();
        if (rg >= 0) {
            RegenLevel current = RegenLevel.fromValue(rg);
            highlightRegenButton(regenButton(current));
            mTvRegenCurrent.setText(getString(R.string.regen_active, current.label));
        } else {
            mTvRegenCurrent.setText("");
        }
        syncOnePedalSpinnersFromPrefs();
    }

    private void closeRegenPanel() {
        mLayoutRegenPanel.setVisibility(View.GONE);
        mLayoutMain.setVisibility(View.VISIBLE);
        mCurrentPanel = PANEL_MAIN;
    }

    private void selectRegen(RegenLevel level) {
        // Tek pedal açıksa önce kapat, sonra regen seviyesi gönder
        sendCommand("PEDAL_OFF");
        sendRegenLevel(level);
        highlightRegenButton(regenButton(level));
        mTvRegenCurrent.setText(getString(R.string.regen_active, level.label));
    }

    /** Seçilen butonu mavi, diğerlerini gri yap */
    private void highlightRegenButton(Button selected) {
        Button[] all = { mBtnRegenOff, mBtnRegenLow, mBtnRegenMedium,
                         mBtnRegenHigh, mBtnRegenAdaptive, mBtnRegenOnePedal };
        for (Button b : all) {
            b.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                    b == selected ? COLOR_ACTIVE : COLOR_INACTIVE));
            b.setTextColor(b == selected ? 0xFFFFFFFF : 0xFF8B949E);
        }
    }

    private Button regenButton(RegenLevel level) {
        switch (level) {
            case OFF:      return mBtnRegenOff;
            case LOW:      return mBtnRegenLow;
            case MEDIUM:   return mBtnRegenMedium;
            case HIGH:     return mBtnRegenHigh;
            case ADAPTIVE: return mBtnRegenAdaptive;
            default:       return mBtnRegenMedium;
        }
    }

    private void setupOnePedalKeyPrefs() {
        SharedPreferences p = getSharedPreferences("mg4_v3", MODE_PRIVATE);
        if (mSpinnerOnePedalKey != null) {
            ArrayAdapter<CharSequence> adapterKey = ArrayAdapter.createFromResource(this, R.array.one_pedal_key_labels, android.R.layout.simple_spinner_item);
            adapterKey.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            mSpinnerOnePedalKey.setAdapter(adapterKey);
            mSpinnerOnePedalKey.setSelection(indexOfOnePedalKey(p.getInt(PREF_ONE_PEDAL_KEY, DEFAULT_ONE_PEDAL_KEY)));
            mSpinnerOnePedalKey.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    p.edit().putInt(PREF_ONE_PEDAL_KEY, ONE_PEDAL_KEY_VALUES[position]).apply();
                    updateShortcutsPanelVisibility();
                }
                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        if (mSpinnerOnePedalPressOn != null) {
            ArrayAdapter<CharSequence> adapterOn = ArrayAdapter.createFromResource(this, R.array.press_type_labels, android.R.layout.simple_spinner_item);
            adapterOn.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            mSpinnerOnePedalPressOn.setAdapter(adapterOn);
            mSpinnerOnePedalPressOn.setSelection(indexOfPressType(p.getString(PREF_ONE_PEDAL_PRESS_TYPE, DEFAULT_ONE_PEDAL_PRESS_TYPE)));
            mSpinnerOnePedalPressOn.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    p.edit().putString(PREF_ONE_PEDAL_PRESS_TYPE, PRESS_TYPE_VALUES[position]).apply();
                }
                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        if (mSpinnerOnePedalPressOff != null) {
            ArrayAdapter<CharSequence> adapterOff = ArrayAdapter.createFromResource(this, R.array.press_type_labels, android.R.layout.simple_spinner_item);
            adapterOff.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            mSpinnerOnePedalPressOff.setAdapter(adapterOff);
            mSpinnerOnePedalPressOff.setSelection(indexOfPressType(p.getString(PREF_ONE_PEDAL_PRESS_TYPE_OFF, DEFAULT_ONE_PEDAL_PRESS_TYPE_OFF)));
            mSpinnerOnePedalPressOff.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    p.edit().putString(PREF_ONE_PEDAL_PRESS_TYPE_OFF, PRESS_TYPE_VALUES[position]).apply();
                }
                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        syncOnePedalSpinnersFromPrefs();
    }

    private void setupCamera360KeyPrefs() {
        SharedPreferences p = getSharedPreferences("mg4_v3", MODE_PRIVATE);
        if (mSpinnerCamera360Key != null) {
            ArrayAdapter<CharSequence> adapterKey = ArrayAdapter.createFromResource(this, R.array.one_pedal_key_labels, android.R.layout.simple_spinner_item);
            adapterKey.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            mSpinnerCamera360Key.setAdapter(adapterKey);
            mSpinnerCamera360Key.setSelection(indexOfOnePedalKey(p.getInt(PREF_CAMERA_360_KEY, DEFAULT_CAMERA_360_KEY)));
            mSpinnerCamera360Key.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    p.edit().putInt(PREF_CAMERA_360_KEY, ONE_PEDAL_KEY_VALUES[position]).apply();
                    updateShortcutsPanelVisibility();
                }
                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        if (mSpinnerCamera360PressOn != null) {
            ArrayAdapter<CharSequence> adapterOn = ArrayAdapter.createFromResource(this, R.array.press_type_labels, android.R.layout.simple_spinner_item);
            adapterOn.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            mSpinnerCamera360PressOn.setAdapter(adapterOn);
            mSpinnerCamera360PressOn.setSelection(indexOfPressType(p.getString(PREF_CAMERA_360_PRESS_ON, DEFAULT_ONE_PEDAL_PRESS_TYPE)));
            mSpinnerCamera360PressOn.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    p.edit().putString(PREF_CAMERA_360_PRESS_ON, PRESS_TYPE_VALUES[position]).apply();
                }
                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        if (mSpinnerCamera360PressOff != null) {
            ArrayAdapter<CharSequence> adapterOff = ArrayAdapter.createFromResource(this, R.array.press_type_labels, android.R.layout.simple_spinner_item);
            adapterOff.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            mSpinnerCamera360PressOff.setAdapter(adapterOff);
            mSpinnerCamera360PressOff.setSelection(indexOfPressType(p.getString(PREF_CAMERA_360_PRESS_OFF, DEFAULT_ONE_PEDAL_PRESS_TYPE_OFF)));
            mSpinnerCamera360PressOff.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    p.edit().putString(PREF_CAMERA_360_PRESS_OFF, PRESS_TYPE_VALUES[position]).apply();
                }
                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
    }

    private void updateShortcutsPanelVisibility() {
        SharedPreferences p = getSharedPreferences("mg4_v3", MODE_PRIVATE);
        int onePedalKey = p.getInt(PREF_ONE_PEDAL_KEY, DEFAULT_ONE_PEDAL_KEY);
        int camera360Key = p.getInt(PREF_CAMERA_360_KEY, DEFAULT_CAMERA_360_KEY);
        if (mLayoutOnePedalOptions != null) {
            mLayoutOnePedalOptions.setVisibility(onePedalKey != -1 ? View.VISIBLE : View.GONE);
        }
        if (mLayoutCamera360Options != null) {
            mLayoutCamera360Options.setVisibility(camera360Key != -1 ? View.VISIBLE : View.GONE);
        }
    }

    private static int indexOfOnePedalKey(int key) {
        for (int i = 0; i < ONE_PEDAL_KEY_VALUES.length; i++) {
            if (ONE_PEDAL_KEY_VALUES[i] == key) return i;
        }
        return 0;
    }

    private void syncOnePedalSpinnersFromPrefs() {
        SharedPreferences p = getSharedPreferences("mg4_v3", MODE_PRIVATE);
        if (mSpinnerOnePedalKey != null) {
            mSpinnerOnePedalKey.setSelection(indexOfOnePedalKey(p.getInt(PREF_ONE_PEDAL_KEY, DEFAULT_ONE_PEDAL_KEY)));
        }
        if (mSpinnerOnePedalPressOn != null && mSpinnerOnePedalPressOff != null) {
            mSpinnerOnePedalPressOn.setSelection(indexOfPressType(p.getString(PREF_ONE_PEDAL_PRESS_TYPE, DEFAULT_ONE_PEDAL_PRESS_TYPE)));
            mSpinnerOnePedalPressOff.setSelection(indexOfPressType(p.getString(PREF_ONE_PEDAL_PRESS_TYPE_OFF, DEFAULT_ONE_PEDAL_PRESS_TYPE_OFF)));
        }
    }

    private static int indexOfPressType(String value) {
        for (int i = 0; i < PRESS_TYPE_VALUES.length; i++) {
            if (PRESS_TYPE_VALUES[i].equals(value)) return i;
        }
        return 0;
    }

    // -------------------------------------------------------------------------
    // Durum / Şarj paneli
    // -------------------------------------------------------------------------

    private void openStatusPanel() {
        mCurrentPanel = PANEL_STATUS;
        mLayoutMain.setVisibility(View.GONE);
        mLayoutStatusPanel.setVisibility(View.VISIBLE);
        mChargingPanelOpen = true;
        refreshStatusPanel();
        updateKeepScreenOn();//new_flag
        mChargingHandler.postDelayed(mChargingRunnable, 100);
    }

    private void closeStatusPanel() {
        mChargingPanelOpen = false;
        mChargingHandler.removeCallbacks(mChargingRunnable);
        updateKeepScreenOn();//new_flag
        mLayoutStatusPanel.setVisibility(View.GONE);
        mLayoutMain.setVisibility(View.VISIBLE);
        mCurrentPanel = PANEL_MAIN;
    }

    /** Şarj paneli açık + ayar açık + şarjda ise ekran uyumasın (FLAG_KEEP_SCREEN_ON). */
    private void updateKeepScreenOn() {//new_flag
        boolean keepOn = mChargingPanelOpen
                && getSharedPreferences("mg4_v3", MODE_PRIVATE).getBoolean(MG4ControlService.PREF_CHARGING_WAKE_LOCK, false)
                && MG4Hardware.isChargingNow();
        if (keepOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void refreshStatusPanel() {
        // Şarj bittiğinde oturumu geçmişe kaydet; yeni kayıt eklendiyse tabloyu güncelle
        ChargingHistory.checkAndSaveSessionIfEnded(this);

        // DC voltaj (her iki sütun için gerekli)
        float dcVolt   = MG4Hardware.getDcVoltage();
        float dcAmpAct = MG4Hardware.getDcCurrentActual();
        float dcAmpExp = MG4Hardware.getDcCurrentExpected();
        float acVolt   = MG4Hardware.getAcVoltage();
        float acAmp    = MG4Hardware.getAcCurrent();
        // BMS gelip gelmediğini görmek için log (filtre: tag:MG4_UI veya MG4_HW)
        if (MG4Hardware.isLogEnabled()) {
            Log.i(TAG, "StatusPanel: dcV=" + dcVolt + " acV=" + acVolt + " dcAact=" + dcAmpAct + " acA=" + acAmp);
        }

        // Beklenen Şarj Gücü (üstteki metin)
        if (!Float.isNaN(dcVolt) && !Float.isNaN(dcAmpExp)) {
            float expKw = (dcVolt * dcAmpExp) / 1000f;
            mTvExpectedPower.setText(getString(R.string.expected_charge_power_format, expKw));
        } else {
            mTvExpectedPower.setText(getString(R.string.expected_charge_power));
        }

        // AC sütunu (acVolt, acAmp yukarıda alındı)
        mTvAcVolt.setText(Float.isNaN(acVolt) ? "--" : String.format("%.0f V", acVolt));
        mTvAcAmp.setText(Float.isNaN(acAmp)   ? "--" : String.format("%.1f A", acAmp));
        if (!Float.isNaN(acVolt) && !Float.isNaN(acAmp)) {
            mTvAcKw.setText(String.format("%.3f kW", (acVolt * acAmp) / 1000f));
        } else {
            mTvAcKw.setText("--");
        }

        // Batarya sütunu
        mTvDcVolt.setText(Float.isNaN(dcVolt)     ? "--" : String.format("%.2f V", dcVolt));
        mTvDcAmpAct.setText(Float.isNaN(dcAmpAct) ? "--" : String.format("%.2f A", dcAmpAct));
        if (!Float.isNaN(dcVolt) && !Float.isNaN(dcAmpAct)) {
            mTvDcKwAct.setText(String.format("%.3f kW", (dcVolt * dcAmpAct) / 1000f));
        } else {
            mTvDcKwAct.setText("--");
        }

        // Enerji satırları (şarj boyunca biriken)
        float acEnergy = MG4Hardware.getAcEnergyKwh();
        float dcEnergy = MG4Hardware.getDcEnergyKwh();
        mTvAcEnergy.setText(acEnergy > 0f ? String.format("%.3f kWh", acEnergy) : "--");
        mTvDcEnergy.setText(dcEnergy > 0f ? String.format("%.3f kWh", dcEnergy) : "--");

        // Şarj durumu (çıkarım: AC/DC akım veya PROP_CHG_STATUS)
        boolean charging = MG4Hardware.isChargingNow();
        mTvChargingStatus.setText(charging ? getString(R.string.charging) : getString(R.string.not_charging));
        mTvChargingStatus.setTextColor(charging ? 0xFF7EE787 : 0xFF8B949E);

        // Şarj süresi (sağ üst köşe)
        long totalSec = MG4Hardware.getChargingDurationMs() / 1000;
        if (totalSec > 0) {
            long h = totalSec / 3600;
            long m = (totalSec % 3600) / 60;
            long s = totalSec % 60;
            mTvChargingDuration.setText(String.format("%02d:%02d:%02d", h, m, s));
        } else {
            mTvChargingDuration.setText("--:--:--");
        }

        // Şarjdayken + ayar açıksa ekran uyanık kalsın
        updateKeepScreenOn();

        // Güç grafiklerini güncelle (kW)
        float maxDcKw = (!Float.isNaN(dcVolt) && !Float.isNaN(dcAmpExp)) ? (dcVolt * dcAmpExp) / 1000f : Float.NaN;
        float acKw    = (!Float.isNaN(acVolt) && !Float.isNaN(acAmp))   ? (acVolt * acAmp) / 1000f : Float.NaN;
        float battKw = (!Float.isNaN(dcVolt) && !Float.isNaN(dcAmpAct))   ? (dcVolt * dcAmpAct) / 1000f : Float.NaN;
        updateChargingCharts(maxDcKw, acKw, battKw);
    }

    private static final int COLOR_CHART_MAX_DC = 0xFFFFA657;
    private static final int COLOR_CHART_AC     = 0xFF7EE787;
    private static final int COLOR_CHART_BATT   = 0xFF58A6FF;

    private void setupChargingCharts() {
        if (mChartChargingPower == null) return;
        int textColor = 0xFF8B949E;
        LineChart chart = mChartChargingPower;
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(true);
        chart.getLegend().setTextColor(textColor);
        chart.getLegend().setTextSize(10f);
        chart.setTouchEnabled(false);
        chart.setDrawGridBackground(false);
        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setTextColor(textColor);
        xAxis.setDrawGridLines(false);
        xAxis.setGranularity(1f);
        YAxis yAxisL = chart.getAxisLeft();
        yAxisL.setTextColor(textColor);
        yAxisL.setDrawGridLines(true);
        yAxisL.setGridColor(0x408B949E);
        chart.getAxisRight().setEnabled(false);
    }

    private static LineDataSet makeDataSet(ArrayList<Entry> entries, int color, String label) {
        LineDataSet set = new LineDataSet(entries, label);
        set.setColor(color);
        set.setLineWidth(1.5f);
        set.setDrawCircles(false);
        set.setDrawValues(false);
        set.setMode(LineDataSet.Mode.LINEAR);
        return set;
    }

    private void updateChargingCharts(float maxDcKw, float acKw, float battKw) {
        if (mChartChargingPower == null) return;
        float x = mChargingChartIndex++;
        // Grafikte şarj gücü negatif aksında gösterilsin: beklenen gücü de negatif tarafta çiz.
        if (!Float.isNaN(maxDcKw)) {
            float maxDcForChart = (maxDcKw > 0f) ? -maxDcKw : maxDcKw;
            mChartEntriesMaxDc.add(new Entry(x, maxDcForChart));
        }
        if (!Float.isNaN(acKw))    mChartEntriesAc.add(new Entry(x, acKw));
        if (!Float.isNaN(battKw))  mChartEntriesBatt.add(new Entry(x, battKw));
        trimChartEntries(mChartEntriesMaxDc);
        trimChartEntries(mChartEntriesAc);
        trimChartEntries(mChartEntriesBatt);

        LineData data = new LineData();
        data.addDataSet(makeDataSet(mChartEntriesMaxDc, COLOR_CHART_MAX_DC, getString(R.string.chart_legend_max_dc)));
        if (acKw > 0f) {
            data.addDataSet(makeDataSet(mChartEntriesAc, COLOR_CHART_AC, getString(R.string.chart_legend_ac)));
        }
        data.addDataSet(makeDataSet(mChartEntriesBatt, COLOR_CHART_BATT, getString(R.string.chart_legend_batt)));
        mChartChargingPower.setData(data);
        mChartChargingPower.invalidate();
    }

    private void trimChartEntries(ArrayList<Entry> entries) {
        float minX = mChargingChartIndex - CHARGING_CHART_MAX_POINTS;
        while (!entries.isEmpty() && entries.get(0).getX() < minX) {
            entries.remove(0);
        }
        if (entries.isEmpty()) return;
        float base = entries.get(0).getX();
        for (Entry e : entries) {
            e.setX(e.getX() - base);
        }
    }

    // -------------------------------------------------------------------------
    // Klima paneli
    // -------------------------------------------------------------------------

    private void openClimatePanel() {
        mCurrentPanel = PANEL_CLIMATE;
        mLayoutMain.setVisibility(View.GONE);
        mLayoutClimatePanel.setVisibility(View.VISIBLE);
        // Tüm butonlar başlangıçta gri (mevcut durum okunamıyor henüz)
        highlightSteerButton(null);
        highlightSeatLButton(null);
        highlightSeatRButton(null);
    }

    private void closeClimatePanel() {
        mLayoutClimatePanel.setVisibility(View.GONE);
        mLayoutMain.setVisibility(View.VISIBLE);
        mCurrentPanel = PANEL_MAIN;
    }

    private void openShortcutsPanel() {
        mCurrentPanel = PANEL_SHORTCUTS;
        updateShortcutsPanelVisibility();
        mLayoutMain.setVisibility(View.GONE);
        mLayoutShortcutsPanel.setVisibility(View.VISIBLE);
    }

    private void closeShortcutsPanel() {
        mLayoutShortcutsPanel.setVisibility(View.GONE);
        mLayoutMain.setVisibility(View.VISIBLE);
        mCurrentPanel = PANEL_MAIN;
    }

    // -------------------------------------------------------------------------
    // Tüketim paneli (hız, güç, yol km, enerji integre, vites)
    // -------------------------------------------------------------------------

    private void openConsumptionPanel() {
        mCurrentPanel = PANEL_CONSUMPTION;
        mLayoutMain.setVisibility(View.GONE);
        mLayoutConsumptionPanel.setVisibility(View.VISIBLE);
        MG4Hardware.ensureConsumptionTripStarted();
        refreshConsumptionPanel();
        mConsumptionHandler.postDelayed(mConsumptionUiRunnable, 500);
    }

    private void closeConsumptionPanel() {
        mConsumptionHandler.removeCallbacks(mConsumptionUiRunnable);
        mLayoutConsumptionPanel.setVisibility(View.GONE);
        mLayoutMain.setVisibility(View.VISIBLE);
        mCurrentPanel = PANEL_MAIN;
    }

    private void resetConsumptionTrip() {
        MG4Hardware.resetConsumptionTrip();
        Toast.makeText(this, "Yol ve enerji sıfırlandı", Toast.LENGTH_SHORT).show();
    }

    /** Panelde servisin güncellediği önbellek + trip enerji gösterilir (UI 500ms'de bir). */
    private void refreshConsumptionPanel() {
        int totalKm = MG4Hardware.getLastTotalKm();
        int mileageStart = MG4Hardware.getMileageAtConsumptionStart();
        // Bu oturum yolu: hız integrali (km). Toplam km sadece bilgi amaçlı.
        double tripDistanceKm = MG4Hardware.getTripDistanceKm();
        int tripKm = (tripDistanceKm >= 0) ? (int) Math.floor(tripDistanceKm + 0.5) : -1;
        float speedKmh = MG4Hardware.getLastSpeedKmh();
        float consumption = MG4Hardware.getLastConsumption();
        // Güç: diğer ekranlarla aynı kaynaktan (DC volt * DC akım / 1000).
        float dcVolt = MG4Hardware.getDcVoltage();
        float dcAmpAct = MG4Hardware.getDcCurrentActual();
        float powerKw = (Float.isNaN(dcVolt) || Float.isNaN(dcAmpAct)) ? Float.NaN : (dcVolt * dcAmpAct) / 1000f;
        int gear = MG4Hardware.getLastGear();
        double tripEnergyKwh = MG4Hardware.getTripEnergyKwh();

        if (mTvConsumptionGear != null) {
            String gearText;
            switch (gear) {
                case 1:  gearText = "P"; break;
                case 2:  gearText = "R"; break;
                case 3:  gearText = "N"; break;
                case 4:  gearText = "D"; break;
                default: gearText = gear < 0 ? "--" : String.valueOf(gear); break;
            }
            mTvConsumptionGear.setText(gearText);
        }
        if (mTvConsumptionTotalKm != null) {
            mTvConsumptionTotalKm.setText(totalKm < 0 ? "--" : String.valueOf(totalKm));
        }
        if (mTvConsumptionKwhPerKm != null) {
            mTvConsumptionKwhPerKm.setText(Float.isNaN(consumption) ? "--" : String.format(Locale.US, "%.3f", consumption));
        }
        if (mTvConsumptionSpeed != null) {
            mTvConsumptionSpeed.setText(Float.isNaN(speedKmh) ? "--" : String.format(Locale.US, "%.2f", speedKmh));
        }
        if (mTvConsumptionPower != null) {
            mTvConsumptionPower.setText(Float.isNaN(powerKw) ? "--" : String.format(Locale.US, "%.2f", powerKw));
        }
        if (mTvConsumptionTripKm != null) {
            if (tripDistanceKm >= 0) {
                mTvConsumptionTripKm.setText(String.format(Locale.US, "%.2f", tripDistanceKm));
            } else {
                mTvConsumptionTripKm.setText("--");
            }
        }
        if (mTvConsumptionEnergy != null) {
            mTvConsumptionEnergy.setText(String.format(Locale.US, "%.3f", tripEnergyKwh));
        }
        // Ortalama tüketim (kWh/100km) = bu oturum enerjisi / bu oturum yolu * 100
        if (mTvConsumptionAvgKwhPer100km != null) {
            if (tripDistanceKm > 0.01 && tripEnergyKwh >= 0) {
                double avgKwhPer100 = (tripEnergyKwh / tripDistanceKm) * 100.0;
                mTvConsumptionAvgKwhPer100km.setText(String.format(Locale.US, "%.2f", avgKwhPer100));
            } else {
                mTvConsumptionAvgKwhPer100km.setText("--");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Motor sesi paneli
    // -------------------------------------------------------------------------

    private void openSoundPanel() {
        mCurrentPanel = PANEL_SOUND;
        mLayoutMain.setVisibility(View.GONE);
        if (mLayoutSoundPanel != null) {
            mLayoutSoundPanel.setVisibility(View.VISIBLE);
        }
        refreshAlarmVolumeHint();
        mAlarmVolumeHandler.removeCallbacks(mAlarmVolumeRunnable);
        mAlarmVolumeHandler.postDelayed(mAlarmVolumeRunnable, 1500);
    }

    private void closeSoundPanel() {
        mAlarmVolumeHandler.removeCallbacks(mAlarmVolumeRunnable);
        if (mLayoutSoundPanel != null) {
            mLayoutSoundPanel.setVisibility(View.GONE);
        }
        mLayoutMain.setVisibility(View.VISIBLE);
        mCurrentPanel = PANEL_MAIN;
    }

    // -------------------------------------------------------------------------
    // Rölanti ayarları paneli (motor sesi ekranından açılır)
    // -------------------------------------------------------------------------

    private void openIdlePanel() {
        mCurrentPanel = PANEL_IDLE;
        if (mLayoutSoundPanel != null) mLayoutSoundPanel.setVisibility(View.GONE);
        if (mLayoutIdlePanel != null) {
            mLayoutIdlePanel.setVisibility(View.VISIBLE);
            setupIdlePanelFromPrefs();
        }
    }

    private void closeIdlePanel() {
        if (mLayoutIdlePanel != null) mLayoutIdlePanel.setVisibility(View.GONE);
        if (mLayoutSoundPanel != null) mLayoutSoundPanel.setVisibility(View.VISIBLE);
        mCurrentPanel = PANEL_SOUND;
    }

    private void setupIdlePanelFromPrefs() {
        SharedPreferences prefs = getSharedPreferences("mg4_v3", MODE_PRIVATE);
        // Anahtarı her zaman prefs'teki seçili profilden türet (Service/Activity aynı key'i kullansın)
        String profileName = prefs.getString(PREF_SOUND_PROFILE, "Lotus Exige");
        String suffix = EngineSoundManager.profileToPrefsSuffix(profileName);
        String volKey = "idle_volume_scale_" + suffix;
        String pitchKey = "idle_pitch_" + suffix;
        int vol = prefs.getInt(volKey, prefs.getInt("idle_volume_scale", 100));
        float pitch = prefs.getFloat(pitchKey, prefs.getFloat("idle_pitch", 1f));
        // Pitch: 0.5–2.0 → SeekBar 0–150 (50 = 1.0)
        int pitchProgress = (int) Math.round((pitch - 0.5f) / 1.5f * 150f);
        pitchProgress = Math.max(0, Math.min(150, pitchProgress));

        TextView tvIdleProfile = findViewById(R.id.tvIdleProfile);
        if (tvIdleProfile != null) tvIdleProfile.setText(getString(R.string.idle_vehicle_format, profileName));

        SeekBar seekVol = findViewById(R.id.seekIdleVolume);
        SeekBar seekPitch = findViewById(R.id.seekIdlePitch);
        TextView tvVol = findViewById(R.id.tvIdleVolumeValue);
        TextView tvPitch = findViewById(R.id.tvIdlePitchValue);
        final String volKeyFinal = volKey;
        final String pitchKeyFinal = pitchKey;
        if (seekVol != null) {
            seekVol.setOnSeekBarChangeListener(null); // önce eski listener'ı kaldır
            seekVol.setProgress(vol);
            if (tvVol != null) tvVol.setText(vol + "%");
            seekVol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                    if (tvVol != null) tvVol.setText(progress + "%");
                    float scale = progress / 100f;
                    if (mEngineSound != null) mEngineSound.setUserIdleVolumeScale(scale);
                    if (fromUser) prefs.edit().putInt(volKeyFinal, progress).commit();
                }
                @Override
                public void onStartTrackingTouch(SeekBar s) {}
                @Override
                public void onStopTrackingTouch(SeekBar s) {}
            });
        }
        if (seekPitch != null) {
            seekPitch.setOnSeekBarChangeListener(null);
            seekPitch.setMax(150);
            seekPitch.setProgress(pitchProgress);
            if (tvPitch != null) tvPitch.setText(String.format(java.util.Locale.US, "%.2f", pitch));
            seekPitch.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                    float p = 0.5f + (progress / 150f) * 1.5f;
                    if (tvPitch != null) tvPitch.setText(String.format(java.util.Locale.US, "%.2f", p));
                    if (mEngineSound != null) mEngineSound.setIdlePitch(p);
                    if (fromUser) prefs.edit().putFloat(pitchKeyFinal, p).commit();
                }
                @Override
                public void onStartTrackingTouch(SeekBar s) {}
                @Override
                public void onStopTrackingTouch(SeekBar s) {}
            });
        }
    }

    /** Tüm araçların rölanti ayarlarını (seviye + pitch) varsayılana döndürür ve paneli günceller. */
    private void resetAllIdleProfiles() {
        SharedPreferences prefs = getSharedPreferences("mg4_v3", MODE_PRIVATE);
        android.content.SharedPreferences.Editor editor = prefs.edit();
        for (String profileName : EngineSoundManager.getProfileLabels()) {
            String suffix = EngineSoundManager.profileToPrefsSuffix(profileName);
            editor.remove("idle_volume_scale_" + suffix);
            editor.remove("idle_pitch_" + suffix);
        }
        editor.remove("idle_volume_scale").remove("idle_pitch"); // eski global anahtarlar
        editor.commit();
        String currentProfile = (mEngineSound != null) ? mEngineSound.getCurrentProfileName() : "Lotus Exige";
        if (mEngineSound != null) {
            mEngineSound.loadIdleSettingsForProfile(this, currentProfile);
        }
        setupIdlePanelFromPrefs();
        Toast.makeText(this, getString(R.string.toast_idle_reset), Toast.LENGTH_SHORT).show();
    }

    /** Motor sesi STREAM_NOTIFICATION kullanır; araç bildirim sesi kısıksa ses de kısık çıkar. */
    private void refreshAlarmVolumeHint() {
        if (mTvAlarmVolumeHint == null) return;
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            mTvAlarmVolumeHint.setText(getString(R.string.notification_volume_okunamadi));
            return;
        }
        int cur = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION);
        if (max <= 0) {
            mTvAlarmVolumeHint.setText(getString(R.string.notification_volume_na));
            return;
        }
        String msg = getString(R.string.notification_volume_format, cur, max);
        if (cur >= max) {
            msg += getString(R.string.notification_volume_max_suffix);
        } else {
            msg += getString(R.string.notification_volume_hint_suffix);
        }
        mTvAlarmVolumeHint.setText(msg);
    }

    /** MODIFY_AUDIO_SETTINGS + sistem UID ile deniyor; araç izin vermeyebilir. */
    private void trySetAlarmVolumeMax() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            Toast.makeText(this, getString(R.string.toast_no_audio_service), Toast.LENGTH_SHORT).show();
            return;
        }
        int max = am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION);
        if (max <= 0) {
            Toast.makeText(this, getString(R.string.toast_volume_unsupported), Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, max, 0);
            int now = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION);
            refreshAlarmVolumeHint();
            if (now >= max) {
                Toast.makeText(this, getString(R.string.toast_volume_max), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, getString(R.string.toast_volume_no_permission), Toast.LENGTH_LONG).show();
            }
        } catch (SecurityException e) {
            Toast.makeText(this, getString(R.string.toast_permission_denied, e.getMessage()), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.toast_error, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        // İç paneller açıksa önce bir seviye geri gel
        if (mCurrentPanel == PANEL_SOUND) {
            closeSoundPanel();
            return;
        } else if (mCurrentPanel == PANEL_CLIMATE) {
            closeClimatePanel();
            return;
        } else if (mCurrentPanel == PANEL_REGEN) {
            closeRegenPanel();
            return;
        } else if (mCurrentPanel == PANEL_STATUS) {
            closeStatusPanel();
            return;
        } else if (mCurrentPanel == PANEL_SHORTCUTS) {
            closeShortcutsPanel();
            return;
        } else if (mCurrentPanel == PANEL_CONSUMPTION) {
            closeConsumptionPanel();
            return;
        } else if (mCurrentPanel == PANEL_IDLE) {
            closeIdlePanel();
            return;
        }
        super.onBackPressed();
    }

    // Direksiyon
    private void selectSteerHeat(int level) {
        sendHeatSteer(level);
        highlightSteerButton(steerButton(level));
        String label = level == 0 ? getString(R.string.toast_heating_steering, getString(R.string.heating_steering_off)) : getString(R.string.toast_heating_steering, getString(R.string.heating_steering_on));
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show();
    }

    private void highlightSteerButton(Button selected) {
        Button[] all = { mBtnSteerOff, mBtnSteerL1 };
        for (Button b : all) {
            boolean active = (b == selected);
            b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    active ? COLOR_HEAT_ON : COLOR_INACTIVE));
            b.setTextColor(active ? 0xFFFFFFFF : 0xFF8B949E);
        }
    }

    private Button steerButton(int level) {
        return level == 1 ? mBtnSteerL1 : mBtnSteerOff;
    }

    // Sol koltuk
    private void selectSeatLeft(int level) {
        sendHeatSeatLeft(level);
        highlightSeatLButton(seatLButton(level));
        String label = level == 0 ? getString(R.string.seat_left_off) : getString(R.string.seat_left_sev, level);
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show();
    }

    private void highlightSeatLButton(Button selected) {
        Button[] all = { mBtnSeatLOff, mBtnSeatLL1, mBtnSeatLL2, mBtnSeatLL3 };
        for (Button b : all) {
            boolean active = (b == selected);
            b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    active ? COLOR_HEAT_ON : COLOR_INACTIVE));
            b.setTextColor(active ? 0xFFFFFFFF : 0xFF8B949E);
        }
    }

    private Button seatLButton(int level) {
        switch (level) {
            case 1:  return mBtnSeatLL1;
            case 2:  return mBtnSeatLL2;
            case 3:  return mBtnSeatLL3;
            default: return mBtnSeatLOff;
        }
    }

    // Sağ koltuk
    private void selectSeatRight(int level) {
        sendHeatSeatRight(level);
        highlightSeatRButton(seatRButton(level));
        String label = level == 0 ? getString(R.string.seat_right_off) : getString(R.string.seat_right_sev, level);
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show();
    }

    private void highlightSeatRButton(Button selected) {
        Button[] all = { mBtnSeatROff, mBtnSeatRL1, mBtnSeatRL2, mBtnSeatRL3 };
        for (Button b : all) {
            boolean active = (b == selected);
            b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    active ? COLOR_HEAT_ON : COLOR_INACTIVE));
            b.setTextColor(active ? 0xFFFFFFFF : 0xFF8B949E);
        }
    }

    private Button seatRButton(int level) {
        switch (level) {
            case 1:  return mBtnSeatRL1;
            case 2:  return mBtnSeatRL2;
            case 3:  return mBtnSeatRL3;
            default: return mBtnSeatROff;
        }
    }

    // -------------------------------------------------------------------------
    // Motor sesi modu
    // -------------------------------------------------------------------------
    
    /** Kayıtlı string'den SoundMode (kalıcı tercih için). */
    private static SoundMode soundModeFromString(String s) {
        // Tüm eski değerleri tek vites moduna eşliyoruz.
        return SoundMode.VIRTUAL_GEAR_V2;
    }
    private static final String SOUND_CHAR_ECO    = "ECO";
    private static final String SOUND_CHAR_NORMAL = "NORMAL";
    private static final String SOUND_CHAR_SPORT  = "SPORT";
    private static final String SOUND_CHAR_AUTO   = "AUTO"; // Araç modunu takip et
    private static final String[] SOUND_CHAR_CYCLE = {
            SOUND_CHAR_ECO, SOUND_CHAR_NORMAL, SOUND_CHAR_SPORT, SOUND_CHAR_AUTO
    };

    private void applySoundCharacter(String character) {
        // Mapping tek yerde: EngineSoundManager.applySoundCharacterFromString
        if (mEngineSound != null) {
            mEngineSound.applySoundCharacterFromString(character);
        }
    }

    private void updateRevMatchButtonText(boolean enabled) {
        if (mBtnRevMatch == null) return;
        mBtnRevMatch.setText(enabled ? getString(R.string.rev_match_on) : getString(R.string.rev_match_off));
    }

    private void updateGearWhineButtonText(boolean enabled) {
        if (mBtnGearWhine == null) return;
        mBtnGearWhine.setText(enabled ? getString(R.string.gear_whine_on) : getString(R.string.gear_whine_off));
    }

    private void cycleSoundCharacter() {
        SharedPreferences prefs = getSharedPreferences("mg4_v3", MODE_PRIVATE);
        String current = prefs.getString("sound_character", SOUND_CHAR_NORMAL);
        int idx = 0;
        for (int i = 0; i < SOUND_CHAR_CYCLE.length; i++) {
            if (SOUND_CHAR_CYCLE[i].equals(current)) { idx = (i + 1) % SOUND_CHAR_CYCLE.length; break; }
        }
        String next = SOUND_CHAR_CYCLE[idx];
        prefs.edit().putString("sound_character", next).apply();
        applySoundCharacter(next);
        updateSoundCharacterButtonText(next);
        Toast.makeText(this, "Şanzıman: " + next, Toast.LENGTH_SHORT).show();
    }

    private void updateSoundCharacterButtonText(String character) {
        if (mBtnGearProfile == null) return;
        switch (character) {
            case SOUND_CHAR_ECO:    mBtnGearProfile.setText(getString(R.string.gearbox_eco)); break;
            case SOUND_CHAR_SPORT:  mBtnGearProfile.setText(getString(R.string.gearbox_sport)); break;
            case SOUND_CHAR_AUTO:   mBtnGearProfile.setText(getString(R.string.gearbox_vehicle)); break;
            default:                mBtnGearProfile.setText(getString(R.string.gearbox_normal)); break;
        }
    }

    private void updateSoundToggleButton() {
        if (mBtnSoundToggle == null) return;

        if (mSoundEnabled) {
            mBtnSoundToggle.setText(getString(R.string.btn_sound_enabled));
            mBtnSoundToggle.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFF1A7F37)); // Yeşil
            mBtnSoundToggle.setTextColor(0xFFFFFFFF);
        } else {
            mBtnSoundToggle.setText(getString(R.string.btn_sound_disabled));
            mBtnSoundToggle.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(COLOR_INACTIVE)); // Gri
            mBtnSoundToggle.setTextColor(0xFF8B949E);
        }
    }

    private void showSoundProfileDialog() {
        final String[] options = EngineSoundManager.getProfileLabels();

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_vehicle_sound_title))
                .setItems(options, (dialog, which) -> {
                    String label = options[which];
                    getSharedPreferences("mg4_v3", MODE_PRIVATE).edit().putString(PREF_SOUND_PROFILE, label).apply();
                    if (mBtnSoundProfile != null) {
                        mBtnSoundProfile.setText(getString(R.string.vehicle_sound_format, label));
                    }
                    // Profil eşlemesini EngineSoundManager yönetsin; rölanti ayarları bu araça özel yüklensin
                    mEngineSound.applyProfileLabel(label);
                    mEngineSound.loadIdleSettingsForProfile(MainActivity.this, label);
                })
                .show();
    }

    // Profil seçimi artık EngineSoundManager.applyProfileLabel içinde yönetiliyor.

    /** Tema modunu kaydet, uygula ve ekranı yenile (Gündüz / Gece / Oto). */
    private void applyThemeMode(int mode) {
        mThemeMode = mode;
        getSharedPreferences("mg4_v3", MODE_PRIVATE).edit().putInt(PREF_THEME_MODE, mode).apply();
        sRecreatedDueToThemeChange = true; // recreate sonrası onCreate'de sese dokunma
        AppCompatDelegate.setDefaultNightMode(mode);
        updateThemeButtons();
    }

    /** Dili kaydet, uygula ve Activity’yi yeniden oluştur. */
    private void applyLanguage(String lang) {
        getSharedPreferences("mg4_v3", MODE_PRIVATE).edit().putString(PREF_LANGUAGE, lang).apply();
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang));
        recreate();
    }

    private void updateThemeButtons() {
        Button btnDay  = findViewById(R.id.btnThemeDay);
        Button btnNight = findViewById(R.id.btnThemeNight);
        Button btnAuto  = findViewById(R.id.btnThemeAuto);
        if (btnDay == null || btnNight == null || btnAuto == null) return;
        boolean isDay  = (mThemeMode == AppCompatDelegate.MODE_NIGHT_NO);
        boolean isNight = (mThemeMode == AppCompatDelegate.MODE_NIGHT_YES);
        boolean isAuto  = (mThemeMode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        btnDay.setBackgroundTintList(android.content.res.ColorStateList.valueOf(isDay  ? COLOR_ACTIVE : COLOR_INACTIVE));
        btnDay.setTextColor(isDay  ? 0xFFFFFFFF : 0xFF8B949E);
        btnNight.setBackgroundTintList(android.content.res.ColorStateList.valueOf(isNight ? COLOR_ACTIVE : COLOR_INACTIVE));
        btnNight.setTextColor(isNight ? 0xFFFFFFFF : 0xFF8B949E);
        btnAuto.setBackgroundTintList(android.content.res.ColorStateList.valueOf(isAuto  ? COLOR_ACTIVE : COLOR_INACTIVE));
        btnAuto.setTextColor(isAuto  ? 0xFFFFFFFF : 0xFF8B949E);
    }

    private void updateLanguageButtons() {
        String lang = getSharedPreferences("mg4_v3", MODE_PRIVATE).getString(PREF_LANGUAGE, "");
        Button btnTr = findViewById(R.id.btnLangTr);
        Button btnEn = findViewById(R.id.btnLangEn);
        if (btnTr != null) {
            boolean trActive = "tr".equals(lang);
            btnTr.setBackgroundTintList(android.content.res.ColorStateList.valueOf(trActive ? COLOR_ACTIVE : COLOR_INACTIVE));
            btnTr.setTextColor(trActive ? 0xFFFFFFFF : 0xFF8B949E);
        }
        if (btnEn != null) {
            boolean enActive = "en".equals(lang);
            btnEn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(enActive ? COLOR_ACTIVE : COLOR_INACTIVE));
            btnEn.setTextColor(enActive ? 0xFFFFFFFF : 0xFF8B949E);
        }
    }

    // -------------------------------------------------------------------------
    // Binder test
    // -------------------------------------------------------------------------

    private void testCarProperty() {
        MG4Hardware.init(this);
        boolean ready = MG4Hardware.isReady();
        String driveStr = "?";
        String regenStr = "?";
        if (ready) {
            int dm = MG4Hardware.getDriveMode();
            int rg = MG4Hardware.getRegenLevel();
            driveStr = dm >= 0 ? DriveMode.fromValue(dm).label + " (" + dm + ")" : "okunamadı";
            regenStr = rg >= 0 ? RegenLevel.fromValue(rg).label + " (" + rg + ")" : "okunamadı";
        }
        mTvBinder.setText(
                "CarPropertyManager : " + (ready ? "✅ HAZIR" : "❌ YOK") + "\n"
                + "Sürüş Modu        : " + driveStr + "\n"
                + "Regen Seviyesi    : " + regenStr);
    }

    // -------------------------------------------------------------------------
    // Intent gönderme
    // -------------------------------------------------------------------------

    private void sendCommand(String action) {
        Intent i = new Intent(this, MG4ControlService.class);
        i.setAction(action);
        startService(i);
    }

    private void sendDriveMode(DriveMode mode) {
        Intent i = new Intent(this, MG4ControlService.class);
        i.setAction("DRIVE_SET");
        i.putExtra("driveValue", mode.value);
        startService(i);
        Toast.makeText(this, "Mod: " + mode.label, Toast.LENGTH_SHORT).show();
    }

    private void sendRegenLevel(RegenLevel level) {
        Intent i = new Intent(this, MG4ControlService.class);
        i.setAction("REGEN_SET");
        i.putExtra("regenValue", level.value);
        startService(i);
        Toast.makeText(this, "Regen: " + level.label, Toast.LENGTH_SHORT).show();
    }

    private void sendHeatSteer(int level) {
        Intent i = new Intent(this, MG4ControlService.class);
        i.setAction("HEAT_STEER_SET");
        i.putExtra("heatLevel", level);
        startService(i);
    }

    private void sendHeatSeatLeft(int level) {
        Intent i = new Intent(this, MG4ControlService.class);
        i.setAction("HEAT_SEAT_L_SET");
        i.putExtra("heatLevel", level);
        startService(i);
    }

    private void sendHeatSeatRight(int level) {
        Intent i = new Intent(this, MG4ControlService.class);
        i.setAction("HEAT_SEAT_R_SET");
        i.putExtra("heatLevel", level);
        startService(i);
    }
}
