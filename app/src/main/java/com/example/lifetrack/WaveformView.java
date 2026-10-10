package com.example.lifetrack;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.Random;

/**
 * 录音时显示的动态波形可视化控件。
 * 多根竖条按随机高度上下波动，模拟声波效果。
 */
public class WaveformView extends View {

    private static final int BAR_COUNT = 5;
    private static final float BAR_WIDTH_RATIO = 0.4f;
    private static final float MIN_BAR_HEIGHT_RATIO = 0.15f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] targetHeights = new float[BAR_COUNT];
    private final float[] currentHeights = new float[BAR_COUNT];
    private final Random random = new Random();

    private ValueAnimator animator;
    private int barColor;

    public WaveformView(Context context) {
        super(context);
        init();
    }

    public WaveformView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public WaveformView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // TODO: 后续若需 XML 自定义 barColor，可在 attrs.xml 中声明并在此读取
        barColor = ContextCompat.getColor(getContext(), R.color.green_primary);
        paint.setColor(barColor);
        paint.setStyle(Paint.Style.FILL);
        // 波形为纯装饰性动画，对辅助功能用户无意义，避免被 TalkBack 误读为可交互内容
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        for (int i = 0; i < BAR_COUNT; i++) {
            targetHeights[i] = 0.3f;
            currentHeights[i] = 0.3f;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == VISIBLE) {
            startAnimation();
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        // 切到不可见时停止动画节省电量；切回可见且已 attached 时恢复
        if (visibility == VISIBLE && isAttachedToWindow()) {
            startAnimation();
        } else {
            stopAnimation();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAnimation();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 主题切换会触发 ConfigurationChange，重新读取颜色避免 barColor 仍指向旧主题
        barColor = ContextCompat.getColor(getContext(), R.color.green_primary);
        paint.setColor(barColor);
        invalidate();
    }

    private void startAnimation() {
        // 先清理可能残留的旧 animator（例如 onAttachedToWindow 多次触发）
        if (animator != null) {
            animator.removeAllListeners();
            animator.cancel();
            animator = null;
        }

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(120);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            for (int i = 0; i < BAR_COUNT; i++) {
                float diff = targetHeights[i] - currentHeights[i];
                currentHeights[i] += diff * 0.4f;
            }
            invalidate();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationRepeat(android.animation.Animator animation) {
                generateNewTargets();
            }
        });
        animator.start();
    }

    private void stopAnimation() {
        if (animator != null) {
            animator.removeAllListeners();
            animator.cancel();
            animator = null;
        }
    }

    private void generateNewTargets() {
        for (int i = 0; i < BAR_COUNT; i++) {
            targetHeights[i] = MIN_BAR_HEIGHT_RATIO + random.nextFloat() * (1f - MIN_BAR_HEIGHT_RATIO);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int left = getPaddingLeft();
        int top = getPaddingTop();
        int right = getWidth() - getPaddingRight();
        int bottom = getHeight() - getPaddingBottom();
        int width = right - left;
        int height = bottom - top;
        if (width <= 0 || height <= 0) return;

        float slotWidth = (float) width / BAR_COUNT;
        float barWidth = slotWidth * BAR_WIDTH_RATIO;
        float cornerRadius = barWidth / 2f;

        for (int i = 0; i < BAR_COUNT; i++) {
            float barHeight = currentHeights[i] * height;
            float barLeft = left + i * slotWidth + (slotWidth - barWidth) / 2f;
            float barTop = top + (height - barHeight) / 2f;
            float barRight = barLeft + barWidth;
            float barBottom = barTop + barHeight;

            canvas.drawRoundRect(barLeft, barTop, barRight, barBottom, cornerRadius, cornerRadius, paint);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int defaultWidth = getResources().getDimensionPixelSize(R.dimen.waveform_default_width);
        int defaultHeight = getResources().getDimensionPixelSize(R.dimen.waveform_default_height);

        int desiredWidth = Math.max(getSuggestedMinimumWidth(), defaultWidth) + getPaddingLeft() + getPaddingRight();
        int desiredHeight = Math.max(getSuggestedMinimumHeight(), defaultHeight) + getPaddingTop() + getPaddingBottom();

        setMeasuredDimension(resolveSize(desiredWidth, widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec));
    }
}
