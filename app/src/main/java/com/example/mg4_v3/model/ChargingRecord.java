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
}
