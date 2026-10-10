# LifeTrack AI洞察差异化实现方案

> 基于浮墨笔记调研结论，结合LifeTrack现有代码结构（DB v10、6张表、DeepSeek+豆包双API），设计四个差异化方向的详细实现方案。

---

## 一、现状基线

在给方案之前，先明确LifeTrack当前有什么、缺什么：

| 能力 | 当前状态 | 缺什么 |
|------|---------|--------|
| 日记存储 | `diary` 表（_id, timestamp, content），SQLCipher加密 | 无附件元数据、无标签、无目标关联 |
| AI洞察问答 | InsightFragment，两轮AI（草稿0.5+精修0.4），带历史洞察上下文注入 | 单一视角，无多视角切换 |
| 周期总结 | 5种周期，带结构化洞察标签（5维度），prompt_version缓存控制 | 无时间序列追踪（trend_metrics已删） |
| 图片/语音 | DoubaoClient支持Vision和Audio识别 | 识别后仅转文本存入content，原始文件无管理 |
| 使用统计 | AnalyticsDao记录foreground_seconds和各页面opens | 仅App行为统计，无日记内容维度统计 |
| 多模态上下文 | 无 | 天气、位置、照片等均未融入AI上下文 |
| 目标系统 | 无 | 完全缺失 |
| 隐私 | SQLCipher本地加密，AI调用走DeepSeek云端 | 敏感日记内容明文上传第三方 |

---

## 二、方向一：时间序列分析

### 2.1 要解决什么问题

flomo的AI洞察是「按需检索」模式——用户问什么，AI搜什么。但日记有一个flomo笔记不具备的特征：**时间连续性**。日记是每天写的，数据天然按时间排列，适合做周期性的趋势追踪。

LifeTrack之前有过trend_metrics表（5维度1-10分评分），但在DB v10被删了。原因可能是：AI打分不稳定、用户看不到价值、折线图交互差。这次不恢复旧方案，换一个更轻、更准的思路。

### 2.2 设计思路

**核心变化：不靠AI打分，靠insights表已有数据做时间序列。**

insights表每次周期总结都会写入5个字段：key_persons、key_projects、key_events、emotion_themes、decisions。这些数据已经在积累，但当前只用于「注入下次总结的上下文」，没有做时间维度的可视化和分析。

方案分三层：

#### 第一层：情绪主题频率统计（本地计算，不调AI）

insights.emotion_themes字段存的是逗号分隔的字符串（如"焦虑,疲惫,满足"）。对历史insights做词频统计，生成情绪趋势图。

```
数据流：
insights表 → 按period_type筛选 → 提取emotion_themes字段
  → 分词 → 词频统计 → 按时间排列 → 渲染折线图/热力图
```

**新增文件**：
- `EmotionTrendAnalyzer.java`：纯本地计算，不调AI
  - `Map<String, List<EmotionPoint>> analyzeEmotionTrend(String periodType, int limit)`
  - 从InsightDao.getRecentInsights取数据，分词统计，返回每个情绪词在时间轴上的出现频率
  - 输出格式：`[{emotion: "焦虑", points: [{period: "2026-W26", count: 3}, ...]}, ...]`

**UI变化**：
- TrackFragment新增「情绪趋势」卡片区域
- 用Canvas画热力图：X轴=时间周期，Y轴=情绪词，颜色深浅=出现频率
- 点击某个情绪词，高亮该词在所有周期中的变化趋势

**效果**：用户打开Track页就能看到「过去三个月焦虑出现频率在下降」「疲惫在第二周达到峰值」，不需要主动问AI。

#### 第二层：AI多视角趋势解读（调AI，但基于第一层的数据）

在第一层统计数据基础上，让AI从不同视角解读趋势。

**DeepSeekClient新增方法**：
```java
public static void analyzeEmotionTrend(
    String emotionTrendData,      // 第一层的统计结果
    String perspective,            // 视角：cause|pattern|action
    InsightAnswerCallback callback
)
```

