# LifeTrack 项目 Code Wiki

## 一、项目概述

LifeTrack 是一款 Android 平台的智能生活记录应用，用 Java 11 编写。它把日记记录、周期性回顾、趋势追踪和洞察问答合在一个 App 里，背后接入了两个 AI 服务：DeepSeek 负责日期分析、总结生成、洞察问答和日记打标签，豆包（火山引擎 ARK）负责图片文字提取和语音转写。

应用有五个主功能页：记录（Record）、追踪（Track）、日记（Diary）、洞察（Insight）、个人（Profile）。数据库用 SQLCipher 加密，密钥由 Android KeyStore 管理。网络请求统一走 OkHttp，AI 流式响应通过 SSE 实时展示。

### 核心功能

| 功能 | 说明 | AI 服务 |
|------|------|---------|
| 图片分析 | 识别图片中的文字或描述场景，支持单张和批量（最多 20 张） | 豆包 Vision |
| 语音转文字 | 录音转写，AAC 格式，最长 5 分钟 | 豆包 ASR |
| 日期分析 | 从日记内容识别日期，支持多日拆分 | DeepSeek |
| 维度分析 | 拆分日期时一并提取运动、睡眠、社交三个维度打分 | DeepSeek |
| 周期总结 | 日/周/月/季/年/自定义六种周期，按周期粒度切换 prompt | DeepSeek |
| 洞察问答 | 基于日记内容的多轮对话，含追问建议和上下文压缩 | DeepSeek |
| 标签系统 | AI 自动打标签，20 个预置标签，支持回顾选取 | DeepSeek |
| 数据加密 | SQLCipher 加密数据库，KeyStore 管理密钥 | 无 |
| 日记导出 | JSON 格式导出/导入，走系统 SAF | 无 |

---

## 二、项目架构

### 2.1 整体架构

```
┌──────────────────────────────────────────────────────────────────┐
│                      LifeTrack Android App                       │
├──────────────────────────────────────────────────────────────────┤
│  Activity 层                                                     │
│    SplashActivity → LoginActivity → MainActivity                │
│                                       └ UsageDetailActivity     │
├──────────────────────────────────────────────────────────────────┤
│  Fragment 层（托管于 MainActivity）                              │
│    RecordFragment │ TrackFragment │ DiaryFragment              │
│    InsightFragment │ ProfileFragment                            │
├──────────────────────────────────────────────────────────────────┤
│  业务层                                                          │
│    DeepSeekClient（日期/总结/洞察/问答/标签/压缩）              │
│    DoubaoClient（图片分析/语音识别）                            │
│    AIHttpUtils（SSE 解析/错误处理）                              │
│    OkHttpHelper（三实例客户端工厂）                              │
│    DialogHelper（弹窗工厂）                                      │
├──────────────────────────────────────────────────────────────────┤
│  数据层                                                          │
│    DiaryDbHelper（SQLCipher 加密，v12，11 张表）                │
│    DiaryDao │ SummaryDao │ AnalyticsDao │ InsightDao           │
│    DimensionDao │ TagDao │ ReflectionDao                        │
├──────────────────────────────────────────────────────────────────┤
│  安全层                                                          │
│    CryptoHelper（KeyStore + AES/GCM 管理 DB 密钥）              │
│    EncryptedPrefsHelper（加密 SharedPreferences 工厂）          │
│    KeyManager（AI API Key 提供）                                │
├──────────────────────────────────────────────────────────────────┤
│  工具与 View                                                     │
│    ImageUtils │ DateUtils │ ThemeHelper │ AppExecutors          │
│    DiaryExportHelper │ DimensionChartView │ WaveformView        │
│    CalendarAdapter                                              │
└──────────────────────────────────────────────────────────────────┘
```

### 2.2 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| Android 语言 | Java | 11 |
| 构建工具 | Gradle / AGP | 9.4.1 / 9.2.0 |
| Min SDK | Android 8.0 | 26 |
| Target SDK | Android 36 | 36 |
| 网络库 | OkHttp（含 SSE 模块） | 4.12.0 |
| 数据库 | SQLCipher 加密 SQLite | 4.6.0 |
| androidx.sqlite | sqlite | 2.6.2 |
| 安全存储 | EncryptedSharedPreferences | 1.1.0-alpha06 |
| UI 组件 | Material Design | 1.10.0 |
| 毛玻璃效果 | BlurView | 3.2.0 |
| 下拉刷新 | SwipeRefreshLayout | 1.1.0 |
| AI 服务 | DeepSeek + 豆包（火山引擎 ARK） | - |

仓库内不包含后端代码。context 文档提到过一个 Flask 后端用于用户认证和数据同步，但当前版本 KeyManager 已改为硬编码 API Key，LoginActivity 也简化为本地非空校验。后端代码可能部署在别处或已从仓库移除。

---

## 三、Activity 层

### 3.1 SplashActivity

启动闪屏页，展示开场动画后跳转 MainActivity。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/SplashActivity.java)

关键逻辑：
- `onCreate` 前调用 `ThemeHelper.init` 防主题闪屏，再调 `KeyManager.init` 初始化密钥
- 首次启动记录静态时间戳 `sAppStartMs`，旋转恢复时复用该时间戳避免计时翻倍
- 图标缩放淡入动画 + 应用名称和标语延迟位移淡入
- 最短显示 2 秒（`MIN_DISPLAY_MS`），不足则补齐
- `onPause`/`onResume` 暂停恢复动画，`onDestroy` 移除 Handler 回调并 cancel 所有 Animator 防 View 泄漏

### 3.2 LoginActivity

登录页，当前为简化版本，仅校验输入非空后跳转 MainActivity，不调后端 API。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/LoginActivity.java)

关键逻辑：
- `attemptLogin` 用 `TextUtils.isEmpty` 校验 email 和 password，失败 Toast 提示，成功跳转 MainActivity 并 finish
- 密码显隐切换，状态通过 `onSaveInstanceState` 持久化以应对旋转

### 3.3 MainActivity

主入口 Activity，管理 5 个 Fragment 和底部导航，统计各 Tab 访问次数与前台时长。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/MainActivity.java)

核心方法：

| 方法 | 功能 |
|------|------|
| `switchFragment(Fragment target)` | hide 当前 Fragment + show 目标，根据方向做平移淡入淡出动画，异步记录对应 Tab 的打开次数 |
| `updateModeIndicator(int newIndex)` | 切换顶部 5 个圆点的高亮状态 |
| `saveCurrentForegroundTime(Runnable afterWrite)` | 计算本次前台秒数，异步写入 `AnalyticsDao.addForegroundSeconds`，写完回调 |

Fragment 管理用 add + hide/show 方式，5 个 Fragment 在首次 onCreate 时一次性创建。非首次创建通过 `findFragmentByTag` 恢复。`onResume` 异步记录 `COLUMN_DU_APP_OPENS`，`onPause` 写入前台时长。

### 3.4 UsageDetailActivity

使用统计详情页，展示总时长、使用天数、今日数据、各 Tab 频次、近 7 天记录，并提供日记导出导入功能。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/UsageDetailActivity.java)

核心方法：

| 方法 | 功能 |
|------|------|
| `loadUsageStats()` | 异步汇总 totalSeconds / daysUsed / todaySeconds / avgSeconds / 近 7 天 / 各 Tab 打开次数 |
| `startExportDiary()` | 构建 `lifetrack_diary_yyyyMMdd_HHmmss.json` 文件名，触发 `ACTION_CREATE_DOCUMENT` |
| `performExport(Uri)` | 异步 `diaryDao.getAll()` 转 JSON 写入 Uri |
| `startImportDiary()` | 触发 `ACTION_OPEN_DOCUMENT` 选择文件 |
| `performImport(Uri)` | 读 InputStream 解析 JSON，弹出确认对话框 |
| `executeImport(JSONArray)` | SQLite 事务批量 insert |
| `formatDuration(long seconds)` | 格式化为"X小时Y分"或"Y分" |

导入流程有确认对话框环节，旋转时通过 `onSaveInstanceState` 暂存未确认的 entries 防丢失。

---

## 四、Fragment 层

### 4.1 RecordFragment

日记记录页，支持图片和录音两种输入，保存时可选调 AI 做日期和维度分析。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/RecordFragment.java)

关键常量：
- `MAX_GALLERY_IMAGES = 20`：相册多选上限
- `MAX_RECORDING_SECONDS = 300`：最长录音 5 分钟
- `MAX_AUDIO_FILE_BYTES = 8MB`：音频文件大小上限
- `STREAM_UPDATE_INTERVAL_MS = 100`：流式文本节流间隔

