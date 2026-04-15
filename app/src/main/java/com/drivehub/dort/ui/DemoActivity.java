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
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import android.widget.Toast;

import com.drivehub.dort.R;
import com.drivehub.dort.hardware.MG4Hardware;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Demo ekranı: ESP, cam kontrolleri, kilit otomasyonu (uzaklaşma/yaklaşma/yakın alan), cam yüzdeleri.
 * Ana ekrandaki "Demo" düğmesiyle açılır.
 * Get'ler (getEspSwitch, getLeaveAutoLockMode, getApproachUnlockMode, getNearfieldUnlockMode vb.)
 * ekran açılışında ve öne gelince çağrılır. Pencere yüzdeleri, hava kalitesi, kapı kilidi ve
 * Track sensörleri (MG4Hardware readTrackSensor*) ayrıca 2 sn aralıkla güncellenir; CPM / HVAC prop aralığı elle okunur.
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
    // Diagnostic index brute-force tarama (Demo)
    private static final int DIAG_SCAN_START_INDEX = 0;
    private static final int DIAG_SCAN_END_INDEX = 512;
    private static final int DIAG_SCAN_MAX_LINES = 30;
    private static final long DIAG_SCAN_INTERVAL_MS = 2000L;
    private final Map<Integer, Integer> mDiagLastValues = new HashMap<>();
    private long mDiagLastScanElapsedMs = 0L;
    private String mDiagScanCachedText = "";
    /** CPM {@link MG4Hardware#readTrackSensorFloat(int)} / {@link MG4Hardware#readTrackSensorInt(int)} aralık çıktısı. */
    private String mCpmPropScanCachedText = "";
    private volatile boolean mCpmPropScanRunning = false;
    /** HVAC {@link MG4Hardware#readHvacPropFloat(int)} / {@link MG4Hardware#readHvacPropInt(int)} — area {@link MG4Hardware#AREA_HVAC}. */
    private String mHvacPropScanCachedText = "";
    private volatile boolean mHvacPropScanRunning = false;
    /**
     * Tek taramada en fazla bu kadar property ID (binder süresi uzar; çıktı yine ~120k karakterde kesilir).
     * Örnek: 0x2160f000…0x2160ffff → adet 4096.
     */
    private static final int CPM_PROP_SCAN_MAX_IDS = 2_097_152; // 2^21

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

        Button btnVoiceAssistant = findViewById(R.id.btnVoiceAssistant);
        if (btnVoiceAssistant != null) {
            btnVoiceAssistant.setOnClickListener(v ->
                    startActivity(new Intent(this, VoiceAssistantActivity.class)));
        }

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

        Button btnCpmPropScan = findViewById(R.id.btnCpmPropScan);
        if (btnCpmPropScan != null) {
            btnCpmPropScan.setOnClickListener(v -> runCpmPropRangeScan());
        }
        Button btnHvacPropScan = findViewById(R.id.btnHvacPropScan);
        if (btnHvacPropScan != null) {
            btnHvacPropScan.setOnClickListener(v -> runHvacPropRangeScan());
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

    /** Ivme / fren / lastik: MG4ControlService runMainTask önbelleği; ADAS/OBD doğrudan okuma. */
    private void refreshTrackSensors() {
        TextView tvCpmL = findViewById(R.id.tvCpmScanLeft);
        TextView tvCpmR = findViewById(R.id.tvCpmScanRight);
        if (tvCpmL == null || tvCpmR == null) return;
        //MG4Hardware.refreshDiagnosticLiveFrame();
        StringBuilder sb = new StringBuilder(900);
        StringBuilder sb2 = new StringBuilder(900);

        appendValueFloat(sb, MG4Hardware.readHvacPropFloat(0x15602511), "0x15602511 muhtemel dis");
        appendValueFloat(sb, MG4Hardware.readHvacPropFloat(0x1560252a), "0x1560252a muhtemel dis");
        appendValueFloat(sb, MG4Hardware.getACValMethodFloat("getOutCarTemp"), "getOutCarTemp");

        appendValueInt(sb, MG4Hardware.getACValMethodInt("getDrvTemp"), "getDrvTemp");
        appendValueInt(sb, MG4Hardware.getACValMethodInt("getPsgTemp"), "getPsgTemp");
        appendValueInt(sb, MG4Hardware.getACValMethodInt("getTempDualZoneOn"), "getTempDualZoneOn");
        appendValueInt(sb, MG4Hardware.getSensorTemperature(), "getSensorTemperature");



        appendValueInt(sb, MG4Hardware.readTrackSensorInt(0x2140f416), "klima açk 290 kapalı 305 0x2140f416"); //değişti 290 klima açık oldu 305 klima kapalı oldu
        appendValueInt(sb, MG4Hardware.readTrackSensorInt(0x2140f41c), "klima ve zamana bağlı değişiyor 0x2140f41c"); //değişti 150 oldu 161 oldu 158, 159 oldu klima kapalı 167 oldu 170 oldu
        appendValueInt(sb, MG4Hardware.readTrackSensorInt(0x2140f42d), "klima açık 76 kapalı 80 0x2140f42d"); //değişti 76 klima açık oldu 80 klima kapalo oldu

        appendValueFloat(sb, MG4Hardware.readHvacPropFloat(0x1560251e), "0x1560251e", 50.3937f);
        appendValueFloat(sb, MG4Hardware.readHvacPropFloat(0x1560251f), "0x1560251f", 50.3937f);
        appendValueFloat(sb, MG4Hardware.readHvacPropFloat(0x15602520), "0x15602520", 50.3937f);
        appendValueFloat(sb, MG4Hardware.readHvacPropFloat(0x15602521), "0x15602521", 50.3937f);
        appendValueFloat(sb, MG4Hardware.readHvacPropFloat(0x15602536), "0x15602536", 33.0f);
        appendValueFloat(sb, MG4Hardware.readHvacPropFloat(0x15602547), "0x15602547", 33.0f);


        appendValueInt(sb, MG4Hardware.readHvacPropInt(0x1540250d), "fan seviyesi"); // fan seviyesi 15 auto
        appendValueInt(sb, MG4Hardware.readHvacPropInt(0x1540250e), "0x1540250e",0);


        appendValueFloat(sb, MG4Hardware.readTrackSensorFloat(0x11600305), "0x11600305",0f);
        appendValueFloat(sb, MG4Hardware.readTrackSensorFloat(0x11600703), "0x11600703");
        appendValueFloat(sb, MG4Hardware.readTrackSensorFloat(0x1160030c), "0x1160030c");
        appendValueInt(sb, MG4Hardware.readTrackSensorInt(0x11600309), "0x11600309");
        appendValueInt(sb, MG4Hardware.readTrackSensorInt(0x1540050f), "0x1540050f");
        appendValueInt(sb, MG4Hardware.readTrackSensorInt(0x1540050b), "0x1540050b");
        appendValueFloat(sb, MG4Hardware.readTrackSensorFloat(0x15600502), "0x15600502");
        appendValueFloat(sb, MG4Hardware.readTrackSensorFloat(0x11600106), "0x11600106");


        appendValueInt(sb, MG4Hardware.readTrackSensorInt(0x2140f40e), "0x2140f40e",22);
        appendValueInt(sb, MG4Hardware.readTrackSensorInt(0x2140f40b), "0x2140f40b",4);
        appendValueInt(sb, MG4Hardware.readTrackSensorInt(0x2140f40c), "0x2140f40c",7);
        appendValueInt(sb, MG4Hardware.readTrackSensorInt(0x2140f410), "0x2140f410",6);
        appendValueInt(sb, MG4Hardware.readTrackSensorInt(0x11400400), "0x11400400",4);
        appendValueInt(sb, MG4Hardware.readTrackSensorInt(0x1140050e), "0x1140050e",49);
        appendValueInt(sb, MG4Hardware.readTrackSensorInt(0x11403808), "0x11403808",150);
        appendValueInt(sb, MG4Hardware.readTrackSensorInt(0x1140380b), "0x1140380b",7);
        appendValueInt(sb, MG4Hardware.readTrackSensorInt(0x1140381c), "0x1140381c",16);
        appendValueFloat(sb, MG4Hardware.readTrackSensorFloat(0x11603832), "0x11603832",18.9990f);
        appendValueFloat(sb, MG4Hardware.readTrackSensorFloat(0x21607b5b), "0x21607b5b",63.5f);
        appendValueFloat(sb, MG4Hardware.readTrackSensorFloat(0x2160c620), "0x2160c620",100.0001f);
        appendValueInt(sb, MG4Hardware.readTrackSensorInt(0x21407b18), "0x21407b18",0);
        appendValueInt(sb, MG4Hardware.readTrackSensorInt(0x21407b19), "0x21407b19",2);
        appendValueInt(sb, MG4Hardware.readTrackSensorInt(0x2140db73), "0x2140db73",2000);
        appendValueFloat(sb, MG4Hardware.readTrackSensorFloat(0x2160f405), "0x2160f405",102.3f);
        appendValueFloat(sb, MG4Hardware.readTrackSensorFloat(0x2160f41b), "0x2160f41b",82.3f);
        appendValueFloat(sb, MG4Hardware.readTrackSensorFloat(0x2160f41d), "0x2160f41d",82.3f);
        appendValueFloat(sb, MG4Hardware.readTrackSensorFloat(0x2160f421), "0x2160f421",82.3f);
        appendValueFloat(sb, MG4Hardware.readTrackSensorFloat(0x2160f44d), "0x2160f44d",82.3f);
        appendValueFloat(sb, MG4Hardware.readTrackSensorFloat(0x2160f44f), "0x2160f44f",82.3f);

        appendCpmPropScan(sb2);
        appendHvacPropScan(sb2);
        /*
        appendValueFloat(sb, MG4Hardware.getSensorWheelAngleGlobal(), "Direksiyon °");
        appendValueFloat(sb, MG4Hardware.readTrackSensorFloat(MG4Hardware.PROP_ADAS_FCW_OBJ_DNGRSOBJLONGRLTVDIST), "tehlikeli nesne boyuna mesafe");
        appendValueFloat(sb, MG4Hardware.readTrackSensorFloat(MG4Hardware.PROP_ADAS_FCW_OBJ_DNGRSOBJLATRLTVDIST), "tehlikeli nesne yanal mesafe");
        */
        /*
        appendObdRequest(sb, 0x3a, "OBD test ABSOLUTE_EVAPORATION_SYSTEM_VAPOR_PRESSURE");
        appendObdRequest(sb, 0x1, "OBD test ENGINE_COOLANT_TEMPERATURE");
        appendObdRequest(sb, 0x8, "OBD test ENGINE_RPM");
        appendObdRequest(sb, 0xc, "OBD test THROTTLE_POSITION");
        appendObdRequest(sb, 0x43, "OBD test RELATIVE_ACCELERATOR_PEDAL_POSITION");
        appendObdRequest(sb, 0x0, "OBD test CALCULATED_ENGINE_LOAD");
        */
        tvCpmL.setText(sb.toString());
        tvCpmR.setText(sb2.toString());
    }

    /** {@link MG4Hardware#readTrackSensorFloat(int)} / {@link MG4Hardware#readTrackSensorInt(int)} ile doldurulur; OBD satırlarının altına eklenir. */
    private void appendCpmPropScan(StringBuilder sb) {
        if (!mCpmPropScanCachedText.isEmpty()) {
            sb.append(mCpmPropScanCachedText);
        }
    }

    private void appendHvacPropScan(StringBuilder sb) {
        if (!mHvacPropScanCachedText.isEmpty()) {
            sb.append(mHvacPropScanCachedText);
        }
    }

    private void runCpmPropRangeScan() {
        if (mCpmPropScanRunning) return;
        EditText et0 = findViewById(R.id.etCpmPropStart);
        EditText et1 = findViewById(R.id.etCpmPropCount);
        Button btn = findViewById(R.id.btnCpmPropScan);
        if (et0 == null || et1 == null) return;
        final int start;
        final int count;
        try {
            start = parsePropIdString(et0.getText().toString());
        } catch (NumberFormatException ex) {
            Toast.makeText(this, R.string.demo_cpm_prop_scan_parse_error, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            count = parsePositiveCountString(et1.getText().toString());
        } catch (NumberFormatException ex) {
            Toast.makeText(this, getString(R.string.demo_cpm_prop_scan_parse_count_error, CPM_PROP_SCAN_MAX_IDS),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        mCpmPropScanRunning = true;
        if (btn != null) {
            btn.setEnabled(false);
            btn.setText(R.string.demo_cpm_prop_scan_busy);
        }
        new Thread(() -> {
            String text = buildCpmPropScanText(start, count);
            runOnUiThread(() -> {
                mCpmPropScanCachedText = text;
                mCpmPropScanRunning = false;
                if (btn != null) {
                    btn.setEnabled(true);
                    btn.setText(R.string.demo_cpm_prop_scan_run);
                }
                refreshTrackSensors();
            });
        }, "demo-cpm-prop-scan").start();
    }

    private void runHvacPropRangeScan() {
        if (mHvacPropScanRunning) return;
        EditText et0 = findViewById(R.id.etHvacPropStart);
        EditText et1 = findViewById(R.id.etHvacPropCount);
        Button btn = findViewById(R.id.btnHvacPropScan);
        if (et0 == null || et1 == null) return;
        final int start;
        final int count;
        try {
            start = parsePropIdString(et0.getText().toString());
        } catch (NumberFormatException ex) {
            Toast.makeText(this, R.string.demo_cpm_prop_scan_parse_error, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            count = parsePositiveCountString(et1.getText().toString());
        } catch (NumberFormatException ex) {
            Toast.makeText(this, getString(R.string.demo_cpm_prop_scan_parse_count_error, CPM_PROP_SCAN_MAX_IDS),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        mHvacPropScanRunning = true;
        if (btn != null) {
            btn.setEnabled(false);
            btn.setText(R.string.demo_hvac_prop_scan_busy);
        }
        new Thread(() -> {
            String text = buildHvacPropScanText(start, count);
            runOnUiThread(() -> {
                mHvacPropScanCachedText = text;
                mHvacPropScanRunning = false;
                if (btn != null) {
                    btn.setEnabled(true);
                    btn.setText(R.string.demo_hvac_prop_scan_run);
                }
                refreshTrackSensors();
            });
        }, "demo-hvac-prop-scan").start();
    }

    private static int parsePropIdString(String raw) throws NumberFormatException {
        String s = raw.trim();
        if (s.isEmpty()) throw new NumberFormatException("empty");
        if (s.startsWith("0x") || s.startsWith("0X")) {
            long v = Long.parseLong(s.substring(2), 16);
            return (int) (v & 0xffffffffL);
        }
        long v = Long.parseLong(s);
        return (int) (v & 0xffffffffL);
    }

    /** Okunacak property sayısı: 1 … {@link #CPM_PROP_SCAN_MAX_IDS}, onluk veya 0x…. */
    private static int parsePositiveCountString(String raw) throws NumberFormatException {
        String s = raw.trim();
        if (s.isEmpty()) throw new NumberFormatException("empty");
        long v;
        if (s.startsWith("0x") || s.startsWith("0X")) {
            v = Long.parseLong(s.substring(2), 16);
        } else {
            v = Long.parseLong(s);
        }
        if (v < 1L || v > (long) CPM_PROP_SCAN_MAX_IDS) throw new NumberFormatException("range");
        if (v > Integer.MAX_VALUE) throw new NumberFormatException("overflow");
        return (int) v;
    }

    private String buildCpmPropScanText(int start, int count) {
        if (count < 1 || count > CPM_PROP_SCAN_MAX_IDS) {
            return getString(R.string.demo_cpm_prop_scan_parse_count_error, CPM_PROP_SCAN_MAX_IDS);
        }
        long last = (long) start + (long) count - 1L;
        if (last > Integer.MAX_VALUE || last < Integer.MIN_VALUE) {
            return getString(R.string.demo_cpm_prop_scan_span_overflow);
        }
        long rawCap = (long) count * 64L + 128L;
        int initialCap = (int) Math.min(200_000L, Math.max(2_048L, rawCap));
        StringBuilder out = new StringBuilder(initialCap);
        int lines = 0;
        for (int i = 0; i < count; i++) {
            int id = (int) (((long) start) + (long) i);
            float f = MG4Hardware.readTrackSensorFloat(id);
            int iv = MG4Hardware.readTrackSensorInt(id);
            boolean hasF = !Float.isNaN(f);
            boolean hasI = iv != -1;
            if (!hasF && !hasI) continue;
            if (f == 0f || iv == 0) continue; //TODO 0 value hide feat
            lines++;
            out.append(String.format(Locale.US, "  id=0x%08X (%d)", id, id));
            if (hasF) out.append(String.format(Locale.US, " float=%.4f", f));
            if (hasI) out.append(String.format(Locale.US, " int=%d", iv));
            out.append('\n');
            if (out.length() > 120000) {
                out.append(getString(R.string.demo_cpm_prop_scan_truncated));
                break;
            }
        }
        return getString(R.string.demo_cpm_prop_scan_result_header, start, count, lines) + out;
    }

    /** CPM ile aynı mantık; {@link MG4Hardware#readHvacPropFloat(int)} / {@link MG4Hardware#readHvacPropInt(int)} (area 0x75). */
    private String buildHvacPropScanText(int start, int count) {
        if (count < 1 || count > CPM_PROP_SCAN_MAX_IDS) {
            return getString(R.string.demo_cpm_prop_scan_parse_count_error, CPM_PROP_SCAN_MAX_IDS);
        }
        long last = (long) start + (long) count - 1L;
        if (last > Integer.MAX_VALUE || last < Integer.MIN_VALUE) {
            return getString(R.string.demo_cpm_prop_scan_span_overflow);
        }
        long rawCap = (long) count * 64L + 128L;
        int initialCap = (int) Math.min(200_000L, Math.max(2_048L, rawCap));
        StringBuilder out = new StringBuilder(initialCap);
        int lines = 0;
        for (int i = 0; i < count; i++) {
            int id = (int) (((long) start) + (long) i);
            float f = MG4Hardware.readHvacPropFloat(id);
            int iv = MG4Hardware.readHvacPropInt(id);
            boolean hasF = !Float.isNaN(f);
            boolean hasI = iv != -1;
            if (!hasF && !hasI) continue;
            if (f == 0f || iv == 0) continue; //TODO 0 value hide feat
            lines++;
            out.append(String.format(Locale.US, "  id=0x%08X (%d)", id, id));
            if (hasF) out.append(String.format(Locale.US, " float=%.4f", f));
            if (hasI) out.append(String.format(Locale.US, " int=%d", iv));
            out.append('\n');
            if (out.length() > 120000) {
                out.append(getString(R.string.demo_cpm_prop_scan_truncated));
                break;
            }
        }
        return getString(R.string.demo_hvac_prop_scan_result_header, start, count, lines) + out;
    }

    private String buildDiagnosticScanText() {
        List<String> changed = new ArrayList<>();
        List<String> stable = new ArrayList<>();
        int alive = 0;
        for (int idx = DIAG_SCAN_START_INDEX; idx <= DIAG_SCAN_END_INDEX; idx++) {
            Integer v = MG4Hardware.readDiagnosticSystemIntegerSensor(idx);
            if (v == null) continue;
            alive++;
            Integer prev = mDiagLastValues.put(idx, v);
            String line = String.format(Locale.US, "  idx=%d(0x%02X) -> %d", idx, idx, v);
            if (prev == null || prev.intValue() != v.intValue()) {
                changed.add(line + (prev == null ? " [new]" : " [chg " + prev + "->" + v + "]"));
            } else {
                stable.add(line);
            }
        }

        StringBuilder out = new StringBuilder(800);
        out.append(String.format(Locale.US, "Diag scan [%d..%d] alive=%d changed=%d%n",
                DIAG_SCAN_START_INDEX, DIAG_SCAN_END_INDEX, alive, changed.size()));
        int maxChanged = Math.min(changed.size(), DIAG_SCAN_MAX_LINES);
        for (int i = 0; i < maxChanged; i++) {
            out.append(changed.get(i)).append('\n');
        }
        if (maxChanged < DIAG_SCAN_MAX_LINES) {
            int remain = DIAG_SCAN_MAX_LINES - maxChanged;
            int maxStable = Math.min(stable.size(), remain);
            for (int i = 0; i < maxStable; i++) {
                out.append(stable.get(i)).append('\n');
            }
        }
        return out.toString();
    }
    private static void appendObdRequest(StringBuilder sb, int propId, String label) {
        float f = MG4Hardware.readObdValueFloat(propId);
        int iv = MG4Hardware.readHvacPropInt(propId);
        boolean hasF = !Float.isNaN(f);
        boolean hasI = iv != -1;
        String v;
        if (hasF) {
            v = String.format(Locale.US, "%.4f", f);
        } else if (hasI) {
            v = String.valueOf(iv);
        }
        else v = "null";
        sb.append(String.format(Locale.US, "%s: %s%n", label, v));
    }

    private static void appendValueFloat(StringBuilder sb, float f, String label) {
        String v = Float.isNaN(f) ? "--" : String.format(Locale.US, "%.4f", f);
        sb.append(String.format(Locale.US, "%s: %s%n", label, v));
    }
    private static void appendValueFloat(StringBuilder sb, float f, String label, float defaultval) {
        if (f == defaultval) return;
        String v = Float.isNaN(f) ? "--" : String.format(Locale.US, "%.4f", f);
        sb.append(String.format(Locale.US, "%s: %s%n", label, v));
    }

    /** CPM Integer track alanları (fren, lastik); okunamadı (i negatif) ise --. */
    private static void appendValueInt(StringBuilder sb, int i, String label, int defaultval) {
        if (i == defaultval) return;
        //String v = (i < 0) ? "--" : String.valueOf(i);
        String v = String.valueOf(i);
        sb.append(String.format(Locale.US, "%s: %s%n", label, v));
    }
    private static void appendValueInt(StringBuilder sb, int i, String label) {
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