三种视角的prompt差异：
- **cause（归因视角）**：为什么这个情绪在上升/下降？结合日记原文找原因
- **pattern（模式视角）**：这个情绪是否有周期性规律？比如每月末焦虑加剧
- **action（行动视角）**：基于趋势，接下来一周建议做什么

**UI变化**：
- 情绪趋势图下方加三个Tab：归因 / 模式 / 行动
- 点击Tab触发对应视角的AI分析，结果以卡片形式展示

#### 第三层：关键事件时间线（跨周期关联）

insights表的key_events字段已经记录了每个周期的关键事件。把这些事件按时间排列，形成时间线。

**新增方法**（InsightDao.java）：
```java
public List<EventEntry> getEventTimeline(String periodType, int limit)
// 从insights提取key_events，按period_key排序，返回 [{period, event, date}]
```

**UI变化**：
- TrackFragment新增「关键事件时间线」区域
- 纵向时间线展示，每个节点显示周期+事件摘要
- 点击事件节点，跳转到该周期的总结详情

### 2.3 数据库变更

不需要新增表。insights表已有足够数据。仅需：
- DiaryDbHelper v11：在insights表增加`emotion_keywords` TEXT字段（存预处理后的情绪关键词JSON），加速查询
  ```sql
  ALTER TABLE insights ADD COLUMN emotion_keywords TEXT NOT NULL DEFAULT '';
  ```
- SummaryDao在保存总结时，同时解析emotion_themes并存入emotion_keywords（JSON数组格式）

### 2.4 预期效果

用户写日记 → 周期总结自动提取情绪主题 → Track页自动展示情绪趋势热力图 → 用户点「归因」看AI解读为什么焦虑在上升 → 点击事件时间线看关键转折点。整个链路不需要用户主动提问，数据从已有insights中提取。

---

## 三、方向二：多媒体融合

### 3.1 要解决什么问题

LifeTrack已经能通过豆包API识别图片和语音，但识别结果只是转成文本塞进diary.content。原始图片/语音文件没有管理，天气和位置信息完全没采集。AI洞察时，上下文只有纯文本，丢失了 multimodal 信息。

### 3.2 设计思路

分三块：附件管理、环境数据采集、多模态上下文注入。

#### 3.2.1 附件管理

**数据库变更**（DB v11）：
```sql
CREATE TABLE diary_attachments (
    _id INTEGER PRIMARY KEY AUTOINCREMENT,
    diary_id INTEGER NOT NULL,
    type TEXT NOT NULL,          -- 'image' | 'audio' | 'video'
    file_path TEXT NOT NULL,     -- 本地路径
    thumbnail_path TEXT,         -- 缩略图路径（图片专用）
    duration_ms INTEGER,         -- 时长（音视频专用）
    ai_description TEXT,         -- AI生成的描述（图片专用）
    created_at TEXT NOT NULL,
    FOREIGN KEY(diary_id) REFERENCES diary(_id) ON DELETE CASCADE
);
CREATE INDEX idx_attachments_diary_id ON diary_attachments(diary_id);
```

**新增文件**：
- `AttachmentDao.java`：CRUD + 按diary_id查询
- `AttachmentManager.java`：文件存储管理
  - `saveImage(Bitmap, long diaryId)` → 压缩存到 `app/files/images/yyyy-MM/`
  - `saveAudio(String filePath, long diaryId)` → 存到 `app/files/audio/yyyy-MM/`
  - `cleanupOrphans()` → 清理无关联的孤儿文件

**UI变化**：
- RecordFragment：拍照/选图后，图片以缩略图条形式显示在输入框下方，不只是识别文字
- DiaryFragment：日记列表项显示附件图标（📷📎🎤）
- 日记详情：图片缩略图可点击查看大图，语音可播放

#### 3.2.2 环境数据采集

在保存日记时自动采集环境信息，存入diary表新增字段。

**数据库变更**（DB v11）：
```sql
ALTER TABLE diary ADD COLUMN weather TEXT NOT NULL DEFAULT '';
ALTER TABLE diary ADD COLUMN location TEXT NOT NULL DEFAULT '';
ALTER TABLE diary ADD COLUMN mood_tag TEXT NOT NULL DEFAULT '';
```