核心方法：

| 方法 | 功能 |
|------|------|
| `handleImageResult(Uri)` | 解码单张图片转 Base64，调豆包流式分析 |
| `handleMultipleImageResults(List<Uri>)` | 解码多张图，批量并发调豆包分析 |
| `analyzeImageWithDoubao(String base64)` | 流式回调按 100ms 节流 setText |
| `startRecording()` | MediaRecorder 配置 MPEG4/AAC/16kHz/128kbps，创建临时 m4a |
| `stopRecording()` | 停止计时器，释放 MediaRecorder，文件有效则调语音识别 |
| `recognizeAudioWithDoubao()` | 读 audioData，大小校验后调 `DoubaoClient.recognizeAudio` |
| `saveRecord()` | 取编辑框内容，isAiConnected 分支到 saveWithAiAnalysis 或 saveDirectly |
| `saveWithAiAnalysis(String)` | 取 7 天维度评分作为 historyContext，调 `DeepSeekClient.analyzeDiaryDatesWithDimensions` |
| `saveDimensionRecord(long, DateEntry)` | 从 DateEntry.dimensions 提取数据写入 DimensionDao |
| `tagNewDiary(long, String)` | 异步调 `DeepSeekClient.tagDiary` 打标签 |
| `loadFlashback()` | 查当日 SharedPreferences 缓存，未命中调 `tagDao.pickTagAndDiaries`，渲染 3 张回顾卡片 |
| `showReflectionDialog(long diaryId)` | 展示日记原文、标签、感想列表，底部输入框可添加感想 |

小回顾（Flashback）是 RecordFragment 的一个特色功能。它从 TagDao 的选取算法获取 3 篇历史日记，点击可查看原文和添加回顾感想。缓存按日存到 SharedPreferences，当天不重复查询。

`onDestroyView` 会主动 dismiss 所有 Dialog、释放 MediaRecorder、清空 Message 队列、置空 View 引用，防止窗口泄漏和 Activity 无法 GC。

### 4.2 DiaryFragment

日记浏览页，按月历视图展示有日记的日期，支持关键词搜索和编辑删除。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/DiaryFragment.java)

核心方法：

| 方法 | 功能 |
|------|------|
| `refreshCalendar()` | 设置月份标题，异步查当月有日记的日期和条数，更新 adapter |
| `loadDiaryForDate(int, int, int)` | 异步查当日日记列表，渲染每项含编辑按钮 |
| `performSearch(String keyword)` | 异步 `diaryDao.searchByKeyword`，渲染搜索结果 |
| `scheduleSearch(String)` | 400ms 节流，去抖后触发 performSearch |
| `showEditDialog(String id, String, String)` | 弹编辑对话框，保存调 `diaryDao.update`，删除调 `diaryDao.delete` |
| `showMonthPickerDialog()` | NumberPicker 选年月 |
| `showDebugDump()` | 长按标题触发，Toast 显示所有日记 ID 和时间戳 |

搜索用 400ms 去抖。GridView 的滚动被禁用，避免和 ScrollView 冲突。`updateGridViewHeight` 缓存了 measure 结果避免重复计算。

### 4.3 TrackFragment

追踪页，提供 6 种周期的总结卡片，含 4 个维度 Tab（总结/运动/睡眠/社交）。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/TrackFragment.java)

这是项目里最复杂的 Fragment。关键常量：
- `TAB_SUMMARY = 0` / `TAB_EXERCISE = 1` / `TAB_SLEEP = 2` / `TAB_SOCIAL = 3`
- 6 种周期：daily / weekly / monthly / quarterly / yearly / custom

核心方法：

| 方法 | 功能 |
|------|------|
| `loadPeriodSummary(int position, boolean animate)` | 查缓存，命中且有效直接显示；未命中调 generateAndCache |
| `generateAndCache(...)` | 调 `DeepSeekClient.generateSummaryStreamWithInsight`，流式更新进度，完成后保存 insight + summary |
| `validateSummary(String, String)` | 校验卡片 JSON：数量、type 合法性、字数范围、内容去重，不合格用 temperature=0.3 重试 |
| `preloadAdjacentPeriods(int)` | 后台预加载前后周期总结，用 `ConcurrentHashMap` 防重 |
| `buildYearlyEntriesText(String[])` | 年度总结复用该年所有周总结，缺失周补原始日记 |
| `buildMonthlyEntriesText(String[])` | 月度总结复用本月各周总结 |
| `buildQuarterlyEntriesText(String[])` | 季度总结复用本季各月总结 |
| `getDateRange(int)` | 计算周期日期范围 [start, end) |
| `getPeriodKey(int)` | 生成周期缓存键 |
| `renderContent(String title, String body)` | 解析 JSON 数组，按 type 分发到不同卡片 View |
| `createLetterCardView(...)` | 年度信卡片：关键词 chips + 人生阶段 + 正文 + 转折点 |
| `selectTab(int)` | 切换 4 个 Tab，切到维度 Tab 时加载对应数据 |
| `loadExerciseData()` / `loadSleepData()` / `loadSocialData()` | 从 DimensionDao 加载维度数据渲染图表 |
| `startLoadHistory(int tab)` | 批量回填历史日记的维度评分，递归每批 3 篇 |
| `loadExerciseProgress()` / `loadSleepFactors()` | 查 SummaryDao 缓存，无缓存调 `DeepSeekClient.generateTextAnalysis` 生成聚合分析 |

缓存失效策略：比较 `entry_count` 和 `prompt_version` 两个字段，任一不匹配则重新生成。`SummaryDao.CURRENT_PROMPT_VERSION = 2`，每次改 prompt 递增。

总结生成时按周期粒度用不同 prompt：weekly 强调连续性变化，monthly 识别反复模式，quarterly 用叙事性主卡片，yearly 保持"年度信"格式。月/季/年总结会复用子周期总结作为输入，提升信息密度。

### 4.4 InsightFragment

洞察对话页，基于日记内容的 AI 多轮对话。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/InsightFragment.java)

关键常量：
- `COMPRESS_THRESHOLD = 50000`：触发压缩的字符阈值
- `TARGET_LENGTH = 40000`：压缩后目标长度
- `MAX_PERSISTED_TURNS = 5`：持久化最近 5 轮对话

对话流程：
1. 用户输入问题，`DeepSeekClient.analyzeTimeRange` 推断时间范围（事实 7 天/模式 30 天/趋势 90 天/情绪 14 天）
2. `diaryDao.queryByDateRange` 获取日记
3. 超过 10 条时调 `filterRelevantDiariesSync` 筛选相关篇目
4. 超 50000 字符时 `compressDiary` 按月分块并发压缩，仍超 40000 则 `sampleDiaryEvenly` 均匀采样
5. `buildInsightContextForRange` 查历史洞察标签
6. `DeepSeekClient.answerWithDiaryContext` 两轮问答（草稿 temperature=0.5 + 完善 temperature=0.4），流式输出
7. 回答完成后 `generateAndShowFollowUps` 生成 3 个追问建议

对话历史存到 SharedPreferences，Fragment 重建时恢复。`MessageAdapter` 用 payloads 做局部刷新（`STREAM_PAYLOAD` 和 `FOLLOWUP_PAYLOAD`），避免流式输出时整列表重绑。打字动画用静态实例缓存复用。

`onDestroyView` 调 `adapter.setFragment(null)` 断开 Fragment 引用，避免异步回调持有 Fragment→View→Activity 引用链导致 Activity 无法 GC。

### 4.5 ProfileFragment

个人中心页，展示统计、API 连通性验证、主题切换、头像设置、日记导出导入、批量补全标签。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/ProfileFragment.java)

核心方法：

| 方法 | 功能 |
|------|------|
| `loadStats()` | 异步查记录数、使用天数、连续天数 |
| `calculateStreak(Set<String>)` | 从今天或昨天开始向前数连续天数 |
| `verifyConnection()` | 并行调 DeepSeek 和豆包的连接验证，AtomicBoolean 跟踪两边完成情况 |
| `startTagFill()` | 查未标记数量，AlertDialog 确认后执行批量补全 |
| `executeTagFill(Context)` | 串行循环未标记日记，`tagDiarySync` 重试 2 次（共 3 次，间隔 300ms），更新进度 |
| `showThemePickerDialog()` | AlertDialog 单选 light/dark/system，调 `ThemeHelper.applyMode` |
| `loadAvatar()` | 从 SharedPreferences 读 avatar_path，BitmapFactory 解码后设置 |

