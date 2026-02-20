package com.example.mg4_v3.hardware;

import android.content.ComponentName;
import android.content.Context;
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
    private static final int PROP_DRIVE_MODE    = 0x2140a17c; //557883772
    private static final int PROP_REGEN_LEVEL   = 0x2140a191; //557883793
    private static final int PROP_ONE_PEDAL     = 0x2140a193; //557883795

    private static final int AREA_GLOBAL        = 0x01000000;

    // HVAC property'leri (CarHvacManager logdan doğrulandı)
    // Service'in callback'inde kullanabilmesi için public
    public  static final int PROP_STEERING_HEAT_PUB = 0x1540253a; // 356525370
    public  static final int PROP_SEAT_HEAT_L_PUB   = 0x15402513; // 356525331 — sol koltuk
    public  static final int PROP_SEAT_HEAT_R_PUB   = 0x15402514; // 356525332 — sağ koltuk
    private static final int PROP_STEERING_HEAT = PROP_STEERING_HEAT_PUB;
    private static final int PROP_SEAT_HEAT_L   = PROP_SEAT_HEAT_L_PUB;
    private static final int PROP_SEAT_HEAT_R   = PROP_SEAT_HEAT_R_PUB;
    private static final int AREA_HVAC          = 0x75;       // 117

    // Araç durum / BMS property'leri (VehicleConditionBinder + VehicleChargingBinder)
    private static final int PROP_SPEED          = 0x11600207; // 291504647 — float km/h (CarSensorManager)
    private static final int PROP_SOC            = 0x21600004; // 560002052 — float % (CarBMSManager)
    private static final int PROP_RANGE          = 0x214099DC; // 557904924 — int km (CarBMSManager)
    private static final int PROP_BATT_VOLT      = 0x21600006; // 560002054 — float V (CarBMSManager)
    private static final int PROP_CHR_AMP_ACT    = 0x21600007; // 560002055 — float A gerçek (CarBMSManager)
    private static final int PROP_CHR_AMP_EXP    = 0x2160000A; // 560002058 — float A beklenen (CarBMSManager)
    private static final int PROP_AC_AMP         = 0x2160006C; // 560002108 — float A AC giriş (CarBMSManager)
    private static final int PROP_AC_VOLT        = 0x2160006D; // 560002109 — float V AC giriş (CarBMSManager)
    private static final int PROP_CHG_STATUS     = 557904905;  // 0x2140F409 — şarj durumu (0=şarjda değil)

    // Katman 2 — Binder (yedek, uid.system gerektirir)
    private static final String DESCRIPTOR_VEHICLE =
            "com.saicmotor.sdk.vehiclesettings.IVehicleSettingService";
    private static final int TX_SET_DRIVE_MODE         = 151;
    private static final int TX_SET_ONE_PEDAL          = 181;
    private static final int TX_SET_REGEN_BRAKE_SWITCH = 159;
    private static final int COUNT = 1;

    /** HVAC property değişikliğini dinlemek isteyen servis buraya register olur. */
    public interface HvacListener {
        void onHvacPropertyChanged(int propId, int value);
    }

    private static volatile HvacListener sHvacListener = null;

    public static void setHvacListener(HvacListener listener) {
        sHvacListener = listener;
    }

    // BMS cache — CarBMSManager onChangeEvent callback'ten gelen son değerler
    // key=propId, value=son bilinen değer (Float veya Integer olarak Object)
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Object> sBmsCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    // Enerji birikimi — UI kapalı olsa bile BMS callback içinde güncellenir
    private static volatile float sAcEnergyKwh      = 0f;
    private static volatile float sDcEnergyKwh      = 0f;
    private static volatile long  sLastBmsEventMs   = 0L;
    private static volatile long  sChargingElapsedMs = 0L;

    // State
    private static Object  sCarPropertyManager = null;
    private static Object  sCarHvacManager     = null;
    private static boolean sCarBindAttempted   = false;
    private static IBinder sVehicleBinder      = null;
    private static boolean sInitialized        = false;

    // -------------------------------------------------------------------------
    // Init / Destroy
    // -------------------------------------------------------------------------

    public static void init(Context context) {
        if (sInitialized) return;
        sInitialized = true;
        Context appContext = context.getApplicationContext();

        Log.i(TAG, "========================================");
        Log.i(TAG, "=== MG4Hardware.init() ===");
        Log.i(TAG, "  uid=" + android.os.Process.myUid() + " pid=" + android.os.Process.myPid());
        Log.i(TAG, "  sdk=" + android.os.Build.VERSION.SDK_INT + " device=" + android.os.Build.DEVICE);

        // Katman 1: CarPropertyManager — com.android.car'a bind ol
        bindCarService(appContext);

        // Katman 2: Binder (yedek)
        logAvailableVehicleServices();
        sVehicleBinder = getBinderService("vehiclesetting");
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
        sVehicleBinder      = null;
        sBmsCache.clear();
        sAcEnergyKwh       = 0f;
        sDcEnergyKwh       = 0f;
        sLastBmsEventMs    = 0L;
        sChargingElapsedMs = 0L;
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
                    car = createCarA.invoke(null, context, (android.os.Handler) null);
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
                Object chm = getCarManager.invoke(sCar, hvacService);
                if (chm != null) {
                    sCarHvacManager = chm;
                    Log.i(TAG, "  ✓ CarHvacManager HAZIR: " + chm.getClass().getName());
                    registerHvacCallback(chm);
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
                    Log.i(TAG, "  ✓ CarBMSManager HAZIR: " + cbm.getClass().getName());
                    registerBmsCallback(cbm);
                } else {
                    Log.w(TAG, "  ✗ CarBMSManager null");
                }
            } catch (Exception e) {
                Log.w(TAG, "  CarBMSManager alınamadı: " + e.getMessage());
            }

            if (sCarPropertyManager == null) {
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

        binderTransact(sVehicleBinder, DESCRIPTOR_VEHICLE, TX_SET_REGEN_BRAKE_SWITCH, 1);
        return setIntPropertyCPM(PROP_REGEN_LEVEL, AREA_GLOBAL, level.value);
    }

    public static boolean setOnePedal(boolean enabled) {
        Log.i(TAG, "setOnePedal → " + (enabled ? "Açık" : "Kapalı"));
        if (setIntPropertyCPM(PROP_ONE_PEDAL, AREA_GLOBAL, enabled ? 1 : 0)) return true;
        return binderTransact(sVehicleBinder, DESCRIPTOR_VEHICLE, TX_SET_ONE_PEDAL, enabled ? 1 : 0);
    }

    /** Direksiyon ısıtma — aç/kapat (0=kapat, 1=aç) */
    public static boolean setSteeringHeat(boolean targetOn) {
        // 1. Önce arabadaki mevcut durumu oku
        int currentStatus = getIntPropertyHvac(PROP_STEERING_HEAT, AREA_HVAC);

        // currentStatus: 0 ise Kapalı, 1 veya daha büyükse Açık
        boolean isActuallyOn = (currentStatus > 0);

        Log.i(TAG, "Direksiyon Isıtma Durumu: " + currentStatus + " | Hedef: " + targetOn);

        // 2. Eğer araba zaten istediğin durumdaysa hiçbir şey yapma
        if (isActuallyOn == targetOn) {
            Log.i(TAG, "Zaten hedef durumda, komut gönderilmedi.");
            return true;
        }

        // 3. Durum farklıysa "1" göndererek toggle yap (durumu değiştir)
        Log.i(TAG, "Durum değişiyor, toggle komutu gönderiliyor...");
        return setIntPropertyHvac(PROP_STEERING_HEAT, AREA_HVAC, 1);
    }

    /** Sol koltuk ısıtma seviyesi (0=kapalı, 1/2/3=seviye) */
    public static boolean setSeatHeatLeft(int level) {
        Log.i(TAG, "setSeatHeatLeft → " + level);
        return setHvacLevelWithToggle(PROP_SEAT_HEAT_L, AREA_HVAC, level);
    }

    /** Sağ koltuk ısıtma seviyesi (0=kapalı, 1/2/3=seviye) */
    public static boolean setSeatHeatRight(int level) {
        Log.i(TAG, "setSeatHeatRight → " + level);
        return setHvacLevelWithToggle(PROP_SEAT_HEAT_R, AREA_HVAC, level);
    }

    /**
     * HVAC (Koltuk/Direksiyon) seviyesini hedef değere getirir.
     * MG4 Döngüsü: 0 (Off) -> 3 (High) -> 2 (Mid) -> 1 (Low) -> 0
     */
    public static boolean setHvacLevelWithToggle(int propId, int area, int targetLevel) {
        long startTime = System.currentTimeMillis();
        long timeoutMs = 7000; // Döngü yavaşladığı için süreyi biraz esnettik
        long lastStepTime = 0;
        long stepInterval = 500; // Tıklar arası 500ms (MG4 ECU'su için en güvenli aralık)

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            int current = getIntPropertyHvac(propId, area);

            // 1. Hedefe ulaşıldı mı?
            if (current == targetLevel) {
                Log.i(TAG, "HVAC Hedefe ulaşıldı: " + targetLevel);
                return true;
            }

            // 2. Tık gönderme zamanı geldi mi? (500ms bekleme)
            long now = System.currentTimeMillis();
            if (now - lastStepTime >= stepInterval) {
                Log.i(TAG, "HVAC Tık gönderiliyor... Mevcut: " + current);
                setIntPropertyHvac(propId, area, 1);
                lastStepTime = now;

                // Tık gönderdikten sonra arabanın beynine 200ms mola verelim
                try { Thread.sleep(200); } catch (Exception ignored) {}
                continue;
            }

            // 3. KRİTİK: Okuma sıklığını azaltıyoruz (Poll Rate)
            // Saniyede 4 kez sormak yeterli. İşlemciyi ve araba hattını yormaz.
            try { Thread.sleep(250); } catch (Exception ignored) {}
        }

        Log.e(TAG, "HVAC Zaman aşımı! prop=" + propId);
        return false;
    }
    /** HVAC property mevcut değerini oku */
    public static int getIntPropertyHvac(int propId, int area) {
        if (sCarHvacManager == null) return -1;
        try {
            java.lang.reflect.Method getInt = sCarHvacManager.getClass()
                    .getMethod("getIntProperty", int.class, int.class);
            Object result = getInt.invoke(sCarHvacManager, propId, area);
            if (result == null) return -1;
            int val = (Integer) result;
            Log.d(TAG, "  HVAC getIntProperty 0x" + Integer.toHexString(propId)
                    + " area=0x" + Integer.toHexString(area) + " → " + val);
            return val;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            Log.w(TAG, "  HVAC getIntProperty ITE: "
                    + (cause != null ? cause.getMessage() : "null"));
            return -1;
        } catch (Exception e) {
            Log.w(TAG, "  HVAC getIntProperty hata: " + e.getClass().getSimpleName());
            return -1;
        }
    }

    // -------------------------------------------------------------------------
    // Getter'lar — Sürüş
    // -------------------------------------------------------------------------

    public static int getDriveMode()  { return getIntPropertyCPM(PROP_DRIVE_MODE,  AREA_GLOBAL); }
    public static int getRegenLevel() { return getIntPropertyCPM(PROP_REGEN_LEVEL, AREA_GLOBAL); }
    public static int getOnePedal()   { return getIntPropertyCPM(PROP_ONE_PEDAL,   AREA_GLOBAL); }

    // -------------------------------------------------------------------------
    // Getter'lar — HVAC (dışarıdan sorgulanabilir, callback yanında yedek okuma)
    // -------------------------------------------------------------------------

    /** Direksiyon ısıtma mevcut durumu: 0=Kapalı, >0=Açık, -1=okunamadı */
    @SuppressWarnings("unused")
    public static int getHvacSteer() { return getIntPropertyHvac(PROP_STEERING_HEAT, AREA_HVAC); }

    /** Sol koltuk ısıtma mevcut seviyesi: 0=Kapalı, 1/2/3, -1=okunamadı */
    @SuppressWarnings("unused")
    public static int getHvacSeatL() { return getIntPropertyHvac(PROP_SEAT_HEAT_L,   AREA_HVAC); }

    /** Sağ koltuk ısıtma mevcut seviyesi: 0=Kapalı, 1/2/3, -1=okunamadı */
    @SuppressWarnings("unused")
    public static int getHvacSeatR() { return getIntPropertyHvac(PROP_SEAT_HEAT_R,   AREA_HVAC); }

    // -------------------------------------------------------------------------
    // Getter'lar — Araç Durum / BMS (float dönerler, hata: Float.NaN)
    // -------------------------------------------------------------------------

    /** Araç hızı — km/h (araç doğrudan km/h gönderiyor, dönüşüm gereksiz) */
    public static float getSpeedKmh() {
        return getFloatPropertyCPM(PROP_SPEED, AREA_GLOBAL);
    }

    /** SOC — % (0.0–100.0). CPM'den oku, yoksa BMS cache'e bak. */
    public static float getSoc() {
        float v = getFloatPropertyCPM(PROP_SOC, AREA_GLOBAL);
        if (Float.isNaN(v)) v = bmsFloat(PROP_SOC);
        return v;
    }

    /** Kalan menzil — km. CPM'den oku, yoksa BMS cache'e bak. */
    public static int getRange() {
        int v = getIntPropertyCPM(PROP_RANGE, AREA_GLOBAL);
        if (v < 0) {
            Object cached = sBmsCache.get(PROP_RANGE);
            if (cached instanceof Number) v = ((Number) cached).intValue();
        }
        return v;
    }

    /** DC batarya voltajı — V */
    public static float getDcVoltage() {
        float v = getFloatPropertyCPM(PROP_BATT_VOLT, AREA_GLOBAL);
        if (Float.isNaN(v)) v = bmsFloat(PROP_BATT_VOLT);
        return v;
    }

    /** DC şarj akımı gerçek — A */
    public static float getDcCurrentActual() {
        float v = getFloatPropertyCPM(PROP_CHR_AMP_ACT, AREA_GLOBAL);
        if (Float.isNaN(v)) v = bmsFloat(PROP_CHR_AMP_ACT);
        return v;
    }

    /** DC şarj akımı beklenen — A */
    public static float getDcCurrentExpected() {
        float v = getFloatPropertyCPM(PROP_CHR_AMP_EXP, AREA_GLOBAL);
        if (Float.isNaN(v)) v = bmsFloat(PROP_CHR_AMP_EXP);
        return v;
    }

    /** AC giriş akımı — A */
    public static float getAcCurrent() {
        float v = getFloatPropertyCPM(PROP_AC_AMP, AREA_GLOBAL);
        if (Float.isNaN(v)) v = bmsFloat(PROP_AC_AMP);
        return v;
    }

    /** AC giriş voltajı — V */
    public static float getAcVoltage() {
        float v = getFloatPropertyCPM(PROP_AC_VOLT, AREA_GLOBAL);
        if (Float.isNaN(v)) v = bmsFloat(PROP_AC_VOLT);
        return v;
    }

    /** BMS cache'ten float oku — callback gelmemişse NaN döner */
    private static float bmsFloat(int propId) {
        Object val = sBmsCache.get(propId);
        if (val instanceof Number) return ((Number) val).floatValue();
        return Float.NaN;
    }

    /** Araç şarjda mı? BMS cache'teki PROP_CHG_STATUS'a bakar. */
    private static boolean isCharging() {
        Object val = sBmsCache.get(PROP_CHG_STATUS);
        if (val instanceof Number) return ((Number) val).intValue() > 0;
        return false;
    }

    /** AC girişinden gelen toplam enerji — kWh (şarj boyunca birikir) */
    public static float getAcEnergyKwh() { return sAcEnergyKwh; }

    /** Bataryanın aldığı toplam enerji — kWh (şarj boyunca birikir) */
    public static float getDcEnergyKwh() { return sDcEnergyKwh; }

    /** Şarj süresi — ms (isCharging() true olan sürenin toplamı) */
    public static long getChargingDurationMs() { return sChargingElapsedMs; }

    /** Enerji ve süre sayaçlarını sıfırla */
    public static void resetEnergy() {
        sAcEnergyKwh       = 0f;
        sDcEnergyKwh       = 0f;
        sLastBmsEventMs    = 0L;
        sChargingElapsedMs = 0L;
        Log.i(TAG, "resetEnergy() çağrıldı");
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

    @SuppressWarnings("SameParameterValue")
    private static boolean setIntPropertyHvac(int propId, int area, int value) {
        if (sCarHvacManager != null) {
            // Yöntem 1: setIntProperty(propId, area, value)
            try {
                java.lang.reflect.Method setInt = sCarHvacManager.getClass()
                        .getMethod("setIntProperty", int.class, int.class, int.class);
                setInt.invoke(sCarHvacManager, propId, area, value);
                Log.i(TAG, "  HVAC setIntProperty 0x" + Integer.toHexString(propId)
                        + " area=0x" + Integer.toHexString(area) + " value=" + value + " ✓");
                return true;
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                Log.w(TAG, "  HVAC setIntProperty ITE→"
                        + (cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "null"));
            } catch (NoSuchMethodException e) {
                Log.w(TAG, "  HVAC setIntProperty metodu yok");
            } catch (Exception e) {
                Log.w(TAG, "  HVAC setIntProperty hata: " + e.getClass().getSimpleName()
                        + ": " + e.getMessage());
            }
            // Yöntem 2: setBooleanProperty — direksiyon ısıtma boolean olabilir
            if (value == 0 || value == 1) {
                try {
                    java.lang.reflect.Method setBool = sCarHvacManager.getClass()
                            .getMethod("setBooleanProperty", int.class, int.class, boolean.class);
                    setBool.invoke(sCarHvacManager, propId, area, value == 1);
                    Log.i(TAG, "  HVAC setBooleanProperty 0x" + Integer.toHexString(propId)
                            + " area=0x" + Integer.toHexString(area) + " value=" + (value==1) + " ✓");
                    return true;
                } catch (java.lang.reflect.InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    Log.w(TAG, "  HVAC setBooleanProperty ITE→"
                            + (cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "null"));
                } catch (Exception ignored) {}
            }
        }
        // Fallback: CPM ile dene
        return setIntPropertyCPM(propId, area, value);
    }

    @SuppressWarnings("SameParameterValue")
    private static int getIntPropertyCPM(int propId, int area) {
        if (sCarPropertyManager == null) return -1;
        try {
            java.lang.reflect.Method getProperty = sCarPropertyManager.getClass()
                    .getMethod("getProperty", Class.class, int.class, int.class);
            Object cpv = getProperty.invoke(sCarPropertyManager, Integer.class, propId, area);
            if (cpv == null) return -1;
            java.lang.reflect.Method getValue = cpv.getClass().getMethod("getValue");
            int result = (Integer) getValue.invoke(cpv);
            Log.d(TAG, "  CPM getInt 0x" + Integer.toHexString(propId) + " → " + result + " ✓");
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

    @SuppressWarnings("SameParameterValue")
    private static float getFloatPropertyCPM(int propId, int area) {
        if (sCarPropertyManager == null) return Float.NaN;
        try {
            java.lang.reflect.Method getProperty = sCarPropertyManager.getClass()
                    .getMethod("getProperty", Class.class, int.class, int.class);
            Object cpv = getProperty.invoke(sCarPropertyManager, Float.class, propId, area);
            if (cpv == null) return Float.NaN;
            java.lang.reflect.Method getValue = cpv.getClass().getMethod("getValue");
            float result = (Float) getValue.invoke(cpv);
            Log.d(TAG, "  CPM getFloat 0x" + Integer.toHexString(propId) + " → " + result + " ✓");
            return result;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            Log.w(TAG, "  CPM getFloat 0x" + Integer.toHexString(propId)
                    + " ITE: " + (cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "null"));
            return Float.NaN;
        } catch (Exception e) {
            Log.w(TAG, "  CPM getFloat 0x" + Integer.toHexString(propId)
                    + " HATA: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return Float.NaN;
        }
    }

    // -------------------------------------------------------------------------
    // Tanı
    // -------------------------------------------------------------------------

    /**
     * CarBMSManager'a callback kayıt eder.
     * BMS onChangeEvent(propId, area, value) gelince sBmsCache güncellenir.
     * Pull yöntemi (getGlobalProperty) bu araçta çalışmıyor — sadece push (callback) çalışıyor.
     */
    private static void registerBmsCallback(Object bmsManager) {
        try {
            java.lang.reflect.Method[] methods = bmsManager.getClass().getMethods();
            java.lang.reflect.Method registerMethod = null;
            for (java.lang.reflect.Method m : methods) {
                String n = m.getName();
                if (n.contains("register") || n.contains("Register")) {
                    Log.i(TAG, "  BMS register metodu: " + n
                            + " params=" + java.util.Arrays.toString(m.getParameterTypes()));
                    if (registerMethod == null) registerMethod = m;
                }
            }

            if (registerMethod == null) {
                Log.w(TAG, "  BMS: registerCallback metodu bulunamadı — callback devre dışı");
                return;
            }

            Class<?>[] paramTypes = registerMethod.getParameterTypes();
            if (paramTypes.length == 0) {
                Log.w(TAG, "  BMS: register metodu parametre almıyor — atlanıyor");
                return;
            }

            Class<?> callbackClass = paramTypes[0];
            Log.i(TAG, "  BMS: callback sınıfı = " + callbackClass.getName()
                    + " isInterface=" + callbackClass.isInterface());

            if (!callbackClass.isInterface()) {
                Log.w(TAG, "  BMS: callback sınıfı interface değil — proxy oluşturulamaz");
                return;
            }

            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    callbackClass.getClassLoader(),
                    new Class<?>[]{ callbackClass },
                    (proxyObj, method, args) -> {
                        String mName = method.getName();
                        // Logcat'te görülen format: onChangeEvent(propId, area, value)
                        // args[0]=propId(int), args[1]=area(int), args[2]=value(Number)
                        if ((mName.contains("Change") || mName.contains("Event")
                                || mName.contains("Property") || mName.contains("Update"))
                                && args != null && args.length >= 3) {

                            try {
                                int propId = (Integer) args[0];
                                // args[2] Float veya Integer olabilir
                                Object rawVal = args[2];
                                if (rawVal instanceof Number) {
                                    sBmsCache.put(propId, rawVal);
                                    Log.d(TAG, "  BMS cache ← 0x" + Integer.toHexString(propId)
                                            + " = " + rawVal);
                                    // Enerji birikimi: DC voltaj her geldiğinde tetikle (BMS döngüsü başına 1×)
                                    if (propId == PROP_BATT_VOLT) {
                                        long nowMs = android.os.SystemClock.elapsedRealtime();
                                        long deltaMs = (sLastBmsEventMs > 0) ? (nowMs - sLastBmsEventMs) : 0;
                                        sLastBmsEventMs = nowMs;
                                        if (deltaMs > 0 && deltaMs < 5000 && isCharging()) {
                                            sChargingElapsedMs += deltaMs;
                                            float acV = bmsFloat(PROP_AC_VOLT);
                                            float acA = bmsFloat(PROP_AC_AMP);
                                            if (!Float.isNaN(acV) && !Float.isNaN(acA) && acA > 0f) {
                                                sAcEnergyKwh += (acV * acA / 1000f) * deltaMs / 3_600_000f;
                                            }
                                            float dcV = ((Number) rawVal).floatValue();
                                            float dcA = bmsFloat(PROP_CHR_AMP_ACT);
                                            if (!Float.isNaN(dcA) && dcA > 0f) {
                                                sDcEnergyKwh += (dcV * dcA / 1000f) * deltaMs / 3_600_000f;
                                            }
                                        }
                                    }
                                }
                            } catch (Exception ex) {
                                Log.w(TAG, "  BMS callback parse hata: " + ex.getMessage());
                            }
                        }
                        // equals/hashCode/toString — Object metodları için varsayılan dönüş
                        if ("equals".equals(mName)) return args != null && args.length == 1 && proxyObj == args[0];
                        if ("hashCode".equals(mName)) return System.identityHashCode(proxyObj);
                        if ("toString".equals(mName)) return "BmsCallbackProxy";
                        return null;
                    });

            registerMethod.invoke(bmsManager, proxy);
            Log.i(TAG, "  ✓ BMS callback kayıt edildi");

        } catch (Exception e) {
            Log.w(TAG, "  BMS registerCallback hata: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * CarHvacManager'a callback kayıt eder.
     * Araç HVAC property'lerinden herhangi biri değiştiğinde sHvacListener çağrılır.
     * CarHvacManager.CarHvacEventCallback API'si reflection ile denenir.
     */
    private static void registerHvacCallback(Object hvacManager) {
        // CarHvacManager'ın registerCallback metodu ve callback class'ı araç üreticisine göre farklılık gösterir.
        // SAIC implementasyonunda genellikle registerCallback(CarHvacEventCallback) şeklindedir.
        // Önce metodları tara, sonra uygun olanı bul.
        try {
            java.lang.reflect.Method[] methods = hvacManager.getClass().getMethods();
            java.lang.reflect.Method registerMethod = null;
            for (java.lang.reflect.Method m : methods) {
                if (m.getName().contains("register") || m.getName().contains("Register")) {
                    Log.i(TAG, "  HVAC register metodu: " + m.getName()
                            + " params=" + java.util.Arrays.toString(m.getParameterTypes()));
                    if (registerMethod == null) registerMethod = m;
                }
            }

            if (registerMethod == null) {
                Log.w(TAG, "  HVAC: registerCallback metodu bulunamadı — callback devre dışı");
                return;
            }

            // Callback sınıfını dinamik proxy ile oluştur
            Class<?>[] paramTypes = registerMethod.getParameterTypes();
            if (paramTypes.length == 0) {
                Log.w(TAG, "  HVAC: register metodu parametre almıyor — atlanıyor");
                return;
            }

            Class<?> callbackClass = paramTypes[0];
            Log.i(TAG, "  HVAC: callback sınıfı = " + callbackClass.getName()
                    + " isInterface=" + callbackClass.isInterface());

            if (!callbackClass.isInterface()) {
                Log.w(TAG, "  HVAC: callback sınıfı interface değil — proxy oluşturulamaz");
                return;
            }

            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    callbackClass.getClassLoader(),
                    new Class<?>[]{ callbackClass },
                    (proxyObj, method, args) -> {
                        String mName = method.getName();
                        // Callback metodunu logla
                        Log.d(TAG, "  HVAC callback: " + mName
                                + " args=" + java.util.Arrays.toString(args));

                        // CarHvacManager callback metodları genellikle:
                        //   onChangeEvent(CarHvacManager, int propId, int areaId, T value)
                        // ya da onPropertyChanged(int propId, int areaId, int value) vb.
                        if (mName.contains("Change") || mName.contains("Property")
                                || mName.contains("Event") || mName.contains("Update")) {
                            HvacListener listener = sHvacListener;
                            if (listener != null && args != null) {
                                // args içinde int propId bul
                                Integer propId = null;
                                Integer value  = null;
                                for (Object a : args) {
                                    if (a instanceof Integer) {
                                        if (propId == null) propId = (Integer) a;
                                        else if (value == null)  value  = (Integer) a;
                                    }
                                }
                                if (propId != null && value != null) {
                                    final int fProp = propId;
                                    final int fVal  = value;
                                    if (fProp == PROP_STEERING_HEAT
                                            || fProp == PROP_SEAT_HEAT_L
                                            || fProp == PROP_SEAT_HEAT_R) {
                                        listener.onHvacPropertyChanged(fProp, fVal);
                                    }
                                }
                            }
                        }
                        // equals/hashCode/toString — Object metodları için varsayılan dönüş
                        if ("equals".equals(mName)) return args != null && args.length == 1 && proxyObj == args[0];
                        if ("hashCode".equals(mName)) return System.identityHashCode(proxyObj);
                        if ("toString".equals(mName)) return "HvacCallbackProxy";
                        return null;
                    });

            registerMethod.invoke(hvacManager, proxy);
            Log.i(TAG, "  ✓ HVAC callback kayıt edildi");

        } catch (Exception e) {
            Log.w(TAG, "  HVAC registerCallback hata: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
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

    @SuppressWarnings("SameParameterValue")
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
