# AI 洞察流式输出防颤抖 & 轨迹页"重新总结"按钮 — 2026-06-26

## 背景
用户反馈两项需求：
1. InsightFragment 中 AI 流式回复输出时，RecyclerView 每次调用 `notifyItemChanged` 会触发 DefaultItemAnimator 的默认 change animation（淡入淡出），造成视觉颤抖。
2. TrackFragment 周期切换行右上角缺少"重新总结"入口，无法在缓存已存在时强制重新生成 AI 总结。

## 解决方案

### 1. 消除 AI 洞察流式输出颤抖（双重方案）
- **禁用 change animation**：在 `InsightFragment.onViewCreated` 中给 `rvMessages` 设置 `DefaultItemAnimator` 并调用 `setSupportsChangeAnimations(false)`，保留 insert/remove 动画。
- **payload 机制**：`MessageAdapter` 新增 `STREAM_PAYLOAD = "stream_update"` 常量；重写 `onBindViewHolder(holder, position, payloads)`，当 payload 包含 `STREAM_PAYLOAD` 时仅刷新文本（不重设背景/头像等）。`onPartialResult` 调用 `notifyItemChanged(position, STREAM_PAYLOAD)`。

### 2. 轨迹页"重新总结"按钮
- **布局**：`fragment_track.xml` 将周期切换行 LinearLayout 宽度改为 `match_parent`，在 `btnNextPeriod` 后添加占位 `View`（weight=1）+ `btnRegenerate` TextView，右对齐。
- **资源**：`strings.xml` 新增 `track_regenerate` / `track_regenerate_confirm` / `track_regenerate_confirm_ok` / `track_regenerate_no_ai` / `cd_regenerate`。
- **DAO**：`SummaryDao` 新增 `deleteSummary(periodType, periodKey)` 用于清除缓存。
- **逻辑**：`TrackFragment` 新增 `confirmRegenerate()`（弹出 AlertDialog，含 isAdded/generatingDialog/AI 连接检查）和 `regenerateCurrentSummary()`（异步删除缓存后调用 `loadPeriodSummary(position, true)` 重新生成）。

## 关键决策

### 异步删除缓存后的周期切换竞态保护
`regenerateCurrentSummary` 先在 IO 线程执行 `summaryDao.deleteSummary`，再 post 回主线程调用 `loadPeriodSummary`。如果用户在此期间切换了周期，会错误地加载旧周期。

**修复**：在主线程回调中添加 `if (selectedPosition != position) return;` 检查。由于缓存已被删除，用户切回来时 `loadPeriodSummary` 会自动触发重新生成（走 generateAndCache 路径）。

### 未删除 insight/trend 缓存
重新总结时只删除 summary 缓存，不删除 insight/trend 缓存。原因：生成成功后 `saveInsightFromJson` / `saveTrendFromJson` 使用 `CONFLICT_REPLACE` 覆盖写入，无需预先清除。

## 修改文件清单
- `app/src/main/java/com/example/lifetrack/InsightFragment.java`
  - L122-127：DefaultItemAnimator 配置
  - L408：onPartialResult 调用 notifyItemChanged 传入 STREAM_PAYLOAD
  - L788：STREAM_PAYLOAD 常量
  - L837：onBindViewHolder payloads 重载
- `app/src/main/res/values/strings.xml`：L256-260 新增 5 项字符串
- `app/src/main/java/com/example/lifetrack/SummaryDao.java`：L186 新增 deleteSummary 方法
- `app/src/main/res/layout/fragment_track.xml`：L150 附近新增 btnRegenerate
- `app/src/main/java/com/example/lifetrack/TrackFragment.java`
  - L63：成员字段 btnRegenerate
  - L162：findViewById 绑定
  - L185-186：点击监听
  - L253：onDestroyView 清理引用
  - L583：confirmRegenerate 方法
  - L614：regenerateCurrentSummary 方法

## 验证
所有修改在文件中保留完整，包括周期切换保护逻辑（TrackFragment L627 `if (selectedPosition != position) return;`）。

## 2026-06-26 追加：流式输出颤抖根因修复

### 用户反馈
"还是会晃来晃去的，就是会冒出来一个新的消息气泡，那个气泡跟着头像随着文字输出一起晃，但其他地方不动"

之前的 `setSupportsChangeAnimations(false)` 只解决了 change 动画，但遗漏了两个根因：
1. **insert 动画**：`notifyItemInserted`（L403/404，AI 首个流式块到达时新增消息项）触发 DefaultItemAnimator 的 insert 动画（alpha 0→1 + translationY 下方上移），视觉上就是"冒出来一个新的消息气泡"。
2. **smoothScroll 动画叠加**：`scrollToBottom()` 内部用 `smoothScrollToPosition`，流式输出中高频调用会让平滑滚动动画不断重定向，与文字增长叠加，造成"气泡跟着头像随着文字输出一起晃"。

### 最终修复
1. **完全禁用 ItemAnimator**：`rvMessages.setItemAnimator(null)`（InsightFragment.java L122-126）
   - 彻底消除 insert/change/remove 所有动画
   - STREAM_PAYLOAD 机制仍保留，减少 onBindViewHolder 时重设背景/头像等不必要操作
2. **流式输出改用瞬时滚动**：
   - 新增 `scrollToBottom(boolean smooth)` 重载（L771-794）
   - 无参版本 `scrollToBottom()` 委托给 `scrollToBottom(true)`，保持向后兼容
   - `onPartialResult` 中调用 `scrollToBottom(false)`（L411），使用 `scrollToPosition` 瞬时跳转
   - 其他场景（用户发送消息、AI 完成、错误等）仍用 `smoothScrollToPosition`

### 关键决策
- **为什么保留 STREAM_PAYLOAD**：即使 ItemAnimator=null，payload 机制仍让 onBindViewHolder 仅更新文本，避免重设背景 drawable、头像可见性等不必要 view 操作，减少 measure/layout 开销。
- **为什么 isComplete 时也用瞬时滚动**：保持一致性，且完成时内容已稳定，瞬时滚动到底部让用户立即看到完整回答，无抖动风险。
- **为什么其他场景保留 smooth**：用户主动操作（发送消息）和 AI 完成后的滚动，平滑动画提供视觉过渡，不会与高频文字增长叠加，无抖动问题。