头像设置走 `avatarPickerLauncher`，选图后 `ImageUtils.decodeSampledBitmap` 解码到 256px，`cropCenterSquare` 中心方形裁剪，保存到 filesDir/avatar.jpg。

---

## 五、业务层 - AI 客户端

### 5.1 DeepSeekClient

DeepSeek AI 服务客户端，封装日期分析、维度分析、总结生成、洞察问答、标签生成、日记压缩等能力。纯静态工具类。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/DeepSeekClient.java)

关键常量：
- `BASE_URL = "https://api.deepseek.com"`
- `MODEL = "deepseek-chat"`
- `contentCache = new LruCache<>(50)`：日期分析结果缓存
- `CACHE_VERSION = 1`：缓存版本号，缓存 key 前缀 `v{N}_`

AI 方法清单：

| 方法 | 用途 | temperature | max_tokens | 流式 |
|------|------|-------------|------------|------|
| `verifyApiKey` | 验证 API Key（GET /models） | - | - | 否 |
| `analyzeDiaryDateStream` | 单日日期分析 | 0.1 | 256 | 是 |
| `analyzeDiaryDatesStream` | 多日日期拆分 | 0.1 | 2048 | 是 |
| `analyzeDiaryDatesStreamWithDimensions` | 多日拆分 + 三维度分析 | 0.1 | 4096 | 是 |
| `generateTextAnalysis` | 通用文本分析 | 0.3 | 2048 | 否 |
| `generateSummaryStream` | 总结流式生成 | 0.7 | 512 | 是 |
| `generateSummaryStreamWithInsight` | 总结 + 洞察 + 趋势 | 0.7 | yearly/quarterly=16384, monthly=8192, 默认=4096 | 是 |
| `analyzeTimeRange` | 时间范围推断 | 0.1 | 128 | 否 |
| `answerWithDiaryContext` | 洞察问答（两轮：草稿 0.5 + 完善 0.4） | 0.5 / 0.4 | 8192 | 是 |
| `generateFollowUpSuggestions` | 追问建议生成 | 0.8 | 256 | 否 |
| `filterRelevantDiaries` | 日记相关性筛选 | 0.1 | 512 | 否 |
| `tagDiary` / `tagDiarySync` | AI 打标签 | 0.3 | 128 | 否 |
| `compressDiaryChunk` | 日记压缩到 40000 字以内 | 0.3 | 4096 | 否 |

总结生成按 periodType 切换 prompt：weekly 强调连续性变化和具体事件；monthly 识别反复模式（3 次以上）和与上月对比；quarterly 用叙事性主卡片（200-400 字）加 2-4 张洞察卡片；yearly 保持"年度信"格式。卡片 type 有 event / habit / quote / default / letter 五种。

`generateSummaryStreamWithInsight` 在总结 JSON 之后输出两个标记块：`INSIGHT_JSON`（关键人物/项目/事件、情绪主题、决策）和 `TREND_JSON`（五维度评分）。流式传输时 `stripInsightBlocks` 移除标记块，避免 `parseSummaryResult` 解析冲突。该方法还注入最近 5 条历史洞察和上期趋势作为上下文。

洞察问答 `answerWithDiaryContext` 采用两轮模式：第一轮 temperature=0.5 生成草稿，第二轮 temperature=0.4 静默完善后一次性替换。注入最近 5 轮对话历史（`ConversationTurn`）和洞察上下文。回答纯文本，禁止 Markdown。

内部数据类：
- `DateEntry`：date, content, dimensions（DimensionData）
- `DimensionData`：exerciseScore, exerciseTypes, exerciseExcerpt, sleepScore, sleepExcerpt, socialContacts, socialExcerpt
- `SummaryEntry`：title, content
- `SummaryWithInsightEntry`：title, content, insightJson
- `ConversationTurn`：question, answer

回调接口：`VerifyCallback`、`DateAnalyzeCallback`、`DateAnalyzeStreamCallback`、`MultiDateAnalyzeCallback`、`MultiDateAnalyzeStreamCallback`、`SummaryCallback`、`SummaryStreamCallback`、`SummaryWithInsightStreamCallback`、`TextAnalysisCallback`、`InsightTimeRangeCallback`、`InsightAnswerCallback`、`InsightFollowUpCallback`、`DiaryFilterCallback`、`TagCallback`、`CompressCallback`。

### 5.2 DoubaoClient

豆包（火山引擎 ARK）多模态客户端，封装图片分析和语音识别。纯静态工具类。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/DoubaoClient.java)

关键常量：
- `BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"`
- `VISION_MODEL = "ep-20260521220024-2mnlc"`
- `AUDIO_MODEL = "ep-20260526095629-d6gwd"`

核心方法：

| 方法 | 功能 |
|------|------|
| `verifyConnection(VerifyCallback)` | POST `/chat/completions` 发送 "hi" + max_tokens=1 验证连通性 |
| `analyzeImageStreamBase64(String, VisionStreamCallback)` | 流式分析单张图片，temperature=0.1, max_tokens=1024 |
| `analyzeMultipleImagesConcurrent(List<String>, BatchVisionStreamCallback)` | 3 线程池并发分析，CountDownLatch 等待，超时 180s，失败图占位"【图片N分析失败】" |
| `recognizeAudio(byte[], String format, AsrCallback)` | 语音转写，POST `/responses`（非 chat/completions） |

图片分析流程：Bitmap → Base64 → 构建多模态消息（system + user 含 text 和 image_url part）→ POST `/chat/completions` stream=true → SSE 流式解析。

语音识别用 Responses API 而非 Chat Completions。音频 Base64 编码嵌入 `input_audio` 类型，解析 `output[].content[].text` 提取转写结果。支持格式：mp4 / wav / mp3 / aac / m4a。

回调接口：`VisionCallback`、`VisionStreamCallback`、`BatchVisionCallback`、`BatchVisionStreamCallback`、`VerifyCallback`、`AsrCallback`。

### 5.3 AIHttpUtils

AI 客户端公共工具类，提供 SSE 解析、body 读取、错误处理。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/AIHttpUtils.java)

核心方法：

| 方法 | 功能 |
|------|------|
| `readSSEStream(Response, SSEChunkHandler)` | 读取 `data: ` 前缀行，遇 `[DONE]` 终止，解析 `choices[0].delta.content` 回调 |
| `readBodyString(Response)` | 安全读取 body 字符串，null/异常返回空串 |
| `readErrorMessage(Response)` | HTTP 401 → "API Key 无效或已过期"，429 → "请求过于频繁，请稍后再试" |
| `getNetworkErrorMessage(Exception)` | UnknownHostException → "无法连接到服务器，请检查网络"，SocketTimeoutException → "连接超时" |

`SSEChunkHandler` 接口只有一个 `onChunk(String chunk)` 方法。

### 5.4 OkHttpHelper

OkHttpClient 工厂，提供三种超时配置的客户端实例。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/OkHttpHelper.java)

| 实例 | connect | read | write | 用途 |
|------|---------|------|-------|------|
| `getDefault()` | 10s | 10s | 10s | 认证、同步 |
| `getSse()` | 15s | 60s | 15s | AI 流式请求 |
| `getLongTimeout()` | 15s | 180s | 15s | 批量图片分析 |

三个实例均为 volatile 双重检查锁单例。DEBUG 构建附加日志拦截器，打印请求方法、URL、响应码、耗时，body 截断到 1024 字符。SSE 响应只打印 `[SSE stream]`。

### 5.5 DialogHelper

通用 Dialog 创建工具，纯静态。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/DialogHelper.java)

核心方法：

| 方法 | 功能 |
|------|------|
| `create(Context, int layoutResId)` | 创建可取消 Dialog，dim=0.5 |
| `create(Context, int, boolean cancelable)` | 自定义 cancelable |
| `create(Context, int, boolean, float dimAmount)` | 全参数，强制 Context 为 Activity |
| `setConfirmButton(Dialog, int buttonId, Runnable)` | 绑定确认按钮，先执行回调再 dismiss |
| `setCloseButton(Dialog)` | 绑定关闭按钮 |

---

## 六、数据层

### 6.1 DiaryDbHelper

SQLCipher 加密数据库管理器，继承 `net.zetetic.database.sqlcipher.SQLiteOpenHelper`。单例模式。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/DiaryDbHelper.java)

- 数据库名：`lifetrack.db`
- 当前版本：`12`
- 加密密钥由 `CryptoHelper.getDatabaseKeyAsString` 提供

