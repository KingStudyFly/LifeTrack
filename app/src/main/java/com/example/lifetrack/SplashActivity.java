package com.example.lifetrack;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.List;

public class SplashActivity extends AppCompatActivity {

    private static final long MIN_DISPLAY_MS = 2000L;
    // 静态字段保存进程内首次启动时间戳，旋转后 onCreate 重建时基于真实启动时间计算剩余显示时长，
    // 避免每次旋转都重新计时导致总显示时间翻倍
    private static long sAppStartMs = 0L;

    private Handler splashHandler;
    private boolean navigated = false;
    private long startTimeMs = 0L;
    private final List<Animator> animators = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 在 super.onCreate 之前应用主题，避免首个 Activity 因主题切换产生闪屏
        ThemeHelper.init(this);
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_splash);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        KeyManager.init(this);
        // 使用静态启动时间戳，旋转后基于真实启动时间计算剩余显示时长。
        // savedInstanceState == null 表示首次启动（含进程内重新打开 Splash），重置时间戳；
        // 旋转重建时 savedInstanceState != null，复用静态值。
        if (savedInstanceState == null) {
            sAppStartMs = System.currentTimeMillis();
        }
        startTimeMs = sAppStartMs;

        View ivIcon = findViewById(R.id.ivSplashIcon);
        View tvAppName = findViewById(R.id.tvAppName);
        View tvTagline = findViewById(R.id.tvTagline);

        if (ivIcon != null) {
            ivIcon.setAlpha(0f);
            ivIcon.setScaleX(0.8f);
            ivIcon.setScaleY(0.8f);
            ObjectAnimator iconScaleX = createAndTrack(ObjectAnimator.ofFloat(ivIcon, "scaleX", 0.8f, 1f));
            ObjectAnimator iconScaleY = createAndTrack(ObjectAnimator.ofFloat(ivIcon, "scaleY", 0.8f, 1f));
            ObjectAnimator iconAlpha = createAndTrack(ObjectAnimator.ofFloat(ivIcon, "alpha", 0f, 1f));
            int iconAnimDuration = getResources().getInteger(R.integer.splash_icon_anim_duration);
            iconScaleX.setDuration(iconAnimDuration);
            iconScaleY.setDuration(iconAnimDuration);
            iconAlpha.setDuration(iconAnimDuration);
            iconScaleX.setInterpolator(new AccelerateDecelerateInterpolator());
            iconScaleY.setInterpolator(new AccelerateDecelerateInterpolator());
            iconScaleX.start();
            iconScaleY.start();
            iconAlpha.start();
        }

        if (tvAppName != null) {
            tvAppName.setAlpha(0f);
            tvAppName.setTranslationY(20f);
        }
        if (tvTagline != null) {
            tvTagline.setAlpha(0f);
            tvTagline.setTranslationY(20f);
        }

        splashHandler = new Handler(Looper.getMainLooper());
        int textAnimDuration = getResources().getInteger(R.integer.splash_text_anim_duration);
        int nameDelay = getResources().getInteger(R.integer.splash_text_name_delay);
        int taglineDelay = getResources().getInteger(R.integer.splash_text_tagline_delay);
        if (tvAppName != null) {
            splashHandler.postDelayed(() -> {
                ObjectAnimator nameAlpha = createAndTrack(ObjectAnimator.ofFloat(tvAppName, "alpha", 0f, 1f));
                ObjectAnimator nameTransY = createAndTrack(ObjectAnimator.ofFloat(tvAppName, "translationY", 20f, 0f));
                nameAlpha.setDuration(textAnimDuration);
                nameTransY.setDuration(textAnimDuration);
                nameAlpha.start();
                nameTransY.start();
            }, nameDelay);
        }

        if (tvTagline != null) {
            splashHandler.postDelayed(() -> {
                ObjectAnimator tagAlpha = createAndTrack(ObjectAnimator.ofFloat(tvTagline, "alpha", 0f, 1f));
                ObjectAnimator tagTransY = createAndTrack(ObjectAnimator.ofFloat(tvTagline, "translationY", 20f, 0f));
                tagAlpha.setDuration(textAnimDuration);
                tagTransY.setDuration(textAnimDuration);
                tagAlpha.start();
                tagTransY.start();
            }, taglineDelay);
        }

        if (savedInstanceState == null) {
            // 首次启动：按约定延迟跳转
            int navigateDelay = getResources().getInteger(R.integer.splash_navigate_delay);
            splashHandler.postDelayed(this::navigateToMain, navigateDelay);
        } else {
            // 旋转恢复：基于真实启动时间立即触发，由 navigateToMain 内 MIN_DISPLAY_MS 检查决定何时真正跳转
            navigateToMain();
        }
    }

    private ObjectAnimator createAndTrack(ObjectAnimator animator) {
        animators.add(animator);
        return animator;
    }

    private void navigateToMain() {
        if (navigated) return;
        if (isFinishing() || isDestroyed()) return;
        // 防御性检查：保证最短显示时间。startTimeMs 基于静态 sAppStartMs，
        // 旋转后已使用真实启动时间，此处检查主要兜底极端时序（如 Handler 延迟早于 MIN_DISPLAY_MS），
        // 避免在动画未播完时跳转。正常路径下首次调用时 elapsed 已 >= MIN_DISPLAY_MS，分支不进入。
        long elapsed = System.currentTimeMillis() - startTimeMs;
        if (elapsed < MIN_DISPLAY_MS) {
            if (splashHandler != null) {
                splashHandler.postDelayed(this::navigateToMain, MIN_DISPLAY_MS - elapsed);
            }
            return;
        }
        navigated = true;
        // TODO: 登录态判断未实现
        // KeyManager 已被改为硬编码版本（无服务端登录态），工程中也无 ApiClient.isLoggedIn 之类方法。
        // 如后续接入登录态：未登录应跳 LoginActivity，已登录跳 MainActivity。
        Intent intent = new Intent(SplashActivity.this, MainActivity.class);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 暂停动画避免后台浪费 CPU，onResume 恢复
        for (Animator animator : animators) {
            if (animator != null && animator.isRunning()) {
                animator.pause();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (Animator animator : animators) {
            if (animator != null && animator.isPaused()) {
                animator.resume();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (splashHandler != null) {
            splashHandler.removeCallbacksAndMessages(null);
            splashHandler = null;
        }
        // 取消所有正在运行的 Animator，避免持有 View 引用导致内存泄漏
        for (Animator animator : animators) {
            if (animator != null && animator.isRunning()) {
                animator.cancel();
            }
        }
        animators.clear();
        super.onDestroy();
    }
}
