package com.example.mg4_v3.hardware;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import com.example.mg4_v3.model.DriveMode;
import com.example.mg4_v3.model.RegenLevel;

/**
 * MG4 EH32 — CarPropertyManager (reflection) ile araç kontrolü.
 *
 * Tüm property ID'ler logcat'ten doğrulandı:
 *   PROP_DRIVE_MODE     = 0x2140a17c  (CarAdvancedAssistedDrivingManager)
 *   PROP_REGEN_LEVEL    = 0x2140a191  (CarAdvancedAssistedDrivingManager)
 *   PROP_ONE_PEDAL      = 0x2140a193  (CarAdvancedAssistedDrivingManager)
 *   PROP_STEERING_HEAT  = 0x1540253a  (CarHvacManager) area=0x75
 *   PROP_SEAT_HEAT_L    = 0x15402513  (CarHvacManager) area=0x75
 *   PROP_SEAT_HEAT_R    = 0x15402514  (CarHvacManager) area=0x75
 *
 * Düzeltme: Car.createCar() callback bekleniyor (not connected sorunu çözüldü).
 */
public class MG4Hardware {

    private static final String TAG = "MG4_HW";

    // Sürüş kontrol property'leri (logdan doğrulandı)
    private static final int PROP_DRIVE_MODE    = 0x2140a17c;
    private static final int PROP_REGEN_LEVEL   = 0x2140a191;
    private static final int PROP_ONE_PEDAL     = 0x2140a193;
    private static final int AREA_GLOBAL        = 0x01000000;

    // HVAC property'leri (CarHvacManager logdan doğrulandı)
    private static final int PROP_STEERING_HEAT = 0x1540253a; // 356525370
    private static final int PROP_SEAT_HEAT_L   = 0x15402513; // 356525331 — sol koltuk
    private static final int PROP_SEAT_HEAT_R   = 0x15402514; // 356525332 — sağ koltuk
    private static final int AREA_HVAC          = 0x75;       // 117

    // Araç durum / BMS property'leri (VehicleConditionBinder + VehicleChargingBinder)
    private static final int PROP_SPEED          = 0x11600207; // 291504647 — float m/s (CarSensorManager)
    private static final int PROP_SOC            = 0x21600004; // 560002052 — float % (CarBMSManager)
    private static final int PROP_RANGE          = 0x214099DC; // 557904924 — int km (CarBMSManager)
    private static final int PROP_BATT_VOLT      = 0x21600006; // 560002054 — float V (CarBMSManager)
    private static final int PROP_CHR_AMP_ACT    = 0x21600007; // 560002055 — float A gerçek (CarBMSManager)
    private static final int PROP_CHR_AMP_EXP    = 0x2160000A; // 560002058 — float A beklenen (CarBMSManager)
    private static final int PROP_AC_AMP         = 0x2160006C; // 560002108 — float A AC giriş (CarBMSManager)
    private static final int PROP_AC_VOLT        = 0x2160006D; // 560002109 — float V AC giriş (CarBMSManager)

    // Katman 2 — Binder (yedek, uid.system gerektirir)
    private static final String DESCRIPTOR_VEHICLE =
            "com.saicmotor.sdk.vehiclesettings.IVehicleSettingService";
    private static final String DESCRIPTOR_AC =
            "com.saicmotor.sdk.vehiclesettings.IAirConditionService";
    private static final int TX_SET_DRIVE_MODE         = 151;
    private static final int TX_SET_REGEN_LEVEL        = 180;
    private static final int TX_SET_ONE_PEDAL          = 181;
    private static final int TX_SET_REGEN_BRAKE_SWITCH = 182;
    private static final int TX_SET_STEERING_HEAT      = 52;
    private static final int COUNT = 1;

    // State
    private static Object  sCarPropertyManager = null;
    private static Object  sCarHvacManager     = null;
    private static Object  sCarBmsManager      = null;
    private static Object  sCarSensorManager   = null;
    private static boolean sCarBindAttempted   = false;
    private static IBinder sVehicleBinder      = null;
    private static IBinder sAcBinder           = null;
    private static boolean sInitialized        = false;
    private static Context sContext;

