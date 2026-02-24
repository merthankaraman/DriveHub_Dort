package com.example.mg4_v3.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import androidx.appcompat.widget.SwitchCompat;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.app.AppCompatActivity;

import com.example.mg4_v3.R;
import com.example.mg4_v3.audio.EngineSoundManager;
import com.example.mg4_v3.hardware.MG4Hardware;
import com.example.mg4_v3.model.ChargingRecord;
import com.example.mg4_v3.model.DriveMode;
import com.example.mg4_v3.model.RegenLevel;
import com.example.mg4_v3.service.MG4ControlService;
import com.example.mg4_v3.util.ChargingHistory;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static com.example.mg4_v3.audio.EngineSoundManager.SoundMode;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MG4_UI";
    private static final String PREF_THEME_MODE = "theme_mode";
    private static final String PREF_SOUND_ENABLED = "sound_enabled";
    private static final String PREF_SOUND_MODE = "sound_mode";
    private static final String PREF_OVERLAY_ENABLED = "overlay_enabled";
    private static final int COLOR_ACTIVE   = 0xFF1F6FEB; // mavi — seçili
    private static final int COLOR_INACTIVE = 0xFF21262D; // koyu gri — seçilmemiş
    private static final int COLOR_HEAT_ON  = 0xFF9E3333; // kırmızı — ısıtma aktif

    // Tema modu (AppCompatDelegate sabitleriyle aynı)
    private int mThemeMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM; // Oto

    private TextView mTvStatus;
    private TextView mTvBinder;
    private TextView mTvSpeed;
    
    // Yapay motor sesi
    private EngineSoundManager mEngineSound;
    private Button mBtnSoundToggle;
    private Button mBtnSoundLoop;
    private Button mBtnSoundFull;
    private Button mBtnSoundVirtualGear;
    private Button mBtnSoundVirtualGearV2; // YENİ EKLENDİ
    private Button mBtnGearProfile;
    private boolean mSoundEnabled = false; // Varsayılan: kapalı (kalıcı tercih SharedPreferences'tan yüklenecek)

    // Ana ekran
    private View mLayoutMain;

    // Regen paneli
    private View   mLayoutRegenPanel;
    private Button mBtnRegenOff;
    private Button mBtnRegenLow;
    private Button mBtnRegenMedium;
    private Button mBtnRegenHigh;
    private Button mBtnRegenAdaptive;
    private Button mBtnRegenOnePedal;
    private TextView mTvRegenCurrent;

    // Şarj paneli
    private View     mLayoutStatusPanel;
    private boolean  mChargingPanelOpen = false;
    private final Handler mChargingHandler = new Handler();
    private final Runnable mChargingRunnable = new Runnable() {
        @Override
        public void run() {
            if (mChargingPanelOpen) {
                refreshStatusPanel();
                mChargingHandler.postDelayed(this, 1000);
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
    private LinearLayout mHistoryTableBody;
    private View mHistoryScroll;

    // Hangi panel açık? (yeniden yaratmada aynı ekrana dönmek için)
    private static final String STATE_PANEL = "current_panel";
    private static final int PANEL_MAIN    = 0;
    private static final int PANEL_STATUS  = 1;
    private static final int PANEL_REGEN   = 2;
    private static final int PANEL_CLIMATE = 3;
    private int mCurrentPanel = PANEL_MAIN;

    // Hız güncelleme
    private final Handler mSpeedHandler = new Handler();
    private final Runnable mSpeedRunnable = new Runnable() {
        @Override
        public void run() {
            float speed = MG4Hardware.getSpeedKmh();
            if (mTvSpeed != null) {
                if (Float.isNaN(speed)) {
                    mTvSpeed.setText("-- km/h");
                } else {
                    mTvSpeed.setText(String.format("%.0f km/h", speed));
                }
            }
            // Yapay motor sesini güncelle
            if (mEngineSound != null) {
                mEngineSound.onSpeedChanged(speed);
            }
            mSpeedHandler.postDelayed(this, 500);
        }
    };

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
        // Kayıtlı tema modunu uygula (setContentView öncesi)
        SharedPreferences prefs = getSharedPreferences("mg4_v3", MODE_PRIVATE);
        mThemeMode = prefs.getInt(PREF_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        AppCompatDelegate.setDefaultNightMode(mThemeMode);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mTvStatus = findViewById(R.id.tvStatus);
        mTvBinder = findViewById(R.id.tvBinderStatus);
        mTvSpeed  = findViewById(R.id.tvSpeed);
        
        // Motor sesi butonları
        mBtnSoundToggle = findViewById(R.id.btnSoundToggle);
        mBtnSoundLoop = findViewById(R.id.btnSoundLoop);
        mBtnSoundFull = findViewById(R.id.btnSoundFull);
        mBtnSoundVirtualGear = findViewById(R.id.btnSoundVirtualGear);
        mBtnSoundVirtualGearV2 = findViewById(R.id.btnSoundVirtualGearV2); // YENİ EKLENDİ

        mBtnGearProfile = findViewById(R.id.btnGearProfile);

        // Yapay motor sesi yöneticisini başlat (önce instance al)
        mEngineSound = EngineSoundManager.getInstance(this);

        // Kayıtlı vites profili
        String savedProfileStr = prefs.getString("gear_profile", "SPORT_6");
        EngineSoundManager.GearProfile savedProfile = gearProfileFromString(savedProfileStr);
        mEngineSound.setGearProfile(savedProfile);
        updateGearProfileButtonText(savedProfile);
        // Butona tıklandığında seçim penceresini aç
        mBtnGearProfile.setOnClickListener(v -> showGearProfileDialog());

        // Araç servislerini başlat
        MG4Hardware.init(this);

        // Son kaydedilen motor sesi / log ayarlarını yükle (kalıcı)
        SharedPreferences prefsSound = getSharedPreferences("mg4_v3", MODE_PRIVATE);
        mSoundEnabled = prefsSound.getBoolean(PREF_SOUND_ENABLED, false);
        SoundMode savedMode = soundModeFromString(prefsSound.getString(PREF_SOUND_MODE, "LOOP"));
        boolean logsEnabled = prefsSound.getBoolean("logs_enabled", true);
        mEngineSound.setMode(savedMode);
        if (mSoundEnabled) mEngineSound.start();
        MG4Hardware.setLogEnabled(logsEnabled);
        updateSoundModeButtons(savedMode);
        updateSoundToggleButton();

        // Motor sesi aç/kapa butonu
        mBtnSoundToggle.setOnClickListener(v -> {
            mSoundEnabled = !mSoundEnabled;
            getSharedPreferences("mg4_v3", MODE_PRIVATE).edit().putBoolean(PREF_SOUND_ENABLED, mSoundEnabled).apply();
            if (mSoundEnabled) {
                mEngineSound.start();
                Toast.makeText(this, "Motor sesi açıldı", Toast.LENGTH_SHORT).show();
            } else {
                mEngineSound.stop();
                Toast.makeText(this, "Motor sesi kapatıldı", Toast.LENGTH_SHORT).show();
            }
            updateSoundToggleButton();
        });

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

        // Motor sesi modu butonları
        mBtnSoundLoop.setOnClickListener(v -> {
            if (mSoundEnabled) {
                mEngineSound.setMode(SoundMode.LOOP);
                getSharedPreferences("mg4_v3", MODE_PRIVATE).edit().putString(PREF_SOUND_MODE, "LOOP").apply();
                updateSoundModeButtons(SoundMode.LOOP);
                Toast.makeText(this, "Motor sesi: Loop (Vites yok)", Toast.LENGTH_SHORT).show();
            }
        });

        mBtnSoundFull.setOnClickListener(v -> {
            if (mSoundEnabled) {
                mEngineSound.setMode(SoundMode.FULL);
                getSharedPreferences("mg4_v3", MODE_PRIVATE).edit().putString(PREF_SOUND_MODE, "FULL").apply();
                updateSoundModeButtons(SoundMode.FULL);
                Toast.makeText(this, "Motor sesi: Tam (Vites var)", Toast.LENGTH_SHORT).show();
            }
        });

        mBtnSoundVirtualGear.setOnClickListener(v -> {
            if (mSoundEnabled) {
                mEngineSound.setMode(SoundMode.VIRTUAL_GEAR);
                getSharedPreferences("mg4_v3", MODE_PRIVATE).edit().putString(PREF_SOUND_MODE, "VIRTUAL_GEAR").apply();
                updateSoundModeButtons(SoundMode.VIRTUAL_GEAR);
                Toast.makeText(this, "Motor sesi: Yapay Vites (6 vites simülasyonu)", Toast.LENGTH_SHORT).show();
            }
        });

        // YENİ V2 BUTONU TIKLAMA OLAYI
        mBtnSoundVirtualGearV2.setOnClickListener(v -> {
            if (mSoundEnabled) {
                mEngineSound.setMode(SoundMode.VIRTUAL_GEAR_V2);
                getSharedPreferences("mg4_v3", MODE_PRIVATE).edit().putString(PREF_SOUND_MODE, "VIRTUAL_GEAR_V2").apply();
                updateSoundModeButtons(SoundMode.VIRTUAL_GEAR_V2);
                Toast.makeText(this, "Motor sesi: V2 (Pürüzsüz Vites)", Toast.LENGTH_SHORT).show();
            }
        });

        // Tema butonları: Gündüz / Gece / Oto (araç sistemine göre)
        Button btnThemeDay  = findViewById(R.id.btnThemeDay);
        Button btnThemeNight = findViewById(R.id.btnThemeNight);
        Button btnThemeAuto  = findViewById(R.id.btnThemeAuto);
        btnThemeDay.setOnClickListener(v  -> applyThemeMode(AppCompatDelegate.MODE_NIGHT_NO));
        btnThemeNight.setOnClickListener(v -> applyThemeMode(AppCompatDelegate.MODE_NIGHT_YES));
        btnThemeAuto.setOnClickListener(v  -> applyThemeMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));
        updateThemeButtons();

        // Versiyon numarasını göster
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            TextView tvVersion = findViewById(R.id.tvVersion);
            tvVersion.setText("EH32 · Android Automotive · v" + pInfo.versionName);
        } catch (Exception ignored) {}

        // Servisi otomatik başlat
        startForegroundService(new Intent(this, MG4ControlService.class));
        mTvStatus.setText("✅ Servis çalışıyor. ★ tuşu aktif.");

        // Ana layout referansı
        mLayoutMain = findViewById(R.id.layoutMain);

        // Ana ekran butonları
        findViewById(R.id.btnTestBinder).setOnClickListener(v -> testCarProperty());
        findViewById(R.id.btnDrive).setOnClickListener(v     -> sendDriveMode(DriveMode.CUSTOM));
findViewById(R.id.btnEco).setOnClickListener(v       -> sendDriveMode(DriveMode.ECO));
        findViewById(R.id.btnNormal).setOnClickListener(v    -> sendDriveMode(DriveMode.NORMAL));
        findViewById(R.id.btnSport).setOnClickListener(v     -> sendDriveMode(DriveMode.SPORT));
        findViewById(R.id.btnSnow).setOnClickListener(v      -> sendDriveMode(DriveMode.SNOW));

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

        findViewById(R.id.btnStatusPanel).setOnClickListener(v -> openStatusPanel());
        findViewById(R.id.btnStatusBack).setOnClickListener(v  -> closeStatusPanel());
        // Manuel ekran yenileme tuşu
        findViewById(R.id.btnStatusRefresh).setOnClickListener(v -> {
            // Otomatik döngüden bağımsız olarak anlık yenile
            refreshStatusPanel();
        });
        mHistoryTableBody = findViewById(R.id.historyTableBody);
        mHistoryScroll    = findViewById(R.id.scrollChargingHistory);
        findViewById(R.id.btnExportChargingHistoryCsv).setOnClickListener(v -> {
            File f = ChargingHistory.exportToCsv(this);
            if (f != null) {
                Toast.makeText(this, "CSV kaydedildi: " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "CSV dışa aktarma başarısız.", Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.btnClearChargingHistory).setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                .setMessage("Geçmişi silmek istiyor musunuz?")
                .setPositiveButton("Evet", (dialog, which) -> {
                    ChargingHistory.clearAll(this);
                    refreshChargingHistoryTable();
                    Toast.makeText(this, "Geçmiş silindi.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Hayır", null)
                .show();
        });

        // Klima paneli açma butonu
        findViewById(R.id.btnClimatePanel).setOnClickListener(v -> openClimatePanel());

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

        mBtnRegenOff.setOnClickListener(v      -> selectRegen(RegenLevel.OFF));
        mBtnRegenLow.setOnClickListener(v      -> selectRegen(RegenLevel.LOW));
        mBtnRegenMedium.setOnClickListener(v   -> selectRegen(RegenLevel.MEDIUM));
        mBtnRegenHigh.setOnClickListener(v     -> selectRegen(RegenLevel.HIGH));
        mBtnRegenAdaptive.setOnClickListener(v -> selectRegen(RegenLevel.ADAPTIVE));
        mBtnRegenOnePedal.setOnClickListener(v -> {
            sendCommand("PEDAL_ON");
            Toast.makeText(this, "Tek Pedal: Açık", Toast.LENGTH_SHORT).show();
            highlightRegenButton(mBtnRegenOnePedal);
            mTvRegenCurrent.setText("Aktif: Tek Pedal");
        });

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
            boolean enabled = p.getBoolean(PREF_OVERLAY_ENABLED, true);
            swOverlay.setChecked(enabled);
            swOverlay.setOnCheckedChangeListener((buttonView, isChecked) -> {
                p.edit().putBoolean(PREF_OVERLAY_ENABLED, isChecked).apply();
                Intent i = new Intent(this, MG4ControlService.class);
                i.setAction(isChecked ? "OVERLAY_ON" : "OVERLAY_OFF");
                startService(i);
            });
        }

        // Şarj geçmişi göster/gizle düğmesi
        Button btnShowHistory = findViewById(R.id.btnShowChargingHistory);
        if (btnShowHistory != null) {
            btnShowHistory.setOnClickListener(v -> {
                if (mHistoryScroll == null) return;
                if (mHistoryScroll.getVisibility() == View.VISIBLE) {
                    mHistoryScroll.setVisibility(View.GONE);
                } else {
                    refreshChargingHistoryTable();
                    mHistoryScroll.setVisibility(View.VISIBLE);
                }
            });
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
            case PANEL_MAIN:
            default:
                // Varsayılan: ana ekran açık kalsın; diğer paneller gizli
                mLayoutMain.setVisibility(View.VISIBLE);
                mLayoutStatusPanel.setVisibility(View.GONE);
                if (mLayoutRegenPanel != null) mLayoutRegenPanel.setVisibility(View.GONE);
                if (mLayoutClimatePanel != null) mLayoutClimatePanel.setVisibility(View.GONE);
                mChargingPanelOpen = false;
                mChargingHandler.removeCallbacks(mChargingRunnable);
                break;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mTvStatus.setText("✅ Servis çalışıyor. ★ tuşu aktif.");
        mSpeedHandler.post(mSpeedRunnable);
        // Yapay motor sesini başlat (eğer açıksa)
        if (mEngineSound != null && mSoundEnabled) {
            mEngineSound.start();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mChargingHandler.removeCallbacks(mChargingRunnable);
        mSpeedHandler.removeCallbacks(mSpeedRunnable);
        // Motor sesini durdurmuyoruz: açıksa arkada çalmaya devam etsin (sadece kullanıcı kapatınca dursun)
        mEngineSound = null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        mSpeedHandler.removeCallbacks(mSpeedRunnable);
        // Motor sesi arkada çalmaya devam etsin; onPause'da durdurmuyoruz
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
            mTvRegenCurrent.setText("Aktif: " + current.label);
        } else {
            mTvRegenCurrent.setText("");
        }
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
        mTvRegenCurrent.setText("Aktif: " + level.label);
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

    // -------------------------------------------------------------------------
    // Durum / Şarj paneli
    // -------------------------------------------------------------------------

    private void openStatusPanel() {
        mCurrentPanel = PANEL_STATUS;
        mLayoutMain.setVisibility(View.GONE);
        mLayoutStatusPanel.setVisibility(View.VISIBLE);
        if (mHistoryScroll != null) mHistoryScroll.setVisibility(View.GONE);
        mChargingPanelOpen = true;
        refreshStatusPanel();
        refreshChargingHistoryTable();
        mChargingHandler.postDelayed(mChargingRunnable, 1000);
    }

    private void closeStatusPanel() {
        mChargingPanelOpen = false;
        mChargingHandler.removeCallbacks(mChargingRunnable);
        mLayoutStatusPanel.setVisibility(View.GONE);
        mLayoutMain.setVisibility(View.VISIBLE);
        mCurrentPanel = PANEL_MAIN;
    }

    private void refreshStatusPanel() {
        // Şarj bittiğinde oturumu geçmişe kaydet; yeni kayıt eklendiyse tabloyu güncelle
        if (ChargingHistory.checkAndSaveSessionIfEnded(this)) {
            refreshChargingHistoryTable();
        }

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
            mTvExpectedPower.setText(String.format("Beklenen Şarj Gücü:  %.1f kW", expKw));
        } else {
            mTvExpectedPower.setText("Beklenen Şarj Gücü:  -- kW");
        }

        // AC sütunu (acVolt, acAmp yukarıda alındı)
        mTvAcVolt.setText(Float.isNaN(acVolt) ? "--" : String.format("%.0f V", acVolt));
        mTvAcAmp.setText(Float.isNaN(acAmp)   ? "--" : String.format("%.1f A", acAmp));
        if (!Float.isNaN(acVolt) && !Float.isNaN(acAmp)) {
            mTvAcKw.setText(String.format("%.1f kW", (acVolt * acAmp) / 1000f));
        } else {
            mTvAcKw.setText("--");
        }

        // Batarya sütunu
        mTvDcVolt.setText(Float.isNaN(dcVolt)     ? "--" : String.format("%.1f V", dcVolt));
        mTvDcAmpAct.setText(Float.isNaN(dcAmpAct) ? "--" : String.format("%.1f A", dcAmpAct));
        if (!Float.isNaN(dcVolt) && !Float.isNaN(dcAmpAct)) {
            mTvDcKwAct.setText(String.format("%.1f kW", (dcVolt * dcAmpAct) / 1000f));
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
        mTvChargingStatus.setText(charging ? "Şarjda" : "Şarjda değil");
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
    }

    private static final SimpleDateFormat SDF = new SimpleDateFormat("dd.MM HH:mm", Locale.getDefault());

    private void refreshChargingHistoryTable() {
        if (mHistoryTableBody == null) return;
        mHistoryTableBody.removeAllViews();
        List<ChargingRecord> list = ChargingHistory.load(this);
        int dp12 = (int) (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12, getResources().getDisplayMetrics()) + 0.5f);
        int dp4 = (int) (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4, getResources().getDisplayMetrics()) + 0.5f);
        for (ChargingRecord r : list) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp4, dp12, dp4);
            addCell(row, 1.2f, SDF.format(new Date(r.startMs)));
            addCell(row, 1.2f, SDF.format(new Date(r.endMs)));
            addCell(row, 0.7f, String.format(Locale.US, "%.2f", r.acKwh));
            addCell(row, 0.7f, String.format(Locale.US, "%.2f", r.dcKwh));
            addCell(row, 0.5f, String.format(Locale.US, "%.1f h", r.getDurationHours()));
            mHistoryTableBody.addView(row);
        }
    }

    private void addCell(LinearLayout row, float weight, String text) {
        TextView tv = new TextView(this);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight));
        tv.setText(text);
        tv.setTextColor(0xFF8B949E);
        tv.setTextSize(11);
        row.addView(tv);
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

    // Direksiyon
    private void selectSteerHeat(int level) {
        sendHeatSteer(level);
        highlightSteerButton(steerButton(level));
        String label = level == 0 ? "Direksiyon: Kapalı" : "Direksiyon: Açık";
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
        String label = level == 0 ? "Sol Koltuk: Kapalı" : "Sol Koltuk: Sev." + level;
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
        String label = level == 0 ? "Sağ Koltuk: Kapalı" : "Sağ Koltuk: Sev." + level;
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
        if ("FULL".equals(s)) return SoundMode.FULL;
        if ("VIRTUAL_GEAR".equals(s)) return SoundMode.VIRTUAL_GEAR;
        if ("VIRTUAL_GEAR_V2".equals(s)) return SoundMode.VIRTUAL_GEAR_V2; // YENİ EKLENDİ
        return SoundMode.LOOP;
    }
    private static EngineSoundManager.GearProfile gearProfileFromString(String s) {
        if ("CRUISER_4".equals(s)) return EngineSoundManager.GearProfile.CRUISER_4;
        if ("RALLY_8".equals(s)) return EngineSoundManager.GearProfile.RALLY_8;
        return EngineSoundManager.GearProfile.SPORT_6; // Varsayılan güvenli değer
    }

    private void updateSoundToggleButton() {
        if (mBtnSoundToggle == null) return;

        if (mSoundEnabled) {
            mBtnSoundToggle.setText("🔊 Ses Kapa");
            mBtnSoundToggle.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFF1A7F37)); // Yeşil
            mBtnSoundToggle.setTextColor(0xFFFFFFFF);
            // Mod butonlarını aktif et
            mBtnSoundLoop.setEnabled(true);
            mBtnSoundFull.setEnabled(true);
            mBtnSoundVirtualGear.setEnabled(true);
            if(mBtnSoundVirtualGearV2 != null) mBtnSoundVirtualGearV2.setEnabled(true); // YENİ
        } else {
            mBtnSoundToggle.setText("🔇 Ses Aç");
            mBtnSoundToggle.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(COLOR_INACTIVE)); // Gri
            mBtnSoundToggle.setTextColor(0xFF8B949E);
            // Mod butonlarını pasif et
            mBtnSoundLoop.setEnabled(false);
            mBtnSoundFull.setEnabled(false);
            mBtnSoundVirtualGear.setEnabled(false);
            if(mBtnSoundVirtualGearV2 != null) mBtnSoundVirtualGearV2.setEnabled(false); // YENİ
        }
    }

    private void showGearProfileDialog() {
        String[] options = {"4-İleri Cruiser (Uzun Oran)", "6-İleri Spor LFA", "8-İleri Ralli (Kısa Oran)"};

        new AlertDialog.Builder(this)
                .setTitle("Şanzıman Profilini Seç")
                .setItems(options, (dialog, which) -> {
                    EngineSoundManager.GearProfile selectedProfile;
                    switch (which) {
                        case 0: selectedProfile = EngineSoundManager.GearProfile.CRUISER_4; break;
                        case 2: selectedProfile = EngineSoundManager.GearProfile.RALLY_8; break;
                        case 1:
                        default: selectedProfile = EngineSoundManager.GearProfile.SPORT_6; break;
                    }

                    // Sesi güncelle
                    mEngineSound.setGearProfile(selectedProfile);

                    // Tercihi kaydet
                    getSharedPreferences("mg4_v3", MODE_PRIVATE)
                            .edit()
                            .putString("gear_profile", selectedProfile.name())
                            .apply();

                    // Buton metnini güncelle
                    updateGearProfileButtonText(selectedProfile);
                    Toast.makeText(this, "Şanzıman değiştirildi", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void updateGearProfileButtonText(EngineSoundManager.GearProfile profile) {
        if (mBtnGearProfile == null) return;
        switch (profile) {
            case CRUISER_4: mBtnGearProfile.setText("⚙️ Şanzıman: 4-İleri Cruiser"); break;
            case RALLY_8:   mBtnGearProfile.setText("⚙️ Şanzıman: 8-İleri Ralli"); break;
            case SPORT_6:   mBtnGearProfile.setText("⚙️ Şanzıman: 6-İleri Spor LFA"); break;
        }
    }

    /** Tema modunu kaydet, uygula ve ekranı yenile (Gündüz / Gece / Oto). */
    private void applyThemeMode(int mode) {
        mThemeMode = mode;
        getSharedPreferences("mg4_v3", MODE_PRIVATE).edit().putInt(PREF_THEME_MODE, mode).apply();
        AppCompatDelegate.setDefaultNightMode(mode);
        updateThemeButtons();
        recreate(); // Tema renklerinin hemen uygulanması için
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

    private void updateSoundModeButtons(SoundMode activeMode) {
        if (mBtnSoundLoop == null || mBtnSoundFull == null || mBtnSoundVirtualGear == null || mBtnSoundVirtualGearV2 == null) return;

        boolean isLoop = (activeMode == SoundMode.LOOP);
        boolean isFull = (activeMode == SoundMode.FULL);
        boolean isVirtualGear = (activeMode == SoundMode.VIRTUAL_GEAR);
        boolean isVirtualGearV2 = (activeMode == SoundMode.VIRTUAL_GEAR_V2); // YENİ

        mBtnSoundLoop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(isLoop ? COLOR_ACTIVE : COLOR_INACTIVE));
        mBtnSoundLoop.setTextColor(isLoop ? 0xFFFFFFFF : 0xFF8B949E);

        mBtnSoundFull.setBackgroundTintList(android.content.res.ColorStateList.valueOf(isFull ? COLOR_ACTIVE : COLOR_INACTIVE));
        mBtnSoundFull.setTextColor(isFull ? 0xFFFFFFFF : 0xFF8B949E);

        mBtnSoundVirtualGear.setBackgroundTintList(android.content.res.ColorStateList.valueOf(isVirtualGear ? COLOR_ACTIVE : COLOR_INACTIVE));
        mBtnSoundVirtualGear.setTextColor(isVirtualGear ? 0xFFFFFFFF : 0xFF8B949E);

        // YENİ BUTON RENK KONTROLÜ
        mBtnSoundVirtualGearV2.setBackgroundTintList(android.content.res.ColorStateList.valueOf(isVirtualGearV2 ? COLOR_ACTIVE : COLOR_INACTIVE));
        mBtnSoundVirtualGearV2.setTextColor(isVirtualGearV2 ? 0xFFFFFFFF : 0xFF8B949E);
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
