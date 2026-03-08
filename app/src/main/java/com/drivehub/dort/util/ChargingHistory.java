package com.drivehub.dort.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.drivehub.dort.R;
import com.drivehub.dort.hardware.MG4Hardware;
import com.drivehub.dort.model.ChargingRecord;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Şarj seanslarını kalıcı saklar (SharedPreferences + JSON).
 * Araç kapanıp açılsa da silinmez; sadece "Geçmişi Sil" onayı ile silinir.
 */
public final class ChargingHistory {

    private static final String PREF_NAME = "drivehub_dort";
    private static final String KEY_HISTORY = "charging_history";
    /** Şarj başlangıç zamanı (wall ms) — uygulama kapalıyken başlayan şarjda süreyi geri yüklemek için. */
    private static final String KEY_CHARGING_START_WALL_MS = "charging_start_wall_ms";

    /** Şarj bittiğinde (isCharging false, önceden başlangıç vardı) kaydı ekleyip enerji/süre sayacını sıfırlar. Kayıt eklendiyse true döner. */
    public static boolean checkAndSaveSessionIfEnded(Context context) {
        long startMs = MG4Hardware.getChargingStartWallMs();
        if (startMs == 0L) return false;
        if (MG4Hardware.isChargingNow()) return false;

        long endMs = System.currentTimeMillis();
        float acKwh = MG4Hardware.getAcChargeEnergyKwh();
        float dcKwh = MG4Hardware.getDcChargeEnergyKwh();
        ChargingRecord record = new ChargingRecord(startMs, endMs, acKwh, dcKwh);
        List<ChargingRecord> list = load(context);
        list.add(0, record); // en yeni başta
        save(context, list);
        MG4Hardware.resetSessionAfterSave();
        return true;
    }

    public static List<ChargingRecord> load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_HISTORY, "[]");
        List<ChargingRecord> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new ChargingRecord(
                    o.getLong("startMs"),
                    o.getLong("endMs"),
                    (float) o.getDouble("acKwh"),
                    (float) o.getDouble("dcKwh")
                ));
            }
        } catch (JSONException e) {
            // boş veya bozuk → boş liste
        }
        return out;
    }

    public static void save(Context context, List<ChargingRecord> list) {
        JSONArray arr = new JSONArray();
        for (ChargingRecord r : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("startMs", r.startMs);
                o.put("endMs", r.endMs);
                o.put("acKwh", r.acKwh);
                o.put("dcKwh", r.dcKwh);
                arr.put(o);
            } catch (JSONException ignored) {}
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY, arr.toString())
            .apply();
    }

    public static void clearAll(Context context) {
        save(context, new ArrayList<ChargingRecord>());
    }

    /** Şarj başlangıç zamanını kaydet (BMS ilk şarj gördüğünde; uygulama sonradan açılsa süre doğru kalsın). */
    public static void saveChargingStart(Context context, long startWallMs) {
        if (context == null) return;
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_CHARGING_START_WALL_MS, startWallMs)
            .apply();
    }

    /** Kayıtlı şarj başlangıç zamanı; yoksa veya geçersizse 0. */
    public static long loadChargingStart(Context context) {
        if (context == null) return 0L;
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_CHARGING_START_WALL_MS, 0L);
    }

    /** Şarj seansı kaydedildikten sonra başlangıç kaydını sil. */
    public static void clearChargingStart(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CHARGING_START_WALL_MS)
            .apply();
    }

    /** Geçmişi CSV olarak app'in dış dosya klasörüne yazar. Başlık ve sütunlar uygulama diline göre. Başarılıysa dosyayı döner, yoksa null. */
    public static File exportToCsv(Context context) {
        List<ChargingRecord> list = load(context);
        File dir = context.getExternalFilesDir(null);
        if (dir == null) return null;
        File out = new File(dir, "drivehub_dort_charging_history.csv");
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault());
        try (FileWriter fw = new FileWriter(out, false)) {
            String header = context.getString(R.string.csv_header_start) + ","
                    + context.getString(R.string.csv_header_end) + ","
                    + context.getString(R.string.csv_header_ac_kwh) + ","
                    + context.getString(R.string.csv_header_dc_kwh) + ","
                    + context.getString(R.string.csv_header_hours) + "\n";
            fw.write(header);
            for (ChargingRecord r : list) {
                String start = sdf.format(new java.util.Date(r.startMs));
                String end = sdf.format(new java.util.Date(r.endMs));
                String line = String.format(java.util.Locale.US,
                        "\"%s\",\"%s\",%.3f,%.3f,%.3f\n",
                        start, end, r.acKwh, r.dcKwh, r.getDurationHours());
                fw.write(line);
            }
            fw.flush();
            return out;
        } catch (IOException e) {
            return null;
        }
    }
}
