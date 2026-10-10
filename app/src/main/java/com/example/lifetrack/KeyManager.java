package com.example.lifetrack;

import android.content.Context;
import android.util.Log;

/**
 * API Key 管理器
 *
 * API Key 由构建时从 local.properties 注入 BuildConfig（不进入版本库），
 * 避免明文 key 泄漏到代码仓库。在 local.properties 中配置
 * DEEPSEEK_API_KEY 与 DOUBAO_API_KEY 后重新构建即可生效。
 */
public class KeyManager {

    private static final String TAG = "KeyManager";

    private static final String DEEPSEEK_API_KEY = BuildConfig.DEEPSEEK_API_KEY;
    private static final String DOUBAO_API_KEY = BuildConfig.DOUBAO_API_KEY;

    /**
     * 兼容旧调用方（SplashActivity），无需网络初始化。
     */
    public static void init(Context context) {
        Log.d(TAG, "init: hardcoded keys ready");
    }

    public static String getDeepSeekKey() {
        return DEEPSEEK_API_KEY;
    }

    public static String getDoubaoKey() {
        return DOUBAO_API_KEY;
    }

    public static boolean isReady() {
        return true;
    }
}
