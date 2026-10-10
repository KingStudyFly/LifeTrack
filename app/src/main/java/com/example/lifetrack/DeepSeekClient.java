package com.example.lifetrack;

import android.util.Log;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class DeepSeekClient {

    private static final String TAG = "DeepSeekClient";
    private static final String BASE_URL = "https://api.deepseek.com";
    private static final String MODEL = "deepseek-flash";

    private static final String SYSTEM_PREFIX = "你是LifeTrack应用的AI助手，服务于用户的日记与生活记录场景。\n" +
            "角色设定：你是用户的私人生活分析助手，风格温和、有洞察力，像一位了解用户但保持理性的朋友。\n" +
            "你不是心理咨询师，不提供专业心理诊断；你不是决策者，只提供观察和建议，选择权归用户。\n" +
            "你擅长从碎片化的日常记录中发现模式、识别矛盾、建立因果联系，并用平实的语言表达。\n" +
            "你尊重用户的隐私和自主性，避免说教，避免过度解读单次事件。";

    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private static final LruCache<String, String> contentCache = new LruCache<>(50);

    // 日期解析正则预编译
    private static final Pattern DATE_PATTERN_1 = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    private static final Pattern DATE_PATTERN_2 = Pattern.compile("(\\d{4})年(\\d{1,2})月(\\d{1,2})[日号]");
    private static final Pattern DATE_PATTERN_3 = Pattern.compile("(\\d{1,2})/(\\d{1,2})/(\\d{4})");
    private static final Pattern DATE_PATTERN_4 = Pattern.compile("(\\d{4})\\.(\\d{1,2})\\.(\\d{1,2})");
    private static final Pattern DATE_NO_YEAR_PATTERN_1 = Pattern.compile("(\\d{1,2})月(\\d{1,2})[日号]");
    private static final Pattern DATE_NO_YEAR_PATTERN_2 = Pattern.compile("(\\d{1,2})-(\\d{1,2})");
    private static final Pattern DATE_NO_YEAR_PATTERN_3 = Pattern.compile("(\\d{1,2})/(\\d{1,2})");

    // 缓存版本号，prompt 变更时递增以失效旧缓存
    private static final int CACHE_VERSION = 1;

    @SuppressWarnings("unused")
    public static String getApiKey() {
        return KeyManager.getDeepSeekKey();
    }

    public static void clearCache() {
        contentCache.evictAll();
    }

    public interface VerifyCallback {
        void onSuccess();
        void onFailure(String message);
    }

    public interface DateAnalyzeCallback {
        void onResult(String date, String cleanedContent);
        void onFailure(String message);
    }

    public interface DateAnalyzeStreamCallback {
        void onPartialResult(String date, String cleanedContent, boolean isComplete);
        void onFailure(String message);
    }

    public interface MultiDateAnalyzeCallback {
        void onResults(List<DateEntry> entries);
        void onFailure(String message);
    }

    public interface MultiDateAnalyzeStreamCallback {
        void onPartialResults(List<DateEntry> entries, boolean isComplete);
        void onFailure(String message);
    }

    public interface SummaryCallback {
        void onResult(String title, String content);
        void onFailure(String message);
    }

    public interface SummaryStreamCallback {
        void onPartialResult(String title, String content, boolean isComplete);
        void onFailure(String message);
    }

    public interface SummaryWithInsightStreamCallback {
        void onPartialResult(String title, String content, boolean isComplete);
        void onComplete(String title, String content, String insightJson);
        void onFailure(String message);
    }

    public static class DateEntry {
        public final String date;
        public final String content;
        public final DimensionData dimensions;
        public DateEntry(String date, String content) {
            this(date, content, null);
        }
        public DateEntry(String date, String content, DimensionData dimensions) {
            this.date = date;
            this.content = content;
            this.dimensions = dimensions;
        }
    }

    /**
     * 维度分析结果：运动/睡眠/社交三维度。
     * 各 score 为 null 表示当天日记未提及该维度。
     */
    public static class DimensionData {
        public final Integer exerciseScore;
        public final List<String> exerciseTypes;
        public final String exerciseExcerpt;
        public final Integer sleepScore;
        public final String sleepExcerpt;
        public final List<String> socialContacts;
        public final String socialExcerpt;

        public DimensionData(Integer exerciseScore, List<String> exerciseTypes, String exerciseExcerpt,
                            Integer sleepScore, String sleepExcerpt,
                            List<String> socialContacts, String socialExcerpt) {
            this.exerciseScore = exerciseScore;
            this.exerciseTypes = exerciseTypes;
            this.exerciseExcerpt = exerciseExcerpt;
            this.sleepScore = sleepScore;
            this.sleepExcerpt = sleepExcerpt;
            this.socialContacts = socialContacts;
            this.socialExcerpt = socialExcerpt;
        }
    }

    public static class SummaryEntry {
        public final String title;
        public final String content;
        public SummaryEntry(String title, String content) {
            this.title = title;
            this.content = content;
        }
    }

    public static class SummaryWithInsightEntry {
        public final String title;
        public final String content;
        public final String insightJson;
        public SummaryWithInsightEntry(String title, String content, String insightJson) {
            this.title = title;
            this.content = content;
            this.insightJson = insightJson;
        }
    }

    public static void verifyApiKey(VerifyCallback callback) {
        AppExecutors.network().execute(() -> {
            try {
                Request request = new Request.Builder()
                        .url(BASE_URL + "/models")
                        .get()
                        .header("Authorization", "Bearer " + getApiKey())
                        .build();

                OkHttpClient client = OkHttpHelper.getDefault();
                try (Response response = client.newCall(request).execute()) {
                    if (response.code() == 200) {
                        String body = AIHttpUtils.readBodyString(response);
                        JSONArray models = new JSONObject(body).getJSONArray("data");
                        Log.d(TAG, "verifyApiKey: success, models count=" + models.length());
                        if (callback != null) callback.onSuccess();
                    } else {
                        String errorMsg = readErrorMessage(response);
                        if (callback != null) callback.onFailure(errorMsg);
                    }
                }
            } catch (UnknownHostException e) {
                Log.e(TAG, "verifyApiKey: DNS resolution failed", e);
                if (callback != null) callback.onFailure("无法连接到服务器，请检查网络");
            } catch (java.net.SocketTimeoutException e) {
                Log.e(TAG, "verifyApiKey: timeout", e);
                if (callback != null) callback.onFailure("连接超时，请检查网络后重试");
            } catch (Exception e) {
                Log.e(TAG, "verifyApiKey: exception", e);
                if (callback != null) callback.onFailure("连接失败：" + e.getMessage());
            }
        });
    }

    public static void analyzeDiaryDate(String content, String currentDate, DateAnalyzeCallback callback) {
        String cacheKey = "v" + CACHE_VERSION + "_single_" + sha256(content);
        String cached = contentCache.get(cacheKey);
        if (cached != null) {
            try {
                JSONObject obj = new JSONObject(cached);
                String cachedDate = obj.optString("date", null);
                String cachedContent = obj.optString("content", null);
                if (cachedDate != null && cachedContent != null) {
                    callback.onResult(cachedDate, cachedContent);
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "parse single cache failed", e);
            }
        }

        analyzeDiaryDateStream(content, currentDate, new DateAnalyzeStreamCallback() {
            @Override
            public void onPartialResult(String date, String cleanedContent, boolean isComplete) {
                if (isComplete) {
                    try {
                        JSONObject cacheObj = new JSONObject();
                        cacheObj.put("date", date);
                        cacheObj.put("content", cleanedContent);
                        contentCache.put(cacheKey, cacheObj.toString());
                    } catch (Exception e) {
                        Log.w(TAG, "build single cache failed", e);
                    }
                    callback.onResult(date, cleanedContent);
                }
            }

            @Override
            public void onFailure(String message) {
                callback.onFailure(message);
            }
        });
    }

    public static void analyzeDiaryDateStream(String content, String currentDate, DateAnalyzeStreamCallback callback) {
        AppExecutors.network().execute(() -> {
            try {
                JSONObject systemMsg = new JSONObject();
                systemMsg.put("role", "system");
                systemMsg.put("content", SYSTEM_PREFIX + "当前任务：日记日期分析。根据日记内容和写入日期，判断事件实际发生的日期。" +
                        "规则：只有明确的年月日（如\"5月12日\"、\"3月1号\"）才能作为新日期的依据，相对时间词（如\"昨天\"、\"前天\"）不影响日期判定。" +
                        "如果明确日期是日记内容的一部分（如\"5月12号的考试\"），而非新日记的开头标记，则该日期不作为拆分依据。" +
                        "删除内容中用于指示日期的部分，返回清理后的纯内容。无日期指示词则原样返回。" +
                        "严格按JSON格式返回：{\"date\":\"yyyy-MM-dd\",\"content\":\"清理后的内容\"}");

                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", "写入日期：" + currentDate + "\n日记内容：" + content);

                JSONArray messages = new JSONArray();
                messages.put(systemMsg);
                messages.put(userMsg);

                JSONObject requestBody = new JSONObject();
                requestBody.put("model", MODEL);
                requestBody.put("messages", messages);
                requestBody.put("stream", true);
                requestBody.put("temperature", 0.1);
                requestBody.put("max_tokens", 256);

                Request request = buildSsePostRequest(BASE_URL + "/chat/completions", requestBody);

                OkHttpClient client = OkHttpHelper.getSse();
                try (Response response = client.newCall(request).execute()) {
                    if (response.code() == 200) {
                        StringBuilder fullContent = new StringBuilder();

                        readSSEStream(response, (chunk) -> {
                            fullContent.append(chunk);
                            String parsed = extractJson(fullContent.toString());
                            if (parsed != null) {
                                String[] result = parseDateResult(parsed, currentDate, content);
                                if (result != null) {
                                    callback.onPartialResult(result[0], result[1], false);
                                }
                            }
                        });

                        String parsed = extractJson(fullContent.toString());
                        String date = currentDate;
                        String cleanedContent = content;
                        if (parsed != null) {
                            String[] result = parseDateResult(parsed, currentDate, content);
                            if (result != null) {
                                date = result[0];
                                cleanedContent = result[1];
                            }
                        }
                        callback.onPartialResult(date, cleanedContent, true);
                    } else {
                        String errorMsg = readErrorMessage(response);
                        Log.w(TAG, "analyzeDiaryDateStream: failed, " + errorMsg);
                        callback.onFailure(errorMsg);
                    }
                }
            } catch (UnknownHostException e) {
                Log.e(TAG, "analyzeDiaryDateStream: DNS resolution failed", e);
                callback.onFailure("无法连接到服务器，请检查网络");
            } catch (java.net.SocketTimeoutException e) {
                Log.e(TAG, "analyzeDiaryDateStream: timeout", e);
                callback.onFailure("AI 分析超时，请检查网络后重试");
            } catch (Exception e) {
                Log.e(TAG, "analyzeDiaryDateStream: exception", e);
                callback.onFailure("AI 分析失败：" + e.getMessage());
            }
        });
    }

    public static void analyzeDiaryDates(String content, String currentDate, MultiDateAnalyzeCallback callback) {
        String cacheKey = "v" + CACHE_VERSION + "_multi_" + sha256(content);
        String cached = contentCache.get(cacheKey);
        if (cached != null) {
            try {
                List<DateEntry> entries = parseCachedEntries(cached, currentDate);
                if (!entries.isEmpty()) {
                    callback.onResults(entries);
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "parse cached entries failed", e);
            }
        }

        analyzeDiaryDatesStream(content, currentDate, new MultiDateAnalyzeStreamCallback() {
            @Override
            public void onPartialResults(List<DateEntry> entries, boolean isComplete) {
                if (isComplete) {
                    if (!entries.isEmpty()) {
                        String cacheValue = buildCacheValue(entries);
                        contentCache.put(cacheKey, cacheValue);
                    }
                    callback.onResults(entries);
                }
            }

            @Override
            public void onFailure(String message) {
                callback.onFailure(message);
            }
        });
    }

    public static void analyzeDiaryDatesStream(String content, String currentDate, MultiDateAnalyzeStreamCallback callback) {
        AppExecutors.network().execute(() -> {
            try {
                JSONObject systemMsg = new JSONObject();
                systemMsg.put("role", "system");
                systemMsg.put("content", SYSTEM_PREFIX + "当前任务：日记日期拆分。将可能包含多天的日记按日期拆分。" +
                        "规则：只有明确的年月日（如\"5月12日\"、\"3月1号\"）才能作为拆分依据，相对时间词（如\"昨天\"、\"前天\"）不拆分。" +
                        "判断明确日期是日记内容的一部分（如\"5月12号的考试\"）还是新日记的开头标记，只有后者才拆分到新日期。" +
                        "删除内容中用于指示日期的部分，返回清理后的纯内容。全文无明确日期则全部归入写入日期。" +
                        "严格按JSON格式返回：{\"entries\":[{\"date\":\"yyyy-MM-dd\",\"content\":\"该日期的日记内容\"}]}");

                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", "写入日期：" + currentDate + "\n日记内容：" + content);

                JSONArray messages = new JSONArray();
                messages.put(systemMsg);
                messages.put(userMsg);

                JSONObject requestBody = new JSONObject();
                requestBody.put("model", MODEL);
                requestBody.put("messages", messages);
                requestBody.put("stream", true);
                requestBody.put("temperature", 0.1);
                requestBody.put("max_tokens", 2048);

                Request request = buildSsePostRequest(BASE_URL + "/chat/completions", requestBody);

                OkHttpClient client = OkHttpHelper.getSse();
                try (Response response = client.newCall(request).execute()) {
                    if (response.code() == 200) {
                        StringBuilder fullContent = new StringBuilder();

                        readSSEStream(response, (chunk) -> {
                            fullContent.append(chunk);
                            List<DateEntry> entries = parseMultiDateResult(fullContent.toString(), currentDate, content);
                            if (!entries.isEmpty()) {
                                callback.onPartialResults(new ArrayList<>(entries), false);
                            }
                        });

                        List<DateEntry> finalEntries = parseMultiDateResult(fullContent.toString(), currentDate, content);
                        Log.d(TAG, "analyzeDiaryDatesStream: raw response = " + fullContent.toString());
                        Log.d(TAG, "analyzeDiaryDatesStream: parsed " + finalEntries.size() + " entries");
                        callback.onPartialResults(finalEntries, true);
                    } else {
                        String errorMsg = readErrorMessage(response);
                        Log.w(TAG, "analyzeDiaryDatesStream: failed, " + errorMsg);
                        callback.onFailure(errorMsg);
                    }
                }
            } catch (UnknownHostException e) {
                Log.e(TAG, "analyzeDiaryDatesStream: DNS resolution failed", e);
                callback.onFailure("无法连接到服务器，请检查网络");
            } catch (java.net.SocketTimeoutException e) {
                Log.e(TAG, "analyzeDiaryDatesStream: timeout", e);
                callback.onFailure("AI 分析超时，请检查网络后重试");
            } catch (Exception e) {
                Log.e(TAG, "analyzeDiaryDatesStream: exception", e);
                callback.onFailure("AI 分析失败：" + e.getMessage());
            }
        });
    }

    /**
     * 带维度分析的日记日期拆分（非流式）。
     * 在拆分日期的同时，分析每个日期的运动/睡眠/社交三维度。
     * historyContext 为过去7天打分摘要文本，用于校准打分标准，可传 null。
     * 缓存 key 用 _multi_dim_ 前缀，与无维度版本隔离。
     */
    public static void analyzeDiaryDatesWithDimensions(String content, String currentDate,
                                                       String historyContext, MultiDateAnalyzeCallback callback) {
        String cacheKey = "v" + CACHE_VERSION + "_multi_dim_" + sha256(content);
        String cached = contentCache.get(cacheKey);
        if (cached != null) {
            try {
                List<DateEntry> entries = parseCachedEntries(cached, currentDate);
                if (!entries.isEmpty()) {
                    callback.onResults(entries);
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "parse cached dim entries failed", e);
            }
        }

        analyzeDiaryDatesStreamWithDimensions(content, currentDate, historyContext, new MultiDateAnalyzeStreamCallback() {
            @Override
            public void onPartialResults(List<DateEntry> entries, boolean isComplete) {
                if (isComplete) {
                    if (!entries.isEmpty()) {
                        String cacheValue = buildCacheValue(entries);
                        contentCache.put(cacheKey, cacheValue);
                    }
                    callback.onResults(entries);
                }
            }

            @Override
            public void onFailure(String message) {
                callback.onFailure(message);
            }
        });
    }

    /**
     * 带维度分析的日记日期拆分（流式）。
     * prompt 合并了日期拆分 + 三维度分析，节省一次 AI 请求。
     */
    public static void analyzeDiaryDatesStreamWithDimensions(String content, String currentDate,
                                                              String historyContext, MultiDateAnalyzeStreamCallback callback) {
        AppExecutors.network().execute(() -> {
            try {
                String historySection = (historyContext != null && !historyContext.isEmpty())
                        ? historyContext
                        : "无历史记录。基准打分参考：运动（未运动=0，轻度拉伸/散步=20-40，中等强度训练=50-70，高强度训练=80-100）；睡眠（失眠/熬夜=0-30，睡得差=40-60，正常=60-80，睡得香=80-100）；社交（只记录重要人物，不记录陌生人）。";

                JSONObject systemMsg = new JSONObject();
                systemMsg.put("role", "system");
                systemMsg.put("content", SYSTEM_PREFIX + "当前任务：日记日期拆分 + 维度分析。" +
                        "将可能包含多天的日记按日期拆分，并对每个日期分析运动/睡眠/社交三个维度。\n\n" +
                        "日期拆分规则：只有明确的年月日（如\"5月12日\"、\"3月1号\"）才能作为拆分依据，相对时间词（如\"昨天\"、\"前天\"）不拆分。" +
                        "判断明确日期是日记内容的一部分（如\"5月12号的考试\"）还是新日记的开头标记，只有后者才拆分到新日期。" +
                        "删除内容中用于指示日期的部分，返回清理后的纯内容。全文无明确日期则全部归入写入日期。\n\n" +
                        "维度分析规则：\n" +
                        "- 运动：从日记内容分析运动强度（0-100），识别运动类型（如上肢、核心、跑步、骑行、球类等）。日记未提及运动则该维度返回null。\n" +
                        "- 睡眠：从日记内容分析睡眠质量（0-100，分数越高睡眠越好）。日记未提及睡眠则返回null。\n" +
                        "- 社交：提取日记中提到的社交对象（重要人物，如家人、朋友、同事的名字或称呼）。日记未提及社交则返回null。\n" +
                        "- 每个维度附带原文摘录excerpt（从日记中提取的关键句子，便于校准打分）。\n\n" +
                        "打分参考（过去7天该用户的打分记录，请保持标准统一）：\n" + historySection + "\n\n" +
                        "严格按JSON格式返回：\n" +
                        "{\"entries\":[{\"date\":\"yyyy-MM-dd\",\"content\":\"清理后的日记\",\"dimensions\":{\"exercise\":{\"score\":65,\"types\":[\"上肢\",\"核心\"],\"excerpt\":\"原文片段\"},\"sleep\":{\"score\":72,\"excerpt\":\"原文片段\"},\"social\":{\"contacts\":[\"妈妈\"],\"excerpt\":\"原文片段\"}}}]}\n" +
                        "未提及的维度返回null，不要编造数据。");

                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", "写入日期：" + currentDate + "\n日记内容：" + content);

                JSONArray messages = new JSONArray();
                messages.put(systemMsg);
                messages.put(userMsg);

                JSONObject requestBody = new JSONObject();
                requestBody.put("model", MODEL);
                requestBody.put("messages", messages);
                requestBody.put("stream", true);
                requestBody.put("temperature", 0.1);
                requestBody.put("max_tokens", 4096);

                Request request = buildSsePostRequest(BASE_URL + "/chat/completions", requestBody);

                OkHttpClient client = OkHttpHelper.getSse();
                try (Response response = client.newCall(request).execute()) {
                    if (response.code() == 200) {
                        StringBuilder fullContent = new StringBuilder();

                        readSSEStream(response, (chunk) -> {
                            fullContent.append(chunk);
                            List<DateEntry> entries = parseMultiDateResult(fullContent.toString(), currentDate, content);
                            if (!entries.isEmpty()) {
                                callback.onPartialResults(new ArrayList<>(entries), false);
                            }
                        });

                        List<DateEntry> finalEntries = parseMultiDateResult(fullContent.toString(), currentDate, content);
                        Log.d(TAG, "analyzeDiaryDatesStreamWithDimensions: raw response = " + fullContent.toString());
                        Log.d(TAG, "analyzeDiaryDatesStreamWithDimensions: parsed " + finalEntries.size() + " entries");
                        callback.onPartialResults(finalEntries, true);
                    } else {
                        String errorMsg = readErrorMessage(response);
                        Log.w(TAG, "analyzeDiaryDatesStreamWithDimensions: failed, " + errorMsg);
                        callback.onFailure(errorMsg);
                    }
                }
            } catch (UnknownHostException e) {
                Log.e(TAG, "analyzeDiaryDatesStreamWithDimensions: DNS resolution failed", e);
                callback.onFailure("无法连接到服务器，请检查网络");
            } catch (java.net.SocketTimeoutException e) {
                Log.e(TAG, "analyzeDiaryDatesStreamWithDimensions: timeout", e);
                callback.onFailure("AI 分析超时，请检查网络后重试");
            } catch (Exception e) {
                Log.e(TAG, "analyzeDiaryDatesStreamWithDimensions: exception", e);
                callback.onFailure("AI 分析失败：" + e.getMessage());
            }
        });
    }

    /**
     * 通用维度聚合分析：接受自定义 system prompt + 数据上下文，返回纯文本分析结果。
     * 用于运动进步分析、睡眠影响因素分析等按需调用场景。
     */
    public interface TextAnalysisCallback {
        void onResult(String content);
        void onFailure(String message);
    }

    public static void generateTextAnalysis(String systemPrompt, String userContent, TextAnalysisCallback callback) {
        AppExecutors.network().execute(() -> {
            try {
                JSONObject systemMsg = new JSONObject();
                systemMsg.put("role", "system");
                systemMsg.put("content", SYSTEM_PREFIX + systemPrompt);

                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", userContent);

                JSONArray messages = new JSONArray();
                messages.put(systemMsg);
                messages.put(userMsg);

                JSONObject requestBody = new JSONObject();
                requestBody.put("model", MODEL);
                requestBody.put("messages", messages);
                requestBody.put("stream", false);
                requestBody.put("temperature", 0.3);
                requestBody.put("max_tokens", 2048);
                // 轨迹模块：关闭默认开启的思考模式，直接输出结果
                requestBody.put("thinking", new JSONObject().put("type", "disabled"));

                Request request = new Request.Builder()
                        .url(BASE_URL + "/chat/completions")
                        .post(RequestBody.create(requestBody.toString(), JSON_TYPE))
                        .header("Authorization", "Bearer " + getApiKey())
                        .build();

                OkHttpClient client = OkHttpHelper.getDefault();
                try (Response response = client.newCall(request).execute()) {
                    if (response.code() == 200) {
                        String body = AIHttpUtils.readBodyString(response);
                        JSONObject json = new JSONObject(body);
                        JSONArray choices = json.getJSONArray("choices");
                        if (choices.length() > 0) {
                            String content = choices.getJSONObject(0)
                                    .getJSONObject("message")
                                    .getString("content");
                            callback.onResult(content.trim());
                        } else {
                            callback.onFailure("AI 返回为空");
                        }
                    } else {
                        String errorMsg = readErrorMessage(response);
                        Log.w(TAG, "generateTextAnalysis: failed, " + errorMsg);
                        callback.onFailure(errorMsg);
                    }
                }
            } catch (UnknownHostException e) {
                Log.e(TAG, "generateTextAnalysis: DNS resolution failed", e);
                callback.onFailure("无法连接到服务器，请检查网络");
            } catch (java.net.SocketTimeoutException e) {
                Log.e(TAG, "generateTextAnalysis: timeout", e);
                callback.onFailure("AI 分析超时，请检查网络后重试");
            } catch (Exception e) {
                Log.e(TAG, "generateTextAnalysis: exception", e);
                callback.onFailure("AI 分析失败：" + e.getMessage());
            }
        });
    }

    /**
     * 提取到的待办条目。time 为时间提示（如"明早""14:00"），无时间信息时为空字符串，
     * title 为事项名称，desc 为补充说明（可为空）。
     */
    public static class ScheduleItem {
        public final String time;
        public final String title;
        public final String desc;

        public ScheduleItem(String time, String title, String desc) {
            this.time = time == null ? "" : time.trim();
            this.title = title == null ? "" : title.trim();
            this.desc = desc == null ? "" : desc.trim();
        }
    }

    public interface ScheduleCallback {
        void onResult(List<ScheduleItem> items);
        void onFailure(String message);
    }

    /**
     * 从日记正文中提取待办事项。
     * 日记保存后由 RecordFragment 调用；提取结果仅用于补充待办列表，
     * 失败或空结果对日记保存流程无影响。
     */
    public static void extractSchedule(String content, ScheduleCallback callback) {
        AppExecutors.network().execute(() -> {
            try {
                JSONObject systemMsg = new JSONObject();
                systemMsg.put("role", "system");
                systemMsg.put("content", SYSTEM_PREFIX + "当前任务：从日记中提取待办事项。\n" +
                        "用户会给你一篇日记正文，请提取其中**尚未完成、后续还需要做**的事项。\n" +
                        "判定标准（务必严格执行）：\n" +
                        "1. 只有当日记明确表达了\"还没做\"\"打算做\"\"需要做\"\"答应了要做\"\"约定要做\"\"该做而未做\"等含义时，才提取为待办。\n" +
                        "2. 已经在日记中完成、做完、做完并总结过的事情，一律不要提取。例如\"今天把方案写完了\"\"上午去开了会\"\"已经交了房租\"\"和朋友吃了饭\"，这些都**不是**待办。\n" +
                        "3. 纯记录、感受、回忆、想法、情绪、评价，不含行动意图的，不提取。\n" +
                        "4. 拿不准时倾向不提取。待办宁可少提取，也不要把已完成的往事变成待办。\n" +
                        "输出规则：\n" +
                        "- time 为时间提示（如\"明早\"\"14:00\"\"下周三\"）；没有时间信息则填空字符串\n" +
                        "- title 为简短事项名称（如\"开会\"\"交房租\"\"写方案\"），控制在 20 字以内\n" +
                        "- desc 为补充说明（地点、准备事项、对象等细节），没有则填空字符串\n" +
                        "- 同一件事在日记中出现多次时只输出一次\n" +
                        "- 若日记中没有待办事项，返回空数组 items\n" +
                        "严格按JSON格式返回：{\"items\":[{\"time\":\"明早\",\"title\":\"开会\",\"desc\":\"准备材料\"}]}，不要返回任何其他内容。");

                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", content);

                JSONArray messages = new JSONArray();
                messages.put(systemMsg);
                messages.put(userMsg);

                JSONObject requestBody = new JSONObject();
                requestBody.put("model", MODEL);
                requestBody.put("messages", messages);
                requestBody.put("stream", false);
                requestBody.put("temperature", 0.1);
                requestBody.put("max_tokens", 1024);
                // 日程模块：关闭默认开启的思考模式，直接输出 JSON
                requestBody.put("thinking", new JSONObject().put("type", "disabled"));

                Request request = new Request.Builder()
                        .url(BASE_URL + "/chat/completions")
                        .post(RequestBody.create(requestBody.toString(), JSON_TYPE))
                        .header("Authorization", "Bearer " + getApiKey())
                        .build();

                OkHttpClient client = OkHttpHelper.getDefault();
                try (Response response = client.newCall(request).execute()) {
                    if (response.code() == 200) {
                        String body = AIHttpUtils.readBodyString(response);
                        JSONObject json = new JSONObject(body);
                        JSONArray choices = json.getJSONArray("choices");
                        if (choices.length() == 0) {
                            callback.onFailure("AI 返回为空");
                            return;
                        }
                        String respContent = choices.getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content");

                        List<ScheduleItem> items = new ArrayList<>();
                        String jsonStr = extractJson(respContent);
                        if (jsonStr != null) {
                            JSONObject parsed = new JSONObject(jsonStr);
                            JSONArray arr = parsed.optJSONArray("items");
                            if (arr != null) {
                                for (int i = 0; i < arr.length(); i++) {
                                    JSONObject item = arr.getJSONObject(i);
                                    String title = item.optString("title", "").trim();
                                    if (title.isEmpty()) continue;
                                    items.add(new ScheduleItem(
                                            item.optString("time", ""),
                                            title,
                                            item.optString("desc", "")));
                                }
                            }
                        } else {
                            Log.w(TAG, "extractSchedule: no JSON in response, content=" + respContent);
                        }

                        // 保持 AI 输出的原始顺序，插入顺序即待办列表的排列顺序
                        Log.d(TAG, "extractSchedule: " + items.size() + " items");
                        callback.onResult(items);
                    } else {
                        String errorMsg = readErrorMessage(response);
                        Log.w(TAG, "extractSchedule: failed, " + errorMsg);
                        callback.onFailure(errorMsg);
                    }
                }
            } catch (UnknownHostException e) {
                Log.e(TAG, "extractSchedule: DNS resolution failed", e);
                callback.onFailure("无法连接到服务器，请检查网络");
            } catch (java.net.SocketTimeoutException e) {
                Log.e(TAG, "extractSchedule: timeout", e);
                callback.onFailure("AI 分析超时，请检查网络后重试");
            } catch (Exception e) {
                Log.e(TAG, "extractSchedule: exception", e);
                callback.onFailure("AI 分析失败：" + e.getMessage());
            }
        });
    }

    public static void generateSummary(String periodLabel, String entriesText, boolean isSummaryBased, SummaryCallback callback) {
        generateSummaryStream(periodLabel, entriesText, isSummaryBased, new SummaryStreamCallback() {
            @Override
            public void onPartialResult(String title, String content, boolean isComplete) {
                if (isComplete) {
                    callback.onResult(title, content);
                }
            }

            @Override
            public void onFailure(String message) {
                callback.onFailure(message);
            }
        });
    }

    public static void generateSummaryStream(String periodLabel, String entriesText, boolean isSummaryBased, SummaryStreamCallback callback) {
        AppExecutors.network().execute(() -> {
            try {
                JSONObject systemMsg = new JSONObject();
                systemMsg.put("role", "system");

                String basePrompt = SYSTEM_PREFIX + "当前任务：洞察卡片生成。";
                if (isSummaryBased) {
                    basePrompt += "用户会给你一段时间内的月度总结（以及部分月份的原始日记），你需要从中挖掘有价值的洞察，以卡片形式呈现。";
                } else {
                    basePrompt += "用户会给你一段时间内的日记条目，你需要从中挖掘有价值的洞察，以卡片形式呈现。";
                }
                basePrompt += "你可以自由决定生成哪些维度的卡片，数量在2-5个之间。参考维度包括但不限于：能力提升、习惯培养、价值观变化、情绪体验、关键事件、人际关系、成长突破、生活节奏等。" +
                        "根据内容选择最相关的维度，不要生硬地套用固定分类。如果某维度没有实质内容，就不要生成该卡片。" +
                        "每张卡片需要：" +
                        "1. type（类型）：从以下类型中选择最合适的：\"event\"（关键事件）、\"habit\"（习惯培养）、\"quote\"（金句感悟），如果都不匹配则留空或填\"default\"" +
                        "2. title（标题）：简短标题（2-6个字）" +
                        "3. content（内容）：一段有深度的内容（50-150字），基于具体事实，给出洞察而非简单复述" +
                        "对于type=\"event\"，内容要突出具体事件；对于type=\"habit\"，内容可包含习惯的进展；对于type=\"quote\"，内容可以是一句提炼的金句或感悟。" +
                        "同时生成一个4-6个字的整体标题，概括这段时间的核心主题。" +
                        "请严格按以下JSON格式返回，不要返回任何其他内容：" +
                        "{\"title\":\"整体标题\",\"cards\":[{\"type\":\"卡片类型\",\"title\":\"卡片标题\",\"content\":\"卡片内容\"},{\"type\":\"卡片类型\",\"title\":\"卡片标题\",\"content\":\"卡片内容\"}]}";

                systemMsg.put("content", basePrompt);

                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                String inputLabel = isSummaryBased ? "月度总结及原始日记" : "日记条目";
                userMsg.put("content", "时间段：" + periodLabel + "\n\n" + inputLabel + "：\n" + entriesText);

                JSONArray messages = new JSONArray();
                messages.put(systemMsg);
                messages.put(userMsg);

                JSONObject requestBody = new JSONObject();
                requestBody.put("model", MODEL);
                requestBody.put("messages", messages);
                requestBody.put("stream", true);
                requestBody.put("temperature", 0.7);
                requestBody.put("max_tokens", 512);

                Request request = buildSsePostRequest(BASE_URL + "/chat/completions", requestBody);

                OkHttpClient client = OkHttpHelper.getSse();
                try (Response response = client.newCall(request).execute()) {
                    if (response.code() == 200) {
                        StringBuilder fullContent = new StringBuilder();

                        readSSEStream(response, (chunk) -> {
                            fullContent.append(chunk);
                            String[] result = parseSummaryResult(fullContent.toString(), periodLabel);
                            if (result != null) {
                                callback.onPartialResult(result[0], result[1], false);
                            }
                        });

                        parseAndCallbackSummary(fullContent.toString(), periodLabel, callback);
                    } else {
                        String errorMsg = readErrorMessage(response);
                        Log.w(TAG, "generateSummaryStream: failed, " + errorMsg);
                        callback.onFailure(errorMsg);
                    }
                }
            } catch (UnknownHostException e) {
                Log.e(TAG, "generateSummaryStream: DNS resolution failed", e);
                callback.onFailure("无法连接到服务器，请检查网络");
            } catch (java.net.SocketTimeoutException e) {
                Log.e(TAG, "generateSummaryStream: timeout", e);
                callback.onFailure("AI 生成超时，请检查网络后重试");
            } catch (Exception e) {
                Log.e(TAG, "generateSummaryStream: exception", e);
                callback.onFailure("AI 生成失败：" + e.getMessage());
            }
        });
    }

    public static void generateSummaryStreamWithInsight(String periodLabel, String entriesText,
            boolean isSummaryBased, String periodType,
            String historyInsightContext,
            SummaryWithInsightStreamCallback callback) {
        generateSummaryStreamWithInsight(periodLabel, entriesText, isSummaryBased, periodType,
                historyInsightContext, 0.7, callback);
    }

    public static void generateSummaryStreamWithInsight(String periodLabel, String entriesText,
            boolean isSummaryBased, String periodType,
            String historyInsightContext,
            double temperature, SummaryWithInsightStreamCallback callback) {
        AppExecutors.network().execute(() -> {
            try {
                JSONObject systemMsg = new JSONObject();
                systemMsg.put("role", "system");

                String basePrompt;
                if ("yearly".equals(periodType)) {
                    basePrompt = SYSTEM_PREFIX + "当前任务：年度深度复盘。用户会给你一年的日记和总结，你需要写一封'年度信'。\n" +
                            "要求：\n" +
                            "1. 提取3-5个年度关键词（主题词，不是事件罗列）\n" +
                            "2. 判断用户这一年处于什么人生阶段，给出描述\n" +
                            "3. 标记重大转折点（时间节点及前后对比）\n" +
                            "4. 如果有去年数据，进行同比对比分析\n" +
                            "5. 基于全年模式给出下阶段方向性建议\n" +
                            "6. 语气温暖、有洞察力，像是在给朋友写一封回顾信\n" +
                            "输出格式：严格按JSON返回。第一张卡片type必须为\"letter\"，content为年度信全文（500-1500字），" +
                            "同时包含keywords（3-5个年度关键词字符串数组）、life_stage（人生阶段描述字符串）、" +
                            "turning_points（转折点字符串数组，每个元素格式为\"时间：描述\"）。" +
                            "后续可追加1-3张洞察卡片，type可选：\"event\"、\"habit\"、\"quote\"、\"default\"。" +
                            "同时生成一个4-6个字的整体标题。";
                } else if ("quarterly".equals(periodType)) {
                    basePrompt = SYSTEM_PREFIX + "当前任务：季度深度复盘。\n" +
                            "用户会给你一个季度的月度总结（以及部分月份的原始日记），你需要进行有深度的季度回顾。\n" +
                            "要求：\n" +
                            "1. 第一张卡片为叙事性主卡片（type=\"default\"），200-400字，按时间线梳理本季度的核心脉络\n" +
                            "2. 提取2-3个季度关键词，融入主卡片内容中\n" +
                            "3. 标记重大转折点（时间节点及前后对比）\n" +
                            "4. 与上季度进行对比分析（如历史上下文中有上季度数据），明确指出变化方向\n" +
                            "5. 给出下季度建议\n" +
                            "6. 后续追加2-4张洞察卡片，type可选：\"event\"（关键事件）、\"habit\"（习惯培养）、\"quote\"（金句感悟）、\"default\"\n" +
                            "7. 每张洞察卡片内容100-200字，基于具体事实\n" +
                            "同时生成一个4-6个字的整体标题。";
                } else if ("monthly".equals(periodType)) {
                    basePrompt = SYSTEM_PREFIX + "当前任务：月度总结。\n" +
                            "用户会给你本月的每周总结（以及部分周的原始日记），你需要从中提炼月度模式。\n" +
                            "要求：\n" +
                            "1. 识别本月反复出现的模式（出现3次以上的主题才算模式，不要把偶发事件当模式）\n" +
                            "2. 与上月对比（如历史上下文中有上月数据），明确指出增强/减弱/新增/消失的变化\n" +
                            "3. 可生成1张\"月度节奏\"卡片（type=\"default\"），描述本月生活节奏的快慢分布和能量波动\n" +
                            "4. 卡片数量3-5个，每张内容100-200字\n" +
                            "5. 卡片type可选：\"event\"（关键事件）、\"habit\"（习惯培养）、\"quote\"（金句感悟）、\"default\"\n" +
                            "6. 内容要基于具体事实，给出洞察而非简单复述周总结\n" +
                            "7. 不要生硬套用固定分类，根据内容选择最相关的维度\n" +
                            "同时生成一个4-6个字的整体标题，概括本月核心主题。";
                } else if ("weekly".equals(periodType)) {
                    basePrompt = SYSTEM_PREFIX + "当前任务：周总结。\n" +
                            "用户会给你本周的日记条目，你需要提炼本周的核心洞察。\n" +
                            "要求：\n" +
                            "1. 重点关注本周与上周的连续性变化（哪些事在延续、哪些事在收尾、哪些是新开始）\n" +
                            "2. 聚焦具体事件而非抽象模式（一周数据不足以支撑\"价值观变化\"等大词）\n" +
                            "3. 卡片内容80-120字，要求引用具体日期或事件细节\n" +
                            "4. 卡片数量2-4个\n" +
                            "5. 卡片type可选：\"event\"（关键事件）、\"habit\"（习惯培养）、\"quote\"（金句感悟）、\"default\"\n" +
                            "6. 不要生硬套用固定分类，根据内容选择最相关的维度\n" +
                            "7. 如果某维度没有实质内容，就不要生成该卡片\n" +
                            "同时生成一个4-6个字的整体标题，概括本周核心主题。";
                } else {
                    basePrompt = SYSTEM_PREFIX + "当前任务：洞察卡片生成。";
                    if (isSummaryBased) {
                        basePrompt += "用户会给你一段时间内的月度总结（以及部分月份的原始日记），你需要从中挖掘有价值的洞察，以卡片形式呈现。";
                    } else {
                        basePrompt += "用户会给你一段时间内的日记条目，你需要从中挖掘有价值的洞察，以卡片形式呈现。";
                    }
                    basePrompt += "你可以自由决定生成哪些维度的卡片，数量在2-5个之间。参考维度包括但不限于：能力提升、习惯培养、价值观变化、情绪体验、关键事件、人际关系、成长突破、生活节奏等。" +
                            "根据内容选择最相关的维度，不要生硬地套用固定分类。如果某维度没有实质内容，就不要生成该卡片。" +
                            "每张卡片需要：" +
                            "1. type（类型）：从以下类型中选择最合适的：\"event\"（关键事件）、\"habit\"（习惯培养）、\"quote\"（金句感悟），如果都不匹配则留空或填\"default\"" +
                            "2. title（标题）：简短标题（2-6个字）" +
                            "3. content（内容）：一段有深度的内容（50-150字），基于具体事实，给出洞察而非简单复述" +
                            "对于type=\"event\"，内容要突出具体事件；对于type=\"habit\"，内容可包含习惯的进展；对于type=\"quote\"，内容可以是一句提炼的金句或感悟。" +
                            "同时生成一个4-6个字的整体标题，概括这段时间的核心主题。";
                }

                // Append insight extraction instructions
                basePrompt += "\n\n在输出总结JSON之后，你还需要输出一个额外的JSON块：\n" +
                        "在```INSIGHT_JSON```和```/INSIGHT_JSON```之间，输出结构化洞察标签：\n" +
                        "```INSIGHT_JSON\n" +
                        "{\"key_persons\":[\"人物1\",\"人物2\"],\"key_projects\":[\"项目1\"],\"key_events\":[\"事件1\"],\"emotion_themes\":[\"主题1\"],\"decisions\":[\"决策1\"]}\n" +
                        "```/INSIGHT_JSON\n" +
                        "请严格按照上述格式输出，确保JSON可解析。";

                // Inject history insight context
                if (historyInsightContext != null && !historyInsightContext.isEmpty()) {
                    basePrompt += "\n\n" + historyInsightContext;
                }

                systemMsg.put("content", basePrompt);

                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                String inputLabel = isSummaryBased ? "月度总结及原始日记" : "日记条目";
                userMsg.put("content", "时间段：" + periodLabel + "\n\n" + inputLabel + "：\n" + entriesText);

                JSONArray messages = new JSONArray();
                messages.put(systemMsg);
                messages.put(userMsg);

                JSONObject requestBody = new JSONObject();
                requestBody.put("model", MODEL);
                requestBody.put("messages", messages);
                requestBody.put("stream", true);
                requestBody.put("temperature", temperature);
                // 轨迹模块：关闭默认开启的思考模式，简化流式解析并提速
                requestBody.put("thinking", new JSONObject().put("type", "disabled"));
                int maxTokens;
                switch (periodType) {
                    case "yearly": maxTokens = 16384; break;
                    case "quarterly": case "monthly": maxTokens = 8192; break;
                    default: maxTokens = 4096; break;
                }
                requestBody.put("max_tokens", maxTokens);

                Request request = buildSsePostRequest(BASE_URL + "/chat/completions", requestBody);

                OkHttpClient client = OkHttpHelper.getSse();
                try (Response response = client.newCall(request).execute()) {
                    if (response.code() == 200) {
                        StringBuilder fullContent = new StringBuilder();

                        readSSEStream(response, (chunk) -> {
                            fullContent.append(chunk);
                            String streamingText = stripInsightBlocks(fullContent.toString());
                            String[] result = parseSummaryResult(streamingText, periodLabel);
                            if (result != null) {
                                callback.onPartialResult(result[0], result[1], false);
                            }
                        });

                        // Parse final result — strip INSIGHT_JSON block before parsing summary
                        String fullText = fullContent.toString();
                        String summaryText = stripInsightBlocks(fullText);
                        String[] summaryResult = parseSummaryResult(summaryText, periodLabel);
                        String title = summaryResult != null ? summaryResult[0] : periodLabel;
                        String content = summaryResult != null ? summaryResult[1] : summaryText;

                        // Extract insight JSON
                        String insightJson = extractBlock(fullText, "INSIGHT_JSON");

                        Log.d(TAG, "generateSummaryStreamWithInsight: insightJson=" + insightJson);

                        callback.onComplete(title, content, insightJson);
                    } else {
                        String errorMsg = readErrorMessage(response);
                        Log.w(TAG, "generateSummaryStreamWithInsight: failed, " + errorMsg);
                        callback.onFailure(errorMsg);
                    }
                }
            } catch (UnknownHostException e) {
                Log.e(TAG, "generateSummaryStreamWithInsight: DNS resolution failed", e);
                callback.onFailure("无法连接到服务器，请检查网络");
            } catch (java.net.SocketTimeoutException e) {
                Log.e(TAG, "generateSummaryStreamWithInsight: timeout", e);
                callback.onFailure("AI 生成超时，请检查网络后重试");
            } catch (Exception e) {
                Log.e(TAG, "generateSummaryStreamWithInsight: exception", e);
                callback.onFailure("AI 生成失败：" + e.getMessage());
            }
        });
    }

    /**
     * 同步包装 generateSummaryStreamWithInsight，用于预加载场景。
     * 在调用线程阻塞等待结果，超时 90 秒。
     */
    public static SummaryWithInsightEntry generateSummaryWithInsightSync(String periodLabel, String entriesText,
            boolean isSummaryBased, String periodType,
            String historyInsightContext) {
        return generateSummaryWithInsightSync(periodLabel, entriesText, isSummaryBased, periodType,
                historyInsightContext, 0.7);
    }

    /**
     * 同步包装 generateSummaryStreamWithInsight，支持自定义 temperature。
     * 用于自检失败后的低温度重新生成。
     */
    public static SummaryWithInsightEntry generateSummaryWithInsightSync(String periodLabel, String entriesText,
            boolean isSummaryBased, String periodType,
            String historyInsightContext,
            double temperature) {
        try {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final SummaryWithInsightEntry[] result = new SummaryWithInsightEntry[1];

            generateSummaryStreamWithInsight(periodLabel, entriesText, isSummaryBased,
                    periodType, historyInsightContext, temperature,
                    new SummaryWithInsightStreamCallback() {
                        @Override
                        public void onPartialResult(String title, String content, boolean isComplete) {
                            // 预加载/重新生成不需要中间结果
                        }

                        @Override
                        public void onComplete(String title, String content, String insightJson) {
                            result[0] = new SummaryWithInsightEntry(title, content, insightJson);
                            latch.countDown();
                        }

                        @Override
                        public void onFailure(String message) {
                            Log.w(TAG, "generateSummaryWithInsightSync: failed, " + message);
                            latch.countDown();
                        }
                    });

            latch.await(90, java.util.concurrent.TimeUnit.SECONDS);
            return result[0];
        } catch (Exception e) {
            Log.e(TAG, "generateSummaryWithInsightSync: exception", e);
            return null;
        }
    }

    public interface InsightTimeRangeCallback {
        void onResult(String startDate, String endDate);
        void onFailure(String message);
    }

    public interface InsightAnswerCallback {
        void onPartialResult(String content, boolean isComplete);
        void onFailure(String message);
    }

    public interface InsightFollowUpCallback {
        void onResult(List<String> suggestions);
        void onFailure(String message);
    }

    public static class ConversationTurn {
        public final String question;
        public final String answer;
        public ConversationTurn(String question, String answer) {
            this.question = question;
            this.answer = answer;
        }
    }

    public static void analyzeTimeRange(String question, InsightTimeRangeCallback callback) {
        AppExecutors.network().execute(() -> {
            try {
                String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());

                JSONObject systemMsg = new JSONObject();
                systemMsg.put("role", "system");
                systemMsg.put("content", SYSTEM_PREFIX + "当前任务：时间范围分析。根据用户的问题，判断问题涉及的时间范围。" +
                        "今天是" + today + "。根据问题中的时间表述（如\"上周\"、\"这个月\"、\"最近三天\"、\"去年\"等）推断起止日期。\n\n" +
                        "问题类型与默认范围规则：\n" +
                        "- 事实类问题（\"做了什么\"、\"发生了什么\"、\"去了哪里\"）：按问题中的时间表述推断，无明确表述则默认7天\n" +
                        "- 模式/原因类问题（\"为什么总是\"、\"为什么最近\"、\"为什么经常\"、\"怎么回事\"）：默认30天，因为模式需要更长时间跨度才能识别\n" +
                        "- 趋势/变化类问题（\"最近的变化\"、\"有没有进步\"、\"趋势如何\"、\"和之前比\"）：默认90天，因为趋势需要足够长的基线\n" +
                        "- 情绪类问题（\"心情怎么样\"、\"为什么焦虑\"、\"为什么低落\"）：默认14天\n\n" +
                        "如果问题同时涉及多种类型，取较长的范围。\n" +
                        "严格按JSON格式返回：{\"start_date\":\"yyyy-MM-dd\",\"end_date\":\"yyyy-MM-dd\"}" +
                        "start_date是起始日期（含），end_date是结束日期（含）。");

                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", question);

                JSONArray messages = new JSONArray();
                messages.put(systemMsg);
                messages.put(userMsg);

                JSONObject requestBody = new JSONObject();
                requestBody.put("model", MODEL);
                requestBody.put("messages", messages);
                requestBody.put("stream", false);
                requestBody.put("temperature", 0.1);
                requestBody.put("max_tokens", 128);

                RequestBody httpBody = RequestBody.create(requestBody.toString(), JSON_TYPE);
                Request request = new Request.Builder()
                        .url(BASE_URL + "/chat/completions")
                        .post(httpBody)
                        .header("Authorization", "Bearer " + getApiKey())
                        .build();

                OkHttpClient client = OkHttpHelper.getDefault();
                try (Response response = client.newCall(request).execute()) {
                    if (response.code() == 200) {
                        String body = AIHttpUtils.readBodyString(response);
                        JSONObject json = new JSONObject(body);
                        JSONArray choices = json.getJSONArray("choices");
                        String content = choices.getJSONObject(0).getJSONObject("message").getString("content");

                        String jsonStr = extractJson(content);
                        if (jsonStr != null) {
                            JSONObject parsed = new JSONObject(jsonStr);
                            String startDate = parsed.optString("start_date", null);
                            String endDate = parsed.optString("end_date", null);

                            if (startDate != null && endDate != null
                                    && startDate.matches("\\d{4}-\\d{2}-\\d{2}")
                                    && endDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
                                callback.onResult(startDate, endDate);
                            } else {
                                callback.onFailure("日期格式解析失败");
                            }
                        } else {
                            callback.onFailure("无法识别时间范围");
                        }
                    } else {
                        String errorMsg = readErrorMessage(response);
                        callback.onFailure(errorMsg);
                    }
                }
            } catch (UnknownHostException e) {
                Log.e(TAG, "analyzeTimeRange: DNS resolution failed", e);
                callback.onFailure("无法连接到服务器，请检查网络");
            } catch (java.net.SocketTimeoutException e) {
                Log.e(TAG, "analyzeTimeRange: timeout", e);
                callback.onFailure("连接超时，请检查网络后重试");
            } catch (Exception e) {
                Log.e(TAG, "analyzeTimeRange: exception", e);
                callback.onFailure("分析失败：" + e.getMessage());
            }
        });
    }

    public static void answerWithDiaryContext(String question, String diaryContext, String startDate, String endDate, int entryCount, InsightAnswerCallback callback) {
        answerWithDiaryContext(question, diaryContext, startDate, endDate, entryCount, null, null, callback);
    }

    public static void answerWithDiaryContext(String question, String diaryContext,
            String startDate, String endDate, int entryCount,
            List<ConversationTurn> conversationHistory,
            String insightContext,
            InsightAnswerCallback callback) {
        AppExecutors.network().execute(() -> {
            try {
                // ========== 第一轮：生成草稿（含分析过程） ==========
                JSONObject systemMsg = new JSONObject();
                systemMsg.put("role", "system");

                String prompt = SYSTEM_PREFIX + "\n\n当前任务：洞察问答。用户会提出关于自己生活的问题，并附上相关的日记内容作为参考。\n\n" +
                        "请按以下格式输出：\n" +
                        "【分析过程】\n" +
                        "（在这里进行推理分析，不要省略。包括：识别核心维度、提取关键事件、分析因果链、发现矛盾点。可以引用具体日期和日记原文。）\n" +
                        "【回答】\n" +
                        "（这里是给用户的初版回答。开头说明参考的日期范围和日记篇数，然后展开分析。）\n\n" +
                        "分析要求：\n" +
                        "1. 识别问题涉及的核心维度（情绪、人际关系、工作、健康、成长、习惯等）\n" +
                        "2. 从日记中找出与问题相关的具体事件和情绪变化，注意时间线上的先后关系\n" +
                        "3. 分析因果链：某事件或情境如何导致了某情绪或行为变化\n" +
                        "4. 如果发现矛盾（比如日记说开心但行为暗示压力），指出并分析可能的原因\n\n" +
                        "回答要求（硬约束，必须遵守）：\n" +
                        "- 每个结论至少引用1个具体日期或事件作为证据\n" +
                        "- 引用日记原文时用引号标出\n" +
                        "- 如果日记证据不足，必须明确说\"日记中未提及\"，不得编造\n" +
                        "- 避免简单复述日记内容，要提炼出日记字面之下的见解\n" +
                        "- 回答要充分展开，不要刻意缩短篇幅\n" +
                        "- 如果用户在追问之前的话题，结合之前的对话上下文连贯回答\n\n" +
                        "【回答】部分开头请先说明参考了从" + startDate + "到" + endDate + "的" + entryCount + "篇日记，再展开分析。\n" +
                        "【回答】部分使用纯文本输出，禁止使用任何Markdown格式符号（如**、##、*、-等），只使用普通文字和标点。\n" +
                        "使用中文回答。\n\n" +
                        "示例：\n\n" +
                        "用户问题：最近为什么总是失眠？\n" +
                        "相关日记：\n" +
                        "【2026-03-05】今天加班到11点，回家后脑子还在转，躺了一个多小时才睡着。\n" +
                        "【2026-03-08】又失眠了，下午喝了杯咖啡提神。\n" +
                        "【2026-03-10】连续第三天没睡好，白天精力很差，工作上出了两个错。\n" +
                        "【2026-03-12】试了睡前听白噪音，居然20分钟就睡着了。\n\n" +
                        "【分析过程】\n" +
                        "核心维度：睡眠、工作压力、咖啡因摄入。\n" +
                        "时间线：3月5日首次出现失眠，当天加班到11点且\"脑子还在转\"。3月8日再次失眠，当天有咖啡摄入记录。3月10日失眠加剧为连续三天，工作出错。3月12日尝试白噪音有效。\n" +
                        "因果链：加班导致大脑兴奋引发失眠；咖啡因加重神经兴奋。失眠导致精力差、工作出错，进而压力增大，形成恶性循环。\n" +
                        "矛盾点：3月12日用白噪音打破了循环，说明环境调节有效，但根源（加班加咖啡因）未解决。\n\n" +
                        "【回答】\n" +
                        "参考了从2026-03-05到2026-03-12的4篇日记。\n\n" +
                        "从3月5日开始，失眠成为一个明显的问题。那天加班到11点，日记里写\"脑子还在转\"，这是入睡困难的直接描述。3月8日的情况更复杂，当天有喝咖啡的记录，咖啡因的半衰期约5小时，下午饮用很可能影响了夜间睡眠。\n\n" +
                        "3月10日之后失眠频率上升，同时精力下降、工作出错，形成了一个值得注意的循环：睡不好导致精力差，精力差导致工作效率低，效率低又需要加班，加班又更难入睡。3月12日的白噪音试验是个转折，20分钟入睡说明环境调节确实有效，但这只解决了入睡环节，根源上的加班时长和咖啡因摄入没有变化。\n\n" +
                        "可以尝试的方向：把最后一杯咖啡改到下午2点前，加班日提前做15分钟放松练习，3月12日的白噪音经验可以延伸到这里。如果加班不可避免，至少在下班后给自己30分钟的缓冲时间再上床。";

                if (insightContext != null && !insightContext.isEmpty()) {
                    prompt += "\n\n" + insightContext;
                }

                systemMsg.put("content", prompt);

                JSONArray messages = new JSONArray();
                messages.put(systemMsg);

                // 注入对话历史（最近5轮，与 InsightFragment 保持一致）
                if (conversationHistory != null) {
                    int startIdx = Math.max(0, conversationHistory.size() - 5);
                    for (int i = startIdx; i < conversationHistory.size(); i++) {
                        ConversationTurn turn = conversationHistory.get(i);
                        JSONObject histUser = new JSONObject();
                        histUser.put("role", "user");
                        histUser.put("content", "我的问题：" + turn.question);
                        messages.put(histUser);

                        JSONObject histAssistant = new JSONObject();
                        histAssistant.put("role", "assistant");
                        histAssistant.put("content", turn.answer);
                        messages.put(histAssistant);
                    }
                }

                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", "我的问题：" + question + "\n\n相关日记：\n" + diaryContext);
                messages.put(userMsg);

                JSONObject requestBody = new JSONObject();
                requestBody.put("model", MODEL);
                requestBody.put("messages", messages);
                requestBody.put("stream", true);
                requestBody.put("temperature", 0.5);
                requestBody.put("max_tokens", 8192);

                Request request = buildSsePostRequest(BASE_URL + "/chat/completions", requestBody);

                OkHttpClient client = OkHttpHelper.getSse();
                String draftAnswer;
                try (Response response = client.newCall(request).execute()) {
                    if (response.code() == 200) {
                        StringBuilder fullContent = new StringBuilder();

                        readSSEStream(response, (chunk) -> {
                            fullContent.append(chunk);
                            String displayContent = stripReasoningBlock(fullContent.toString());
                            if (!displayContent.isEmpty()) {
                                callback.onPartialResult(displayContent, false);
                            }
                        });

                        draftAnswer = stripReasoningBlock(fullContent.toString());
                        if (draftAnswer.isEmpty()) {
                            String fullText = fullContent.toString();
                            int reasoningMarker = fullText.indexOf("【分析过程】");
                            if (reasoningMarker != -1) {
                                // AI 输出了分析过程但被截断没有【回答】，返回分析内容（去掉标记）
                                draftAnswer = fullText.substring(reasoningMarker + "【分析过程】".length()).trim();
                            }
                            if (draftAnswer.isEmpty()) {
                                // AI 完全没按格式输出，兜底用原文
                                draftAnswer = fullText;
                            }
                        }
                    } else {
                        String errorMsg = readErrorMessage(response);
                        Log.w(TAG, "answerWithDiaryContext: draft failed, " + errorMsg);
                        callback.onFailure(errorMsg);
                        return;
                    }
                }

                // ========== 第二轮：自我完善 ==========
                // 先确保草稿完整显示在 UI 上（第一轮流式可能最后一段没刷新）
                callback.onPartialResult(draftAnswer, false);
                String refinedAnswer = refineAnswer(question, draftAnswer, startDate, endDate, entryCount);
                if (refinedAnswer == null) {
                    // 完善失败，用草稿
                    callback.onPartialResult(draftAnswer, true);
                } else {
                    callback.onPartialResult(refinedAnswer, true);
                }
            } catch (UnknownHostException e) {
                Log.e(TAG, "answerWithDiaryContext: DNS resolution failed", e);
                callback.onFailure("无法连接到服务器，请检查网络");
            } catch (java.net.SocketTimeoutException e) {
                Log.e(TAG, "answerWithDiaryContext: timeout", e);
                callback.onFailure("AI 回答超时，请检查网络后重试");
            } catch (Exception e) {
                Log.e(TAG, "answerWithDiaryContext: exception", e);
                callback.onFailure("AI 回答失败：" + e.getMessage());
            }
        });
    }

    /**
     * 第二轮：让 AI 审视自己的初版回答并完善。
     * 静默累积 refinedContent，不在 UI 上流式替换草稿（避免“重新开始回答”视觉）。
     * 完成后由调用方一次性 onPartialResult(refinedAnswer, true) 替换。
     * 失败时返回 null，调用方使用初版回答兜底。
     */
    private static String refineAnswer(String question, String draftAnswer, String startDate, String endDate,
                                       int entryCount) {
        try {
            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", SYSTEM_PREFIX + "\n\n当前任务：回答完善。你刚才为用户生成了一份初版回答，现在请审视并完善它。\n\n" +
                    "完善方向：\n" +
                    "1. 检查是否有结论缺少具体证据支撑，补充引用的日期或事件\n" +
                    "2. 检查因果链是否完整，是否有跳跃的逻辑环节\n" +
                    "3. 检查是否遗漏了日记中的重要线索\n" +
                    "4. 检查建议是否具体可操作，避免空泛的\"保持好心态\"之类\n" +
                    "5. 检查语气是否温和、不说教，像朋友而非导师\n" +
                    "6. 检查是否过度解读了单次事件，确保结论有足够证据\n\n" +
                    "硬约束：\n" +
                    "- 不得编造日记中不存在的内容，证据不足时明确说明\n" +
                    "- 保留初版回答中准确的部分，不要为了改而改\n" +
                    "- 开头仍需说明参考了从" + startDate + "到" + endDate + "的" + entryCount + "篇日记\n" +
                    "- 使用纯文本输出，禁止任何Markdown格式符号\n" +
                    "- 使用中文\n" +
                    "- 直接输出完善后的回答，不要输出分析过程，不要输出\"完善后的回答\"之类的前缀");

            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", "用户问题：" + question + "\n\n初版回答：\n" + draftAnswer + "\n\n请输出完善后的回答。");

            JSONArray messages = new JSONArray();
            messages.put(systemMsg);
            messages.put(userMsg);

            JSONObject requestBody = new JSONObject();
            requestBody.put("model", MODEL);
            requestBody.put("messages", messages);
            requestBody.put("stream", true);
            requestBody.put("temperature", 0.4);
            requestBody.put("max_tokens", 8192);

            Request request = buildSsePostRequest(BASE_URL + "/chat/completions", requestBody);

            OkHttpClient client = OkHttpHelper.getSse();
            StringBuilder refinedContent = new StringBuilder();
            // 第二轮静默累积，不在 UI 上流式替换。
            // 原实现按阈值用 refinedContent 替换 draftAnswer 会造成
            // “完整草稿突然变回开头几百字”的视觉，被用户反馈为“重新开始回答”。
            // 改为：第二轮完成后由调用方一次性 onPartialResult(refinedAnswer, true) 替换。
            try (Response response = client.newCall(request).execute()) {
                if (response.code() != 200) {
                    String errorMsg = readErrorMessage(response);
                    Log.w(TAG, "refineAnswer: failed, " + errorMsg);
                    return null;
                }

                readSSEStream(response, (chunk) -> refinedContent.append(chunk));

                String result = refinedContent.toString().trim();
                // 完善后的回答太短（低于初版三分之一），判定为失败，用初版兜底
                int minLength = Math.max(50, draftAnswer.length() / 3);
                if (result.length() < minLength) {
                    Log.w(TAG, "refineAnswer: refined too short (" + result.length() + " < " + minLength + "), fallback to draft");
                    return null;
                }
                return result;
            }
        } catch (Exception e) {
            Log.e(TAG, "refineAnswer: exception", e);
            return null;
        }
    }

    /**
     * 从 AI 输出中剥离【分析过程】部分，只保留【回答】部分。
     * 流式传输时，如果还没到【回答】标记，返回空字符串。
     * 如果 AI 没按格式输出（没有【分析过程】和【回答】标记），返回原文。
     */
    private static String stripReasoningBlock(String text) {
        if (text == null || text.isEmpty()) return "";

        int answerMarker = text.indexOf("【回答】");

        if (answerMarker == -1) {
            // 还没到回答部分
            boolean hasReasoning = text.contains("【分析过程】");
            if (hasReasoning) {
                // 还在推理中，不显示
                return "";
            }
            // AI 没按格式输出，直接返回原文
            return text;
        }

        // 返回【回答】之后的内容
        return text.substring(answerMarker + "【回答】".length()).trim();
    }

    public static void generateFollowUpSuggestions(String question, String answer, InsightFollowUpCallback callback) {
        AppExecutors.network().execute(() -> {
            try {
                JSONObject systemMsg = new JSONObject();
                systemMsg.put("role", "system");
                systemMsg.put("content", SYSTEM_PREFIX + "当前任务：根据用户的问题和AI的回答，生成3个追问建议。" +
                        "追问建议应该帮助用户更深入地探索自己的生活模式。" +
                        "建议要具体、可操作，避免泛泛而谈。" +
                        "每个建议不超过20个字。" +
                        "严格按JSON格式返回：{\"suggestions\":[\"建议1\",\"建议2\",\"建议3\"]}");

                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", "用户问题：" + question + "\n\nAI回答：" + (answer.length() > 500 ? answer.substring(0, 500) + "…" : answer));

                JSONArray messages = new JSONArray();
                messages.put(systemMsg);
                messages.put(userMsg);

                JSONObject requestBody = new JSONObject();
                requestBody.put("model", MODEL);
                requestBody.put("messages", messages);
                requestBody.put("stream", false);
                requestBody.put("temperature", 0.8);
                requestBody.put("max_tokens", 256);

                RequestBody httpBody = RequestBody.create(requestBody.toString(), JSON_TYPE);
                Request request = new Request.Builder()
                        .url(BASE_URL + "/chat/completions")
                        .post(httpBody)
                        .header("Authorization", "Bearer " + getApiKey())
                        .build();

                OkHttpClient client = OkHttpHelper.getDefault();
                try (Response response = client.newCall(request).execute()) {
                    if (response.code() == 200) {
                        String body = AIHttpUtils.readBodyString(response);
                        JSONObject json = new JSONObject(body);
                        JSONArray choices = json.getJSONArray("choices");
                        String content = choices.getJSONObject(0).getJSONObject("message").getString("content");

                        List<String> suggestions = new ArrayList<>();
                        String jsonStr = extractJson(content);
                        if (jsonStr != null) {
                            JSONObject parsed = new JSONObject(jsonStr);
                            JSONArray arr = parsed.optJSONArray("suggestions");
                            if (arr != null) {
                                for (int i = 0; i < arr.length(); i++) {
                                    String s = arr.optString(i, "");
                                    if (!s.isEmpty()) suggestions.add(s);
                                }
                            }
                        }
                        callback.onResult(suggestions);
                    } else {
                        String errorMsg = readErrorMessage(response);
                        callback.onFailure(errorMsg);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "generateFollowUpSuggestions: exception", e);
                callback.onFailure("生成追问建议失败：" + e.getMessage());
            }
        });
    }

    public interface DiaryFilterCallback {
        void onResult(List<Integer> relevantIndices);
        void onFailure(String message);
    }

    /**
     * 日记相关性筛选。根据用户问题，从日记列表中筛选出相关篇目的索引。
     * 只传日记摘要（日期+前80字）给AI，减少token消耗。
     * 筛选失败时返回全部索引作为兜底。
     */
    public static void filterRelevantDiaries(String question, List<String[]> entries, DiaryFilterCallback callback) {
        AppExecutors.network().execute(() -> {
            try {
                StringBuilder listText = new StringBuilder();
                for (int i = 0; i < entries.size(); i++) {
                    String[] entry = entries.get(i);
                    if (entry == null || entry.length < 3) {
                        listText.append("[").append(i).append("] (数据异常)\n");
                        continue;
                    }
                    String date = entry[1];
                    String content = entry[2];
                    String summary = content.length() > 80 ? content.substring(0, 80) + "…" : content;
                    summary = summary.replace("\n", " ");
                    listText.append("[").append(i).append("] ").append(date).append("：").append(summary).append("\n");
                }

                JSONObject systemMsg = new JSONObject();
                systemMsg.put("role", "system");
                systemMsg.put("content", SYSTEM_PREFIX + "当前任务：日记相关性筛选。\n" +
                        "用户会提出一个问题，并附上日记列表（带序号、日期和摘要）。\n" +
                        "你需要判断哪些日记与问题相关，返回相关日记的序号列表。\n" +
                        "规则：\n" +
                        "- 返回与问题直接或间接相关的日记序号\n" +
                        "- 如果不确定是否相关，倾向于包含\n" +
                        "- 至少返回3篇（如果总数不足3篇则全部返回）\n" +
                        "- 最多返回全部\n" +
                        "严格按JSON格式返回：{\"indices\":[0,2,5,7]}");

                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", "我的问题：" + question + "\n\n日记列表：\n" + listText.toString());

                JSONArray messages = new JSONArray();
                messages.put(systemMsg);
                messages.put(userMsg);

                JSONObject requestBody = new JSONObject();
                requestBody.put("model", MODEL);
                requestBody.put("messages", messages);
                requestBody.put("stream", false);
                requestBody.put("temperature", 0.1);
                requestBody.put("max_tokens", 512);

                RequestBody httpBody = RequestBody.create(requestBody.toString(), JSON_TYPE);
                Request request = new Request.Builder()
                        .url(BASE_URL + "/chat/completions")
                        .post(httpBody)
                        .header("Authorization", "Bearer " + getApiKey())
                        .build();

                OkHttpClient client = OkHttpHelper.getDefault();
                try (Response response = client.newCall(request).execute()) {
                    if (response.code() == 200) {
                        String body = AIHttpUtils.readBodyString(response);
                        JSONObject json = new JSONObject(body);
                        JSONArray choices = json.getJSONArray("choices");
                        String content = choices.getJSONObject(0).getJSONObject("message").getString("content");

                        List<Integer> indices = new ArrayList<>();
                        String jsonStr = extractJson(content);
                        if (jsonStr != null) {
                            JSONObject parsed = new JSONObject(jsonStr);
                            JSONArray arr = parsed.optJSONArray("indices");
                            if (arr != null) {
                                for (int i = 0; i < arr.length(); i++) {
                                    int idx = arr.optInt(i, -1);
                                    if (idx >= 0 && idx < entries.size()) {
                                        indices.add(idx);
                                    }
                                }
                            }
                        }

                        if (indices.isEmpty()) {
                            // 筛选失败，返回全部索引
                            for (int i = 0; i < entries.size(); i++) {
                                indices.add(i);
                            }
                        }
                        Log.d(TAG, "filterRelevantDiaries: " + indices.size() + "/" + entries.size() + " entries selected");
                        callback.onResult(indices);
                    } else {
                        String errorMsg = readErrorMessage(response);
                        Log.w(TAG, "filterRelevantDiaries: failed, " + errorMsg);
                        callback.onFailure(errorMsg);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "filterRelevantDiaries: exception", e);
                callback.onFailure("日记筛选失败：" + e.getMessage());
            }
        });
    }

    public interface TagCallback {
        void onResult(List<String> tags);
        void onFailure(String message);
    }

    /**
     * AI 打标签：根据日记内容，从可用标签库中选 1-3 个标签。
     * 异步版本，用于保存日记后自动打标签。
     *
     * @param content       日记内容
     * @param availableTags 可用标签名列表（从 DB 动态读取）
     * @param callback      回调
     */
    public static void tagDiary(String content, List<String> availableTags, TagCallback callback) {
        AppExecutors.network().execute(() -> {
            List<String> tags = tagDiarySyncInternal(content, availableTags);
            if (tags != null) {
                callback.onResult(tags);
            } else {
                callback.onFailure("AI 打标签失败");
            }
        });
    }

    /**
     * AI 打标签：同步版本，用于批量补全（调用方需在 io 线程）。
     * 失败返回 null。
     */
    public static List<String> tagDiarySync(String content, List<String> availableTags) {
        return tagDiarySyncInternal(content, availableTags);
    }

    private static List<String> tagDiarySyncInternal(String content, List<String> availableTags) {
        if (content == null || content.trim().isEmpty()) return null;
        if (availableTags == null || availableTags.isEmpty()) return null;

        try {
            StringBuilder tagList = new StringBuilder();
            for (int i = 0; i < availableTags.size(); i++) {
                if (i > 0) tagList.append("、");
                tagList.append(availableTags.get(i));
            }

            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", SYSTEM_PREFIX + "当前任务：为日记打标签。\n" +
                    "可用标签列表：" + tagList + "\n" +
                    "规则：\n" +
                    "- 从上述列表中选 1-3 个最贴切的标签\n" +
                    "- 必须只从列表中选择，不要编造新标签\n" +
                    "- 优先选核心主题，避免过度标注\n" +
                    "- 如果内容太短或无法判断，返回 [\"其他\"]\n" +
                    "严格按 JSON 格式返回：{\"tags\":[\"标签1\",\"标签2\"]}");

            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", content);

            JSONArray messages = new JSONArray();
            messages.put(systemMsg);
            messages.put(userMsg);

            JSONObject requestBody = new JSONObject();
            requestBody.put("model", MODEL);
            requestBody.put("messages", messages);
            requestBody.put("stream", false);
            requestBody.put("temperature", 0.3);
            requestBody.put("max_tokens", 128);

            RequestBody httpBody = RequestBody.create(requestBody.toString(), JSON_TYPE);
            Request request = new Request.Builder()
                    .url(BASE_URL + "/chat/completions")
                    .post(httpBody)
                    .header("Authorization", "Bearer " + getApiKey())
                    .build();

            OkHttpClient client = OkHttpHelper.getDefault();
            try (Response response = client.newCall(request).execute()) {
                if (response.code() == 200) {
                    String body = AIHttpUtils.readBodyString(response);
                    JSONObject json = new JSONObject(body);
                    JSONArray choices = json.getJSONArray("choices");
                    String respContent = choices.getJSONObject(0).getJSONObject("message").getString("content");

                    List<String> tags = new ArrayList<>();
                    String jsonStr = extractJson(respContent);
                    if (jsonStr != null) {
                        JSONObject parsed = new JSONObject(jsonStr);
                        JSONArray arr = parsed.optJSONArray("tags");
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                String tag = arr.optString(i, null);
                                if (tag != null && availableTags.contains(tag)) {
                                    tags.add(tag);
                                }
                            }
                        }
                    }

                    if (tags.isEmpty()) {
                        Log.w(TAG, "tagDiary: no valid tags, fallback to 其他");
                        tags.add("其他");
                    }
                    Log.d(TAG, "tagDiary: " + tags + " for content(" + content.length() + " chars)");
                    return tags;
                } else {
                    String errorMsg = readErrorMessage(response);
                    Log.w(TAG, "tagDiary: failed, " + errorMsg);
                    return null;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "tagDiary: exception", e);
            return null;
        }
    }

    public interface CompressCallback {
        void onResult(String compressed);
        void onFailure(String message);
    }

    public static void compressDiaryChunk(String chunk, CompressCallback callback) {
        AppExecutors.network().execute(() -> {
            try {
                JSONObject systemMsg = new JSONObject();
                systemMsg.put("role", "system");
                systemMsg.put("content", SYSTEM_PREFIX + "当前任务：日记压缩。将以下日记内容压缩到40000字以内。" +
                        "保留所有关键事件、重要决策、情绪变化、人际关系和转折点，同时保留有价值的细节和具体描述。" +
                        "删除日常琐事和重复内容，但不要遗漏任何重要信息。" +
                        "保持时间顺序，用简洁但不失细节的语言概括。");

                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", chunk);

                JSONArray messages = new JSONArray();
                messages.put(systemMsg);
                messages.put(userMsg);

                JSONObject requestBody = new JSONObject();
                requestBody.put("model", MODEL);
                requestBody.put("messages", messages);
                requestBody.put("stream", false);
                requestBody.put("temperature", 0.3);
                requestBody.put("max_tokens", 4096);

                RequestBody httpBody = RequestBody.create(requestBody.toString(), JSON_TYPE);
                Request request = new Request.Builder()
                        .url(BASE_URL + "/chat/completions")
                        .post(httpBody)
                        .header("Authorization", "Bearer " + getApiKey())
                        .build();

                OkHttpClient client = OkHttpHelper.getDefault();
                try (Response response = client.newCall(request).execute()) {
                    if (response.code() == 200) {
                        String body = AIHttpUtils.readBodyString(response);
                        JSONObject json = new JSONObject(body);
                        JSONArray choices = json.getJSONArray("choices");
                        String content = choices.getJSONObject(0).getJSONObject("message").getString("content");
                        callback.onResult(content);
                    } else {
                        String errorMsg = readErrorMessage(response);
                        callback.onFailure(errorMsg);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "compressDiaryChunk: exception", e);
                callback.onFailure("压缩失败：" + e.getMessage());
            }
        });
    }

    private static Request buildSsePostRequest(String url, JSONObject body) throws Exception {
        RequestBody requestBody = RequestBody.create(body.toString(), JSON_TYPE);
        return new Request.Builder()
                .url(url)
                .post(requestBody)
                .header("Authorization", "Bearer " + getApiKey())
                .header("Accept", "text/event-stream")
                .header("Connection", "keep-alive")
                .build();
    }

    private interface SSEChunkHandler {
        void onChunk(String chunk);
    }

    private static void readSSEStream(Response response, SSEChunkHandler handler) throws Exception {
        AIHttpUtils.readSSEStream(response, handler::onChunk);
    }

    private static void parseAndCallbackSummary(String fullContent, String defaultTitle, SummaryStreamCallback callback) {
        String title = defaultTitle;
        String content = fullContent;

        String jsonStr = extractJson(fullContent);
        if (jsonStr != null) {
            try {
                JSONObject parsed = new JSONObject(jsonStr);
                String parsedTitle = parsed.optString("title", null);
                if (parsedTitle != null && !parsedTitle.isEmpty()) {
                    title = parsedTitle;
                }
                if (parsed.has("cards")) {
                    content = parsed.getJSONArray("cards").toString();
                } else {
                    String parsedContent = parsed.optString("content", null);
                    if (parsedContent != null && !parsedContent.isEmpty()) {
                        content = parsedContent;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "parse summary failed", e);
            }
        }

        callback.onPartialResult(title, content, true);
    }

    private static String[] parseSummaryResult(String fullContent, String defaultTitle) {
        String jsonStr = extractJson(fullContent);
        if (jsonStr == null) return null;

        try {
            JSONObject parsed = new JSONObject(jsonStr);
            String title = parsed.optString("title", null);
            String content;

            if (parsed.has("cards")) {
                content = parsed.getJSONArray("cards").toString();
            } else {
                content = parsed.optString("content", null);
                if (content == null || content.isEmpty()) {
                    content = fullContent;
                }
            }

            return new String[]{
                    title != null && !title.isEmpty() ? title : defaultTitle,
                    content
            };
        } catch (Exception e) {
            return null;
        }
    }

    private static String[] parseDateResult(String jsonStr, String currentDate, String originalContent) {
        try {
            JSONObject parsed = new JSONObject(jsonStr);
            String parsedDate = parsed.optString("date", null);
            String parsedContent = parsed.optString("content", null);

            String date = currentDate;
            if (parsedDate != null) {
                String extracted = extractDate(parsedDate);
                if (extracted != null) {
                    date = extracted;
                } else {
                    String resolved = resolveDateWithoutYear(parsedDate, currentDate);
                    if (resolved != null) date = resolved;
                }
            }

            String cleanedContent = originalContent;
            if (parsedContent != null && !parsedContent.isEmpty()) {
                cleanedContent = parsedContent;
            }

            return new String[]{date, cleanedContent};
        } catch (Exception e) {
            String extracted = extractDate(jsonStr);
            if (extracted != null) {
                return new String[]{extracted, originalContent};
            }
            String resolved = resolveDateWithoutYear(jsonStr, currentDate);
            if (resolved != null) {
                return new String[]{resolved, originalContent};
            }
            return null;
        }
    }

    private static List<DateEntry> parseMultiDateResult(String fullContent, String currentDate, String originalContent) {
        List<DateEntry> entries = new ArrayList<>();
        String jsonStr = extractJson(fullContent);

        if (jsonStr != null) {
            try {
                JSONObject parsed = new JSONObject(jsonStr);
                JSONArray arr = parsed.optJSONArray("entries");
                if (arr != null && arr.length() > 0) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject item = arr.getJSONObject(i);
                        String date = currentDate;
                        String parsedDate = item.optString("date", null);
                        if (parsedDate != null) {
                            String extracted = extractDate(parsedDate);
                            if (extracted != null) {
                                date = extracted;
                            } else {
                                String resolved = resolveDateWithoutYear(parsedDate, currentDate);
                                if (resolved != null) date = resolved;
                            }
                        }
                        String parsedContent = item.optString("content", "");
                        if (!parsedContent.isEmpty()) {
                            DimensionData dimensions = parseDimensionData(item.optJSONObject("dimensions"));
                            entries.add(new DateEntry(date, parsedContent, dimensions));
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "parse multi date result failed", e);
            }
        }

        if (entries.isEmpty()) {
            String fallbackDate = extractDate(fullContent);
            if (fallbackDate == null) fallbackDate = currentDate;
            entries.add(new DateEntry(fallbackDate, originalContent));
        }

        return entries;
    }

    private static List<DateEntry> parseCachedEntries(String cacheValue, String currentDate) {
        List<DateEntry> entries = new ArrayList<>();
        try {
            String jsonStr = extractJson(cacheValue);
            if (jsonStr != null) {
                JSONObject parsed = new JSONObject(jsonStr);
                JSONArray arr = parsed.optJSONArray("entries");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject item = arr.getJSONObject(i);
                        String date = currentDate;
                        String parsedDate = item.optString("date", null);
                        if (parsedDate != null) {
                            String extracted = extractDate(parsedDate);
                            if (extracted != null) {
                                date = extracted;
                            } else {
                                String resolved = resolveDateWithoutYear(parsedDate, currentDate);
                                if (resolved != null) date = resolved;
                            }
                        }
                        String content = item.optString("content", "");
                        if (!content.isEmpty()) {
                            DimensionData dimensions = parseDimensionData(item.optJSONObject("dimensions"));
                            entries.add(new DateEntry(date, content, dimensions));
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "parse cached entries failed", e);
        }
        return entries;
    }

    /**
     * 解析 dimensions JSON 对象为 DimensionData。
     * 兼容旧格式：dimObj 为 null 时返回 null（旧缓存或 AI 未返回维度）。
     */
    private static DimensionData parseDimensionData(JSONObject dimObj) {
        if (dimObj == null) return null;

        Integer exScore = null;
        List<String> exTypes = null;
        String exExcerpt = null;
        Integer sleepScore = null;
        String sleepExcerpt = null;
        List<String> socialContacts = null;
        String socialExcerpt = null;

        try {
            JSONObject exObj = dimObj.optJSONObject("exercise");
            if (exObj != null) {
                if (!exObj.isNull("score") && exObj.has("score")) {
                    exScore = exObj.getInt("score");
                }
                JSONArray typesArr = exObj.optJSONArray("types");
                if (typesArr != null) {
                    exTypes = new ArrayList<>();
                    for (int i = 0; i < typesArr.length(); i++) {
                        exTypes.add(typesArr.getString(i));
                    }
                }
                if (!exObj.isNull("excerpt") && exObj.has("excerpt")) {
                    exExcerpt = exObj.getString("excerpt");
                }
            }

            JSONObject sleepObj = dimObj.optJSONObject("sleep");
            if (sleepObj != null) {
                if (!sleepObj.isNull("score") && sleepObj.has("score")) {
                    sleepScore = sleepObj.getInt("score");
                }
                if (!sleepObj.isNull("excerpt") && sleepObj.has("excerpt")) {
                    sleepExcerpt = sleepObj.getString("excerpt");
                }
            }

            JSONObject socialObj = dimObj.optJSONObject("social");
            if (socialObj != null) {
                JSONArray contactsArr = socialObj.optJSONArray("contacts");
                if (contactsArr != null) {
                    socialContacts = new ArrayList<>();
                    for (int i = 0; i < contactsArr.length(); i++) {
                        socialContacts.add(contactsArr.getString(i));
                    }
                }
                if (!socialObj.isNull("excerpt") && socialObj.has("excerpt")) {
                    socialExcerpt = socialObj.getString("excerpt");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "parseDimensionData failed", e);
        }

        // 三维度全空则返回 null，避免写入无意义的空记录
        if (exScore == null && sleepScore == null
                && (socialContacts == null || socialContacts.isEmpty())) {
            return null;
        }
        return new DimensionData(exScore, exTypes, exExcerpt,
                sleepScore, sleepExcerpt, socialContacts, socialExcerpt);
    }

    private static String buildCacheValue(List<DateEntry> entries) {
        try {
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (DateEntry entry : entries) {
                JSONObject item = new JSONObject();
                item.put("date", entry.date);
                item.put("content", entry.content);
                if (entry.dimensions != null) {
                    item.put("dimensions", buildDimensionJson(entry.dimensions));
                }
                arr.put(item);
            }
            root.put("entries", arr);
            return root.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static JSONObject buildDimensionJson(DimensionData dim) throws Exception {
        JSONObject dimObj = new JSONObject();
        if (dim.exerciseScore != null || dim.exerciseTypes != null || dim.exerciseExcerpt != null) {
            JSONObject exObj = new JSONObject();
            if (dim.exerciseScore != null) exObj.put("score", dim.exerciseScore);
            if (dim.exerciseTypes != null) {
                JSONArray typesArr = new JSONArray();
                for (String t : dim.exerciseTypes) typesArr.put(t);
                exObj.put("types", typesArr);
            }
            if (dim.exerciseExcerpt != null) exObj.put("excerpt", dim.exerciseExcerpt);
            dimObj.put("exercise", exObj);
        }
        if (dim.sleepScore != null || dim.sleepExcerpt != null) {
            JSONObject sleepObj = new JSONObject();
            if (dim.sleepScore != null) sleepObj.put("score", dim.sleepScore);
            if (dim.sleepExcerpt != null) sleepObj.put("excerpt", dim.sleepExcerpt);
            dimObj.put("sleep", sleepObj);
        }
        if (dim.socialContacts != null || dim.socialExcerpt != null) {
            JSONObject socialObj = new JSONObject();
            if (dim.socialContacts != null) {
                JSONArray contactsArr = new JSONArray();
                for (String c : dim.socialContacts) contactsArr.put(c);
                socialObj.put("contacts", contactsArr);
            }
            if (dim.socialExcerpt != null) socialObj.put("excerpt", dim.socialExcerpt);
            dimObj.put("social", socialObj);
        }
        return dimObj;
    }

    private static String sha256(String input) {
        if (input == null) return "null";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format(Locale.US, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            Log.w(TAG, "SHA-256 not available, fallback to hashCode", e);
            return String.valueOf(input.hashCode());
        }
    }

    private static String stripInsightBlocks(String text) {
        if (text == null) return null;
        String result = text;
        // Remove INSIGHT_JSON block (including partial: start marker without end marker)
        int start = result.indexOf("```INSIGHT_JSON");
        if (start != -1) {
            int end = result.indexOf("```/INSIGHT_JSON", start);
            if (end != -1) {
                end += "```/INSIGHT_JSON".length();
                result = result.substring(0, start) + result.substring(end);
            } else {
                // Partial block during streaming — strip from start marker to end
                result = result.substring(0, start);
            }
        }
        return result;
    }

    private static String extractBlock(String text, String blockName) {
        String startMarker = "```" + blockName;
        String endMarker = "```/" + blockName;
        int start = text.indexOf(startMarker);
        if (start == -1) return null;
        start = text.indexOf('\n', start);
        if (start == -1) return null;
        start++; // skip the newline
        int end = text.indexOf(endMarker, start);
        if (end == -1) return null;
        String content = text.substring(start, end).trim();
        // Validate it's parseable JSON
        String jsonStr = extractJson(content);
        return jsonStr != null ? jsonStr : content;
    }

    private static String extractJson(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        if (start == -1) return null;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static String extractDate(String text) {
        Matcher m1 = DATE_PATTERN_1.matcher(text);
        if (m1.find()) {
            return String.format(Locale.US, "%04d-%02d-%02d",
                    Integer.parseInt(m1.group(1)), Integer.parseInt(m1.group(2)), Integer.parseInt(m1.group(3)));
        }

        Matcher m2 = DATE_PATTERN_2.matcher(text);
        if (m2.find()) {
            return String.format(Locale.US, "%04d-%02d-%02d",
                    Integer.parseInt(m2.group(1)), Integer.parseInt(m2.group(2)), Integer.parseInt(m2.group(3)));
        }

        Matcher m3 = DATE_PATTERN_3.matcher(text);
        if (m3.find()) {
            return String.format(Locale.US, "%04d-%02d-%02d",
                    Integer.parseInt(m3.group(3)), Integer.parseInt(m3.group(1)), Integer.parseInt(m3.group(2)));
        }

        Matcher m4 = DATE_PATTERN_4.matcher(text);
        if (m4.find()) {
            return String.format(Locale.US, "%04d-%02d-%02d",
                    Integer.parseInt(m4.group(1)), Integer.parseInt(m4.group(2)), Integer.parseInt(m4.group(3)));
        }

        Log.w(TAG, "extractDate: no date pattern matched in: " + text);
        return null;
    }

    private static String resolveDateWithoutYear(String text, String currentDate) {
        try {
            String year = currentDate.substring(0, 4);

            Matcher m1 = DATE_NO_YEAR_PATTERN_1.matcher(text);
            if (m1.find()) {
                return String.format(Locale.US, "%s-%02d-%02d",
                        year, Integer.parseInt(m1.group(1)), Integer.parseInt(m1.group(2)));
            }

            Matcher m2 = DATE_NO_YEAR_PATTERN_2.matcher(text);
            if (m2.find()) {
                int month = Integer.parseInt(m2.group(1));
                int day = Integer.parseInt(m2.group(2));
                if (month >= 1 && month <= 12 && day >= 1 && day <= 31) {
                    return String.format(Locale.US, "%s-%02d-%02d", year, month, day);
                }
            }

            Matcher m3 = DATE_NO_YEAR_PATTERN_3.matcher(text);
            if (m3.find()) {
                int month = Integer.parseInt(m3.group(1));
                int day = Integer.parseInt(m3.group(2));
                if (month >= 1 && month <= 12 && day >= 1 && day <= 31) {
                    return String.format(Locale.US, "%s-%02d-%02d", year, month, day);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "resolveDateWithoutYear failed", e);
        }
        return null;
    }

    private static String readErrorMessage(Response response) {
        return AIHttpUtils.readErrorMessage(response);
    }
}
