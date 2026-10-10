package com.example.lifetrack;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * 管理深色/浅色主题模式切换与持久化。
 * 模式值与 AppCompatDelegate.MODE_NIGHT_* 一致，便于直接传入 setDefaultNightMode。
 */
public final class ThemeHelper {

    private static final String PREFS_NAME = "lifetrack_prefs";
    private static final String KEY_THEME_MODE = "theme_mode";

    /** 跟随系统（默认） */
    public static final int MODE_SYSTEM = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    /** 浅色模式 */
    public static final int MODE_LIGHT = AppCompatDelegate.MODE_NIGHT_NO;
    /** 深色模式 */
    public static final int MODE_DARK = AppCompatDelegate.MODE_NIGHT_YES;

    private ThemeHelper() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 读取已保存的模式，默认跟随系统 */
    public static int getMode(Context context) {
        return prefs(context).getInt(KEY_THEME_MODE, MODE_SYSTEM);
    }

    /** 持久化并立即应用到 AppCompatDelegate */
    public static void applyMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_THEME_MODE, mode).apply();
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    /** 在 Application/Activity 启动时调用，恢复已保存的模式 */
    public static void init(Context context) {
        AppCompatDelegate.setDefaultNightMode(getMode(context));
    }

    /**
     * 当前是否处于深色模式。
     * - 显式 MODE_DARK/MODE_LIGHT 直接返回对应值；
     * - MODE_SYSTEM（跟随系统）时，依据当前资源配置实时判定。
     * <p>
     * 注意：必须在 Activity 已完成 Configuration 应用后调用，否则可能取到旧值。
     */
    public static boolean isDarkMode(Context context) {
        int mode = getMode(context);
        if (mode == MODE_DARK) return true;
        if (mode == MODE_LIGHT) return false;
        // 跟随系统：依据当前资源配置判定
        int nightModeFlags = context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }
}
