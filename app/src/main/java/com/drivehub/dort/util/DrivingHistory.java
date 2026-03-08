package com.drivehub.dort.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.drivehub.dort.model.DrivingRecord;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sürüş oturumlarını kalıcı saklar (SharedPreferences + JSON).
 * Şarj geçmişine benzer yapıdadır.
 */
public final class DrivingHistory {

    private static final String PREF_NAME = "drivehub_dort";
    private static final String KEY_HISTORY = "driving_history";

    private DrivingHistory() {}

    public static void addSession(Context context,
                                  long startMs,
                                  long endMs,
                                  float distanceKm,
                                  float energyKwh) {
        if (context == null) return;
        if (endMs <= startMs) return;

        float durationHours = Math.max(0f, (endMs - startMs) / 3_600_000f);
        float avgSpeedKmh = (durationHours > 0f) ? (distanceKm / durationHours) : 0f;
        float avgKwhPer100km = (distanceKm > 0f) ? (energyKwh / distanceKm) * 100f : 0f;

        DrivingRecord record = new DrivingRecord(
                startMs,
                endMs,
                distanceKm,
                energyKwh,
                avgSpeedKmh,
                avgKwhPer100km
        );

        List<DrivingRecord> list = load(context);
        list.add(0, record); // en yeni başta
        save(context, list);
    }

    public static List<DrivingRecord> load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_HISTORY, "[]");
        List<DrivingRecord> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new DrivingRecord(
                        o.getLong("startMs"),
                        o.getLong("endMs"),
                        (float) o.getDouble("distanceKm"),
                        (float) o.getDouble("energyKwh"),
                        (float) o.getDouble("avgSpeedKmh"),
                        (float) o.getDouble("avgKwhPer100km")
                ));
            }
        } catch (JSONException e) {
            // boş veya bozuk → boş liste
        }
        return out;
    }

    public static void save(Context context, List<DrivingRecord> list) {
        JSONArray arr = new JSONArray();
        for (DrivingRecord r : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("startMs", r.startMs);
                o.put("endMs", r.endMs);
                o.put("distanceKm", r.distanceKm);
                o.put("energyKwh", r.energyKwh);
                o.put("avgSpeedKmh", r.avgSpeedKmh);
                o.put("avgKwhPer100km", r.avgKwhPer100km);
                arr.put(o);
            } catch (JSONException ignored) {}
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_HISTORY, arr.toString())
                .apply();
    }

    public static void clearAll(Context context) {
        save(context, new ArrayList<DrivingRecord>());
    }

    /** Geçmişi CSV olarak app'in dış dosya klasörüne yazar. */
    public static File exportToCsv(Context context) {
        List<DrivingRecord> list = load(context);
        File dir = context.getExternalFilesDir(null);
        if (dir == null) return null;
        File out = new File(dir, "drivehub_dort_driving_history.csv");
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        try (FileWriter fw = new FileWriter(out, false)) {
            String header = "Start,End,DistanceKm,EnergyKwh,AvgSpeedKmh,AvgKwhPer100km,Hours\n";
            fw.write(header);
            for (DrivingRecord r : list) {
                String start = sdf.format(new java.util.Date(r.startMs));
                String end = sdf.format(new java.util.Date(r.endMs));
                String line = String.format(Locale.US,
                        "\"%s\",\"%s\",%.3f,%.3f,%.2f,%.2f,%.3f\n",
                        start, end, r.distanceKm, r.energyKwh, r.avgSpeedKmh,
                        r.avgKwhPer100km, r.getDurationHours());
                fw.write(line);
            }
            fw.flush();
            return out;
        } catch (IOException e) {
            return null;
        }
    }
}

