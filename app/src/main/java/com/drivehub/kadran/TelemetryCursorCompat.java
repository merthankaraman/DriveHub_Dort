package com.drivehub.kadran;

import android.database.Cursor;

/**
 * Eski DriveHub Dort yalnızca ilk 7 telemetri sütununu döndürdüğünde, genişletilmiş
 * sütun adları Cursor'da yoktur ({@link Cursor#getColumnIndex} → -1).
 * Güncel Kadran bu yardımcılarla eksik sütunları çökmeden varsayılanla doldurur.
 */
public final class TelemetryCursorCompat {

    private TelemetryCursorCompat() {
    }

    public static int getColumnIndexSafe(Cursor c, String columnName) {
        if (c == null || columnName == null) {
            return -1;
        }
        return c.getColumnIndex(columnName);
    }

    public static float getFloatOrDefault(Cursor c, String columnName, float defaultValue) {
        int idx = getColumnIndexSafe(c, columnName);
        if (idx < 0) {
            return defaultValue;
        }
        if (c.isNull(idx)) {
            return defaultValue;
        }
        return c.getFloat(idx);
    }

    public static int getIntOrDefault(Cursor c, String columnName, int defaultValue) {
        int idx = getColumnIndexSafe(c, columnName);
        if (idx < 0) {
            return defaultValue;
        }
        if (c.isNull(idx)) {
            return defaultValue;
        }
        return c.getInt(idx);
    }
}
