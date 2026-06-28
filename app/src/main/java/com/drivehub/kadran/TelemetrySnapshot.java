package com.drivehub.kadran;

import android.content.Context;
import android.database.Cursor;
import android.util.Log;

/**
 * DriveHub Dort telemetrisinin son anlığı — {@link #load} ile doldurulur.
 * Tüm {@link TelemetryConstants} kolonları (varsa cursor'da) okunur.
 */
public final class TelemetrySnapshot {

    private static final Object LOCK = new Object();

    private static volatile boolean valid;

    private static float rpm = -1f;
    private static float speedKmh;
    private static int gear = 1;
    private static float throttle01;
    private static float dcKw;
    private static float rpmMax = 7000f;
    private static float motorMaxKw = 150f;

    private static int tirePressureFl;
    private static int tirePressureFr;
    private static int tirePressureRl;
    private static int tirePressureRr;
    private static int tireTempFl;
    private static int tireTempFr;
    private static int tireTempRl;
    private static int tireTempRr;
    private static float wheelAngleDeg;
    private static int brakePedalPressure;
    private static float accelPortrait;
    private static float accelLateral;
    private static int vehiclePowerPerc = -1;

    private TelemetrySnapshot() {
    }

    /**
     * Dort provider'dan güncel telemetriyi okur ve anlığı günceller.
     *
     * @return false: sorgu hatası veya satır yok
     */
    public static boolean load(Context ctx) {
        if (ctx == null) {
            return false;
        }
        synchronized (LOCK) {
            Cursor c = null;
            try {
                c = ctx.getContentResolver().query(
                        TelemetryConstants.TELEMETRY_CONTENT_URI, null, null, null, null);
                if (c != null && c.moveToFirst()) {
                    applyFromCursor(c);
                    valid = true;
                    return true;
                }
            } catch (Throwable t) {
                Log.w("KADRAN_TEL", "TelemetrySnapshot.load: " + t.getMessage());
            } finally {
                if (c != null) {
                    c.close();
                }
            }
            return false;
        }
    }

    private static void applyFromCursor(Cursor c) {
        rpm = TelemetryCursorCompat.getFloatOrDefault(c, TelemetryConstants.COL_RPM, -1f);
        speedKmh = TelemetryCursorCompat.getFloatOrDefault(c, TelemetryConstants.COL_SPEED_KMH, 0f);
        gear = TelemetryCursorCompat.getIntOrDefault(c, TelemetryConstants.COL_GEAR, 1);
        throttle01 = TelemetryCursorCompat.getFloatOrDefault(c, TelemetryConstants.COL_THROTTLE01, 0f);
        dcKw = TelemetryCursorCompat.getFloatOrDefault(c, TelemetryConstants.COL_DC_POWER_KW, 0f);
        rpmMax = TelemetryCursorCompat.getFloatOrDefault(c, TelemetryConstants.COL_RPM_MAX, 7000f);
        motorMaxKw = TelemetryCursorCompat.getFloatOrDefault(
                c, TelemetryConstants.COL_MOTOR_MAX_POWER_KW, 150f);

        tirePressureFl = TelemetryCursorCompat.getIntOrDefault(c, TelemetryConstants.COL_TIRE_PRESSURE_FL, 0);
        tirePressureFr = TelemetryCursorCompat.getIntOrDefault(c, TelemetryConstants.COL_TIRE_PRESSURE_FR, 0);
        tirePressureRl = TelemetryCursorCompat.getIntOrDefault(c, TelemetryConstants.COL_TIRE_PRESSURE_RL, 0);
        tirePressureRr = TelemetryCursorCompat.getIntOrDefault(c, TelemetryConstants.COL_TIRE_PRESSURE_RR, 0);
        tireTempFl = TelemetryCursorCompat.getIntOrDefault(c, TelemetryConstants.COL_TIRE_TEMP_FL, 0);
        tireTempFr = TelemetryCursorCompat.getIntOrDefault(c, TelemetryConstants.COL_TIRE_TEMP_FR, 0);
        tireTempRl = TelemetryCursorCompat.getIntOrDefault(c, TelemetryConstants.COL_TIRE_TEMP_RL, 0);
        tireTempRr = TelemetryCursorCompat.getIntOrDefault(c, TelemetryConstants.COL_TIRE_TEMP_RR, 0);
        wheelAngleDeg = TelemetryCursorCompat.getFloatOrDefault(c, TelemetryConstants.COL_WHEEL_ANGLE_DEG, 0f);
        brakePedalPressure = TelemetryCursorCompat.getIntOrDefault(
                c, TelemetryConstants.COL_BRAKE_PEDAL_PRESSURE, 0);
        accelPortrait = TelemetryCursorCompat.getFloatOrDefault(c, TelemetryConstants.COL_ACCEL_PORTRAIT, 0f);
        accelLateral = TelemetryCursorCompat.getFloatOrDefault(c, TelemetryConstants.COL_ACCEL_LATERAL, 0f);
        vehiclePowerPerc = TelemetryCursorCompat.getIntOrDefault(
                c, TelemetryConstants.COL_VEHICLE_POWER_PERC, -1);
    }

