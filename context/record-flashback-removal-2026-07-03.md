# 记录页"小回顾"功能彻底删除

## 2026-07-03 — 删除记录页 flashback 整块功能

### 任务背景
用户要求彻底删除"记录"界面的回顾功能（小回顾卡片 + 查看完整内容对话框 + AI 挑选 3 篇日记的逻辑）。

### 已完成删除

#### 1. 布局文件
- `layout/fragment_record.xml`：删除 `flashbackSection` 整块（3 张卡片 + empty/loading 占位 + 标题栏），保留标题/副标题/tips/输入卡片/page_label
- `layout/dialog_flashback_view.xml`：删除整个文件

#### 2. drawable 资源（删除 4 个）
- `drawable/bg_flashback_card.xml`
- `drawable/bg_flashback_reflection.xml`
- `drawable/bg_flashback_stripe_gold.xml`
- `drawable/bg_flashback_stripe_green.xml`

#### 3. strings.xml
删除 11 个字符串：`flashback_title`、`flashback_subtitle`、`flashback_tag_milestone`、`flashback_tag_daily`、`flashback_last_year`、`flashback_days_ago`、`flashback_view_title`、`flashback_tag_picked`、`flashback_close`、`record_flashback_empty_hint`、`record_flashback_loading`、`record_flashback_pick_failed`

#### 4. colors.xml + values-night/colors.xml
- 删除 `flashback_stripe_brown`（无引用）
- 删除 `glass_flashback`（无引用）
- 保留 `flashback_tag_bg`（被 `bg_tips_bar.xml` 复用为通用淡绿半透明底色，更新注释说明）

#### 5. RecordFragment.java
- 删除 import：`android.content.SharedPreferences`、`java.util.Calendar`（仅 flashback 用）
- 删除 16 个字段：`flashbackSection`、`flashbackCard1/2/3`、`fbDate1/2/3`、`fbTag1/2/3`、`fbText1/2/3`、`fbReflection1`、`tvFlashbackEmpty`、`tvFlashbackLoading`、`flashbackSelectedIds`、`flashbackViewDialog`
- 删除 7 个方法：`loadFlashback`、`showFlashbackLoading`、`renderFlashbackEmpty`、`renderFlashbackCards`、`bindFlashbackCard`、`showFlashbackViewDialog`、`renderFlashbackViewDialog`
- 删除 `onViewCreated` 中 16 个 findViewById + OnClickListener 设置 + `loadFlashback()` 调用
- 删除 `saveDirectly`/`saveWithAiAnalysis`（3 处）末尾的 `loadFlashback()` 调用
- 删除 `onDestroyView` 中 `flashbackViewDialog` dismiss + 16 个字段置 null

#### 6. DeepSeekClient.java
- 删除 `PickThreeCallback` 接口
- 删除 `pickThreeDiaries` 方法（约 95 行，含 AI 挑选 3 篇日记的 prompt 与 HTTP 调用）

#### 7. DiaryDao.java
- 删除 `getRandomEntries(int limit)` 方法（随机采样候选日记）
- 删除 `getById(long id)` 方法（按 id 查询单条日记，仅回顾卡片点击用）

### Bug 检查报告

#### 验证安全（无问题）
- `RecordFragment.java` 无 `flashback`/`Calendar`/`SharedPreferences` 残留引用
- `DeepSeekClient.java` 无 `pickThree`/`PickThree` 残留
- `DiaryDao.java` 无 `getRandomEntries`/`getById` 残留
- `fragment_record.xml` 不再引用任何已删 drawable/string/color
- import 完整：`Dialog` 仍被 `analyzingDialog`/`successDialog`/`errorDialog`/`imagePickerDialog`/`recordingDialog` 使用；`LinearLayout` 仍被 `btnModeImage`/`btnModeAudio` 使用
- `onDestroyView` 清理与字段声明一致（删的字段都删了清理代码）
- `saveDirectly`/`saveWithAiAnalysis` 删 `loadFlashback()` 后逻辑仍完整（保存→Toast→清空→showSuccessDialog）
- `flashback_tag_bg` 颜色仍被 `bg_tips_bar.xml` 复用，保留合理（淡绿半透明底色，名字是历史遗留）

#### 已知无害残留
- 用户手机上可能存在 `lifetrack_flashback` SharedPreferences 文件（之前每日缓存 3 篇日记 id），删除后不再读写，占极少存储，无害。如需清理可在首次启动时 `deleteSharedPreferences("lifetrack_flashback")`，但非必要

### 关键文件路径
- `app/src/main/res/layout/fragment_record.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values/colors.xml`
- `app/src/main/res/values-night/colors.xml`
- `app/src/main/java/com/example/lifetrack/RecordFragment.java`
- `app/src/main/java/com/example/lifetrack/DeepSeekClient.java`
- `app/src/main/java/com/example/lifetrack/DiaryDao.java`
