package com.example.mg4_v3.ui;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.mg4_v3.R;
import com.example.mg4_v3.hardware.MG4Hardware;
import com.example.mg4_v3.model.DriveMode;
import com.example.mg4_v3.model.RegenLevel;
import com.example.mg4_v3.service.MG4ControlService;

public class MainActivity extends AppCompatActivity {

    private TextView mTvStatus;
    private TextView mTvBinder;

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

        // Servisi otomatik başlat — kullanıcı müdahalesi gerektirmez
        startForegroundService(new Intent(this, MG4ControlService.class));
        mTvStatus.setText("✅ Servis çalışıyor. ★ tuşu aktif.");

        findViewById(R.id.btnTestBinder).setOnClickListener(v -> testCarProperty());

        findViewById(R.id.btnDrive).setOnClickListener(v   -> sendCommand("DRIVE_CYCLE"));
        findViewById(R.id.btnRegen).setOnClickListener(v   -> sendCommand("REGEN_CYCLE"));
        findViewById(R.id.btnPedalOn).setOnClickListener(v  -> sendCommand("PEDAL_ON"));
        findViewById(R.id.btnPedalOff).setOnClickListener(v -> sendCommand("PEDAL_OFF"));

        findViewById(R.id.btnEco).setOnClickListener(v    -> sendDriveMode(DriveMode.ECO));
        findViewById(R.id.btnNormal).setOnClickListener(v -> sendDriveMode(DriveMode.NORMAL));
        findViewById(R.id.btnSport).setOnClickListener(v  -> sendDriveMode(DriveMode.SPORT));
        findViewById(R.id.btnSnow).setOnClickListener(v   -> sendDriveMode(DriveMode.SNOW));

        findViewById(R.id.btnHeatOn).setOnClickListener(v -> sendCommand("HEAT_ON"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Servis arka planda zaten çalışıyor (onCreate'de başlatıldı / BootReceiver ile başlatıldı)
        mTvStatus.setText("✅ Servis çalışıyor. ★ tuşu aktif.");
    }

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
}
