package com.drivehub.dort.telemetry;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Kadran uygulaması (com.drivehub.kadran) için telemetri sağlar.
 * content://com.drivehub.dort.telemetry/latest ile query atılır; broadcast yerine
 * kullanıldığı için sistem UID'den "non-protected broadcast" uyarısı oluşmaz.
 */
public class TelemetryProvider extends ContentProvider {

    public static final String AUTHORITY = "com.drivehub.dort.telemetry";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/latest");

    public static final String COL_RPM = "rpm";
    public static final String COL_SPEED_KMH = "speedKmh";
    public static final String COL_GEAR = "gear";
    public static final String COL_THROTTLE01 = "throttle01";
    public static final String COL_DC_POWER_KW = "dcPowerKw";
    public static final String COL_RPM_MAX = "rpmMax";
    public static final String COL_MOTOR_MAX_POWER_KW = "motorMaxPowerKw";

    private static final String[] COLUMNS = {
            COL_RPM, COL_SPEED_KMH, COL_GEAR, COL_THROTTLE01,
            COL_DC_POWER_KW, COL_RPM_MAX, COL_MOTOR_MAX_POWER_KW
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                       @Nullable String[] selectionArgs, @Nullable String sortOrder) {
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
                TelemetryHolder.getMotorMaxPowerKw()
        });
        return c;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return "vnd.android.cursor.item/vnd.drivehub.dort.telemetry";
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
