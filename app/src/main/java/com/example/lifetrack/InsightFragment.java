package com.example.lifetrack;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class InsightFragment extends Fragment {

    private static final String TAG = "InsightFragment";
    private static final int COMPRESS_THRESHOLD = 50000;
    private static final int TARGET_LENGTH = 40000;
    private static final String PREFS_INSIGHT = "lifetrack_insight";
    private static final String KEY_CONVERSATION = "conversation_history";
    private static final int MAX_PERSISTED_TURNS = 5;
    // savedInstanceState 中保存流式回复位置的 key
    private static final String KEY_AI_RESPONSE_POSITION = "ai_response_position";

    private RecyclerView rvMessages;
    private EditText etQuestion;
    private ImageView btnSend;
    private MessageAdapter adapter;
    private final List<Message> messages = new ArrayList<>();
    private boolean isLoading = false;
    private int aiResponsePosition = -1;
    private HorizontalScrollView suggestionScroll;
    private LinearLayout suggestionContainer;
    private View typingIndicatorActive; // 当前显示打字指示器的 View

    // 多轮对话历史（只存用户问题和AI回答，不含状态消息）
    private final List<DeepSeekClient.ConversationTurn> conversationHistory = new ArrayList<>();

    // Fragment 销毁标志，用于在异步回调中提前退出，避免销毁后操作 UI
    private volatile boolean isDestroyed = false;

    private InsightDao insightDao;

    static class Message {
        final String text;
        final boolean isUser;
        final boolean isStatus;
        final List<String> followUpSuggestions; // 追问建议，仅AI消息有

        Message(String text, boolean isUser) {
            this(text, isUser, false, null);
        }

        Message(String text, boolean isUser, List<String> followUpSuggestions) {
            this(text, isUser, false, followUpSuggestions);
        }

        Message(String text, boolean isUser, boolean isStatus, List<String> followUpSuggestions) {
            this.text = text;
            this.isUser = isUser;
            this.isStatus = isStatus;
            this.followUpSuggestions = followUpSuggestions;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_insight, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Fragment 复用场景下 isDestroyed 可能仍为 true（onDestroyView 已置 true），
        // 这里重置保证后续异步回调能正常工作
        isDestroyed = false;

        // 恢复流式回复位置（注意：Fragment 重建后 DeepSeekClient 回调因 isDestroyed 已重置无法触发，
        // 这里仅恢复状态值，isLoading 保持 false，下一次发送会重置 aiResponsePosition）
        if (savedInstanceState != null) {
            aiResponsePosition = savedInstanceState.getInt(KEY_AI_RESPONSE_POSITION, -1);
        }

        rvMessages = view.findViewById(R.id.rvMessages);
        etQuestion = view.findViewById(R.id.etQuestion);
        btnSend = view.findViewById(R.id.btnSend);
        suggestionScroll = view.findViewById(R.id.suggestionScroll);
        suggestionContainer = view.findViewById(R.id.suggestionContainer);

        adapter = new MessageAdapter(messages);
        adapter.setFragment(this);
        LinearLayoutManager layoutManager = new LinearLayoutManager(requireContext());
        layoutManager.setStackFromEnd(true);
        rvMessages.setLayoutManager(layoutManager);
        rvMessages.setAdapter(adapter);
        // 完全禁用 ItemAnimator：避免流式输出时
        // 1) notifyItemInserted 触发的淡入+上移动画造成新气泡"冒出来"抖动；
        // 2) notifyItemChanged 时即使 setSupportsChangeAnimations(false) 仍可能触发 measure 抖动。
        // 用户明确要求流式输出过程不要有任何动画干扰。
        rvMessages.setItemAnimator(null);

        // 使用 ApplicationContext 避免持有 Activity Context 导致内存泄漏
        insightDao = new InsightDao(requireContext().getApplicationContext());

        addWelcomeMessage();
        restoreConversationHistory();
        setupSuggestions();

        if (btnSend != null) {
            btnSend.setOnClickListener(v -> sendQuestion());
        }
    }

    private void restoreConversationHistory() {
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_INSIGHT, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_CONVERSATION, null);
        if (json == null || json.isEmpty()) return;

        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String user = obj.optString("user", "");
                String assistant = obj.optString("assistant", "");
                if (!user.isEmpty() && !assistant.isEmpty()) {
                    conversationHistory.add(new DeepSeekClient.ConversationTurn(user, assistant));
                    messages.add(new Message(user, true));
                    messages.add(new Message(assistant, false));
                }
            }
            if (!messages.isEmpty()) {
                adapter.notifyItemRangeInserted(1, messages.size() - 1);
                scrollToBottom();
            }
        } catch (Exception e) {
            Log.e(TAG, "restoreConversationHistory failed", e);
        }
    }

    private void persistConversationHistory() {
        try {
            android.content.Context ctx = getContext();
            if (ctx == null) return;
            JSONArray arr = new JSONArray();
            for (DeepSeekClient.ConversationTurn turn : conversationHistory) {
                JSONObject obj = new JSONObject();
                obj.put("user", turn.question);
                obj.put("assistant", turn.answer);
                arr.put(obj);
            }
            ctx.getSharedPreferences(PREFS_INSIGHT, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CONVERSATION, arr.toString())
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "persistConversationHistory failed", e);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        // 保存流式回复位置，配置变化（如旋转）后可恢复列表定位
        outState.putInt(KEY_AI_RESPONSE_POSITION, aiResponsePosition);
    }

    @Override
    public void onDestroyView() {
        isDestroyed = true;
        if (adapter != null) {
            adapter.setFragment(null);
        }
        // 清理 View 引用，避免异步回调持有 Fragment→View→Activity 引用链导致 Activity 无法 GC
        rvMessages = null;
        etQuestion = null;
        btnSend = null;
        suggestionScroll = null;
        suggestionContainer = null;
        adapter = null;
        super.onDestroyView();
    }

    /**
     * 安全地在主线程执行 UI 更新。
     * 检查 Fragment 是否仍附加且未销毁、Activity 未 finishing，避免 requireActivity() 抛异常。
     */
    private void runOnUiIfAlive(Runnable action) {
        if (isDestroyed || !isAdded()) return;
        android.app.Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) return;
        activity.runOnUiThread(action);
    }

    private void addWelcomeMessage() {
        messages.add(new Message(getString(R.string.insight_welcome), false));
        adapter.notifyItemInserted(messages.size() - 1);
    }

    /**
     * 默认推荐问题，从 R.array.insight_default_suggestions 读取，便于国际化与维护
     */
    private String[] getDefaultSuggestions() {
        return getResources().getStringArray(R.array.insight_default_suggestions);
    }

    private void setupSuggestions() {
        if (suggestionContainer == null) return;
        suggestionContainer.removeAllViews();
        Context ctx = suggestionContainer.getContext();

        int padH = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12, ctx.getResources().getDisplayMetrics());
        int padV = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6, ctx.getResources().getDisplayMetrics());
        int marginR = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, ctx.getResources().getDisplayMetrics());

        for (String suggestion : getDefaultSuggestions()) {
            TextView chip = new TextView(ctx);
            chip.setText(suggestion);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            chip.setTextColor(ContextCompat.getColor(ctx, R.color.text_dropdown));
            chip.setPadding(padH, padV, padH, padV);
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setBackgroundResource(R.drawable.bg_suggestion_chip);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.rightMargin = marginR;
            chip.setLayoutParams(params);

            chip.setOnClickListener(v -> {
                if (etQuestion != null && !isLoading) {
                    etQuestion.setText(suggestion);
                    sendQuestion();
                }
            });

            suggestionContainer.addView(chip);
        }

        // 有推荐问题且对话为空时显示
        if (suggestionScroll != null) {
            suggestionScroll.setVisibility(messages.size() <= 1 ? View.VISIBLE : View.GONE);
        }
    }

    private void updateSuggestionVisibility() {
        if (suggestionScroll != null) {
            suggestionScroll.setVisibility(messages.size() <= 1 ? View.VISIBLE : View.GONE);
        }
    }

    private void sendQuestion() {
        if (isLoading) return;
        if (etQuestion == null || btnSend == null) return;

        String question = etQuestion.getText().toString().trim();
        if (question.isEmpty()) return;

        etQuestion.setText("");

        // 隐藏推荐问题
        updateSuggestionVisibility();

        messages.add(new Message(question, true));
        adapter.notifyItemInserted(messages.size() - 1);
        scrollToBottom();

        isLoading = true;
        btnSend.setAlpha(0.4f);
        btnSend.setClickable(false);

        addStatusMessage(getString(R.string.insight_analyzing));

        DeepSeekClient.analyzeTimeRange(question, new DeepSeekClient.InsightTimeRangeCallback() {
            @Override
            public void onResult(String startDate, String endDate) {
                if (isDestroyed || !isAdded()) return;
                runOnUiIfAlive(() -> {
                    if (isDestroyed || !isAdded()) return;
                    updateLastStatusMessage(getString(R.string.insight_searching) + "（" + startDate + " ~ " + endDate + "）");

                    // 在UI线程拷贝对话历史，避免并发修改
                    final List<DeepSeekClient.ConversationTurn> historyCopy = new ArrayList<>(conversationHistory);
                    android.content.Context ctx = getContext();
                    if (ctx == null) return;
                    final android.content.Context appCtx = ctx.getApplicationContext();
                    AppExecutors.io().execute(() -> {
                        if (isDestroyed) return;
                        DiaryDao diaryDao = new DiaryDao(appCtx);
                        List<String[]> entries = diaryDao.queryByDateRange(startDate, endDate);

                        if (entries.isEmpty()) {
                            if (!isDestroyed && isAdded()) {
                                runOnUiIfAlive(() -> {
                                    if (isDestroyed || !isAdded()) return;
                                    updateLastStatusMessage(getString(R.string.insight_no_diary));
                                    isLoading = false;
                                    if (btnSend != null) {
                                        btnSend.setAlpha(1f);
                                        btnSend.setClickable(true);
                                    }
                                });
                            }
                            return;
                        }

                        // 日记较多时先筛选与问题相关的篇目，减少无关内容稀释焦点
                        List<String[]> relevantEntries = entries;
                        if (entries.size() > 10) {
                            if (!isDestroyed && isAdded()) {
                                runOnUiIfAlive(() -> {
                                    if (isDestroyed || !isAdded()) return;
                                    updateLastStatusMessage(getString(R.string.insight_status_filtering));
                                });
                            }
                            List<Integer> indices = filterRelevantDiariesSync(question, entries);
                            if (indices != null && !indices.isEmpty()) {
                                relevantEntries = new ArrayList<>();
                                for (int idx : indices) {
                                    if (idx >= 0 && idx < entries.size()) {
                                        relevantEntries.add(entries.get(idx));
                                    }
                                }
                            }
                        }

                        StringBuilder diaryText = new StringBuilder();
                        for (String[] entry : relevantEntries) {
                            if (entry == null || entry.length < 3) continue;
                            diaryText.append("【").append(entry[1]).append("】\n").append(entry[2]).append("\n\n");
                        }

                        String diaryContent = diaryText.toString();

                        if (diaryContent.length() > COMPRESS_THRESHOLD) {
                            if (!isDestroyed && isAdded()) {
                                runOnUiIfAlive(() -> {
                                    if (isDestroyed || !isAdded()) return;
                                    updateLastStatusMessage(getString(R.string.insight_status_compressing));
                                });
                            }
                            diaryContent = compressDiary(diaryContent);
                            if (diaryContent.length() > TARGET_LENGTH) {
                                diaryContent = sampleDiaryEvenly(diaryContent, TARGET_LENGTH);
                            }
                        }

                        // 查询该时间范围内的洞察上下文
                        String insightContext = buildInsightContextForRange(startDate, endDate);

                        if (!isDestroyed && isAdded()) {
                            runOnUiIfAlive(() -> {
                                if (isDestroyed || !isAdded()) return;
                                updateLastStatusMessage(getString(R.string.insight_thinking));
                            });
                        }

                        String finalDiaryContent = diaryContent;
                        final int finalEntryCount = relevantEntries.size();

                        DeepSeekClient.answerWithDiaryContext(question, finalDiaryContent, startDate, endDate, finalEntryCount,
                                historyCopy, insightContext,
                                new DeepSeekClient.InsightAnswerCallback() {
                                    @Override
                                    public void onPartialResult(String content, boolean isComplete) {
                                        if (isDestroyed || !isAdded()) return;
                                        runOnUiIfAlive(() -> {
                                            if (isDestroyed || !isAdded()) return;
                                            if (aiResponsePosition == -1) {
                                                removeLastStatusMessage();
                                                messages.add(new Message(content, false));
                                                aiResponsePosition = messages.size() - 1;
                                                adapter.notifyItemInserted(aiResponsePosition);
                                            } else {
                                                messages.set(aiResponsePosition, new Message(content, false));
                                                // 使用 payload 仅刷新文本，避免重设背景造成视觉变化
                                                adapter.notifyItemChanged(aiResponsePosition, MessageAdapter.STREAM_PAYLOAD);
                                            }
                                            // 流式输出时使用瞬时滚动，避免 smoothScroll 动画
                                            // 与高频文字增长叠加造成气泡/头像视觉抖动
                                            scrollToBottom(false);

                                            if (isComplete) {
                                                // 保存到对话历史
                                                conversationHistory.add(new DeepSeekClient.ConversationTurn(question, content));
                                                // 限制为最近 5 轮（每轮一个 ConversationTurn，含 user+assistant）
                                                while (conversationHistory.size() > MAX_PERSISTED_TURNS) {
                                                    conversationHistory.remove(0);
                                                }
                                                persistConversationHistory();
                                                // 先重置 aiResponsePosition，使后续完整重绑时
                                                // onBindViewHolder 走 else 分支关闭 typingIndicator 并停止动画
                                                final int completedPos = aiResponsePosition;
                                                aiResponsePosition = -1;
                                                // 触发完整重绑（不带 payload）：
                                                // STREAM_PAYLOAD 路径只刷新文本，不会关闭 typingIndicator，
                                                // 完成时必须完整重绑才能把打字指示器置 GONE，避免回答下方残留占位
                                                if (completedPos >= 0 && completedPos < messages.size() && adapter != null) {
                                                    adapter.notifyItemChanged(completedPos);
                                                }
                                                // 生成追问建议
                                                generateAndShowFollowUps(question, content);
                                                isLoading = false;
                                                if (btnSend != null) {
                                                    btnSend.setAlpha(1f);
                                                    btnSend.setClickable(true);
                                                }
                                            }
                                        });
                                    }

                                    @Override
                                    public void onFailure(String message) {
                                        if (isDestroyed || !isAdded()) return;
                                        runOnUiIfAlive(() -> {
                                            if (isDestroyed || !isAdded()) return;
                                            if (aiResponsePosition != -1) {
                                                // AI 已返回部分内容，保留已有回复
                                                // 先关闭原 AI 消息的 typingIndicator（完整重绑），
                                                // 再追加错误状态消息，避免打字指示器残留
                                                int completedPos = aiResponsePosition;
                                                aiResponsePosition = -1;
                                                if (completedPos >= 0 && completedPos < messages.size() && adapter != null) {
                                                    adapter.notifyItemChanged(completedPos);
                                                }
                                                messages.add(new Message("⏳ " + getString(R.string.insight_error, message), false, true, null));
                                                adapter.notifyItemInserted(messages.size() - 1);
                                                scrollToBottom();
                                            } else {
                                                updateLastStatusMessage(getString(R.string.insight_error, message));
                                            }
                                            aiResponsePosition = -1;
                                            isLoading = false;
                                            if (btnSend != null) {
                                                btnSend.setAlpha(1f);
                                                btnSend.setClickable(true);
                                            }
                                        });
                                    }
                                });
                    });
                });
            }

            @Override
            public void onFailure(String message) {
                if (isDestroyed || !isAdded()) return;
                runOnUiIfAlive(() -> {
                    if (isDestroyed || !isAdded()) return;
                    updateLastStatusMessage(getString(R.string.insight_error, message));
                    isLoading = false;
                    if (btnSend != null) {
                        btnSend.setAlpha(1f);
                        btnSend.setClickable(true);
                    }
                });
            }
        });
    }

    /**
     * 查询时间范围内的洞察标签，构建上下文文本
     */
    private String buildInsightContextForRange(String startDate, String endDate) {
        try {
            StringBuilder sb = new StringBuilder();
            String[] periodTypes = {"daily", "weekly", "monthly"};
            for (String periodType : periodTypes) {
                List<InsightDao.InsightData> insights = insightDao.getInsightsByDateRange(periodType, startDate, endDate, 3);
                if (!insights.isEmpty()) {
                    sb.append("以下是").append(periodType).append("类型在该时间范围内的洞察标签：\n");
                    for (int i = 0; i < insights.size(); i++) {
                        InsightDao.InsightData d = insights.get(i);
                        sb.append("洞察").append(i + 1).append("：");
                        if (!d.keyPersons.isEmpty()) sb.append("关键人物=").append(d.keyPersons).append("；");
                        if (!d.keyProjects.isEmpty()) sb.append("关键项目=").append(d.keyProjects).append("；");
                        if (!d.keyEvents.isEmpty()) sb.append("关键事件=").append(d.keyEvents).append("；");
                        if (!d.emotionThemes.isEmpty()) sb.append("情绪主题=").append(d.emotionThemes).append("；");
                        if (!d.decisions.isEmpty()) sb.append("决策节点=").append(d.decisions).append("；");
                        sb.append("\n");
                    }
                }
            }
            String result = sb.toString().trim();
            if (!result.isEmpty()) {
                return "以下是该用户的历史洞察数据，供你参考分析。\n" +
                        "请在分析中明确对比当前观察与历史洞察的变化方向（增强/减弱/新增/消失），不要简单复述历史数据。\n" +
                        result;
            }
        } catch (Exception e) {
            Log.w(TAG, "buildInsightContextForRange failed", e);
        }
        return null;
    }

    /**
     * 同步调用日记筛选，最多等待30秒。失败时返回null（调用方用全部日记兜底）。
     */
    private List<Integer> filterRelevantDiariesSync(String question, List<String[]> entries) {
        final List<Integer> result = new ArrayList<>();
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        DeepSeekClient.filterRelevantDiaries(question, entries, new DeepSeekClient.DiaryFilterCallback() {
            @Override
            public void onResult(List<Integer> relevantIndices) {
                synchronized (result) {
                    result.addAll(relevantIndices);
                }
                latch.countDown();
            }

            @Override
            public void onFailure(String message) {
                Log.w(TAG, "filterRelevantDiariesSync failed: " + message);
                latch.countDown();
            }
        });

        try {
            // 超时返回 false，调用方用 null 兜底（全部日记参与回答）
            if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                Log.w(TAG, "filterRelevantDiariesSync await timeout, fallback to null");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 中断检查：Fragment 已销毁或线程被取消时尽早返回，避免在已失效的上下文中继续
        if (Thread.currentThread().isInterrupted()) {
            return null;
        }

        synchronized (result) {
            return result.isEmpty() ? null : new ArrayList<>(result);
        }
    }

    /**
     * 生成追问建议并更新消息
     */
    private void generateAndShowFollowUps(String question, String answer) {
        DeepSeekClient.generateFollowUpSuggestions(question, answer, new DeepSeekClient.InsightFollowUpCallback() {
            @Override
            public void onResult(List<String> suggestions) {
                if (isDestroyed || !isAdded() || suggestions == null || suggestions.isEmpty()) return;
                android.app.Activity activity = getActivity();
                if (activity == null || activity.isFinishing()) return;
                activity.runOnUiThread(() -> {
                    if (isDestroyed || !isAdded()) return;
                    // aiResponsePosition此时已重置为-1，需要反向查找AI消息
                    for (int i = messages.size() - 1; i >= 0; i--) {
                        if (!messages.get(i).isUser && !messages.get(i).isStatus) {
                            messages.set(i, new Message(messages.get(i).text, false, suggestions));
                            // 使用 payload 仅刷新追问区域，避免重设背景造成视觉变化
                            adapter.notifyItemChanged(i, MessageAdapter.FOLLOWUP_PAYLOAD);
                            break;
                        }
                    }
                });
            }

            @Override
            public void onFailure(String message) {
                Log.w(TAG, "generateFollowUpSuggestions failed: " + message);
            }
        });
    }

    /**
     * 用户点击追问建议
     */
    private void onFollowUpClick(String suggestion) {
        if (isLoading) return;
        if (etQuestion == null) return;
        etQuestion.setText(suggestion);
        sendQuestion();
    }

    private void addStatusMessage(String status) {
        messages.add(new Message("⏳ " + status, false, true, null));
        adapter.notifyItemInserted(messages.size() - 1);
        scrollToBottom();
    }

    private void updateLastStatusMessage(String newStatus) {
        int lastPos = messages.size() - 1;
        if (lastPos >= 0) {
            messages.set(lastPos, new Message("⏳ " + newStatus, false, true, null));
            adapter.notifyItemChanged(lastPos);
            scrollToBottom();
        }
    }

    private void removeLastStatusMessage() {
        int lastPos = messages.size() - 1;
        if (lastPos >= 0 && messages.get(lastPos).isStatus) {
            messages.remove(lastPos);
            adapter.notifyItemRemoved(lastPos);
        }
    }

    private String compressDiary(String diaryContent) {
        LinkedHashMap<String, StringBuilder> monthChunks = new LinkedHashMap<>();
        StringBuilder currentChunk = new StringBuilder();
        String currentMonth = null;

        for (String line : diaryContent.split("\n")) {
            if (line.startsWith("【") && line.contains("】")) {
                String dateStr = line.substring(1, line.indexOf("】"));
                String month = dateStr.length() >= 7 ? dateStr.substring(0, 7) : dateStr;
                if (!month.equals(currentMonth)) {
                    if (currentChunk.length() > 0 && currentMonth != null) {
                        monthChunks.put(currentMonth, currentChunk);
                    }
                    currentChunk = new StringBuilder();
                    currentMonth = month;
                }
            }
            currentChunk.append(line).append("\n");
        }
        if (currentChunk.length() > 0 && currentMonth != null) {
            monthChunks.put(currentMonth, currentChunk);
        }

        if (monthChunks.isEmpty()) {
            int chunkSize = 8000;
            int chunkCount = (diaryContent.length() + chunkSize - 1) / chunkSize;
            for (int i = 0; i < chunkCount; i++) {
                int start = i * chunkSize;
                int end = Math.min(start + chunkSize, diaryContent.length());
                monthChunks.put("chunk_" + i, new StringBuilder(diaryContent.substring(start, end)));
            }
        }

        String[] compressedResults = new String[monthChunks.size()];
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(monthChunks.size());

        int index = 0;
        for (Map.Entry<String, StringBuilder> entry : monthChunks.entrySet()) {
            final int idx = index++;
            String chunk = entry.getValue().toString();

            DeepSeekClient.compressDiaryChunk(chunk, new DeepSeekClient.CompressCallback() {
                @Override
                public void onResult(String compressed) {
                    compressedResults[idx] = compressed;
                    successCount.incrementAndGet();
                    latch.countDown();
                }

                @Override
                public void onFailure(String message) {
                    compressedResults[idx] = chunk;
                    failCount.incrementAndGet();
                    latch.countDown();
                }
            });
        }

        try {
            // 超时返回 false。此时部分 chunk 可能未完成，下方 successCount==0 时会兜底均匀采样
            if (!latch.await(120, java.util.concurrent.TimeUnit.SECONDS)) {
                Log.w(TAG, "compressDiary await timeout, success=" + successCount.get()
                        + " fail=" + failCount.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return sampleDiaryEvenly(diaryContent, TARGET_LENGTH);
        }

        // 中断检查：避免在已取消的上下文中继续拼接结果
        if (Thread.currentThread().isInterrupted()) {
            return sampleDiaryEvenly(diaryContent, TARGET_LENGTH);
        }

        if (successCount.get() == 0) {
            return sampleDiaryEvenly(diaryContent, TARGET_LENGTH);
        }

        StringBuilder result = new StringBuilder();
        for (String s : compressedResults) {
            if (s != null) {
                result.append(s).append("\n\n");
            }
        }

        return result.toString();
    }

    /**
     * 均匀采样日记内容，保留时间线上的代表性条目，而非只截取末尾。
     * 按【日期】分割条目，每隔N篇取1篇，确保覆盖整个时间范围。
     */
    private String sampleDiaryEvenly(String diaryContent, int targetLength) {
        if (diaryContent.length() <= targetLength) return diaryContent;

        List<String> entries = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : diaryContent.split("\n", -1)) {
            if (line.startsWith("【") && line.contains("】") && current.length() > 0) {
                entries.add(current.toString());
                current = new StringBuilder();
            }
            current.append(line).append("\n");
        }
        if (current.length() > 0) entries.add(current.toString());

        if (entries.isEmpty()) return diaryContent.substring(0, targetLength);

        double ratio = (double) targetLength / diaryContent.length();
        int step = Math.max(1, (int) Math.round(1.0 / ratio));

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < entries.size(); i += step) {
            result.append(entries.get(i));
        }

        if (result.length() > targetLength) {
            result = new StringBuilder(result.substring(result.length() - targetLength));
        }

        return result.toString();
    }

    private void scrollToBottom() {
        scrollToBottom(true);
    }

    /**
     * 滚动到底部。
     *
     * @param smooth true 使用 smoothScrollToPosition（适合用户主动操作：发送消息、AI 完成）；
     *               false 使用瞬时 scrollToPosition（适合流式输出场景，避免平滑滚动动画
     *               与高频文字增长叠加造成气泡/头像视觉抖动）。
     */
    private void scrollToBottom(boolean smooth) {
        if (adapter == null || rvMessages == null) return;
        rvMessages.post(() -> {
            if (adapter == null || rvMessages == null) return;
            if (adapter.getItemCount() > 0) {
                if (smooth) {
                    rvMessages.smoothScrollToPosition(adapter.getItemCount() - 1);
                } else {
                    rvMessages.scrollToPosition(adapter.getItemCount() - 1);
                }
            }
        });
    }

    static class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.ViewHolder> {

        private final List<Message> messageList;
        private InsightFragment fragment;

        // 流式更新 payload：仅刷新文本，避免重设背景等造成的视觉变化
        static final String STREAM_PAYLOAD = "stream_update";
        // 追问建议更新 payload：仅刷新 followUpArea
        static final String FOLLOWUP_PAYLOAD = "followup_update";

        static class ViewHolder extends RecyclerView.ViewHolder {
            LinearLayout msgRootLayout;
            LinearLayout msgBubbleArea;
            TextView tvMessage;
            FrameLayout avatarAi;
            View avatarSpacer;
            FrameLayout avatarUser;
            View rightSpacer;
            LinearLayout followUpArea;
            View typingIndicator;
            View typingDot1, typingDot2, typingDot3;

            ViewHolder(View itemView) {
                super(itemView);
                msgRootLayout = itemView.findViewById(R.id.msgRootLayout);
                msgBubbleArea = itemView.findViewById(R.id.msgBubbleArea);
                tvMessage = itemView.findViewById(R.id.tvMessage);
                avatarAi = itemView.findViewById(R.id.avatarAi);
                avatarSpacer = itemView.findViewById(R.id.avatarSpacer);
                avatarUser = itemView.findViewById(R.id.avatarUser);
                rightSpacer = itemView.findViewById(R.id.rightSpacer);
                followUpArea = itemView.findViewById(R.id.followUpArea);
                typingIndicator = itemView.findViewById(R.id.typingIndicator);
                typingDot1 = itemView.findViewById(R.id.typingDot1);
                typingDot2 = itemView.findViewById(R.id.typingDot2);
                typingDot3 = itemView.findViewById(R.id.typingDot3);
            }
        }

        MessageAdapter(List<Message> messageList) {
            this.messageList = messageList;
        }

        void setFragment(InsightFragment fragment) {
            this.fragment = fragment;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_insight_message, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position, @NonNull List<Object> payloads) {
            if (payloads.contains(STREAM_PAYLOAD)) {
                // 流式更新：仅刷新文本，保留首次绑定时设置的背景/头像等
                Message msg = messageList.get(position);
                holder.tvMessage.setText(msg.text);
                return;
            }
            if (payloads.contains(FOLLOWUP_PAYLOAD)) {
                // 追问建议更新：仅刷新追问区域，保留首次绑定时设置的背景/头像等
                bindFollowUpArea(holder, position);
                return;
            }
            super.onBindViewHolder(holder, position, payloads);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Message msg = messageList.get(position);
            holder.tvMessage.setText(msg.text);

            // 清理追问区域
            holder.followUpArea.removeAllViews();
            holder.followUpArea.setVisibility(View.GONE);

            if (msg.isUser) {
                // 用户消息：右侧对齐，显示用户头像
                holder.msgBubbleArea.setGravity(android.view.Gravity.END);
                holder.tvMessage.setBackgroundResource(R.drawable.bg_insight_msg_user_updated);
                holder.tvMessage.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.white));
                holder.avatarAi.setVisibility(View.GONE);
                holder.avatarSpacer.setVisibility(View.GONE);
                holder.avatarUser.setVisibility(View.VISIBLE);
                holder.rightSpacer.setVisibility(View.GONE);
            } else {
                // AI 消息：左侧对齐，显示 AI 头像
                holder.msgBubbleArea.setGravity(android.view.Gravity.START);
                holder.tvMessage.setBackgroundResource(R.drawable.bg_insight_msg_ai_updated);
                holder.tvMessage.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.text_primary));
                holder.avatarAi.setVisibility(View.VISIBLE);
                holder.avatarSpacer.setVisibility(View.GONE);
                holder.avatarUser.setVisibility(View.GONE);
                holder.rightSpacer.setVisibility(View.GONE);
            }

            // 状态消息（无气泡背景，纯文本样式）
            if (msg.isStatus) {
                holder.tvMessage.setBackgroundResource(android.R.color.transparent);
                holder.tvMessage.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.text_subtitle));
                holder.avatarAi.setVisibility(View.GONE);
                holder.avatarUser.setVisibility(View.GONE);
                // 状态消息不需要头像
            }

            // 打字指示器（仅在最后一条 AI 消息且正在流式传输时显示）
            if (fragment != null && fragment.aiResponsePosition == position && !msg.isUser) {
                holder.typingIndicator.setVisibility(View.VISIBLE);
                startTypingAnimation(holder);
            } else {
                holder.typingIndicator.setVisibility(View.GONE);
                stopTypingAnimation(holder);
            }

            // 追问建议（逻辑抽取到 bindFollowUpArea，便于 FOLLOWUP_PAYLOAD 局部刷新复用）
            bindFollowUpArea(holder, position);
        }

        /**
         * 绑定追问建议区域。FOLLOWUP_PAYLOAD 局部刷新时复用本方法，
         * 仅刷新追问 chips，保留首次绑定时设置的气泡背景/头像等。
         */
        private void bindFollowUpArea(@NonNull ViewHolder holder, int position) {
            holder.followUpArea.removeAllViews();
            Message msg = messageList.get(position);
            if (!msg.isUser && !msg.isStatus && msg.followUpSuggestions != null
                    && !msg.followUpSuggestions.isEmpty() && fragment != null) {
                holder.followUpArea.setVisibility(View.VISIBLE);
                holder.followUpArea.setGravity(android.view.Gravity.START);

                Context ctx = holder.followUpArea.getContext();
                int padH = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12, ctx.getResources().getDisplayMetrics());
                int padV = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6, ctx.getResources().getDisplayMetrics());
                int marginR = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, ctx.getResources().getDisplayMetrics());

                for (String suggestion : msg.followUpSuggestions) {
                    TextView chip = new TextView(ctx);
                    chip.setText(suggestion);
                    chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    chip.setTextColor(ContextCompat.getColor(ctx, R.color.text_dropdown));
                    chip.setPadding(padH, padV, padH, padV);
                    chip.setClickable(true);
                    chip.setFocusable(true);
                    // 保证最小触摸目标 48dp，符合无障碍规范
                    chip.setMinimumHeight((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48,
                            ctx.getResources().getDisplayMetrics()));
                    chip.setBackgroundResource(R.drawable.bg_suggestion_chip);

                    LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );
                    chipParams.rightMargin = marginR;
                    chip.setLayoutParams(chipParams);

                    final String s = suggestion;
                    chip.setOnClickListener(v -> {
                        if (fragment != null) {
                            fragment.onFollowUpClick(s);
                        }
                    });

                    holder.followUpArea.addView(chip);
                }
            } else {
                holder.followUpArea.setVisibility(View.GONE);
            }
        }

        // 缓存 3 个 ScaleAnimation 实例复用，避免每次 onBindViewHolder 都重新创建（性能优化）。
        // 同时只有一个 ViewHolder 会显示打字指示器（fragment.aiResponsePosition 对应的位置），
        // 因此共享静态实例不会出现多个 View 同时使用同一 Animation 的冲突。
        private static final android.view.animation.ScaleAnimation TYPING_ANIM_1 = buildTypingAnim(0);
        private static final android.view.animation.ScaleAnimation TYPING_ANIM_2 = buildTypingAnim(150);
        private static final android.view.animation.ScaleAnimation TYPING_ANIM_3 = buildTypingAnim(300);

        private static android.view.animation.ScaleAnimation buildTypingAnim(long startOffset) {
            android.view.animation.ScaleAnimation anim = new android.view.animation.ScaleAnimation(
                    0.5f, 1f, 0.5f, 1f,
                    android.view.animation.ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
                    android.view.animation.ScaleAnimation.RELATIVE_TO_SELF, 0.5f
            );
            anim.setDuration(400);
            anim.setStartOffset(startOffset);
            anim.setRepeatCount(android.view.animation.ScaleAnimation.INFINITE);
            anim.setRepeatMode(android.view.animation.ScaleAnimation.REVERSE);
            return anim;
        }

        private void startTypingAnimation(ViewHolder holder) {
            if (holder.typingDot1 == null) return;
            holder.typingDot1.startAnimation(TYPING_ANIM_1);
            holder.typingDot2.startAnimation(TYPING_ANIM_2);
            holder.typingDot3.startAnimation(TYPING_ANIM_3);
        }

        private void stopTypingAnimation(ViewHolder holder) {
            if (holder.typingDot1 != null) holder.typingDot1.clearAnimation();
            if (holder.typingDot2 != null) holder.typingDot2.clearAnimation();
            if (holder.typingDot3 != null) holder.typingDot3.clearAnimation();
        }

        @Override
        public int getItemCount() {
            return messageList.size();
        }

        @Override
        public void onViewRecycled(@NonNull ViewHolder holder) {
            holder.followUpArea.removeAllViews();
            holder.followUpArea.setVisibility(View.GONE);
            stopTypingAnimation(holder);
        }
    }
}
