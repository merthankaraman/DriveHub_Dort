package com.drivehub.dort.ota;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.util.Locale;

/** İndirilenler’deki eski DriveHub OTA APK’larını ve cache’deki staged paketi temizler. */
final class OtaCleanup {

    private static final String TAG = "MG4_OTA";

    private OtaCleanup() {
    }

    /** Tüm OTA APK / hash dosyalarını sil (kurulum sonrası). */
    static void deleteAllOtaApks(Context context) {
        deleteOldApks(null);
        deleteStagedCache(context);
    }

    /**
     * Eski OTA paketlerini sil.
     * @param keep silinmeyecek dosya (şu an indirilen / kurulan); null = hepsini sil
     */
    static void deleteOldApks(File keep) {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null || !dir.isDirectory()) {
            Log.w(TAG, "cleanup: Downloads yok");
            return;
        }
        File[] files = dir.listFiles((d, name) -> isOtaDownloadName(name));
        if (files == null || files.length == 0) return;

        String keepPath = null;
        try {
            if (keep != null) keepPath = keep.getCanonicalPath();
        } catch (Exception ignored) {
            if (keep != null) keepPath = keep.getAbsolutePath();
        }

        int deleted = 0;
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            try {
                if (keepPath != null && keepPath.equals(f.getCanonicalPath())) continue;
            } catch (Exception e) {
                if (keepPath != null && keepPath.equals(f.getAbsolutePath())) continue;
            }
            if (f.delete()) {
                deleted++;
                Log.i(TAG, "eski APK silindi: " + f.getName());
            } else {
                Log.w(TAG, "silinemedi: " + f.getName());
            }
        }
        if (deleted > 0) {
            Log.i(TAG, "OTA cleanup: " + deleted + " dosya silindi");
        }
    }

    static void deleteStagedCache(Context context) {
        if (context == null) return;
        File staged = new File(new File(context.getCacheDir(), "ota"), "update.apk");
        if (staged.exists() && staged.delete()) {
            Log.i(TAG, "staged apk silindi");
        }
    }

    private static boolean isOtaDownloadName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);
        boolean otaFile = lower.endsWith(".apk") || lower.endsWith(".apk.sha256");
        if (!otaFile) return false;
        return lower.startsWith("drivehub") || lower.contains("dort");
    }
}
