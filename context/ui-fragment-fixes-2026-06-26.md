# 5 个 Fragment UI bug 全量修复

## 2026-06-26 — 36 项 UI bug 修复（仅改 Java，资源待补齐）

### 约束
- 只改 `app/src/main/java/com/example/lifetrack/` 下 5 个 Fragment 的 Java 文件
- 不动 strings.xml/colors.xml（资源下阶段补齐）
- 硬编码中文 → `getString(R.string.xxx)` 或 `requireContext().getString(...)`
- 命名前缀：`fragment_diary_xxx` / `fragment_insight_xxx` / `fragment_record_xxx` / `fragment_track_xxx` / `fragment_profile_xxx`（实际落地时 track 用了 `track_xxx`、`period_xxx` 等已有或就近命名）
- 用 Edit 工具改，不 Write 整个文件
- 改完自检空指针、并发问题

### DiaryFragment.java（items 1-7）
- DAO 构造统一改 `getApplicationContext()`
- 循环内 `return` 改 `break`，保留已收集的部分条目
- 硬编码星期数组改 `R.string.week_sun` 等
- "数据异常" 改 `R.string.error_data_error`
- 删除未调用的 `scrollToDiaryArea`
- measure 结果缓存到局部变量，避免重复 `measure()`
- editDialog 在 `onDestroyView` 主动 dismiss

### InsightFragment.java（items 8-13）
- `isDestroyed = false` 在 `onViewCreated` 重置（重建后可再次使用）
- 新增 `KEY_AI_RESPONSE_POSITION` 状态保存与恢复
- `latch.await` 加超时与中断检查（避免永远阻塞）
- 硬编码颜色改 `R.color`
- `ScaleAnimation` 提为静态字段复用，新增 `buildTypingAnim` 工厂方法

### ProfileFragment.java（items 14-16）
- 删除无用字段
- `registerForActivityResult` 从 `onViewCreated` 移到 final 字段初始化（必须在 STARTED 之前注册）
- `onDestroyView` 清理 View 引用

### RecordFragment.java（items 17-21）
- DAO 构造改 `getApplicationContext()`
- `onSaveInstanceState` 补全（保存草稿状态）
- 硬编码中文改 `record_xxx`
- stream 文本更新加 100ms 节流（避免高频 setText 卡顿）
- `isAdded()` return 前用 `new Handler(Looper.getMainLooper()).post(...)` dismiss dialog（注意：不能用 `runOnUiIfAlive`，因为其内部 `!isAdded()` 检查会直接 return 导致 dismiss 不执行）

### TrackFragment.java（items 22-36，共 15 项）

**状态保存与恢复**
- 新增 `STATE_SELECTED_POSITION` / `STATE_OFFSET_TIME` 常量
- `onSaveInstanceState` 保存 `selectedPosition` 和 `offsetCalendar.getTimeInMillis()`
- `onViewCreated` 恢复时 clamp 到 now（防止恢复出未来时间）
- `onViewCreated` 末尾 `loadPeriodSummary(0, false)` 改为 `loadPeriodSummary(selectedPosition, false)`

**维度显隐状态持久化**
- `dimensionVisible` 从局部变量提升为成员字段 `{true,true,true,true,true}`
- 标签创建后立即 `setAlpha(dimensionVisible[i] ? 1f : 0.3f)`（否则新标签全亮但图表已隐藏）

**Dialog 窗口泄漏防护**
- 新增 `activeDialogs` 列表 + `registerPickerDialog`/`unregisterPickerDialog`/`dismissAllPickerDialogs`
- `showDatePicker`/`showWeekPicker`/`showCustomPeriodPicker` 及嵌套 `DatePickerDialog` 全部注册
- `showNumberPickerDialog` 补 confirm 按钮 + `setOnCancelListener`
- `onDestroyView` 调 `dismissAllPickerDialogs()`

