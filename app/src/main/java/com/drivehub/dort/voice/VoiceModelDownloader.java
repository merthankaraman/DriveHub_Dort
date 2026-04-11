package com.drivehub.dort.voice;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * İlk çalıştırmada Vosk Türkçe küçük modelini indirip uygulama dosya alanına açar.
 */
public final class VoiceModelDownloader {

    public static final String MODEL_DIR_NAME = "vosk-model-small-tr-0.3";
    private static final String MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip";

    private VoiceModelDownloader() {}

    /**
     * Uygulama özel dizini: car ve sim farklı {@code applicationId} ile farklı yol alır
     * (ör. …/com.drivehub.dort.sim/files/); her kurulum kendi modelini kullanır.
     */
    public static File modelDirectory(Context context) {
        return new File(context.getFilesDir(), MODEL_DIR_NAME);
    }

    /**
     * Küçük TR modeli (0.3) zip içinde {@code final.mdl} kökte; bazı modellerde {@code am/final.mdl}.
     */
    public static boolean isModelReady(Context context) {
        File root = modelDirectory(context);
        if (new File(root, "final.mdl").isFile()) {
            return true;
        }
        return new File(root, "am/final.mdl").isFile();
    }

    public interface ProgressListener {
        void onProgress(String message, long bytesRead, long contentLength);
    }

    /**
     * @return model klasörünün mutlak yolu
     */
    public static String ensureModel(Context context, ProgressListener listener) throws IOException {
        if (isModelReady(context)) {
            return modelDirectory(context).getAbsolutePath();
        }
        File filesDir = context.getFilesDir();
        File zipOut = new File(filesDir, MODEL_DIR_NAME + ".zip");
        downloadToFile(MODEL_URL, zipOut, listener);
        File destParent = filesDir;
        unzipSafely(zipOut, destParent);
        if (!zipOut.delete()) {
            // yoksay
        }
        if (!isModelReady(context)) {
            throw new IOException(
                    "Model açıldı ama geçerli dosya yok (final.mdl veya am/final.mdl). "
                            + "Uygulama verisini temizleyip tekrar deneyin: " + modelDirectory(context));
        }
        return modelDirectory(context).getAbsolutePath();
    }

    private static void downloadToFile(String urlStr, File dest, ProgressListener listener)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(30_000);
        c.setReadTimeout(120_000);
        c.setInstanceFollowRedirects(true);
        c.connect();
        int code = c.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        long total = c.getContentLengthLong();
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            long read = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                read += n;
                if (listener != null) {
                    listener.onProgress("İndiriliyor…", read, total);
                }
            }
            out.flush();
        } finally {
            c.disconnect();
        }
    }

    private static void unzipSafely(File zipFile, File destDir) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        String destCanonical = destDir.getCanonicalPath() + File.separator;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(destDir, entry.getName());
                String can = outFile.getCanonicalPath();
                if (!can.startsWith(destCanonical)) {
                    throw new IOException("Geçersiz zip girdisi: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    if (!outFile.isDirectory() && !outFile.mkdirs()) {
                        throw new IOException("Klasör oluşturulamadı: " + outFile);
                    }
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Klasör oluşturulamadı: " + parent);
                    }
                    try (FileOutputStream fos = new FileOutputStream(outFile, false)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }
}
