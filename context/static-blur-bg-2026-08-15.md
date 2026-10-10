# 静态毛玻璃背景实现（bg_frosted 径向渐变方案）

## 2026-08-15 第二轮 — 修复"背景出现椭圆形"bug

### 现象

真机上背景色斑被一条大椭圆弧线切断，用户看到"背景是个椭圆形"。

### 根因

shape 用了 `oval`：GradientDrawable 会把渐变**裁剪在 shape 路径内**，
而 oval 路径 = 内切于全屏 View 边界的椭圆。数学验证（内切椭圆方程
(x-0.5)²/0.25+(y-0.5)²/0.25=1）：

- 左上色斑中心 (0.08, 0.04) → 1.55，椭圆外，大半被削
- 右上色斑中心 (0.96, 0.18) → 1.26，椭圆外，一半被削
- 底部色斑中心 (0.30, 0.96) → 1.01，压线，下半被削

贴边色斑被这条不可见椭圆边界削出的弧线，即用户看到的"椭圆形"。
CSS radial-gradient 无任何容器裁剪，所以 HTML 预览看不到此问题。

### 修复

drawable/bg_frosted.xml 与 drawable-night/bg_frosted.xml：
- `shape="oval"` → `shape="rectangle"`（矩形路径=全屏，渐变圆形不再被削）
- 删除无效的 `<size>`（fill bounds 下 intrinsic size 无意义）
- 两文件头部加注释说明此坑

编译验证：assembleDebug exit 0。

### 教训

**Android shape 渐变会被 shape 路径裁剪**。做"全屏径向色斑"必须用
rectangle，oval 只适用于"渐变确实要被限制在椭圆内"的场景。

---

## 2026-08-15 第三轮 — 卡片/输入框/chip 质感 1:1 复刻（Layer 2）

### 背景

用户反馈"预览里的对话、输入框、显示框质感完全不一样"。逐项对比诊断，
预览的质感配方本来就是静态属性（文件头注明不用 backdrop-filter），
安卓侧只是参数走样，可近乎 1:1 复刻。

### 差异诊断表（改前 → 改后）

| 元素 | HTML 预览 | 安卓改前 | 安卓改后 |
|------|-----------|----------|----------|
| 卡片底色 | rgba(255,255,255,0.88) | #FFFFFF 不透明 | #E0FFFFFF |
| 卡片描边 | rgba(255,255,255,0.55) 白高光 | #E8E4DF 不透明灰 | #8CFFFFFF |
| 卡片阴影 | blur 3px+20px 双层渐隐 | 底部露 4dp #1A000000 硬边块 | 3 层递减 alpha 圆角矩形渐隐 |
| 深色卡片 | rgba(30,30,30,0.88) | #1E1E1E 不透明 | #E01E1E1E + #1AFFFFFF 边 + 加深阴影 |
| 输入框底 | rgba(255,255,255,0.55) | #F5F3F0 不透明 | #8CFFFFFF（night #8C2A2A2A） |
| chip | 16% 绿胶囊无边 | 15% 绿 + 10dp 圆角 + 灰边 | 保留绿底 + 20dp 胶囊 + 24% 白高光 |
| 底部导航 | rgba(250,248,245,0.78) | #FAF8F5 不透明 | #C7FAF8F5（night #CC121212），色斑可延伸到导航下 |

### 柔和阴影实现方式（自绘多层法）

CSS `box-shadow: 0 1px 3px a.05, 0 6px 20px a.06` 的 Android 复刻：
layer-list 从下到上 3 层（最外最淡 alpha 4% → 中 5% → 接触 7%），
主卡片 inset left/top/right=1dp bottom=5dp 露出渐变影。
layer-list 无法超出 view bounds，故用 inset 收缩主卡片而非外扩阴影层。
深色版阴影 alpha 提至 15%/20%/25%。

### 修改文件清单

- values/colors.xml：glass_input→#8CFFFFFF、glass_nav→#C7FAF8F5
- values-night/colors.xml：glass_input→#8C2A2A2A、glass_nav→#CC121212
- drawable/bg_card.xml、drawable/bg_card_inner.xml（重写）
- drawable-night/bg_card.xml、drawable-night/bg_card_inner.xml（重写）
- drawable/bg_tag_chip.xml：胶囊 + 白高光边
- drawable/bg_api_input.xml：非聚焦描边灰→#59FFFFFF
- drawable/bg_page_label.xml：描边→#8CFFFFFF；新增 drawable-night/bg_page_label.xml（#1AFFFFFF）

### Bug 检查记录

- bg_card 半透明影响 dialog_reflection/activity_login/usage_detail：
  Dialog 有 dim 层、登录页纯色底，88% 白均可读，无破坏