#### 表结构（11 张表）

**diary 表（日记）**

| 字段 | 类型 | 说明 |
|------|------|------|
| `_id` | INTEGER PK | 自增 |
| `timestamp` | TEXT NOT NULL | yyyy-MM-dd HH:mm:ss |
| `content` | TEXT NOT NULL | 日记内容 |
| `status` | INTEGER NOT NULL DEFAULT 0 | 0=未打标签，1=已打标签 |

**summaries 表（总结缓存）**

| 字段 | 类型 | 说明 |
|------|------|------|
| `_id` | INTEGER PK | 自增 |
| `period_type` | TEXT NOT NULL | daily/weekly/monthly/quarterly/yearly/custom |
| `period_key` | TEXT NOT NULL | 周期标识 |
| `title` | TEXT NOT NULL | 总结标题 |
| `content` | TEXT NOT NULL | JSON 卡片数组 |
| `entry_count` | INTEGER NOT NULL | 关联日记条目数（缓存失效判断） |
| `prompt_version` | INTEGER NOT NULL DEFAULT 0 | prompt 版本号 |
| `created_at` | TEXT NOT NULL | 创建时间 |

UNIQUE 约束：`(period_type, period_key)`

**daily_usage 表（每日使用统计）**

| 字段 | 类型 | 说明 |
|------|------|------|
| `date` | TEXT PK | yyyy-MM-dd |
| `foreground_seconds` | INTEGER NOT NULL DEFAULT 0 | 前台秒数 |
| `app_opens` | INTEGER NOT NULL DEFAULT 0 | App 打开次数 |
| `record_opens` | INTEGER NOT NULL DEFAULT 0 | 记录页打开次数 |
| `track_opens` | INTEGER NOT NULL DEFAULT 0 | 追踪页打开次数 |
| `diary_opens` | INTEGER NOT NULL DEFAULT 0 | 日记页打开次数 |
| `profile_opens` | INTEGER NOT NULL DEFAULT 0 | 个人页打开次数 |

**insights 表（结构化洞察）**

| 字段 | 类型 | 说明 |
|------|------|------|
| `_id` | INTEGER PK | 自增 |
| `period_type` | TEXT NOT NULL | 周期类型 |
| `period_key` | TEXT NOT NULL | 周期标识 |
| `key_persons` | TEXT NOT NULL DEFAULT '' | 关键人物（逗号分隔） |
| `key_projects` | TEXT NOT NULL DEFAULT '' | 关键项目 |
| `key_events` | TEXT NOT NULL DEFAULT '' | 关键事件 |
| `emotion_themes` | TEXT NOT NULL DEFAULT '' | 情绪主题 |
| `decisions` | TEXT NOT NULL DEFAULT '' | 决策节点 |
| `created_at` | TEXT NOT NULL | 创建时间 |

UNIQUE 约束：`(period_type, period_key)`

**tag 表（标签）**：`_id` PK, `name` TEXT UNIQUE NOT NULL

**diary_tag 表（日记-标签关联）**：`diary_id` + `tag_id` 联合主键，外键 `diary(_id)` ON DELETE CASCADE

**tag_last_shown 表（标签最近展示）**：`tag_id` PK, `last_shown_date` TEXT，外键 `tag(_id)`

**reflection 表（回顾感想）**：`_id` PK, `diary_id` INTEGER NOT NULL, `content` TEXT NOT NULL, `created_at` TEXT NOT NULL，外键 `diary(_id)` ON DELETE CASCADE

**dimension_records 表（维度记录）**：`_id` PK, `date` TEXT NOT NULL, `diary_id` INTEGER（可空）, `exercise_score` INTEGER, `exercise_types` TEXT, `sleep_score` INTEGER, `social_contacts` TEXT, `raw_excerpt` TEXT, `created_at` TEXT NOT NULL

**sessions 表（会话追踪）**：`_id` PK, `start_time` TEXT NOT NULL, `end_time` TEXT, `duration` INTEGER

**feature_usage 表（功能使用追踪）**：`_id` PK, `feature_name` TEXT NOT NULL, `start_time` TEXT NOT NULL, `end_time` TEXT, `duration` INTEGER

索引：`idx_diary_timestamp`、`idx_insights_created`、`idx_diary_tag_tag`、`idx_dimension_records_date`

#### 版本升级历史

| 版本 | 升级内容 |
|------|----------|
| v2 | 创建 summaries 表 |
| v3 | 创建 sessions 表 |
| v4 | 创建 feature_usage 表 |
| v5 | 创建 daily_usage 表 |
| v6 | daily_usage 加 5 个 opens 列 |
| v7 | 创建 insights 表 |
| v8 | summaries 加 prompt_version 列 |
| v9 | 创建 idx_diary_timestamp、idx_insights_created |
| v10 | DROP INDEX idx_trend_created, DROP TABLE trend_metrics（移除五维趋势） |
| v11 | 创建 tag/diary_tag/tag_last_shown/reflection 表 + diary 加 status 列 + initDefaultTags |
| v12 | 创建 dimension_records 表 + idx_dimension_records_date |

`onDowngrade` 不删数据，仅打日志。

#### 加密迁移

`migrateIfNeeded` 在首次检测到未加密 DB 时执行：用 `android.database.sqlite.SQLiteDatabase` 只读打开旧明文 DB → `filterExistingColumns` 探测列存在性兼容低版本 → 读取 6 张表数据到内存 → rename 旧 DB 到 `.bak` → 创建新加密 DB 写入数据 → 成功删 `.bak` 并标记 `db_encrypted=true`，失败无条件恢复 `.bak`。

#### 默认标签库

20 个预置标签：工作、学习、情感、健康、家庭、朋友、旅行、阅读、运动、美食、思考、创作、休息、财务、其他、梦境、欲望、灵感、焦虑、目标。通过 `INSERT OR IGNORE` 初始化。

### 6.2 DiaryDao

日记表数据访问对象。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/DiaryDao.java)

核心方法：