- `weather`：格式 `"晴,26℃"` 或空串
- `location`：格式 `"上海市浦东新区"` 或空串
- `mood_tag`：用户手选的情绪标签，格式 `"焦虑"` 或空串

**新增文件**：
- `EnvironmentHelper.java`：
  - `getWeather(double lat, double lng)` → 调用和风天气API（免费额度够用）
  - `getLocation(Context)` → 调用FusedLocationProviderClient获取粗略位置
  - 两者均为可选采集，用户可在偏好设置中关闭

**UI变化**：
- RecordFragment保存日记时，底部状态栏自动显示「📍上海 ☀️26℃」
- 用户可手动修改mood_tag：输入框下方加一行情绪chip选择器（6个常用情绪：平静/开心/焦虑/疲惫/沮丧/愤怒）

#### 3.2.3 多模态上下文注入

修改InsightFragment的`buildInsightContextForRange`方法，把附件描述、天气、位置、情绪标签融入上下文。

**当前上下文格式**：
```
【2026-07-01】
今天去了医院，人很多等了很久。
```

**改进后**：
```
【2026-07-01】☀️晴 28℃ 📍上海 😐焦虑 📎2张图片
今天去了医院，人很多等了很久。
[图片描述：医院候诊大厅，排队人群]
```

**DeepSeekClient.answerWithDiaryContext的prompt追加**：
```
注意：日记中标注了天气、位置、情绪标签和图片描述。
请在分析中考虑这些环境因素与日记内容的关联，例如天气对情绪的影响、位置与事件的对应关系。
```

**DoubaoClient新增方法**：
```java
public static void describeImage(String imagePath, DescriptionCallback callback)
// 对单张图片生成一句话描述，存入diary_attachments.ai_description
// prompt: "用一句话描述这张图片的内容，重点捕捉场景、人物动作和氛围"
```

在日记保存后异步触发图片描述生成，不阻塞用户操作。

### 3.3 预期效果

用户拍一张医院排队的照片写日记 → 系统自动记录天气、位置、情绪标签 → AI描述图片内容 → 下次问AI「我最近为什么老觉得累」时，AI能看到「那天28℃大晴天你去医院排了很久的队」，分析更立体——不只是文本层面的「累」，还能关联到环境因素。

---

## 四、方向三：目标关联

### 4.1 要解决什么问题

flomo的AI洞察偏向「你是什么样的人」，适合自我认知。但LifeTrack作为生活追踪工具，用户除了想了解自己，还想追踪「我想做到的事做得怎么样了」。当前LifeTrack完全没有目标系统，日记和目标之间没有关联。

### 4.2 设计思路

#### 4.2.1 目标系统

**数据库变更**（DB v11）：
```sql
CREATE TABLE goals (
    _id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL,           -- "每天运动30分钟"
    description TEXT NOT NULL DEFAULT '',
    category TEXT NOT NULL,        -- 'health' | 'work' | 'learning' | 'relation' | 'other'
    status TEXT NOT NULL DEFAULT 'active',  -- 'active' | 'paused' | 'completed' | 'abandoned'
    start_date TEXT NOT NULL,
    target_date TEXT,              -- 可选截止日期
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE diary_goals (
    diary_id INTEGER NOT NULL,
    goal_id INTEGER NOT NULL,
    ai_relevance TEXT NOT NULL DEFAULT '',  -- AI判断的关联理由
    PRIMARY KEY(diary_id, goal_id),
    FOREIGN KEY(diary_id) REFERENCES diary(_id) ON DELETE CASCADE,
    FOREIGN KEY(goal_id) REFERENCES goals(_id) ON DELETE CASCADE
);
CREATE INDEX idx_diary_goals_goal_id ON diary_goals(goal_id);
```

**新增文件**：
- `GoalDao.java`：目标CRUD + 按状态/类别查询
- `DiaryGoalDao.java`：关联关系CRUD + 按goal_id查日记 + 按diary_id查目标

**UI变化**：
- ProfileFragment新增「我的目标」入口
- 目标管理页：列出所有目标，按状态分组
  - 创建目标：标题 + 类别（5选1） + 可选截止日期 + 描述
  - 目标详情：关联日记列表 + 进展时间线 + AI进展评估

