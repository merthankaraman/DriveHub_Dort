package com.example.mg4_2.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.mg4_2.R;
import com.example.mg4_2.hardware.MG4Hardware;
import com.example.mg4_2.model.DriveMode;
import com.example.mg4_2.service.MG4ControlService;

public class MainActivity extends AppCompatActivity {

    private TextView mTvStatus;
    private TextView mTvBinder;
    private Button   mBtnService;
    private boolean  mServiceStarted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mTvStatus   = findViewById(R.id.tvStatus);
        mTvBinder   = findViewById(R.id.tvBinderStatus);
        mBtnService = findViewById(R.id.btnService);

        findViewById(R.id.btnPermission).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                        .setData(Uri.parse("package:" + getPackageName()))));

        mBtnService.setOnClickListener(v -> toggleService());

        findViewById(R.id.btnTestBinder).setOnClickListener(v -> testBinder());

        findViewById(R.id.btnDrive).setOnClickListener(v  -> sendCommand("DRIVE_CYCLE"));
        findViewById(R.id.btnRegen).setOnClickListener(v  -> sendCommand("REGEN_CYCLE"));
        findViewById(R.id.btnPedalOn).setOnClickListener(v  -> sendCommand("PEDAL_ON"));
        findViewById(R.id.btnPedalOff).setOnClickListener(v -> sendCommand("PEDAL_OFF"));
        findViewById(R.id.btnHeatOn).setOnClickListener(v   -> sendCommand("HEAT_ON"));

        findViewById(R.id.btnEco).setOnClickListener(v    -> sendDriveMode(DriveMode.ECO));
        findViewById(R.id.btnNormal).setOnClickListener(v -> sendDriveMode(DriveMode.NORMAL));
        findViewById(R.id.btnSport).setOnClickListener(v  -> sendDriveMode(DriveMode.SPORT));
        findViewById(R.id.btnSnow).setOnClickListener(v   -> sendDriveMode(DriveMode.SNOW));
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean canWrite = Settings.System.canWrite(this);
        findViewById(R.id.btnPermission).setVisibility(canWrite ? View.GONE : View.VISIBLE);
        mTvStatus.setText(canWrite ? "İzinler tamam ✓" : "⚠ WRITE_SETTINGS izni gerekli!");
    }

    private void toggleService() {
        if (mServiceStarted) {
            stopService(new Intent(this, MG4ControlService.class));
            mServiceStarted = false;
            mBtnService.setText("Servisi Başlat");
            mTvStatus.setText("Servis durduruldu.");
        } else {
            startForegroundService(new Intent(this, MG4ControlService.class));
            mServiceStarted = true;
            mBtnService.setText("Servisi Durdur");
            mTvStatus.setText("✅ Servis çalışıyor. Hardkey 66 aktif.");
        }
    }

    private void testBinder() {
        boolean vs = MG4Hardware.isServiceAvailable(MG4Hardware.SERVICE_VEHICLE_SETTING);
        boolean ac = MG4Hardware.isServiceAvailable(MG4Hardware.SERVICE_AIR_CONDITION);
        mTvBinder.setText("vehiclesetting : " + (vs ? "✅ BAĞLI" : "❌ YOK") + "\n"
                + "aircondition   : " + (ac ? "✅ BAĞLI" : "❌ YOK"));
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