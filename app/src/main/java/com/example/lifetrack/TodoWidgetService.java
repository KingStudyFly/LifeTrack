package com.example.lifetrack;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.List;

/**
 * 桌面待办小组件的集合数据源。
 *
 * 为什么需要这个类：RemoteViews 不支持 ScrollView（不在控件白名单内），
 * 但官方支持「集合视图」（ListView/GridView/StackView）配合 RemoteViewsService，
 * 集合视图自带垂直滚动。getViewAt(position) 按需渲染——
 * 显示 3 条就只创建 3 个 View，想看全部就滚动，不受预置行数限制。
 *
 * 注意：集合视图的每行由本工厂独立渲染，背景透明与圆角需在
 * widget_todo.xml 的容器层保证，item 布局本身不再设背景。
 */
public class TodoWidgetService extends RemoteViewsService {

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new TodoRemoteViewsFactory(getApplicationContext());
    }

    /**
     * 待办列表工厂。数据在 onDataSetChanged 时一次性读入并缓存，
     * 避免 getViewAt 每次都解密查询 SQLCipher（跨进程调用代价高）。
     */
    static class TodoRemoteViewsFactory implements RemoteViewsFactory {

        private static final String TAG = "TodoWidgetSvc";
        /** 标题最大字符数 */
        private static final int MAX_TITLE_LENGTH = 30;
        /** 副标题最大字符数 */
        private static final int MAX_SUB_LENGTH = 20;
        /** 行点击 requestCode 基准值，避开 widgetId 自身取值 */
        private static final int ROW_REQUEST_BASE = 1_000_000;

        private final Context context;
        private List<TodoItem> pending = new ArrayList<>();

        TodoRemoteViewsFactory(Context context) {
            this.context = context.getApplicationContext();
        }

        @Override
        public void onCreate() {
        }

        @Override
        public void onDataSetChanged() {
            List<TodoItem> all;
            try {
                all = new TodoDao(context).queryAll();
            } catch (Exception e) {
                Log.w(TAG, "onDataSetChanged: query failed", e);
                pending = new ArrayList<>();
                return;
            }
            int limit = TodoWidgetPrefs.getMaxItems(context);
            List<TodoItem> rows = new ArrayList<>();
            int total = 0;
            if (all != null) {
                for (TodoItem item : all) {
                    if (item.completed) continue;
                    total++;
                    if (rows.size() < limit) rows.add(item);
                }
            }
            pending = rows;
            Log.d(TAG, "onDataSetChanged: totalPending=" + total
                    + ", limit=" + limit + ", cached=" + rows.size());
            // 供 Provider 的标题行读取总数（集合视图本身拿不到聚合值）
            TodoWidgetPrefs.setPendingTotal(context, total);
        }

        @Override
        public void onDestroy() {
            pending = new ArrayList<>();
        }

        @Override
        public int getCount() {
            return pending.size();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= pending.size()) return null;
            TodoItem item = pending.get(position);

            int bgAlpha = TodoWidgetPrefs.getBgAlpha(context);
            RemoteViews views = new RemoteViews(context.getPackageName(),
                    R.layout.widget_todo_item);
            views.setTextViewText(R.id.itemTitle, ellipsize(item.title, MAX_TITLE_LENGTH));
            views.setTextViewText(R.id.itemSub, buildSubtitle(item));
            views.setTextViewText(R.id.itemStatus,
                    context.getString(R.string.widget_todo_status_pending));
            views.setTextColor(R.id.itemTitle,
                    TodoWidgetPrefs.primaryTextColor(context, bgAlpha));
            views.setTextColor(R.id.itemSub,
                    TodoWidgetPrefs.secondaryTextColor(context, bgAlpha));
            views.setTextColor(R.id.itemStatus,
                    TodoWidgetPrefs.primaryTextColor(context, bgAlpha));
            views.setOnClickPendingIntent(R.id.itemRoot,
                    buildOpenPendingIntent(context, ROW_REQUEST_BASE + (int) item.id));
            return views;
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            return pending.get(position).id;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        /**
         * 副标题：优先时间提示，其次补充说明，最后标注来源。
         * 目的是补足主标题没表达完的信息，而不是重复标题。
         */
        private String buildSubtitle(TodoItem item) {
            if (!TextUtils.isEmpty(item.time)) {
                return ellipsize(item.time, MAX_SUB_LENGTH);
            }
            if (!TextUtils.isEmpty(item.description)) {
                return ellipsize(item.description, MAX_SUB_LENGTH);
            }
            return item.source != null && "ai".equals(item.source) ? "来自日记" : "普通待办";
        }

        private String ellipsize(String text, int maxLength) {
            if (text == null) return "";
            return text.length() <= maxLength ? text : text.substring(0, maxLength) + "…";
        }

        private android.app.PendingIntent buildOpenPendingIntent(Context ctx, int requestCode) {
            Intent intent = new Intent(ctx, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SCHEDULE);
            return android.app.PendingIntent.getActivity(ctx, requestCode, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);
        }
    }
}
