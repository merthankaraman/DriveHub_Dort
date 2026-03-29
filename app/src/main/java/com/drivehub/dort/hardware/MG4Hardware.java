package com.drivehub.dort.hardware;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;

import com.drivehub.dort.model.DriveMode;
import com.drivehub.dort.model.RegenLevel;

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
    private static final int PROP_DRIVE_MODE         = 0x2140a17c; //557883772
    private static final int PROP_REGEN_LEVEL        = 0x2140a191; //557883793
    private static final int PROP_ONE_PEDAL          = 0x2140a193; //557883795

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
    // CarPropertyValue.getPropertyId() ile callback'te DÖNEN değerler (log 2302261219: 0x2160f406 vb.)
    private static final int PROP_SPEED          = 0x11600207; // 291504647 — float km/h (CarSensorManager)
    private static final int PROP_VEHICLE_IGNITION = 289412477; // getVehicleIgnition(): 0=kapalı, 2=çalışıyor
    private static final int PROP_ENGINE_STATE     = 557847932; // getEngineState(): 0=kapalı, >0=EV sistemi aktif
    private static final int PROP_SOC            = 560002052;   // float % (CarBMSManager)
    private static final int PROP_RANGE          = 0x214099DC; // 557904924 — int km (CarBMSManager)
    private static final int PROP_BATT_VOLT      = 0x2160f406;  // 560039942 — float V DC (callback'te gelen)
    private static final int PROP_CHR_AMP_ACT   = 0x2160f407;  // 560039943 — float A gerçek (callback'te gelen)
    private static final int PROP_CHR_AMP_EXP   = 0x2160f40A;  // 560039946 — float A beklenen (pattern; logda görünmezse NaN kalır)
    private static final int PROP_AC_AMP        = 0x2160f43c;  // 560039996 — float A AC giriş (callback'te gelen)
    private static final int PROP_AC_VOLT      = 0x2160f43d;  // 560039997 — float V AC giriş (callback'te gelen)
    private static final int PROP_CHG_STATUS    = 557904905;   // şarj durumu (0=şarjda değil)
    // Tüketim ekranı (VehicleConditionBinder / BMS — MG4_BINDER_REFERENCE.md)
    private static final int PROP_TOTAL_MILEAGE = 557873939;   // getTotalMileage() — toplam km (VendorInstrumentCluster)
    private static final int PROP_GEAR         = 557847918;    // getCarGear() — vites konumu
    private static volatile float sTripDistanceIntegrationScale = 1f;

    // Katman 2 — Binder (yedek, uid.system gerektirir)
    private static final String DESCRIPTOR_VEHICLE =
            "com.saicmotor.sdk.vehiclesettings.IVehicleSettingService";
    private static final int TX_SET_DRIVE_MODE         = 130;
    private static final int TX_SET_REGEN_LEVEL        = 161;
    private static final int TX_SET_ONE_PEDAL          = 164;
    private static final int COUNT = 1;

    /** HVAC property değişikliğini dinlemek isteyen servis buraya register olur. */
    public interface HvacListener {
        void onHvacPropertyChanged(int propId, int value);
    }

    /** Sürüş modu (drive mode) değişikliğini dinlemek için listener. */
    public interface DriveModeListener {
        void onDriveModeChanged(int modeValue);
    }

    private static volatile HvacListener      sHvacListener      = null;
    private static volatile DriveModeListener sDriveModeListener = null;
    /** CPM/vehicle callback'ten gelen son sürüş modu (getProperty pull bu araçta çalışmayabilir). */
    private static volatile int sCachedDriveMode = -1;
    /** Aynı şekilde regen seviyesi — vehicle manager callback'ten cache. */
    private static volatile int sCachedRegenLevel = -1;

    /** Motor sesi açık mı (prefs yerine tek kaynak; tuş/ekran değiştirince güncellenir). */
    private static volatile boolean sSoundEnabled = false;

    public static void setSoundEnabled(boolean enabled) { sSoundEnabled = enabled; }
    public static boolean isSoundEnabled() { return sSoundEnabled; }

    public static void setHvacListener(HvacListener listener) {
        sHvacListener = listener;
    }

    public static void setDriveModeListener(DriveModeListener listener) {
        sDriveModeListener = listener;
    }

    // BMS cache — CarBMSManager onChangeEvent callback'ten gelen son değerler
    // key=propId, value=son bilinen değer (Float veya Integer olarak Object)
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Object> sBmsCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** İlk BMS callback'te bir kez loglamak için */
    private static volatile boolean sBmsFirstEventLogged = false;
    /** Eksik BMS parse metod uyarısı bir kez */
    private static volatile boolean sBmsParseWarningLogged = false;
    private static volatile boolean sBmsPropIdWarningLogged = false;
    // Enerji birikimi — UI kapalı olsa bile servisin 100ms polling'inde güncellenir
    private static volatile float sAcChargeEnergyKwh       = 0f;
    private static volatile float sDcChargeEnergyKwh       = 0f;
    /** DC istasyon: U_dc × I_beklenen (getDcCurrentExpected) trapez entegrali — kWh */
    private static volatile float sStationDcChargeEnergyKwh = 0f;
    private static volatile long  sLastBmsEventMs    = 0L;

    // 100ms polling ile güncellenen global ölçümler
    private static volatile float sDcVolt            = Float.NaN;
    private static volatile float sDcAmp             = Float.NaN;
    private static volatile float sDcKw              = Float.NaN;
    private static volatile float sDcStationKw       = Float.NaN;
    private static volatile float sAcVolt            = Float.NaN;
    private static volatile float sAcAmp             = Float.NaN;
    private static volatile float sAcKw              = Float.NaN;
    // Şarj süresi için basit sayaç — şarj başladığı anda sistem saatini tutar
    private static volatile long  sChargingStartWallMs = 0L;
    // READY durumu (100ms polling ile güncellenen cache)
    private static volatile boolean sVehicleReady       = false;
    private static volatile int sVehicleIgnition        = 0;
    /** Şarj başlangıcını persist etmek için (BMS'te set, getChargingDurationMs'te geri yükle). */
    private static Context sAppContext = null;
    // Detay log açık mı? (BMS/StatusPanel spam'i için)
    private static volatile boolean sLogEnabled = true;

    // State
    private static Object  sCarPropertyManager     = null;
    private static Object  sCarHvacManager         = null;
    private static boolean sCarBindAttempted       = false;
    private static IBinder sVehicleBinder          = null;
    private static boolean sInitialized            = false;
    private static volatile Object  sVehicleSettingService = null;
    /** Kapı/cam/sunroof için — hub.getService("vehiclecontrol") → IVehicleControlService */
    private static volatile Object  sVehicleControlService = null;
    /** Klima / hava kalitesi — hub.getService("aircondition") → IAirConditionService */
    private static volatile Object  sAirConditionService = null;
    private static volatile boolean sVsBindAttempted       = false;

    // -------------------------------------------------------------------------
    // Init / Destroy
    // -------------------------------------------------------------------------

    public static void init(Context context) {
        if (sInitialized) return;
        sInitialized = true;
        Context appContext = context.getApplicationContext();
        sAppContext = appContext;

        if (sLogEnabled) {
            Log.i(TAG, "========================================");
            Log.i(TAG, "=== MG4Hardware.init() ===");
            Log.i(TAG, "  uid=" + android.os.Process.myUid() + " pid=" + android.os.Process.myPid());
            Log.i(TAG, "  sdk=" + android.os.Build.VERSION.SDK_INT + " device=" + android.os.Build.DEVICE);
        }

        // Katman 1: CarPropertyManager — com.android.car'a bind ol
        bindCarService(appContext);
        bindVehicleService(appContext);

        // Katman 2: Binder (yedek)
        if (sLogEnabled) logAvailableVehicleServices();
        sVehicleBinder = getBinderService("vehiclesetting");
        if (sLogEnabled) {
            if (sVehicleBinder != null) Log.i(TAG, "  ✓ Katman2: vehiclesetting binder bağlı");
            else Log.w(TAG, "  ✗ Katman2: vehiclesetting null (SELinux — beklenen)");
            Log.i(TAG, "========================================");
        }
    }

    public static boolean isReady() {
        return sCarPropertyManager != null || sVehicleBinder != null;
    }

    /** Ayrıntılı log açık mı? */
    public static boolean isLogEnabled() { return sLogEnabled; }

    /** Ayrıntılı log (BMS dump, StatusPanel vb.) aç/kapa. */
    public static void setLogEnabled(boolean enabled) { sLogEnabled = enabled; }

    public static void destroy() {
        sCarPropertyManager    = null;
        sCarHvacManager        = null;
        sVehicleBinder         = null;
        sVehicleSettingService = null;
        sVsBindAttempted       = false;
        sCachedDriveMode       = -1;
        sCachedRegenLevel      = -1;
        sBmsCache.clear();
        sAcChargeEnergyKwh         = 0f;
        sDcChargeEnergyKwh         = 0f;
        sStationDcChargeEnergyKwh  = 0f;
        sLastBmsEventMs      = 0L;
        sChargingStartWallMs = 0L;
        sAppContext          = null;
        sInitialized        = false;
        sCarBindAttempted   = false;
        if (sLogEnabled) Log.i(TAG, "destroy()");
    }

    // -------------------------------------------------------------------------
    // Katman 1 — CarPropertyManager via Car.createCar (callback ile)
    // -------------------------------------------------------------------------

    private static void bindCarService(Context context) {
        if (sCarBindAttempted) return;
        sCarBindAttempted = true;
        try {
            Class<?> carClass = Class.forName("android.car.Car");
            if (sLogEnabled) Log.i(TAG, "  Katman1: android.car.Car sınıfı bulundu ✓");

            // Yöntem A: createCar(Context, Handler) — SDK 28 için en güvenilir
            // Handler null → main thread callback
            java.lang.reflect.Method createCarA = null;
            try {
                createCarA = carClass.getMethod("createCar", Context.class, android.os.Handler.class);
                if (sLogEnabled) Log.i(TAG, "  Katman1: createCar(Context, Handler) metodu bulundu");
            } catch (NoSuchMethodException ignored) {}

            // Yöntem B: createCar(Context) — en basit
            java.lang.reflect.Method createCarB = null;
            try {
                createCarB = carClass.getMethod("createCar", Context.class);
                if (sLogEnabled) Log.i(TAG, "  Katman1: createCar(Context) metodu bulundu");
            } catch (NoSuchMethodException ignored) {}

            // Yöntem C: createCar(Context, ServiceConnection)
            java.lang.reflect.Method createCarC = null;
            try {
                createCarC = carClass.getMethod("createCar", Context.class, ServiceConnection.class);
                if (sLogEnabled) Log.i(TAG, "  Katman1: createCar(Context, ServiceConnection) metodu bulundu");
            } catch (NoSuchMethodException ignored) {}

            Object car = null;

            // Önce B'yi dene (en basit — blocking connect)
            if (createCarB != null) {
                try {
                    car = createCarB.invoke(null, context);
                    if (car != null && sLogEnabled) Log.i(TAG, "  Katman1: createCar(Context) → başarılı");
                } catch (Exception e) {
                    Log.w(TAG, "  createCar(Context) hata: " + e.getMessage());
                    car = null;
                }
            }

            // B başarısız olduysa A'yı dene
            if (car == null && createCarA != null) {
                try {
                    car = createCarA.invoke(null, context, (android.os.Handler) null);
                    if (car != null && sLogEnabled) Log.i(TAG, "  Katman1: createCar(Context, Handler) → başarılı");
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
                        if (sLogEnabled) Log.i(TAG, "  Katman1: ServiceConnection.onServiceConnected → " + name);
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
                    if (car != null && sLogEnabled) Log.i(TAG, "  Katman1: createCar(Context,SC) → callback bekleniyor");
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
                if (sLogEnabled) Log.i(TAG, "  Katman1: car.connect() çağrıldı");
            } catch (NoSuchMethodException e) {
                if (sLogEnabled) Log.i(TAG, "  Katman1: connect() metodu yok (beklenen)");
            } catch (Exception e) {
                Log.w(TAG, "  Katman1: connect() hata: " + e.getMessage());
            }

            // isConnected() kontrol et
            boolean connected = false;
            try {
                java.lang.reflect.Method isConnected = carClass.getMethod("isConnected");
                connected = (Boolean) isConnected.invoke(car);
                if (sLogEnabled) Log.i(TAG, "  Katman1: isConnected() → " + connected);
            } catch (Exception e) {
                Log.w(TAG, "  Katman1: isConnected() yok/hata: " + e.getMessage());
            }

            // Bağlıysa hemen manager'ları al
            if (connected) {
                tryGetManagersFromCar(carClass);
            } else {
                // Bağlı değilse kısa bekle sonra tekrar dene (Handler ile)
                if (sLogEnabled) Log.i(TAG, "  Katman1: Henüz bağlı değil, 500ms sonra tekrar deneniyor...");
                final Class<?> carClassFinal = carClass;
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    boolean c2 = false;
                    try {
                        java.lang.reflect.Method isConn = carClassFinal.getMethod("isConnected");
                        c2 = (Boolean) isConn.invoke(sCar);
                    } catch (Exception ignored) {}
                    if (sLogEnabled) Log.i(TAG, "  Katman1: [retry] isConnected() → " + c2);
                    if (c2) {
                        tryGetManagersFromCar(carClassFinal);
                    } else {
                        // Son deneme: 2 saniye sonra
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            if (sLogEnabled) Log.i(TAG, "  Katman1: [retry2] getManagersFromCar deneniyor...");
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
        if (sLogEnabled) Log.i(TAG, "  tryGetManagersFromCar çağrıldı");

        // isConnected kontrolü
        try {
            java.lang.reflect.Method isConn = carClass.getMethod("isConnected");
            boolean connected = (Boolean) isConn.invoke(sCar);
            if (sLogEnabled) Log.i(TAG, "  isConnected() → " + connected);
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
            if (sLogEnabled) Log.i(TAG, "  PROPERTY_SERVICE = " + propertyService);
            Object cpm = getCarManager.invoke(sCar, propertyService);
            if (cpm != null) {
                sCarPropertyManager = cpm;
                if (sLogEnabled) Log.i(TAG, "  ✓ CarPropertyManager HAZIR: " + cpm.getClass().getName());
                // Sürüş modu gibi CPM tabanlı event'ler için callback kaydı
                registerDriveModeCallback(cpm);
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
                    if (sLogEnabled) Log.i(TAG, "  ✓ CarHvacManager HAZIR: " + chm.getClass().getName());
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
                    if (sLogEnabled) Log.i(TAG, "  ✓ CarBMSManager HAZIR: " + cbm.getClass().getName());
                    registerBmsCallback(cbm);
                } else {
                    Log.w(TAG, "  ✗ CarBMSManager null");
                }
            } catch (Exception e) {
                Log.w(TAG, "  CarBMSManager alınamadı: " + e.getMessage());
            }

            // Sürüş modu: BMS gibi onChangeEvent veren manager (CarAdvancedAssistedDrivingManager vb.)
            // Log'da "onChangeEvent,PropID,area,value:557883772_..." com.saicmotor.service.vehicle'dan geliyor.
            for (String managerKey : new String[]{ "vehicle", "advanced_assisted_driving", "driving_state" }) {
                try {
                    Object mgr = getCarManager.invoke(sCar, managerKey);
                    if (mgr != null) {
                        if (sLogEnabled) Log.i(TAG, "  ✓ Manager '" + managerKey + "' HAZIR: " + mgr.getClass().getName());
                        registerDriveModeCallbackFromVehicleManager(mgr);
                        break; // bir tanesi başarılıysa yeter
                    }
                } catch (Exception e) {
                    if (sLogEnabled) Log.d(TAG, "  getCarManager(" + managerKey + ") yok/hata: " + e.getMessage());
                }
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

    private static void bindVehicleService(Context context) {
        if (sVsBindAttempted) return;
        sVsBindAttempted = true;
        try {
            android.content.Intent intent = new android.content.Intent();
            intent.setComponent(new ComponentName(
                    "com.saicmotor.service.vehicle",
                    "com.saicmotor.service.vehicle.VehicleService"
            ));
            boolean ok = context.bindService(intent, new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    try {
                        java.util.List<ClassLoader> loaders = buildVsClassLoaders(context);

                        // Hub stub
                        Class<?> hubStub = findVsClass(loaders, java.util.Arrays.asList(
                                "com.saicmotor.sdk.vehiclesettings.IHubService$Stub",
                                "com.saicvehicleservice.IHubService$Stub",
                                "com.saicvehicleservice.sdk.IHubService$Stub",
                                "com.saicmotor.service.vehicle.IHubService$Stub"
                        ));
                        if (hubStub == null) throw new ClassNotFoundException("IHubService$Stub bulunamadı");

                        java.lang.reflect.Method asInterface = hubStub.getMethod("asInterface", IBinder.class);
                        Object hub = asInterface.invoke(null, service);

                        // vehiclesetting binder'ı hub üzerinden al
                        java.lang.reflect.Method getService = hub.getClass().getMethod("getService", String.class);
                        IBinder vsBinder = null;
                        for (String key : new String[]{"vehiclesetting", "vehicle_settings", "vehicle_setting_service"}) {
                            try {
                                IBinder b = (IBinder) getService.invoke(hub, key);
                                if (b != null) { vsBinder = b; Log.i(TAG, "  Katman3: vsBinder key=" + key + " ✓"); break; }
                            } catch (Throwable ignored) {}
                        }
                        if (vsBinder == null) throw new IllegalStateException("vehiclesetting binder null");

                        // IVehicleSettingService$Stub ile wrap et
                        Class<?> vsStub = findVsClass(loaders, java.util.Arrays.asList(
                                "com.saicmotor.sdk.vehiclesettings.IVehicleSettingService$Stub",
                                "com.saicvehicleservice.sdk.vehiclesettings.IVehicleSettingService$Stub"
                        ));
                        if (vsStub == null) throw new ClassNotFoundException("IVehicleSettingService$Stub bulunamadı");

                        java.lang.reflect.Method vsAsIface = vsStub.getMethod("asInterface", IBinder.class);
                        sVehicleSettingService = vsAsIface.invoke(null, vsBinder);
                        Log.i(TAG, "  ✓ Katman3: VehicleSettingService HAZIR: "
                                + sVehicleSettingService.getClass().getName());

                        // vehiclecontrol — kapı kilidi, camlar, sunroof (IVehicleControlService)
                        IBinder vcBinder = null;
                        for (String key : new String[]{"vehiclecontrol", "vehicle_control", "vehicle_control_service"}) {
                            try {
                                IBinder b = (IBinder) getService.invoke(hub, key);
                                if (b != null) { vcBinder = b; Log.i(TAG, "  Katman3: vehiclecontrol key=" + key + " ✓"); break; }
                            } catch (Throwable ignored) {}
                        }
                        if (vcBinder != null) {
                            Class<?> vcStub = findVsClass(loaders, java.util.Arrays.asList(
                                    "com.saicmotor.sdk.vehiclesettings.IVehicleControlService$Stub",
                                    "com.saicvehicleservice.sdk.vehiclesettings.IVehicleControlService$Stub"
                            ));
                            if (vcStub != null) {
                                java.lang.reflect.Method vcAsIface = vcStub.getMethod("asInterface", IBinder.class);
                                sVehicleControlService = vcAsIface.invoke(null, vcBinder);
                                Log.i(TAG, "  ✓ Katman3: VehicleControlService HAZIR (kapı/cam)");
                            } else {
                                Log.w(TAG, "  Katman3: IVehicleControlService$Stub bulunamadı");
                            }
                        } else {
                            Log.w(TAG, "  Katman3: vehiclecontrol binder null");
                        }
                        // aircondition — klima, PM2.5, dış sıcaklık (IAirConditionService)
                        IBinder acBinder = null;
                        for (String key : new String[]{"aircondition", "air_condition"}) {
                            try {
                                IBinder b = (IBinder) getService.invoke(hub, key);
                                if (b != null) { acBinder = b; Log.i(TAG, "  Katman3: aircondition key=" + key + " ✓"); break; }
                            } catch (Throwable ignored) {}
                        }
                        if (acBinder != null) {
                            Class<?> acStub = findVsClass(loaders, java.util.Arrays.asList(
                                    "com.saicmotor.sdk.vehiclesettings.IAirConditionService$Stub",
                                    "com.saicvehicleservice.sdk.vehiclesettings.IAirConditionService$Stub"
                            ));
                            if (acStub != null) {
                                java.lang.reflect.Method acAsIface = acStub.getMethod("asInterface", IBinder.class);
                                sAirConditionService = acAsIface.invoke(null, acBinder);
                                Log.i(TAG, "  ✓ Katman3: AirConditionService HAZIR (PM2.5, dış sıcaklık)");
                            } else {
                                Log.w(TAG, "  Katman3: IAirConditionService$Stub bulunamadı");
                            }
                        } else {
                            Log.w(TAG, "  Katman3: aircondition binder null");
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "  ✗ Katman3: onServiceConnected hata: " + t);
                    }
                }
                @Override
                public void onServiceDisconnected(ComponentName name) {
                    sVehicleSettingService = null;
                    sVehicleControlService = null;
                    sAirConditionService = null;
                    Log.w(TAG, "  Katman3: VehicleSettingService/VehicleControlService bağlantısı kesildi");
                }
            }, Context.BIND_AUTO_CREATE);
            if (sLogEnabled) {
                if (ok) Log.i(TAG, "  Katman3: bindService gönderildi, callback bekleniyor...");
                else    Log.w(TAG, "  Katman3: bindService başarısız (paket yok?)");
            }
        } catch (Throwable t) {
            Log.e(TAG, "  Katman3: bindVehicleService hata: " + t);
        }
    }

    private static java.util.List<ClassLoader> buildVsClassLoaders(Context context) {
        java.util.List<ClassLoader> loaders = new java.util.ArrayList<>();
        for (String pkg : new String[]{"com.saicmotor.service.vehicle", "com.saicvehicleservice"}) {
            try {
                android.content.pm.ApplicationInfo ai =
                        context.getPackageManager().getApplicationInfo(pkg, 0);
                if (ai.sourceDir == null) continue;
                java.io.File optDir = new java.io.File(context.getCodeCacheDir(), "dexopt_" + pkg);
                optDir.mkdirs();
                ClassLoader cl = new dalvik.system.DexClassLoader(
                        ai.sourceDir, optDir.getAbsolutePath(), null, context.getClassLoader());
                loaders.add(cl);
                Log.i(TAG, "  Katman3: DexClassLoader hazır: " + pkg);
            } catch (Throwable t) {
                Log.w(TAG, "  Katman3: DexClassLoader başarısız: " + pkg + " → " + t);
            }
        }
        loaders.add(context.getClassLoader()); // fallback
        return loaders;
    }

    private static Class<?> findVsClass(java.util.List<ClassLoader> loaders, java.util.List<String> candidates) {
        for (String name : candidates) {
            for (ClassLoader cl : loaders) {
                try { return Class.forName(name, false, cl); } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /** Katman 3: sVehicleSettingService üzerinde metod adıyla int setter çağır. */
    private static boolean vsSetInt(String methodName, int value) {
        Object vs = sVehicleSettingService;
        if (vs == null) {
            if (sLogEnabled) Log.w(TAG, "  Katman3: vsSetInt — servis bağlı değil");
            return false;
        }
        try {
            java.lang.reflect.Method m = vs.getClass().getMethod(methodName, int.class);
            m.invoke(vs, value);
            if (sLogEnabled) Log.i(TAG, "  Katman3: " + methodName + "(" + value + ") ✓");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "  Katman3: " + methodName + "(" + value + ") HATA: " + t);
            return false;
        }
    }

    /** Katman 3: sVehicleSettingService — int getter. Okunamazsa -1. */
    private static int vsGetInt(String methodName) {
        Object vs = sVehicleSettingService;
        if (vs == null) return -1;
        try {
            java.lang.reflect.Method m = vs.getClass().getMethod(methodName);
            Object result = m.invoke(vs);
            if (result instanceof Number) return ((Number) result).intValue();
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Uzaklaşma ile kilitleme: 0=Kapalı, 1=Açık. Anahtar uzaklaşınca araç kilitlenir (PEPS). */
    public static int getLeaveAutoLockMode() {
        int v = vsGetInt("getLeaveAutoLockMode");
        if (sLogEnabled) Log.i(TAG, "DEMO: getLeaveAutoLockMode => " + v);
        return v;
    }

    /** Uzaklaşma ile kilitleme aç (1) veya kapat (0). */
    public static boolean setLeaveAutoLockMode(int value) {
        if (sLogEnabled) Log.i(TAG, "DEMO: setLeaveAutoLockMode(" + value + ")");
        return vsSetInt("setLeaveAutoLockMode", value);
    }

    /** Yaklaşma ile açma: 0=Kapalı, 1=Açık. Anahtar yaklaşınca kilit açılır (PEPS). */
    public static int getApproachUnlockMode() {
        int v = vsGetInt("getApproachUnlockMode");
        if (sLogEnabled) Log.i(TAG, "DEMO: getApproachUnlockMode => " + v);
        return v;
    }

    /** Yaklaşma ile açma aç (1) veya kapat (0). */
    public static boolean setApproachUnlockMode(int value) {
        if (sLogEnabled) Log.i(TAG, "DEMO: setApproachUnlockMode(" + value + ")");
        return vsSetInt("setApproachUnlockMode", value);
    }

    /** Yakın alan açma (kapı kolu yakınında). */
    public static int getNearfieldUnlockMode() {
        int v = vsGetInt("getNearfieldUnlockMode");
        if (sLogEnabled) Log.i(TAG, "DEMO: getNearfieldUnlockMode => " + v);
        return v;
    }

    public static boolean setNearfieldUnlockMode(int value) {
        if (sLogEnabled) Log.i(TAG, "DEMO: setNearfieldUnlockMode(" + value + ")");
        return vsSetInt("setNearfieldUnlockMode", value);
    }

    /** Katman 3: VehicleControlService (kapı/cam) — int setter. */
    private static boolean vsControlSetInt(String methodName, int value) {
        Object vc = sVehicleControlService;
        if (vc == null) {
            if (sLogEnabled) Log.w(TAG, "  Katman3: vsControlSetInt — VehicleControlService bağlı değil");
            return false;
        }
        try {
            java.lang.reflect.Method m = vc.getClass().getMethod(methodName, int.class);
            m.invoke(vc, value);
            if (sLogEnabled) Log.i(TAG, "  Katman3: VehicleControl." + methodName + "(" + value + ") ✓");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "  Katman3: VehicleControl." + methodName + "(" + value + ") HATA: " + t);
            return false;
        }
    }

    /** Katman 3: VehicleControlService (cam pozisyonu 0–100) — float setter. */
    private static boolean vsControlSetFloat(String methodName, float value) {
        Object vc = sVehicleControlService;
        if (vc == null) {
            if (sLogEnabled) Log.w(TAG, "  Katman3: vsControlSetFloat — VehicleControlService bağlı değil");
            return false;
        }
        try {
            java.lang.reflect.Method m = vc.getClass().getMethod(methodName, float.class);
            m.invoke(vc, value);
            if (sLogEnabled) Log.i(TAG, "  Katman3: VehicleControl." + methodName + "(" + value + ") ✓");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "  Katman3: VehicleControl." + methodName + "(" + value + ") HATA: " + t);
            return false;
        }
    }

    /** Katman 3: VehicleControlService — float getter (cam yüzdesi 0–100). Okunamazsa -1. */
    private static float vsControlGetFloat(String methodName) {
        Object vc = sVehicleControlService;
        if (vc == null) return -1f;
        try {
            java.lang.reflect.Method m = vc.getClass().getMethod(methodName);
            Object result = m.invoke(vc);
            if (result instanceof Number) return ((Number) result).floatValue();
            return -1f;
        } catch (Throwable t) {
            return -1f;
        }
    }

    /** Katman 3: VehicleControlService — int getter (ESP vb.). Okunamazsa -1. */
    private static int vsControlGetInt(String methodName) {
        Object vc = sVehicleControlService;
        if (vc == null) return -1;
        try {
            java.lang.reflect.Method m = vc.getClass().getMethod(methodName);
            Object result = m.invoke(vc);
            if (result instanceof Number) return ((Number) result).intValue();
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    // -------------------------------------------------------------------------
    // Kapı / Cam — VehicleControlService (Property ID yerine tek metot)
    // -------------------------------------------------------------------------

    /** Kapı kilitle (1) veya aç (0). VehicleSettingsService bağlıysa kullanır. */
    public static boolean setDoorLock(int lock) {
        boolean ok = vsControlSetInt("setDoorLock", lock);
        if (sLogEnabled) Log.i(TAG, "DEMO: setDoorLock(" + lock + ") => " + ok);
        return ok;
    }

    /** Kapı kilit durumu: 0=Açık, 1=Kilitli. Okunamazsa -1. */
    public static int getDoorLock() {
        int v = vsControlGetInt("getDoorLock");
        if (sLogEnabled) Log.i(TAG, "DEMO: getDoorLock => " + v);
        return v;
    }

    /** Sürücü camı: 0f=kapalı, 100f=tam açık. */
    public static boolean setDriveWindow(float position) {
        return vsControlSetFloat("setDriveWindow", position);
    }

    /** Yolcu camı: 0f=kapalı, 100f=tam açık. */
    public static boolean setPassengerWindow(float position) {
        return vsControlSetFloat("setPassengerWindow", position);
    }

    /** Sol arka cam. */
    public static boolean setLeftRearWindow(float position) {
        return vsControlSetFloat("setLeftRearWindow", position);
    }

    /** Sağ arka cam. */
    public static boolean setRightRearWindow(float position) {
        return vsControlSetFloat("setRightRearWindow", position);
    }

    /** Tüm camları kapat (0). */
    public static boolean closeAllWindows() {
        boolean a = vsControlSetFloat("setDriveWindow", 0f);
        boolean b = vsControlSetFloat("setPassengerWindow", 0f);
        boolean c = vsControlSetFloat("setLeftRearWindow", 0f);
        boolean d = vsControlSetFloat("setRightRearWindow", 0f);
        return a || b || c || d;
    }

    /** VehicleControlService (kapı/cam) bağlı mı? */
    public static boolean isVehicleControlServiceBound() {
        return sVehicleControlService != null;
    }

    /** Cam yüzdelerini oku (0–100). Okunamazsa -1. */
    public static float getDriveWindow() {
        float v = vsControlGetFloat("getDriveWindow");
        if (sLogEnabled) Log.i(TAG, "DEMO: getDriveWindow => " + v);
        return v;
    }
    public static float getPassengerWindow() {
        float v = vsControlGetFloat("getPassengerWindow");
        if (sLogEnabled) Log.i(TAG, "DEMO: getPassengerWindow => " + v);
        return v;
    }
    public static float getLeftRearWindow() {
        float v = vsControlGetFloat("getLeftRearWindow");
        if (sLogEnabled) Log.i(TAG, "DEMO: getLeftRearWindow => " + v);
        return v;
    }
    public static float getRightRearWindow() {
        float v = vsControlGetFloat("getRightRearWindow");
        if (sLogEnabled) Log.i(TAG, "DEMO: getRightRearWindow => " + v);
        return v;
    }

    /** ESP: 0=Kapalı, 1=Açık (API’de set 0..3 geçerli). Okunamazsa -1. */
    public static int getEspSwitch() {
        int v = vsControlGetInt("getEspSwitch");
        if (sLogEnabled) Log.i(TAG, "DEMO: getEspSwitch => " + v);
        return v;
    }

    /** ESP kapat (0) veya aç (1). Karlı/buzda tekerleklerin tutunması için 0 denenebilir. */
    public static boolean setEspSwitch(int value) {
        if (sLogEnabled) Log.i(TAG, "DEMO: setEspSwitch(" + value + ")");
        return vsControlSetInt("setEspSwitch", value);
    }

    // -------------------------------------------------------------------------
    // Hava kalitesi / Klima — IAirConditionService (hub.getService("aircondition"))
    // -------------------------------------------------------------------------

    private static int acGetInt(String methodName) {
        Object ac = sAirConditionService;
        if (ac == null) return -1;
        try {
            java.lang.reflect.Method m = ac.getClass().getMethod(methodName);
            Object result = m.invoke(ac);
            if (result instanceof Number) return ((Number) result).intValue();
            return -1;
        } catch (Throwable t) { return -1; }
    }

    private static float acGetFloat(String methodName) {
        Object ac = sAirConditionService;
        if (ac == null) return Float.NaN;
        try {
            java.lang.reflect.Method m = ac.getClass().getMethod(methodName);
            Object result = m.invoke(ac);
            if (result instanceof Number) return ((Number) result).floatValue();
            return Float.NaN;
        } catch (Throwable t) { return Float.NaN; }
    }

    /** PM2.5 (partikül/toz) yoğunluğu. Okunamazsa -1. */
    public static int getPm25Concentration() {
        int v = acGetInt("getPm25Concentration");
        if (sLogEnabled) Log.i(TAG, "DEMO: getPm25Concentration => " + v);
        return v;
    }
    /** PM2.5 filtre durumu. Okunamazsa -1. */
    public static int getPm25Filter() {
        int v = acGetInt("getPm25Filter");
        if (sLogEnabled) Log.i(TAG, "DEMO: getPm25Filter => " + v);
        return v;
    }
    /** Negatif iyon durumu. Okunamazsa -1. */
    public static int getAnionStatus() { return acGetInt("getAnionStatus"); }
    /** Dış ortam sıcaklığı (°C). Okunamazsa NaN. */
    public static float getOutCarTemp() {
        float v = acGetFloat("getOutCarTemp");
        if (sLogEnabled) Log.i(TAG, "DEMO: getOutCarTemp => " + (Float.isNaN(v) ? "NaN" : v));
        return v;
    }
    // getDrvTemp / getPsgTemp bilinçli olarak Demo ekranında kullanılmıyor (klima set derecesi).

    /** Hava kalitesi hassasiyeti (AQS). IVehicleSettingService. Okunamazsa -1. */
    public static int getAqsSensitivity() {
        int v = vsGetInt("getAqsSensitivity");
        if (sLogEnabled) Log.i(TAG, "DEMO: getAqsSensitivity => " + v);
        return v;
    }
    public static boolean setAqsSensitivity(int value) { return vsSetInt("setAqsSensitivity", value); }

    // -------------------------------------------------------------------------
    // Setter'lar
    // -------------------------------------------------------------------------

    public static boolean setDriveMode(DriveMode mode) {
        boolean ret_val = false;
        if (sLogEnabled) Log.i(TAG, "setDriveMode → " + mode.label + " (" + mode.value + ")");
        if (setIntPropertyCPM(PROP_DRIVE_MODE, AREA_GLOBAL, mode.value)) ret_val = true;
        if (!ret_val) ret_val = binderTransact(sVehicleBinder, DESCRIPTOR_VEHICLE, TX_SET_DRIVE_MODE, mode.value);
        if (sAppContext != null) {
            sAppContext.getSharedPreferences("drivehub_dort", Context.MODE_PRIVATE)
                    .edit()
                    .putInt(com.drivehub.dort.service.MG4ControlService.PREF_LAST_DRIVE_MODE, mode.value)
                    .apply();
        }
        return ret_val;
    }
    public static boolean setOnePedal(boolean enabled) {
        if (sLogEnabled) Log.i(TAG, "setOnePedal → " + (enabled ? "Açık" : "Kapalı"));
        if (setIntPropertyCPM(PROP_ONE_PEDAL, AREA_GLOBAL, enabled ? 1 : 0)) return true;
        return binderTransact(sVehicleBinder, DESCRIPTOR_VEHICLE, TX_SET_ONE_PEDAL, enabled ? 1 : 0);
    }

    public static boolean setRegenLevel(RegenLevel level) {
        boolean ok;
        if (sLogEnabled) Log.i(TAG, "setRegenLevel → " + level.label + " (" + level.value + ")");
        if (level != RegenLevel.ONE_PEDAL){
            setOnePedal(false);
            ok = setIntPropertyCPM(PROP_REGEN_LEVEL, AREA_GLOBAL, level.value);
            if (!ok) ok = binderTransact(sVehicleBinder, DESCRIPTOR_VEHICLE, TX_SET_REGEN_LEVEL, level.value);
        }
        else{
            ok = setOnePedal(true);
            if (!ok) ok = setIntPropertyCPM(PROP_REGEN_LEVEL, AREA_GLOBAL, level.value);
            if (!ok) ok = binderTransact(sVehicleBinder, DESCRIPTOR_VEHICLE, TX_SET_REGEN_LEVEL, level.value);
        }

        if (sLogEnabled) Log.i(TAG, "setRegenLevel: seviye=" + level.value + " CPM=" + ok);

        // En son seçilen regen seviyesini her durumda (OK olsa da olmasa da) hatırla
        /*if (sAppContext != null) {
            sAppContext.getSharedPreferences("drivehub_dort", Context.MODE_PRIVATE)
                    .edit()
                    .putInt(com.drivehub.dort.service.MG4ControlService.PREF_LAST_REGEN_LEVEL, level.value)
                    .apply();
        }*/
        return ok;
    }
    public static boolean setSteeringHeat(boolean targetOn) {
        // 1. Önce arabadaki mevcut durumu oku
        int currentStatus = getIntPropertyHvac(PROP_STEERING_HEAT, AREA_HVAC);

        // currentStatus: 0 ise Kapalı, 1 veya daha büyükse Açık
        boolean isActuallyOn = (currentStatus > 0);

        if (sLogEnabled) Log.i(TAG, "Direksiyon Isıtma Durumu: " + currentStatus + " | Hedef: " + targetOn);

        // 2. Eğer araba zaten istediğin durumdaysa hiçbir şey yapma
        if (isActuallyOn == targetOn) {
            if (sLogEnabled) Log.i(TAG, "Zaten hedef durumda, komut gönderilmedi.");
            return true;
        }

        // 3. Durum farklıysa "1" göndererek toggle yap (durumu değiştir)
        if (sLogEnabled) Log.i(TAG, "Durum değişiyor, toggle komutu gönderiliyor...");
        return setIntPropertyHvac(PROP_STEERING_HEAT, AREA_HVAC, 1);
    }

    /** Sol koltuk ısıtma seviyesi (0=kapalı, 1/2/3=seviye) */
    public static boolean setSeatHeatLeft(int level) {
        if (sLogEnabled) Log.i(TAG, "setSeatHeatLeft → " + level);
        return setHvacLevelWithToggle(PROP_SEAT_HEAT_L, AREA_HVAC, level);
    }

    /** Sağ koltuk ısıtma seviyesi (0=kapalı, 1/2/3=seviye) */
    public static boolean setSeatHeatRight(int level) {
        if (sLogEnabled) Log.i(TAG, "setSeatHeatRight → " + level);
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
                if (sLogEnabled) Log.i(TAG, "HVAC Hedefe ulaşıldı: " + targetLevel);
                return true;
            }

            // 2. Tık gönderme zamanı geldi mi? (500ms bekleme)
            long now = System.currentTimeMillis();
            if (now - lastStepTime >= stepInterval) {
                if (sLogEnabled) Log.i(TAG, "HVAC Tık gönderiliyor... Mevcut: " + current);
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

    /** Sürüş modu: önce CPM callback cache (onChangeEvent); yoksa getProperty dene. */
    public static int getDriveMode() {
        if (sCachedDriveMode >= 0) return sCachedDriveMode;
        int v = getIntPropertyCPM(PROP_DRIVE_MODE, AREA_GLOBAL);
        if (v >= 0) sCachedDriveMode = v;
        return v;
    }
    /** Regen seviyesi: önce vehicle/CPM callback cache; yoksa getProperty dene (sürüş modu gibi). */
    public static int getRegenLevel() {
        int v;
        if (sCachedRegenLevel >= 0) return sCachedRegenLevel;
        if (getOnePedal() == 1) {
            sCachedRegenLevel = 6;
            v = sCachedRegenLevel;
        }
        else {
            v = getIntPropertyCPM(PROP_REGEN_LEVEL, AREA_GLOBAL);
            if (v >= 0) sCachedRegenLevel = v;
        }
        return v;
    }
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

    /** Araç hızı — km/h (araç doğrudan km/h gönderiyor, dönüşüm gereksiz). Mümkünse getSpeedForEngine() kullan (tek okuma). */
    public static float getSpeedKmh() {
        return getFloatPropertyCPM(PROP_SPEED, AREA_GLOBAL);
    }

    // Hız tek kaynak: sim açıksa sim, değilse gerçek; sadece getSpeedForEngine() hattan okur
    private static volatile boolean sSimSpeedActive = false;
    private static volatile float   sSimSpeedKmh   = 0f;
    private static volatile float   sLastSpeedForDisplay = 0f;

    /** MainActivity hız testi açınca/kapayınca veya slider değişince çağrılır. */
    public static void setSimSpeed(boolean active, float kmh) {
        sSimSpeedActive = active;
        sSimSpeedKmh = (kmh >= 0f && kmh <= 500f) ? kmh : 0f;
    }

    /** Sim (hız testi) açık mı — servis sesi sim modunda da çalsın diye kullanır. */
    public static boolean isSimSpeedActive() {
        return sSimSpeedActive;
    }

    /** Motor sesi için hız — tek yerden okuma. Sim açıksa sim hız, değilse hattan okuyup sLastSpeedForDisplay günceller. */
    public static float getSpeedForEngine() {
        if (sSimSpeedActive) return sSimSpeedKmh;
        float s = getSpeedKmh();
        sLastSpeedForDisplay = Float.isNaN(s) ? 0f : s;
        return sLastSpeedForDisplay;
    }

    /** UI için son okunan gerçek hız (getSpeedForEngine tarafından güncellenir; hattan ek okuma yapmaz). */
    public static float getLastSpeedForDisplay() {
        return sLastSpeedForDisplay;
    }

    /** Araç "READY" mi? (EV sistemi aktif mi?) — 100ms polling ile güncellenen cache'ten döner. */
    public static boolean isVehicleReady() {
        return sVehicleReady;
    }
    /** Araç kontak durumu 2 ise ekran kontak açık frene basıldığında anlık 3 sonra tekrar 2 */
    public static int getVehicleIgnition() {
        return sVehicleIgnition;
    }

    /** SOC — % (0.0–100.0). 100ms task'ta güncellenen cache değerini döner. */
    public static float getSoc() {
        if (Float.isNaN(sLastSoc)) {
            // İlk çağrıda (task henüz çalışmadıysa) tek seferlik ham okuma yap.
            sLastSoc = readSocRaw();
        }
        return sLastSoc;
    }

    /** SOC — CPM'den oku, yoksa BMS cache'e bak (ham okuma). */
    private static float readSocRaw() {
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

    /** Toplam km (kilometre sayacı). -1 = okunamadı. */
    public static int getTotalMileage() {
        return getIntPropertyCPM(PROP_TOTAL_MILEAGE, AREA_GLOBAL);
    }

    /** Vites konumu (getCarGear). Araç üreticisine göre değerler (P/R/N/D vb. sayısal). -1 = okunamadı. */
    public static int getGear() {
        return getIntPropertyCPM(PROP_GEAR, AREA_GLOBAL);
    }
    // -------------------------------------------------------------------------
    // Enerji integrasyonu (serviste 100ms'de bir çalışır; uygulama açık olmasa da boot'tan itibaren)
    // Sadece DC/AC kW üzerinden hesaplanır; consumption*hız tabanlı "sürüş gücü" kullanılmaz.
    // -------------------------------------------------------------------------
    private static volatile double sTripEnergyKwh = 0.0;
    private static volatile double sTripDistanceKm = 0.0;
    private static volatile double sTripHours = 0.0;
    /** Hayat boyu km/kWh — trip gibi integral, periyodik hafızaya yazılır (sıfırlanmaz). */
    private static volatile double sLifetimeKm = 0.0;
    private static volatile double sLifetimeKwh = 0.0;
    /** Hayat boyu sürüş süresi — saat cinsinden (ortalama hız hesapları için). */
    private static volatile double sLifetimeHours = 0.0;
    private static final String PREF_NAME_LIFETIME = "drivehub_dort";
    private static final String PREF_LIFETIME_KM = "consumption_lifetime_km";
    private static final String PREF_LIFETIME_KWH = "consumption_lifetime_kwh1";
    private static final String PREF_LIFETIME_HOURS = "consumption_lifetime_hours";

    // Kullanıcı tanımlı ek uzun dönem tüketim profilleri (Hayat boyu'dan bağımsız, ayrı ayrı sıfırlanabilir).
    // Dizideki tüm slotlar (0..N-1) gerçek profil; Lifetime ayrı değişkenlerde tutulur.
    private static final int CONSUMPTION_PROFILE_SLOTS = 3; // Bu sayıyı arttırmak yeni profiller ekler.
    private static final String PREF_CONS_PROFILE_KM_PREFIX  = "cons_profile_km_";
    private static final String PREF_CONS_PROFILE_KWH_PREFIX = "cons_profile_kwh_";
    private static final String PREF_CONS_PROFILE_NAME_PREFIX  = "cons_profile_name_";
    private static final String PREF_CONS_PROFILE_HOURS_PREFIX = "cons_profile_hours_";
    private static final String PREF_CONS_PROFILE_DEFAULT_NAME_PREFIX = "Profil ";
    private static final double[] sConsProfileKm     = new double[CONSUMPTION_PROFILE_SLOTS];
    private static final double[] sConsProfileKwh    = new double[CONSUMPTION_PROFILE_SLOTS];
    private static final double[] sConsProfileHours  = new double[CONSUMPTION_PROFILE_SLOTS];
    private static final String[] sConsProfileName   = new String[CONSUMPTION_PROFILE_SLOTS];
    // Sürüş grafiği için bağımsız sayaçlar (UI trip reset'inden etkilenmez)
    private static volatile double sDriveGraphEnergyKwh = 0.0;
    private static volatile double sDriveGraphDistanceKm = 0.0;
    private static volatile int sMileageAtConsumptionStart = -1;
    /** Son integrasyon anı (monotonik; SystemClock.elapsedRealtime()). Saat geri alınsa bile dt negatif olmaz. */
    private static volatile long sConsumptionLastRealtimeMs = 0;
    private static volatile float sLastSpeedKmh = Float.NaN;
    /** SOC cache: 100ms task içinde güncellenir. */
    private static volatile float sLastSoc = Float.NaN;
    /** 1:P  2:R  3:N  4:D */
    private static volatile int sLastGear = -1;
    private static volatile int elseifcounter = 0;
    private static volatile int elsecounter = 0;
    private static volatile int dtHourscounter = 0;
    private static volatile int dtHourscounter1 = 0;

    /**
     * Serviste 100ms'de bir çağrılır:
     *  - DC/AC güçleri oku
     *  - Enerjiyi ve mesafeyi entegre et
     *  - Global önbelleğe (sDcKw, sTripEnergyKwh, sTripDistanceKm, vb.) yaz
     */
    public static void run100msTask() {
        float speedKmh_raw = getSpeedKmh();
        float speedKmh = speedKmh_raw * (speedKmh_raw >= 80 ? 1.003f : 1f);
        if (speedKmh < 1.0f) speedKmh = 0f;


        if (Float.isNaN(speedKmh)) speedKmh = sLastSpeedForDisplay;

        // SOC'yi 100ms task içinde cache'le (UI paneli bu değişkenden okusun).
        sLastSoc = readSocRaw();

        float dcVolt = getDcVoltage();
        float dcAmpAct = getDcCurrentActual();
        float dcKw;
        if (!Float.isNaN(dcVolt) && !Float.isNaN(dcAmpAct)) {
            dcKw = (dcVolt * dcAmpAct) / 1000f;
        } else {
            dcKw = Float.NaN;
            elsecounter += 1;
        }
        float dcAmpExp = getDcCurrentExpected();
        float stationDcKw = (!Float.isNaN(dcVolt) && !Float.isNaN(dcAmpExp))
                ? (dcVolt * dcAmpExp) / 1000f : Float.NaN;
        if (!Float.isNaN(stationDcKw) && Math.abs(stationDcKw) > 300f) {
            stationDcKw = Float.NaN;
        }

        // Güç için güvenlik sınırı: abs(dcKw) mantıksız derecede büyükse integrale sokma
        if (!Float.isNaN(dcKw) && Math.abs(dcKw) > 300f) {
            if (sLogEnabled) {
                Log.w(TAG, "integral_diag: dcKw clamp edildi dcKw=" + dcKw
                        + " dcVolt=" + dcVolt + " dcAmpAct=" + dcAmpAct
                        + " speedKmh=" + speedKmh);
            }
            elseifcounter += 1;
            dcKw = Float.NaN;
        }

        if ((elseifcounter > 0 || elsecounter > 0)) Log.i(TAG,"integral_diag: dcVolt ve dcAmp NAN elseif: " + elseifcounter + " else: " + elsecounter + "kwh/km speedKmh " + speedKmh);


        float acVolt = getAcVoltage();
        float acAmp = getAcCurrent();
        float acKw = (!Float.isNaN(acVolt) && !Float.isNaN(acAmp))
                ? (acVolt * acAmp) / 1000f : Float.NaN;

        // Monotonik saat: NTP/senkron ile geri gitmez, integral için kayıp/çift sayım olmaz
        long nowRealtimeMs = SystemClock.elapsedRealtime();
        double dtHours = (sConsumptionLastRealtimeMs > 0)
                ? (nowRealtimeMs - sConsumptionLastRealtimeMs) / 3600000.0
                : 0.0;
        if (dtHours <= 0.0 || dtHours > 1.0) {
            if (dtHours <= 0.0) dtHourscounter += 1;
            else dtHourscounter1 += 1;
            if (sLogEnabled) {
                Log.w(TAG,"integral_diag: dtHours skip dt=" + dtHours
                        + " lastRealtimeMs=" + sConsumptionLastRealtimeMs + " nowRealtimeMs=" + nowRealtimeMs);
            }
            sConsumptionLastRealtimeMs = nowRealtimeMs;
            return;
        }
        if (dtHourscounter > 0 || dtHourscounter1 > 0) Log.i(TAG,"integral_diag: dtHourscounter " + dtHourscounter + " dtHourscounter1: " + dtHourscounter1);

        sConsumptionLastRealtimeMs = nowRealtimeMs;
        if (dtHours > 0) {
            double drivedKwh = 0;
            float vEffKmh;
            if (Float.isNaN(sLastSpeedKmh)) {
                vEffKmh = Math.abs(speedKmh);
            } else {
                vEffKmh = (Math.abs(sLastSpeedKmh) + Math.abs(speedKmh)) * 0.5f;
            }
            double dKm = vEffKmh * dtHours * sTripDistanceIntegrationScale;
            // DC güç (kWh): trapez — önceki tick sDcKw + şimdiki dcKw ortalaması × dt
            if (!Float.isNaN(dcKw)) {
                if (speedKmh == 0f && dcKw < 0f) {
                    drivedKwh = 0;
                } else {
                    float pPrev = sDcKw;
                    double pEffKw = Float.isNaN(pPrev) ? dcKw : (pPrev + dcKw) * 0.5;
                    drivedKwh = pEffKw * dtHours;
                }
            }
            for (int i = 0; i < CONSUMPTION_PROFILE_SLOTS; i++) {
                sConsProfileKm[i] += dKm;
                sConsProfileKwh[i] += drivedKwh;
            }
            sTripDistanceKm += dKm;
            sTripEnergyKwh += drivedKwh;

            sLifetimeKwh += drivedKwh;
            sLifetimeKm += dKm;

            //if (isVehicleReady()) {
                sLifetimeHours += dtHours;
                sTripHours += dtHours;
                for (int i = 0; i < CONSUMPTION_PROFILE_SLOTS; i++) {
                    sConsProfileHours[i] += dtHours;
                }
            //    if (sLastGear >= 2 && sLastGear <= 4) {

                    if (!Float.isNaN(speedKmh)) {
                        sDriveGraphDistanceKm += dKm;
                    }
                    if (!Float.isNaN(dcKw)) {
                        sDriveGraphEnergyKwh += drivedKwh;
                    }
            //    }
            //}
            if (isCharging()) {
                if (!Float.isNaN(acKw) && acKw > 0f) {
                    double aEffKw = (Float.isNaN(sAcKw) || sAcKw <= 0f)
                            ? acKw
                            : (sAcKw + acKw) * 0.5;
                    if (aEffKw > 0) {
                        sAcChargeEnergyKwh += aEffKw * dtHours;
                    }
                }
                if (!Float.isNaN(dcKw)) {
                    double pEffKw = Float.isNaN(sDcKw) ? dcKw : (sDcKw + dcKw) * 0.5;
                    if (pEffKw < 0f) {
                        sDcChargeEnergyKwh += Math.abs(pEffKw * dtHours);
                    }
                }
                if (!Float.isNaN(stationDcKw)) {
                    double sEffKw = Float.isNaN(sDcStationKw) ? stationDcKw : (sDcStationKw + stationDcKw) * 0.5;
                    if (sEffKw < 0f) {
                        sStationDcChargeEnergyKwh += Math.abs(sEffKw * dtHours);
                    } else if (sEffKw > 0f) {
                        sStationDcChargeEnergyKwh += sEffKw * dtHours;
                    }
                }
            }
        } else {
            dtHourscounter += 1;
        }

        sDcVolt = dcVolt;
        sDcAmp  = dcAmpAct;
        sDcKw   = dcKw;
        sDcStationKw = stationDcKw;
        sAcVolt = acVolt;
        sAcAmp  = acAmp;
        sAcKw   = acKw;
        sLastSpeedKmh = speedKmh;
        sLastGear = getGear();

        sVehicleIgnition = getIntPropertyCPM(PROP_VEHICLE_IGNITION, AREA_GLOBAL);
        sVehicleReady = (getIntPropertyCPM(PROP_ENGINE_STATE, AREA_GLOBAL) == 1);
        // Şarj durumu: callback gelmeden (uygulama yeniden başladıysa) polling ile cache'e yaz
        int chgSt = getIntPropertyCPM(PROP_CHG_STATUS, AREA_GLOBAL);
        if (chgSt >= 0) {
            sBmsCache.put(PROP_CHG_STATUS, Integer.valueOf(chgSt));
        }
    }

    /** Yol sıfırla: trip başlangıç km = şimdiki toplam km, enerji + mesafe = 0. */
    public static void resetConsumptionTrip() {
        sMileageAtConsumptionStart = getTotalMileage();
        sTripEnergyKwh = 0.0;
        sTripDistanceKm = 0.0;
        sTripHours = 0.0;
        sConsumptionLastRealtimeMs = SystemClock.elapsedRealtime();
    }

    /** İlk kez trip başlat (panel açıldığında veya servis ilk çalıştığında; -1 ise şimdiki km'den başlat). */
    public static void ensureConsumptionTripStarted() {
        if (sMileageAtConsumptionStart < 0) {
            sMileageAtConsumptionStart = getTotalMileage();
            sConsumptionLastRealtimeMs = SystemClock.elapsedRealtime();
        }
    }
    public static double getTripEnergyKwh() { return sTripEnergyKwh; }
    public static double getTripDistanceKm() { return sTripDistanceKm; }
    public static double getTripHours() { return sTripHours; }

    /** Hayat boyu değerlerini hafızadan yükle (servis başlarken bir kez çağrılmalı). */
    public static void loadLifetimeFromPrefs(Context ctx) {
        if (ctx == null) return;
        android.content.SharedPreferences p = ctx.getSharedPreferences(PREF_NAME_LIFETIME, Context.MODE_PRIVATE);
        sLifetimeKm = p.getFloat(PREF_LIFETIME_KM, 0f);
        sLifetimeKwh = p.getFloat(PREF_LIFETIME_KWH, 0f);
        sLifetimeHours = p.getFloat(PREF_LIFETIME_HOURS, 0f);
        // Ek profillerin sayaçlarını ve isimlerini yükle (slot 0..N-1)
        for (int i = 0; i < CONSUMPTION_PROFILE_SLOTS; i++) {
            sConsProfileKm[i] = p.getFloat(PREF_CONS_PROFILE_KM_PREFIX + i, 0f);
            sConsProfileKwh[i] = p.getFloat(PREF_CONS_PROFILE_KWH_PREFIX + i, 0f);
            sConsProfileHours[i] = p.getFloat(PREF_CONS_PROFILE_HOURS_PREFIX + i, 0f);
            sConsProfileName[i] = p.getString(PREF_CONS_PROFILE_NAME_PREFIX + i, null);
        }
    }

    /** Hayat boyu değerlerini hafızaya yaz (serviste arada bir çağrılmalı; veri kaybı olmasın). */
    public static void persistLifetimeToPrefs(Context ctx) {
        if (ctx == null) return;
        android.content.SharedPreferences.Editor e =
                ctx.getSharedPreferences(PREF_NAME_LIFETIME, Context.MODE_PRIVATE).edit()
                        .putFloat(PREF_LIFETIME_KM, (float) sLifetimeKm)
                        .putFloat(PREF_LIFETIME_KWH, (float) sLifetimeKwh)
                        .putFloat(PREF_LIFETIME_HOURS, (float) sLifetimeHours);
        // Ek profillerin sayaçlarını da periyodik olarak persist et
        for (int i = 0; i < CONSUMPTION_PROFILE_SLOTS; i++) {
            e.putFloat(PREF_CONS_PROFILE_KM_PREFIX + i, (float) sConsProfileKm[i]);
            e.putFloat(PREF_CONS_PROFILE_KWH_PREFIX + i, (float) sConsProfileKwh[i]);
            e.putFloat(PREF_CONS_PROFILE_HOURS_PREFIX + i, (float) sConsProfileHours[i]);
        }
        e.apply();
    }
    public static float getLastSpeedKmh() { return sLastSpeedKmh; }
    /** 1:P  2:R  3:N  4:D */
    public static int getLastGear() { return sLastGear; }
    public static double getDriveGraphEnergyKwh() { return sDriveGraphEnergyKwh; }
    public static double getDriveGraphDistanceKm() { return sDriveGraphDistanceKm; }
    // Hayat boyu sayaç getter'ları
    public static double getLifetimeKm() { return sLifetimeKm; }
    public static double getLifetimeKwh() { return sLifetimeKwh; }
    public static double getLifetimeHours() { return sLifetimeHours; }
    /** Hayat boyu sayaçlarını ve persist değerlerini sıfırla. */
    public static void resetLifetime(Context ctx) {
        sLifetimeKm = 0.0;
        sLifetimeKwh = 0.0;
        sLifetimeHours = 0.0;
        persistLifetimeToPrefs(ctx);
    }
    public static void resetDriveGraphCounters() {
        sDriveGraphEnergyKwh = 0.0;
        sDriveGraphDistanceKm = 0.0;
    }
    // --- Ek uzun dönem profiller API'si ---
    public static int getConsumptionProfileSlots() {
        return CONSUMPTION_PROFILE_SLOTS;
    }
    public static double getConsumptionProfileKm(int index) {
        if (index < 0 || index >= CONSUMPTION_PROFILE_SLOTS) return 0.0;
        return sConsProfileKm[index];
    }
    public static double getConsumptionProfileKwh(int index) {
        if (index < 0 || index >= CONSUMPTION_PROFILE_SLOTS) return 0.0;
        return sConsProfileKwh[index];
    }
    public static double getConsumptionProfileHours(int index) {
        if (index < 0 || index >= CONSUMPTION_PROFILE_SLOTS) return 0.0;
        return sConsProfileHours[index];
    }
    public static String getConsumptionProfileName(int index) {
        if (index < 0 || index >= CONSUMPTION_PROFILE_SLOTS) return "";
        String name = sConsProfileName[index];
        return (name != null) ? name : "";
    }
    public static void setConsumptionProfileName(Context ctx, int index, String name) {
        if (index < 0 || index >= CONSUMPTION_PROFILE_SLOTS) return;
        sConsProfileName[index] = name;
        if (ctx != null && name != null) {
            ctx.getSharedPreferences(PREF_NAME_LIFETIME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(PREF_CONS_PROFILE_NAME_PREFIX + index, name)
                    .apply();
        }
    }
    public static void resetConsumptionProfile(Context ctx, int index) {
        if (index < 0 || index >= CONSUMPTION_PROFILE_SLOTS) {
            return;
        }
        sConsProfileKm[index] = 0.0;
        sConsProfileKwh[index] = 0.0;
        sConsProfileHours[index] = 0.0;
        if (ctx != null) {
            ctx.getSharedPreferences(PREF_NAME_LIFETIME, Context.MODE_PRIVATE)
                    .edit()
                    .remove(PREF_CONS_PROFILE_KM_PREFIX + index)
                    .remove(PREF_CONS_PROFILE_KWH_PREFIX + index)
                    .remove(PREF_CONS_PROFILE_HOURS_PREFIX + index)
                    .apply();
        }
    }
    public static float getDcVoltGlobal() { return sDcVolt; }
    public static float getDcAmpGlobal()  { return sDcAmp; }
    public static float getDcKwGlobal()   { return sDcKw; }
    public static float getAcVoltGlobal() { return sAcVolt; }
    public static float getAcAmpGlobal()  { return sAcAmp; }
    public static float getAcKwGlobal()   { return sAcKw; }

    /** DC batarya voltajı — V. Önce BMS cache (canlı callback), yoksa CPM. */
    public static float getDcVoltage() {
        float v = bmsFloat(PROP_BATT_VOLT);
        if (Float.isNaN(v)) v = getFloatPropertyCPM(PROP_BATT_VOLT, AREA_GLOBAL);
        return v;
    }

    /** DC şarj akımı gerçek — A. Önce BMS cache, yoksa CPM. */
    public static float getDcCurrentActual() {
        float v = bmsFloat(PROP_CHR_AMP_ACT);
        if (Float.isNaN(v)) v = getFloatPropertyCPM(PROP_CHR_AMP_ACT, AREA_GLOBAL);
        return v;
    }

    /** DC şarj akımı beklenen — A. Önce BMS cache, yoksa CPM. */
    public static float getDcCurrentExpected() {
        float v = bmsFloat(PROP_CHR_AMP_EXP);
        if (Float.isNaN(v)) v = getFloatPropertyCPM(PROP_CHR_AMP_EXP, AREA_GLOBAL);
        return v;
    }

    /** AC giriş akımı — A. Önce BMS cache, yoksa CPM. */
    public static float getAcCurrent() {
        float v = bmsFloat(PROP_AC_AMP);
        if (Float.isNaN(v)) v = getFloatPropertyCPM(PROP_AC_AMP, AREA_GLOBAL);
        return v;
    }

    /** AC giriş voltajı — V. Önce BMS cache, yoksa CPM. */
    public static float getAcVoltage() {
        float v = bmsFloat(PROP_AC_VOLT);
        if (Float.isNaN(v)) v = getFloatPropertyCPM(PROP_AC_VOLT, AREA_GLOBAL);
        return v;
    }

    /** BMS / şarj ile ilgili property mi (log spam azaltmak için sadece bunları logluyoruz) */
    private static boolean isBmsPropId(int propId) {
        return (propId >= 0x2160f406 && propId <= 0x2160f43d)  // callback'te gelen 0x2160f406, 0x2160f407, 0x2160f43c, 0x2160f43d
                || (propId >= 0x21400000 && propId <= 0x21410000)
                || propId == PROP_CHG_STATUS || propId == PROP_RANGE;
    }

    /** BMS cache'ten float oku — callback gelmemişse NaN döner */
    private static float bmsFloat(int propId) {
        Object val = sBmsCache.get(propId);
        if (val instanceof Number) return ((Number) val).floatValue();
        return Float.NaN;
    }

    /** Araç şarjda mı? Önce PROP_CHG_STATUS (cache veya polling); yoksa AC/DC akım çıkarımı (uygulama yeniden başladıysa). */
    private static boolean isCharging() {
        Object val = sBmsCache.get(PROP_CHG_STATUS);
        float acA = getAcAmpGlobal();
        float dcA = getDcAmpGlobal();
        float dcV = getDcVoltGlobal();

        // 10 dc şarj, 1 ac şarj, 8 durdu, 5 bağlanıyor, 7 bağlandı şarj olmuyor
        int st = val instanceof Number ? ((Number) val).intValue() : -1;
        if ((st == 1) || (st == 10)) return true;
        // Cache boş veya şarj değil: uygulama yeniden başladıysa callback henüz gelmemiş olabilir → AC/DC fallback
        if (!Float.isNaN(acA) && acA > 0.5f) return true;
        if (!Float.isNaN(dcA) && !Float.isNaN(dcV) && dcV > 200f && dcA <= -1f && sLastSpeedKmh == 0) return true;
        return false;
    }

    /** BMS cache güncellendiğinde çağrılır; şarj ilk tespit edildiğinde başlangıç zamanını kaydedip persist eder. */
    private static void onBmsCacheUpdated() {
        if (!isCharging() || sChargingStartWallMs != 0L) return;
        long now = System.currentTimeMillis();
        sChargingStartWallMs = now;
        if (sAppContext != null) {
            com.drivehub.dort.util.ChargingHistory.saveChargingStart(sAppContext, now);
            if (sLogEnabled) Log.i(TAG, "Şarj başlangıcı kaydedildi (BMS) → persist");
        }
    }

    /** AC girişinden gelen toplam enerji — kWh (şarj boyunca birikir) */
    public static float getAcChargeEnergyKwh() { return sAcChargeEnergyKwh; }

    /** Bataryanın aldığı toplam enerji — kWh (şarj boyunca birikir) */
    public static float getDcChargeEnergyKwh() { return sDcChargeEnergyKwh; }

    /** İstasyon DC teklifi: U × I_beklenen entegrali — kWh (şarj oturumu boyunca) */
    public static float getStationDcChargeEnergyKwh() { return sStationDcChargeEnergyKwh; }

    /**
     * Şarj süresi — ms.
     * Başlangıç: (1) BMS callback şarjı ilk gördüğünde set + persist, (2) yoksa persist'ten geri yükle,
     * (3) yoksa isCharging() true iken ilk çağrıda "şimdi" ile başlat (senin isCharging fonksiyonu da süreyi başlatmak için kullanılır).
     */
    public static long getChargingDurationMs() {
        boolean charging = isCharging();
        long now = System.currentTimeMillis();
        // Şarj görüldüğünde ve henüz başlangıç yoksa: hep "şimdi"den başlat (ekran uyandığında 0 görünsün).
        if (charging && sChargingStartWallMs == 0L) {
            sChargingStartWallMs = now;
            if (sAppContext != null) com.drivehub.dort.util.ChargingHistory.saveChargingStart(sAppContext, now);
        }
        if (charging) {
            return Math.max(0L, now - sChargingStartWallMs);
        } else {
            if (sChargingStartWallMs == 0L) return 0L;
            return Math.max(0L, now - sChargingStartWallMs);
        }
    }

    /** Şu an şarjda mı? (PROP_CHG_STATUS veya AC/DC akım çıkarımı) — UI'da göstermek için. */
    public static boolean isChargingNow() { return isCharging(); }

    /** Şarj başlangıç zamanı (wall clock ms). Geçmiş kaydı için. */
    public static long getChargingStartWallMs() { return sChargingStartWallMs; }

    /** Şarj bittiğinde kayıt alındıktan sonra çağrılır; sonraki seans için sayacı sıfırlar. */
    public static void resetSessionAfterSave() {
        sChargingStartWallMs = 0L;
        sAcChargeEnergyKwh         = 0f;
        sDcChargeEnergyKwh         = 0f;
        sStationDcChargeEnergyKwh  = 0f;
        sLastBmsEventMs      = 0L;
        if (sAppContext != null) com.drivehub.dort.util.ChargingHistory.clearChargingStart(sAppContext);
    }

    /** Enerji ve süre sayaçlarını sıfırla */
    public static void resetEnergy() {
        sAcChargeEnergyKwh         = 0f;
        sDcChargeEnergyKwh         = 0f;
        sStationDcChargeEnergyKwh  = 0f;
        sLastBmsEventMs      = 0L;
        sChargingStartWallMs = 0L;
        if (sLogEnabled) Log.i(TAG, "resetEnergy() çağrıldı");
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
            if (sLogEnabled) Log.i(TAG, "  CPM setInt 0x" + Integer.toHexString(propId)
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
                if (sLogEnabled) Log.i(TAG, "  HVAC setIntProperty 0x" + Integer.toHexString(propId)
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
                    if (sLogEnabled) Log.i(TAG, "  HVAC setBooleanProperty 0x" + Integer.toHexString(propId)
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
                    if (sLogEnabled) Log.i(TAG, "  BMS register metodu: " + n
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
            if (sLogEnabled) Log.i(TAG, "  BMS: callback sınıfı = " + callbackClass.getName()
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
                        if (args != null && (mName.contains("Change") || mName.contains("Event")
                                || mName.contains("Property") || mName.contains("Update") || mName.contains("Bms") || mName.contains("Data"))) {
                            // Tek argüman = CarPropertyValue benzeri (getPropertyId + getValue)
                            if (args.length == 1 && args[0] != null) {
                                if (!sBmsFirstEventLogged) {
                                    sBmsFirstEventLogged = true;
                                    if (sLogEnabled) Log.i(TAG, "BMS callback ilk event args=1 [0]=" + (args[0] != null ? args[0].getClass().getName() : "null"));
                                }
                                Object event = args[0];
                                Class<?> clazz = event.getClass();
                                java.lang.reflect.Method getPropIdM = null;
                                try { getPropIdM = clazz.getMethod("getPropertyId"); } catch (NoSuchMethodException e) {}
                                if (getPropIdM == null) try { getPropIdM = clazz.getMethod("getPropId"); } catch (NoSuchMethodException e) {}
                                java.lang.reflect.Method getValM = null;
                                try { getValM = clazz.getMethod("getValue"); } catch (NoSuchMethodException e) {}
                                if (getValM == null) try { getValM = clazz.getMethod("getFloatValue"); } catch (NoSuchMethodException e) {}
                                if (getValM == null) {
                                    for (java.lang.reflect.Method m : clazz.getMethods()) {
                                        if (m.getParameterCount() == 0 && (m.getName().toLowerCase().contains("value") || m.getName().toLowerCase().contains("float"))) {
                                            Class<?> ret = m.getReturnType();
                                            if (Number.class.isAssignableFrom(ret) || ret == float.class || ret == int.class || ret == double.class
                                                    || ret == float[].class || ret == Float[].class) {
                                                getValM = m;
                                                break;
                                            }
                                        }
                                    }
                                }
                                if (getPropIdM != null && getValM != null) {
                                    try {
                                        Object pidObj = getPropIdM.invoke(event);
                                        int propId = pidObj instanceof Number ? ((Number) pidObj).intValue() : -1;
                                        Object valueObj = getValM.invoke(event);
                                        if (propId < 0 && !sBmsPropIdWarningLogged) {
                                            sBmsPropIdWarningLogged = true;
                                            Log.w(TAG, "BMS parse: propId alınamadı pidObj=" + pidObj + " type=" + (pidObj != null ? pidObj.getClass().getSimpleName() : "null"));
                                        }
                                        Object toCache = null;
                                        if (valueObj instanceof Number) {
                                            toCache = valueObj;
                                        } else if (valueObj instanceof float[]) {
                                            float[] arr = (float[]) valueObj;
                                            if (arr.length > 0) toCache = Float.valueOf(arr[0]);
                                        } else if (valueObj instanceof Float[]) {
                                            Float[] arr = (Float[]) valueObj;
                                            if (arr.length > 0 && arr[0] != null) toCache = arr[0];
                                        } else if (valueObj instanceof int[]) {
                                            int[] arr = (int[]) valueObj;
                                            if (arr.length > 0) toCache = Integer.valueOf(arr[0]);
                                        } else if (valueObj != null && valueObj.getClass().isArray() && java.lang.reflect.Array.getLength(valueObj) > 0) {
                                            Object first = java.lang.reflect.Array.get(valueObj, 0);
                                            if (first instanceof Number) toCache = (Number) first;
                                        }
                                        if (propId >= 0 && toCache instanceof Number) {
                                            sBmsCache.put(propId, toCache);
                                            //if (isBmsPropId(propId)) {
                                            //    Log.i(TAG, "BMS CACHE OK 0x" + Integer.toHexString(propId) + " = " + toCache);
                                            //}
                                            if (propId == PROP_BATT_VOLT) {
                                                sLastBmsEventMs = android.os.SystemClock.elapsedRealtime();
                                            }
                                            onBmsCacheUpdated();
                                        } else if (isBmsPropId(propId)) {
                                            Log.w(TAG, "BMS parse value tipi desteklenmiyor: propId=0x" + Integer.toHexString(propId) + " value=" + (valueObj != null ? valueObj.getClass().getName() : "null"));
                                        }
                                    } catch (Exception ex) {
                                        Log.w(TAG, "BMS CarPropertyValue parse hata: " + ex.getMessage());
                                    }
                                } else if (!sBmsParseWarningLogged) {
                                    sBmsParseWarningLogged = true;
                                    if (getPropIdM == null) Log.w(TAG, "BMS parse: getPropertyId/getPropId metodu bulunamadı");
                                    if (getValM == null) {
                                        StringBuilder sb = new StringBuilder("BMS parse: getValue/getFloatValue yok. value* metodları: ");
                                        for (java.lang.reflect.Method m : clazz.getMethods()) {
                                            if (m.getParameterCount() == 0 && m.getName().toLowerCase().contains("value"))
                                                sb.append(m.getName()).append(" ");
                                        }
                                        Log.w(TAG, sb.toString());
                                    }
                                }
                            } else if (args.length >= 2) {
                            try {
                                // propId: args içinde BMS/araç property int'ini ara (0x2140xxxx, 0x2160xxxx vb.)
                                int propId = -1;
                                for (Object a : args) {
                                    if (a instanceof Integer) {
                                        int i = (Integer) a;
                                        if ((i >= 0x21400000 && i <= 0x21410000) || (i >= 0x21600000 && i <= 0x21600200)) {
                                            propId = i;
                                            break;
                                        }
                                    }
                                }
                                // value: Number veya getValue() ile al
                                Object rawVal = null;
                                for (Object a : args) {
                                    if (a instanceof Number) {
                                        rawVal = a;
                                        break;
                                    }
                                    if (a != null && !(a instanceof Integer) && !(a instanceof Long)) {
                                        try {
                                            java.lang.reflect.Method getVal = a.getClass().getMethod("getValue");
                                            Object v = getVal.invoke(a);
                                            if (v instanceof Number) {
                                                rawVal = v;
                                                break;
                                            }
                                        } catch (Exception ignored) {}
                                        try {
                                            java.lang.reflect.Method getVal = a.getClass().getMethod("getFloatValue");
                                            Object v = getVal.invoke(a);
                                            if (v instanceof Number) {
                                                rawVal = v;
                                                break;
                                            }
                                        } catch (Exception ignored) {}
                                    }
                                }
                                if (propId >= 0 && rawVal instanceof Number) {
                                    sBmsCache.put(propId, rawVal);
                                    //Log.i(TAG, "BMS CACHE OK 0x" + Integer.toHexString(propId) + " = " + rawVal);
                                    if (propId == PROP_BATT_VOLT) {
                                        sLastBmsEventMs = android.os.SystemClock.elapsedRealtime();
                                    }
                                    onBmsCacheUpdated();
                                } else {
                                    Log.w(TAG, "BMS callback SKIP: propId=" + propId + " rawVal=" + rawVal);
                                }
                            } catch (Exception ex) {
                                Log.w(TAG, "BMS callback parse hata: " + ex.getMessage());
                            }
                            } // args.length >= 2
                        }
                        // equals/hashCode/toString — Object metodları için varsayılan dönüş
                        if ("equals".equals(mName)) return args != null && args.length == 1 && proxyObj == args[0];
                        if ("hashCode".equals(mName)) return System.identityHashCode(proxyObj);
                        if ("toString".equals(mName)) return "BmsCallbackProxy";
                        return null;
                    });

            registerMethod.invoke(bmsManager, proxy);
            if (sLogEnabled) Log.i(TAG, "  ✓ BMS callback kayıt edildi");

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
                    if (sLogEnabled) Log.i(TAG, "  HVAC register metodu: " + m.getName()
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
            if (sLogEnabled) Log.i(TAG, "  HVAC: callback sınıfı = " + callbackClass.getName()
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
            if (sLogEnabled) Log.i(TAG, "  ✓ HVAC callback kayıt edildi");

        } catch (Exception e) {
            Log.w(TAG, "  HVAC registerCallback hata: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * BMS gibi: vehicle / advanced_assisted_driving manager'ına callback kayıt eder.
     * onChangeEvent(557883772, area, value) gelince cache + listener güncellenir; logcat'te görünür.
     */
    private static void registerDriveModeCallbackFromVehicleManager(Object manager) {
        if (manager == null) return;
        try {
            java.lang.reflect.Method registerMethod = null;
            for (java.lang.reflect.Method m : manager.getClass().getMethods()) {
                String n = m.getName();
                if (n.contains("register") || n.contains("Register")) {
                    if (sLogEnabled) Log.i(TAG, "  VehicleManager register: " + n);
                    if (registerMethod == null) registerMethod = m;
                }
            }
            if (registerMethod == null) {
                Log.w(TAG, "  VehicleManager: registerCallback bulunamadı");
                return;
            }
            Class<?> callbackClass = registerMethod.getParameterTypes()[0];
            if (!callbackClass.isInterface()) {
                Log.w(TAG, "  VehicleManager: callback interface değil");
                return;
            }
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    callbackClass.getClassLoader(),
                    new Class<?>[]{ callbackClass },
                    (proxyObj, method, args) -> {
                        String mName = method.getName();
                        if (args == null || (!mName.contains("Change") && !mName.contains("Event") && !mName.contains("Property"))) {
                            if ("equals".equals(mName)) return false;
                            if ("hashCode".equals(mName)) return System.identityHashCode(proxyObj);
                            if ("toString".equals(mName)) return "DriveModeVehicleCallbackProxy";
                            return null;
                        }
                        Integer propId = null;
                        Integer value = null;
                        if (args.length >= 3 && args[0] instanceof Number && args[2] instanceof Number) {
                            propId = ((Number) args[0]).intValue();
                            value = ((Number) args[2]).intValue();
                        } else if (args.length == 1 && args[0] != null) {
                            Object ev = args[0];
                            try {
                                java.lang.reflect.Method getPid = ev.getClass().getMethod("getPropertyId");
                                java.lang.reflect.Method getVal = null;
                                try { getVal = ev.getClass().getMethod("getIntValue"); } catch (NoSuchMethodException e) {}
                                if (getVal == null) getVal = ev.getClass().getMethod("getValue");
                                propId = ((Number) getPid.invoke(ev)).intValue();
                                value = ((Number) getVal.invoke(ev)).intValue();
                            } catch (Exception ignored) {}
                        } else if (args.length >= 2) {
                            for (Object a : args) {
                                if (a instanceof Number) {
                                    if (propId == null) propId = ((Number) a).intValue();
                                    else if (value == null) value = ((Number) a).intValue();
                                }
                            }
                        }
                        if (propId != null && value != null) {
                            if (propId == PROP_DRIVE_MODE) {
                                sCachedDriveMode = value;
                                Log.i(TAG, "  Sürüş modu (vehicle manager) 0x" + Integer.toHexString(propId) + " → " + value + " (DriveHub Dort abonesi)");
                                DriveModeListener listener = sDriveModeListener;
                                if (listener != null) listener.onDriveModeChanged(value);
                            } else if (propId == PROP_REGEN_LEVEL) {
                                sCachedRegenLevel = value;
                                if (sLogEnabled) Log.i(TAG, "  Regen seviyesi (vehicle manager) 0x" + Integer.toHexString(propId) + " → " + value);
                            }
                        }
                        return null;
                    });
            registerMethod.invoke(manager, proxy);
            Log.i(TAG, "  ✓ Sürüş modu + regen vehicle manager callback kayıt edildi (BMS gibi)");
        } catch (Exception e) {
            Log.w(TAG, "  VehicleManager registerDriveMode hata: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * CarPropertyManager'a callback kayıt eder ve sürüş modu (PROP_DRIVE_MODE) değişince
     * sDriveModeListener'ı tetikler.
     */
    private static void registerDriveModeCallback(Object carPropertyManager) {
        if (carPropertyManager == null) return;
        try {
            java.lang.reflect.Method[] methods = carPropertyManager.getClass().getMethods();
            java.lang.reflect.Method registerMethod = null;
            for (java.lang.reflect.Method m : methods) {
                String n = m.getName();
                if (n.contains("register") || n.contains("Register")) {
                    if (sLogEnabled) Log.i(TAG, "  CPM register metodu: " + n
                            + " params=" + java.util.Arrays.toString(m.getParameterTypes()));
                    if (registerMethod == null) registerMethod = m;
                }
            }

            if (registerMethod == null) {
                Log.w(TAG, "  CPM: registerCallback metodu bulunamadı — drive mode callback devre dışı");
                return;
            }

            Class<?>[] paramTypes = registerMethod.getParameterTypes();
            if (paramTypes.length == 0) {
                Log.w(TAG, "  CPM: register metodu parametre almıyor — atlanıyor");
                return;
            }

            Class<?> callbackClass = paramTypes[0];
            if (!callbackClass.isInterface()) {
                Log.w(TAG, "  CPM: callback sınıfı interface değil — proxy oluşturulamaz");
                return;
            }

            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    callbackClass.getClassLoader(),
                    new Class<?>[]{ callbackClass },
                    (proxyObj, method, args) -> {
                        String mName = method.getName();
                        if (mName.contains("Change") || mName.contains("Property")
                                || mName.contains("Event") || mName.contains("Update")) {
                            DriveModeListener listener = sDriveModeListener;
                            if (listener != null && args != null) {
                                Integer propId = null;
                                Integer value  = null;

                                // Case 1: Tek argüman CarPropertyValue benzeri obje
                                if (args.length == 1 && args[0] != null) {
                                    Object event = args[0];
                                    Class<?> clazz = event.getClass();
                                    java.lang.reflect.Method getPropIdM = null;
                                    java.lang.reflect.Method getValM    = null;
                                    try { getPropIdM = clazz.getMethod("getPropertyId"); } catch (NoSuchMethodException ignored) {}
                                    if (getPropIdM == null) try { getPropIdM = clazz.getMethod("getPropId"); } catch (NoSuchMethodException ignored) {}
                                    try { getValM = clazz.getMethod("getIntValue"); } catch (NoSuchMethodException ignored) {}
                                    if (getValM == null) try { getValM = clazz.getMethod("getValue"); } catch (NoSuchMethodException ignored) {}
                                    if (getPropIdM != null && getValM != null) {
                                        try {
                                            Object pidObj = getPropIdM.invoke(event);
                                            Object valObj = getValM.invoke(event);
                                            if (pidObj instanceof Number) propId = ((Number) pidObj).intValue();
                                            if (valObj instanceof Number) value = ((Number) valObj).intValue();
                                        } catch (Exception ignored) {}
                                    }
                                } else if (args.length >= 3) {
                                    // Case 2: log formatı onChangeEvent,PropID,area,value → (propId, area, value)
                                    if (args[0] instanceof Number) propId = ((Number) args[0]).intValue();
                                    if (args[2] instanceof Number) value  = ((Number) args[2]).intValue();
                                } else if (args.length >= 2) {
                                    for (Object a : args) {
                                        if (a instanceof Integer) {
                                            if (propId == null) propId = (Integer) a;
                                            else if (value == null) value = (Integer) a;
                                        }
                                    }
                                }

                                if (propId != null && value != null) {
                                    if (propId == PROP_DRIVE_MODE) {
                                        sCachedDriveMode = value;
                                        if (listener != null) listener.onDriveModeChanged(value);
                                    } else if (propId == PROP_REGEN_LEVEL) {
                                        sCachedRegenLevel = value;
                                    }
                                }
                            }
                        }
                        if ("equals".equals(mName)) return args != null && args.length == 1 && proxyObj == args[0];
                        if ("hashCode".equals(mName)) return System.identityHashCode(proxyObj);
                        if ("toString".equals(mName)) return "DriveModeCallbackProxy";
                        return null;
                    });

            registerMethod.invoke(carPropertyManager, proxy);
            if (sLogEnabled) Log.i(TAG, "  ✓ CPM drive mode + regen callback kayıt edildi");

        } catch (Exception e) {
            Log.w(TAG, "  CPM registerDriveModeCallback hata: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void logAvailableVehicleServices() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            java.lang.reflect.Method listServices = sm.getMethod("listServices");
            String[] services = (String[]) listServices.invoke(null);
            if (services == null) { Log.w(TAG, "listServices null"); return; }
            if (sLogEnabled) Log.i(TAG, "Servisler toplam: " + services.length);
            if (sLogEnabled) Log.i(TAG, "Servisler (vehicle/air/saic/setting/car içerenler):");
            int found = 0;
            for (String s : services) {
                if (s != null && (s.toLowerCase().contains("vehicle")
                        || s.toLowerCase().contains("air")
                        || s.toLowerCase().contains("saic")
                        || s.toLowerCase().contains("setting")
                        || s.toLowerCase().contains("car"))) {
                    if (sLogEnabled) Log.i(TAG, "  [servis] " + s);
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
            if (sLogEnabled) Log.i(TAG, "  Binder TX=" + txId + " value=" + value
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