    /** Simülasyon: sadece kadranın kullandığı temel alanlar. */
    public static void setSim(float simRpm, float simSpeedKmh, float simDcKw, int simGear,
            float simRpmMax, float simMotorMaxKw) {
        synchronized (LOCK) {
            rpm = simRpm;
            speedKmh = simSpeedKmh;
            dcKw = simDcKw;
            gear = simGear;
            rpmMax = simRpmMax;
            motorMaxKw = simMotorMaxKw;
            valid = true;
        }
    }

    public static boolean isValid() {
        return valid;
    }

    public static float getRpm() {
        synchronized (LOCK) {
            return rpm;
        }
    }

    public static float getSpeedKmh() {
        synchronized (LOCK) {
            return speedKmh;
        }
    }

    public static int getGear() {
        synchronized (LOCK) {
            return gear;
        }
    }

    public static float getThrottle01() {
        synchronized (LOCK) {
            return throttle01;
        }
    }

    public static float getDcKw() {
        synchronized (LOCK) {
            return dcKw;
        }
    }

    public static float getRpmMax() {
        synchronized (LOCK) {
            return rpmMax;
        }
    }

    public static float getMotorMaxKw() {
        synchronized (LOCK) {
            return motorMaxKw;
        }
    }

    public static int getTirePressureFl() {
        synchronized (LOCK) {
            return tirePressureFl;
        }
    }

    public static int getTirePressureFr() {
        synchronized (LOCK) {
            return tirePressureFr;
        }
    }

    public static int getTirePressureRl() {
        synchronized (LOCK) {
            return tirePressureRl;
        }
    }

    public static int getTirePressureRr() {
        synchronized (LOCK) {
            return tirePressureRr;
        }
    }

    public static int getTireTempFl() {
        synchronized (LOCK) {
            return tireTempFl;
        }
    }

    public static int getTireTempFr() {
        synchronized (LOCK) {
            return tireTempFr;
        }
    }

    public static int getTireTempRl() {
        synchronized (LOCK) {
            return tireTempRl;
        }
    }

    public static int getTireTempRr() {
        synchronized (LOCK) {
            return tireTempRr;
        }
    }

    public static float getWheelAngleDeg() {
        synchronized (LOCK) {
            return wheelAngleDeg;
        }
    }

    public static int getBrakePedalPressure() {
        synchronized (LOCK) {
            return brakePedalPressure;
        }
    }

    public static float getAccelPortrait() {
        synchronized (LOCK) {
            return accelPortrait;
        }
    }

    public static float getAccelLateral() {
        synchronized (LOCK) {
            return accelLateral;
        }
    }

    public static int getVehiclePowerPerc() {
        synchronized (LOCK) {
            return vehiclePowerPerc;
        }
    }
}