| 方法 | 返回值 | 功能 |
|------|--------|------|
| `insert(String timestamp, String content)` | `long` | 插入日记，返回新 id，失败 -1 |
| `queryByDate(String dateStr)` | `List<String[]>` | 按日期查询 `[dateStr, nextDay)`，返回 `[{id, timestamp, content}]`，timestamp 升序 |
| `queryByDateRange(String start, String end)` | `List<String[]>` | 按日期范围查询 |
| `getAll()` | `List<String[]>` | 全部日记，timestamp 降序，返回 `[{timestamp, content}]` |
| `searchByKeyword(String keyword)` | `List<String[]>` | 关键词搜索，LIKE 转义 `% _ \`，timestamp 降序 |
| `getDiaryDaysForMonth(int year, int month)` | `Set<Integer>` | 月内日记日期集合（day 数字） |
| `getDiaryDaysWithCounts(int, int)` | `Map<Integer, Integer>` | 月内每日日记条数 |
| `getCount()` | `int` | 日记总数 |
| `getUniqueDayCount()` | `int` | 不同日期数 |
| `getUniqueDays()` | `Set<String>` | 所有不同日期字符串 |
| `update(long id, String content)` | `int` | 更新日记内容 |
| `delete(long id)` | `int` | 删除日记 |
| `getUnlabeledDiaries()` | `List<String[]>` | 查 status=0 的日记 `[{id, content}]` |
| `getUnlabeledCount()` | `int` | 未标记日记数 |

按日期查询用半开区间 `[dateStr, nextDay)` 避免边界问题。关键词搜索用 `LIKE ? ESCAPE '\\'` 防通配符注入。

### 6.3 SummaryDao

总结表数据访问对象。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/SummaryDao.java)

- `CURRENT_PROMPT_VERSION = 2`：每次改 DeepSeekClient 总结 prompt 时递增

核心方法：

| 方法 | 返回值 | 功能 |
|------|--------|------|
| `getSummary(String periodType, String periodKey)` | `String[]` | 查总结 `[title, content, entryCount, promptVersion]`，无则 null |
| `saveSummary(...)` | `long` | 保存总结，CONFLICT_REPLACE 策略 |
| `getEntryCountForPeriod(String start, String end)` | `int` | 周期内日记条目数（半开区间） |
| `getEntriesTextForPeriod(String start, String end)` | `String` | 周期内日记文本，格式 `[timestamp] content\n` |
| `getSummariesTextForKeys(String childType, List<String> keys)` | `String` | 批量获取子周期总结文本，格式 `【key title】\ncontent\n\n` |
| `getExistingSummaryKeys(String type, List<String> keys)` | `Set<String>` | 批量查询已存在的 period_key |
| `deleteSummary(String type, String key)` | `int` | 删除总结（用于"重新总结"） |

### 6.4 AnalyticsDao

使用统计 DAO，操作 daily_usage 表。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/AnalyticsDao.java)

核心方法：

| 方法 | 功能 |
|------|------|
| `addForegroundSeconds(String date, long seconds)` | 累加前台秒数，UPSERT 模式，事务包裹 |
| `incrementOpenCount(String date, String column)` | 递增某列打开次数，column 须在白名单内 |
| `getTodaySeconds()` | 今日前台秒数 |
| `getTotalSeconds()` | 总前台秒数 |
| `getDaysUsed()` | 使用天数（foreground_seconds > 0） |
| `getRecentDailyUsage(int limit)` | 最近 N 条（date DESC） |
| `getAllDailyUsage()` | 全部记录（date ASC） |

`ALLOWED_OPEN_COLUMNS` 白名单含 5 个列名，防止 SQL 注入。内部数据类 `DailyUsage` 含 7 个 final 字段。

### 6.5 InsightDao

洞察数据 DAO，操作 insights 表。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/InsightDao.java)

核心方法：

| 方法 | 功能 |
|------|------|
| `saveInsight(...)` | 保存洞察，null 转 ""，CONFLICT_REPLACE |
| `getInsight(String type, String key)` | 查单条洞察 |
| `getRecentInsights(String type, int limit)` | 按类型查最近 N 条 |
| `getInsightsByDateRange(String type, String start, String end, int limit)` | 按时间范围查询，daily 用 period_key 精确过滤，monthly 用前 7 位，其他用 created_at |
| `buildInsightContext(String type, int limit)` | 构建历史洞察上下文文本供 AI prompt 注入 |

内部数据类 `InsightData`：keyPersons, keyProjects, keyEvents, emotionThemes, decisions（全 String）。

### 6.6 DimensionDao

维度记录 DAO，操作 dimension_records 表。数据来源是 DeepSeek 在日记日期拆分时一并分析的运动/睡眠/社交三维度。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/DimensionDao.java)

核心方法：

| 方法 | 功能 |
|------|------|
| `insert(String date, Long diaryId, Integer exerciseScore, List<String> exerciseTypes, Integer sleepScore, List<String> socialContacts, String excerpt)` | 插入维度记录，score 为 null 不写入，List 转 JSON 数组存储 |
| `getByDateRange(String start, String end)` | 按日期范围查询（闭区间，date ASC） |
| `getRecent(int days)` | 最近 N 天（date DESC），用 `date('now', '-N days')` |
| `getBySocialContact(String name, String start)` | 按社交对象查询，SQL 过滤后 Java 层再做 contains 匹配 |
| `getSocialRecords(String start)` | 所有社交维度非空记录 |
| `getRecentScoresText(int days)` | 拼接近期打分摘要供 AI prompt 参考 |
| `hasRecordForDate(String date)` | 该日期是否已有维度记录（回填时跳过） |

内部数据类 `DimensionRecord`：id, date, diaryId(Long), exerciseScore(Integer), exerciseTypes(List<String>), sleepScore(Integer), socialContacts(List<String>), rawExcerpt, createdAt。

`exercise_types` 和 `social_contacts` 以 JSON 数组字符串存储。

### 6.7 TagDao

标签系统 DAO，操作 tag/diary_tag/tag_last_shown 三张表。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/TagDao.java)

核心方法：

| 方法 | 功能 |
|------|------|
| `getAllTagNames()` | 所有标签名（id ASC），供 AI prompt 动态拼接 |
| `getTagId(String name)` | 根据名查 id，不存在返回 -1 |
| `setTagsForDiary(long diaryId, List<String> tags)` | 批量设置标签（事务），先清除旧关联再插入新关联，更新 diary.status=1 |
| `getTagsForDiary(long diaryId)` | 查日记的所有标签名（INNER JOIN） |
| `pickTagAndDiaries(String today)` | 回顾选取算法 |

回顾选取算法 `pickTagAndDiaries` 的逻辑：
1. 排除 7 天内已展示过的标签（`last_shown_date > sevenDaysAgo`）
2. 全部标签 7 天内展示过则放宽为全部标签
3. 随机打乱后选第一个有关联日记的标签
4. 从该标签关联日记中随机选最多 3 篇（排除今日，`ORDER BY RANDOM() LIMIT 3`）
5. 更新该标签的 `last_shown_date`
6. 返回 `ReviewPick`，无可用时返回 null

内部数据类 `ReviewPick`：tagId, tagName, diaries(List<String[]>)，每篇 diary = {id, timestamp, content}。

### 6.8 ReflectionDao

回顾感想 DAO，操作 reflection 表。一篇日记可有多条感想。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/ReflectionDao.java)

核心方法：

| 方法 | 功能 |
|------|------|
| `addReflection(long diaryId, String content, String createdAt)` | 添加感想，返回新 id |
| `getReflectionsForDiary(long diaryId)` | 查某日记的所有感想，created_at DESC |
| `countReflections(long diaryId)` | 统计感想数量 |
| `deleteReflection(long reflectionId)` | 删除单条感想 |

外键 `diary_id` ON DELETE CASCADE，日记删除时感想同步删除。内部数据类 `Reflection`：id, diaryId, content, createdAt。

---

## 七、安全层

### 7.1 CryptoHelper

数据库主密钥的生成、加密存储与读取。基于 Android KeyStore + AES/GCM。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/CryptoHelper.java)

关键常量：
- `ANDROID_KEYSTORE = "AndroidKeyStore"`
- `KEY_ALIAS = "lifetrack_master_key"`
- `PREFS_NAME = "lifetrack_crypto"`

核心方法：

| 方法 | 功能 |
|------|------|
| `getDatabaseKey(Context)` | 获取 32 字节 DB 密钥：先查 volatile 缓存，再从 SP 读取加密密钥解密，失败则重新生成 |
| `getDatabaseKeyAsString(Context)` | 转 64 字符 hex 串 |
| `isDatabaseEncrypted(Context)` | 读取 `db_encrypted` 标记 |
| `setDatabaseEncrypted(Context, boolean)` | 写入标记 |
| `consumeKeyRegeneratedFlag()` | 一次性消费"密钥已重生成"标志，调用方据此清理旧数据 |
| `invalidateCache()` | 清零并置空 cachedDbKey |

密钥管理流程：
1. 取 `cachedDbKey` volatile 缓存，命中直接返回 clone
2. 从 SP 读取 Base64 编码的 `encrypted_db_key` 和 `iv`
3. 用 Keystore 主密钥 + `GCMParameterSpec(128, iv)` 解密
4. 解密失败（含 `KeyPermanentlyInvalidatedException`）则清旧记录、`invalidateCache`、置 `keyRegenerated = true`
5. `SecureRandom` 生成 32 字节新密钥
6. Keystore 主密钥以 `AES/GCM/NoPadding` 加密，`commit()` 同步落盘
7. `invalidateCache` 显式清零每个字节再置 null

Keystore 主密钥用 `KeyGenParameterSpec` 设置 `PURPOSE_ENCRYPT | PURPOSE_DECRYPT`、`BLOCK_MODE_GCM`、`ENCRYPTION_PADDING_NONE`、`KeySize=256`。

### 7.2 EncryptedPrefsHelper

加密 SharedPreferences 工厂，基于 `EncryptedSharedPreferences`。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/EncryptedPrefsHelper.java)

核心方法：

| 方法 | 功能 |
|------|------|
| `create(Context, String fileName)` | 创建/获取加密 SP，含迁移与降级逻辑 |
| `isEncrypted(SharedPreferences)` | 判断是否为 EncryptedSharedPreferences 实例 |

创建流程：
1. 从 `ConcurrentHashMap` 缓存取，命中直接返回
2. 检查 `lifetrack_crypto` 中 `prefs_migrated_<fileName>` 标记
3. 未迁移则将旧 SP 全部键值按类型写入新加密 SP，`commit()` 同步落盘，成功后清空旧 SP
4. `MasterKey.Builder` 用 `AES256_GCM`，SP scheme 为 `AES256_SIV`（key）+ `AES256_GCM`（value）
5. 实际文件名 = `fileName + "_enc"`
6. 创建失败 → 删除损坏文件 → 再试 → 仍失败则返回 `fileName + "_fallback"` 的明文 SP，日志警告 "DEGRADING TO PLAINTEXT STORAGE"

### 7.3 KeyManager

AI API Key 提供方。当前为硬编码版本。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/KeyManager.java)

核心方法：

| 方法 | 功能 |
|------|------|
| `init(Context)` | 兼容旧调用方的空操作，仅打日志 |
| `getDeepSeekKey()` | 返回硬编码 DeepSeek key |
| `getDoubaoKey()` | 返回硬编码豆包 key |
| `isReady()` | 永远返回 true |

注释说明已弃用从服务端 `/api/keys` 获取的方案。安全提醒：APK 可被反编译提取 key，建议混淆 + 运行时拼接 + 服务端代理。

---

## 八、工具类

### 8.1 ImageUtils

图片处理工具，静态方法。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/ImageUtils.java)

- `MAX_DIMENSION = 768`：最长边上限
- `COMPRESS_QUALITY = 60`：JPEG 压缩质量

| 方法 | 功能 |
|------|------|
| `compressBitmap(Bitmap)` | 等比缩放到 768px 内 |
| `bitmapToBytes(Bitmap)` | 压缩转 JPEG 字节数组 |
| `bitmapToBase64(Bitmap)` | 转 Base64 字符串（NO_WRAP） |
| `decodeSampledBitmap(Context, Uri, int, int)` | 两阶段解码：先 `inJustDecodeBounds` 探测尺寸，再按 `inSampleSize` 解码 |
| `calculateInSampleSize(Options, int, int)` | 计算 2 的幂次采样率 |

### 8.2 DateUtils

线程安全的日期格式化工具。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/DateUtils.java)

格式常量：

| 常量 | 格式 | 用途 |
|------|------|------|
| `PATTERN_DATE` | `yyyy-MM-dd` | 日期 |
| `PATTERN_TIME` | `HH:mm:ss` | 时间 |
| `PATTERN_DATETIME` | `yyyy-MM-dd HH:mm:ss` | 日期时间 |
| `PATTERN_FILE_DATE` | `yyyyMMdd_HHmmss` | 文件名时间戳 |

用 `ThreadLocal<SimpleDateFormat>` 解决非线程安全问题，Locale 固定 `Locale.US`。

### 8.3 ThemeHelper

深色/浅色主题管理。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/ThemeHelper.java)

| 方法 | 功能 |
|------|------|
| `getMode(Context)` | 读取已保存模式，默认 MODE_SYSTEM |
| `applyMode(Context, int)` | 持久化并立即调 `setDefaultNightMode` |
| `init(Context)` | 启动时恢复模式 |
| `isDarkMode(Context)` | 当前是否深色，跟随系统则读 UI_MODE_NIGHT_MASK |

模式值与 `AppCompatDelegate.MODE_NIGHT_*` 一致。

### 8.4 AppExecutors

统一线程池管理。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/AppExecutors.java)

| 方法 | 线程池 | 核心/最大 | 队列 | 用途 |
|------|--------|-----------|------|------|
| `io()` | ThreadPoolExecutor | CORE_POOL_SIZE / CORE_POOL_SIZE | 128 | IO 密集型 |
| `network()` | ThreadPoolExecutor | 3 / 3 | 64 | 网络请求 |
| `single()` | ThreadPoolExecutor | 1 / 1 | 64 | 串行 |

`CORE_POOL_SIZE = Math.max(2, Math.min(CPU_COUNT - 1, 4))`。三池均用 `ArrayBlockingQueue` + `CallerRunsPolicy`（背压，避免任务被静默丢弃），线程均为 daemon。

### 8.5 DiaryExportHelper

日记导出导入工具，基于 JSON + SAF。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/DiaryExportHelper.java)

| 方法 | 功能 |
|------|------|
| `exportToUri(Context, Uri)` | `DiaryDao.getAll()` 转 JSON 写入 Uri，返回条数 |
| `importFromUri(Context, Uri)` | 从 Uri 读 JSON 逐条 insert，返回条数 |
| `generateExportFileName()` | 生成 `lifetrack_diary_yyyyMMdd_HHmmss.json` |

导出跳过 null 或长度 < 2 的条目，返回实际写入条数。JSON 格式 version=1，缩进 2 空格，UTF-8 编码。

---

## 九、自定义 View 与适配器

### 9.1 DimensionChartView

维度折线图自定义 View，用于运动/睡眠 Tab 的月度趋势图。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/DimensionChartView.java)

继承 `View`。纵轴范围固定 0-100。

| 方法 | 功能 |
|------|------|
| `setScores(List<Float>, List<String>)` | 设置数据并 invalidate，null 元素表示断点 |
| `clearData()` | 清空并刷新 |
| `onDraw(Canvas)` | 绘制网格、折线、填充、点、标签 |

绘制顺序：空状态文字 → 25/50/75 三条网格线 → 折线（连续非 null 才连线，null 形成断点）→ 折线下方渐变填充（alpha 0.5→0.05）→ 数据点（4dp 圆）→ x 轴标签（最多 6 个）。

颜色：线/点/数值文字用 `R.color.brown_period_text`，填充渐变用 `R.color.brown_dropdown`，x 轴标签用 `R.color.text_subtitle`，网格线用 `R.color.text_hint`（alpha=40）。

### 9.2 WaveformView

录音波形可视化控件，5 根竖条上下波动模拟声波。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/WaveformView.java)

继承 `View`。

- `BAR_COUNT = 5`：竖条数量
- `BAR_WIDTH_RATIO = 0.4f`：竖条宽度占槽宽比例
- `MIN_BAR_HEIGHT_RATIO = 0.15f`：最小高度比例
- 动画时长 120ms，`LinearInterpolator`，无限重复

每帧 `currentHeights[i] += (targetHeights[i] - currentHeights[i]) * 0.4f`，向目标靠近 40% 产生平滑过渡。每 120ms 通过 `generateNewTargets` 重新生成 5 个随机目标。颜色用 `R.color.green_primary`，主题切换时 `onConfigurationChanged` 重读。

`onAttachedToWindow` 启动动画，`onDetachedFromWindow` 停止动画避免泄漏。

### 9.3 CalendarAdapter

日历 GridView 适配器，继承 `BaseAdapter`。

[文件位置](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/CalendarAdapter.java)

内部类：
- `interface OnDayClickListener { void onDayClick(int year, int month, int day); }`
- `static class DayItem { int day, year, month; boolean isCurrentMonth; }`
- `private static class ViewHolder { TextView tvDay; View bgSelected, bgToday, dotDiary; int position, lastTface; }`

| 方法 | 功能 |
|------|------|
| `setMonth(int year, int month)` | 生成当月日历数据，重置 selectedPosition/todayPosition |
| `setDiaryDays(Set<Integer>)` | 设置有日记的日期 |
| `setDiaryDayCounts(Map<Integer, Integer>)` | 设置每日日记条数 |
| `setSelectedDay(int day)` | 按日设置选中位置 |
| `saveState()` / `restoreState(int)` | 保存/恢复选中位置 |

日历格子计算：周一起始，取当月 1 号的 `DAY_OF_WEEK` 换算前置补足天数，从上月末尾倒推填入。当月 1 至 `getActualMaximum(DAY_OF_MONTH)`。后置补足到总格子数为 7 的倍数。今天高亮，选中日期高亮。

日记点逻辑：`count > 0` 显示圆点，`scale = min(count/5f, 1f)`，缩放 `0.5 + 0.5 * scale`。`scale >= 0.6f` 用 `dot_event` Drawable，否则用 `dot_diary`。Drawable 调 `.mutate()` 避免状态串扰。

---

## 十、依赖关系

### 10.1 模块依赖

```
SplashActivity → ThemeHelper, KeyManager
LoginActivity → （无外部依赖）
MainActivity → AnalyticsDao, AppExecutors, 5 个 Fragment
UsageDetailActivity → DiaryDao, AnalyticsDao, DiaryDbHelper, DialogHelper, AppExecutors

