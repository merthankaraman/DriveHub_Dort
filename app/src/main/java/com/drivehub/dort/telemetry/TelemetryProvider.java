package com.drivehub.dort.telemetry;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Kadran uygulaması (com.drivehub.kadran) ve diğer istemciler için telemetri sağlar.
 * content://com.drivehub.dort.telemetry/latest ile query atılır; broadcast yerine
 * kullanıldığı için sistem UID'den "non-protected broadcast" uyarısı oluşmaz.
 * <p>
 * <b>Uyumluluk:</b> İlk 7 sütun (rpm … motorMaxPowerKw) sabit; ek alanlar
 * <strong>sona eklenir</strong>. Eski DriveHub APK sadece 7 sütun döndürebilir — güncel
 * Kadran genişletilmiş sütunları okurken {@code getColumnIndex(name) == -1} kontrolü
 * yapmalıdır (DriveHub_Kadran içinde TelemetryCursorCompat).
 * <p>
 * Açılışta process ölmesin diye tüm yüzeyler try/catch ile korunur; hata olursa
 * boş/geçerli bir Cursor döner (Kadran tarafı null/0 satırı tolere etmeli).
 */
public class TelemetryProvider extends ContentProvider {

    private static final String TAG = "MG4_TEL_PROVIDER";

    public static final String AUTHORITY = "com.drivehub.dort.telemetry";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/latest");

    public static final String COL_RPM = "rpm";
    public static final String COL_SPEED_KMH = "speedKmh";
    public static final String COL_GEAR = "gear";
    public static final String COL_THROTTLE01 = "throttle01";
    public static final String COL_DC_POWER_KW = "dcPowerKw";
    public static final String COL_RPM_MAX = "rpmMax";
    public static final String COL_MOTOR_MAX_POWER_KW = "motorMaxPowerKw";

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

    private static final String[] COLUMNS = {
            COL_RPM, COL_SPEED_KMH, COL_GEAR, COL_THROTTLE01,
            COL_DC_POWER_KW, COL_RPM_MAX, COL_MOTOR_MAX_POWER_KW,
            COL_TIRE_PRESSURE_FL, COL_TIRE_PRESSURE_FR, COL_TIRE_PRESSURE_RL, COL_TIRE_PRESSURE_RR,
            COL_TIRE_TEMP_FL, COL_TIRE_TEMP_FR, COL_TIRE_TEMP_RL, COL_TIRE_TEMP_RR,
            COL_WHEEL_ANGLE_DEG, COL_BRAKE_PEDAL_PRESSURE,
            COL_ACCEL_PORTRAIT, COL_ACCEL_LATERAL, COL_VEHICLE_POWER_PERC
    };

    @Override
    public boolean onCreate() {
        try {
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "onCreate: " + t.getMessage(), t);
            return true;
        }
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                       @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        try {
            if (!"latest".equals(uri.getLastPathSegment())) {
                return null;
            }
            MatrixCursor c = new MatrixCursor(COLUMNS, 1);
            c.addRow(new Object[]{
                    TelemetryHolder.getRpm(),
                    TelemetryHolder.getSpeedKmh(),
                    TelemetryHolder.getGear(),
                    TelemetryHolder.getThrottle01(),
                    TelemetryHolder.getDcPowerKw(),
                    TelemetryHolder.getRpmMax(),
                    TelemetryHolder.getMotorMaxPowerKw(),
                    TelemetryHolder.getTirePressureFl(),
                    TelemetryHolder.getTirePressureFr(),
                    TelemetryHolder.getTirePressureRl(),
                    TelemetryHolder.getTirePressureRr(),
                    TelemetryHolder.getTireTempFl(),
                    TelemetryHolder.getTireTempFr(),
                    TelemetryHolder.getTireTempRl(),
                    TelemetryHolder.getTireTempRr(),
                    TelemetryHolder.getWheelAngleDeg(),
                    TelemetryHolder.getBrakePedalPressure(),
                    TelemetryHolder.getAccelPortrait(),
                    TelemetryHolder.getAccelLateral(),
                    TelemetryHolder.getVehiclePowerPerc()
            });
            return c;
        } catch (Throwable t) {
            Log.e(TAG, "query failed: " + t.getMessage(), t);
            return emptyLatestCursor();
        }
    }

    /** Hata durumunda bile şema uyumlu boş satır (veya sütun) döndürür. */
    private static MatrixCursor emptyLatestCursor() {
        try {
            MatrixCursor c = new MatrixCursor(COLUMNS, 1);
            c.addRow(new Object[]{
                    0f, 0f, 0, 0f, 0f, 0f, 0f,
                    -1, -1, -1, -1,
                    -1, -1, -1, -1,
                    Float.NaN, -1, Float.NaN, Float.NaN, -1
            });
            return c;
        } catch (Throwable t) {
            return new MatrixCursor(COLUMNS);
        }
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        try {
            return "vnd.android.cursor.item/vnd.drivehub.dort.telemetry";
        } catch (Throwable t) {
            Log.e(TAG, "getType failed: " + t.getMessage(), t);
            return null;
        }
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }
}
