package com.example.lifetrack;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * AI 客户端公共工具：SSE 解析、错误消息提取
 */
public final class AIHttpUtils {

    private static final String TAG = "AIHttpUtils";

    private AIHttpUtils() {}

    /**
     * SSE 流式响应解析接口
     */
    public interface SSEChunkHandler {
        /**
         * @param chunk 增量内容
         */
        void onChunk(String chunk);
    }

    /**
     * 解析 OpenAI 兼容的 SSE 流式响应，逐块回调
     */
    public static void readSSEStream(Response response, SSEChunkHandler handler) throws Exception {
        ResponseBody body = response.body();
        if (body == null) return;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8), 8192)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    try {
                        JSONObject json = new JSONObject(data);
                        JSONArray choices = json.optJSONArray("choices");
                        if (choices != null && choices.length() > 0) {
                            JSONObject first = choices.optJSONObject(0);
                            if (first == null) continue;
                            JSONObject delta = first.optJSONObject("delta");
                            if (delta != null) {
                                // 思考模式下 delta.content 可能为 JSON null，optString 会把 null 转成字面 "null"，
                                // 导致 UI 流式显示一串 null。isNull 同时覆盖 key 缺失与值为 null 两种情况。
                                String chunk = delta.isNull("content") ? "" : delta.optString("content", "");
                                if (!chunk.isEmpty()) {
                                    handler.onChunk(chunk);
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "parse SSE chunk error", e);
                    }
                }
            }
        }
    }

    /**
     * 安全读取 Response body 字符串，避免重复调用 response.body()。
     * body 为 null 或读取异常时返回空串。
     */
    public static String readBodyString(Response response) {
        try {
            ResponseBody body = response.body();
            return body != null ? body.string() : "";
        } catch (Exception e) {
            Log.w(TAG, "readBodyString: failed", e);
            return "";
        }
    }

    /**
     * 提取 HTTP 错误响应中的可读消息
     */
    public static String readErrorMessage(Response response) {
        int code = response.code();
        String errorMsg = "HTTP " + code;
        try {
            String bodyString = readBodyString(response);
            JSONObject errorJson = new JSONObject(bodyString);
            errorMsg = errorJson.getJSONObject("error").getString("message");
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse error response body", e);
        }

        if (code == 401) {
            errorMsg = "API Key 无效或已过期";
        } else if (code == 429) {
            errorMsg = "请求过于频繁，请稍后再试";
        }

        return errorMsg;
    }

    /**
     * 统一网络异常消息提取
     */
    public static String getNetworkErrorMessage(Exception e) {
        if (e instanceof java.net.UnknownHostException) {
            return "无法连接到服务器，请检查网络";
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return "连接超时，请检查网络后重试";
        }
        return e.getMessage() != null ? e.getMessage() : "未知错误";
    }
}