**其他**
- `updateGeneratingProgress(title, content)` 简化为 `updateGeneratingProgress()`
- 删除空的 `onDestroy()`
- `periodNames` 从 final 字段改非 final，`onViewCreated` 初始化（保证 getString 可用）
- `dimensionLabels` 改 `R.string.track_dim_mood` 等
- 所有硬编码中文转 `R.string.track_xxx`：周期 badge、空状态、AI 失败消息、标题、picker titles、formatter
- `dimensionColors` 改 `R.color.chart_dimension_*`
- chip 颜色改 `R.color.chip_*`；`0xFF8D6E63` → `R.color.chip_letter_bg`；`0xFFFFFFFF` → `R.color.white`；`0x15000000` → `R.color.empty_icon_bg`
- `gridPaint` 颜色 `0x1A000000` → `R.color.chart_grid`，移入 first-draw 缓存块
- 骨架屏 `0x18000000` → `R.color.skeleton_bg`
- chartView 加 `setContentDescription(getString(R.string.track_chart_desc))`
- `onDestroyView` 中 `preloadingKeys.clear()`

### 自检发现并修复的潜在 bug

1. **`onViewCreated` 中状态恢复被覆盖**：`offsetCalendar = Calendar.getInstance(Locale.US)` 在 savedInstanceState 恢复之后执行会覆盖恢复值。改为 `if (offsetCalendar == null)` 守卫。

2. **`onDestroyView` 中 `periodNames = null` 导致 NPE**：异步回调（如 `updateContentUI(periodNames[position], ...)`）可能在 onDestroyView 后执行。移除置 null 行，保留数组（纯字符串不持有 View/Activity 引用）。

3. **`dismissAllPickerDialogs` 的 ConcurrentModificationException**：for 循环 dismiss 时，OnDismissListener 会调 `unregisterPickerDialog` 修改 `activeDialogs`。改为先取快照 `new ArrayList<>(activeDialogs)`，再 `activeDialogs.clear()`，然后遍历快照 dismiss。

4. **`getPeriodLabel` 中未使用的 `dateRange` 变量**：删除该行，switch 重构为 fall-through 形式。

### 跳过项
- strings.xml/colors.xml 资源补齐（用户明确下阶段处理）

### 待补齐资源（下阶段）
TrackFragment 引用的 R.string/R.color 中，strings.xml 已存在：`period_daily`/`period_weekly`/`period_monthly`/`period_quarterly`/`period_yearly`/`period_custom`/`track_no_entries`/`track_no_ai`/`track_generating`/`track_generating_streaming`/`track_cached_hint`/`custom_period_*`/`month_picker_*`/`year_label`/`month_label`。

需新增的字符串（命名以 `track_` 开头）：`track_dim_mood`/`track_dim_energy`/`track_dim_sleep`/`track_dim_focus`/`track_dim_stress`（维度名）、`track_chart_desc`（无障碍描述）、`track_week_badge_format`/`track_month_badge_format`/`track_quarter_badge_format`/`track_year_badge_format`（周期 badge 格式）、`track_select_period`/`track_select_date`/`track_select_year_month`（picker 标题）等。

需新增的颜色（colors.xml）：`chart_dimension_mood`/`chart_dimension_energy`/`chart_dimension_sleep`/`chart_dimension_focus`/`chart_dimension_stress`、`chart_grid`、`chip_letter_bg`、`chip_mood`/`chip_energy`/`chip_sleep`/`chip_focus`/`chip_stress`、`empty_icon_bg`、`skeleton_bg`。

### 可复用的模式

**Dialog 窗口泄漏防护模式**（适用于所有持有 Dialog 的 Fragment）：
```java
private final java.util.List<Dialog> activeDialogs = new java.util.ArrayList<>();

private void registerPickerDialog(Dialog dialog) {
    if (dialog == null) return;
    activeDialogs.add(dialog);
}

private void unregisterPickerDialog(Dialog dialog) {
    activeDialogs.remove(dialog);
}

private void dismissAllPickerDialogs() {
    // 取快照再 clear，避免 dismiss 触发 OnDismissListener 调用 unregister 导致并发修改
    java.util.List<Dialog> copy = new java.util.ArrayList<>(activeDialogs);
    activeDialogs.clear();
    for (Dialog d : copy) {
        if (d != null && d.isShowing()) {
            try { d.dismiss(); } catch (Exception ignored) {}
        }
    }
}
// onDestroyView 中调用 dismissAllPickerDialogs()
```