RecordFragment → DiaryDao, TagDao, ReflectionDao, DimensionDao
                 → DeepSeekClient, DoubaoClient, ImageUtils, DialogHelper, AppExecutors

DiaryFragment → DiaryDao, CalendarAdapter, DialogHelper, AppExecutors

TrackFragment → SummaryDao, InsightDao, DimensionDao, DiaryDao
                → DeepSeekClient, DimensionChartView, DialogHelper, AppExecutors

InsightFragment → DeepSeekClient, DiaryDao, InsightDao, AppExecutors

ProfileFragment → DiaryDao, TagDao, DeepSeekClient, DoubaoClient
                  → DiaryExportHelper, ImageUtils, DialogHelper, ThemeHelper, AppExecutors
```

### 10.2 业务层依赖

```
DeepSeekClient → AppExecutors, KeyManager, OkHttpHelper, AIHttpUtils
DoubaoClient   → AppExecutors, KeyManager, OkHttpHelper, AIHttpUtils, ImageUtils
AIHttpUtils    → okhttp3（独立工具）
OkHttpHelper   → okhttp3, BuildConfig
```

### 10.3 数据层依赖

```
DiaryDbHelper → net.zetetic.database.sqlcipher, CryptoHelper, BuildConfig
DiaryDao      → DiaryDbHelper
SummaryDao    → DiaryDbHelper, DateUtils
AnalyticsDao  → DiaryDbHelper
InsightDao    → DiaryDbHelper, DateUtils
DimensionDao  → DiaryDbHelper, DateUtils, org.json
TagDao        → DiaryDbHelper, DateUtils
ReflectionDao → DiaryDbHelper
```

### 10.4 数据库表关系

```
diary (1) ──< diary_tag (N) >── (1) tag
diary (1) ──< reflection (N)
diary (1) ──< dimension_records (N)   [diary_id 可空]
tag (1) ──< tag_last_shown (1)
summaries       (独立，UNIQUE(period_type, period_key))
insights        (独立，UNIQUE(period_type, period_key))
daily_usage     (独立，date 为 PK)
sessions        (独立)
feature_usage   (独立)
```

### 10.5 外部依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| `androidx.activity:activity-ktx` | 1.8.0 | ActivityResult API |
| `androidx.appcompat:appcompat` | 1.6.1 | 兼容性支持 |
| `androidx.constraintlayout:constraintlayout` | 2.1.4 | 约束布局 |
| `com.google.android.material:material` | 1.10.0 | Material 组件 |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | HTTP 客户端 |
| `com.squareup.okhttp3:okhttp-sse` | 4.12.0 | SSE 支持 |
| `net.zetetic:sqlcipher-android` | 4.6.0 | 加密 SQLite |
| `androidx.security:security-crypto` | 1.1.0-alpha06 | 加密 SharedPreferences |
| `androidx.sqlite:sqlite` | 2.6.2 | sqlite 支持 |
| `androidx.swiperefreshlayout` | 1.1.0 | 下拉刷新 |
| `com.github.Dimezis:BlurView` | 3.2.0 | 毛玻璃效果 |
| `junit:junit` | 4.13.2 | 单元测试 |
| `androidx.test.espresso:espresso-core` | 3.5.1 | UI 测试 |

---

## 十一、数据流转

### 11.1 记录流程

```
用户输入（图片/语音/文字）
    │
    ├─ 图片 → ImageUtils 解码 → DoubaoClient.analyzeImageStreamBase64
    │        → SSE 流式回调（100ms 节流 setText）→ 文字填入编辑框
    │
    ├─ 语音 → MediaRecorder 录音 → DoubaoClient.recognizeAudio
    │        → 文字填入编辑框
    │
    └─ 保存 → isAiConnected?
              ├─ 是 → DeepSeekClient.analyzeDiaryDatesWithDimensions
              │      → 多日拆分 + 三维度分析
              │      → 逐条 diaryDao.insert + tagNewDiary + saveDimensionRecord
              └─ 否 → diaryDao.insert（单条当日）
              → showSuccessDialog + loadFlashback 刷新小回顾
