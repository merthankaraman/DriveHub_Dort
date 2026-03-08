package com.drivehub.dort.model;

/**
 * Tek bir sürüş oturumu: başlangıç/bitiş zamanı, yol, enerji ve özet istatistikler.
 */
public class DrivingRecord {
    public final long startMs;
    public final long endMs;
    public final float distanceKm;
    public final float energyKwh;
    public final float avgSpeedKmh;
    public final float avgKwhPer100km;

    public DrivingRecord(long startMs,
                         long endMs,
                         float distanceKm,
                         float energyKwh,
                         float avgSpeedKmh,
                         float avgKwhPer100km) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.distanceKm = distanceKm;
        this.energyKwh = energyKwh;
        this.avgSpeedKmh = avgSpeedKmh;
        this.avgKwhPer100km = avgKwhPer100km;
    }

    /** Süre — saat cinsinden (bitiş - başlangıç) */
    public float getDurationHours() {
        return Math.max(0f, (endMs - startMs) / 3_600_000f);
    }

    /** Süre parçaları: [saat, dakika, saniye]. */
    public int[] getDurationParts() {
        long totalMs = Math.max(0L, endMs - startMs);
        long totalSec = totalMs / 1000;
        int h = (int) (totalSec / 3600);
        int m = (int) ((totalSec % 3600) / 60);
        int s = (int) (totalSec % 60);
        return new int[]{h, m, s};
    }
}

