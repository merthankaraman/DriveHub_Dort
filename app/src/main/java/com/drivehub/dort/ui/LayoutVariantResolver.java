package com.drivehub.dort.ui;

import android.content.Context;
import android.content.SharedPreferences;

import com.drivehub.dort.R;

/**
 * "new" layout varyantını aç/kapat ve runtime'da doğru kaynağı seç.
 */
public final class LayoutVariantResolver {

    private static final String PREFS_NAME = "drivehub_dort";
    public static final String PREF_USE_NEW_LAYOUTS = "use_new_layouts";

    private LayoutVariantResolver() {}

    public static boolean isNewLayoutsEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_USE_NEW_LAYOUTS, false);
    }

    public static void setNewLayoutsEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(PREF_USE_NEW_LAYOUTS, enabled).apply();
    }

    public static int resolveLayout(Context context, String baseLayoutName) {
        boolean useNewLayouts = isNewLayoutsEnabled(context);
        switch (baseLayoutName) {
            case "activity_main":
                return useNewLayouts ? R.layout.activity_main_new : R.layout.activity_main;
            case "activity_driving_history":
                return useNewLayouts ? R.layout.activity_driving_history_new : R.layout.activity_driving_history;
            case "activity_charging_history":
                return useNewLayouts ? R.layout.activity_charging_history_new : R.layout.activity_charging_history;
            default:
                return 0;
        }
    }

    public static boolean hasLayout(Context context, String layoutName) {
        return resolveLayout(context, layoutName) != 0;
    }
}