```

### 11.2 总结生成流程

```
用户选择周期
    │
    ▼
loadPeriodSummary
    │
    ├─ showSkeleton（占位卡片 + 脉冲动画）
    │
    ├─ 查 summaryDao.getSummary 缓存
    │   ├─ 命中且有效（entry_count + prompt_version 匹配）
    │   │   → renderContent + preloadAdjacentPeriods
    │   │
    │   └─ 未命中
    │       → 月/季/年用 buildXxxEntriesText 复用子周期总结
    │       → 否则 getEntriesTextForPeriod
    │       → generateAndCache
    │
    └─ generateAndCache
        → insightDao.buildInsightContext（最近 5 条历史洞察）
        → DeepSeekClient.generateSummaryStreamWithInsight（流式）
        → onPartialResult: stripInsightBlocks → parseSummaryResult → 更新进度
        → onComplete:
            → 保存 insight + summary 到 DB
            → validateSummary 自检（不合格用 temperature=0.3 重试）
            → renderContent + preloadAdjacentPeriods
```

### 11.3 洞察问答流程

```
用户输入问题
    │
    ▼
DeepSeekClient.analyzeTimeRange（推断时间范围）
    │
    ▼
diaryDao.queryByDateRange 获取日记
    │
    ├─ 0 条 → 显示"无日记"
    │
    └─ >10 条 → filterRelevantDiariesSync（同步等待 30s 筛选）
              │
              ▼
        超 50000 字?
        ├─ 是 → compressDiary（按月分块并发压缩，120s 超时）
        │       → 仍超 40000 字则 sampleDiaryEvenly 均匀采样
        └─ 否 → 直接使用
              │
              ▼
        buildInsightContextForRange（查历史洞察标签）
              │
              ▼
        DeepSeekClient.answerWithDiaryContext（两轮：草稿 0.5 + 完善 0.4）
        → 流式输出到 UI
        → 完成后 conversationHistory.add + persistConversationHistory
        → generateFollowUpSuggestions 生成 3 个追问建议
```

### 11.4 维度回填流程

```
用户点击"读取过往日记"
    │
    ▼
confirmLoadHistory → AlertDialog 确认
    │
    ▼
startLoadHistory → diaryDao.getAll → 过滤未打分日记
    │
    ▼
processHistoryBatch（递归，每批 3 篇）
    → 拼接 batchContent
    → DeepSeekClient.analyzeDiaryDatesWithDimensions
    → onResults: saveDimensionRecordFromEntry 逐条保存
    → 递归下一批
    → 全部完成: Toast + 刷新当前 Tab
```

---

## 十二、项目运行

### 12.1 环境要求

| 项目 | 版本 |
|------|------|
| compileSdk | 36（minorApiLevel = 1） |
| minSdk | 26（Android 8.0） |
| targetSdk | 36 |
| Java | 11 |
| Gradle | 9.4.1 |
| AGP | 9.2.0 |

### 12.2 构建命令

```bash
# Debug 版本
./gradlew assembleDebug

# Release 版本（需配置签名）
./gradlew assembleRelease

