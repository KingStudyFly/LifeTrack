# LifeTrack UI 改进规划

通读了全部布局文件、样式、主题、颜色和字符串资源后，以下是按优先级排列的改进建议。

---

## 一、结构层面（影响面大，建议优先处理）

### 1. 状态栏沉浸式

目前所有 Fragment 硬编码 `paddingTop="60dp"` 来避开状态栏。没有用到 WindowInsets，在不同设备（有刘海、挖孔屏）上表现不一致。

**做法：** 在 `MainActivity` 中启用 edge-to-edge，让 Fragment 使用 `WindowInsetsCompat` 获取真实的系统栏高度。`values/dimens.xml` 里的 `page_padding_top` 改为通过代码动态设置。

### 2. Fragment 切换动画

当前 Fragment 切换没有过渡动画，tab 切换体验生硬。

**做法：** 用 `FragmentTransaction.setCustomAnimations()` 添加简单的 fade 或 slide 动画，不需要复杂，300ms 的 alpha 过渡就有明显改善。

### 3. BottomNavigationView 样式优化

5 个 tab 已经是底部导航的上限了。当前的问题：
- 图标尺寸和 label 字号没有自定义，依赖 Material 默认值
- 没有 badge 机制（比如未读洞察数量）

**做法：** 给 BottomNavigationView 加上 `labelVisibilityMode="labeled"` 已做了，但可以增加自定义的 BadgeDrawable，在 Insight tab 上显示未读消息数。图标的 active/inactive 状态对比度可以再加强。

---

## 二、各页面具体改进

### RecordFragment（记录页）

| 问题 | 改进 |
|------|------|
| 输入框是裸 EditText，视觉重量不够 | 给输入框加顶部装饰线或左侧竖条，暗示可编辑区域 |
| 录音/图片按钮文字太小（12sp），颜色只有 green_primary | 增大到 13sp，给按钮加背景色块区分当前模式 |
| 保存按钮是右下角的小药丸 | 如果输入框有内容时，保存按钮变色/发光提示可提交 |
| 没有字数统计 | 右下角加字数指示器（不影响主流程，纯信息展示） |
| Flashback 区域默认 gone，没有任何引导 | 在没有 flashback 时显示一行引导文字，比如"坚持记录，回顾即将解锁" |
| 没有情绪/标签快捷选择 | 可以在输入框上方加一排可选的情绪标签（可选改进，不影响核心流程） |

### TrackFragment（轨迹页）

| 问题 | 改进 |
|------|------|
| 自定义下拉菜单（dropdownMenu）手动管理 visibility | 换成 Material3 的 `ExposedDropdownMenu`，减少代码量和 bug 面积 |
| 周期切换的左右箭头只有 28dp，触摸区域偏小 | 放大到至少 44dp（符合触摸目标最小尺寸） |
| 趋势图是 Canvas 手绘的 | 短期不改也行，但如果后续想支持手势缩放、tooltip，建议迁移到 MPAndroidChart |
| 洞察标签区域只是纯文本列表 | 换成 `ChipGroup`，用不同颜色的 chip 区分人物/事件/情绪等类别 |

### DiaryFragment（日记页）

| 问题 | 改进 |
|------|------|
| 日历格子里只有有/无日记两种状态（通过 dot_diary / dot_event） | 可以根据当天日记条数调节 dot 大小或颜色深浅，让日历有"热力图"的感觉 |
| 搜索栏永远显示 | 搜索栏默认收起，点击搜索图标后展开，释放垂直空间 |
| 日记条目没有图片缩略图 | 如果条目包含图片，在条目前加小缩略图预览 |
| 编辑操作只有文字按钮 | 加上左滑删除的手势，比点编辑再点删除快 |

### InsightFragment（洞察页）

| 问题 | 改进 |
|------|------|
| AI 回复和用户消息没有头像区分 | 用户消息右侧对齐+蓝色气泡，AI 消息左侧对齐+绿色气泡，各自加小头像 |
| 发送按钮复用了 `ic_nav_insight` | 用语义明确的发送图标（箭头或纸飞机） |
| 没有预设问题提示 | 在输入框上方加几个可点击的推荐问题 chip，降低冷启动门槛 |
| 流式回复没有视觉反馈 | 添加末尾的打字指示器（三个跳动圆点） |
| 回复是纯文本 | 如果后端返回的内容有结构（列表、分段），可以做简单的 Markdown 渲染 |

