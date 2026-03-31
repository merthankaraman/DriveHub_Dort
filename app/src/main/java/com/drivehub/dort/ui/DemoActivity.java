package com.drivehub.dort.ui;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.Button;
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
 * Get'ler (getEspSwitch, getLeaveAutoLockMode, getApproachUnlockMode, getNearfieldUnlockMode vb.)
 * ekran açılışında ve öne gelince çağrılır. Pencere yüzdeleri, hava kalitesi, kapı kilidi ve
 * Track sensörleri (MG4Hardware TRACK_SENSOR_*) ayrıca 2 sn aralıkla güncellenir.
 */
public class DemoActivity extends AppCompatActivity {

    private Spinner mWindowSpinner;
    private TextView mWindowLevel;
    private TextView mWindowTargetLabel;
    private int mWindowTarget = 0;
    private boolean mWindowAutoRunning = false;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mPollRunnable = new Runnable() {
        @Override
        public void run() {
            pollDemoValues();
            mHandler.postDelayed(this, 2000); // 2 saniyede bir (pencereler, hava kalitesi, kilit)
        }
    };
    /** Track sensörlerini daha sık (500 ms) güncelle. */
    private final Runnable mTrackRunnable = new Runnable() {
        @Override
        public void run() {
            refreshTrackSensors();
            mHandler.postDelayed(this, 500); // 500 ms
        }
    };

    // Bluetooth kilidi (deneysel)
    private static final String PREF_BT_LOCK_DEVICE_ADDR = "bt_lock_device_addr";
    private static final String PREF_BT_LOCK_DEVICE_NAME = "bt_lock_device_name";
    private static final String PREF_BT_LOCK_ENABLED = "bt_lock_enabled";
    private static final long BT_LOCK_TIMEOUT_MS = 15_000L; // 15 sn boyunca hiç görülmezse "uzak" say
    // RSSI eşikleri (dBm) — yaklaşık: -60 çok yakın, -90 çok uzak
    private static final int BT_NEAR_RSSI_DBM = -70;
    private static final int BT_FAR_RSSI_DBM = -85;

    private BluetoothAdapter mBtAdapter;
    private String mBtLockDeviceAddr;
    private String mBtLockDeviceName;
    private long mBtLockLastSeen = 0L;
    private boolean mBtLockReceiverRegistered = false;
    private int mBtLockLastRssi = Integer.MIN_VALUE;
    private boolean mBtLockWasNearOnce = false;
    private TextView mBtLockStatusView;

    private final Handler mBtHandler = new Handler(Looper.getMainLooper());
    @SuppressLint("MissingPermission")
    private final Runnable mBtScanRunnable = new Runnable() {
        @Override
        public void run() {
            if (mBtAdapter == null) return;
            if (!mBtAdapter.isEnabled()) return;
            try {
                if (!mBtAdapter.isDiscovering()) {
                    mBtAdapter.startDiscovery();
                }
            } catch (SecurityException ignored) {
                // İzin hatası olursa taramayı sessizce atla
                return;
            }
            // Daha sık tarama: yaklaşık 10 sn arayla
            mBtHandler.postDelayed(this, 10_000L);
        }
    };

