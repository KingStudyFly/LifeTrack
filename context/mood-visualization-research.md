# 心情趋势与热力图可视化调研

## 2026-07-02 — 日记软件情绪可视化调研

### 调研范围
18款日记与情绪追踪App + 通用设计模式，覆盖英文日记（Day One、Journey、Apple Health）、中文日记（EMMO、心岛日记、格志等）、专门情绪追踪App（Daylio、eMoods、Mooda、Pixels、Bearable等）。

### 核心发现

1. **情绪可视化不是日记App标配**。传统简洁型日记App（一本日记、Penzu、Momento、每日记）普遍不做情绪统计。

2. **热力图两大流派**：
   - GitHub-style周网格（7列x52行，适合年度概览）——代表：Pixels、eMoods
   - Year in Pixels艺术画（365格方阵，视觉冲击强）——代表：Daylio、Moodistory

3. **数据模型光谱**：
   - 离散表情（Mooda、EMMO）→ 1-5级评分（Daylio，最常用）→ 连续滑块（Apple Health -1.0~1.0）→ 医学级多维量表（eMoods、Bearable）

4. **分析深度分层**：
   - L1 简单统计 → L2 趋势分析 → L3 关联分析（Daylio活动-心情关联是核心付费卖点）→ L4 模式识别 → L5 AI洞察（EMMO）→ L6 医学级（eMoods波动预警）

5. **记录交互模式**：
   - 绑定模式（写日记时选心情）：Journey、EMMO、格志
   - 解耦模式（独立记录）：Apple Health、Daylio、eMoods
   - 混合模式（心情必填文字可选）：Daylio

### 对LifeTrack的建议（记录在完整报告中）

- 简化为一维"今日心情"（1-5级+emoji），删除五维模型
- 年度GitHub-style热力图 + 月度折线图组合
- 心情与日记绑定（写日记时选），不另开入口
- 情绪分布统计（饼图、streak、平均值对比）
- 数据模型：diary表新增 mood_score (1-5, 0=未记录) + mood_emoji

### 完整报告
- docx：`日记软件心情趋势与热力图实现方式调研报告.docx`
- md：`情绪可视化调研报告_raw.md`
