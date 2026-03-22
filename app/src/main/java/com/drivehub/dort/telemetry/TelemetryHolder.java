package com.drivehub.dort.telemetry;

/**
 * Son telemetri anlık görüntüsü. EngineSoundManager periyodik günceller;
 * TelemetryProvider query ile okur. Broadcast yerine ContentProvider kullanımı
 * sistem UID'den "non-protected broadcast" uyarısını önler.
 */
public final class TelemetryHolder {
    private static volatile float sRpm;
    private static volatile float sSpeedKmh;
    private static volatile int   sGear;
    private static volatile float sThrottle01;
    private static volatile float sDcPowerKw;
    private static volatile float sRpmMax;
    private static volatile float sMotorMaxPowerKw;

    public static void update(float rpm, float speedKmh, int gear, float throttle01,
                              float dcPowerKw, float rpmMax, float motorMaxPowerKw) {
        try {
            sRpm = rpm;
            sSpeedKmh = speedKmh;
            sGear = gear;
            sThrottle01 = throttle01;
            sDcPowerKw = dcPowerKw;
            sRpmMax = rpmMax;
            sMotorMaxPowerKw = motorMaxPowerKw;
        } catch (Throwable ignored) {
            // Asla üst katmana sıçramasın (servis / ses döngüsü çökmez)
        }
    }

    public static float getRpm() { return sRpm; }
    public static float getSpeedKmh() { return sSpeedKmh; }
    public static int getGear() { return sGear; }
    public static float getThrottle01() { return sThrottle01; }
    public static float getDcPowerKw() { return sDcPowerKw; }
    public static float getRpmMax() { return sRpmMax; }
    public static float getMotorMaxPowerKw() { return sMotorMaxPowerKw; }
}
