package com.example.mg4_2.hardware;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import com.example.mg4_2.model.DriveMode;
import com.example.mg4_2.model.RegenLevel;

public class MG4Hardware {

    private static final String TAG = "MG4_HW";

    private static final String DESCRIPTOR_VEHICLE =
            "com.saicmotor.sdk.vehiclesettings.IVehicleSettingService";
    private static final String DESCRIPTOR_AC =
            "com.saicmotor.sdk.vehiclesettings.IAirConditionService";

    // Setter TX ID'ler (APK decompile ile doğrulandı)
    private static final int TX_SET_DRIVE_MODE         = 151;
    private static final int TX_SET_REGEN_LEVEL        = 180;
    private static final int TX_SET_ONE_PEDAL          = 181;
    private static final int TX_SET_REGEN_BRAKE_SWITCH = 182;
    private static final int TX_SET_STEERING_HEAT      = 52;  // aircondition servisi

    // Getter TX ID'ler — reply formatı: [status:int][value:int]
    private static final int TX_GET_DRIVE_MODE  = 152;
    private static final int TX_GET_REGEN_LEVEL = 183; // tahmin — logda doğrulanacak
    private static final int TX_GET_ONE_PEDAL   = 184; // tahmin — logda doğrulanacak

    private static final int AREA_ID = 0x01000000;
    private static final int COUNT   = 1;

    private static IBinder sVehicleBinder;
    private static IBinder sAcBinder;
    private static boolean sInitialized = false;

    // -------------------------------------------------------------------------
    // Init / Destroy
    // -------------------------------------------------------------------------

    public static void init(Context context) {
        if (sInitialized) return;
        sInitialized = true;

        Log.i(TAG, "========================================");
        Log.i(TAG, "=== MG4Hardware.init() ===");
        Log.i(TAG, "  uid=" + android.os.Process.myUid()
                + " pid=" + android.os.Process.myPid());
        Log.i(TAG, "  sdk=" + android.os.Build.VERSION.SDK_INT
                + " device=" + android.os.Build.DEVICE
                + " model=" + android.os.Build.MODEL);

        // Servis listesini logla — servis adı yanlışsa buradan anlaşılır
        logAvailableVehicleServices();

        sVehicleBinder = getService("vehiclesetting");
        sAcBinder      = getService("aircondition");

        if (sVehicleBinder != null) {
            Log.i(TAG, "  ✓ vehiclesetting bağlı: " + sVehicleBinder.getClass().getName());
            // Binder descriptor'ını doğrula
            try {
                String iface = sVehicleBinder.getInterfaceDescriptor();
                Log.i(TAG, "  ✓ vehiclesetting descriptor: " + iface);
                if (!DESCRIPTOR_VEHICLE.equals(iface)) {
                    Log.w(TAG, "  !! DESCRIPTOR UYUMSUZ! Beklenen: " + DESCRIPTOR_VEHICLE);
                }
            } catch (Exception e) {
                Log.w(TAG, "  descriptor alınamadı: " + e.getMessage());
            }
            // İlk bağlantıda mevcut değerleri oku (senkronizasyon için)
            readAndLogCurrentState();
        } else {
            Log.e(TAG, "  ✗ vehiclesetting NULL — olası sebepler:");
            Log.e(TAG, "    1) SELinux: uid.system imzası gerekiyor");
            Log.e(TAG, "    2) Servis adı yanlış (adb shell service list | grep -i vehicle)");
            Log.e(TAG, "    3) Araç kapalı/başlatılmamış");
        }

        if (sAcBinder != null) {
            Log.i(TAG, "  ✓ aircondition bağlı: " + sAcBinder.getClass().getName());
        } else {
            Log.e(TAG, "  ✗ aircondition NULL");
        }
        Log.i(TAG, "========================================");
    }

    public static boolean isReady() {
        return sVehicleBinder != null;
    }

    public static void destroy() {
        sVehicleBinder = null;
        sAcBinder = null;
        sInitialized = false;
        Log.i(TAG, "destroy()");
    }