#### 4.2.2 AI自动关联日记与目标

日记保存后，异步调用AI判断这条日记与哪些目标相关。

**DeepSeekClient新增方法**：
```java
public static void relateDiaryToGoals(
    String diaryContent,
    List<GoalEntry> activeGoals,    // 当前active状态的目标列表
    RelateCallback callback         // 返回 List<{goalId, relevance}>
)
```

**Prompt设计**：
```
[系统角色]
你是LifeTrack的目标关联助手。判断用户日记内容与以下哪个目标相关。

[目标列表]
目标1：每天运动30分钟（健康类）
目标2：完成Flutter学习课程（学习类）
目标3：每周和家人通话一次（关系类）

[用户日记]
{diaryContent}

[输出要求]
返回JSON数组，每个元素包含goal_id和relevance（一句话说明关联理由）。
只返回明确相关的目标，不相关的不要勉强关联。
如果没有任何相关目标，返回空数组 []。
```

temperature = 0.1，max_tokens = 512，非流式调用。

**触发时机**：
- 日记保存后，在`RecordFragment.saveDiary()`的回调中异步触发
- 不阻塞用户操作，关联结果静默写入diary_goals表
- 如果AI判断有关联，在日记详情页底部显示「关联目标：XXX」标签

#### 4.2.3 AI目标进展评估

用户在目标详情页点击「AI评估进展」时，AI读取所有关联日记，生成进展报告。

**DeepSeekClient新增方法**：
```java
public static void assessGoalProgress(
    GoalEntry goal,
    List<String[]> relatedDiaries,   // 关联日记列表
    InsightAnswerCallback callback
)
```

**Prompt设计**：
```
[系统角色]
你是LifeTrack的目标进展评估助手。

[目标信息]
目标：{goal.title}
类别：{goal.category}
开始日期：{goal.start_date}
截止日期：{goal.target_date or "无"}
描述：{goal.description}

[关联日记]（共{count}篇）
【2026-06-15】今天跑了5公里，感觉不错。
【2026-06-20】膝盖有点疼，没运动。
【2026-06-25】偷懒了三天，有点自责。
...

[输出要求]
1. 进展评估：推进中/停滞/倒退，附判断依据
2. 关键节点：列出日记中反映的重要进展或阻碍
3. 模式识别：是否存在反复出现的阻碍模式
4. 建议：接下来一周的具体行动建议（不超过3条）
每个结论必须引用具体日记日期。
```

temperature = 0.5，流式输出，复用InsightFragment的UI样式。

#### 4.2.4 目标上下文注入洞察问答

修改InsightFragment的`buildInsightContextForRange`，追加目标上下文：

```
用户当前正在追踪的目标：
1. 每天运动30分钟（健康类，进行中，开始于2026-06-01）
2. 完成Flutter学习课程（学习类，进行中）
请在分析中考虑日记内容与这些目标的关联。
```

### 4.3 预期效果

用户创建目标「每天运动30分钟」→ 写日记时提到「今天跑了5公里」→ AI自动关联到该目标 → 用户在目标详情页看到所有相关日记的时间线 → 点「AI评估进展」看到「过去两周运动频率下降，主要阻碍是膝盖疼痛（6月20日）和连续偷懒（6月25日），建议先恢复轻度运动」→ 在Insight问答中问AI「我最近执行力怎么样」时，AI会结合目标进展给出分析。

---

## 五、方向四：隐私优先

### 5.1 要解决什么问题

日记比通用笔记更私密。当前LifeTrack的AI调用全部走DeepSeek云端，日记内容明文上传到第三方服务器。SQLCipher只保护本地存储，不保护传输。部分用户可能因此不敢在日记中写真实想法，而这恰恰违背了日记应用的初衷。

### 5.2 设计思路

不是二选一（本地或云端），而是分层：敏感内容本地处理，常规分析走云端。

#### 5.2.1 内容敏感度分级

在日记保存时，自动判断敏感度等级。

