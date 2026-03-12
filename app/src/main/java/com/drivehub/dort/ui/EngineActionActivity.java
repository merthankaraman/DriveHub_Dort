package com.drivehub.dort.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import com.drivehub.dort.audio.EngineSoundManager;

/**
 * Basit köprü Activity:
 * Dış uygulamalardan gelen intent'leri alıp EngineSoundManager üzerinde komuta çevirir.
 * Her çağrıda hızlıca işi yapar ve kendini kapatır.
 *
 * Kullanılabilir aksiyonlar:
 * - ACTION_START_ENGINE_SOUND
 * - ACTION_STOP_ENGINE_SOUND
 */
public class EngineActionActivity extends Activity {

    public static final String ACTION_START_ENGINE_SOUND = "com.drivehub.dort.action.START_ENGINE_SOUND";
    public static final String ACTION_STOP_ENGINE_SOUND  = "com.drivehub.dort.action.STOP_ENGINE_SOUND";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String action = intent != null ? intent.getAction() : null;

        EngineSoundManager esm = EngineSoundManager.getInstance(getApplicationContext());
        SharedPreferences prefs = getSharedPreferences("drivehub_dort", MODE_PRIVATE);

        if (ACTION_START_ENGINE_SOUND.equals(action)) {
            // Ana ekrandaki ses AÇ düğmesiyle aynı: sadece bayrağı aç
            prefs.edit().putBoolean("sound_enabled", true).apply();

            // Uygulama zaten çalışıyorsa motor sesini hemen başlatabilsin diye profil ayarlarını yükle
            esm.initFromPreferences(this);
        } else if (ACTION_STOP_ENGINE_SOUND.equals(action)) {
            // Ses bayrağını kapat ve varsa aktif motor döngüsünü durdur
            prefs.edit().putBoolean("sound_enabled", false).apply();
            esm.stop();
        }

        // UI göstermiyoruz, sadece işi yapıp kapanıyoruz.
        finish();
    }
}

