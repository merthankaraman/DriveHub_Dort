package com.drivehub.kadran;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.drivehub.dort.R;

import java.util.Locale;

/**
 * Pist modu: ortada büyük hız; ivme süreleri, mesafe (integral), yavaşlama 50–0 / 100–0.
 */
public class TrackFragment extends Fragment {

    private static final float SPEED_IDLE_KMH = 0f;
    /** Bu altında eğim (°) satırı hesaplanır; üstünde sadece "—". */
    private static final float SPEED_STOPPED_MAX_KMH = 1f;
    /** v(km/h)×Δt(s) önce km cinsinden yol verir; ×1000 ile m. Aynı sayı: 1000/3600 = 1/3,6. */
    private static final float METERS_PER_KMH_AND_SECOND = 1000f / 3600f;
    private static final float DIST_M_400 = 400f;
    private static final float DIST_M_800 = 800f;

    private TextView tvLiveSpeed;
    private TextView tvVehiclePowerPerc;
    private TextView tvTime_0_60;
    private TextView tvTime_0_100;
    private TextView tvTime_60_120;
    private TextView tvTime_80_160;
    private TextView tvTime_0_400m;
    private TextView tvTrackKw;
    private TextView tvTrackBrakePressure;
    private TextView tvTime_0_800m;
    private TextView tvTime_50_0;
    private TextView tvTime_100_0;
    private AccelGMeterView gMeter;
    private TextView tvAccelG;
    private TextView tvTireFlBar;
    private TextView tvTireFlTemp;
    private TextView tvTireFrBar;
    private TextView tvTireFrTemp;
    private TextView tvTireRlBar;
    private TextView tvTireRlTemp;
    private TextView tvTireRrBar;
    private TextView tvTireRrTemp;
    private TextView tvTirePressureUnit;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean observerRegistered = false;
    private final ContentObserver telemetryObserver = new ContentObserver(mainHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            mainHandler.post(TrackFragment.this::readTelemetryOnce);
        }
    };

    private long runStartElapsed;
    private long markAt60Ms;
    private long markAt80Ms;
    private long lastIntegrationTimeMs;
    private float distanceMetersAccum;
    private boolean armedForNextRun = true;
    private final boolean[] segmentDone = new boolean[4];
    private final Float[] recordedSeconds = new Float[4];
    private boolean dist400Done;
    private boolean dist800Done;
    private Float recorded400Sec;
    private Float recorded800Sec;

    private float lastSpeedKmh = Float.NaN;
    private long decel50StartMs;
    private long decel100StartMs;

    /** Lastik basıncı gösterimi: true = psi, false = bar (telemetri kPa). */
    private boolean tirePressurePsi = false;

    /** 1 bar = 100 kPa. */
    private static final float KPA_PER_BAR = 100f;
    /** 1 psi ≈ 6,894757 kPa. */
    private static final float KPA_PER_PSI = 6.894757f;

    /** {@link DashboardFragment} ile aynı dosya. */
    private static final String PREFS_NAME = "drivehub_kadran_prefs";
    private static final String PREF_KEY_TIRE_PRESSURE_PSI = "track_tire_pressure_psi";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_track, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        tvLiveSpeed = view.findViewById(R.id.tvLiveSpeed);
        tvVehiclePowerPerc = view.findViewById(R.id.tvVehiclePowerPerc);
        tvTime_0_60 = view.findViewById(R.id.tvTime_0_60);
        tvTime_0_100 = view.findViewById(R.id.tvTime_0_100);
        tvTime_60_120 = view.findViewById(R.id.tvTime_60_120);
        tvTime_80_160 = view.findViewById(R.id.tvTime_80_160);
        tvTime_0_400m = view.findViewById(R.id.tvTime_0_400);
        tvTrackKw = view.findViewById(R.id.tvTrackKw);
        tvTrackBrakePressure = view.findViewById(R.id.tvTrackBrakePressure);
        tvTime_0_800m = view.findViewById(R.id.tvTime_0_800);
        tvTime_50_0 = view.findViewById(R.id.tvTime_50_0);
        tvTime_100_0 = view.findViewById(R.id.tvTime_100_0);
        gMeter = view.findViewById(R.id.gMeter);
        tvAccelG = view.findViewById(R.id.tvAccelG);
        tvTireFlBar = view.findViewById(R.id.tvTireFlBar);
        tvTireFlTemp = view.findViewById(R.id.tvTireFlTemp);
        tvTireFrBar = view.findViewById(R.id.tvTireFrBar);
        tvTireFrTemp = view.findViewById(R.id.tvTireFrTemp);
        tvTireRlBar = view.findViewById(R.id.tvTireRlBar);
        tvTireRlTemp = view.findViewById(R.id.tvTireRlTemp);
        tvTireRrBar = view.findViewById(R.id.tvTireRrBar);
        tvTireRrTemp = view.findViewById(R.id.tvTireRrTemp);
        tvTirePressureUnit = view.findViewById(R.id.tvTirePressureUnit);
        loadTirePressureUnitPref();
        ImageView ivTireWheelHeader = view.findViewById(R.id.ivTireWheelHeader);
        if (ivTireWheelHeader != null) {
            ivTireWheelHeader.setOnClickListener(v -> {
                tirePressurePsi = !tirePressurePsi;
                saveTirePressureUnitPref();
                applyTirePressureUnitLabel();
                updateTires();
            });
        }
        refreshTheme();
    }

    private void loadTirePressureUnitPref() {
        SharedPreferences p =
                requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        tirePressurePsi = p.getBoolean(PREF_KEY_TIRE_PRESSURE_PSI, false);
    }

    private void saveTirePressureUnitPref() {
        requireContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_KEY_TIRE_PRESSURE_PSI, tirePressurePsi)
                .apply();
    }

    private void applyTirePressureUnitLabel() {
        if (tvTirePressureUnit == null) {
            return;
        }
        tvTirePressureUnit.setText(tirePressurePsi ? "psi" : "bar");
    }

    /** AppCompatDelegate / uiMode değişince çağrılır; values vs values-night renkleri yenilenir. */
    public void refreshTheme() {
        View root = getView();
        if (root == null) {
            return;
        }
        Context ctx = requireContext();
        Drawable trackBg = ContextCompat.getDrawable(ctx, R.drawable.track_background_gradient);
        if (trackBg != null) {
            root.setBackground(trackBg);
        } else {
            root.setBackgroundColor(ContextCompat.getColor(ctx, R.color.screen_background));
        }
        applyTrackPanelBackgrounds(root, ctx);
        applyTrackThemeColors(root, ctx);
        applyTirePressureUnitLabel();
        readTelemetryOnce();
    }

    private static void applyTrackPanelBackgrounds(View root, Context ctx) {
        View left = root.findViewById(R.id.trackLeftRail);
        if (left != null) {
            Drawable d = ContextCompat.getDrawable(ctx, R.drawable.track_rail_surface);
            if (d != null) {
                left.setBackground(d);
            }
        }
        View center = root.findViewById(R.id.trackCenterSheet);
        if (center != null) {
            Drawable d = ContextCompat.getDrawable(ctx, R.drawable.track_center_sheet);
            if (d != null) {
                center.setBackground(d);
            }
        }
        View right = root.findViewById(R.id.trackRightRail);
        if (right != null) {
            Drawable d = ContextCompat.getDrawable(ctx, R.drawable.track_rail_surface);
            if (d != null) {
                right.setBackground(d);
            }
        }
        View tireCard = root.findViewById(R.id.tireInnerCard);
        if (tireCard != null) {
            Drawable d = ContextCompat.getDrawable(ctx, R.drawable.bg_tire_panel);
            if (d != null) {
                tireCard.setBackground(d);
            }
        }
        View tireGrid = root.findViewById(R.id.tireGridCross);
        if (tireGrid != null) {
            Drawable d = ContextCompat.getDrawable(ctx, R.drawable.tire_grid_cross);
            if (d != null) {
                tireGrid.setBackground(d);
            }
        }
        ImageView wheelIv = root.findViewById(R.id.ivTireWheelHeader);
        if (wheelIv != null) {
            Drawable d = ContextCompat.getDrawable(ctx, R.drawable.ic_wheel_header);
            if (d != null) {
                wheelIv.setImageDrawable(d);
            }
        }
    }

    private static void applyTrackThemeColors(View v, Context ctx) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            Object tag = tv.getTag();
            if (tag != null && "track_lbl".equals(tag.toString())) {
                tv.setTextColor(ContextCompat.getColor(ctx, R.color.track_label));
            } else if (tag != null && "track_val".equals(tag.toString())) {
                tv.setTextColor(ContextCompat.getColor(ctx, R.color.track_value));
            } else if (tv.getId() == R.id.tvLiveSpeed) {
                tv.setTextColor(ContextCompat.getColor(ctx, R.color.track_speed_big));
            } else if (tv.getId() == R.id.tvAccelG || tv.getId() == R.id.tvVehiclePowerPerc) {
                tv.setTextColor(ContextCompat.getColor(ctx, R.color.track_value));
            }
        } else if (v instanceof AccelGMeterView) {
            v.invalidate();
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                applyTrackThemeColors(g.getChildAt(i), ctx);
            }
        }
    }
    private void applyPowerKwTextColor(TextView target, float powerKw) {
        if (target == null) return;
        int percent = TelemetrySnapshot.getVehiclePowerPerc();
        if (powerKw < 0f) {
            target.setTextColor(ContextCompat.getColor(requireContext(), R.color.gauge_regen));
        } else if (percent > 80) {
            target.setTextColor(ContextCompat.getColor(requireContext(), R.color.gauge_power_high));
        } else if (percent > 50) {
            target.setTextColor(ContextCompat.getColor(requireContext(), R.color.gauge_power_warn));
        } else {
            target.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_value));
        }
    }

    private void readTelemetryOnce() {
        if (TelemetrySnapshot.load(requireContext())) {
            updateLiveSpeed();
            updateVehiclePowerPerc();
            updateTrackKw();
            updateBrakePressure();
            updateTires();
            updateAccelMeter();
            updateDecelerationTimes();
            updateSegmentTimes();
        }
    }

    private void updateAccelMeter() {
        float portrait = TelemetrySnapshot.getAccelPortrait();
        float lateral = TelemetrySnapshot.getAccelLateral();
        if (gMeter != null) {
            gMeter.setAccelerationMs2(portrait, lateral);
        }
        if (tvAccelG != null) {
            final float g = 9.80665f;
            float gLong = portrait / g;
            float gLat = lateral / g;
            float speedKmh = TelemetrySnapshot.getSpeedKmh();
            String tiltLine = formatStoppedTiltDegrees(speedKmh, portrait, lateral, g);
            String AccelS = String.format(Locale.US,
                    "%.2f %.2f g\n%.2f %.2f m/s²",
                    gLong, gLat, portrait, lateral);
            if (speedKmh <= SPEED_STOPPED_MAX_KMH) {
                AccelS = "";
            }
            tvAccelG.setText(String.format(Locale.US,"%s%s", tiltLine, AccelS));
        }
    }
    private static String formatStoppedTiltDegrees(
            float speedKmh, float portraitMs2, float lateralMs2, float gMs2) {
        if (speedKmh > SPEED_STOPPED_MAX_KMH) {
            return "";
        }
        float g2 = gMs2 * gMs2;
        float p2l2 = portraitMs2 * portraitMs2 + lateralMs2 * lateralMs2;
        float gz = (float) Math.sqrt(Math.max(0f, g2 - p2l2));
        double pitchRad = Math.atan2(portraitMs2,
                Math.sqrt(lateralMs2 * lateralMs2 + gz * gz));
        double rollRad = Math.atan2(lateralMs2,
                Math.sqrt(portraitMs2 * portraitMs2 + gz * gz));
        float pitchDeg = (float) Math.toDegrees(pitchRad);
        float rollDeg = (float) Math.toDegrees(rollRad);
        return String.format(Locale.US, "%+.2f° %+.2f°", pitchDeg, rollDeg);
    }

    private void updateLiveSpeed() {
        if (tvLiveSpeed != null) {
            tvLiveSpeed.setText(String.format(Locale.US, "%.2f km/h", TelemetrySnapshot.getSpeedKmh()));
        }
    }

    private void updateVehiclePowerPerc() {
        if (tvVehiclePowerPerc == null) {
            return;
        }
        int p = TelemetrySnapshot.getVehiclePowerPerc();
        tvVehiclePowerPerc.setText(String.format(Locale.US, "%% %d", p));
        applyPowerKwTextColor(tvVehiclePowerPerc, p);
    }

    private void updateTrackKw() {
        if (tvTrackKw == null) {
            return;
        }
        float kw = TelemetrySnapshot.getDcKw();
        applyPowerKwTextColor(tvTrackKw, kw);
        tvTrackKw.setText(String.format(Locale.US, "%.2f kW", kw));
    }

    private void updateBrakePressure() {
        if (tvTrackBrakePressure == null) {
            return;
        }
        int p = TelemetrySnapshot.getBrakePedalPressure();
        if (p == 0) tvTrackBrakePressure.setVisibility(View.INVISIBLE);
        else  tvTrackBrakePressure.setVisibility(View.VISIBLE);
        tvTrackBrakePressure.setText(String.format(Locale.US, "%d kpa", p));
    }

    private void updateTires() {
        applyTirePressureUnitLabel();
        setTireCorner(
                tvTireFlBar,
                tvTireFlTemp,
                TelemetrySnapshot.getTirePressureFl(),
                TelemetrySnapshot.getTireTempFl());
        setTireCorner(
                tvTireFrBar,
                tvTireFrTemp,
                TelemetrySnapshot.getTirePressureFr(),
                TelemetrySnapshot.getTireTempFr());
        setTireCorner(
                tvTireRlBar,
                tvTireRlTemp,
                TelemetrySnapshot.getTirePressureRl(),
                TelemetrySnapshot.getTireTempRl());
        setTireCorner(
                tvTireRrBar,
                tvTireRrTemp,
                TelemetrySnapshot.getTirePressureRr(),
                TelemetrySnapshot.getTireTempRr());
    }

    /**
     * Telemetri basıncı kPa; köşede yalnızca sayı (birim üstte). Sıcaklık °C tam sayı.
     */
    private void setTireCorner(TextView tvBar, TextView tvTemp, int kpa, int tempC) {
        if (tvBar == null || tvTemp == null) {
            return;
        }
        if (kpa <= 0 && tempC <= 0) {
            tvBar.setText("--");
            tvTemp.setText("--");
            return;
        }
        if (kpa > 0) {
            float display = tirePressurePsi ? (kpa / KPA_PER_PSI) : (kpa / KPA_PER_BAR);
            tvBar.setText(String.format(Locale.US, "%.1f", display));
        } else {
            tvBar.setText("--");
        }
        tvTemp.setText(String.format(Locale.US, "%d", tempC));
    }

    /**
     * Yavaşlama: aşağı yönde 100 ve 50 eşiği geçildiğinde süre başlar; hız ≤ 0 iken biter.
     * Eşiğin üstüne tekrar çıkılırsa o deneme iptal edilir.
     */
    private void updateDecelerationTimes() {
        float speedKmh = TelemetrySnapshot.getSpeedKmh();
        long now = SystemClock.elapsedRealtime();

        if (!Float.isNaN(lastSpeedKmh)) {
            if (lastSpeedKmh > 100f && speedKmh <= 100f) {
                decel100StartMs = now;
            }
            if (lastSpeedKmh > 50f && speedKmh <= 50f) {
                decel50StartMs = now;
            }
        }

        if (speedKmh <= SPEED_IDLE_KMH) {
            if (decel100StartMs > 0) {
                float sec = (now - decel100StartMs) / 1000f;
                decel100StartMs = 0;
                if (tvTime_100_0 != null) {
                    tvTime_100_0.setText(String.format(Locale.US, "%.2f s", sec));
                }
            }
            if (decel50StartMs > 0) {
                float sec = (now - decel50StartMs) / 1000f;
                decel50StartMs = 0;
                if (tvTime_50_0 != null) {
                    tvTime_50_0.setText(String.format(Locale.US, "%.2f s", sec));
                }
            }
        } else {
            if (decel100StartMs > 0 && speedKmh > 100f) {
                decel100StartMs = 0;
            }
            if (decel50StartMs > 0 && speedKmh > 50f) {
                decel50StartMs = 0;
            }
        }

        lastSpeedKmh = speedKmh;
    }

    private void updateSegmentTimes() {
        float speedKmh = TelemetrySnapshot.getSpeedKmh();
        long now = SystemClock.elapsedRealtime();
        TextView[] tvs = {tvTime_0_60, tvTime_0_100, tvTime_60_120, tvTime_80_160};

        if (speedKmh <= SPEED_IDLE_KMH) {
            armedForNextRun = true;
            runStartElapsed = 0;
            markAt60Ms = 0;
            markAt80Ms = 0;
            lastIntegrationTimeMs = 0;
            distanceMetersAccum = 0f;
            return;
        }

        if (armedForNextRun) {
            runStartElapsed = now;
            lastIntegrationTimeMs = now;
            distanceMetersAccum = 0f;
            dist400Done = false;
            dist800Done = false;
            recorded400Sec = null;
            recorded800Sec = null;
            armedForNextRun = false;
            markAt60Ms = 0;
            markAt80Ms = 0;
            for (int i = 0; i < segmentDone.length; i++) {
                segmentDone[i] = false;
                recordedSeconds[i] = null;
                if (tvs[i] != null) {
                    tvs[i].setText("—");
                }
            }
            if (tvTime_0_400m != null) {
                tvTime_0_400m.setText("—");
            }
            if (tvTime_0_800m != null) {
                tvTime_0_800m.setText("—");
            }
            decel50StartMs = 0;
            decel100StartMs = 0;
            if (tvTime_50_0 != null) {
                tvTime_50_0.setText("—");
            }
            if (tvTime_100_0 != null) {
                tvTime_100_0.setText("—");
            }
        }

        if (runStartElapsed <= 0) {
            return;
        }

        if (lastIntegrationTimeMs > 0) {
            float deltaSec = (now - lastIntegrationTimeMs) / 1000f;
            if (deltaSec > 0f) {
                distanceMetersAccum += speedKmh * deltaSec * METERS_PER_KMH_AND_SECOND * (speedKmh >= 80f ? 1.0035f: 1f);
            }
        }
        lastIntegrationTimeMs = now;

        float elapsedFromStart = (now - runStartElapsed) / 1000f;

        if (markAt60Ms == 0 && speedKmh >= 60f) {
            markAt60Ms = now;
        }
        if (markAt80Ms == 0 && speedKmh >= 80f) {
            markAt80Ms = now;
        }

        if (!segmentDone[0] && speedKmh >= 60f) {
            segmentDone[0] = true;
            recordedSeconds[0] = elapsedFromStart;
        }
        if (!segmentDone[1] && speedKmh >= 100f) {
            segmentDone[1] = true;
            recordedSeconds[1] = elapsedFromStart;
        }
        if (!segmentDone[2] && speedKmh >= 120f && markAt60Ms > 0) {
            segmentDone[2] = true;
            recordedSeconds[2] = (now - markAt60Ms) / 1000f;
        }
        if (!segmentDone[3] && speedKmh >= 160f && markAt80Ms > 0) {
            segmentDone[3] = true;
            recordedSeconds[3] = (now - markAt80Ms) / 1000f;
        }

        if (!dist400Done && distanceMetersAccum >= DIST_M_400) {
            dist400Done = true;
            recorded400Sec = elapsedFromStart;
        }
        if (!dist800Done && distanceMetersAccum >= DIST_M_800) {
            dist800Done = true;
            recorded800Sec = elapsedFromStart;
        }

        for (int i = 0; i < recordedSeconds.length; i++) {
            if (recordedSeconds[i] != null && tvs[i] != null) {
                tvs[i].setText(String.format(Locale.US, "%.2f s", recordedSeconds[i]));
            }
        }
        if (recorded400Sec != null && tvTime_0_400m != null) {
            tvTime_0_400m.setText(String.format(Locale.US, "%.2f s", recorded400Sec));
        }
        if (recorded800Sec != null && tvTime_0_800m != null) {
            tvTime_0_800m.setText(String.format(Locale.US, "%.2f s", recorded800Sec));
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        observerRegistered = false;
        try {
            requireContext().getContentResolver().registerContentObserver(
                    TelemetryConstants.TELEMETRY_CONTENT_URI,
                    false,
                    telemetryObserver
            );
            observerRegistered = true;
        } catch (SecurityException e) {
            Log.w("TRACK", "Telemetry provider yok");
        }
        readTelemetryOnce();
        refreshTheme();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (observerRegistered) {
            try {
                requireContext().getContentResolver().unregisterContentObserver(telemetryObserver);
            } catch (Throwable ignored) {
            }
            observerRegistered = false;
        }
    }
}
