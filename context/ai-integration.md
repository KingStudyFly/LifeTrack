# AI Integration Details

## 2026-09-12 — 模型切换 + 日程提取功能

- **Model**: `deepseek-chat` → `deepseek-flash`（DeepSeekClient.MODEL 常量，一处生效）
- 新增方法：`extractSchedule(content, ScheduleCallback)` — 从某天日记中提取日程安排，返回 `List<ScheduleItem>`（time/title/desc），非流式、temperature 0.1、max_tokens 1024，返回前按 HH:mm 稳定排序（无时间条目排最后，`timeToMinutes` 兜底解析）。
- 新增日程页签：底部导航「记录」与「轨迹」之间插入「日程」；MainActivity 增加第 6 个 Fragment 与顶部模式指示器第 6 个圆点（dot0-dot5）；日程页不参与模块使用统计（与洞察页一致）。
- 相关源码：`app/src/main/java/com/example/lifetrack/ScheduleFragment.java`

---

## 2026-06-14 — 完整 AI 集成架构

### DeepSeek API

- **Base URL**: `https://api.deepseek.com`
- **Model**: `deepseek-chat`
- **API Key 来源**: KeyManager.getDeepSeekKey()（从服务端获取，缓存到 EncryptedSharedPreferences）

#### 功能清单

| 方法 | 功能 | 流式 | temperature | max_tokens |
|------|------|------|-------------|------------|
| verifyApiKey | 验证 Key | 否 | - | - |
| analyzeDiaryDateStream | 单日期分析 | 是 | 0.1 | 256 |
| analyzeDiaryDatesStream | 多日期拆分 | 是 | 0.1 | 2048 |
| generateSummaryStream | 周期总结生成 | 是 | 0.7 | 512 |
| generateSummaryStreamWithInsight | 总结+洞察+趋势 | 是 | 0.7 | 4096 |
| generateSummaryWithInsightSync | 预加载同步包装 | 否（内部流式） | 0.7 | 4096+ |
| analyzeTimeRange | 时间范围分析 | 否 | 0.1 | 128 |
| answerWithDiaryContext | 洞察问答 | 是 | 0.7 | 4096 |
| compressDiaryChunk | 日记压缩 | 否 | 0.3 | 4096 |

#### Prompt 架构

所有方法共用 `SYSTEM_PREFIX = "你是LifeTrack应用的AI助手，服务于用户的日记与生活记录场景。"`

**日期分析 prompt 要点**：
- 只有明确年月日才能作为新日期依据
- 相对时间词（昨天、前天）不影响日期判定
- 日期作为内容一部分（如"5月12号的考试"）不拆分
- 返回 JSON: `{"date":"yyyy-MM-dd","content":"清理后的内容"}`

**总结生成 prompt 要点**：
- 2026-06-20 起按周期粒度拆分 prompt：
  - weekly: 强调连续性变化、具体事件，卡片 80-120 字，2-4 个
  - monthly: 识别反复出现模式（3次以上），与上月对比，卡片 100-200 字，3-5 个
  - quarterly: 叙事性主卡片（200-400字）+ 2-4 张洞察卡片
  - yearly: 保持"年度信"格式
  - daily/custom: 通用洞察卡片，50-150 字，2-5 个
- 卡片 type: event / habit / quote / default / letter
- 卡片 title: 2-6 字
- 整体 title: 4-6 字
- 返回 JSON: `{"title":"...","cards":[...]}`

**年度/季度深度复盘 prompt**：
- yearly: "年度信"格式，3-5 关键词，人生阶段判断，重大转折点，同比对比，方向建议
- quarterly: 叙事性主卡片 + 洞察卡片，2-3 关键词融入主卡片，转折点，季度对比

**层级复用输入策略**（2026-06-20 新增）：
- monthly: 复用本周各周总结（`buildMonthlyEntriesText`），缺失周补原始日记
- quarterly: 复用本季各月总结（`buildQuarterlyEntriesText`），缺失月份补原始日记
- yearly: 复用全年各周总结（`buildYearlyEntriesText`），缺失周补原始日记
- isSummaryBased=true 时 inputLabel 为"月度总结及原始日记"等

