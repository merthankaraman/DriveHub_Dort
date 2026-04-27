package com.drivehub.dort.ui;

import android.content.Context;
import android.content.SharedPreferences;

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
        if (isNewLayoutsEnabled(context)) {
            int newLayoutId = getLayoutId(context, baseLayoutName + "_new");
            if (newLayoutId != 0) {
                return newLayoutId;
            }
        }
        return getLayoutId(context, baseLayoutName);
    }

    public static boolean hasLayout(Context context, String layoutName) {
        return getLayoutId(context, layoutName) != 0;
    }

    private static int getLayoutId(Context context, String layoutName) {
        return context.getResources().getIdentifier(layoutName, "layout", context.getPackageName());
    }
}
