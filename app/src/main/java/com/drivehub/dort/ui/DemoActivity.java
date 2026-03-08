package com.drivehub.dort.ui;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.drivehub.dort.R;
import com.drivehub.dort.hardware.MG4Hardware;

import java.util.Locale;

/**
 * Demo ekranı: ESP, cam kontrolleri, kilit otomasyonu (uzaklaşma/yaklaşma/yakın alan), cam yüzdeleri.
 * Ana ekrandaki "Demo" düğmesiyle açılır.
 *
 * Get'ler (getEspSwitch, getLeaveAutoLockMode, getApproachUnlockMode, getNearfieldUnlockMode vb.)
 * sadece şu anlarda çağrılır: ekran açılırken (onCreate) ve ekran tekrar öne gelince (onResume).
 * Arka planda veya periyodik yenileme yok; sadece kullanıcı Demo'ya girip çıktığında güncellenir.
 */
public class DemoActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_demo);

        findViewById(R.id.btnDemoBack).setOnClickListener(v -> finish());

        // ESP
        SwitchCompat switchEsp = findViewById(R.id.switchEsp);
        TextView tvEspStatus = findViewById(R.id.tvEspStatus);
        if (switchEsp != null) {
            int esp = MG4Hardware.getEspSwitch();
            switchEsp.setChecked(esp == 1);
            updateEspStatus(tvEspStatus, esp);
            switchEsp.setOnCheckedChangeListener((v, isChecked) -> {
                MG4Hardware.setEspSwitch(isChecked ? 1 : 0);
                if (tvEspStatus != null) updateEspStatus(tvEspStatus, isChecked ? 1 : 0);
            });
        }

        // Uzaklaşma ile kilitleme (anahtar gidince araç kilitlensin)
        SwitchCompat switchLeaveAutoLock = findViewById(R.id.switchLeaveAutoLock);
        TextView tvLeaveAutoLockStatus = findViewById(R.id.tvLeaveAutoLockStatus);
        if (switchLeaveAutoLock != null) {
            int mode = MG4Hardware.getLeaveAutoLockMode();
            switchLeaveAutoLock.setChecked(mode == 1);
            updateLeaveAutoLockStatus(tvLeaveAutoLockStatus, mode);
            switchLeaveAutoLock.setOnCheckedChangeListener((v, isChecked) -> {
                MG4Hardware.setLeaveAutoLockMode(isChecked ? 1 : 0);
                if (tvLeaveAutoLockStatus != null) updateLeaveAutoLockStatus(tvLeaveAutoLockStatus, isChecked ? 1 : 0);
            });
        }

        // Yaklaşınca kilidi aç
        SwitchCompat switchApproachUnlock = findViewById(R.id.switchApproachUnlock);
        TextView tvApproachUnlockStatus = findViewById(R.id.tvApproachUnlockStatus);
        if (switchApproachUnlock != null) {
            int mode = MG4Hardware.getApproachUnlockMode();
            switchApproachUnlock.setChecked(mode == 1);
            updateApproachUnlockStatus(tvApproachUnlockStatus, mode);
            switchApproachUnlock.setOnCheckedChangeListener((v, isChecked) -> {
                MG4Hardware.setApproachUnlockMode(isChecked ? 1 : 0);
                if (tvApproachUnlockStatus != null) updateApproachUnlockStatus(tvApproachUnlockStatus, isChecked ? 1 : 0);
            });
        }

        // Yakın alan kilidi aç
        SwitchCompat switchNearfieldUnlock = findViewById(R.id.switchNearfieldUnlock);
        TextView tvNearfieldUnlockStatus = findViewById(R.id.tvNearfieldUnlockStatus);
        if (switchNearfieldUnlock != null) {
            int mode = MG4Hardware.getNearfieldUnlockMode();
            switchNearfieldUnlock.setChecked(mode == 1);
            updateNearfieldUnlockStatus(tvNearfieldUnlockStatus, mode);
            switchNearfieldUnlock.setOnCheckedChangeListener((v, isChecked) -> {
                MG4Hardware.setNearfieldUnlockMode(isChecked ? 1 : 0);
                if (tvNearfieldUnlockStatus != null) updateNearfieldUnlockStatus(tvNearfieldUnlockStatus, isChecked ? 1 : 0);
            });
        }

        // Pencere: spinner + seekbar + yüzdeler
        Spinner spinner = findViewById(R.id.spinnerWindowSelect);
        SeekBar seekBar = findViewById(R.id.seekBarWindowLevel);
        TextView tvLevel = findViewById(R.id.tvWindowLevel);
        TextView tvPercentages = findViewById(R.id.tvWindowPercentages);
        if (spinner != null && seekBar != null && tvLevel != null) {
            String[] items = {
                getString(R.string.window_driver),
                getString(R.string.window_passenger),
                getString(R.string.window_rear_left),
                getString(R.string.window_rear_right),
                getString(R.string.window_all)
            };
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(adapter);

            seekBar.setMax(100);
            seekBar.setProgress(0);
            tvLevel.setText(getString(R.string.window_level_format, 0));

            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    tvLevel.setText(getString(R.string.window_level_format, progress));
                    float value = (float) progress;
                    int sel = spinner.getSelectedItemPosition();
                    if (sel < 0) sel = 0;
                    switch (sel) {
                        case 0: MG4Hardware.setDriveWindow(value); break;
                        case 1: MG4Hardware.setPassengerWindow(value); break;
                        case 2: MG4Hardware.setLeftRearWindow(value); break;
                        case 3: MG4Hardware.setRightRearWindow(value); break;
                        case 4:
                            MG4Hardware.setDriveWindow(value);
                            MG4Hardware.setPassengerWindow(value);
                            MG4Hardware.setLeftRearWindow(value);
                            MG4Hardware.setRightRearWindow(value);
                            break;
                        default: break;
                    }
                }
                @Override
                public void onStartTrackingTouch(SeekBar s) {}
                @Override
                public void onStopTrackingTouch(SeekBar s) {
                    refreshWindowPercentages();
                }
            });
            refreshWindowPercentages();
        }
        refreshAirQuality();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshWindowPercentages();
        SwitchCompat switchEsp = findViewById(R.id.switchEsp);
        TextView tvEspStatus = findViewById(R.id.tvEspStatus);
        if (switchEsp != null) {
            int esp = MG4Hardware.getEspSwitch();
            if (esp >= 0) switchEsp.setChecked(esp == 1);
            updateEspStatus(tvEspStatus, esp);
        }
        SwitchCompat switchLeaveAutoLock = findViewById(R.id.switchLeaveAutoLock);
        TextView tvLeaveAutoLockStatus = findViewById(R.id.tvLeaveAutoLockStatus);
        if (switchLeaveAutoLock != null) {
            int mode = MG4Hardware.getLeaveAutoLockMode();
            if (mode >= 0) switchLeaveAutoLock.setChecked(mode == 1);
            updateLeaveAutoLockStatus(tvLeaveAutoLockStatus, mode);
        }
        SwitchCompat switchApproachUnlock = findViewById(R.id.switchApproachUnlock);
        TextView tvApproachUnlockStatus = findViewById(R.id.tvApproachUnlockStatus);
        if (switchApproachUnlock != null) {
            int mode = MG4Hardware.getApproachUnlockMode();
            if (mode >= 0) switchApproachUnlock.setChecked(mode == 1);
            updateApproachUnlockStatus(tvApproachUnlockStatus, mode);
        }
        SwitchCompat switchNearfieldUnlock = findViewById(R.id.switchNearfieldUnlock);
        TextView tvNearfieldUnlockStatus = findViewById(R.id.tvNearfieldUnlockStatus);
        if (switchNearfieldUnlock != null) {
            int mode = MG4Hardware.getNearfieldUnlockMode();
            if (mode >= 0) switchNearfieldUnlock.setChecked(mode == 1);
            updateNearfieldUnlockStatus(tvNearfieldUnlockStatus, mode);
        }
        refreshAirQuality();
    }

    private void updateEspStatus(TextView tv, int value) {
        if (tv == null) return;
        tv.setText(getString(R.string.esp_status, value));
    }

    private void updateLeaveAutoLockStatus(TextView tv, int value) {
        if (tv == null) return;
        tv.setText(getString(R.string.leave_auto_lock_status, value));
    }

    private void updateApproachUnlockStatus(TextView tv, int value) {
        if (tv == null) return;
        tv.setText(getString(R.string.approach_unlock_status, value));
    }

    private void updateNearfieldUnlockStatus(TextView tv, int value) {
        if (tv == null) return;
        tv.setText(getString(R.string.nearfield_unlock_status, value));
    }

    private void refreshAirQuality() {
        String unknown = getString(R.string.air_quality_unknown);
        TextView tvPm25 = findViewById(R.id.tvAirQualityPm25);
        TextView tvOutTemp = findViewById(R.id.tvAirQualityOutdoorTemp);
        TextView tvFilter = findViewById(R.id.tvAirQualityPm25Filter);
        TextView tvAqs = findViewById(R.id.tvAirQualityAqs);
        if (tvPm25 != null) {
            int pm = MG4Hardware.getPm25Concentration();
            tvPm25.setText(getString(R.string.air_quality_pm25, pm >= 0 ? String.valueOf(pm) : unknown));
        }
        if (tvOutTemp != null) {
            float t = MG4Hardware.getOutCarTemp();
            tvOutTemp.setText(getString(R.string.air_quality_outdoor_temp,
                    !Float.isNaN(t) ? String.format(Locale.getDefault(), "%.1f °C", t) : unknown));
        }
        TextView tvDrvTemp = findViewById(R.id.tvAirQualityDrvTemp);
        TextView tvPsgTemp = findViewById(R.id.tvAirQualityPsgTemp);
        if (tvDrvTemp != null) {
            int drv = MG4Hardware.getDrvTemp();
            tvDrvTemp.setText(getString(R.string.air_quality_drv_temp, drv >= 0 ? String.valueOf(drv) : unknown));
        }
        if (tvPsgTemp != null) {
            int psg = MG4Hardware.getPsgTemp();
            tvPsgTemp.setText(getString(R.string.air_quality_psg_temp, psg >= 0 ? String.valueOf(psg) : unknown));
        }
        if (tvFilter != null) {
            int f = MG4Hardware.getPm25Filter();
            tvFilter.setText(getString(R.string.air_quality_pm25_filter, f >= 0 ? String.valueOf(f) : unknown));
        }
        if (tvAqs != null) {
            int aqs = MG4Hardware.getAqsSensitivity();
            tvAqs.setText(getString(R.string.air_quality_aqs, aqs >= 0 ? String.valueOf(aqs) : unknown));
        }
    }

    private void refreshWindowPercentages() {
        TextView tv = findViewById(R.id.tvWindowPercentages);
        if (tv == null) return;
        float d = MG4Hardware.getDriveWindow();
        float p = MG4Hardware.getPassengerWindow();
        float l = MG4Hardware.getLeftRearWindow();
        float r = MG4Hardware.getRightRearWindow();
        String sd = d < 0 ? "--" : String.format(Locale.getDefault(), "%.0f", d);
        String sp = p < 0 ? "--" : String.format(Locale.getDefault(), "%.0f", p);
        String sl = l < 0 ? "--" : String.format(Locale.getDefault(), "%.0f", l);
        String sr = r < 0 ? "--" : String.format(Locale.getDefault(), "%.0f", r);
        tv.setText(getString(R.string.window_driver) + " " + sd + "% · "
                + getString(R.string.window_passenger) + " " + sp + "% · "
                + getString(R.string.window_rear_left) + " " + sl + "% · "
                + getString(R.string.window_rear_right) + " " + sr + "%");
    }
}
