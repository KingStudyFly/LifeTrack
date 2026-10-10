package com.example.lifetrack;

import android.util.Log;

import java.util.concurrent.TimeUnit;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;

public class OkHttpHelper {

    private static final String TAG = "OkHttpHelper";
    private static volatile OkHttpClient defaultClient;
    private static volatile OkHttpClient sseClient;
    private static volatile OkHttpClient longTimeoutClient;

    private static final Interceptor LOGGING_INTERCEPTOR = chain -> {
        Request request = chain.request();
        long start = System.nanoTime();

        RequestBody reqBody = request.body();
        if (reqBody != null) {
            try (Buffer buffer = new Buffer()) {
                reqBody.writeTo(buffer);
                String reqStr = buffer.readUtf8();
                if (reqStr.length() > 1024) {
                    reqStr = reqStr.substring(0, 1024) + "...(truncated)";
                }
                Log.d(TAG, "--> " + request.method() + " " + request.url() + "\n" + reqStr);
            } catch (Exception e) {
                Log.d(TAG, "--> " + request.method() + " " + request.url() + " (body read failed)");
            }
        } else {
            Log.d(TAG, "--> " + request.method() + " " + request.url());
        }

        Response response;
        try {
            response = chain.proceed(request);
        } catch (Exception e) {
            // chain.proceed 抛 IOException（连接超时、DNS 失败等）时，若不捕获，
            // 日志只有 "-->" 没有 "<--"，调试时难以定位失败请求。
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            Log.d(TAG, "<-- [ERROR] " + request.method() + " " + request.url()
                    + " (" + elapsed + "ms) " + e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        String contentType = response.header("Content-Type", "");
        if (contentType.contains("text/event-stream")) {
            Log.d(TAG, "<-- " + response.code() + " " + request.url()
                    + " (" + elapsed + "ms) [SSE stream]");
        } else {
            try {
                String respStr = response.peekBody(1024).string();
                if (respStr.length() > 1024) {
                    respStr = respStr.substring(0, 1024) + "...(truncated)";
                }
                Log.d(TAG, "<-- " + response.code() + " " + request.url()
                        + " (" + elapsed + "ms)\n" + respStr);
            } catch (Exception e) {
                Log.d(TAG, "<-- " + response.code() + " " + request.url()
                        + " (" + elapsed + "ms) [body read failed]");
            }
        }

        return response;
    };

    private static OkHttpClient.Builder applyLogging(OkHttpClient.Builder builder) {
        if (BuildConfig.DEBUG) {
            builder.addInterceptor(LOGGING_INTERCEPTOR);
        }
        return builder;
    }

    public static OkHttpClient getDefault() {
        if (defaultClient == null) {
            synchronized (OkHttpHelper.class) {
                if (defaultClient == null) {
                    defaultClient = applyLogging(new OkHttpClient.Builder())
                            .connectTimeout(10, TimeUnit.SECONDS)
                            .readTimeout(10, TimeUnit.SECONDS)
                            .writeTimeout(10, TimeUnit.SECONDS)
                            .build();
                }
            }
        }
        return defaultClient;
    }

    public static OkHttpClient getSse() {
        if (sseClient == null) {
            synchronized (OkHttpHelper.class) {
                if (sseClient == null) {
                    sseClient = applyLogging(new OkHttpClient.Builder())
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(60, TimeUnit.SECONDS)
                            .writeTimeout(15, TimeUnit.SECONDS)
                            .build();
                }
            }
        }
        return sseClient;
    }

    public static OkHttpClient getLongTimeout() {
        if (longTimeoutClient == null) {
            synchronized (OkHttpHelper.class) {
                if (longTimeoutClient == null) {
                    longTimeoutClient = applyLogging(new OkHttpClient.Builder())
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(180, TimeUnit.SECONDS)
                            .writeTimeout(15, TimeUnit.SECONDS)
                            .build();
                }
            }
        }
        return longTimeoutClient;
    }
}
