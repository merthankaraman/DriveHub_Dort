package com.example.mg4_v3.ui;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.mg4_v3.R;
import com.example.mg4_v3.model.ChargingRecord;
import com.example.mg4_v3.util.ChargingHistory;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Şarj geçmişi tablosunu geniş ekranda gösterir (Status panelinden açılır).
 */
public class ChargingHistoryActivity extends AppCompatActivity {

    private static final SimpleDateFormat SDF = new SimpleDateFormat("dd.MM HH:mm", Locale.getDefault());

    private LinearLayout mHistoryTableBody;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_charging_history);

        mHistoryTableBody = findViewById(R.id.historyTableBody);

        Button btnBack = findViewById(R.id.btnChargingHistoryBack);
        btnBack.setOnClickListener(v -> finish());

        findViewById(R.id.btnExportChargingHistoryCsv).setOnClickListener(v -> {
            File f = ChargingHistory.exportToCsv(this);
            if (f != null) {
                Toast.makeText(this, "CSV kaydedildi: " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "CSV dışa aktarma başarısız.", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.btnClearChargingHistory).setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setMessage("Geçmişi silmek istiyor musunuz?")
                    .setPositiveButton("Evet", (dialog, which) -> {
                        ChargingHistory.clearAll(this);
                        refreshTable();
                        Toast.makeText(this, "Geçmiş silindi.", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Hayır", null)
                    .show();
        });

        refreshTable();
    }

    private void refreshTable() {
        if (mHistoryTableBody == null) return;
        mHistoryTableBody.removeAllViews();
        List<ChargingRecord> list = ChargingHistory.load(this);
        int dp12 = (int) (getResources().getDisplayMetrics().density * 12 + 0.5f);
        int dp8 = (int) (getResources().getDisplayMetrics().density * 8 + 0.5f);
        for (ChargingRecord r : list) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp8, dp12, dp8);
            addCell(row, 1.2f, SDF.format(new Date(r.startMs)));
            addCell(row, 1.2f, SDF.format(new Date(r.endMs)));
            addCell(row, 0.7f, String.format(Locale.US, "%.2f", r.acKwh));
            addCell(row, 0.7f, String.format(Locale.US, "%.2f", r.dcKwh));
            addCell(row, 0.5f, r.getDurationFormatted());
            mHistoryTableBody.addView(row);
        }
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