**自检机制**（2026-06-20 新增）：
- `validateSummary` 校验卡片质量：JSON 可解析、type 合法、字数范围、内容重复
- 校验失败后台用 temperature=0.3 重新生成一次
- 重新生成结果仍不合格则保留原结果

**缓存版本控制**（2026-06-20 新增）：
- summaries 表加 `prompt_version` 字段（DB v8）
- `SummaryDao.CURRENT_PROMPT_VERSION = 2`，每次改 prompt 递增
- 加载缓存时比较 entry_count 和 prompt_version，任一不匹配则重新生成

**洞察+趋势提取**（generateSummaryStreamWithInsight）：
- 在总结 JSON 之后输出两个标记块
- ```INSIGHT_JSON``` → `{"key_persons":[...],"key_projects":[...],"key_events":[...],"emotion_themes":[...],"decisions":[...]}`
- ```TREND_JSON``` → `{"mood_score":7,"energy_score":6,"social_score":5,"work_pressure_score":8,"health_score":7}`
- 流式传输时 stripInsightBlocks 移除标记块，避免 parseSummaryResult 解析冲突
- 历史洞察上下文：最近 5 条同类型洞察注入 prompt
- 上期趋势上下文：上一个同类型周期指标注入 prompt

**洞察问答 prompt 要点**：
- 回答开头说明参考日期范围和日记篇数
- 纯文本输出，禁止 Markdown 格式
- 温暖有洞察力，避免简单复述

**日记压缩 prompt 要点**：
- 压缩到 40000 字以内
- 保留关键事件、决策、情绪变化、人际关系、转折点
- 删除日常琐事和重复内容

#### 缓存机制

- `LruCache<String, String>(50)` 缓存日期分析结果
- 缓存键: `"single_" + content.hashCode()` / `"multi_" + content.hashCode()`
- 总结不缓存（由 SummaryDao 持久化）

### Doubao API

- **Base URL**: `https://ark.cn-beijing.volces.com/api/v3`
- **Vision Model**: `ep-20260521220024-2mnlc`
- **Audio Model**: `ep-20260526095629-d6gwd`
- **API Key 来源**: KeyManager.getDoubaoKey()

#### 功能清单

| 方法 | 功能 | 并发 | 超时 |
|------|------|------|------|
| verifyConnection | 连接验证 | - | SSE |
| analyzeImageStreamBase64 | 单图流式分析 | - | SSE (60s read) |
| analyzeMultipleImagesConcurrent | 批量并发分析 | 3 线程池 | 180s |
| recognizeAudio | 语音识别 | - | SSE |

#### Vision System Prompt 要点

- 文字提取：完整准确，保持原文格式，不修改/翻译/润色
- 场景描述：简洁生动，2-3 句话
- 不添加前缀说明或总结
- 详细覆盖文档类、截图类、生活场景类

#### 语音识别实现

- 使用 Responses API (`/responses`)，非 Chat Completions
- 音频 Base64 编码嵌入 `input_audio` 类型
- 解析 `output[].content[].text` 提取结果
- 音频格式: AAC (MPEG_4), 16kHz 采样率, 单声道

### OkHttp 配置

| 实例 | connect | read | write | 用途 |
|------|---------|------|-------|------|
| default | 10s | 10s | 10s | 认证、同步 |
| sse | 15s | 60s | 15s | AI 流式请求 |
| longTimeout | 15s | 180s | 15s | 批量图片分析 |

### SSE 解析统一模式

1. 设置 `Accept: text/event-stream` + `Connection: keep-alive`
2. BufferedReader 逐行读取
3. 匹配 `data: ` 前缀
4. 解析 `choices[0].delta.content`
5. `data: [DONE]` 标记完成
6. 异常 chunk 静默跳过（Log.w）
