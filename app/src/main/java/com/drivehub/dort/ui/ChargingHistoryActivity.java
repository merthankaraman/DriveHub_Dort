package com.drivehub.dort.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.drivehub.dort.R;
import com.drivehub.dort.model.ChargingRecord;
import com.drivehub.dort.util.ChargingHistory;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Şarj geçmişi tablosunu geniş ekranda gösterir (Status panelinden açılır).
 */
public class ChargingHistoryActivity extends AppCompatActivity {

    private static final SimpleDateFormat SDF_ROW = new SimpleDateFormat("dd.MM HH:mm", Locale.getDefault());
    private static final SimpleDateFormat SDF_DAY = new SimpleDateFormat("d MMMM yyyy", new Locale("tr"));
    private static final SimpleDateFormat SDF_MONTH = new SimpleDateFormat("MMMM yyyy", new Locale("tr"));
    // ChargingHistory ile aynı SharedPreferences anahtarı; yeni veri yazıldığında ekranı otomatik yenilemek için dinleyeceğiz.
    private static final String PREF_NAME = "drivehub_dort";
    private static final String KEY_HISTORY = "charging_history";

    private LinearLayout mHistoryTableBody;
    private SharedPreferences mPrefs;
    private SharedPreferences.OnSharedPreferenceChangeListener mPrefsListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_charging_history);

        mHistoryTableBody = findViewById(R.id.historyTableBody);

        // Şarj geçmişi SharedPreferences'ını dinle: yeni kayıt eklendiğinde tabloyu otomatik yenile.
        mPrefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        mPrefsListener = (sharedPreferences, key) -> {
            if (KEY_HISTORY.equals(key)) {
                refreshTable();
            }
        };
        mPrefs.registerOnSharedPreferenceChangeListener(mPrefsListener);

        Button btnBack = findViewById(R.id.btnChargingHistoryBack);
        btnBack.setOnClickListener(v -> finish());

        findViewById(R.id.btnExportChargingHistoryCsv).setOnClickListener(v -> {
            File f = ChargingHistory.exportToCsv(this);
            if (f != null) {
                Toast.makeText(this, getString(R.string.charging_history_toast_saved, f.getAbsolutePath()), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, getString(R.string.charging_history_toast_export_failed), Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.btnClearChargingHistory).setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.charging_history_confirm_clear))
                    .setPositiveButton(getString(R.string.charging_history_yes), (dialog, which) -> {
                        ChargingHistory.clearAll(this);
                        refreshTable();
                        Toast.makeText(this, getString(R.string.charging_history_toast_cleared), Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(getString(R.string.charging_history_no), null)
                    .show();
        });

        refreshTable();
        FullscreenHelper.applyFromPrefs(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        FullscreenHelper.applyFromPrefs(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            FullscreenHelper.applyFromPrefs(this);
        }
    }

    private void refreshTable() {
        if (mHistoryTableBody == null) return;
        mHistoryTableBody.removeAllViews();
        List<ChargingRecord> list = ChargingHistory.load(this);
        if (list.isEmpty()) return;

        Calendar cal = Calendar.getInstance();

        // Önce ay bazında grupla
        Map<Long, List<ChargingRecord>> byMonth = new LinkedHashMap<>();
        for (ChargingRecord r : list) {
            cal.setTimeInMillis(r.startMs);
            cal.set(Calendar.DAY_OF_MONTH, 1);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            long monthStart = cal.getTimeInMillis();
            byMonth.computeIfAbsent(monthStart, k -> new ArrayList<>()).add(r);
        }

        List<Long> monthKeys = new ArrayList<>(byMonth.keySet());
        monthKeys.sort((a, b) -> Long.compare(b, a));

        int dp12 = (int) (getResources().getDisplayMetrics().density * 12 + 0.5f);
        int dp8 = (int) (getResources().getDisplayMetrics().density * 8 + 0.5f);
        int dp4 = (int) (getResources().getDisplayMetrics().density * 4 + 0.5f);

        for (Long monthStart : monthKeys) {
            List<ChargingRecord> monthList = byMonth.get(monthStart);
            if (monthList == null) continue;

            // Ay başlığı (örn. "Şubat 2026")
            TextView monthHeader = new TextView(this);
            monthHeader.setText(SDF_MONTH.format(new Date(monthStart)));
            monthHeader.setTextColor(ContextCompat.getColor(this, R.color.status_value));
            monthHeader.setTextSize(18);
            monthHeader.setTypeface(null, android.graphics.Typeface.BOLD);
            monthHeader.setPadding(0, dp8 * 2, 0, dp4);
            mHistoryTableBody.addView(monthHeader);

            // Bu ay içinde gün bazında grupla
            Map<Long, List<ChargingRecord>> byDay = new LinkedHashMap<>();
            for (ChargingRecord r : monthList) {
                cal.setTimeInMillis(r.startMs);
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                long dayStart = cal.getTimeInMillis();
                byDay.computeIfAbsent(dayStart, k -> new ArrayList<>()).add(r);
            }

            List<Long> dayKeys = new ArrayList<>(byDay.keySet());
            dayKeys.sort((a, b) -> Long.compare(b, a));

            for (Long dayStart : dayKeys) {
                List<ChargingRecord> dayList = byDay.get(dayStart);
                if (dayList == null) continue;
                dayList.sort((a, b) -> Long.compare(b.startMs, a.startMs));

                boolean multiple = dayList.size() > 1;

                if (multiple) {
                    TextView dayHeader = new TextView(this);
                    dayHeader.setText(SDF_DAY.format(new Date(dayStart)));
                    dayHeader.setTextColor(ContextCompat.getColor(this, R.color.status_value));
                    dayHeader.setTextSize(16);
                    dayHeader.setTypeface(null, android.graphics.Typeface.BOLD);
                    dayHeader.setPadding(0, dp8 * 2, 0, dp4);
                    mHistoryTableBody.addView(dayHeader);
                }

                for (ChargingRecord r : dayList) {
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, dp4, dp12, dp4);
                    addCell(row, 1.2f, SDF_ROW.format(new Date(r.startMs)));
                    addCell(row, 1.2f, SDF_ROW.format(new Date(r.endMs)));
                    addCell(row, 0.45f, formatChargeTypeLabel(r.chargeType));
                    addCell(row, 0.7f, String.format(Locale.US, "%.2f", r.acKwh));
                    addCell(row, 0.7f, String.format(Locale.US, "%.2f", r.dcKwh));
                    addCell(row, 0.5f, formatDuration(r));
                    mHistoryTableBody.addView(row);
                }

                if (multiple) {
                    float sumAcStation = 0f, sumDcStation = 0f, sumBatt = 0f;
                    for (ChargingRecord r : dayList) {
                        sumBatt += r.dcKwh;
                        if (ChargingRecord.CHARGE_TYPE_AC.equals(r.chargeType)) {
                            sumAcStation += r.acKwh;
                        } else if (ChargingRecord.CHARGE_TYPE_DC.equals(r.chargeType)) {
                            sumDcStation += r.acKwh;
                        }
                    }
                    LinearLayout bar = new LinearLayout(this);
                    bar.setOrientation(LinearLayout.HORIZONTAL);
                    bar.setGravity(Gravity.CENTER_VERTICAL);
                    bar.setBackgroundColor(ContextCompat.getColor(this, R.color.surface_variant));
                    int pad = (int) (getResources().getDisplayMetrics().density * 10 + 0.5f);
                    bar.setPadding(pad, pad, pad, pad);
                    bar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    TextView barText = new TextView(this);
                    barText.setText(getString(R.string.charging_history_total_format, sumAcStation, sumDcStation, sumBatt));
                    barText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                    barText.setTextSize(12);
                    bar.addView(barText);
                    mHistoryTableBody.addView(bar);
                }

                View daySpacer = new View(this);
                daySpacer.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp8 * 2));
                mHistoryTableBody.addView(daySpacer);
            }

            // Ay toplamı: AC şarj (istasyon) · DC şarj (istasyon)
            float monthSumAcStation = 0f, monthSumDcStation = 0f, monthSumBatt = 0f;
            for (ChargingRecord r : monthList) {
                monthSumBatt += r.dcKwh;
                if (ChargingRecord.CHARGE_TYPE_AC.equals(r.chargeType)) {
                    monthSumAcStation += r.acKwh;
                } else if (ChargingRecord.CHARGE_TYPE_DC.equals(r.chargeType)) {
                    monthSumDcStation += r.acKwh;
                }
            }
            LinearLayout monthBar = new LinearLayout(this);
            monthBar.setOrientation(LinearLayout.HORIZONTAL);
            monthBar.setGravity(Gravity.CENTER_VERTICAL);
            monthBar.setBackgroundColor(ContextCompat.getColor(this, R.color.surface_variant));
            int padMonth = (int) (getResources().getDisplayMetrics().density * 10 + 0.5f);
            monthBar.setPadding(padMonth, padMonth, padMonth, padMonth);
            monthBar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView monthBarText = new TextView(this);
            monthBarText.setText(getString(R.string.charging_history_month_total_format,
                    monthSumAcStation, monthSumDcStation, monthSumBatt));
            monthBarText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            monthBarText.setTextSize(12);
            monthBar.addView(monthBarText);
            mHistoryTableBody.addView(monthBar);

            // Ay sonrası boşluk
            View monthSpacer = new View(this);
            monthSpacer.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp8 * 3));
            mHistoryTableBody.addView(monthSpacer);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mPrefs != null && mPrefsListener != null) {
            mPrefs.unregisterOnSharedPreferenceChangeListener(mPrefsListener);
        }
    }

    private String formatChargeTypeLabel(String code) {
        if (ChargingRecord.CHARGE_TYPE_AC.equals(code)) {
            return getString(R.string.charging_type_ac);
        }
        if (ChargingRecord.CHARGE_TYPE_DC.equals(code)) {
            return getString(R.string.charging_type_dc);
        }
        return code != null ? code : "—";
    }

    private String formatDuration(ChargingRecord r) {
        int[] p = r.getDurationParts();
        int h = p[0], m = p[1], s = p[2];
        if (h > 0) {
            return getString(R.string.duration_format_hms, h, m, s);
        }
        return getString(R.string.duration_format_ms, m, s);
    }

    private void addCell(LinearLayout row, float weight, String text) {
        TextView tv = new TextView(this);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight));
        tv.setText(text);
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        tv.setTextSize(14);
        row.addView(tv);
    }
}
