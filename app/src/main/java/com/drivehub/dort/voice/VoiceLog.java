package com.drivehub.dort.voice;

import android.util.Log;

import com.drivehub.dort.hardware.MG4Hardware;

/**
 * Sesli asistan logları — yalnızca {@link MG4Hardware#isLogEnabled()} açıkken Logcat'e yazar
 * (Ayarlar / detay log ile aynı anahtar).
 */
public final class VoiceLog {

    public static final String TAG_ASST = "VOICE_ASST";
    public static final String TAG_GRAMMAR = "VOICE_GRAMMAR";
    public static final String TAG_CMD = "VOICE_CMD";

    private VoiceLog() {}

    public static void d(String tag, String msg) {
        if (MG4Hardware.isLogEnabled()) {
            Log.d(tag, msg);
        }
    }

    public static void i(String tag, String msg) {
        if (MG4Hardware.isLogEnabled()) {
            Log.i(tag, msg);
        }
    }

    public static void w(String tag, String msg) {
        if (MG4Hardware.isLogEnabled()) {
            Log.w(tag, msg);
        }
    }

    public static void e(String tag, String msg) {
        if (MG4Hardware.isLogEnabled()) {
            Log.e(tag, msg);
        }
    }

    public static void e(String tag, String msg, Throwable tr) {
        if (MG4Hardware.isLogEnabled()) {
            Log.e(tag, msg, tr);
        }
    }
}
