package com.drivehub.dort.ota;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * İndirilen APK'yı önce uygulama cache'ine kopyalar, doğrular, sonra kurar.
 * Doğrudan Downloads URI ile kurulum araçta sık "paket ayrıştırılamadı" verir.
 */
final class OtaInstaller {

    private static final String TAG = "MG4_OTA";
    private static final String OTA_DIR = "ota";
    private static final String OTA_APK_NAME = "update.apk";

    private OtaInstaller() {
    }

    static void install(Context context, long downloadId, Uri apkUri) throws Exception {
        File staged = stageApkToCache(context, downloadId, apkUri);
        validateApk(context, staged);

        try {
            installWithSession(context, staged);
            Log.i(TAG, "PackageInstaller session started: " + staged.getAbsolutePath()
                    + " size=" + staged.length());
            return;
        } catch (Throwable t) {
            Log.w(TAG, "session install failed, fallback Intent: " + t.getMessage());
        }

        if (Build.VERSION.SDK_INT >= 26
                && !context.getPackageManager().canRequestPackageInstalls()) {
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            settings.setData(Uri.parse("package:" + context.getPackageName()));
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(settings);
            } catch (Throwable ignored) {
            }
            throw new IllegalStateException("unknown sources permission required");
        }

        Uri contentUri = FileProvider.getUriForFile(
                context, context.getPackageName() + ".fileprovider", staged);
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(contentUri, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        grantToInstallers(context, contentUri);
        if (install.resolveActivity(context.getPackageManager()) == null) {
            throw new IllegalStateException("No package installer activity");
        }
        context.startActivity(install);
        Log.i(TAG, "install Intent started uri=" + contentUri);
    }

    /** Kurulum sırasında korumak için kaynak Downloads dosyası. */
    static File resolveApkFilePublic(Context context, long downloadId, Uri apkUri) {
        return resolveApkFile(context, downloadId, apkUri);
    }

    private static File stageApkToCache(Context context, long downloadId, Uri apkUri) throws Exception {
        File dir = new File(context.getCacheDir(), OTA_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Cannot create ota cache dir");
        }
        File dest = new File(dir, OTA_APK_NAME);
        if (dest.exists() && !dest.delete()) {
            Log.w(TAG, "could not delete old staged apk");
        }

        try (InputStream in = openApkStream(context, downloadId, apkUri);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            long total = 0;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
            if (total < 1024L) {
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
                throw new IllegalStateException("APK too small (" + total + " bytes)");
            }
            Log.i(TAG, "staged apk " + total + " bytes → " + dest.getAbsolutePath());
        }

        try (FileInputStream fis = new FileInputStream(dest)) {
            byte[] magic = new byte[2];
            if (fis.read(magic) != 2 || magic[0] != 'P' || magic[1] != 'K') {
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
                throw new IllegalStateException("Not a valid APK/ZIP (bad magic)");
            }
        }
        return dest;
    }

    private static void validateApk(Context context, File apk) throws Exception {
        PackageManager pm = context.getPackageManager();
        PackageInfo info = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (info == null || info.packageName == null) {
            throw new IllegalStateException("PackageManager cannot parse APK");
        }
        if (!context.getPackageName().equals(info.packageName)) {
            throw new IllegalStateException("APK package mismatch: " + info.packageName);
        }
        Log.i(TAG, "APK ok package=" + info.packageName
                + " versionName=" + info.versionName
                + " versionCode=" + (Build.VERSION.SDK_INT >= 28
                ? info.getLongVersionCode() : info.versionCode));
    }

    private static void installWithSession(Context context, File apk) throws Exception {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = null;
        try {
            session = installer.openSession(sessionId);
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                session.fsync(out);
            }
            Intent callback = new Intent(context, OtaInstallResultReceiver.class);
            callback.setAction(OtaInstallResultReceiver.ACTION_INSTALL_RESULT);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pi = PendingIntent.getBroadcast(context, sessionId, callback, flags);
            session.commit(pi.getIntentSender());
            session = null;
        } finally {
            if (session != null) {
                try {
                    session.abandon();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void grantToInstallers(Context context, Uri uri) {
        String[] pkgs = {
                "com.android.packageinstaller",
                "com.google.android.packageinstaller",
                "com.samsung.android.packageinstaller"
        };
        for (String pkg : pkgs) {
            try {
                context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
            }
        }
    }

    private static InputStream openApkStream(Context context, long downloadId, Uri apkUri) throws Exception {
        if (downloadId > 0L) {
            try {
                android.app.DownloadManager dm =
                        (android.app.DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) {
                    ParcelFileDescriptor pfd = dm.openDownloadedFile(downloadId);
                    if (pfd != null) {
                        return new ParcelFileDescriptor.AutoCloseInputStream(pfd);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "openDownloadedFile: " + t.getMessage());
            }
        }
        File file = resolveApkFile(context, downloadId, apkUri);
        if (file != null && file.exists()) {
            return new FileInputStream(file);
        }
        if (apkUri != null) {
            InputStream in = context.getContentResolver().openInputStream(apkUri);
            if (in != null) return in;
        }
        throw new IllegalStateException("Cannot open downloaded APK stream");
    }

    private static File resolveApkFile(Context context, long downloadId, Uri apkUri) {
        try {
            android.app.DownloadManager dm =
                    (android.app.DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null && downloadId > 0L) {
                try (Cursor c = dm.query(new android.app.DownloadManager.Query().setFilterById(downloadId))) {
                    if (c != null && c.moveToFirst()) {
                        int idx = c.getColumnIndex(android.app.DownloadManager.COLUMN_LOCAL_URI);
                        if (idx >= 0) {
                            String local = c.getString(idx);
                            if (local != null && local.startsWith("file:")) {
                                File f = new File(Uri.parse(local).getPath());
                                if (f.exists()) return f;
                            }
                        }
                        int pathIdx = c.getColumnIndex("local_filename");
                        if (pathIdx >= 0) {
                            String path = c.getString(pathIdx);
                            if (path != null) {
                                File f = new File(path);
                                if (f.exists()) return f;
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "resolve download file: " + t.getMessage());
        }
        if (apkUri != null && "file".equalsIgnoreCase(apkUri.getScheme())) {
            File f = new File(apkUri.getPath());
            if (f.exists()) return f;
        }
        try {
            File downloads = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS);
            if (downloads != null && downloads.isDirectory()) {
                File[] files = downloads.listFiles((d, name) -> isOtaApkName(name));
                if (files != null && files.length > 0) {
                    File best = files[0];
                    for (File f : files) {
                        if (f.lastModified() > best.lastModified()) best = f;
                    }
                    return best;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static boolean isOtaApkName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);
        if (!lower.endsWith(".apk")) return false;
        return lower.startsWith("drivehub") || lower.contains("dort");
    }
}