    // -------------------------------------------------------------------------
    // Setter'lar
    // -------------------------------------------------------------------------

    public static boolean setDriveMode(DriveMode mode) {
        Log.i(TAG, "setDriveMode → " + mode.label + " (value=" + mode.value + ")");
        boolean ok = transact(sVehicleBinder, DESCRIPTOR_VEHICLE, TX_SET_DRIVE_MODE, mode.value);
        if (ok) {
            // Doğrulama: geri oku
            int actual = getDriveModeRaw();
            if (actual >= 0) {
                Log.i(TAG, "  doğrulama: araçtaki değer=" + actual
                        + (actual == mode.value ? " ✓ EŞLEŞTI" : " ✗ FARKLI! (beklenen=" + mode.value + ")"));
            }
        }
        return ok;
    }

    public static boolean setRegenLevel(RegenLevel level) {
        Log.i(TAG, "setRegenLevel → " + level.label + " (value=" + level.value + ")");
        // Adım 1: ana regen switch
        boolean sw = transact(sVehicleBinder, DESCRIPTOR_VEHICLE,
                TX_SET_REGEN_BRAKE_SWITCH, level != RegenLevel.OFF ? 1 : 0);
        Log.i(TAG, "  regenBrakeSwitch → " + (sw ? "OK" : "FAIL"));
        if (level == RegenLevel.OFF) return sw;
        // Adım 2: seviye
        boolean ok = transact(sVehicleBinder, DESCRIPTOR_VEHICLE, TX_SET_REGEN_LEVEL, level.value);
        Log.i(TAG, "  regenLevel=" + level.value + " → " + (ok ? "OK" : "FAIL"));
        return ok;
    }

    public static boolean setOnePedal(boolean enabled) {
        Log.i(TAG, "setOnePedal → " + (enabled ? "Açık" : "Kapalı"));
        return transact(sVehicleBinder, DESCRIPTOR_VEHICLE, TX_SET_ONE_PEDAL, enabled ? 1 : 0);
    }

    public static boolean setSteeringHeat(boolean enabled) {
        Log.i(TAG, "setSteeringHeat → " + (enabled ? "Açık" : "Kapalı"));
        return transact(sAcBinder, DESCRIPTOR_AC, TX_SET_STEERING_HEAT, enabled ? 1 : 0);
    }

    // -------------------------------------------------------------------------
    // Getter'lar
    // -------------------------------------------------------------------------

    public static int getDriveMode()  { return getDriveModeRaw(); }
    public static int getRegenLevel() { return getIntValue(sVehicleBinder, DESCRIPTOR_VEHICLE, TX_GET_REGEN_LEVEL); }
    public static int getOnePedal()   { return getIntValue(sVehicleBinder, DESCRIPTOR_VEHICLE, TX_GET_ONE_PEDAL); }

    private static int getDriveModeRaw() {
        return getIntValue(sVehicleBinder, DESCRIPTOR_VEHICLE, TX_GET_DRIVE_MODE);
    }

    // -------------------------------------------------------------------------
    // Tanı — başlangıçta mevcut durumu logla
    // -------------------------------------------------------------------------

    private static void readAndLogCurrentState() {
        Log.i(TAG, "--- Mevcut araç durumu okunuyor ---");
        int dm = getDriveModeRaw();
        Log.i(TAG, "  getDriveMode  (TX=" + TX_GET_DRIVE_MODE + ") → " + dm
                + (dm >= 0 ? " (" + DriveMode.fromValue(dm).label + ")" : " (okunamadı)"));

        int rg = getIntValue(sVehicleBinder, DESCRIPTOR_VEHICLE, TX_GET_REGEN_LEVEL);
        Log.i(TAG, "  getRegenLevel (TX=" + TX_GET_REGEN_LEVEL + ") → " + rg
                + (rg >= 0 ? " (" + RegenLevel.fromValue(rg).label + ")" : " (okunamadı/TX yanlış)"));

        int op = getIntValue(sVehicleBinder, DESCRIPTOR_VEHICLE, TX_GET_ONE_PEDAL);
        Log.i(TAG, "  getOnePedal   (TX=" + TX_GET_ONE_PEDAL + ") → " + op
                + (op >= 0 ? " (" + (op == 1 ? "Açık" : "Kapalı") + ")" : " (okunamadı/TX yanlış)"));
        Log.i(TAG, "-----------------------------------");
    }

