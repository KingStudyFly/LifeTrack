package com.example.lifetrack;

import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class DoubaoClient {

    private static final String TAG = "DoubaoClient";
    private static final String BASE_URL = "https://ark.cn-beijing.volces.com/api/v3";
    private static final String VISION_MODEL = "ep-20260521220024-2mnlc";
    private static final String AUDIO_MODEL = "ep-20260526095629-d6gwd";

    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private static final String VISION_SYSTEM_PROMPT =
            "你是一个专业的图像内容分析助手，专门负责对用户提交的图片进行精准的文字提取。\n\n" +
            "## 文字提取规则\n\n" +
            "当图片中包含文字内容时（包括但不限于：手写笔记、打印文档、屏幕截图、产品标签、书信、报纸、" +
            "书籍页面、幻灯片演示、数据表格、图表标注、广告海报、菜单、路标、证件等），你需要：\n\n" +
            "1. 完整准确地提取图片中的" +
                    "所有文字内容，不遗漏任何可见文字\n" +
            "2. 严格保持原文的格式和结构，包括段落划分、换行位置、缩进层次\n" +
            "3. 对于标题、列表、编号等格式化内容，保持原有的格式标记\n" +
            "4. 对于表格内容，尽量保持表格的行列结构\n" +
            "5. 如果文字模糊、部分遮挡或难以辨认，根据上下文合理推断，无法辨认的部分用[?]标注\n" +
            "6. 对于数学公式、化学方程式等专业内容，尽量保持原有的符号和格式\n" +
            "7. 不要对提取的文字进行任何修改、纠正或润色\n" +
            "8. 对于含有外语的图片，保持原文提取，不要翻译\n" +
            "9. 对于含有代码的图片，保持代码的缩进和格式\n\n" +
            "## 场景描述规则\n\n" +
            "当图片中不以文字为主体，而是展示一个场景或物体时（包括但不限于：自然风景、城市街景、人物肖像、" +
            "建筑外观、动物、植物、食物、物品、艺术品等），你需要：\n\n" +
            "1. 用简洁生动的语言描述图片中的主要内容\n" +
            "2. 重点描述最突出的视觉元素和关键细节\n" +
            "3. 如果图片中有多个元素，按照从主要到次要的顺序描述\n" +
            "4. 保持描述的客观性，避免过度解读或添加主观判断\n" +
            "5. 描述长度适中，通常2-3句话即可\n\n" +
            "## 常见场景处理指南\n\n" +
            "### 文档类图片\n" +
            "- 身份证件：提取姓名、证件号码等关键信息，保持原文格式\n" +
            "- 发票收据：提取日期、金额、商品名称、商家信息等\n" +
            "- 名片：提取姓名、职位、公司、联系方式等\n" +
            "- 试卷作业：完整提取题目和作答内容，保持排版\n\n" +
            "### 屏幕截图类\n" +
            "- 聊天记录：提取对话内容，保持对话格式\n" +
            "- 网页截图：提取主要文字内容\n" +
            "- 代码截图：完整提取代码，严格保持缩进和换行\n" +
            "- 应用界面：描述界面布局和主要可见元素\n\n" +
            "### 生活场景类\n" +
            "- 食物照片：描述食物种类、外观特征和摆盘\n" +
            "- 风景照片：描述场景的主要构成和整体氛围\n" +
            "- 人物照片：描述人物的动作、表情和穿着\n" +
            "- 活动场景：描述活动内容和参与者的行为\n\n" +
            "## 输出格式要求\n\n" +
            "1. 只输出提取的文字内容或场景描述\n" +
            "2. 不要添加任何前缀说明（如\"这张图片显示\"、\"图片中的文字是\"等）\n" +
            "3. 不要在输出末尾添加总结、评价或额外说明\n" +
            "4. 直接输出核心内容，保持简洁\n" +
            "5. 不要输出任何与图片内容无关的文字";

    public interface VisionCallback {
        void onResult(String text);
        void onFailure(String message);
    }

    public interface VisionStreamCallback {
        void onPartialResult(String text, boolean isComplete);
        void onFailure(String message);
    }

    public interface BatchVisionCallback {
        void onResults(List<String> texts);
        void onFailure(String message);
    }

    public interface BatchVisionStreamCallback {
        void onPartialResults(List<String> texts, int completedCount, int totalCount, boolean isComplete);
        void onFailure(String message);
    }

    public interface VerifyCallback {
        void onSuccess();
        void onFailure(String message);
    }

    public interface AsrCallback {
        void onResult(String text);
        void onFailure(String message);
    }

    private static Request buildSsePostRequest(String url, JSONObject body) throws Exception {
        RequestBody requestBody = RequestBody.create(body.toString(), JSON_TYPE);
        return new Request.Builder()
                .url(url)
                .post(requestBody)
                .header("Authorization", "Bearer " + KeyManager.getDoubaoKey())
                .header("Accept", "text/event-stream")
                .header("Connection", "keep-alive")
                .build();
    }

    private static Request buildPostRequest(String url, JSONObject body) throws Exception {
        RequestBody requestBody = RequestBody.create(body.toString(), JSON_TYPE);
        return new Request.Builder()
                .url(url)
                .post(requestBody)
                .header("Authorization", "Bearer " + KeyManager.getDoubaoKey())
                .header("Connection", "keep-alive")
                .build();
    }

    private static JSONArray buildVisionMessages(String base64Image) throws Exception {
        JSONObject systemMsg = new JSONObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", VISION_SYSTEM_PROMPT);

        JSONObject textPart = new JSONObject();
        textPart.put("type", "text");
        textPart.put("text", "请分析这张图片。");

        JSONObject imagePart = new JSONObject();
        imagePart.put("type", "image_url");
        JSONObject imageUrl = new JSONObject();
        imageUrl.put("url", "data:image/jpeg;base64," + base64Image);
        imagePart.put("image_url", imageUrl);

        JSONArray contentArray = new JSONArray();
        contentArray.put(textPart);
        contentArray.put(imagePart);

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", contentArray);

        JSONArray messages = new JSONArray();
        messages.put(systemMsg);
        messages.put(userMsg);
        return messages;
    }

    private static String parseChatSSEStream(Response response, StringBuilder fullContent,
            VisionStreamCallback streamCallback) throws Exception {
        if (response.body() == null) return fullContent.toString().trim();

        AIHttpUtils.readSSEStream(response, chunk -> {
            fullContent.append(chunk);
            if (streamCallback != null) {
                streamCallback.onPartialResult(fullContent.toString().trim(), false);
            }
        });
        return fullContent.toString().trim();
    }

    private static String parseChatSSEStreamSimple(Response response, StringBuilder fullContent) throws Exception {
        if (response.body() == null) return fullContent.toString().trim();

        AIHttpUtils.readSSEStream(response, fullContent::append);
        return fullContent.toString().trim();
    }

    public static void verifyConnection(VerifyCallback callback) {
        AppExecutors.network().execute(() -> {
            try {
                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", "hi");

                JSONArray messages = new JSONArray();
                messages.put(userMsg);

                JSONObject requestBody = new JSONObject();
                requestBody.put("model", VISION_MODEL);
                requestBody.put("messages", messages);
                requestBody.put("max_tokens", 1);

                Request request = buildPostRequest(BASE_URL + "/chat/completions", requestBody);

                OkHttpClient client = OkHttpHelper.getSse();
                try (Response response = client.newCall(request).execute()) {
                    if (response.code() == 200) {
                        Log.d(TAG, "verifyConnection: success");
                        if (callback != null) callback.onSuccess();
                    } else {
                        String errorMsg = readErrorMessage(response);
                        Log.w(TAG, "verifyConnection: failed, " + errorMsg);
                        if (callback != null) callback.onFailure(errorMsg);
                    }
                }
            } catch (UnknownHostException e) {
                Log.e(TAG, "verifyConnection: DNS resolution failed", e);
                if (callback != null) callback.onFailure("无法连接到豆包服务器，请检查网络");
            } catch (java.net.SocketTimeoutException e) {
                Log.e(TAG, "verifyConnection: timeout", e);
                if (callback != null) callback.onFailure("豆包连接超时，请检查网络后重试");
            } catch (Exception e) {
                Log.e(TAG, "verifyConnection: exception", e);
                if (callback != null) callback.onFailure("豆包连接失败：" + e.getMessage());
            }
        });
    }

    @SuppressWarnings("unused")
    public static void analyzeImage(Bitmap bitmap, VisionCallback callback) {
        analyzeImageStream(bitmap, new VisionStreamCallback() {
            @Override
            public void onPartialResult(String text, boolean isComplete) {
                if (isComplete) {
                    callback.onResult(text);
                }
            }

            @Override
            public void onFailure(String message) {
                callback.onFailure(message);
            }
        });
    }

    public static void analyzeImageStream(Bitmap bitmap, VisionStreamCallback callback) {
        AppExecutors.network().execute(() -> {
            try {
                String base64Image = bitmapToBase64(bitmap);
                analyzeImageStreamInternal(base64Image, callback);
            } catch (UnknownHostException e) {
                Log.e(TAG, "analyzeImageStream: DNS resolution failed", e);
                callback.onFailure("无法连接到服务器，请检查网络");
            } catch (java.net.SocketTimeoutException e) {
                Log.e(TAG, "analyzeImageStream: timeout", e);
                callback.onFailure("图片分析超时，请检查网络后重试");
            } catch (Exception e) {
                Log.e(TAG, "analyzeImageStream: exception", e);
                callback.onFailure("图片分析失败：" + e.getMessage());
            }
        });
    }

    public static void analyzeImageStreamBase64(String base64Image, VisionStreamCallback callback) {
        AppExecutors.network().execute(() -> {
            try {
                analyzeImageStreamInternal(base64Image, callback);
            } catch (UnknownHostException e) {
                Log.e(TAG, "analyzeImageStreamBase64: DNS resolution failed", e);
                callback.onFailure("无法连接到服务器，请检查网络");
            } catch (java.net.SocketTimeoutException e) {
                Log.e(TAG, "analyzeImageStreamBase64: timeout", e);
                callback.onFailure("图片分析超时，请检查网络后重试");
            } catch (Exception e) {
                Log.e(TAG, "analyzeImageStreamBase64: exception", e);
                callback.onFailure("图片分析失败：" + e.getMessage());
            }
        });
    }

    private static void analyzeImageStreamInternal(String base64Image, VisionStreamCallback callback) throws Exception {
        JSONArray messages = buildVisionMessages(base64Image);

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", VISION_MODEL);
        requestBody.put("messages", messages);
        requestBody.put("stream", true);
        requestBody.put("temperature", 0.1);
        requestBody.put("max_tokens", 1024);

        Request request = buildSsePostRequest(BASE_URL + "/chat/completions", requestBody);

        OkHttpClient client = OkHttpHelper.getSse();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 200) {
                StringBuilder fullContent = new StringBuilder();
                parseChatSSEStream(response, fullContent, callback);
                callback.onPartialResult(fullContent.toString().trim(), true);
            } else {
                String errorMsg = readErrorMessage(response);
                Log.w(TAG, "analyzeImageStreamInternal: failed, " + errorMsg);
                callback.onFailure(errorMsg);
            }
        }
    }

    @SuppressWarnings("unused")
    public static void analyzeMultipleImages(List<Bitmap> bitmaps, BatchVisionCallback callback) {
        List<String> base64Images = new ArrayList<>();
        for (Bitmap bitmap : bitmaps) {
            base64Images.add(bitmapToBase64(bitmap));
        }
        analyzeMultipleImagesConcurrent(base64Images, new BatchVisionStreamCallback() {
            @Override
            public void onPartialResults(List<String> texts, int completedCount, int totalCount, boolean isComplete) {
                if (isComplete) {
                    callback.onResults(texts);
                }
            }

            @Override
            public void onFailure(String message) {
                callback.onFailure(message);
            }
        });
    }

    public static void analyzeMultipleImagesConcurrent(List<String> base64Images, BatchVisionStreamCallback callback) {
        AppExecutors.network().execute(() -> {
            int total = base64Images.size();
            List<String> results = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                results.add("");
            }

            AtomicInteger completedCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(total);
            Object lock = new Object();
            // 标记是否已向调用方发送完成回调，防止超时后剩余任务继续回调破坏契约
            AtomicBoolean finished = new AtomicBoolean(false);

            for (int i = 0; i < total; i++) {
                final int index = i;
                final String base64Image = base64Images.get(i);

                AppExecutors.network().execute(() -> {
                    try {
                        JSONArray messages = buildVisionMessages(base64Image);

                        JSONObject requestBody = new JSONObject();
                        requestBody.put("model", VISION_MODEL);
                        requestBody.put("messages", messages);
                        requestBody.put("stream", true);
                        requestBody.put("temperature", 0.1);
                        requestBody.put("max_tokens", 1024);

                        Request request = buildSsePostRequest(BASE_URL + "/chat/completions", requestBody);

                        OkHttpClient client = OkHttpHelper.getLongTimeout();
                        try (Response response = client.newCall(request).execute()) {
                            if (response.code() == 200) {
                                StringBuilder fullContent = new StringBuilder();
                                parseChatSSEStreamSimple(response, fullContent);

                                String text = fullContent.toString().trim();
                                List<String> snapshot;
                                int done;
                                synchronized (lock) {
                                    if (!text.isEmpty()) {
                                        results.set(index, text);
                                    } else {
                                        failureCount.incrementAndGet();
                                        results.set(index, "【图片" + (index + 1) + "分析失败】");
                                    }
                                    done = completedCount.incrementAndGet();
                                    snapshot = new ArrayList<>(results);
                                }
                                if (!finished.get()) {
                                    callback.onPartialResults(snapshot, done, total, false);
                                }
                            } else {
                                String errorMsg = readErrorMessage(response);
                                Log.w(TAG, "analyzeImage " + index + " failed: " + errorMsg);
                                List<String> snapshot;
                                int done;
                                synchronized (lock) {
                                    failureCount.incrementAndGet();
                                    results.set(index, "【图片" + (index + 1) + "分析失败】");
                                    done = completedCount.incrementAndGet();
                                    snapshot = new ArrayList<>(results);
                                }
                                if (!finished.get()) {
                                    callback.onPartialResults(snapshot, done, total, false);
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "analyzeImage " + index + " exception", e);
                        List<String> snapshot;
                        int done;
                        synchronized (lock) {
                            failureCount.incrementAndGet();
                            results.set(index, "【图片" + (index + 1) + "分析失败】");
                            done = completedCount.incrementAndGet();
                            snapshot = new ArrayList<>(results);
                        }
                        if (!finished.get()) {
                            callback.onPartialResults(snapshot, done, total, false);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            try {
                boolean completed = latch.await(180, TimeUnit.SECONDS);
                if (!completed) {
                    Log.w(TAG, "analyzeMultipleImagesConcurrent: latch await timed out");
                }

                int failures = failureCount.get();
                if (failures > 0) {
                    Log.w(TAG, "analyzeMultipleImagesConcurrent: " + failures + " images failed");
                }

                int actualCompleted = completedCount.get();
                List<String> finalSnapshot;
                synchronized (lock) {
                    finished.set(true);
                    finalSnapshot = new ArrayList<>(results);
                }
                callback.onPartialResults(finalSnapshot, actualCompleted, total, true);
            } catch (InterruptedException e) {
                Log.e(TAG, "analyzeMultipleImagesConcurrent: interrupted", e);
                Thread.currentThread().interrupt();
                synchronized (lock) {
                    finished.set(true);
                }
                callback.onFailure("分析超时");
            }
        });
    }

    public static void recognizeAudio(byte[] audioData, String format, AsrCallback callback) {
        if (format == null) {
            throw new IllegalArgumentException("format cannot be null");
        }
        if (!format.equals("mp4") && !format.equals("wav") && !format.equals("mp3")
                && !format.equals("aac") && !format.equals("m4a")) {
            throw new IllegalArgumentException("Unsupported audio format: " + format);
        }
        AppExecutors.network().execute(() -> {
            try {
                String base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP);

                JSONObject textPart = new JSONObject();
                textPart.put("type", "input_text");
                textPart.put("text", "请将这段音频中的语音内容完整准确地转换为文字，只输出转写结果，不要添加任何前缀说明。");

                JSONObject audioPart = new JSONObject();
                audioPart.put("type", "input_audio");
                audioPart.put("audio_url", "data:audio/" + format + ";base64," + base64Audio);

                JSONArray contentArray = new JSONArray();
                contentArray.put(textPart);
                contentArray.put(audioPart);

                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", contentArray);

                JSONArray inputArray = new JSONArray();
                inputArray.put(userMsg);

                JSONObject requestBody = new JSONObject();
                requestBody.put("model", AUDIO_MODEL);
                requestBody.put("input", inputArray);
                requestBody.put("temperature", 0.1);
                requestBody.put("max_output_tokens", 2048);

                Log.d(TAG, "recognizeAudio: sending request to Responses API, audio size=" + audioData.length
                        + " bytes, base64 length=" + base64Audio.length());

                Request request = buildPostRequest(BASE_URL + "/responses", requestBody);

                OkHttpClient client = OkHttpHelper.getSse();
                try (Response response = client.newCall(request).execute()) {
                    Log.d(TAG, "recognizeAudio: response code=" + response.code());
                    if (response.code() == 200) {
                        String responseBody = AIHttpUtils.readBodyString(response);

                        String text = "";
                        try {
                            JSONObject responseJson = new JSONObject(responseBody);
                            JSONArray output = responseJson.optJSONArray("output");
                            if (output != null) {
                                StringBuilder textBuilder = new StringBuilder();
                                for (int i = 0; i < output.length(); i++) {
                                    JSONObject item = output.optJSONObject(i);
                                    if (item != null && "message".equals(item.optString("type"))) {
                                        JSONArray content = item.optJSONArray("content");
                                        if (content != null) {
                                            for (int j = 0; j < content.length(); j++) {
                                                JSONObject contentItem = content.optJSONObject(j);
                                                if (contentItem != null && "output_text".equals(contentItem.optString("type"))) {
                                                    textBuilder.append(contentItem.optString("text", ""));
                                                }
                                            }
                                        }
                                    }
                                }
                                text = textBuilder.toString();
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "recognizeAudio: parse response error, raw=" + responseBody, e);
                        }

                        text = text.trim();
                        Log.d(TAG, "recognizeAudio: result length=" + text.length()
                                + ", text=" + (text.length() > 200 ? text.substring(0, 200) + "..." : text));
                        if (callback != null) callback.onResult(text);
                    } else {
                        String errorMsg = readErrorMessage(response);
                        Log.w(TAG, "recognizeAudio: failed, code=" + response.code() + ", error=" + errorMsg);
                        if (callback != null) callback.onFailure(errorMsg);
                    }
                }
            } catch (UnknownHostException e) {
                Log.e(TAG, "recognizeAudio: DNS resolution failed", e);
                if (callback != null) callback.onFailure("无法连接到服务器，请检查网络");
            } catch (java.net.SocketTimeoutException e) {
                Log.e(TAG, "recognizeAudio: timeout", e);
                if (callback != null) callback.onFailure("语音识别超时，请检查网络后重试");
            } catch (Exception e) {
                Log.e(TAG, "recognizeAudio: exception", e);
                if (callback != null) callback.onFailure("语音识别失败：" + e.getMessage());
            }
        });
    }

    private static String bitmapToBase64(Bitmap bitmap) {
        return ImageUtils.bitmapToBase64(bitmap);
    }

    private static String readErrorMessage(Response response) {
        return AIHttpUtils.readErrorMessage(response);
    }
}
