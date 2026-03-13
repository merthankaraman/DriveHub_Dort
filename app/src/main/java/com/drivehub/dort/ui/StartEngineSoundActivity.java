package com.drivehub.dort.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import com.drivehub.dort.audio.EngineSoundManager;

/**
 * Basit yardımcı Activity:
 * Başka uygulamalardan doğrudan çağrıldığında motor sesini AÇAR.
 * UI göstermez, işi yapıp hemen kapanır.
 */
public class StartEngineSoundActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EngineSoundManager esm = EngineSoundManager.getInstance(getApplicationContext());
        SharedPreferences prefs = getSharedPreferences("drivehub_dort", MODE_PRIVATE);

        // MainActivity'deki ses aç butonuyla aynı: sadece bayrağı aç
        prefs.edit().putBoolean("sound_enabled", true).apply();

        // Uygulama zaten çalışıyorsa profil/ayarları yüklesin
        esm.initFromPreferences(this);

        // İsteğe göre anında başlatmak istersen:
        // esm.start();

        finish();
    }
}