    @SuppressLint("MissingPermission")
    private final BroadcastReceiver mBtReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device == null || mBtLockDeviceAddr == null) return;
                String addr = device.getAddress();
                if (addr != null && addr.equals(mBtLockDeviceAddr)) {
                    mBtLockLastSeen = System.currentTimeMillis();
                    int rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                    mBtLockLastRssi = rssi;
                    if (rssi != Short.MIN_VALUE && rssi >= BT_NEAR_RSSI_DBM) {
                        // En az bir kez gerçekten yakında görmüş olalım
                        mBtLockWasNearOnce = true;
                    }
                    updateBtLockStatusText();
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                maybeAutoLockByBluetooth();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_demo);
        FullscreenHelper.applyFromPrefs(this);

        findViewById(R.id.btnDemoBack).setOnClickListener(v -> finish());

        // Pencere: spinner + seekbar + yüzdeler
        mWindowSpinner = findViewById(R.id.spinnerWindowSelect);
        mWindowLevel = findViewById(R.id.tvWindowLevel);
        mWindowTargetLabel = findViewById(R.id.tvWindowTarget);
        SeekBar seekTarget = findViewById(R.id.seekBarWindowTarget);
        Button btnGoToTarget = findViewById(R.id.btnWindowGoToTarget);
        if (mWindowSpinner != null && mWindowLevel != null) {
            String[] items = {
                getString(R.string.window_driver),
                getString(R.string.window_passenger),
                getString(R.string.window_rear_left),
                getString(R.string.window_rear_right),
                getString(R.string.window_all)
            };
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            mWindowSpinner.setAdapter(adapter);

            Button btnStepClose = findViewById(R.id.btnWindowStepClose);
            Button btnStepOpen = findViewById(R.id.btnWindowStepOpen);
            Button btnFullClose = findViewById(R.id.btnWindowFullClose);
            Button btnFullOpen = findViewById(R.id.btnWindowFullOpen);

            if (btnStepClose != null) {
                btnStepClose.setOnClickListener(v -> sendWindowCommand(1));
            }
            if (btnStepOpen != null) {
                btnStepOpen.setOnClickListener(v -> sendWindowCommand(2));
            }
            if (btnFullClose != null) {
                btnFullClose.setOnClickListener(v -> sendWindowCommand(3));
            }
            if (btnFullOpen != null) {
                btnFullOpen.setOnClickListener(v -> sendWindowCommand(4));
            }

            updateWindowCommandLabel(0);
            refreshWindowPercentages();

            if (seekTarget != null && mWindowTargetLabel != null) {
                seekTarget.setMax(100);
                seekTarget.setProgress(0);
                updateWindowTargetLabel(0);
                seekTarget.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if (!fromUser) return;
                        mWindowTarget = progress;
                        updateWindowTargetLabel(progress);
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {}

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {}
                });
            }

            if (btnGoToTarget != null) {
                btnGoToTarget.setOnClickListener(v -> startWindowAutoProgram());
            }
        }
        refreshAirQuality();

        // Kapı kilidi butonları
        Button btnDoorLock = findViewById(R.id.btnDoorLock);
        Button btnDoorUnlock = findViewById(R.id.btnDoorUnlock);
        if (btnDoorLock != null) {
            btnDoorLock.setOnClickListener(v -> {
                MG4Hardware.setDoorLock(1);
                updateDoorLockStatus();
            });
        }
        if (btnDoorUnlock != null) {
            btnDoorUnlock.setOnClickListener(v -> {
                // MG4 binder tarafında 2 değeri kilit açma komutu gibi davranıyor
                MG4Hardware.setDoorLock(2);
                updateDoorLockStatus();
            });
        }

        // Bluetooth kilidi (deneysel)
        mBtAdapter = BluetoothAdapter.getDefaultAdapter();
        mBtLockStatusView = findViewById(R.id.tvBtLockStatus);
        SwitchCompat swBtEnabled = findViewById(R.id.switchBtLockEnabled);
        Button btnBtSelect = findViewById(R.id.btnBtLockSelectDevice);
        if (mBtLockStatusView != null && swBtEnabled != null && btnBtSelect != null) {
            loadBtLockPrefs(swBtEnabled);
            updateBtLockStatusText();
            btnBtSelect.setOnClickListener(v -> showBtDevicePicker(swBtEnabled));
            swBtEnabled.setOnCheckedChangeListener((v, isChecked) -> {
                getSharedPreferences("drivehub_dort", MODE_PRIVATE)
                        .edit().putBoolean(PREF_BT_LOCK_ENABLED, isChecked).apply();
                if (!isChecked) {
                    stopBluetoothLockMonitoring();
                } else {
                    startBluetoothLockMonitoring();
                }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshWindowPercentages();
        refreshAirQuality();
        updateDoorLockStatus();
        mHandler.post(mPollRunnable);
        mHandler.post(mTrackRunnable);
        startBluetoothLockMonitoring();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            FullscreenHelper.applyFromPrefs(this);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mPollRunnable);
        mHandler.removeCallbacks(mTrackRunnable);
        stopBluetoothLockMonitoring();
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
        if (tvFilter != null) {
            int f = MG4Hardware.getPm25Filter();
            tvFilter.setText(getString(R.string.air_quality_pm25_filter, f >= 0 ? String.valueOf(f) : unknown));
        }
        if (tvAqs != null) {
            int aqs = MG4Hardware.getAqsSensitivity();
            tvAqs.setText(getString(R.string.air_quality_aqs, aqs >= 0 ? String.valueOf(aqs) : unknown));
        }
    }

    /** Pencere yüzdeleri ve hava kalitesini periyodik güncelle. */
    private void pollDemoValues() {
        // Pencere yüzdeleri + hava kalitesi
        refreshWindowPercentages();
        refreshAirQuality();
        updateDoorLockStatus();
    }

    /** MG4Hardware TRACK_SENSOR_* — CarPropertyManager global area (Demo). */
    private void refreshTrackSensors() {
        TextView tv = findViewById(R.id.tvTrackSensors);
        if (tv == null) return;
        StringBuilder sb = new StringBuilder(900);
        appendTrackFloat(sb, MG4Hardware.TRACK_SENSOR_LATERAL_ACCEL, "Yanal ivme");
        appendTrackFloat(sb, MG4Hardware.TRACK_SENSOR_ACCEL_PORTRAIT, "Boyuna ivme");
        appendTrackInt(sb, MG4Hardware.TRACK_SENSOR_DRIVE_EFFICIENCY, "Güç %");
        appendTrackFloat(sb, MG4Hardware.TRACK_SENSOR_FAST_ACCEL_DECEL, "Güç pedalı %.");
        appendTrackInt(sb, MG4Hardware.TRACK_SENSOR_BRAKE_PEDAL_PRESSURE, "Fren basıncı");// maks 4500 gördüm
        appendTrackFloat(sb, MG4Hardware.TRACK_SENSOR_WHEEL_ANGLE, "Direksiyon °");
        appendTrackInt(sb, MG4Hardware.PROP_TIRE_TEMP_FL, "Lastik sıcaklık ön sol °");
        appendTrackInt(sb, MG4Hardware.PROP_TIRE_TEMP_FR, "Lastik sıcaklık ön sağ °");
        appendTrackInt(sb, MG4Hardware.PROP_TIRE_TEMP_RL, "Lastik sıcaklık arka sol °");
        appendTrackInt(sb, MG4Hardware.PROP_TIRE_TEMP_RR, "Lastik sıcaklık arka sağ °");
        appendObdRequestInt(sb, MG4Hardware.PROP_TORQUE_PERCENT_DRIVER_DEMAND_INDEX, "Sürüc talebi tork ");
        appendObdRequestInt(sb, MG4Hardware.PROP_TORQUE_PERCENT_ENGINE_ACTUAL_INDEX, "Gerçek tork ");
        appendObdRequestInt(sb, MG4Hardware.PROP_TORQUE_PERCENT_ENGINE_REFERENCE_INDEX, "Referans tork ");
        tv.setText(sb.toString());
    }

    /** CarDiagnosticManager — IntegerSensorIndex fiilî % tork (null = çerçeve yok / destek yok). */
    private static void appendObdRequestInt(StringBuilder sb, int propId, String label) {
        Integer v = MG4Hardware.readObdValueInt(propId);
        //String s = (v == null) ? "--" : String.valueOf(v);
        String s = String.valueOf(v);
        sb.append(String.format(Locale.US, "%s: %s%n", label, s));
    }
    private static void appendObdRequestFloat(StringBuilder sb, int propId, String label) {
        float f = MG4Hardware.readObdValueFloat(propId);
        String v = Float.isNaN(f) ? "--" : String.format(Locale.US, "%.4f", f);
        sb.append(String.format(Locale.US, "%s: %s%n", label, v));
    }

    private static void appendTrackFloat(StringBuilder sb, int propId, String label) {
        float f = MG4Hardware.readTrackSensorFloat(propId);
        String v = Float.isNaN(f) ? "--" : String.format(Locale.US, "%.4f", f);
        sb.append(String.format(Locale.US, "%s: %s%n", label, v));
    }

    private static void appendTrackInt(StringBuilder sb, int propId, String label) {
        int i = MG4Hardware.readTrackSensorInt(propId);
        //String v = (i < 0) ? "--" : String.valueOf(i);
        String v = String.valueOf(i);
        sb.append(String.format(Locale.US, "%s: %s%n", label, v));
    }

    private void updateDoorLockStatus() {
        TextView tv = findViewById(R.id.tvDoorLockStatus);
        if (tv == null) return;
        int v = MG4Hardware.getDoorLock();
        String human;
        if (v == 1 || v == 2) {
            human = getString(R.string.door_lock_lock);
        } else if (v == 0) {
            human = getString(R.string.door_lock_unlock);
        } else {
            human = "--";
        }
        tv.setText(getString(R.string.door_lock_status_format, human, v));
    }

    private void loadBtLockPrefs(SwitchCompat swEnabled) {
        String addr = getSharedPreferences("drivehub_dort", MODE_PRIVATE)
                .getString(PREF_BT_LOCK_DEVICE_ADDR, null);
        String name = getSharedPreferences("drivehub_dort", MODE_PRIVATE)
                .getString(PREF_BT_LOCK_DEVICE_NAME, null);
        boolean enabled = getSharedPreferences("drivehub_dort", MODE_PRIVATE)
                .getBoolean(PREF_BT_LOCK_ENABLED, false);
        mBtLockDeviceAddr = addr;
        mBtLockDeviceName = name;
        swEnabled.setChecked(enabled);
    }

    @SuppressLint("MissingPermission")
    private void showBtDevicePicker(SwitchCompat swEnabled) {
        if (mBtAdapter == null) {
            if (mBtLockStatusView != null) {
                mBtLockStatusView.setText(R.string.bt_lock_toast_bt_off);
            }
            return;
        }
        if (!mBtAdapter.isEnabled()) {
            if (mBtLockStatusView != null) {
                mBtLockStatusView.setText(R.string.bt_lock_toast_bt_off);
            }
            return;
        }
        // Basit: eşleştirilmiş (bonded) cihazlardan seçim
        java.util.Set<BluetoothDevice> bonded = mBtAdapter.getBondedDevices();
        if (bonded == null || bonded.isEmpty()) {
            if (mBtLockStatusView != null) {
                mBtLockStatusView.setText(R.string.bt_lock_status_no_device);
            }
            return;
        }
        final java.util.List<BluetoothDevice> list = new java.util.ArrayList<>(bonded);
        CharSequence[] names = new CharSequence[list.size()];
        for (int i = 0; i < list.size(); i++) {
            BluetoothDevice d = list.get(i);
            String n = d.getName();
            if (n == null || n.isEmpty()) n = d.getAddress();
            names[i] = n + " (" + d.getAddress() + ")";
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.bt_lock_select_device)
                .setItems(names, (dialog, which) -> {
                    BluetoothDevice d = list.get(which);
                    mBtLockDeviceAddr = d.getAddress();
                    mBtLockDeviceName = d.getName() != null ? d.getName() : d.getAddress();
                    mBtLockLastSeen = 0L;
                    mBtLockLastRssi = Integer.MIN_VALUE;
                    mBtLockWasNearOnce = false;
                    getSharedPreferences("drivehub_dort", MODE_PRIVATE)
                            .edit()
                            .putString(PREF_BT_LOCK_DEVICE_ADDR, mBtLockDeviceAddr)
                            .putString(PREF_BT_LOCK_DEVICE_NAME, mBtLockDeviceName)
                            .apply();
                    updateBtLockStatusText();
                    if (swEnabled.isChecked()) {
                        startBluetoothLockMonitoring();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @SuppressLint("MissingPermission")
    private void startBluetoothLockMonitoring() {
        if (mBtAdapter == null) return;
        boolean enabled = getSharedPreferences("drivehub_dort", MODE_PRIVATE)
                .getBoolean(PREF_BT_LOCK_ENABLED, false);
        if (!enabled) return;
        // Fail-safe: Bluetooth kapalıysa asla kilitleme denemesi yapma
        if (!mBtAdapter.isEnabled()) return;
        if (!mBtLockReceiverRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(BluetoothDevice.ACTION_FOUND);
            f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            registerReceiver(mBtReceiver, f);
            mBtLockReceiverRegistered = true;
        }
        mBtHandler.removeCallbacks(mBtScanRunnable);
        mBtHandler.post(mBtScanRunnable);
    }

    @SuppressLint("MissingPermission")
    private void stopBluetoothLockMonitoring() {
        mBtHandler.removeCallbacks(mBtScanRunnable);
        if (mBtAdapter != null) {
            try {
                if (mBtAdapter.isDiscovering()) {
                    mBtAdapter.cancelDiscovery();
                }
            } catch (SecurityException ignored) {
                // Güvenlik istisnasını görmezden gel
            }
        }
        if (mBtLockReceiverRegistered) {
            try {
                unregisterReceiver(mBtReceiver);
            } catch (Exception ignored) {}
            mBtLockReceiverRegistered = false;
        }
    }

    private void maybeAutoLockByBluetooth() {
        if (mBtLockDeviceAddr == null) return;
        boolean enabled = getSharedPreferences("drivehub_dort", MODE_PRIVATE)
                .getBoolean(PREF_BT_LOCK_ENABLED, false);
        if (!enabled) return;
        if (mBtAdapter == null || !mBtAdapter.isEnabled()) return; // fail-safe: BT kapalıyken kilitleme
        // Araç çalışıyorken (READY) asla otomatik kilitleme denemesi yapma
        if (MG4Hardware.isVehicleReady()) return;
        long now = System.currentTimeMillis();
        // En az bir kez gerçekten yakında görmeden kilitlemeye kalkma
        if (!mBtLockWasNearOnce) return;

        boolean longTimeNoSee = (mBtLockLastSeen == 0L) || ((now - mBtLockLastSeen) > BT_LOCK_TIMEOUT_MS);
        boolean veryWeakSignal = (mBtLockLastRssi != Integer.MIN_VALUE && mBtLockLastRssi <= BT_FAR_RSSI_DBM);

        if (longTimeNoSee || veryWeakSignal) {
            // Cihaz belirgin biçimde uzaklaştı: kilitle
            MG4Hardware.setDoorLock(1);
            updateDoorLockStatus();
            android.widget.Toast.makeText(this, R.string.bt_lock_toast_locked, android.widget.Toast.LENGTH_SHORT).show();
            // Bir kere kilitledikten sonra yeniden yakınlaşana kadar tekrar tetikleme
            mBtLockWasNearOnce = false;
        }
    }

    private void updateBtLockStatusText() {
        if (mBtLockStatusView == null) return;
        if (mBtLockDeviceAddr == null || mBtLockDeviceName == null) {
            mBtLockStatusView.setText(R.string.bt_lock_status_no_device);
        } else {
            String base = getString(R.string.bt_lock_status_device, mBtLockDeviceName);
            if (mBtLockLastRssi != Integer.MIN_VALUE) {
                base = base + " (" + mBtLockLastRssi + " dBm)";
            }
            mBtLockStatusView.setText(base);
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

    private void startWindowAutoProgram() {
        if (mWindowSpinner == null) return;
        mWindowAutoRunning = true;
        stepWindowTowardsTarget();
    }

    private void stepWindowTowardsTarget() {
        if (!mWindowAutoRunning || mWindowSpinner == null) return;

        int sel = mWindowSpinner.getSelectedItemPosition();
        if (sel < 0) sel = 0;

        float current;
        switch (sel) {
            case 0:
                current = MG4Hardware.getDriveWindow();
                break;
            case 1:
                current = MG4Hardware.getPassengerWindow();
                break;
            case 2:
                current = MG4Hardware.getLeftRearWindow();
                break;
            case 3:
                current = MG4Hardware.getRightRearWindow();
                break;
            default:
                current = -1f;
                break;
        }

        if (current < 0f || Float.isNaN(current)) {
            mWindowAutoRunning = false;
            return;
        }

        float target = mWindowTarget;

        // Uç değerlerde direkt tam aç / tam kapat komutlarını kullan
        if (target <= 5f) {
            sendWindowCommand(3); // Tam kapat
            mWindowAutoRunning = false;
            refreshWindowPercentages();
            return;
        } else if (target >= 95f) {
            sendWindowCommand(4); // Tam aç
            mWindowAutoRunning = false;
            refreshWindowPercentages();
            return;
        }

        float diff = target - current;
        if (Math.abs(diff) <= 15f) {
            // Hedefe yeterince yaklaştık (±15%)
            mWindowAutoRunning = false;
            refreshWindowPercentages();
            return;
        }

        // Hedefe doğru bir kademe git
        if (diff > 0f) {
            // Açma yönü
            sendWindowCommand(2);
        } else {
            // Kapama yönü
            sendWindowCommand(1);
        }

        // Biraz bekleyip tekrar ölç
        mHandler.postDelayed(this::stepWindowTowardsTarget, 800);
    }

    private void sendWindowCommand(int command) {
        if (command < 1 || command > 4) return;
        if (mWindowSpinner == null) return;
        int sel = mWindowSpinner.getSelectedItemPosition();
        if (sel < 0) sel = 0;

        switch (sel) {
            case 0:
                MG4Hardware.setDriveWindow((float) command);
                break;
            case 1:
                MG4Hardware.setPassengerWindow((float) command);
                break;
            case 2:
                MG4Hardware.setLeftRearWindow((float) command);
                break;
            case 3:
                MG4Hardware.setRightRearWindow((float) command);
                break;
            case 4:
                MG4Hardware.setDriveWindow((float) command);
                MG4Hardware.setPassengerWindow((float) command);
                MG4Hardware.setLeftRearWindow((float) command);
                MG4Hardware.setRightRearWindow((float) command);
                break;
            default:
                break;
        }

        updateWindowCommandLabel(command);
        refreshWindowPercentages();
    }

    private void updateWindowCommandLabel(int command) {
        if (mWindowLevel == null) return;
        int resId;
        switch (command) {
            case 1:
                resId = R.string.window_cmd_step_close;
                break;
            case 2:
                resId = R.string.window_cmd_step_open;
                break;
            case 3:
                resId = R.string.window_cmd_full_close;
                break;
            case 4:
                resId = R.string.window_cmd_full_open;
                break;
            case 0:
            default:
                resId = R.string.window_cmd_none;
                break;
        }
        mWindowLevel.setText(getString(resId));
    }

    private void updateWindowTargetLabel(int target) {
        if (mWindowTargetLabel == null) return;
        mWindowTargetLabel.setText(getString(R.string.window_target_label, target));
    }
}
