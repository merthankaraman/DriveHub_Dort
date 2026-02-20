package com.example.mg4_v3.ui;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.mg4_v3.R;
import com.example.mg4_v3.hardware.MG4Hardware;
import com.example.mg4_v3.model.DriveMode;
import com.example.mg4_v3.model.RegenLevel;
import com.example.mg4_v3.service.MG4ControlService;

public class MainActivity extends AppCompatActivity {

    private static final int COLOR_ACTIVE   = 0xFF1F6FEB; // mavi — seçili
    private static final int COLOR_INACTIVE = 0xFF21262D; // koyu gri — seçilmemiş
    private static final int COLOR_HEAT_ON  = 0xFF9E3333; // kırmızı — ısıtma aktif

    private TextView mTvStatus;
    private TextView mTvBinder;

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

    // Durum/Şarj paneli
    private View     mLayoutStatusPanel;
    private Button   mBtnStatusAutoRefresh;
    private boolean  mAutoRefreshActive = false;
    private final Handler mAutoRefreshHandler = new Handler();
    private final Runnable mAutoRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (mAutoRefreshActive) {
                refreshStatusPanel();
                mAutoRefreshHandler.postDelayed(this, 1000);
            }
        }
    };
    private TextView mTvSoc;
    private TextView mTvRange;
    private TextView mTvSpeed;
    private TextView mTvAcVolt;
    private TextView mTvAcAmp;
    private TextView mTvAcKw;
    private TextView mTvDcVolt;
    private TextView mTvDcAmpExp;
    private TextView mTvDcAmpAct;
    private TextView mTvDcKwExp;
    private TextView mTvDcKwAct;

    // Klima paneli
    private View   mLayoutClimatePanel;
    // Direksiyon
    private Button mBtnSteerOff;
    private Button mBtnSteerL1;
    private Button mBtnSteerL2;
    private Button mBtnSteerL3;
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
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mTvStatus = findViewById(R.id.tvStatus);
        mTvBinder = findViewById(R.id.tvBinderStatus);

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
        findViewById(R.id.btnDrive).setOnClickListener(v     -> sendCommand("DRIVE_CYCLE"));
        findViewById(R.id.btnPedalOn).setOnClickListener(v   -> sendCommand("PEDAL_ON"));
        findViewById(R.id.btnPedalOff).setOnClickListener(v  -> sendCommand("PEDAL_OFF"));
        findViewById(R.id.btnEco).setOnClickListener(v       -> sendDriveMode(DriveMode.ECO));
        findViewById(R.id.btnNormal).setOnClickListener(v    -> sendDriveMode(DriveMode.NORMAL));
        findViewById(R.id.btnSport).setOnClickListener(v     -> sendDriveMode(DriveMode.SPORT));
        findViewById(R.id.btnSnow).setOnClickListener(v      -> sendDriveMode(DriveMode.SNOW));

        // Durum/Şarj paneli
        mLayoutStatusPanel = findViewById(R.id.layoutStatusPanel);
        mTvSoc      = findViewById(R.id.tvSoc);
        mTvRange    = findViewById(R.id.tvRange);
        mTvSpeed    = findViewById(R.id.tvSpeed);
        mTvAcVolt   = findViewById(R.id.tvAcVolt);
        mTvAcAmp    = findViewById(R.id.tvAcAmp);
        mTvAcKw     = findViewById(R.id.tvAcKw);
        mTvDcVolt   = findViewById(R.id.tvDcVolt);
        mTvDcAmpExp = findViewById(R.id.tvDcAmpExp);
        mTvDcAmpAct = findViewById(R.id.tvDcAmpAct);
        mTvDcKwExp  = findViewById(R.id.tvDcKwExp);
        mTvDcKwAct  = findViewById(R.id.tvDcKwAct);

        mBtnStatusAutoRefresh = findViewById(R.id.btnStatusAutoRefresh);

        findViewById(R.id.btnStatusPanel).setOnClickListener(v -> openStatusPanel());
        findViewById(R.id.btnStatusBack).setOnClickListener(v  -> closeStatusPanel());
        findViewById(R.id.btnStatusRefresh).setOnClickListener(v -> refreshStatusPanel());
        mBtnStatusAutoRefresh.setOnClickListener(v -> toggleAutoRefresh());

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
        mBtnSteerL2  = findViewById(R.id.btnSteerL2);
        mBtnSteerL3  = findViewById(R.id.btnSteerL3);
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

        findViewById(R.id.btnClimateBack).setOnClickListener(v -> closeClimatePanel());

        // Direksiyon buton listener'ları
        mBtnSteerOff.setOnClickListener(v -> selectSteerHeat(0));
        mBtnSteerL1.setOnClickListener(v  -> selectSteerHeat(1));
        mBtnSteerL2.setOnClickListener(v  -> selectSteerHeat(2));
        mBtnSteerL3.setOnClickListener(v  -> selectSteerHeat(3));

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
    }

    @Override
    protected void onResume() {
        super.onResume();
        mTvStatus.setText("✅ Servis çalışıyor. ★ tuşu aktif.");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mAutoRefreshHandler.removeCallbacks(mAutoRefreshRunnable);
    }

    // -------------------------------------------------------------------------
    // Regen paneli
    // -------------------------------------------------------------------------

    private void openRegenPanel() {
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
    }

    private void selectRegen(RegenLevel level) {
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
        mLayoutMain.setVisibility(View.GONE);
        mLayoutStatusPanel.setVisibility(View.VISIBLE);
        refreshStatusPanel();
    }

    private void closeStatusPanel() {
        stopAutoRefresh();
        mLayoutStatusPanel.setVisibility(View.GONE);
        mLayoutMain.setVisibility(View.VISIBLE);
    }

    private void toggleAutoRefresh() {
        if (mAutoRefreshActive) {
            stopAutoRefresh();
        } else {
            mAutoRefreshActive = true;
            mBtnStatusAutoRefresh.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFF1A7F37));
            mBtnStatusAutoRefresh.setTextColor(0xFFFFFFFF);
            mAutoRefreshHandler.postDelayed(mAutoRefreshRunnable, 1000);
        }
    }

    private void stopAutoRefresh() {
        mAutoRefreshActive = false;
        mAutoRefreshHandler.removeCallbacks(mAutoRefreshRunnable);
        mBtnStatusAutoRefresh.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(COLOR_INACTIVE));
        mBtnStatusAutoRefresh.setTextColor(0xFFC9D1D9);
    }

    private void refreshStatusPanel() {
        // SOC
        float soc = MG4Hardware.getSoc();
        mTvSoc.setText(Float.isNaN(soc) ? "--" : String.format("%.1f", soc));

        // Menzil
        int range = MG4Hardware.getRange();
        mTvRange.setText(range >= 0 ? String.valueOf(range) : "--");

        // Hız (km/h — araç doğrudan km/h gönderiyor)
        float speedKmh = MG4Hardware.getSpeedKmh();
        if (!Float.isNaN(speedKmh) && speedKmh >= 0) {
            mTvSpeed.setText(String.format("%.0f", speedKmh));
        } else {
            mTvSpeed.setText("--");
        }

        // DC voltaj
        float dcVolt = MG4Hardware.getDcVoltage();
        mTvDcVolt.setText(Float.isNaN(dcVolt) ? "-- V" : String.format("%.1f V", dcVolt));

        // DC akım gerçek
        float dcAmpAct = MG4Hardware.getDcCurrentActual();
        mTvDcAmpAct.setText(Float.isNaN(dcAmpAct) ? "-- A" : String.format("%.1f A", dcAmpAct));

        // DC akım beklenen
        float dcAmpExp = MG4Hardware.getDcCurrentExpected();
        mTvDcAmpExp.setText(Float.isNaN(dcAmpExp) ? "-- A" : String.format("%.1f A", dcAmpExp));

        // DC güç hesabı: P = V × I
        if (!Float.isNaN(dcVolt) && !Float.isNaN(dcAmpAct)) {
            mTvDcKwAct.setText(String.format("%.1f kW", (dcVolt * dcAmpAct) / 1000f));
        } else {
            mTvDcKwAct.setText("-- kW");
        }
        if (!Float.isNaN(dcVolt) && !Float.isNaN(dcAmpExp)) {
            mTvDcKwExp.setText(String.format("%.1f kW", (dcVolt * dcAmpExp) / 1000f));
        } else {
            mTvDcKwExp.setText("-- kW");
        }

        // AC voltaj
        float acVolt = MG4Hardware.getAcVoltage();
        mTvAcVolt.setText(Float.isNaN(acVolt) ? "-- V" : String.format("%.0f V", acVolt));

        // AC akım
        float acAmp = MG4Hardware.getAcCurrent();
        mTvAcAmp.setText(Float.isNaN(acAmp) ? "-- A" : String.format("%.1f A", acAmp));

        // AC güç: P = V × I
        if (!Float.isNaN(acVolt) && !Float.isNaN(acAmp)) {
            mTvAcKw.setText(String.format("%.1f kW", (acVolt * acAmp) / 1000f));
        } else {
            mTvAcKw.setText("-- kW");
        }
    }

    // -------------------------------------------------------------------------
    // Klima paneli
    // -------------------------------------------------------------------------

    private void openClimatePanel() {
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
    }

    // Direksiyon
    private void selectSteerHeat(int level) {
        sendHeatSteer(level);
        highlightSteerButton(steerButton(level));
        String label = level == 0 ? "Direksiyon: Kapalı" : "Direksiyon: Sev." + level;
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show();
    }

    private void highlightSteerButton(Button selected) {
        Button[] all = { mBtnSteerOff, mBtnSteerL1, mBtnSteerL2, mBtnSteerL3 };
        for (Button b : all) {
            boolean active = (b == selected);
            b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    active ? COLOR_HEAT_ON : COLOR_INACTIVE));
            b.setTextColor(active ? 0xFFFFFFFF : 0xFF8B949E);
        }
    }

    private Button steerButton(int level) {
        switch (level) {
            case 1:  return mBtnSteerL1;
            case 2:  return mBtnSteerL2;
            case 3:  return mBtnSteerL3;
            default: return mBtnSteerOff;
        }
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
