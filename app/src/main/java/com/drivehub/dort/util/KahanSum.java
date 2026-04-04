package com.drivehub.dort.util;

/**
 * Neumann–Kahan telafili toplama: çok sayıda küçük terim (ör. periyodik kWh dilimleri) eklenirken
 * basit {@code sum += x} ile oluşan yuvarlama kaybını azaltır. Tüm iç durum {@code double}.
 */
public final class KahanSum {

    private double sum;
    private double c;

    public synchronized void add(double input) {
        double y = input - c;
        double t = sum + y;
        c = (t - sum) - y;
        sum = t;
    }

    public synchronized double get() {
        return sum;
    }

    /** Sayaç ve telafi terimini sıfırlar. */
    public synchronized void reset() {
        sum = 0.0;
        c = 0.0;
    }

    /**
     * Kalıcı depo veya dış kaynaktan gelen toplamı yükler; telafiyi sıfırlar.
     * Eski tek seferlik float kayıtlarından sonra yeni birikim temiz başlar.
     */
    public synchronized void setTotal(double total) {
        sum = total;
        c = 0.0;
    }
}
