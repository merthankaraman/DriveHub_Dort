package com.drivehub.kadran;

import android.net.Uri;

/**
 * DriveHub Dort ile telemetri haberleşmesi.
 * Telemetri artık ContentProvider üzerinden (broadcast sistem UID uyarısı veriyordu).
 */
public final class TelemetryConstants {

    /** Dort telemetri provider URI (content://com.drivehub.dort.telemetry/latest) */
    public static final Uri TELEMETRY_CONTENT_URI = Uri.parse("content://com.drivehub.dort.telemetry/latest");

    /** Provider query kolon isimleri (Dort TelemetryProvider ile aynı) */
    public static final String COL_RPM = "rpm";
    public static final String COL_SPEED_KMH = "speedKmh";
    public static final String COL_GEAR = "gear";
    public static final String COL_THROTTLE01 = "throttle01";
    public static final String COL_DC_POWER_KW = "dcPowerKw";
    public static final String COL_RPM_MAX = "rpmMax";
    public static final String COL_MOTOR_MAX_POWER_KW = "motorMaxPowerKw";

    /**
     * Genişletilmiş şasi sütunları — eski Dort APK'da yoktur; okurken
     * {@link TelemetryCursorCompat#getFloatOrDefault} / {@code getIntOrDefault} kullanın.
     */
    public static final String COL_TIRE_PRESSURE_FL = "tirePressureFl";
    public static final String COL_TIRE_PRESSURE_FR = "tirePressureFr";
    public static final String COL_TIRE_PRESSURE_RL = "tirePressureRl";
    public static final String COL_TIRE_PRESSURE_RR = "tirePressureRr";
    public static final String COL_TIRE_TEMP_FL = "tireTempFl";
    public static final String COL_TIRE_TEMP_FR = "tireTempFr";
    public static final String COL_TIRE_TEMP_RL = "tireTempRl";
    public static final String COL_TIRE_TEMP_RR = "tireTempRr";
    public static final String COL_WHEEL_ANGLE_DEG = "wheelAngleDeg";
    public static final String COL_BRAKE_PEDAL_PRESSURE = "brakePedalPressure";
    public static final String COL_ACCEL_PORTRAIT = "accelPortrait";
    public static final String COL_ACCEL_LATERAL = "accelLateral";
    public static final String COL_VEHICLE_POWER_PERC = "vehiclePowerPerc";

    private TelemetryConstants() {
        // no-op
    }
}

