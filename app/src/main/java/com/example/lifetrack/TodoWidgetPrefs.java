package com.example.lifetrack;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.core.content.ContextCompat;

/**
 * 桌面待办小组件的外观配置。
 * 背景为半透明白色，不透明度（0-100）可调：0% 完全透出壁纸，100% 纯白。
 */
public final class TodoWidgetPrefs {

    private static final String PREFS_NAME = "lifetrack_widget";
    private static final String KEY_BG_ALPHA = "widget_bg_alpha";
    private static final String KEY_MAX_ITEMS = "widget_max_items";
    /**
     * 未完成总数。集合视图（ListView）拿不到聚合值，
     * 由 TodoWidgetService 读库后写入，供 Provider 渲染标题行。
     */
    private static final String KEY_PENDING_TOTAL = "widget_pending_total";
    /**
     * 配置版本。用于在外观语义变化时作废旧的用户设置——
     * 否则旧版存下的高不透明度值会被新版直接沿用，导致「改了默认但看起来没变」。
     */
    private static final String KEY_CONFIG_VERSION = "widget_config_version";
    private static final int CURRENT_CONFIG_VERSION = 3;

    /**
     * 默认不透明度 26%，能明显透出壁纸。
     * 刻意不用 50%：Android 的 setAlpha 在深色壁纸上叠加后仍偏实，
     * 而参考图那种通透感需要明显低于半透明的值。
     */
    public static final int DEFAULT_BG_ALPHA = 26;
    public static final int MIN_BG_ALPHA = 0;
    public static final int MAX_BG_ALPHA = 100;
    /**
     * 集合视图方案下，ListView 会缓存并按需渲染任意条数，
     * 上限只用于约束一次性读入的条数，避免超大列表拖慢组件。
     */
    public static final int DEFAULT_MAX_ITEMS = 30;
    public static final int MAX_ITEMS_LIMIT = 50;

    /**
     * 背景不透明度达到该值时改用深色文字。
     * 半透明背景下白字更通透，但背景接近纯白时白字会看不清，故需随不透明度反色。
     */
    private static final int DARK_TEXT_THRESHOLD = 55;

    private TodoWidgetPrefs() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 首次读取时执行配置迁移：检测到旧版本配置则清除，让新默认值生效。
     * 必须在任何 getter 之前调用。
     *
     * v1 → v2：alpha 语义从「实心白卡片」变为「磨砂半透」，旧值作废。
     * v2 → v3：布局行数4→10，max_items 上限同步放开；旧值被钳在 4，
     *导致「明明扩容了却仍只显示 4 条」，故也作废。
     */
    public static void migrate(Context context) {
        SharedPreferences sp = prefs(context);
        int version = sp.getInt(KEY_CONFIG_VERSION, 1);
        if (version >= CURRENT_CONFIG_VERSION) return;
        SharedPreferences.Editor editor = sp.edit();
        if (version < 2) {
            // v1 的 alpha 是「实心白卡片」语义，v2 是「磨砂半透」，直接沿用会显得很实
            editor.remove(KEY_BG_ALPHA);
        }
        if (version < 3) {
            // 旧值上限是 4，扩容后需要重置，否则用户看到的条数不会变
            editor.remove(KEY_MAX_ITEMS);
        }
        editor.putInt(KEY_CONFIG_VERSION, CURRENT_CONFIG_VERSION).apply();
    }

    /** 背景不透明度，0-100 */
    public static int getBgAlpha(Context context) {
        migrate(context);
        return clampAlpha(prefs(context).getInt(KEY_BG_ALPHA, DEFAULT_BG_ALPHA));
    }

    public static void setBgAlpha(Context context, int alpha) {
        prefs(context).edit().putInt(KEY_BG_ALPHA, clampAlpha(alpha)).apply();
    }

    /** 最多展示的待办条数，1-4 */
    public static int getMaxItems(Context context) {
        migrate(context);
        int value = prefs(context).getInt(KEY_MAX_ITEMS, DEFAULT_MAX_ITEMS);
        return Math.max(1, Math.min(MAX_ITEMS_LIMIT, value));
    }

    public static void setMaxItems(Context context, int count) {
        prefs(context).edit().putInt(KEY_MAX_ITEMS,
                Math.max(1, Math.min(MAX_ITEMS_LIMIT, count))).apply();
    }

    /** 背景是否已亮到需要深色文字 */
    public static boolean needsDarkText(int alphaPercent) {
        return alphaPercent >= DARK_TEXT_THRESHOLD;
    }

    /** 未完成总数（由集合视图服务写入） */
    public static void setPendingTotal(Context context, int total) {
        prefs(context).edit().putInt(KEY_PENDING_TOTAL, Math.max(0, total)).apply();
    }

    public static int getPendingTotal(Context context) {
        return Math.max(0, prefs(context).getInt(KEY_PENDING_TOTAL, 0));
    }

    /** 按背景亮度取主文字色：深底用白字，亮底用深字 */
    public static int primaryTextColor(Context context, int alphaPercent) {
        return ContextCompat.getColor(context, needsDarkText(alphaPercent)
                ? R.color.text_primary : android.R.color.white);
    }

    /** 次要文字色（副标题、右侧状态） */
    public static int secondaryTextColor(Context context, int alphaPercent) {
        if (needsDarkText(alphaPercent)) {
            return ContextCompat.getColor(context, R.color.text_subtitle);
        }
        // 半透明背景下次要文字用半透明白，避免过亮抢主标题
        return 0x99FFFFFF;
    }

    private static int clampAlpha(int alpha) {
        return Math.max(MIN_BG_ALPHA, Math.min(MAX_BG_ALPHA, alpha));
    }
}
