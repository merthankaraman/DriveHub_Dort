package com.example.mg4_2.hardware;

import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import com.example.mg4_2.model.DriveMode;
import com.example.mg4_2.model.RegenLevel;

import java.lang.reflect.Method;

public class MG4Hardware {

    private static final String TAG = "MG4_HW";

    public static final String SERVICE_VEHICLE_SETTING = "vehiclesetting";
    public static final String SERVICE_AIR_CONDITION   = "aircondition";

    private static final String TOKEN_VEHICLE_SETTING =
            "com.saicmotor.sdk.vehiclesettings.IVehicleSettingService";
    private static final String TOKEN_AIR_CONDITION =
            "com.saicmotor.sdk.vehiclesettings.IAirConditionService";

    public static final int AREA_EH32 = 16777216; // 0x01000000

    private static final int TX_SET_DRIVE_MODE    = 151;
    private static final int TX_SET_REGEN_LEVEL   = 180;
    private static final int TX_SET_STEERING_HEAT = 52;
    private static final int TX_SET_ONE_PEDAL     = 181;

    public static boolean setDriveMode(DriveMode mode) {
        Log.i(TAG, "setDriveMode → " + mode.label + " (" + mode.value + ")");
        return sendToVehicleSetting(TX_SET_DRIVE_MODE, mode.value);
    }

    public static boolean setRegenLevel(RegenLevel level) {
        Log.i(TAG, "setRegenLevel → " + level.label + " (" + level.value + ")");
        return sendToVehicleSetting(TX_SET_REGEN_LEVEL, level.value);
    }

    public static boolean setOnePedal(boolean enabled) {
        Log.i(TAG, "setOnePedal → " + (enabled ? "Açık" : "Kapalı"));
        return sendToVehicleSetting(TX_SET_ONE_PEDAL, enabled ? 1 : 0);
    }

    public static boolean setSteeringHeat(boolean enabled) {
        Log.i(TAG, "setSteeringHeat → " + (enabled ? "Açık" : "Kapalı"));
        return sendToAirCondition(TX_SET_STEERING_HEAT, enabled ? 1 : 0);
    }

    public static boolean isServiceAvailable(String serviceName) {
        IBinder b = getService(serviceName);
        return b != null && b.isBinderAlive();
    }

    private static boolean sendToVehicleSetting(int txId, int value) {
        IBinder binder = getService(SERVICE_VEHICLE_SETTING);
        if (binder == null) {
            Log.e(TAG, "vehiclesetting servisi bulunamadı!");
            return false;
        }
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TOKEN_VEHICLE_SETTING);
            data.writeInt(AREA_EH32); // Alan ID — zorunlu
            data.writeInt(1);          // Count — KRİTİK!
            data.writeInt(value);
            data.writeFloatArray(new float[0]);
            data.writeByteArray(new byte[0]);

            boolean ok = binder.transact(txId, data, reply, 0);
            if (ok) reply.readException();
            Log.i(TAG, "vehiclesetting TX=" + txId + " VAL=" + value + " → " + (ok ? "OK" : "FAIL"));
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "vehiclesetting hata: " + e.getMessage(), e);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static boolean sendToAirCondition(int txId, int value) {
        IBinder binder = getService(SERVICE_AIR_CONDITION);
        if (binder == null) {
            Log.e(TAG, "aircondition servisi bulunamadı!");
            return false;
        }
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TOKEN_AIR_CONDITION);
            data.writeInt(1);
            data.writeInt(value);

            boolean ok = binder.transact(txId, data, reply, 0);
            if (ok) reply.readException();
            Log.i(TAG, "aircondition TX=" + txId + " VAL=" + value + " → " + (ok ? "OK" : "FAIL"));
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "aircondition hata: " + e.getMessage(), e);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static IBinder getService(String name) {
        try {
            Class<?> sm  = Class.forName("android.os.ServiceManager");
            Method   get = sm.getMethod("getService", String.class);
            return (IBinder) get.invoke(null, name);
        } catch (Exception e) {
            Log.e(TAG, "getService(" + name + ") hata: " + e.getMessage());
            return null;
        }
    }
}