- 卡片叠卡片（diary/track inner 卡）：88%×2 叠加仍可读
- bg_tag_chip radius 20dp 超半高，GradientDrawable 自动 clamp 成胶囊
- chip 深色底 flashback_tag_bg 有 night 色值自动适配，白高光 24% 深浅均 OK
- fragment_record 卡片 elevation=2dp 与自绘柔影叠加：可接受（HTML 本身双影）
- 编译 assembleDebug exit 0

### 遗留微调项（用户真机验收后）

- record 页 EditText 本身透明（在玻璃卡上），若想完全对齐预览的
  --bg-input 55% 白块观感，可再包一层
- bg_api_input 深色下 35% 白描边无 night 变体（可接受，聚焦态绿边保留）
- 柔影浓度/扩散若与预览有差，调 bg_card 三层 alpha（浅 0A/0D/12，深 26/33/40）


## 2026-08-15 — Layer 1 落地（方案 A：XML 径向渐变）

### 背景

用户长期想实现"HTML 预览里好看的毛玻璃"，此前实时模糊路线失败：
- BlurView 循环引用（blurView 模糊自身父容器）→ libhwui.so 栈溢出（见 blurview-crash-fix-2026-06-27.md）
- minSdk 26 无 RenderEffect（API 31+），实时模糊走 RenderScript 慢路径 → 卡顿

### 核心结论（第一性原理）

背景色斑是纯装饰，"出生时就可以是模糊的"，根本不需要跑模糊算法。
HTML 预览的 Layer 1 用 radial-gradient 实现（无 filter:blur），
安卓原生的零成本对等物 = GradientDrawable radial 渐变 layer-list。

### 三条路径对比（已论证，选 A）

| 路径 | 结论 |
|------|------|
| A. XML 径向渐变 layer-list | **选用**：0 行 Java，与 HTML 预览同源，fraction 定位自动适配屏幕，drawable-night 零成本切深色 |
| B. 启动时生成 Bitmap | 备选升级路径（可做多段衰减最接近真高斯），需要线程/缓存/主题监听，暂不用 |
| C. 静态 WebP 图片 | 调参要重新出图，需浅/深两套，不选 |

### 本次修改

1. **drawable/bg_frosted.xml（新建）**
   - 底色 @color/bg_primary + 6 个 oval shape radial 渐变色斑
   - 位置用 centerX/centerY fraction（0.08/0.04 等），半径 190-260dp 固定值
   - 浅色色斑：green_primary #8B9A7D / brown_light #C4A77D / 辅助淡绿 #A7C4A0 / brown_accent #B8A99A / brown_dropdown #D4B78D，startColor alpha 0x42-0x52
2. **drawable-night/bg_frosted.xml（新建）**
   - 同位置，颜色换 night 提亮色（#9DAD8E/#D4B78D/#C8B9AA），alpha 整体降至 0x1F-0x33
3. **layout/activity_main.xml**
   - 根 FrameLayout 最底层插入 View(id=frostBackground, background=@drawable/bg_frosted)
   - 位于 fragment 容器 LinearLayout 之下，fragment 透明背景可透出
4. **layout/fragment_insight.xml（bug 修复）**
   - 底部输入区容器原 android:background="@color/bg_primary"（不透明）会切断色斑形成"实底带"，已移除
   - 该容器是垂直排列兄弟节点非叠层，移除背景无内容穿透风险

### Bug 检查记录

- 5 个 fragment 根视图确认均为 @android:color/transparent（grep 验证）
- fragment_track/diary/record 无不透明 bg_primary 遮挡（仅 insight 有一处，已修）
- MainActivity.java 无根背景代码级覆盖（仅 dot 背景切换）
- themes.xml windowBackground=@color/bg_primary，启动底色一致
- radial gradient 需 API 26+（gradientRadius 必填），minSdk 26 满足
- 编译验证：assembleDebug exit 0

### 性能特征

- 完全静态 drawable，系统缓存渲染结果，每帧零额外计算
- 无动画、无 invalidate、无 bitmap 生成
- 与旧 bg_glass_blobs 方案的关键区别：无"硬边色块"，渐变天然柔和

### 设计参数调参工作流

在 static_blur_preview.html（浏览器秒级迭代）调 Layer 1 色斑参数 →
中心百分比原样搬进 XML，半径 px→dp，颜色加 8 位 hex alpha。

### 关键文件

- d:\Coding\AISummary\LifeTrack\static_blur_preview.html（HTML 预览，含实时模糊对比实验）
- app/src/main/res/drawable/bg_frosted.xml
- app/src/main/res/drawable-night/bg_frosted.xml
- app/src/main/res/layout/activity_main.xml

### 后续可选（未做）

- BottomNavigationView 的 glass_nav 目前不透明 #FAF8F5，可改半透明让色斑延伸到导航栏
- 若觉得单段衰减不够圆润，升级到方案 B（多段衰减 shader）
