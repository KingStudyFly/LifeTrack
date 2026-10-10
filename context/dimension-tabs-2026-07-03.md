# 轨迹页运动/睡眠/社交三维度分析

## 2026-07-03 — 维度 Tab 完整功能落地

### 背景
轨迹页此前的运动/睡眠/社交三个 Tab 是占位空壳。用户要求实现：
- 运动：记录不同种类运动次数（上肢、核心、跑步等）；月度波浪图看每天运动强度（从日记描述分析）；运动能力进步分析
- 睡眠：从用户每天描述打分；月度波浪图看每天睡眠；恶性睡眠影响因素分析
- 社交：和重要人物的交流（近三个月）
- 三个 Tab 都要加"读取过往日记"按钮，回填历史日记的维度分
- 每天写日记时自动用 DeepSeek 分析、更新维度数据
- 打分时参考过往七天打分，保证标准统一（0-100）
- 维度分析与日记日期拆分合并到同一个 prompt，节省请求次数

### 关键实现

**数据层**
- `DiaryDbHelper`: DATABASE_VERSION 11→12，新增 `dimension_records` 表（diary_id 外键 ON DELETE CASCADE）
- `DimensionDao`: insert/getByDateRange/getRecent/getRecentScoresText(拼接"日期 运动:65[上肢,核心] 睡眠:72"供 AI 参考)/hasRecordForDate/getSocialRecords
- 修复了 getRecentScoresText 错误地用 jsonArrayToList 解析 List.toString() 的 bug

**AI 层**
- `DeepSeekClient`: 新增 `DimensionData` 类（exerciseScore, exerciseTypes, exerciseExcerpt, sleepScore, sleepExcerpt, socialContacts, socialExcerpt）
- `analyzeDiaryDatesStreamWithDimensions`: 合并 prompt（日期拆分规则 + 三维度分析规则 + 7天历史参考 + JSON 输出含 dimensions），max_tokens 4096
- `analyzeDiaryDatesWithDimensions`: 非流式包装
- `generateTextAnalysis`: 通用聚合分析方法（运动进步分析、睡眠影响因素分析复用）

**记录层**
- `RecordFragment.saveWithAiAnalysis`: 重构为先取 7 天历史 → 调 `analyzeDiaryDatesWithDimensions` → 写入 dimension_records

**轨迹层**
- `DimensionChartView`: 新建自定义折线图（断点处理、网格线 25/50/75、棕色配色、180dp 高、空状态文案）
- `fragment_track.xml`: 三 Tab 替换为完整布局（周期切换行 + 趋势卡 + 统计卡 + 分析卡 + 读取过往按钮）
- `TrackFragment`: 维度数据加载/渲染/按钮绑定；递归批处理 `processHistoryBatch`（每批 3 篇，跳过已打分日期）；聚合分析按需调用并缓存到 summaries 表

**strings.xml**: 新增 22 条维度相关字符串

### 两个关键 Bug 与修复
1. **dpToPx 重复定义**: TrackFragment 原本已有此方法，我新增的代码又加了一个，导致编译失败。删除重复定义。
2. **lowCount 非最终变量**: 在 `loadSleepFactors` 中，外层 lambda 里 `lowCount++` 修改了变量，内部匿名类 `TextAnalysisCallback.onResult` 又引用它，违反 Java 闭包规则。修复：用 `final int lowCountFinal = lowCount;` 捕获快照。

### JAVA_HOME 配置
本机 JAVA_HOME 错误地指向 `D:\IJ`（不存在）。正确的 JDK 在：
`C:\Users\zzl\AppData\Local\Programs\Microsoft\jdk-21.0.7.6-hotspot`
编译时需在 PowerShell 中临时设置：
```powershell
$env:JAVA_HOME="C:\Users\zzl\AppData\Local\Programs\Microsoft\jdk-21.0.7.6-hotspot"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat assembleDebug
```

### 最终状态
`BUILD SUCCESSFUL`，三维度 Tab 完整功能可用，等待真机验证日记写入与历史回填流程。