### ProfileFragment（我的页）

| 问题 | 改进 |
|------|------|
| 头像区域是纯 placeholder，没有实际功能 | 支持点击更换头像（拍照/相册），这个功能投入小、体感提升大 |
| 统计数字只有冷冰冰的数字 | 数字下方加一个迷你趋势线（sparkline），或者加一个"比上周 +X%"的对比 |
| "网络连接"卡片放在个人区域里，语义不匹配 | 改为"系统状态"或移到设置页面 |
| 菜单项是纯文本列表 | 给每个菜单项配独立图标和颜色（已部分实现 bg_menu_icon_* 系列，但没有统一运用） |
| 没有退出登录确认 | 已有 `profile_logout_confirm` 字符串，确认实际是否弹出了确认 dialog |

### LoginFragment（登录页）

| 问题 | 改进 |
|------|------|
| 微信/手机/Google 三个社交登录按钮内部是空的 View，没有图标 | 填充对应的品牌图标，否则看起来像占位符 |
| Tab 切换是手动实现的两个 TextView | 换成 Material3 `TabLayout`，一行代码解决问题，还自带动画 |
| 密码输入框没有可见性切换 | 加一个 eye icon 切换 `inputType` |
| 返回按钮的图标是旋转 90 度的 arrow_down | 直接用 `ic_chevron_left` |

---

## 三、视觉一致性

### 1. 废弃 API 修复

- `ic_nav_insight.xml` 里的 `android:tint` 改为 `app:tint`（`android:tint` 在 API 31 已废弃）
- 卡片阴影全部用 `elevation`，没有用 Material3 的 `android:translationZ` + surface tint，在深色模式下阴影效果不佳

### 2. 圆角统一

当前卡片用 `bg_card` drawable，圆角是 `card_radius=16dp`。但部分组件的圆角不一致：
- 输入框 `bg_api_input` 的圆角需要确认是否和卡片一致
- 保存按钮 `bg_save_btn_pill` 是全圆角（pill shape），这个没问题
- Dropdown `bg_dropdown_trigger` 和 `bg_dropdown_menu` 的圆角需要确认

建议在 `dimens.xml` 里统一定义 `corner_radius_sm`、`corner_radius_md`、`corner_radius_lg`。

### 3. 深色模式完善

`values-night/colors.xml` 已经存在，`themes.xml` 里 `colorOnPrimary` 和 `colorOnSecondary` 在深色模式下改成了 `black`。但以下地方可能遗漏：
- `item_diary_entry.xml` 里的分割线颜色直接引用 `border_light`，确认深色值是否覆盖
- `item_insight_message.xml` 的背景色是在 Java 代码里动态设置的，需要确认是否读取了 night color
- Dialog 的背景色需要检查是否适配了深色

---

## 四、交互细节

| 改进点 | 说明 |
|--------|------|
| 触摸反馈 | 关键操作（保存、删除）加 haptic feedback |
| 空状态设计 | Track 和 Diary 的空状态只有一行文字，可以加一个插画或动画 |
| 下拉刷新 | DiaryFragment 加 SwipeRefreshLayout |
| 列表动画 | 日记列表和洞察消息列表加 itemAnimator |
| 键盘遮挡 | RecordFragment 输入多行文字时，确保输入框不被键盘遮挡（`android:windowSoftInputMode="adjustResize"`） |

---

## 五、建议的实施顺序

1. **状态栏沉浸式 + WindowInsets** — 基础设施，一次修好所有页面受益
2. **Fragment 切换动画** — 改动小，体感提升明显
3. **InsightFragment 的聊天 UI 优化** — 这是用户高频使用的 AI 交互页面，值得投入
4. **DiaryFragment 日历热力图 + 下拉刷新** — 让日记浏览更有吸引力
5. **RecordFragment 输入区增强** — 引导文字、字数统计、保存按钮状态
6. **ProfileFragment 头像和统计卡片** — 个人页的完成度
7. **LoginFragment 修复** — TabLayout 替换、社交登录图标、密码可见性切换
8. **深色模式全面检查** — 逐页面验证
9. **微交互和动画** — 空状态插画、列表动画、haptic feedback

---

以上都是基于现有代码结构提出的改动方向，每项都可以独立实施，不影响其他模块。
