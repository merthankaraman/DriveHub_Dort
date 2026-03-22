package com.drivehub.dort.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

/**
 * Ayarlardaki "tam ekran" tercihi ile immersive modu uygular.
 * Dialog / başka Activity sonrası sistem çubuğu geri gelince
 * {@link Activity#onWindowFocusChanged(boolean)} içinde tekrar çağrılmalı.
 */
public final class FullscreenHelper {

    public static final String PREF_NAME = "drivehub_dort";
    /** MainActivity ile aynı anahtar — değiştirme */
    public static final String KEY_FULLSCREEN = "fullscreen_mode";

    private FullscreenHelper() {}

    public static boolean isFullscreenEnabled(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_FULLSCREEN, false);
    }

    public static void applyFromPrefs(Activity activity) {
        if (activity == null) return;
        apply(activity, isFullscreenEnabled(activity));
    }

    /**
     * Eski MainActivity.applyFullscreen ile aynı davranış + layout flag'leri
     * (dialog kapanınca yeniden uygulanınca daha stabil).
     */
    public static void apply(Activity activity, boolean enabled) {
        if (activity == null) return;
        View decor = activity.getWindow().getDecorView();
        if (enabled) {
            int flags = View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            decor.setSystemUiVisibility(flags);
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }
}