    // -------------------------------------------------------------------------
    // Init / Destroy
    // -------------------------------------------------------------------------

    public static void init(Context context) {
        if (sInitialized) return;
        sInitialized = true;
        sContext = context.getApplicationContext();

        Log.i(TAG, "========================================");
        Log.i(TAG, "=== MG4Hardware.init() ===");
        Log.i(TAG, "  uid=" + android.os.Process.myUid() + " pid=" + android.os.Process.myPid());
        Log.i(TAG, "  sdk=" + android.os.Build.VERSION.SDK_INT + " device=" + android.os.Build.DEVICE);

        // Katman 1: CarPropertyManager — com.android.car'a bind ol
        bindCarService(sContext);

        // Katman 2: Binder (yedek)
        logAvailableVehicleServices();
        sVehicleBinder = getBinderService("vehiclesetting");
        sAcBinder      = getBinderService("aircondition");
        if (sVehicleBinder != null) Log.i(TAG, "  ✓ Katman2: vehiclesetting binder bağlı");
        else Log.w(TAG, "  ✗ Katman2: vehiclesetting null (SELinux — beklenen)");
        Log.i(TAG, "========================================");
    }

    public static boolean isReady() {
        return sCarPropertyManager != null || sVehicleBinder != null;
    }

    public static void destroy() {
        sCarPropertyManager = null;
        sCarHvacManager     = null;
        sCarBmsManager      = null;
        sCarSensorManager   = null;
        sVehicleBinder      = null;
        sAcBinder           = null;
        sInitialized        = false;
        sCarBindAttempted   = false;
        Log.i(TAG, "destroy()");
    }

    // -------------------------------------------------------------------------
    // Katman 1 — CarPropertyManager via Car.createCar (callback ile)
    // -------------------------------------------------------------------------

