# Deep Insight System

## 2026-06-14 — 深度洞察系统实现完成

### 概述
实现了三个核心功能：结构化洞察提取与积累、长期趋势追踪、年度/季度深度复盘。解决了日记"单次消费"问题，使 AI 总结能参考历史洞察、追踪长期趋势、提供深度复盘。

### 架构决策

| 决策 | 选择 | 原因 |
|------|------|------|
| 结构化数据与总结文本分离 | ```INSIGHT_JSON``` / ```TREND_JSON``` 标记块 | 避免与 parseSummaryResult 的 JSON 提取冲突 |
| 趋势可视化 | 自定义 Canvas 折线图 | 无需引入第三方图表库，轻量 |
| 数据库升级 | v6 → v7，新增 insights + trend_metrics 表 | 独立表存储，与 summaries 解耦 |
| 年度/季度 prompt | 专用深度复盘 prompt 替代通用 prompt | 输出"年度信"而非"周报" |
| 历史洞察注入 | 最近 5 条历史洞察作为上下文 | 平衡 token 消耗与信息量 |
| 趋势对比 | 上一个同类型周期指标注入 prompt | AI 可输出"相比上周情绪分从6升至8" |

### 新增文件
- `InsightDao.java` — 洞察数据访问（saveInsight, getInsight, getRecentInsights, buildInsightContext）
- `TrendDao.java` — 趋势指标数据访问（saveMetrics, getMetrics, getRecentMetrics, buildPreviousTrendContext）

### 修改文件
- `DiaryDbHelper.java` — DATABASE_VERSION 6→7，新增 insights 和 trend_metrics 表
- `DeepSeekClient.java` — 新增 generateSummaryStreamWithInsight 方法和 SummaryWithInsightStreamCallback 接口
- `TrackFragment.java` — 重写 generateAndCache，新增趋势图渲染逻辑
- `fragment_track.xml` — 新增 trendChartArea LinearLayout

### 数据库表结构

**insights 表**：
| 字段 | 类型 | 说明 |
|------|------|------|
| _id | INTEGER PK | 自增 |
| period_type | TEXT NOT NULL | 周期类型 |
| period_key | TEXT NOT NULL | 周期标识 |
| key_persons | TEXT | 关键人物（逗号分隔） |
| key_projects | TEXT | 关键项目（逗号分隔） |
| key_events | TEXT | 关键事件（逗号分隔） |
| emotion_themes | TEXT | 情绪主题（逗号分隔） |
| decisions | TEXT | 决策节点（逗号分隔） |
| created_at | TEXT | 创建时间 |

**trend_metrics 表**：
| 字段 | 类型 | 说明 |
|------|------|------|
| _id | INTEGER PK | 自增 |
| period_type | TEXT NOT NULL | 周期类型 |
| period_key | TEXT NOT NULL | 周期标识 |
| mood_score | REAL | 情绪分 1-10 |
| energy_score | REAL | 精力分 1-10 |
| social_score | REAL | 社交分 1-10 |
| work_pressure_score | REAL | 工作压力分 1-10 |
| health_score | REAL | 健康分 1-10 |
| created_at | TEXT | 创建时间 |

### 关键 Bug 修复
1. **DB 写入在 UI 线程** → 移至 new Thread() 后台线程
2. **parseSummaryResult 解析错误** → 新增 stripInsightBlocks 方法移除标记块
3. **trendChartArea 未隐藏** → 在所有异常分支添加 setVisibility(GONE)
4. **冗余 onPartialResult 调用** → 移除 onComplete 前的冗余调用
5. **误改原 generateSummaryStream** → 回退，确保原方法不变
6. **buildPreviousTrendContext 取最旧指标** → 新增 getRecentMetricsDesc(DESC排序)，改用该方法取最新指标
7. **趋势图与数据保存竞态** → loadTrendChart 移到 saveInsightFromJson/saveTrendFromJson 完成后执行
8. **流式中部分 INSIGHT_JSON 块未剥离** → stripInsightBlocks 处理部分块：找不到结束标记时从起始标记截断到末尾
9. **onDraw 反复调用 dpToPx** → dpToPx 结果缓存为实例变量，首次 onDraw 时初始化

### Spec 文件位置
- `d:\Coding\AISummary\LifeTrack\.trae\specs\deep-insight-system\spec.md`
- `d:\Coding\AISummary\LifeTrack\.trae\specs\deep-insight-system\tasks.md`
- `d:\Coding\AISummary\LifeTrack\.trae\specs\deep-insight-system\checklist.md`

### 数据流详解

**generateAndCache 完整流程**：
1. 后台线程查询 historyInsightContext（最近 5 条同类型洞察）和 previousTrendContext（上期趋势指标）
2. 切回 UI 线程调用 DeepSeekClient.generateSummaryStreamWithInsight
3. 流式回调 onPartialResult：stripInsightBlocks → parseSummaryResult → updateGeneratingProgress
4. onComplete：
   - 新线程保存 insight + trend 到 DB
   - 保存完成后 post 到 UI 线程 loadTrendChart
   - UI 线程：dismissDialog → saveSummary → updateContentUI → preloadAdjacentPeriods
5. onFailure：trendChartArea.setVisibility(GONE) + 显示错误

**趋势图渲染**：
- 取最近 12 条同类型 trend_metrics（ASC 排序）
- 5 维度折线图：情绪(绿)、精力(蓝)、社交(橙)、压力(红)、健康(紫)
- 维度标签可点击切换显示/隐藏
- Canvas 自定义 View，dpToPx 缓存为实例变量

**洞察问答流程**（InsightFragment）：
1. DeepSeekClient.analyzeTimeRange → 返回 {start_date, end_date}（智能时间范围：事实7天/模式30天/趋势90天/情绪14天）
2. DiaryDao.queryByDateRange 获取日记
3. 日记格式化：【日期】\n内容
4. 超 50000 字按月分块压缩（compressDiaryChunk 并发）
5. 压缩后仍超 40000 字则截取末尾
6. 查询洞察标签上下文（buildInsightContextForRange）和趋势指标上下文（buildTrendContextForRange）
7. UI线程拷贝对话历史（conversationHistory，最近10轮）
8. DeepSeekClient.answerWithDiaryContext 流式回答（注入对话历史+洞察上下文+趋势上下文）
9. 回答完成后：保存到对话历史 → 生成追问建议（generateFollowUpSuggestions）→ 显示在回答下方
10. 用户点击追问建议 → 自动填入输入框并发送

**洞察问答改进**（2026-06-14）：
- 多轮对话：conversationHistory 维护最近10轮问答，传入API messages数组
- Prompt分析框架：6步隐式推理指令（维度识别→事件提取→因果链→矛盾分析→意义→建议）
- 智能时间范围：analyzeTimeRange prompt增加问题类型分类规则
- 洞察/趋势上下文注入：buildInsightContextForRange + buildTrendContextForRange
- 追问建议：generateFollowUpSuggestions 生成3个追问chip，点击可发送
- Fragment泄漏修复：onDestroyView 清除 adapter.fragment 引用
- ViewHolder复用修复：onViewRecycled 清理动态添加的 followUpArea
