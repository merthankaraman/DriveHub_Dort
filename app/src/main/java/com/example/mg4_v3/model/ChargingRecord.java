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

    /**
     * Süreyi metin olarak: 60 dakikadan uzunsa "x saat y dakika z saniye",
     * kısaysa sadece "y dakika z saniye".
     */
    public String getDurationFormatted() {
        long totalMs = Math.max(0L, endMs - startMs);
        long totalSec = totalMs / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (totalSec >= 3600) {
            return String.format("%d saat %d dakika %d saniye", h, m, s);
        }
        return String.format("%d dakika %d saniye", m, s);
    }
}