# 单元测试
./gradlew test
```

### 12.3 配置说明

**签名配置**：在 `local.properties` 中配置 `KEYSTORE_PASSWORD` 和 `KEY_PASSWORD`，缺失时回退到 debug 签名。签名文件 `lifetrack-release.jks`，keyAlias 为 `Wizard091`。

**API Key**：当前硬编码在 `KeyManager.java` 中，不在 BuildConfig 或 local.properties。

**16 KB 页面对齐**：`alignElf16kb` 任务在 `merge*NativeLibs` 后自动执行，用 `align_elf_16kb.py` 脚本修复第三方 .so 的 ELF LOAD 段对齐，兼容 Android 15+ 的 16 KB 页面设备。

### 12.4 AndroidManifest 权限

| 权限 | 用途 |
|------|------|
| `INTERNET` | 网络请求（AI API） |
| `CAMERA` | 拍照 |
| `RECORD_AUDIO` | 录音 |
| `READ_MEDIA_IMAGES` | 读取相册（Android 13+） |
| `READ_EXTERNAL_STORAGE` | 读取存储（Android 12 及以下，maxSdkVersion=32） |

### 12.5 Activity 注册

| Activity | exported | 说明 |
|----------|----------|------|
| `SplashActivity` | true | LAUNCHER 入口 |
| `MainActivity` | false | 主页 |
| `UsageDetailActivity` | false | 使用统计详情 |
| `LoginActivity` | false | 登录页 |

`FileProvider` 用于相机拍照临时文件共享，authorities 为 `${applicationId}.fileprovider`。

---

## 十三、关键技术特性

### 13.1 数据库加密

SQLCipher 4.6.0 加密 SQLite，密钥由 CryptoHelper 管理。密钥生成流程：32 字节 SecureRandom 随机密钥 → Android KeyStore 的 AES-256/GCM 主密钥加密 → 加密密钥 + IV 存到 SharedPreferences → volatile 缓存避免重复解密。明文→加密迁移由 `DiaryDbHelper.migrateIfNeeded` 处理，支持低版本旧库列探测。

### 13.2 SSE 流式响应

所有 AI 请求支持流式响应。统一解析模式：`Accept: text/event-stream` 请求头 → `BufferedReader` 逐行读取 → 匹配 `data: ` 前缀 → 解析 `choices[0].delta.content` → `data: [DONE]` 完成。异常 chunk 静默跳过（Log.w）。RecordFragment 对流式文本做 100ms 节流，避免频繁 setText 卡 UI。

### 13.3 缓存策略

| 缓存层 | 实现 | 失效策略 |
|--------|------|----------|
| DeepSeekClient 内存缓存 | `LruCache(50)` | 缓存 key 含 `v{N}_` 版本前缀，改 prompt 递增 CACHE_VERSION |
| TrackFragment 数据库缓存 | summaries 表 | 比较 entry_count 和 prompt_version，任一不匹配重新生成 |
| TrackFragment 预加载 | 后台单线程 + ConcurrentHashMap | 防重复加载 |
| RecordFragment 小回顾 | SharedPreferences | 按日缓存，当天不重复查询 |

### 13.4 线程管理

`AppExecutors` 提供三个线程池：io（CORE_POOL_SIZE）、network（3 线程）、single（串行）。均用 `CallerRunsPolicy` 背压，避免任务被静默丢弃。所有 AI 请求通过 `AppExecutors.network()` 执行，回调切回 UI 线程。DB 写入禁止在 UI 线程。

### 13.5 总结自检机制

`TrackFragment.validateSummary` 校验生成结果：JSON 可解析、type 合法（event/habit/quote/default/letter）、字数在周期对应范围内、内容前 40 字符不重复。不合格则用 temperature=0.3 后台重新生成一次，仍不合格保留原结果。

### 13.6 层级复用输入

月/季/年总结复用子周期总结作为输入，提升信息密度。`buildMonthlyEntriesText` 复用本月各周总结，`buildQuarterlyEntriesText` 复用本季各月总结，`buildYearlyEntriesText` 复用全年各周总结。缺失子周期用原始日记补齐。`getExistingSummaryKeys` 批量查询已缓存子周期，`getSummariesTextForKeys` 拼接文本。

### 13.7 洞察问答上下文压缩

日记超 50000 字符时触发压缩。按月份分块（识别 `【yyyy-MM-dd】` 前缀），并发调 `compressDiaryChunk`，CountDownLatch 等待 120s。全失败用 `sampleDiaryEvenly` 均匀采样兜底。压缩后仍超 40000 字符则再采样。

### 13.8 标签回顾选取

`TagDao.pickTagAndDiaries` 实现 7 天排除 + 随机选取 + 兜底全标签的策略。排除 7 天内已展示过的标签，全部展示过则放宽。随机打乱后选第一个有关联日记的标签，从该标签关联日记中随机选最多 3 篇（排除今日）。确保标签回顾不重复，给用户新鲜感。

### 13.9 SQL 安全

- `AnalyticsDao.incrementOpenCount` 的 column 参数用 `ALLOWED_OPEN_COLUMNS` 白名单校验，防 SQL 注入
- `DiaryDao.searchByKeyword` 用 `LIKE ? ESCAPE '\\'` 转义 `% _ \` 通配符
- 日期查询用半开区间 `[start, nextDay)` 避免边界问题
- `DiaryDbHelper.onDowngrade` 不删数据，仅打日志

---

## 十四、文件结构

```
LifeTrack/
├── app/                                    # Android 应用模块
│   ├── build.gradle.kts                    # 模块构建配置
│   ├── proguard-rules.pro                  # 混淆规则
│   └── src/main/
│       ├── AndroidManifest.xml             # 清单文件
│       ├── java/com/example/lifetrack/
│       │   ├── SplashActivity.java         # 启动闪屏页
│       │   ├── LoginActivity.java          # 登录页（简化版）
│       │   ├── MainActivity.java           # 主入口，管理 5 个 Fragment
│       │   ├── UsageDetailActivity.java    # 使用统计详情 + 导出导入
│       │   ├── RecordFragment.java         # 记录页（图片/语音/文字）
│       │   ├── DiaryFragment.java          # 日记浏览页（月历+搜索）
│       │   ├── TrackFragment.java          # 追踪页（6 周期 + 4 维度 Tab）
│       │   ├── InsightFragment.java        # 洞察对话页
│       │   ├── ProfileFragment.java        # 个人中心
│       │   ├── DeepSeekClient.java         # DeepSeek AI 客户端
│       │   ├── DoubaoClient.java           # 豆包 AI 客户端
│       │   ├── AIHttpUtils.java            # SSE 解析 + 错误处理
│       │   ├── OkHttpHelper.java           # 三实例 OkHttp 工厂
│       │   ├── DialogHelper.java           # 弹窗工厂
│       │   ├── DiaryDbHelper.java          # SQLCipher 加密 DB（v12，11 表）
│       │   ├── DiaryDao.java               # 日记 DAO
│       │   ├── SummaryDao.java             # 总结 DAO
│       │   ├── AnalyticsDao.java           # 使用统计 DAO
│       │   ├── InsightDao.java             # 洞察 DAO
│       │   ├── DimensionDao.java           # 维度记录 DAO
│       │   ├── TagDao.java                 # 标签 DAO
│       │   ├── ReflectionDao.java          # 回顾感想 DAO
│       │   ├── CryptoHelper.java           # KeyStore 密钥管理
│       │   ├── EncryptedPrefsHelper.java   # 加密 SP 工厂
│       │   ├── KeyManager.java             # API Key 提供（硬编码）
│       │   ├── ImageUtils.java             # 图片处理
│       │   ├── DateUtils.java              # 日期格式化
│       │   ├── ThemeHelper.java            # 主题管理
│       │   ├── AppExecutors.java           # 线程池
│       │   ├── DiaryExportHelper.java      # 日记导出导入
│       │   ├── DimensionChartView.java     # 维度折线图
│       │   ├── WaveformView.java           # 录音波形
│       │   └── CalendarAdapter.java        # 日历适配器
│       ├── res/
│       │   ├── layout/                     # 布局文件
│       │   ├── drawable/                   # 图形资源
│       │   ├── drawable-night/             # 暗色图形
│       │   ├── color/                      # 颜色状态列表
│       │   ├── values/                     # 字符串/颜色/主题
│       │   ├── values-night/               # 暗色主题
│       │   ├── menu/                       # 菜单资源
│       │   ├── mipmap-*/                   # 应用图标
│       │   └── xml/                        # 配置 XML
│       └── ...
├── context/                                # 项目上下文文档
│   ├── project-overview.md
│   ├── ai-integration.md
│   ├── security-architecture.md
│   ├── deep-insight-system.md
│   └── ...（其他历史记录）
├── scripts/
│   ├── md_to_docx.py                       # MD 转 DOCX 工具
│   └── generate_flomo_report.py            # flomo 报告生成
├── align_elf_16kb.py                       # 16KB 页面对齐脚本
├── build.gradle.kts                        # 根项目构建配置
├── settings.gradle.kts                     # 项目设置
├── gradle/
│   ├── libs.versions.toml                  # 依赖版本目录
│   └── wrapper/                            # Gradle Wrapper
├── gradlew / gradlew.bat                   # Gradle 执行脚本
├── gradle.properties                       # Gradle 属性
├── lifetrack-release.jks                   # Release 签名密钥库
├── local.properties                        # 本地配置（签名密码等）
└── CODE_WIKI.md                            # 本文档
```

---

## 十五、已知问题与改进方向

### 安全方面

| 问题 | 当前状态 | 建议 |
|------|----------|------|
| API Key 硬编码 | KeyManager 中明文常量 | 移到服务端代理或运行时拼接 |
| 后端 HTTP 明文 | context 文档提到后端用 HTTP | 启用 HTTPS |
| JWT 无刷新机制 | 30 天过期 | 实现 Refresh Token |
| 密钥重建后数据丢失 | consumeKeyRegeneratedFlag 通知调用方 | 考虑密钥迁移而非清空 |

### 架构方面

- 数据库版本已到 v12，onUpgrade 分支较多，可考虑抽取迁移脚本
- TrackFragment 方法数过多，可拆分为多个职责类（周期管理、维度渲染、历史回填）
- LoginActivity 简化为本地校验，如需恢复服务端认证需重新接入
- 部分 Fragment 直接操作 DAO，可引入 ViewModel 隔离
