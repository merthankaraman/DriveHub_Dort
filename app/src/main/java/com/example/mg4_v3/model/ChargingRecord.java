package com.example.mg4_v3.model;

/**
 * Tek bir şarj seansı: başlangıç/bitiş zamanı ve o seanstaki AC/DC kWh.
 */
public class ChargingRecord {
    public final long startMs;
    public final long endMs;
    public final float acKwh;
    public final float dcKwh;

    public ChargingRecord(long startMs, long endMs, float acKwh, float dcKwh) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.acKwh = acKwh;
        this.dcKwh = dcKwh;
    }

    /** Süre — saat cinsinden (bitiş - başlangıç) */
    public float getDurationHours() {
        return Math.max(0f, (endMs - startMs) / 3_600_000f);
    }

    /** Süre parçaları: [saat, dakika, saniye]. getString(duration_format_hms/min_sec) ile kullan. */
    public int[] getDurationParts() {
        long totalMs = Math.max(0L, endMs - startMs);
        long totalSec = totalMs / 1000;
        int h = (int) (totalSec / 3600);
        int m = (int) ((totalSec % 3600) / 60);
        int s = (int) (totalSec % 60);
        return new int[] { h, m, s };
    }

    /**
     * Süreyi metin olarak (varsayılan Türkçe). Dil için ChargingHistoryActivity içinde getString ile format kullan.
     */
    public String getDurationFormatted() {
        int[] p = getDurationParts();
        int h = p[0], m = p[1], s = p[2];
        if (h > 0) {
            return String.format("%d saat %d dakika %d saniye", h, m, s);
        }
        return String.format("%d dakika %d saniye", m, s);
    }
}
