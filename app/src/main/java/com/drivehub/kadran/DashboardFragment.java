package com.drivehub.kadran;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.github.anastr.speedviewlib.PointerSpeedometer;
import com.github.anastr.speedviewlib.TubeSpeedometer;

import com.drivehub.dort.R;

import me.ibrahimsn.lib.Speedometer;

/**
 * Ana kadran ekranı (ViewPager içinde ikinci sayfa).
 */
public class DashboardFragment extends Fragment {

    private TextView tvGear;
    private TextView tvVersion;
    private Button btnMode;
    private View rootView;

    private static final long GAUGE_GREETING_DURATION_MS = 2000L;
    private static final long GAUGE_ANIMATING_DURATION_MS = 100L;

    private TubeSpeedometer gaugeRpmDial;
    private Speedometer gaugeSpeedometer;
    private PointerSpeedometer gaugePowerDial;

    private Button btnSpeedTest;
    private View layoutSpeedTest;
    private SeekBar seekSpeedTest;
    private SeekBar seekRpmTest;
    private SeekBar seekThrottleTest;
    private SeekBar seekPowerTest;
    private TextView tvSimSpeedLabel;
    private TextView tvSimRpmLabel;
    private TextView tvSimThrottleLabel;
    private TextView tvSimPowerLabel;
    private boolean simMode = false;
    private float simSpeedKmh = 0f;
    private float simRpm = 0f;
    private float simThrottle = 0f;
    private float simPowerKw = 0f;
    private final Handler greetingHandler = new Handler(Looper.getMainLooper());
    private static boolean greetingPlayedOnce = false;

    private static final String PREFS_NAME = "drivehub_kadran_prefs";
    private static final String KEY_THEME_MODE = "theme_mode";
    private static final int MODE_AUTO = 0;
    private static final int MODE_NIGHT = 1;
    private static final int MODE_DAY = 2;
    private int currentThemeMode = MODE_AUTO;
    private boolean isNightMode = true;