    /**
     * Araçta kayıtlı servisleri tara — "vehiclesetting" adını doğrulamak için.
     * adb shell service list çıktısının log eşdeğeri.
     */
    private static void logAvailableVehicleServices() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            java.lang.reflect.Method listServices = sm.getMethod("listServices");
            String[] services = (String[]) listServices.invoke(null);
            if (services == null) {
                Log.w(TAG, "listServices() null döndü");
                return;
            }
            Log.i(TAG, "Araç servis listesi (vehicle/air içerenler):");
            for (String s : services) {
                if (s != null && (s.toLowerCase().contains("vehicle")
                        || s.toLowerCase().contains("air")
                        || s.toLowerCase().contains("saic")
                        || s.toLowerCase().contains("setting"))) {
                    Log.i(TAG, "  [servis] " + s);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "listServices hata: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Binder yardımcıları
    // -------------------------------------------------------------------------

    private static IBinder getService(String name) {
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

    /**
     * Protokol (CLAUDE.md):
     *   writeInterfaceToken(descriptor)
     *   writeInt(AREA_ID)    // 0x01000000 ZORUNLU
     *   writeInt(COUNT)      // 1 ZORUNLU
     *   writeInt(value)
     *   writeFloatArray([])
     *   writeByteArray([])
     *
     * reply: [status:int] — 0=başarı
     */
    private static boolean transact(IBinder binder, String descriptor, int txId, int value) {
        if (binder == null) {
            Log.w(TAG, "transact TX=" + txId + " — binder null");
            return false;
        }
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            data.writeInt(AREA_ID);
            data.writeInt(COUNT);
            data.writeInt(value);
            data.writeFloatArray(new float[0]);
            data.writeByteArray(new byte[0]);

            binder.transact(txId, data, reply, 0);

            // reply'ın tamamını logla (ilk 4 int) — format yanlışsa buradan anlaşılır
            int replySize = reply.dataAvail();
            int status = reply.readInt();
            Log.i(TAG, "  TX=" + txId + " value=" + value
                    + " → status=" + status
                    + " replyBytes=" + replySize
                    + (status != 0 ? " ✗ ARAÇ REDDETTİ" : " ✓"));

            // Status != 0 ise reply'da hata kodu veya mesaj olabilir — logla
            if (status != 0 && reply.dataAvail() > 0) {
                try {
                    int extra = reply.readInt();
                    Log.w(TAG, "    reply[1]=" + extra + " (hata kodu olabilir)");
                } catch (Exception ignored) {}
            }
            return status == 0;
        } catch (Exception e) {
            Log.e(TAG, "  TX=" + txId + " EXCEPTION: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static int getIntValue(IBinder binder, String descriptor, int txId) {
        if (binder == null) return -1;
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            data.writeInt(AREA_ID);
            binder.transact(txId, data, reply, 0);
            int replySize = reply.dataAvail();
            int status = reply.readInt();
            if (status == 0 && reply.dataAvail() >= 4) {
                int result = reply.readInt();
                Log.i(TAG, "  GET TX=" + txId + " → status=0 value=" + result
                        + " replyBytes=" + replySize + " ✓");
                return result;
            }
            Log.w(TAG, "  GET TX=" + txId + " → status=" + status
                    + " replyBytes=" + replySize
                    + (reply.dataAvail() < 4 ? " (reply çok kısa — TX yanlış olabilir)" : ""));
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "  GET TX=" + txId + " EXCEPTION: " + e.getMessage());
            return -1;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