**新增文件**：
- `ContentSensitivityClassifier.java`：
  ```java
  public enum SensitivityLevel {
      LOW,       // 日常记录，无敏感信息
      MEDIUM,    // 包含个人情绪、人际关系描述
      HIGH       // 包含健康、财务、家庭矛盾等高度私密内容
  }
  
  public static SensitivityLevel classify(String content) {
      // 规则引擎，不调AI：
      // 1. 关键词匹配：疾病/药名/金额/家庭矛盾词 → HIGH
      // 2. 情绪强度：出现3个以上负面情绪词 → MEDIUM
      // 3. 默认 → LOW
  }
  ```

**数据库变更**（DB v11）：
```sql
ALTER TABLE diary ADD COLUMN sensitivity INTEGER NOT NULL DEFAULT 0;
-- 0=LOW, 1=MEDIUM, 2=HIGH
```

**用户可控**：偏好设置中增加「隐私模式」开关：
- 关闭（默认）：所有内容走云端AI，体验最佳
- 开启：HIGH敏感度日记不走云端，仅本地处理

#### 5.2.2 本地轻量分析（隐私模式下）

不上调云端API，用本地规则+统计做基础分析。

**新增文件**：
- `LocalAnalyzer.java`：
  ```java
  public class LocalAnalysisResult {
      public String sentiment;        // positive / neutral / negative
      public List<String> keywords;   // 高频词
      public String summary;          // 一句话摘要（规则生成，非AI）
  }
  
  public static LocalAnalysisResult analyze(List<String[]> diaries) {
      // 1. 情绪分析：基于情绪词典 + 词频统计
      // 2. 关键词提取：TF-IDF，去停用词
      // 3. 摘要：取首尾句 + 高频词拼接
  }
  ```

**效果**：隐私模式下，Insight问答用本地分析结果替代DeepSeek回答。效果远不如云端AI，但保证零泄露。

#### 5.2.3 端侧大模型（进阶方案）

对于希望隐私+AI质量兼顾的用户，支持加载端侧小模型。

