package com.example.lifetrack;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 桌面小组件布局与配置的回归测试。
 *
 * 背景：曾出现「载入小组件失败」与「只显示 1 行 / 只显示 4 条」两类问题，
 * 根因分别是 RemoteViews 控件白名单限制与存量配置被沿用。
 * 这里把关键约束固化为测试，避免以后改布局时无声破坏。
 */
@RunWith(AndroidJUnit4.class)
public class TodoWidgetLayoutTest {

    /**
     * 主布局 + 单行布局都要能被 RemoteViews 正常膨胀。
     * RemoteViews 白名单不允许 <View>、ConstraintLayout 等，
     * 一旦误用，apply() 会抛 ActionException。
     */
    @Test
    public void layoutsInflateSuccessfully() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        RemoteViews root = new RemoteViews(context.getPackageName(), R.layout.widget_todo);
        root.setImageViewResource(R.id.widgetTodoBackground, R.drawable.bg_widget_todo);
        root.setInt(R.id.widgetTodoBackground, "setImageAlpha", 66);
        root.setTextViewText(R.id.widgetTodoTitle, "LifeTrack · 待办");
        root.setTextViewText(R.id.widgetTodoCount, "6 项未完成");
        root.setTextColor(R.id.widgetTodoTitle, 0xFFFFFFFF);
        root.setTextColor(R.id.widgetTodoCount, 0x99FFFFFF);
        root.setTextColor(R.id.widgetTodoEmpty, 0x99FFFFFF);

        RemoteViews item = new RemoteViews(context.getPackageName(), R.layout.widget_todo_item);
        item.setTextViewText(R.id.itemTitle, "示例待办事项标题");
        item.setTextViewText(R.id.itemSub, "普通待办");
        item.setTextViewText(R.id.itemStatus, "未完成");
        item.setTextColor(R.id.itemTitle, 0xFFFFFFFF);
        item.setTextColor(R.id.itemSub, 0xB3FFFFFF);
        item.setTextColor(R.id.itemStatus, 0xFFFFFFFF);

        // apply() 会递归执行全部 Action，不抛异常即说明宿主支持这些操作。
        // 注意：apply() 不会把子 View 挂到传入容器上，故不能 findViewById 断言。
        root.apply(context, new FrameLayout(context));
        item.apply(context, new FrameLayout(context));

        assertNotNull("主布局未能渲染", root);
        assertNotNull("单行布局未能渲染", item);
    }

    /** 背景亮到接近纯白时白字不可读，文字颜色必须随不透明度反色 */
    @Test
    public void textColorFlipsWhenBackgroundIsBright() {
        assertFalse("半透明背景下应使用白字",
                TodoWidgetPrefs.needsDarkText(TodoWidgetPrefs.DEFAULT_BG_ALPHA));
        assertTrue("背景接近纯白时应切换为深色字",
                TodoWidgetPrefs.needsDarkText(100));
        assertTrue("过半阈值应切换为深色字", TodoWidgetPrefs.needsDarkText(60));
    }

    /** 显示条数上限需被正确钳制 */
    @Test
    public void maxItemsIsClamped() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        TodoWidgetPrefs.setMaxItems(context, 99);
        assertEquals(TodoWidgetPrefs.MAX_ITEMS_LIMIT, TodoWidgetPrefs.getMaxItems(context));
        TodoWidgetPrefs.setMaxItems(context, 0);
        assertEquals(1, TodoWidgetPrefs.getMaxItems(context));
    }

    /**
     * 回归：上限从 4扩到 10 后，旧版本存下的 max_items=4 会被沿用，
     * 导致「数据有 6 项但只显示 4 条」。迁移必须重置该值。
     */
    @Test
    public void migrationResetsLegacyMaxItems() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences sp = context.getSharedPreferences("lifetrack_widget",
                Context.MODE_PRIVATE);
        sp.edit()
                .putInt("widget_config_version", 2)
                .putInt("widget_max_items", 4)
                .commit();

        int value = TodoWidgetPrefs.getMaxItems(context);

        assertTrue("旧版 max_items=4 未被迁移重置，实际=" + value, value > 4);
        assertEquals(3, sp.getInt("widget_config_version", -1));
    }

    /** 集合视图服务必须在清单中注册，否则 ListView 拿不到数据 */
    @Test
    public void widgetServiceIsRegistered() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.pm.PackageInfo info = context.getPackageManager()
                .getPackageInfo(context.getPackageName(),
                        android.content.pm.PackageManager.GET_SERVICES);
        boolean found = false;
        if (info.services != null) {
            for (android.content.pm.ServiceInfo s : info.services) {
                if (TodoWidgetService.class.getName().equals(s.name)) {
                    found = true;
                    break;
                }
            }
        }
        assertTrue("TodoWidgetService 未在清单中注册", found);
    }

    /**
     * 回归：单行根节点若声明 clickable/focusable，系统会在长按滚动时
     * 对该行播放默认按压/缩放动画，表现为「组件被选中缩小」。
     * 行点击由 ListView 处理，不需要这两个属性。
     */
    @Test
    public void widgetItemRootHasNoPressableState() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.view.View root = android.view.LayoutInflater.from(context)
                .inflate(R.layout.widget_todo_item, null, false);

        assertFalse("item 根节点不应 clickable，否则滚动长按会播放按压动画",
                root.isClickable());
        assertFalse("item 根节点不应 focusable，否则滚动长按会有选中态",
                root.isFocusable());
    }

    /**
     * ListView 的按压高亮与拖动缓存色都要透明，否则滚动时分别出现
     * 高亮块与黑底。
     *
     * 注意：不要加 overScrollMode="never" 这类「顺手优化」属性——
     * 实测在部分 launcher 上会让集合视图连不上服务，列表恒空。
     */
    @Test
    public void listViewDisablesScrollArtifacts() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.view.View root = android.view.LayoutInflater.from(context)
                .inflate(R.layout.widget_todo, null, false);
        android.widget.ListView list = root.findViewById(R.id.widgetTodoList);

        assertNotNull("ListView 缺失", list);
        assertEquals("cacheColorHint 应为透明", 0, list.getCacheColorHint());
    }
}