    private static void bindCarService(Context context) {
        if (sCarBindAttempted) return;
        sCarBindAttempted = true;
        try {
            Class<?> carClass = Class.forName("android.car.Car");
            Log.i(TAG, "  Katman1: android.car.Car sınıfı bulundu ✓");

            // Yöntem A: createCar(Context, Handler) — SDK 28 için en güvenilir
            // Handler null → main thread callback
            java.lang.reflect.Method createCarA = null;
            try {
                createCarA = carClass.getMethod("createCar", Context.class, android.os.Handler.class);
                Log.i(TAG, "  Katman1: createCar(Context, Handler) metodu bulundu");
            } catch (NoSuchMethodException ignored) {}

            // Yöntem B: createCar(Context) — en basit
            java.lang.reflect.Method createCarB = null;
            try {
                createCarB = carClass.getMethod("createCar", Context.class);
                Log.i(TAG, "  Katman1: createCar(Context) metodu bulundu");
            } catch (NoSuchMethodException ignored) {}

            // Yöntem C: createCar(Context, ServiceConnection)
            java.lang.reflect.Method createCarC = null;
            try {
                createCarC = carClass.getMethod("createCar", Context.class, ServiceConnection.class);
                Log.i(TAG, "  Katman1: createCar(Context, ServiceConnection) metodu bulundu");
            } catch (NoSuchMethodException ignored) {}

            Object car = null;

            // Önce B'yi dene (en basit — blocking connect)
            if (createCarB != null) {
                try {
                    car = createCarB.invoke(null, context);
                    if (car != null) Log.i(TAG, "  Katman1: createCar(Context) → başarılı");
                } catch (Exception e) {
                    Log.w(TAG, "  createCar(Context) hata: " + e.getMessage());
                    car = null;
                }
            }

            // B başarısız olduysa A'yı dene
            if (car == null && createCarA != null) {
                try {
                    car = createCarA.invoke(null, context, (Object) null);
                    if (car != null) Log.i(TAG, "  Katman1: createCar(Context, Handler) → başarılı");
                } catch (Exception e) {
                    Log.w(TAG, "  createCar(Context, Handler) hata: " + e.getMessage());
                    car = null;
                }
            }

            // C ile dene (ServiceConnection)
            if (car == null && createCarC != null) {
                ServiceConnection carConnection = new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        Log.i(TAG, "  Katman1: ServiceConnection.onServiceConnected → " + name);
                        tryGetManagersFromCar(carClass);
                    }
                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                        Log.w(TAG, "  Katman1: Car bağlantısı kesildi");
                        sCarPropertyManager = null;
                        sCarHvacManager = null;
                    }
                };
                try {
                    car = createCarC.invoke(null, context, carConnection);
                    if (car != null) Log.i(TAG, "  Katman1: createCar(Context,SC) → callback bekleniyor");
                } catch (Exception e) {
                    Log.w(TAG, "  createCar(Context,SC) hata: " + e.getMessage());
                    car = null;
                }
            }

            if (car == null) {
                Log.e(TAG, "  Katman1: Tüm createCar yöntemleri başarısız");
                return;
            }

            sCar = car;

            // connect() çağır (eğer varsa)
            try {
                java.lang.reflect.Method connectMethod = carClass.getMethod("connect");
                connectMethod.invoke(car);
                Log.i(TAG, "  Katman1: car.connect() çağrıldı");
            } catch (NoSuchMethodException e) {
                Log.i(TAG, "  Katman1: connect() metodu yok (beklenen)");
            } catch (Exception e) {
                Log.w(TAG, "  Katman1: connect() hata: " + e.getMessage());
            }

            // isConnected() kontrol et
            boolean connected = false;
            try {
                java.lang.reflect.Method isConnected = carClass.getMethod("isConnected");
                connected = (Boolean) isConnected.invoke(car);
                Log.i(TAG, "  Katman1: isConnected() → " + connected);
            } catch (Exception e) {
                Log.w(TAG, "  Katman1: isConnected() yok/hata: " + e.getMessage());
            }

            // Bağlıysa hemen manager'ları al
            if (connected) {
                tryGetManagersFromCar(carClass);
            } else {
                // Bağlı değilse kısa bekle sonra tekrar dene (Handler ile)
                Log.i(TAG, "  Katman1: Henüz bağlı değil, 500ms sonra tekrar deneniyor...");
                final Class<?> carClassFinal = carClass;
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    boolean c2 = false;
                    try {
                        java.lang.reflect.Method isConn = carClassFinal.getMethod("isConnected");
                        c2 = (Boolean) isConn.invoke(sCar);
                    } catch (Exception ignored) {}
                    Log.i(TAG, "  Katman1: [retry] isConnected() → " + c2);
                    if (c2) {
                        tryGetManagersFromCar(carClassFinal);
                    } else {
                        // Son deneme: 2 saniye sonra
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            Log.i(TAG, "  Katman1: [retry2] getManagersFromCar deneniyor...");
                            tryGetManagersFromCar(carClassFinal);
                        }, 2000);
                    }
                }, 500);
            }

        } catch (ClassNotFoundException e) {
            Log.e(TAG, "  Katman1: android.car.Car yok: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "  Katman1: bindCarService hata: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static Object sCar = null; // Car instance

    private static void tryGetManagersFromCar(Class<?> carClass) {
        if (sCar == null) {
            Log.e(TAG, "  tryGetManagersFromCar: sCar null!");
            return;
        }
        Log.i(TAG, "  tryGetManagersFromCar çağrıldı");

        // isConnected kontrolü
        try {
            java.lang.reflect.Method isConn = carClass.getMethod("isConnected");
            boolean connected = (Boolean) isConn.invoke(sCar);
            Log.i(TAG, "  isConnected() → " + connected);
            if (!connected) {
                Log.w(TAG, "  Car henüz bağlı değil — manager alınamaz");
                return;
            }
        } catch (Exception e) {
            Log.d(TAG, "  isConnected() kontrol edilemedi: " + e.getMessage());
            // kontrol edilemiyorsa devam et, deneyelim
        }

        try {
            java.lang.reflect.Method getCarManager =
                    carClass.getMethod("getCarManager", String.class);

            // CarPropertyManager
            java.lang.reflect.Field propField = carClass.getField("PROPERTY_SERVICE");
            String propertyService = (String) propField.get(null);
            Log.i(TAG, "  PROPERTY_SERVICE = " + propertyService);
            Object cpm = getCarManager.invoke(sCar, propertyService);
            if (cpm != null) {
                sCarPropertyManager = cpm;
                Log.i(TAG, "  ✓ CarPropertyManager HAZIR: " + cpm.getClass().getName());
            } else {
                Log.e(TAG, "  ✗ CarPropertyManager null (izin yok?)");
            }

            // CarHvacManager
            try {
                java.lang.reflect.Field hvacField = carClass.getField("HVAC_SERVICE");
                String hvacService = (String) hvacField.get(null);
                Log.i(TAG, "  HVAC_SERVICE = " + hvacService);
                Object chm = getCarManager.invoke(sCar, hvacService);
                if (chm != null) {
                    sCarHvacManager = chm;
                    Log.i(TAG, "  ✓ CarHvacManager HAZIR: " + chm.getClass().getName());
                } else {
                    Log.w(TAG, "  ✗ CarHvacManager null");
                }
            } catch (Exception e) {
                Log.w(TAG, "  CarHvacManager alınamadı: " + e.getMessage());
            }

            // CarBMSManager (SAIC özel — "bms" service adı)
            try {
                Object cbm = getCarManager.invoke(sCar, "bms");
                if (cbm != null) {
                    sCarBmsManager = cbm;
                    Log.i(TAG, "  ✓ CarBMSManager HAZIR: " + cbm.getClass().getName());
                } else {
                    Log.w(TAG, "  ✗ CarBMSManager null");
                }
            } catch (Exception e) {
                Log.w(TAG, "  CarBMSManager alınamadı: " + e.getMessage());
            }

            // CarSensorManager
            try {
                java.lang.reflect.Field sensorField = null;
                try { sensorField = carClass.getField("SENSOR_SERVICE"); } catch (Exception ignored) {}
                String sensorService = (sensorField != null)
                        ? (String) sensorField.get(null) : "sensor";
                Log.i(TAG, "  SENSOR_SERVICE = " + sensorService);
                Object csm = getCarManager.invoke(sCar, sensorService);
                if (csm != null) {
                    sCarSensorManager = csm;
                    Log.i(TAG, "  ✓ CarSensorManager HAZIR: " + csm.getClass().getName());
                } else {
                    Log.w(TAG, "  ✗ CarSensorManager null");
                }
            } catch (Exception e) {
                Log.w(TAG, "  CarSensorManager alınamadı: " + e.getMessage());
            }

            if (sCarPropertyManager != null) {
                readAndLogCurrentState();
            } else {
                Log.e(TAG, "  ✗ CarPropertyManager alınamadı — hiçbir şey çalışmayacak");
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            Log.e(TAG, "  tryGetManagersFromCar InvocationTargetException: "
                    + (cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : e.getMessage()));
            if (cause != null) {
                Log.e(TAG, "    cause stacktrace: " + android.util.Log.getStackTraceString(cause));
            }
        } catch (Exception e) {
            Log.e(TAG, "  tryGetManagersFromCar hata: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Setter'lar
    // -------------------------------------------------------------------------

    public static boolean setDriveMode(DriveMode mode) {
        Log.i(TAG, "setDriveMode → " + mode.label + " (" + mode.value + ")");
        if (setIntPropertyCPM(PROP_DRIVE_MODE, AREA_GLOBAL, mode.value)) return true;
        return binderTransact(sVehicleBinder, DESCRIPTOR_VEHICLE, TX_SET_DRIVE_MODE, mode.value);
    }

    public static boolean setRegenLevel(RegenLevel level) {
        Log.i(TAG, "setRegenLevel → " + level.label + " (" + level.value + ")");
        if (level == RegenLevel.OFF) {
            // OFF: regen ana switch'i kapat (hem CPM hem Binder)
            boolean ok = setIntPropertyCPM(PROP_REGEN_LEVEL, AREA_GLOBAL, 0); // önce en düşüğe çek
            binderTransact(sVehicleBinder, DESCRIPTOR_VEHICLE, TX_SET_REGEN_BRAKE_SWITCH, 0);
            Log.i(TAG, "  Regen OFF → brake switch kapatıldı");
            return ok || sVehicleBinder != null;
        }
        // ON seviyeler: önce switch'i aç, sonra seviyeyi yaz
        if (sCarPropertyManager != null) {
            return setIntPropertyCPM(PROP_REGEN_LEVEL, AREA_GLOBAL, level.value);
        }
        binderTransact(sVehicleBinder, DESCRIPTOR_VEHICLE, TX_SET_REGEN_BRAKE_SWITCH, 1);
        return binderTransact(sVehicleBinder, DESCRIPTOR_VEHICLE, TX_SET_REGEN_LEVEL, level.value);
    }

    public static boolean setOnePedal(boolean enabled) {
        Log.i(TAG, "setOnePedal → " + (enabled ? "Açık" : "Kapalı"));
        if (setIntPropertyCPM(PROP_ONE_PEDAL, AREA_GLOBAL, enabled ? 1 : 0)) return true;
        return binderTransact(sVehicleBinder, DESCRIPTOR_VEHICLE, TX_SET_ONE_PEDAL, enabled ? 1 : 0);
    }

    /** Direksiyon ısıtma — aç/kapat (eski API uyumluluğu) */
    public static boolean setSteeringHeat(boolean enabled) {
        Log.i(TAG, "setSteeringHeat → " + (enabled ? "Açık" : "Kapalı"));
        int val = enabled ? 1 : 0;
        if (setIntPropertyHvac(PROP_STEERING_HEAT, AREA_HVAC, val)) return true;
        return binderTransact(sAcBinder, DESCRIPTOR_AC, TX_SET_STEERING_HEAT, val);
    }

    /** Direksiyon ısıtma — seviyeli (0=kapalı, 1/2/3=seviye) */
    public static boolean setSteeringHeatLevel(int level) {
        Log.i(TAG, "setSteeringHeatLevel → " + level);
        if (setIntPropertyHvac(PROP_STEERING_HEAT, AREA_HVAC, level)) return true;
        // Binder yedek: seviyeli değer gönder (araç destekliyorsa)
        return binderTransact(sAcBinder, DESCRIPTOR_AC, TX_SET_STEERING_HEAT, level);
    }

    /** Sol koltuk ısıtma seviyesi (0=kapalı, 1/2/3=seviye) */
    public static boolean setSeatHeatLeft(int level) {
        Log.i(TAG, "setSeatHeatLeft → " + level);
        return setIntPropertyHvac(PROP_SEAT_HEAT_L, AREA_HVAC, level);
    }

    /** Sağ koltuk ısıtma seviyesi (0=kapalı, 1/2/3=seviye) */
    public static boolean setSeatHeatRight(int level) {
        Log.i(TAG, "setSeatHeatRight → " + level);
        return setIntPropertyHvac(PROP_SEAT_HEAT_R, AREA_HVAC, level);
    }

    // -------------------------------------------------------------------------
    // Getter'lar — Sürüş
    // -------------------------------------------------------------------------

    public static int getDriveMode()  { return getIntPropertyCPM(PROP_DRIVE_MODE,  AREA_GLOBAL); }
    public static int getRegenLevel() { return getIntPropertyCPM(PROP_REGEN_LEVEL, AREA_GLOBAL); }
    public static int getOnePedal()   { return getIntPropertyCPM(PROP_ONE_PEDAL,   AREA_GLOBAL); }

    // -------------------------------------------------------------------------
    // Getter'lar — Araç Durum / BMS (float dönerler, hata: Float.NaN)
    // -------------------------------------------------------------------------

    /** Araç hızı — m/s (km/h için × 3.6) */
    public static float getSpeedMs() {
        return getFloatPropertyCPM(PROP_SPEED, AREA_GLOBAL);
    }

    /** SOC — % (0.0–100.0) */
    public static float getSoc() {
        float v = getFloatPropertyBms(PROP_SOC);
        if (Float.isNaN(v)) v = getFloatPropertyCPM(PROP_SOC, AREA_GLOBAL);
        return v;
    }

    /** Kalan menzil — km */
    public static int getRange() {
        int v = getIntPropertyBms(PROP_RANGE);
        if (v < 0) v = getIntPropertyCPM(PROP_RANGE, AREA_GLOBAL);
        return v;
    }

    /** DC batarya voltajı — V */
    public static float getDcVoltage() {
        float v = getFloatPropertyBms(PROP_BATT_VOLT);
        if (Float.isNaN(v)) v = getFloatPropertyCPM(PROP_BATT_VOLT, AREA_GLOBAL);
        return v;
    }

    /** DC şarj akımı gerçek — A */
    public static float getDcCurrentActual() {
        float v = getFloatPropertyBms(PROP_CHR_AMP_ACT);
        if (Float.isNaN(v)) v = getFloatPropertyCPM(PROP_CHR_AMP_ACT, AREA_GLOBAL);
        return v;
    }

    /** DC şarj akımı beklenen — A */
    public static float getDcCurrentExpected() {
        float v = getFloatPropertyBms(PROP_CHR_AMP_EXP);
        if (Float.isNaN(v)) v = getFloatPropertyCPM(PROP_CHR_AMP_EXP, AREA_GLOBAL);
        return v;
    }

    /** AC giriş akımı — A */
    public static float getAcCurrent() {
        float v = getFloatPropertyBms(PROP_AC_AMP);
        if (Float.isNaN(v)) v = getFloatPropertyCPM(PROP_AC_AMP, AREA_GLOBAL);
        return v;
    }

    /** AC giriş voltajı — V */
    public static float getAcVoltage() {
        float v = getFloatPropertyBms(PROP_AC_VOLT);
        if (Float.isNaN(v)) v = getFloatPropertyCPM(PROP_AC_VOLT, AREA_GLOBAL);
        return v;
    }

    // -------------------------------------------------------------------------
    // CarPropertyManager — reflection ile set/get
    // -------------------------------------------------------------------------

    private static boolean setIntPropertyCPM(int propId, int area, int value) {
        if (sCarPropertyManager == null) {
            Log.w(TAG, "  CPM setInt 0x" + Integer.toHexString(propId) + " — CPM hazır değil");
            return false;
        }
        try {
            java.lang.reflect.Method setInt = sCarPropertyManager.getClass()
                    .getMethod("setIntProperty", int.class, int.class, int.class);
            setInt.invoke(sCarPropertyManager, propId, area, value);
            Log.i(TAG, "  CPM setInt 0x" + Integer.toHexString(propId)
                    + " area=0x" + Integer.toHexString(area) + " value=" + value + " ✓");
            return true;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause != null) {
                Log.e(TAG, "  CPM setInt 0x" + Integer.toHexString(propId)
                        + " ITE→" + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                Log.e(TAG, "  CPM setInt stacktrace: " + android.util.Log.getStackTraceString(cause));
            } else {
                Log.e(TAG, "  CPM setInt 0x" + Integer.toHexString(propId) + " ITE (cause null)");
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "  CPM setInt 0x" + Integer.toHexString(propId)
                    + " HATA: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    private static boolean setIntPropertyHvac(int propId, int area, int value) {
        if (sCarHvacManager != null) {
            try {
                java.lang.reflect.Method setInt = sCarHvacManager.getClass()
                        .getMethod("setIntProperty", int.class, int.class, int.class);
                setInt.invoke(sCarHvacManager, propId, area, value);
                Log.i(TAG, "  HVAC setInt 0x" + Integer.toHexString(propId)
                        + " area=0x" + Integer.toHexString(area) + " value=" + value + " ✓");
                return true;
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause != null) {
                    Log.w(TAG, "  HVAC setInt ITE→" + cause.getClass().getSimpleName()
                            + ": " + cause.getMessage());
                } else {
                    Log.w(TAG, "  HVAC setInt ITE (cause null)");
                }
                // HVAC manager başarısız — CPM'e fallback etme, izin hatası aynı olur
                return false;
            } catch (Exception e) {
                Log.w(TAG, "  HVAC setInt hata: " + e.getClass().getSimpleName()
                        + ": " + e.getMessage());
            }
        }
        return setIntPropertyCPM(propId, area, value);
    }

    private static int getIntPropertyCPM(int propId, int area) {
        if (sCarPropertyManager == null) return -1;
        try {
            java.lang.reflect.Method getProperty = sCarPropertyManager.getClass()
                    .getMethod("getProperty", Class.class, int.class, int.class);
            Object cpv = getProperty.invoke(sCarPropertyManager, Integer.class, propId, area);
            if (cpv == null) return -1;
            java.lang.reflect.Method getValue = cpv.getClass().getMethod("getValue");
            int result = (Integer) getValue.invoke(cpv);
            Log.i(TAG, "  CPM getInt 0x" + Integer.toHexString(propId) + " → " + result + " ✓");
            return result;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause != null) {
                Log.e(TAG, "  CPM getInt 0x" + Integer.toHexString(propId)
                        + " ITE→" + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            } else {
                Log.e(TAG, "  CPM getInt 0x" + Integer.toHexString(propId) + " ITE (cause null)");
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "  CPM getInt 0x" + Integer.toHexString(propId)
                    + " HATA: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return -1;
        }
    }

    private static float getFloatPropertyCPM(int propId, int area) {
        if (sCarPropertyManager == null) return Float.NaN;
        try {
            java.lang.reflect.Method getProperty = sCarPropertyManager.getClass()
                    .getMethod("getProperty", Class.class, int.class, int.class);
            Object cpv = getProperty.invoke(sCarPropertyManager, Float.class, propId, area);
            if (cpv == null) return Float.NaN;
            java.lang.reflect.Method getValue = cpv.getClass().getMethod("getValue");
            float result = (Float) getValue.invoke(cpv);
            Log.i(TAG, "  CPM getFloat 0x" + Integer.toHexString(propId) + " → " + result + " ✓");
            return result;
        } catch (Exception e) {
            Log.d(TAG, "  CPM getFloat 0x" + Integer.toHexString(propId)
                    + " HATA: " + e.getClass().getSimpleName());
            return Float.NaN;
        }
    }

    /** CarBMSManager üzerinden float okuma */
    private static float getFloatPropertyBms(int propId) {
        if (sCarBmsManager == null) return Float.NaN;
        try {
            // getGlobalProperty(Float.class, propId) — SAIC API
            java.lang.reflect.Method m = sCarBmsManager.getClass()
                    .getMethod("getGlobalProperty", Class.class, int.class);
            Object result = m.invoke(sCarBmsManager, Float.class, propId);
            if (result == null) return Float.NaN;
            float val = (Float) result;
            Log.i(TAG, "  BMS getFloat 0x" + Integer.toHexString(propId) + " → " + val + " ✓");
            return val;
        } catch (Exception e) {
            Log.d(TAG, "  BMS getFloat 0x" + Integer.toHexString(propId)
                    + " HATA: " + e.getClass().getSimpleName());
            return Float.NaN;
        }
    }

    /** CarBMSManager üzerinden int okuma */
    private static int getIntPropertyBms(int propId) {
        if (sCarBmsManager == null) return -1;
        try {
            java.lang.reflect.Method m = sCarBmsManager.getClass()
                    .getMethod("getGlobalProperty", Class.class, int.class);
            Object result = m.invoke(sCarBmsManager, Integer.class, propId);
            if (result == null) return -1;
            int val = (Integer) result;
            Log.i(TAG, "  BMS getInt 0x" + Integer.toHexString(propId) + " → " + val + " ✓");
            return val;
        } catch (Exception e) {
            Log.d(TAG, "  BMS getInt 0x" + Integer.toHexString(propId)
                    + " HATA: " + e.getClass().getSimpleName());
            return -1;
        }
    }

    // -------------------------------------------------------------------------
    // Tanı
    // -------------------------------------------------------------------------

    private static void readAndLogCurrentState() {
        Log.i(TAG, "--- Mevcut araç durumu ---");
        int dm = getIntPropertyCPM(PROP_DRIVE_MODE, AREA_GLOBAL);
        Log.i(TAG, "  DriveMode  → " + dm
                + (dm >= 0 ? " (" + DriveMode.fromValue(dm).label + ")" : " (okunamadı)"));
        int rg = getIntPropertyCPM(PROP_REGEN_LEVEL, AREA_GLOBAL);
        Log.i(TAG, "  RegenLevel → " + rg
                + (rg >= 0 ? " (" + RegenLevel.fromValue(rg).label + ")" : " (okunamadı)"));
        int op = getIntPropertyCPM(PROP_ONE_PEDAL, AREA_GLOBAL);
        Log.i(TAG, "  OnePedal   → " + op
                + (op >= 0 ? " (" + (op == 1 ? "Açık" : "Kapalı") + ")" : " (okunamadı)"));
        Log.i(TAG, "--------------------------");
    }

    private static void logAvailableVehicleServices() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            java.lang.reflect.Method listServices = sm.getMethod("listServices");
            String[] services = (String[]) listServices.invoke(null);
            if (services == null) { Log.w(TAG, "listServices null"); return; }
            Log.i(TAG, "Servisler toplam: " + services.length);
            Log.i(TAG, "Servisler (vehicle/air/saic/setting/car içerenler):");
            int found = 0;
            for (String s : services) {
                if (s != null && (s.toLowerCase().contains("vehicle")
                        || s.toLowerCase().contains("air")
                        || s.toLowerCase().contains("saic")
                        || s.toLowerCase().contains("setting")
                        || s.toLowerCase().contains("car"))) {
                    Log.i(TAG, "  [servis] " + s);
                    found++;
                }
            }
            if (found == 0) Log.w(TAG, "  (hiç ilgili servis bulunamadı)");
        } catch (Exception e) {
            Log.w(TAG, "listServices hata: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Katman 2 — Binder yardımcıları
    // -------------------------------------------------------------------------

    private static IBinder getBinderService(String name) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            java.lang.reflect.Method m = sm.getMethod("getService", String.class);
            IBinder b = (IBinder) m.invoke(null, name);
            Log.d(TAG, "getService(" + name + ") → " + b);
            return b;
        } catch (Exception e) {
            Log.e(TAG, "getService(" + name + ") hata: " + e.getMessage());
            return null;
        }
    }

    private static boolean binderTransact(IBinder binder, String descriptor, int txId, int value) {
        if (binder == null) {
            Log.w(TAG, "  Binder TX=" + txId + " — binder null");
            return false;
        }
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            data.writeInt(AREA_GLOBAL);
            data.writeInt(COUNT);
            data.writeInt(value);
            data.writeFloatArray(new float[0]);
            data.writeByteArray(new byte[0]);
            binder.transact(txId, data, reply, 0);
            int replySize = reply.dataAvail();
            int status = reply.readInt();
            Log.i(TAG, "  Binder TX=" + txId + " value=" + value
                    + " → status=" + status + " replyBytes=" + replySize
                    + (status == 0 ? " ✓" : " ✗ REDDEDİLDİ"));
            return status == 0;
        } catch (Exception e) {
            Log.e(TAG, "  Binder TX=" + txId + " EXCEPTION: " + e.getMessage());
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