**异步回调中 isAdded return 前 dismiss 的正确写法**：
```java
// 错误：runOnUiIfAlive 内部 !isAdded() 会 return，dismiss 不执行
// runOnUiIfAlive(this::dismissDialog);

// 正确：直接 post 到主线程，try-catch 包裹
new Handler(Looper.getMainLooper()).post(() -> {
    try { dismissDialog(); } catch (Exception ignored) {}
});
```

**状态恢复 clamp 到 now 模式**：
```java
if (offsetTime != Long.MIN_VALUE) {
    Calendar restored = Calendar.getInstance(Locale.US);
    restored.setTimeInMillis(offsetTime);
    Calendar now = Calendar.getInstance(Locale.US);
    if (restored.after(now)) restored = now;  // 防止恢复出未来时间
    offsetCalendar = restored;
}
```

## 2026-06-27 — 补齐 RecordFragment + TrackFragment 深度修复

### RecordFragment.java（补齐遗漏）
- 发现 5 处 `btnSave.setEnabled(false);` 未替换为 `setBtnSaveEnabled(false);`（handleMultipleImageResults/analyzeImageWithDoubao/analyzeMultipleImagesWithDoubao/saveDirectly/saveWithAiAnalysis），全部补齐
- 其中 #2/#3 位于 `activity.runOnUiThread` 异步回调内，btnSave 可能为 null，必须用 wrapper

### TrackFragment.java（7 项修复）
1. **P1 DAO 内存泄漏**：`SummaryDao`/`InsightDao`/`TrendDao` 构造改用 `requireContext().getApplicationContext()`
2. **P2 isDestroyed 标志**：新增 `volatile boolean isDestroyed`，`onViewCreated` 置 false、`onDestroyView` 置 true；`runOnUiIfAlive` 前置检查 + 包装 Runnable 二次检查，覆盖 onDestroyView→onDetach 之间 isAdded() 仍为 true 的窗口
3. **P2 ObjectAnimator textColor 泄漏**：dropdown 项文字颜色动画（200ms）加入 `skeletonAnimators` 列表复用管理；showSkeleton/renderContent/renderEmptyState/onDestroyView 四处均调用 cancelSkeletonAnimators() 清理
4. **P2 dimensionVisible 状态保存**：新增 `STATE_DIMENSION_VISIBLE` 常量，`onSaveInstanceState` 用 `putBooleanArray` 保存，`onViewCreated` 用 `System.arraycopy` 恢复
5. **P2 关闭按钮触摸目标**：`closeSize = dpToPx(28)` → `dpToPx(48)`（showNumberPickerDialog 和 showCustomPeriodPicker 各 1 处），满足 48dp 无障碍最小触摸目标
6. **P3 季度格式化**：`quarterPicker.setFormatter(value -> "Q" + value)` → `getString(R.string.track_quarter_format, value)`
7. **P3 onDraw 数组缓存**：匿名 View 类新增 `cachedValues` 字段，按 metricsSize 变化时才重新分配，避免每帧 GC

### 可复用的模式

**isDestroyed 双重检查模式**（覆盖 onDestroyView 后 isAdded() 仍为 true 的窗口）：
```java
private volatile boolean isDestroyed = false;

// onViewCreated
isDestroyed = false;

// onDestroyView（第一行）
isDestroyed = true;

private void runOnUiIfAlive(Runnable action) {
    if (isDestroyed || !isAdded()) return;
    Activity activity = getActivity();
    if (activity == null || activity.isFinishing()) return;
    // 二次检查：post 到 UI 线程前 onDestroyView 可能已执行
    activity.runOnUiThread(() -> {
        if (!isDestroyed && isAdded()) {
            action.run();
        }
    });
}
```

### 待补齐资源
- `R.string.track_quarter_format`：季度格式化字符串，如 `"Q%d"` 或 `"第%d季度"`

