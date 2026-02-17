package com.example.mg4_2.hardware;

import android.car.Car;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import com.example.mg4_2.model.DriveMode;
import com.example.mg4_2.model.RegenLevel;

public class MG4Hardware {

    private static final String TAG = "MG4_HW";

    // Property ID'leri — logcat setProperty propId değerlerinden doğrulandı
    public static final int PROP_DRIVE_MODE  = 0x2140a17c; // 557883772
    public static final int PROP_REGEN_LEVEL = 0x2140a191; // 557883793
    public static final int PROP_ONE_PEDAL   = 0x2140a193; // 557883795

    // Area ID — logcat onChangeEvent alanından doğrulandı
    public static final int AREA_GLOBAL = 0x01000000; // 16777216

    private static Car                sCar;
    private static CarPropertyManager sCarPropertyManager;
    private static Context            sContext;
    private static boolean            sInitialized = false;

    private static final ServiceConnection sServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "onServiceConnected: " + name);
            try {
                sCarPropertyManager = (CarPropertyManager)
                        sCar.getCarManager(Car.PROPERTY_SERVICE);
                if (sCarPropertyManager != null) {
                    Log.i(TAG, "CarPropertyManager bağlandı ✓");
                } else {
                    Log.e(TAG, "CarPropertyManager null döndü!");
                }
            } catch (Exception e) {
                Log.e(TAG, "onServiceConnected hatası: " + e.getMessage(), e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "Car servisi bağlantısı kesildi, yeniden bağlanılıyor...");
            sCarPropertyManager = null;
            // Yeniden bağlan
            if (sContext != null) {
                connectCar(sContext);
            }
        }
    };

    public static void init(Context context) {
        if (sInitialized) {
            Log.d(TAG, "init() zaten çağrıldı, atlanıyor");
            return;
        }
        sContext = context.getApplicationContext();
        sInitialized = true;
        connectCar(sContext);
    }

    private static void connectCar(Context context) {
        try {
            // Yöntem 1: createCar(Context, ServiceConnection)
            sCar = Car.createCar(context, sServiceConnection);
            Log.i(TAG, "Car.createCar() başarılı, onServiceConnected bekleniyor...");
        } catch (Exception e) {
            Log.e(TAG, "createCar hatası: " + e.getMessage() + " — bindService deneniyor...", e);
            // Yöntem 2: direkt bindService ile Car servisine bağlan
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(
                        "com.android.car",
                        "com.android.car.CarService"));
                boolean bound = context.bindService(intent, sServiceConnection,
                        Context.BIND_AUTO_CREATE);
                Log.i(TAG, "bindService(CarService) → " + bound);
            } catch (Exception e2) {
                Log.e(TAG, "bindService hatası: " + e2.getMessage(), e2);
            }
        }
    }

    public static boolean isReady() {
        return sCarPropertyManager != null;
    }

    public static void destroy() {
        sInitialized = false;
        try {
            if (sCar != null && sCar.isConnected()) {
                sCar.disconnect();
                Log.i(TAG, "Car bağlantısı kesildi");
            }
        } catch (Exception e) {
            Log.e(TAG, "Car.disconnect() hatası: " + e.getMessage());
        }
        sCar = null;
        sCarPropertyManager = null;
        sContext = null;
    }

    public static boolean setDriveMode(DriveMode mode) {
        Log.i(TAG, "setDriveMode → " + mode.label + " (" + mode.value + ")");
        return setIntProperty(PROP_DRIVE_MODE, mode.value);
    }

    public static boolean setRegenLevel(RegenLevel level) {
        Log.i(TAG, "setRegenLevel → " + level.label + " (" + level.value + ")");
        return setIntProperty(PROP_REGEN_LEVEL, level.value);
    }

    public static boolean setOnePedal(boolean enabled) {
        Log.i(TAG, "setOnePedal → " + (enabled ? "Açık" : "Kapalı"));
        return setIntProperty(PROP_ONE_PEDAL, enabled ? 1 : 0);
    }

    public static int getDriveMode()  { return getIntProperty(PROP_DRIVE_MODE);  }
    public static int getRegenLevel() { return getIntProperty(PROP_REGEN_LEVEL); }
    public static int getOnePedal()   { return getIntProperty(PROP_ONE_PEDAL);   }

    private static boolean setIntProperty(int propId, int value) {
        if (sCarPropertyManager == null) {
            Log.e(TAG, "CarPropertyManager henüz hazır değil!");
            return false;
        }
        try {
            sCarPropertyManager.setIntProperty(propId, AREA_GLOBAL, value);
            Log.i(TAG, "setIntProperty 0x" + Integer.toHexString(propId)
                    + " value=" + value + " → OK");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "setIntProperty 0x" + Integer.toHexString(propId)
                    + " hatası: " + e.getMessage(), e);
            return false;
        }
    }

    private static int getIntProperty(int propId) {
        if (sCarPropertyManager == null) {
            Log.e(TAG, "CarPropertyManager hazır değil!");
            return -1;
        }
        try {
            CarPropertyValue<Integer> val =
                    sCarPropertyManager.getProperty(Integer.class, propId, AREA_GLOBAL);
            if (val != null) {
                int result = val.getValue();
                Log.i(TAG, "getIntProperty 0x" + Integer.toHexString(propId) + " → " + result);
                return result;
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "getIntProperty 0x" + Integer.toHexString(propId)
                    + " hatası: " + e.getMessage(), e);
            return -1;
        }
    }
}
