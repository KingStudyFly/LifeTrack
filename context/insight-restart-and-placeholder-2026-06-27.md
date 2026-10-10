# 洞察界面“重新开始回答”+“回答下方占位”修复 — 2026-06-27

## 用户反馈
1. “洞察”界面，有时回答到一半会突然开始从前面的节点重新开始回答。
2. 回答完之后它的回答下面莫名空出很大的占位符。

## 根因

### Bug 1：重新开始回答
`DeepSeekClient.answerWithDiaryContext` 两轮架构：
- 第一轮流式输出累积草稿 `draftAnswer`，UI 文本逐渐增长。
- L947 `callback.onPartialResult(draftAnswer, false)` 确保完整草稿上屏。
- 第二轮 `refineAnswer` 流式累积 `refinedContent`，原实现在长度达到 `updateThreshold`（`min(300, max(50, draftAnswer/2))`）后调用 `callback.onPartialResult(current, false)`。

问题：UI 上已显示完整草稿（可能几千字），被替换为 `refinedContent` 开头几百字，视觉上就是“完整回答突然变回开头，重新开始流式”。这就是“有时”发生的原因——只有 `refinedContent` 达到阈值才会触发。

### Bug 2：回答下方占位符
`InsightFragment.onPartialResult` 流式更新走 `STREAM_PAYLOAD` 路径，只刷文本，不触碰 `typingIndicator`。`typingIndicator` 在首次绑定时被设为 VISIBLE（`aiResponsePosition == position`），此后流式过程一直保持 VISIBLE。

`isComplete=true` 时 `aiResponsePosition` 被重置为 -1，但**没有触发完整重绑**，`onBindViewHolder` 未被再次调用，`typingIndicator`（12dp+12dp padding + 动画点）残留显示，并被追问建议区域之上撑出空白——即“回答下面空出很大的占位符”。

`onFailure`（AI 已返回部分内容时）路径存在同样的残留问题。

## 修复

### DeepSeekClient.refineAnswer（第二轮静默化）
- 移除 `updateThreshold` 与 `callback.onPartialResult(current, false)` 流式回调。
- `readSSEStream` 改为只累积 `refinedContent.append(chunk)`。
- 完成后由调用方 `answerWithDiaryContext` 一次性 `callback.onPartialResult(refinedAnswer, true)` 替换。
- 移除 `refineAnswer` 不再使用的 `callback` 参数及调用方实参。

效果：第二轮静默执行，UI 保持草稿不变 + typingIndicator 闪烁（合理的“AI 还在处理”状态）；完成后一次性替换为完善版，不再“重新开始流式”。

### InsightFragment.onPartialResult（关闭 typingIndicator）
- `isComplete=true` 分支：先保存 `completedPos = aiResponsePosition`，重置 `aiResponsePosition = -1`，再调用 `adapter.notifyItemChanged(completedPos)`（不带 payload）触发完整重绑。
- 完整重绑时 `onBindViewHolder` 看到 `fragment.aiResponsePosition == -1 != position`，走 else 分支将 `typingIndicator` 置 GONE 并 `stopTypingAnimation`。
- `onFailure` 路径同步修复：追加错误消息前先完整重绑原 AI 消息位置关闭 typingIndicator。

## 潜在 bug 检查
- 完整重绑会重设背景 drawable / 头像可见性，但值与流式期间相同，无视觉变化。
- `followUpArea` 在完整重绑时 `removeAllViews() + GONE`，此时 `followUpSuggestions` 为 null，无影响；之后 `generateAndShowFollowUps` 异步回调用 `FOLLOWUP_PAYLOAD` 局部刷新。
- `notifyItemChanged` 边界检查：`completedPos >= 0 && completedPos < messages.size() && adapter != null`。
- 第二轮静默期间 `isLoading` 仍为 true，`sendQuestion` 开头 `if (isLoading) return;` 阻止新请求，btnSend 不可点击。
- Fragment 销毁保护：`onPartialResult` / `onFailure` 开头 `if (isDestroyed || !isAdded()) return;` + `runOnUiIfAlive` 双重检查。

## 修改文件
- `app/src/main/java/com/example/lifetrack/DeepSeekClient.java`
  - L948：调用方移除 callback 实参
  - L968-975：refineAnswer javadoc 更新 + 移除 callback 参数
  - L1012-1025：移除 updateThreshold，readSSEStream 改为只累积
- `app/src/main/java/com/example/lifetrack/InsightFragment.java`
  - L413-438：isComplete 分支增加 completedPos 完整重绑
  - L442-469：onFailure 分支增加 completedPos 完整重绑
