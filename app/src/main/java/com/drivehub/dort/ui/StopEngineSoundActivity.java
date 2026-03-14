package com.drivehub.dort.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;

import com.drivehub.dort.audio.EngineSoundManager;
import com.drivehub.dort.hardware.MG4Hardware;

/**
 * Basit yardımcı Activity:
 * Başka uygulamalardan doğrudan çağrıldığında motor sesini KAPATIR.
 * UI göstermez, işi yapıp hemen kapanır.
 */
public class StopEngineSoundActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EngineSoundManager esm = EngineSoundManager.getInstance(getApplicationContext());
        SharedPreferences prefs = getSharedPreferences("drivehub_dort", MODE_PRIVATE);

        // Ses bayrağını kapat ve aktif motor döngüsünü durdur
        prefs.edit().putBoolean("sound_enabled", false).apply();
        MG4Hardware.setSoundEnabled(false);
        esm.stop();

        finish();
    }
}

