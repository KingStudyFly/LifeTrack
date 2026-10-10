# 毛玻璃效果预览规范实施

## 2026-06-27 — 五页面标签 + bg-blob 补齐

### 任务背景
用户提供《LifeTrack 毛玻璃效果预览 — 五个界面详细描述》规范文档，要求按规范修改 Android UI。规范涵盖整体框架、5 个页面（Record/Track/Diary/Insight/Profile）、毛玻璃规格表、配色体系。

### 已完成修改
1. **drawable/bg_glass_blobs.xml + drawable-night/bg_glass_blobs.xml**
   - 补齐 blob 6 (#8B9A7D 28%, 120dp) 和 blob 7 (#C8B9AA 32%, 110dp)
   - 由 5 个色块补齐到规范要求的 7 个
   - 深色版本同步使用 #9DAD8E 提亮色

2. **drawable/bg_page_label.xml（新建）**
   - 8dp 圆角 + 0.5dp glass_border_subtle 边框
   - 背景使用 glass_input（55% 半透明白）
   - 作为 5 个页面标签的统一背景 drawable

3. **values/strings.xml**
   - 添加 page_label_record/track/diary/insight/profile 5 个字符串资源
   - 避免硬编码英文标签文本

4. **layout/fragment_record.xml**
   - 根视图由 ScrollView 改为 FrameLayout 包裹 ScrollView
   - 末尾添加 RECORD 标签（top|end, marginTop 20dp, marginEnd 20dp, 10sp, elevation 3dp）

5. **layout/fragment_track.xml**
   - 原根视图已是 FrameLayout，直接添加 TRACK 标签

6. **layout/fragment_diary.xml**
   - 根视图由 SwipeRefreshLayout 改为 FrameLayout 包裹 SwipeRefreshLayout
   - 添加 DIARY 标签

7. **layout/fragment_insight.xml**
   - 根视图由 LinearLayout(id=insightRoot) 改为 FrameLayout 包裹
   - 添加 INSIGHT 标签
   - **预检**：grep 确认 insightRoot ID 在 Java 代码中无引用，安全包裹

8. **layout/fragment_profile.xml**
   - 根视图由 ScrollView 改为 FrameLayout 包裹 ScrollView
   - **bug 修复**：原本添加的 PROFILE 标签与右上角 settings 按钮（40×40dp, y=16-56dp）位置冲突，标签 elevation 3dp 会遮住按钮图标，已移除该标签

### Bug 检查报告

#### 关键修复
- **Profile 标签位置冲突（已修复）**：settings 按钮占据右上角，添加 PROFILE 标签会导致视觉冲突，已移除

#### 验证安全（无问题）
- Java 代码中无 `(ScrollView)/(LinearLayout)/(FrameLayout)` 类型强转根视图的代码，包裹 FrameLayout 不会触发 ClassCastException
- 所有 `getView()` 调用直接当 `View` 用，未做强转
- `swipeRefresh` / `nestedScrollView` 通过 `view.findViewById()` 访问，与新根视图兼容
- `page_padding_top = 16dp`，标签 marginTop 20dp 位于顶部边缘下方，位置合理
- 4 个保留标签的页面（Record/Track/Diary/Insight）右上角无其他元素冲突
- Track 的 dropdownMenu 位于 `top|start`（左侧），与右侧标签无冲突
- 标签 elevation 3dp 高于内容卡片 2dp，正确浮于上方
- 标签 `importantForAccessibility="no"` 正确隐藏于无障碍服务

#### 规范差异（既有问题，非本次引入）
- 规范要求 Insight 页面 padding-top 56px，但现有布局为 16dp（page_padding_top）
- 规范要求 INSIGHT 标签 top:56px，本次实现使用 20dp 以保持与其他页面一致
- 真正的 backdrop-filter 模糊效果在标准 Android XML 中无法实现，bg_page_label 仅用半透明色近似

### 关键文件路径
- d:\Coding\AISummary\LifeTrack\frosted_glass_preview.html（HTML 参考实现，1390 行）
- d:\Coding\AISummary\LifeTrack\app\src\main\res\drawable\bg_glass_blobs.xml
- d:\Coding\AISummary\LifeTrack\app\src\main\res\drawable\bg_page_label.xml
- d:\Coding\AISummary\LifeTrack\app\src\main\res\layout\fragment_{record,track,diary,insight,profile}.xml

## 2026-06-27 第二轮 — 装饰层叠加 + 模式指示器 + 导航分割线

### 任务背景
用户反馈"感觉没变化"，发现第一轮修改的 5 个小标签和补齐的 blob 色块视觉上不明显。
深查发现：bg_glass_blobs 色块装饰层根本没被任何布局引用（layout_glass_background.xml
存在但无人 include），缺顶部模式指示器，缺底部导航顶部分割线。

### 已完成修改
1. **activity_main.xml 重构**
   - 在根 FrameLayout 最底层叠加 `<View background="@drawable/bg_glass_blobs">`
   - 顶部加 LinearLayout(id=modeIndicator) + 5 个 View(dot0~dot4) 圆点
   - 底部导航上方加 0.5dp glass_border_subtle 分割线 View

2. **drawable/dot_mode_indicator_active.xml（新建）**
   - 8dp 圆点，green_primary 色（深色自动通过 values-night 取 #9DAD8E）

3. **drawable/dot_mode_indicator_inactive.xml（新建）**
   - 浅色版：#26000000（rgba(0,0,0,0.15)）

4. **drawable-night/dot_mode_indicator_inactive.xml（新建）**
   - 深色版：#33FFFFFF（rgba(255,255,255,0.2)）

5. **MainActivity.java**
   - 新增 DOT_IDS 数组与 activeDotIndex 字段
   - 新增 updateModeIndicator(int) 方法切换圆点背景
   - switchFragment 末尾调用 updateModeIndicator 同步高亮
   - savedInstanceState != null 分支末尾也调用，修复旋转后圆点不同步 bug

### Bug 检查报告

#### 已修复 bug
- **旋转后模式指示器圆点不同步**：savedInstanceState 恢复后 activeFragment 正确但 activeDotIndex
  仍为 0。在恢复分支末尾调用 updateModeIndicator(getTabIndex(activeFragment)) 修复

#### 验证安全（无问题）
- bg_glass_blobs View 默认 clickable=false，不拦截 fragment 触摸事件
- 模式指示器 elevation 10dp 高于 fragment 内容，正确浮于上方
- 模式指示器水平居中，与左对齐的页面标题、右对齐的 RECORD/TRACK 等标签水平位置不冲突
- bg_frosted（预渲染模糊图）+ bg_glass_blobs（色块）+ fragment 内容三层叠加顺序正确
- 5 个 fragment 根视图均为 `@android:color/transparent`，bg-blob 色块会透出
- DOT_IDS 顺序与 getTabIndex 返回值一致：record=0, track=1, diary=2, insight=3, profile=4
- activity_main.xml 中 dot0 初始背景为 active，与 activeDotIndex=0 一致
- 模式指示器 marginTop 12dp 在 systemBars padding 之下，避开状态栏

#### 技术限制（无法在原生 XML 实现）
- bg-blob 的 filter:blur(100px) 无法在标准 Android XML 实现，只能用半透明色块近似
- 真正的 backdrop-filter 需要 BlurView 库，但历史已尝试会触发栈溢出崩溃
  （见 context/blurview-crash-fix-2026-06-27.md），不重新引入

## 2026-06-27 第三轮 — 卡片不透明 + 阴影厚实感

### 任务背景
用户反馈卡片半透明（glass_card #A8FFFFFF）效果不好，要求卡片不透明 + 加阴影有厚实感。

### 已完成修改
1. **drawable/bg_card.xml 重构为 layer-list**
   - 底层：淡黑色 #1A000000 占满 View
   - 上层：主卡片 @color/bg_card（#FFFFFF 不透明），bottom=4dp inset 露出底层阴影
   - 圆角 20dp，0.5dp glass_border_subtle 描边

2. **drawable/bg_card_inner.xml 重构为 layer-list**
   - 底层：#14000000 占满
   - 上层：@color/bg_card_inner（#FFFFFF），bottom=3dp inset

3. **drawable-night/bg_card.xml（新建）**
   - 深色模式阴影用 #66000000（更深黑，在 #121212 上可见）
   - 主卡片 @color/bg_card（深色 #1E1E1E 不透明）

4. **drawable-night/bg_card_inner.xml（新建）**
   - 阴影 #55000000，主卡片 @color/bg_card_inner（深色 #2A2A2A）

### 设计决策
- 不改 glass_card 颜色值（保留半透明给消息气泡/chip/主题 colorSurface 用）
- 只改 bg_card.xml / bg_card_inner.xml 的 solid 引用为不透明色 @color/bg_card
- 用 layer-list inset 方案而非负值（负值会被 View 默认 clipToPadding 裁剪）

### Bug 检查报告

#### 验证安全（无问题）
- 12 处用 @drawable/bg_card 的卡片，padding 均 ≥ 10dp（大于 4dp 阴影 inset），内容不被阴影覆盖
- fragment_profile settings 按钮 40dp + padding 10dp，图标 20dp 在中心，距底部 10dp > 4dp 安全
- fragment_profile 头像内层 90dp View 用 bg_card，外层 96dp bg_avatar_ring 描边会盖住边缘，影响小
- TrackFragment.java 动态 `card.setBackground(bg_card_inner)` 兼容 layer-list
- layer-list inset 不影响 View 内容摆放（内容按 View padding），只影响 drawable 绘制范围
- 圆角由 shape 内部 corners 控制，inset 后仍正确绘制

#### 已知限制
- layer-list 阴影是硬边偏移，不像 elevation 那样有真实模糊扩散
- 阴影只在底部，不是四周
- 配合 View elevation（1-3dp）叠加使用，整体厚实感增强

