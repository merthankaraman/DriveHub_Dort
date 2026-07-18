package com.drivehub.kadran;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.drivehub.dort.R;
import com.drivehub.dort.hardware.MG4Hardware;

public class MainActivity extends AppCompatActivity {

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyImmersive();
        for (Fragment f : getSupportFragmentManager().getFragments()) {
            if (f instanceof DashboardFragment) {
                ((DashboardFragment) f).onThemeConfigurationChanged();
            } else if (f instanceof TrackFragment) {
                ((TrackFragment) f).refreshTheme();
            }
        }
    }

    /** Tema düğmesinden sonra pist ekranı renkleri (AppCompatDelegate ile values/values-night). */
    public void refreshTrackFragmentTheme() {
        for (Fragment f : getSupportFragmentManager().getFragments()) {
            if (f instanceof TrackFragment) {
                ((TrackFragment) f).refreshTheme();
            }
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setContentView(R.layout.activity_kadran);

        ViewPager2 pager = findViewById(R.id.mainPager);
        pager.setAdapter(new MainPagerAdapter(this));
        pager.setCurrentItem(0, false);

        findViewById(R.id.btnKadranBack).setOnClickListener(v -> finish());

        applyImmersive();
    }

    private void applyImmersive() {
        View decor = getWindow().getDecorView();
        int flags = View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        decor.setSystemUiVisibility(flags);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
        MG4Hardware.setTrackModeIpk(true);
    }
}