    private boolean mTelemetryObserverRegistered = false;
    private final Handler telemetryHandler = new Handler(Looper.getMainLooper());
    private final ContentObserver telemetryObserver = new ContentObserver(telemetryHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (simMode) return;
            telemetryHandler.post(DashboardFragment.this::readTelemetryOnce);
        }
    };

    private void readTelemetryOnce() {
        if (simMode) return;
        if (TelemetrySnapshot.load(requireContext())) {
            updateDisplay();
        }
    }

    /** Tema / renk güncellemesinden sonra önbellekteki telemetriyle ibreleri tazeler. */
    private void refreshDisplayAfterTheme() {
        if (simMode) {
            updateFromSim();
        } else if (TelemetrySnapshot.isValid()) {
            updateDisplay();
        } else {
            readTelemetryOnce();
        }
    }

    private float progressToSimPower(int progress) {
        if (progress <= 80) {
            return -80f + progress;
        } else {
            return (float) (progress - 80);
        }
    }

    private void updateDisplay() {
        float rpm = TelemetrySnapshot.getRpm();
        float speed = TelemetrySnapshot.getSpeedKmh();
        float dcKw = TelemetrySnapshot.getDcKw();
        int gear = TelemetrySnapshot.getGear();
        float rpmMax = TelemetrySnapshot.getRpmMax();
        float motorMaxKw = TelemetrySnapshot.getMotorMaxKw();
        if (rpm == -1) {
            gaugeRpmDial.setVisibility(View.INVISIBLE);
            tvGear.setVisibility(View.INVISIBLE);
        } else {
            gaugeRpmDial.setVisibility(View.VISIBLE);
            tvGear.setVisibility(View.VISIBLE);
        }
        if (tvGear != null) {
            tvGear.setText("A" + Math.max(gear, 1));
        }
        if (gaugeRpmDial != null) {
            float rpmClamped = rpm;
            if (rpmClamped < 0f) rpmClamped = 0f;
            gaugeRpmDial.speedTo(rpmClamped, GAUGE_ANIMATING_DURATION_MS);
            gaugeRpmDial.setMaxSpeed(rpmMax);
        }
        if (gaugeSpeedometer != null) {
            int s = (int) speed;
            if (s < 0) s = 0;
            gaugeSpeedometer.setSpeed(s, 0, null);
        }
        if (gaugePowerDial != null) {
            boolean isRegen = dcKw < 0f;
            float value = isRegen ? -dcKw : dcKw;
            float max = isRegen ? 80f : motorMaxKw;
            if (value < 0f) value = 0f;
            gaugePowerDial.setMaxSpeed(max);
            if (isRegen) {
                gaugePowerDial.setBackgroundCircleColor(
                        ContextCompat.getColor(requireContext(), R.color.gauge_regen));
            } else {
                int powerPerc = TelemetrySnapshot.getVehiclePowerPerc();
                powerPerc = Math.max(powerPerc, 0);
                if (powerPerc > 60) {
                    gaugePowerDial.setBackgroundCircleColor(
                            ContextCompat.getColor(requireContext(), R.color.gauge_power_high));
                } else {
                    gaugePowerDial.setBackgroundCircleColor(
                            ContextCompat.getColor(requireContext(), R.color.gauge_accent));
                }
            }
            gaugePowerDial.speedTo(value, GAUGE_ANIMATING_DURATION_MS);
        }
    }

    private void applyAppCompatNightModeFromPrefs() {
        int mode;
        if (currentThemeMode == MODE_AUTO) {
            mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        } else if (currentThemeMode == MODE_NIGHT) {
            mode = AppCompatDelegate.MODE_NIGHT_YES;
        } else {
            mode = AppCompatDelegate.MODE_NIGHT_NO;
        }
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    /** Sistem / far ile uiMode değişince (configChanges) veya tema düğmesinden sonra */
    public void onThemeConfigurationChanged() {
        applyMode(false);
        //refreshDisplayAfterTheme(); TODO test
    }

    private void applyMode(boolean fromUser) {
        boolean night;
        if (currentThemeMode == MODE_AUTO) {
            int uiMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            night = (uiMode == Configuration.UI_MODE_NIGHT_YES);
        } else if (currentThemeMode == MODE_NIGHT) {
            night = true;
        } else {
            night = false;
        }
        isNightMode = night;

        Context ctx = requireContext();
        int screenBg = ContextCompat.getColor(ctx, R.color.screen_background);
        if (rootView != null) {
            rootView.setBackgroundColor(screenBg);
        }

        if (tvVersion != null) {
            tvVersion.setTextColor(ContextCompat.getColor(ctx, R.color.accent_title));
        }
        if (tvGear != null) {
            tvGear.setTextColor(ContextCompat.getColor(ctx, R.color.gauge_text));
        }

        if (btnMode != null) {
            String text;
            switch (currentThemeMode) {
                default:
                case MODE_AUTO:
                    text = "🌓";
                    break;
                case MODE_NIGHT:
                    text = "🌙";
                    break;
                case MODE_DAY:
                    text = "☀️";
                    break;
            }
            btnMode.setText(text);
        }

        int dialBack = ContextCompat.getColor(ctx, R.color.gauge_dial_back);
        int dialText = ContextCompat.getColor(ctx, R.color.gauge_text);

        if (gaugeRpmDial != null) {
            gaugeRpmDial.setSpeedometerBackColor(dialBack);
            gaugeRpmDial.setUnitTextColor(dialText);
            gaugeRpmDial.setSpeedTextColor(dialText);
        }
        if (gaugePowerDial != null) {
            gaugePowerDial.setUnitTextColor(dialText);
            gaugePowerDial.setSpeedTextColor(dialText);
        }
        if (gaugeSpeedometer != null) {
            gaugeSpeedometer.setTextColor(dialText);
        }

        if (layoutSpeedTest != null) {
            layoutSpeedTest.setBackgroundColor(ContextCompat.getColor(ctx, R.color.panel_bottom));
        }
        int statusColor = ContextCompat.getColor(ctx, R.color.status_value);
        if (tvSimSpeedLabel != null) {
            tvSimSpeedLabel.setTextColor(statusColor);
        }
        if (tvSimRpmLabel != null) {
            tvSimRpmLabel.setTextColor(statusColor);
        }
        if (tvSimThrottleLabel != null) {
            tvSimThrottleLabel.setTextColor(statusColor);
        }
        if (tvSimPowerLabel != null) {
            tvSimPowerLabel.setTextColor(statusColor);
        }
    }

    private void updateFromSim() {
        TelemetrySnapshot.setSim(simRpm, simSpeedKmh, simPowerKw, 0, 7000f, 150f);
        updateDisplay();
    }

    private void runGaugeGreeting() {
        if (gaugeRpmDial != null) {
            gaugeRpmDial.speedTo(8000f, GAUGE_GREETING_DURATION_MS);
            greetingHandler.postDelayed(
                    () -> gaugeRpmDial.speedTo(0f, GAUGE_GREETING_DURATION_MS),
                    GAUGE_GREETING_DURATION_MS + 50
            );
        }
        if (gaugeSpeedometer != null) {
            int maxSpeed = 180;
            gaugeSpeedometer.setSpeed(maxSpeed, GAUGE_GREETING_DURATION_MS, null);
            greetingHandler.postDelayed(
                    () -> gaugeSpeedometer.setSpeed(0, GAUGE_GREETING_DURATION_MS, null),
                    GAUGE_GREETING_DURATION_MS + 50
            );
        }
        if (gaugePowerDial != null) {
            float maxPower = 150f;
            gaugePowerDial.setMaxSpeed(maxPower);
            gaugePowerDial.speedTo(0, 0);
            gaugePowerDial.speedTo(maxPower, GAUGE_GREETING_DURATION_MS);
            greetingHandler.postDelayed(
                    () -> gaugePowerDial.speedTo(0f, GAUGE_GREETING_DURATION_MS),
                    GAUGE_GREETING_DURATION_MS + 50
            );
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        rootView = view.findViewById(R.id.rootScroll);
        tvGear = view.findViewById(R.id.tvGaugeGear);
        tvVersion = view.findViewById(R.id.tvVersion);
        btnMode = view.findViewById(R.id.btnMode);
        gaugeRpmDial = view.findViewById(R.id.gaugeRpmDial);
        gaugeSpeedometer = view.findViewById(R.id.gaugeSpeedometer);
        gaugePowerDial = view.findViewById(R.id.gaugePowerDial);
        btnSpeedTest = view.findViewById(R.id.btnSpeedTest);
        layoutSpeedTest = view.findViewById(R.id.layoutSpeedTest);
        seekSpeedTest = view.findViewById(R.id.seekSpeedTest);
        seekRpmTest = view.findViewById(R.id.seekRpmTest);
        seekThrottleTest = view.findViewById(R.id.seekThrottleTest);
        seekPowerTest = view.findViewById(R.id.seekPowerTest);
        tvSimSpeedLabel = view.findViewById(R.id.tvSimSpeedLabel);
        tvSimRpmLabel = view.findViewById(R.id.tvSimRpmLabel);
        tvSimThrottleLabel = view.findViewById(R.id.tvSimThrottleLabel);
        tvSimPowerLabel = view.findViewById(R.id.tvSimPowerLabel);

        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        currentThemeMode = prefs.getInt(KEY_THEME_MODE, MODE_AUTO);
        applyAppCompatNightModeFromPrefs();
        applyMode(false);

        if (tvVersion != null) {
            String version = "1.0";
            try {
                PackageManager pm = requireContext().getPackageManager();
                PackageInfo info = pm.getPackageInfo(requireContext().getPackageName(), 0);
                if (info != null && info.versionName != null) {
                    version = info.versionName;
                }
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            tvVersion.setText("v" + version);
        }

        if (btnMode != null) {
            btnMode.setOnClickListener(v -> {
                if (currentThemeMode == MODE_AUTO) {
                    currentThemeMode = MODE_DAY;
                } else if (currentThemeMode == MODE_DAY) {
                    currentThemeMode = MODE_NIGHT;
                } else {
                    currentThemeMode = MODE_AUTO;
                }
                applyAppCompatNightModeFromPrefs();
                applyMode(true);
                //refreshDisplayAfterTheme();TODO test
                SharedPreferences p = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                p.edit().putInt(KEY_THEME_MODE, currentThemeMode).apply();
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).refreshTrackFragmentTheme();
                }
            });
        }

        if (btnSpeedTest != null && layoutSpeedTest != null) {
            btnSpeedTest.setOnClickListener(v -> {
                boolean opening = layoutSpeedTest.getVisibility() != View.VISIBLE;
                layoutSpeedTest.setVisibility(opening ? View.VISIBLE : View.GONE);
                simMode = opening;
                if (simMode) {
                    if (seekSpeedTest != null) {
                        simSpeedKmh = seekSpeedTest.getProgress();
                    }
                    if (seekRpmTest != null) {
                        simRpm = seekRpmTest.getProgress();
                    }
                    if (seekThrottleTest != null) {
                        simThrottle = seekThrottleTest.getProgress() / 100f;
                    }
                    if (seekPowerTest != null) {
                        simPowerKw = progressToSimPower(seekPowerTest.getProgress());
                    }
                    updateFromSim();
                }
            });
        }

        if (seekSpeedTest != null) {
            seekSpeedTest.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!simMode) return;
                    simSpeedKmh = progress;
                    updateFromSim();
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
        }

        if (seekRpmTest != null) {
            seekRpmTest.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!simMode) return;
                    simRpm = progress;
                    updateFromSim();
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
        }

        if (seekThrottleTest != null) {
            seekThrottleTest.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!simMode) return;
                    simThrottle = progress / 100f;
                    updateFromSim();
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
        }

        if (seekPowerTest != null) {
            seekPowerTest.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!simMode) return;
                    simPowerKw = progressToSimPower(progress);
                    updateFromSim();
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        mTelemetryObserverRegistered = false;
        try {
            requireContext().getContentResolver().registerContentObserver(
                    TelemetryConstants.TELEMETRY_CONTENT_URI,
                    false,
                    telemetryObserver
            );
            mTelemetryObserverRegistered = true;
        } catch (SecurityException e) {
            Log.w("KADRAN_TEL", "DriveHub Dort yok veya TelemetryProvider yok; kadran telemetrisiz açılıyor.");
        }
        readTelemetryOnce();

        if (!greetingPlayedOnce) {
            runGaugeGreeting();
            greetingPlayedOnce = true;
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mTelemetryObserverRegistered) {
            try {
                requireContext().getContentResolver().unregisterContentObserver(telemetryObserver);
            } catch (Throwable ignored) {
            }
            mTelemetryObserverRegistered = false;
        }
    }
}