**技术选型**：
- [MLC LLM](https://github.com/mlc-ai/mlc-llm)：Android端可跑的4-bit量化模型
- 模型：Qwen2.5-1.5B-Instruct（约1.2GB）或 Phi-3-mini（约2.2GB）
- 推理速度：骁龙8 Gen2上约8-15 tokens/s，可接受

**新增文件**：
- `LocalLLMEngine.java`：
  ```java
  public class LocalLLMEngine {
      // 初始化MLC引擎，加载模型
      public void init(Context context, String modelPath, InitCallback callback)
      
      // 本地推理，替代DeepSeekClient的部分方法
      public void answerLocally(String question, String diaryContext, 
                                 InsightAnswerCallback callback)
      
      // 判断模型是否已下载
      public boolean isModelReady()
  }
  ```

**UI变化**：
- ProfileFragment新增「AI引擎」设置项：
  - 云端模式（默认）：DeepSeek API，质量高
  - 本地模式：端侧模型，隐私优先，质量有限
  - 混合模式（推荐）：LOW/MEDIUM走云端，HIGH走本地

**模型下载**：
- 首次切换到本地/混合模式时，提示下载模型（约1.2GB）
- 存储到 `app/files/models/qwen2.5-1.5b/`
- 下载进度条 + 断点续传

#### 5.2.4 云端调用的数据脱敏

即使在云端模式下，也对上传内容做轻量脱敏。

**新增方法**（DeepSeekClient或独立工具类）：
```java
public static String sanitizeForCloud(String diaryContent) {
    // 1. 正则替换手机号：1[3-9]\d{9} → [手机号]
    // 2. 正则替换身份证号 → [身份证]
    // 3. 正则替换银行卡号 → [卡号]
    // 4. 正则替换邮箱 → [邮箱]
    // 5. 可选：人名替换（用NER模型或规则库）
    return sanitized;
}
```

在`answerWithDiaryContext`和`generateSummaryStreamWithInsight`拼上下文前，对每条日记内容过一遍`sanitizeForCloud`。

### 5.3 预期效果

用户在偏好设置中看到「AI引擎」选项 → 选择「混合模式」→ 写日记提到具体金额和药名时，系统判定为HIGH敏感度 → Insight问答时该条日记走本地模型处理 → 其他普通日记走云端DeepSeek → 用户在日记详情页能看到敏感度标签（可手动调整）→ 所有上传云端的内容已自动脱敏。

---

## 六、实现优先级与依赖关系

```
方向一（时间序列）   ─── 无依赖，可独立开发
    └── 依赖：insights表已有数据（需积累2-3个周期）

方向二（多媒体融合） ─── 依赖DB v11迁移
    ├── 附件管理：独立开发
    ├── 环境采集：需申请定位权限
    └── 上下文注入：依赖附件管理+环境采集完成

方向三（目标关联）   ─── 依赖DB v11迁移
    ├── 目标系统：独立开发
    ├── AI关联：依赖目标系统 + DeepSeekClient
    └── 上下文注入：依赖目标系统

方向四（隐私优先）   ─── 部分独立
    ├── 敏感度分级：独立开发，依赖DB v11
    ├── 本地分析：独立开发
    ├── 端侧模型：独立但工作量大，可后期推进
    └── 数据脱敏：独立开发，可立即接入现有流程
```

**建议顺序**：
1. 先做方向四的数据脱敏（成本最低，立即生效）
2. 再做方向一的时间序列（利用已有数据，无需用户额外操作）
3. 然后做方向三的目标关联（用户价值最高，但需DB迁移）
4. 最后做方向二的多媒体融合（工作量最大，涉及权限申请）
5. 方向四的端侧模型作为长期目标推进

---

## 七、数据库迁移总览

四个方向合计需要DB v10 → v11的迁移：

```sql
-- 方向一：insights表加字段
ALTER TABLE insights ADD COLUMN emotion_keywords TEXT NOT NULL DEFAULT '';

-- 方向二：附件表 + diary表加环境字段
CREATE TABLE diary_attachments (...);
ALTER TABLE diary ADD COLUMN weather TEXT NOT NULL DEFAULT '';
ALTER TABLE diary ADD COLUMN location TEXT NOT NULL DEFAULT '';
ALTER TABLE diary ADD COLUMN mood_tag TEXT NOT NULL DEFAULT '';

-- 方向三：目标表 + 关联表
CREATE TABLE goals (...);
CREATE TABLE diary_goals (...);

-- 方向四：diary表加敏感度字段
ALTER TABLE diary ADD COLUMN sensitivity INTEGER NOT NULL DEFAULT 0;
```

迁移策略：
- `onUpgrade`中按顺序执行上述SQL
- 用`try-catch`包裹每条ALTER（兼容已存在列的情况）
- 迁移前自动创建`.bak`备份（沿用现有机制）
- 版本号写死`DATABASE_VERSION = 11`

---

## 八、风险与权衡

### 8.1 功能膨胀风险

四个方向全做完，LifeTrack的复杂度会显著上升。建议每个方向都做成可关闭的模块，用户在偏好设置中按需开启。

### 8.2 AI调用成本

方向一第二层、方向三的自动关联和目标评估都会新增AI调用。按DeepSeek定价估算：
- 方向一趋势解读：用户主动触发，月均10次，约0.3元
- 方向三自动关联：每条日记触发1次，月均30次，约0.9元
- 方向三目标评估：用户主动触发，月均5次，约0.2元

合计每月约1.4元，可控。但自动关联建议做成可关闭的，避免用户觉得费钱。

### 8.3 端侧模型可行性

Qwen2.5-1.5B在手机端跑8-15 tokens/s，对于短回答（200-300字）需要20-40秒，体验不如云端。适合做「兜底」而非主力。如果用户手机性能差，可能完全跑不动。需要在设置页明确标注硬件要求。

### 8.4 环境数据采集的隐私悖论

方向二要采集位置和天气，方向四要保护隐私。两者存在张力。解决方案：
- 位置采集只取区级精度（不存GPS坐标），仅用于展示城市名
- 天气数据基于位置间接获取，不单独存储坐标
- 所有环境采集均为可选，用户可全局关闭
- 采集的环境数据不上传云端（仅在本地拼入上下文时附带）
