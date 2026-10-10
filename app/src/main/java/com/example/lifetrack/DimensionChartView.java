package com.example.lifetrack;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 维度折线图：用于运动/睡眠 Tab 的月度波浪图。
 * 纵轴 0-100，横轴日期。
 * scores 中 null 元素表示当天无数据，画断点不连线。
 */
public class DimensionChartView extends View {

    private final List<Float> scores = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final int padLeftPx;
    private final int padRightPx;
    private final int padTopPx;
    private final int padBottomPx;
    private final int pointRadiusPx;

    public DimensionChartView(Context context) {
        this(context, null);
    }

    public DimensionChartView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DimensionChartView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        padLeftPx = dp(8);
        padRightPx = dp(8);
        padTopPx = dp(12);
        padBottomPx = dp(20);
        pointRadiusPx = dp(4);

        int lineColor = ContextCompat.getColor(context, R.color.brown_period_text);
        int fillColor = ContextCompat.getColor(context, R.color.brown_dropdown);
        int textColor = ContextCompat.getColor(context, R.color.text_subtitle);
        int hintColor = ContextCompat.getColor(context, R.color.text_hint);

        linePaint.setColor(lineColor);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        pointPaint.setColor(lineColor);
        pointPaint.setStyle(Paint.Style.FILL);

        fillPaint.setStyle(Paint.Style.FILL);

        labelPaint.setColor(textColor);
        labelPaint.setTextSize(sp(10));
        labelPaint.setTextAlign(Paint.Align.CENTER);

        valuePaint.setColor(lineColor);
        valuePaint.setTextSize(sp(9));
        valuePaint.setTextAlign(Paint.Align.CENTER);

        gridPaint.setColor(hintColor);
        gridPaint.setAlpha(40);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1);

        emptyTextPaint.setColor(textColor);
        emptyTextPaint.setTextSize(sp(13));
        emptyTextPaint.setTextAlign(Paint.Align.CENTER);

        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /**
     * 设置数据并刷新。
     * scores 和 labels 长度应一致；scores 元素为 null 表示当天无数据（断点）。
     */
    public void setScores(List<Float> newScores, List<String> newLabels) {
        scores.clear();
        labels.clear();
        if (newScores != null) {
            scores.addAll(newScores);
        }
        if (newLabels != null) {
            labels.addAll(newLabels);
        }
        invalidate();
    }

    public void clearData() {
        scores.clear();
        labels.clear();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredHeight = dp(180);
        int height = resolveSize(desiredHeight, heightMeasureSpec);
        setMeasuredDimension(resolveSize(dp(300), widthMeasureSpec), height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float chartLeft = padLeftPx;
        float chartRight = w - padRightPx;
        float chartTop = padTopPx;
        float chartBottom = h - padBottomPx;
        float chartWidth = chartRight - chartLeft;
        float chartHeight = chartBottom - chartTop;

        // 空状态
        if (scores.isEmpty()) {
            canvas.drawText("暂无数据，点击下方按钮读取过往日记", w / 2f, h / 2f, emptyTextPaint);
            return;
        }

        // 画 3 条参考网格线（25/50/75）
        for (int level : new int[]{25, 50, 75}) {
            float y = chartBottom - (level / 100f) * chartHeight;
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint);
        }

        int n = scores.size();
        if (n == 0) return;

        // 计算每个数据点 x 坐标
        float slotWidth = (n == 1) ? 0 : chartWidth / (n - 1);
        float[] xs = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = chartLeft + i * slotWidth;
        }

        // 画折线段（连续两个非 null 才连线）
        Path linePath = new Path();
        boolean pathStarted = false;
        for (int i = 0; i < n; i++) {
            Float s = scores.get(i);
            if (s == null) {
                pathStarted = false;
                continue;
            }
            float y = chartBottom - (s / 100f) * chartHeight;
            if (!pathStarted) {
                linePath.moveTo(xs[i], y);
                pathStarted = true;
            } else {
                linePath.lineTo(xs[i], y);
            }
        }
        canvas.drawPath(linePath, linePaint);

        // 画填充区域（折线下方淡色渐变）
        Path fillPath = new Path();
        boolean fillStarted = false;
        for (int i = 0; i < n; i++) {
            Float s = scores.get(i);
            if (s == null) {
                if (fillStarted) {
                    fillPath.lineTo(xs[i - 1], chartBottom);
                    fillPath.close();
                    fillStarted = false;
                }
                continue;
            }
            float y = chartBottom - (s / 100f) * chartHeight;
            if (!fillStarted) {
                fillPath.moveTo(xs[i], chartBottom);
                fillPath.lineTo(xs[i], y);
                fillStarted = true;
            } else {
                fillPath.lineTo(xs[i], y);
            }
        }
        if (fillStarted) {
            fillPath.lineTo(xs[n - 1], chartBottom);
            fillPath.close();
        }
        // 渐变填充
        int fillColor = ContextCompat.getColor(getContext(), R.color.brown_dropdown);
        fillPaint.setShader(new LinearGradient(0, chartTop, 0, chartBottom,
                adjustAlpha(fillColor, 0.5f), adjustAlpha(fillColor, 0.05f),
                Shader.TileMode.CLAMP));
        canvas.drawPath(fillPath, fillPaint);
        fillPaint.setShader(null);

        // 画数据点
        for (int i = 0; i < n; i++) {
            Float s = scores.get(i);
            if (s == null) continue;
            float y = chartBottom - (s / 100f) * chartHeight;
            canvas.drawCircle(xs[i], y, pointRadiusPx, pointPaint);
        }

        // 画 x 轴日期标签（最多显示 6 个，避免拥挤）
        int maxLabels = Math.min(6, n);
        int step = Math.max(1, n / maxLabels);
        for (int i = 0; i < n; i += step) {
            String label = labels.isEmpty() || i >= labels.size() ? "" : labels.get(i);
            if (label == null || label.isEmpty()) continue;
            // 标签可能为 yyyy-MM-dd，只显示 MM-dd
            String shortLabel = label.length() > 5 ? label.substring(5) : label;
            // 文本基线偏移，让标签贴在底部
            canvas.drawText(shortLabel, xs[i], chartBottom + dp(14), labelPaint);
        }
    }

    private int dp(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics());
    }

    private float sp(float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                getResources().getDisplayMetrics());
    }

    private int adjustAlpha(int color, float alpha) {
        int a = Math.round(Color.alpha(color) * alpha);
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }
}
