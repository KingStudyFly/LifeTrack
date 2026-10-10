# 记录页灰字改动 + 轨迹页 null 显示修复 + AI 思考模式关闭

## 2026-09-13 — 轨迹/日程 AI 关闭思考模式（thinking disabled）

**背景**：用户要求轨迹模块和日程模块的 AI 不要开思考模式。
**查证**：DeepSeek 新 API（base_url `https://api.deepseek.com`，模型 `deepseek-flash`，见 DeepSeekClient.MODEL）的[思考模式默认开启](https://api-docs.deepseek.com/guides/thinking_mode/)（enabled by default，effort=high）。代码里虽然没显式开思考，服务端默认仍会先输出 CoT（reasoning_content），导致生成慢、回归问题排查困难。
**关闭方式**：请求体顶层加 `"thinking": {"type": "disabled"}`。

**改动点（DeepSeekClient.java）**：
- `extractSchedule`（日程提取）→ disabled
- `generateSummaryStreamWithInsight`（轨迹周期总结，含同步包装 generateSummaryWithInsightSync）→ disabled
- `generateTextAnalysis`（轨迹的运动进步分析 / 睡眠影响因素分析）→ disabled

**注意**：洞察问答 `answerWithDiaryContext`（InsightFragment 用）保持不变 —— 刻意保留思考/分析过程用于提升回答质量；记录页维度分析 `analyzeDiaryDates*` 未动。

---

## 2026-09-13 — 记录页灰字

`strings.xml` 中 `record_input_hint` 由 `写下你的想法…` 改为 `睡眠，运动......`。

---

## 2026-09-13 — 轨迹页"AI 总结显示一大堆 null"修复

**根因**：DeepSeek 返回的卡片 JSON 中字段为显式 `null`（如 `"content": null`、`"keywords": [null]`、`"life_stage": null`、`"turning_points": [null]`）时，org.json 的 `JSONObject.optString(name, fallback)` 在 **key 存在但值为 null** 时返回**字面字符串 `"null"`**（`value.toString()`），而不是 fallback。于是渲染层标题/卡片内容/关键词/转折点全部显示 "null"。

**修复（TrackFragment.java）**：
- 新增 `sanitizeText(String)`：`null` / `"null"`（忽略大小写）/ 空白 → 返回 `""`，否则原样返回
- `renderContent`：title、cardType、cardTitle、cardContent、life_stage 全部过 sanitize（字面 null → 空，不再显示 "null"）
- `createLetterCardView`：keywords / turningPoints 数组元素 optString 也会得到 "null"，循环内逐项 sanitize
- `joinArrayToString`：跳过字面 "null" 元素（影响洞察元数据的正确性）
- `validateSummary`：`cardContent` 为字面 "null" 直接判定不合格 → 触发低 temperature 重新生成，而不是展示

**注意**：缓存中已存在的坏数据无需清理 —— 从 DB 读取渲染时同样走 renderContent，sanitize 兜底。

### 3. 验证
- `.\gradlew.bat :app:assembleDebug` → BUILD SUCCESSFUL

### 关键文件
- `app/src/main/res/values/strings.xml`
- `app/src/main/java/com/example/lifetrack/TrackFragment.java`