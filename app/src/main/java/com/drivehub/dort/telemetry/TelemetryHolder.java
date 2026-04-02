package com.drivehub.dort.telemetry;

/**
 * Son telemetri anlık görüntüsü. EngineSoundManager periyodik günceller;
 * TelemetryProvider query ile okur. Broadcast yerine ContentProvider kullanımı
 * sistem UID'den "non-protected broadcast" uyarısını önler.
 * <p>
 * Yeni ölçümler {@link TelemetryProvider} içinde her zaman <strong>sona eklenen</strong>
 * sütunlarla taşınır; eski DriveHub sürümleri yalnızca ilk 7 sütunu döndürebilir.
 */
public final class TelemetryHolder {
    private static volatile float sRpm;
    private static volatile float sSpeedKmh;
    private static volatile int sGear;
    private static volatile float sThrottle01;
    private static volatile float sDcPowerKw;
    private static volatile float sRpmMax;
    private static volatile float sMotorMaxPowerKw;

    private static volatile int sTirePressureFl = -1;
    private static volatile int sTirePressureFr = -1;
    private static volatile int sTirePressureRl = -1;
    private static volatile int sTirePressureRr = -1;
    private static volatile int sTireTempFl = -1;
    private static volatile int sTireTempFr = -1;
    private static volatile int sTireTempRl = -1;
    private static volatile int sTireTempRr = -1;
    private static volatile float sWheelAngleDeg = Float.NaN;
    private static volatile int sBrakePedalPressure = -1;
    private static volatile float sAccelPortrait = Float.NaN;
    private static volatile float sAccelLateral = Float.NaN;
    private static volatile int sVehiclePowerPerc = -1;

    /**
     * Tam güncelleme. Şasi değerleri MG4Hardware global önbellekten gelir;
     * geçersiz int için -1, float için {@link Float#NaN} (Provider ile aynı anlam).
     */
    public static void update(float rpm, float speedKmh, int gear, float throttle01,
                              float dcPowerKw, float rpmMax, float motorMaxPowerKw,
                              int tirePressureFl, int tirePressureFr, int tirePressureRl, int tirePressureRr,
                              int tireTempFl, int tireTempFr, int tireTempRl, int tireTempRr,
                              float wheelAngleDeg,
                              int brakePedalPressure,
                              float accelPortrait, float accelLateral,
                              int vehiclePowerPerc) {
        try {
            sRpm = rpm;
            sSpeedKmh = speedKmh;
            sGear = gear;
            sThrottle01 = throttle01;
            sDcPowerKw = dcPowerKw;
            sRpmMax = rpmMax;
            sMotorMaxPowerKw = motorMaxPowerKw;
            sTirePressureFl = tirePressureFl;
            sTirePressureFr = tirePressureFr;
            sTirePressureRl = tirePressureRl;
            sTirePressureRr = tirePressureRr;
            sTireTempFl = tireTempFl;
            sTireTempFr = tireTempFr;
            sTireTempRl = tireTempRl;
            sTireTempRr = tireTempRr;
            sWheelAngleDeg = wheelAngleDeg;
            sBrakePedalPressure = brakePedalPressure;
            sAccelPortrait = accelPortrait;
            sAccelLateral = accelLateral;
            sVehiclePowerPerc = vehiclePowerPerc;
        } catch (Throwable ignored) {
        }
    }

    public static float getRpm() { return sRpm; }
    public static float getSpeedKmh() { return sSpeedKmh; }
    public static int getGear() { return sGear; }
    public static float getThrottle01() { return sThrottle01; }
    public static float getDcPowerKw() { return sDcPowerKw; }
    public static float getRpmMax() { return sRpmMax; }
    public static float getMotorMaxPowerKw() { return sMotorMaxPowerKw; }

    public static int getTirePressureFl() { return sTirePressureFl; }
    public static int getTirePressureFr() { return sTirePressureFr; }
    public static int getTirePressureRl() { return sTirePressureRl; }
    public static int getTirePressureRr() { return sTirePressureRr; }
    public static int getTireTempFl() { return sTireTempFl; }
    public static int getTireTempFr() { return sTireTempFr; }
    public static int getTireTempRl() { return sTireTempRl; }
    public static int getTireTempRr() { return sTireTempRr; }
    public static float getWheelAngleDeg() { return sWheelAngleDeg; }
    public static int getBrakePedalPressure() { return sBrakePedalPressure; }
    public static float getAccelPortrait() { return sAccelPortrait; }
    public static float getAccelLateral() { return sAccelLateral; }
    public static int getVehiclePowerPerc() { return sVehiclePowerPerc; }
}
