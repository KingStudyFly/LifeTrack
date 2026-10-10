package com.example.lifetrack;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.RemoteViews;

/**
 * 桌面待办小组件：展示未完成的待办，支持垂直滚动。
 *
 * 采用官方集合视图方案：ListView + RemoteViewsService（{@link TodoWidgetService}）。
 * 不用预置固定行，是因为 RemoteViews 无法动态 addView，条目数被布局行数限死；
 * 集合视图由工厂按需渲染，条目数不再受布局限制。
 */
public class TodoWidgetProvider extends AppWidgetProvider {

    private static final String TAG = "TodoWidget";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            updateWidget(context, manager, id);
        }
    }

    /**
     * 供待办页与日记保存流程在数据变化后主动刷新。
     *
     * 需做两件事：
     * 1. notifyAppWidgetViewDataChanged 让集合视图重取数据；
     * 2. 重新 updateAppWidget刷新标题行的「N 项未完成」——
     *    集合视图的总数由服务侧写入共享配置，标题是静态 TextView，需单独重绘。
     */
    public static void notifyDataChanged(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(
                    new ComponentName(context, TodoWidgetProvider.class));
            if (ids == null || ids.length == 0) return;
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widgetTodoList);
            for (int id : ids) {
                updateWidget(context, manager, id);
            }
        } catch (Exception e) {
            Log.w(TAG, "notifyDataChanged failed", e);
        }
    }

    private static void updateWidget(Context context, AppWidgetManager manager, int widgetId) {
        final Context appContext = context.getApplicationContext();

        RemoteViews views = new RemoteViews(appContext.getPackageName(), R.layout.widget_todo);
        applyBackgroundAlpha(views, appContext);
        applyTextColors(views, appContext);

        views.setTextViewText(R.id.widgetTodoTitle,
                appContext.getString(R.string.widget_todo_title));

        // 绑定集合视图：ListView 的数据由 RemoteViewsService 提供。
        // 不用 setEmptyView——它会接管空状态显示，实测在部分 launcher 上
        // 导致集合视图连不上服务（列表恒为空）。改为自行控制空态TextView。
        Intent serviceIntent = new Intent(appContext, TodoWidgetService.class);
        serviceIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        // data 必须唯一，否则 Intent 被系统去重，多个组件会共用同一份缓存
        serviceIntent.setData(Uri.parse(serviceIntent.toUri(Intent.URI_INTENT_SCHEME)));
        views.setRemoteAdapter(R.id.widgetTodoList, serviceIntent);

        PendingIntent openApp = buildOpenPendingIntent(appContext, widgetId);
        views.setOnClickPendingIntent(R.id.widgetTodoRoot, openApp);
        views.setOnClickPendingIntent(R.id.widgetTodoHeader, openApp);

        manager.updateAppWidget(widgetId, views);
    }

    /**
     * 背景不透明度：0% 全透（可见桌面壁纸），100% 纯白。
     *
     * 官方推荐做法：把圆角 ShapeDrawable 放到 ImageView 的 src 上，
     * 再用 setInt(id, "setImageAlpha", 0-255) 调透明度。
     * 原因：setBackgroundColor 会替换掉 drawable，圆角就没了。
     * 载体必须是 ImageView（RemoteViews 白名单不允许 <View>）。
     */
    private static void applyBackgroundAlpha(RemoteViews views, Context context) {
        int alpha = Math.round(TodoWidgetPrefs.getBgAlpha(context) * 255f / 100f);
        views.setImageViewResource(R.id.widgetTodoBackground, R.drawable.bg_widget_todo);
        views.setInt(R.id.widgetTodoBackground, "setImageAlpha", alpha);
    }

    /**
     * 背景亮到一定程度时白字会看不清，需切为深色文字。
     * 集合视图的行内文字由 RemoteViewsFactory 按同规则处理，保持一致。
     */
    private static void applyTextColors(RemoteViews views, Context context) {
        int bgAlpha = TodoWidgetPrefs.getBgAlpha(context);
        views.setTextColor(R.id.widgetTodoTitle,
                TodoWidgetPrefs.primaryTextColor(context, bgAlpha));
        views.setTextColor(R.id.widgetTodoCount,
                TodoWidgetPrefs.secondaryTextColor(context, bgAlpha));
        views.setTextColor(R.id.widgetTodoEmpty,
                TodoWidgetPrefs.secondaryTextColor(context, bgAlpha));
    }

    private static PendingIntent buildOpenPendingIntent(Context context, int requestCode) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SCHEDULE);
        return PendingIntent.getActivity(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                         int appWidgetId, Bundle newOptions) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions);
        updateWidget(context, manager, appWidgetId);
    }
}
