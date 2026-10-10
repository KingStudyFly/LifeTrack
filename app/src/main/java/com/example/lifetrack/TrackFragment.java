package com.example.lifetrack;

import android.animation.ObjectAnimator;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.DatePicker;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class TrackFragment extends Fragment {

    private static final String TAG = "TrackFragment";

    private LinearLayout dropdownTrigger;
    private LinearLayout dropdownMenu;
    private LinearLayout contentArea;
    private LinearLayout summaryHeader;
    private LinearLayout cardsContainer;
    private TextView tvCurrentPeriod;
    private ImageView ivArrow;
    private TextView tvPeriodBadge;
    private TextView tvContentTitle;
    private TextView tvGeneratingProgress;
    private ImageView btnPrevPeriod;
    private ImageView btnNextPeriod;
    private TextView btnRegenerate;

    // Tab 标签条
    private TextView tabSummary;
    private TextView tabExercise;
    private TextView tabSleep;
    private TextView tabSocial;
    private View summaryTabContent;
    private View exerciseTabContent;
    private View sleepTabContent;
    private View socialTabContent;
    private static final int TAB_SUMMARY = 0;
    private static final int TAB_EXERCISE = 1;
    private static final int TAB_SLEEP = 2;
    private static final int TAB_SOCIAL = 3;
    private int selectedTab = TAB_SUMMARY;

    // 维度 Tab 相关 View
    private DimensionDao dimensionDao;
    private DimensionChartView exerciseChart;
    private DimensionChartView sleepChart;
    private ImageView btnPrevExercisePeriod;
    private TextView tvExercisePeriodBadge;
    private ImageView btnNextExercisePeriod;
    private ImageView btnPrevSleepPeriod;
    private TextView tvSleepPeriodBadge;
    private ImageView btnNextSleepPeriod;
    private LinearLayout exerciseTypesList;
    private TextView tvExerciseTypesEmpty;
    private TextView tvExerciseProgressContent;
    private LinearLayout sleepStatsList;
    private TextView tvSleepStatsEmpty;
    private TextView tvSleepFactorsContent;
    private LinearLayout socialContactsList;
    private TextView tvSocialEmpty;
    private TextView btnExerciseLoadHistory;
    private TextView btnSleepLoadHistory;
    private TextView btnSocialLoadHistory;

    // 维度月份偏移（运动/睡眠各自维护；社交固定近三个月）
    private Calendar exerciseMonthOffset;
    private Calendar sleepMonthOffset;
    private boolean isLoadingHistory = false;

    private volatile Calendar offsetCalendar;

    private boolean dropdownOpen = false;
    private boolean isAnimating = false;

    private final int[] itemIds = {
            R.id.itemDaily, R.id.itemWeekly, R.id.itemMonthly,
            R.id.itemQuarterly, R.id.itemYearly, R.id.itemCustom
    };

    private final String[] periodTypes = {
            "daily", "weekly", "monthly", "quarterly", "yearly", "custom"
    };

    private int selectedPosition = 0;
    private SummaryDao summaryDao;
    private Dialog generatingDialog;
    private TextView tvMsg;

    // 跟踪当前显示的 Picker Dialog，避免 Fragment 销毁时窗口泄漏
    private final java.util.List<Dialog> activeDialogs = new java.util.ArrayList<>();

    private String[] periodNames;

    private final ConcurrentHashMap<String, Boolean> preloadingKeys = new ConcurrentHashMap<>();
    // volatile 保证后台线程写入后主线程可见，用于校验回调时当前周期是否变化
    private volatile String currentPeriodKey = null;
    private volatile String currentPeriodType = null;
    private volatile String currentTitle = null;
    private volatile String currentContent = null;

    private String customStartDate = null;
    private String customEndDate = null;

    private static final String PREFS_TRACK = "lifetrack_track";
    private static final String KEY_CUSTOM_START = "custom_start_date";
    private static final String KEY_CUSTOM_END = "custom_end_date";

    private static final String STATE_SELECTED_POSITION = "state_selected_position";
    private static final String STATE_OFFSET_TIME = "state_offset_time";

    private InsightDao insightDao;
    private final List<ObjectAnimator> skeletonAnimators = new ArrayList<>();
    // Fragment 视图已销毁标志，用于阻止 onDestroyView 后的异步回调操作 View
    private volatile boolean isDestroyed = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_track, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // 视图重建时重置销毁标志
        isDestroyed = false;

        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_TRACK, Context.MODE_PRIVATE);
        customStartDate = prefs.getString(KEY_CUSTOM_START, null);
        customEndDate = prefs.getString(KEY_CUSTOM_END, null);

        periodNames = new String[]{
                getString(R.string.period_daily),
                getString(R.string.period_weekly),
                getString(R.string.period_monthly),
                getString(R.string.period_quarterly),
                getString(R.string.period_yearly),
                getString(R.string.period_custom)
        };

        if (savedInstanceState != null) {
            selectedPosition = savedInstanceState.getInt(STATE_SELECTED_POSITION, 0);
            long offsetTime = savedInstanceState.getLong(STATE_OFFSET_TIME, Long.MIN_VALUE);
            if (offsetTime != Long.MIN_VALUE) {
                Calendar restored = Calendar.getInstance(Locale.US);
                restored.setTimeInMillis(offsetTime);
                Calendar now = Calendar.getInstance(Locale.US);
                if (restored.after(now)) restored = now;
                offsetCalendar = restored;
            }
        }

        dropdownTrigger = view.findViewById(R.id.dropdownTrigger);
        dropdownMenu = view.findViewById(R.id.dropdownMenu);
        contentArea = view.findViewById(R.id.contentArea);
        summaryHeader = view.findViewById(R.id.summaryHeader);
        cardsContainer = view.findViewById(R.id.cardsContainer);
        tvCurrentPeriod = view.findViewById(R.id.tvCurrentPeriod);
        ivArrow = view.findViewById(R.id.ivArrow);
        tvPeriodBadge = view.findViewById(R.id.tvPeriodBadge);
        tvContentTitle = view.findViewById(R.id.tvContentTitle);
        btnPrevPeriod = view.findViewById(R.id.btnPrevPeriod);
        btnNextPeriod = view.findViewById(R.id.btnNextPeriod);
        btnRegenerate = view.findViewById(R.id.btnRegenerate);

        tabSummary = view.findViewById(R.id.tabSummary);
        tabExercise = view.findViewById(R.id.tabExercise);
        tabSleep = view.findViewById(R.id.tabSleep);
        tabSocial = view.findViewById(R.id.tabSocial);
        summaryTabContent = view.findViewById(R.id.summaryTabContent);
        exerciseTabContent = view.findViewById(R.id.exerciseTabContent);
        sleepTabContent = view.findViewById(R.id.sleepTabContent);
        socialTabContent = view.findViewById(R.id.socialTabContent);

        // 维度 Tab View 绑定
        exerciseChart = view.findViewById(R.id.exerciseChart);
        sleepChart = view.findViewById(R.id.sleepChart);
        btnPrevExercisePeriod = view.findViewById(R.id.btnPrevExercisePeriod);
        tvExercisePeriodBadge = view.findViewById(R.id.tvExercisePeriodBadge);
        btnNextExercisePeriod = view.findViewById(R.id.btnNextExercisePeriod);
        btnPrevSleepPeriod = view.findViewById(R.id.btnPrevSleepPeriod);
        tvSleepPeriodBadge = view.findViewById(R.id.tvSleepPeriodBadge);
        btnNextSleepPeriod = view.findViewById(R.id.btnNextSleepPeriod);
        exerciseTypesList = view.findViewById(R.id.exerciseTypesList);
        tvExerciseTypesEmpty = view.findViewById(R.id.tvExerciseTypesEmpty);
        tvExerciseProgressContent = view.findViewById(R.id.tvExerciseProgressContent);
        sleepStatsList = view.findViewById(R.id.sleepStatsList);
        tvSleepStatsEmpty = view.findViewById(R.id.tvSleepStatsEmpty);
        tvSleepFactorsContent = view.findViewById(R.id.tvSleepFactorsContent);
        socialContactsList = view.findViewById(R.id.socialContactsList);
        tvSocialEmpty = view.findViewById(R.id.tvSocialEmpty);
        btnExerciseLoadHistory = view.findViewById(R.id.btnExerciseLoadHistory);
        btnSleepLoadHistory = view.findViewById(R.id.btnSleepLoadHistory);
        btnSocialLoadHistory = view.findViewById(R.id.btnSocialLoadHistory);

        // 使用 ApplicationContext 避免持有 Activity Context 导致内存泄漏
        summaryDao = new SummaryDao(requireContext().getApplicationContext());
        insightDao = new InsightDao(requireContext().getApplicationContext());
        dimensionDao = new DimensionDao(requireContext().getApplicationContext());

        if (exerciseMonthOffset == null) {
            exerciseMonthOffset = Calendar.getInstance(Locale.US);
        }
        if (sleepMonthOffset == null) {
            sleepMonthOffset = Calendar.getInstance(Locale.US);
        }

        if (offsetCalendar == null) {
            offsetCalendar = Calendar.getInstance(Locale.US);
        }

        updatePeriodBadge();

        if (btnPrevPeriod != null) {
            btnPrevPeriod.setOnClickListener(v -> navigatePeriod(-1));
        }
        if (btnNextPeriod != null) {
            btnNextPeriod.setOnClickListener(v -> navigatePeriod(1));
        }
        if (tvPeriodBadge != null) {
            tvPeriodBadge.setOnClickListener(v -> showPeriodPicker());
        }
        if (btnRegenerate != null) {
            btnRegenerate.setOnClickListener(v -> confirmRegenerate());
        }

        if (dropdownTrigger != null) {
            dropdownTrigger.setOnClickListener(v -> {
                if (!isAnimating) toggleDropdown();
            });
        }

        for (int i = 0; i < itemIds.length; i++) {
            final int pos = i;
            LinearLayout item = view.findViewById(itemIds[i]);
            if (item == null) continue;
            item.setOnClickListener(v -> {
                if (!isAnimating) selectPeriod(pos);
            });
        }

        // Tab 切换：4 个标签横向滑动条
        if (tabSummary != null) {
            tabSummary.setOnClickListener(v -> selectTab(TAB_SUMMARY));
        }
        if (tabExercise != null) {
            tabExercise.setOnClickListener(v -> selectTab(TAB_EXERCISE));
        }
        if (tabSleep != null) {
            tabSleep.setOnClickListener(v -> selectTab(TAB_SLEEP));
        }
        if (tabSocial != null) {
            tabSocial.setOnClickListener(v -> selectTab(TAB_SOCIAL));
        }

        // 维度周期切换监听
        if (btnPrevExercisePeriod != null) {
            btnPrevExercisePeriod.setOnClickListener(v -> navigateDimensionMonth(TAB_EXERCISE, -1));
        }
        if (btnNextExercisePeriod != null) {
            btnNextExercisePeriod.setOnClickListener(v -> navigateDimensionMonth(TAB_EXERCISE, 1));
        }
        if (btnPrevSleepPeriod != null) {
            btnPrevSleepPeriod.setOnClickListener(v -> navigateDimensionMonth(TAB_SLEEP, -1));
        }
        if (btnNextSleepPeriod != null) {
            btnNextSleepPeriod.setOnClickListener(v -> navigateDimensionMonth(TAB_SLEEP, 1));
        }

        // 读取过往日记按钮
        if (btnExerciseLoadHistory != null) {
            btnExerciseLoadHistory.setOnClickListener(v -> confirmLoadHistory(TAB_EXERCISE));
        }
        if (btnSleepLoadHistory != null) {
            btnSleepLoadHistory.setOnClickListener(v -> confirmLoadHistory(TAB_SLEEP));
        }
        if (btnSocialLoadHistory != null) {
            btnSocialLoadHistory.setOnClickListener(v -> confirmLoadHistory(TAB_SOCIAL));
        }

        // 聚合分析按需触发
        if (tvExerciseProgressContent != null) {
            tvExerciseProgressContent.setOnClickListener(v -> loadExerciseProgress());
        }
        if (tvSleepFactorsContent != null) {
            tvSleepFactorsContent.setOnClickListener(v -> loadSleepFactors());
        }

        updateDropdownItemUI(false);
        loadPeriodSummary(selectedPosition, false);
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            if (dropdownMenu != null) {
                dropdownMenu.setVisibility(View.GONE);
                dropdownMenu.setScaleY(1f);
                dropdownMenu.setAlpha(1f);
                dropdownOpen = false;
            }
            loadPeriodSummary(selectedPosition, false);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_SELECTED_POSITION, selectedPosition);
        if (offsetCalendar != null) {
            outState.putLong(STATE_OFFSET_TIME, offsetCalendar.getTimeInMillis());
        }
    }

    @Override
    public void onDestroyView() {
        isDestroyed = true;
        // 预加载任务改用全局 AppExecutors.io()，无法 shutdown；
        // 任务内部已有 isAdded() 和 Thread.interrupted() 检查，销毁后会安全退出。
        cancelSkeletonAnimators();
        dismissGeneratingDialog();
        dismissAllPickerDialogs();
        // 清理预加载标记，避免重建后误判为已加载
        preloadingKeys.clear();
        // 清理 View 引用，避免异步回调持有已销毁的 View
        dropdownTrigger = null;
        dropdownMenu = null;
        contentArea = null;
        summaryHeader = null;
        cardsContainer = null;
        tvCurrentPeriod = null;
        ivArrow = null;
        tvPeriodBadge = null;
        tvContentTitle = null;
        tvGeneratingProgress = null;
        btnPrevPeriod = null;
        btnNextPeriod = null;
        btnRegenerate = null;
        tabSummary = null;
        tabExercise = null;
        tabSleep = null;
        tabSocial = null;
        summaryTabContent = null;
        exerciseTabContent = null;
        sleepTabContent = null;
        socialTabContent = null;
        exerciseChart = null;
        sleepChart = null;
        btnPrevExercisePeriod = null;
        tvExercisePeriodBadge = null;
        btnNextExercisePeriod = null;
        btnPrevSleepPeriod = null;
        tvSleepPeriodBadge = null;
        btnNextSleepPeriod = null;
        exerciseTypesList = null;
        tvExerciseTypesEmpty = null;
        tvExerciseProgressContent = null;
        sleepStatsList = null;
        tvSleepStatsEmpty = null;
        tvSleepFactorsContent = null;
        socialContactsList = null;
        tvSocialEmpty = null;
        btnExerciseLoadHistory = null;
        btnSleepLoadHistory = null;
        btnSocialLoadHistory = null;
        // periodNames 为纯字符串数组，不持有 View/Activity 引用，保留以避免异步回调 NPE
        super.onDestroyView();
    }

    private void dismissAllPickerDialogs() {
        // 先取快照再 clear，避免 dismiss 触发 OnDismissListener 调用 unregisterPickerDialog
        // 导致 ConcurrentModificationException
        java.util.List<Dialog> copy = new java.util.ArrayList<>(activeDialogs);
        activeDialogs.clear();
        for (Dialog d : copy) {
            if (d != null && d.isShowing()) {
                try {
                    d.dismiss();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void registerPickerDialog(Dialog dialog) {
        if (dialog == null) return;
        activeDialogs.add(dialog);
    }

    private void unregisterPickerDialog(Dialog dialog) {
        activeDialogs.remove(dialog);
    }

    /**
     * 安全地在主线程执行 UI 更新。
     * 检查 Fragment 是否仍附加且 Activity 未 finishing，避免 requireActivity() 抛异常。
     */
    private void runOnUiIfAlive(Runnable action) {
        if (isDestroyed || !isAdded()) return;
        android.app.Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) return;
        // 再次检查 isDestroyed，防止 post 到 UI 线程前 onDestroyView 已执行
        activity.runOnUiThread(() -> {
            if (!isDestroyed && isAdded()) {
                action.run();
            }
        });
    }

    private boolean isAiConnected() {
        return !DeepSeekClient.getApiKey().isEmpty();
    }

    private String getApiKey() {
        return DeepSeekClient.getApiKey();
    }

    private String[] getDateRange(int position) {
        Calendar cal = (Calendar) offsetCalendar.clone();
        SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

        String start, end;

        switch (position) {
            case 0:
                start = dayFmt.format(cal.getTime());
                cal.add(Calendar.DAY_OF_MONTH, 1);
                end = dayFmt.format(cal.getTime());
                break;
            case 1:
                // Locale.US 下 firstDayOfWeek=Sunday，若今日恰好是 Sunday，
                // set(MONDAY) 会跳到下周周一，导致查询范围错位。先回退一天避免此问题。
                if (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
                    cal.add(Calendar.DAY_OF_MONTH, -1);
                }
                cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
                start = dayFmt.format(cal.getTime());
                cal.add(Calendar.WEEK_OF_YEAR, 1);
                end = dayFmt.format(cal.getTime());
                break;
            case 2:
                cal.set(Calendar.DAY_OF_MONTH, 1);
                start = dayFmt.format(cal.getTime());
                cal.add(Calendar.MONTH, 1);
                end = dayFmt.format(cal.getTime());
                break;
            case 3:
                int quarter = cal.get(Calendar.MONTH) / 3;
                cal.set(Calendar.MONTH, quarter * 3);
                cal.set(Calendar.DAY_OF_MONTH, 1);
                start = dayFmt.format(cal.getTime());
                cal.add(Calendar.MONTH, 3);
                end = dayFmt.format(cal.getTime());
                break;
            case 4:
                cal.set(Calendar.DAY_OF_YEAR, 1);
                start = dayFmt.format(cal.getTime());
                cal.add(Calendar.YEAR, 1);
                end = dayFmt.format(cal.getTime());
                break;
            case 5:
                if (customStartDate != null && customEndDate != null) {
                    start = customStartDate;
                    Calendar endCal = Calendar.getInstance(Locale.US);
                    SimpleDateFormat dayFmt2 = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                    try {
                        endCal.setTime(dayFmt2.parse(customEndDate));
                        endCal.add(Calendar.DAY_OF_MONTH, 1);
                        end = dayFmt2.format(endCal.getTime());
                    } catch (Exception e) {
                        end = customEndDate;
                    }
                } else {
                    start = dayFmt.format(cal.getTime());
                    cal.add(Calendar.DAY_OF_MONTH, 1);
                    end = dayFmt.format(cal.getTime());
                }
                break;
            default:
                start = dayFmt.format(cal.getTime());
                cal.add(Calendar.DAY_OF_MONTH, 1);
                end = dayFmt.format(cal.getTime());
        }

        return new String[]{start, end};
    }

    private String getPeriodKey(int position) {
        Calendar cal = (Calendar) offsetCalendar.clone();
        SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat monthFmt = new SimpleDateFormat("yyyy-MM", Locale.US);
        SimpleDateFormat yearFmt = new SimpleDateFormat("yyyy", Locale.US);

        switch (position) {
            case 0:
                return dayFmt.format(cal.getTime());
            case 1:
                // 与 getDateRange 保持一致：Sunday 时回退一天，避免跨周
                if (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
                    cal.add(Calendar.DAY_OF_MONTH, -1);
                }
                int week = cal.get(Calendar.WEEK_OF_YEAR);
                return yearFmt.format(cal.getTime()) + "-W" + String.format(Locale.US, "%02d", week);
            case 2:
                return monthFmt.format(cal.getTime());
            case 3:
                int quarter = (cal.get(Calendar.MONTH) / 3) + 1;
                return yearFmt.format(cal.getTime()) + "-Q" + quarter;
            case 4:
                return yearFmt.format(cal.getTime());
            case 5:
                if (customStartDate != null && customEndDate != null) {
                    return customStartDate + "_" + customEndDate;
                }
                return dayFmt.format(cal.getTime());
            default:
                return dayFmt.format(cal.getTime());
        }
    }

    private String getPeriodLabel(int position) {
        String periodKey = getPeriodKey(position);
        String name = periodNames[position];
        switch (position) {
            case 0:
            case 1:
            case 2:
            case 3:
            case 4:
                return getString(R.string.track_period_label_format, name, periodKey);
            case 5:
                return getString(R.string.track_period_label_custom_format, name, periodKey);
            default:
                return periodKey;
        }
    }

    private String buildYearlyEntriesText(String[] dateRange) {
        Calendar cal = (Calendar) offsetCalendar.clone();
        int year = cal.get(Calendar.YEAR);

        Calendar weekCal = Calendar.getInstance(Locale.US);
        weekCal.clear();
        weekCal.set(Calendar.YEAR, year);
        weekCal.set(Calendar.MONTH, Calendar.JANUARY);
        weekCal.set(Calendar.DAY_OF_MONTH, 1);
        weekCal.setFirstDayOfWeek(Calendar.MONDAY);

        List<String> weeklyKeys = new ArrayList<>();
        List<String[]> weeklyRanges = new ArrayList<>();
        int prevWeek = -1;
        while (weekCal.get(Calendar.YEAR) <= year) {
            int week = weekCal.get(Calendar.WEEK_OF_YEAR);
            if (week != prevWeek && weekCal.get(Calendar.YEAR) == year) {
                String key = String.format(Locale.US, "%04d-W%02d", year, week);
                if (!weeklyKeys.contains(key)) {
                    weeklyKeys.add(key);
                    String wStart = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(weekCal.getTime());
                    Calendar nextWeek = (Calendar) weekCal.clone();
                    nextWeek.add(Calendar.WEEK_OF_YEAR, 1);
                    String wEnd = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(nextWeek.getTime());
                    weeklyRanges.add(new String[]{wStart, wEnd});
                }
                prevWeek = week;
            }
            weekCal.add(Calendar.DAY_OF_MONTH, 1);
        }

        return buildEntriesTextFromSubPeriods("weekly", getString(R.string.track_weekly_summary_label), weeklyKeys, weeklyRanges, dateRange);
    }

    /**
     * 构建月度总结输入：复用本月各周总结，缺失周补原始日记。
     * 提升输入信息密度，让 AI 做更高阶的模式识别而非从流水重新归纳。
     */
    private String buildMonthlyEntriesText(String[] dateRange) {
        Calendar cal = (Calendar) offsetCalendar.clone();
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH);

        Calendar weekCal = Calendar.getInstance(Locale.US);
        weekCal.clear();
        weekCal.set(Calendar.YEAR, year);
        weekCal.set(Calendar.MONTH, month);
        weekCal.set(Calendar.DAY_OF_MONTH, 1);
        weekCal.setFirstDayOfWeek(Calendar.MONDAY);

        SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

        List<String> weeklyKeys = new ArrayList<>();
        List<String[]> weeklyRanges = new ArrayList<>();
        int prevWeek = -1;
        int maxDay = weekCal.getActualMaximum(Calendar.DAY_OF_MONTH);
        for (int day = 1; day <= maxDay; day++) {
            weekCal.set(Calendar.DAY_OF_MONTH, day);
            int week = weekCal.get(Calendar.WEEK_OF_YEAR);
            if (week != prevWeek) {
                String key = String.format(Locale.US, "%04d-W%02d", year, week);
                if (!weeklyKeys.contains(key)) {
                    weeklyKeys.add(key);
                    String wStart = dayFmt.format(weekCal.getTime());
                    Calendar nextWeek = (Calendar) weekCal.clone();
                    nextWeek.add(Calendar.WEEK_OF_YEAR, 1);
                    String wEnd = dayFmt.format(nextWeek.getTime());
                    weeklyRanges.add(new String[]{wStart, wEnd});
                }
                prevWeek = week;
            }
        }

        return buildEntriesTextFromSubPeriods("weekly", getString(R.string.track_weekly_summary_label), weeklyKeys, weeklyRanges, dateRange);
    }

    /**
     * 构建季度总结输入：复用本季各月总结，缺失月份补原始日记。
     */
    private String buildQuarterlyEntriesText(String[] dateRange) {
        Calendar cal = (Calendar) offsetCalendar.clone();
        int year = cal.get(Calendar.YEAR);
        int quarter = cal.get(Calendar.MONTH) / 3;
        int startMonth = quarter * 3;

        SimpleDateFormat monthFmt = new SimpleDateFormat("yyyy-MM", Locale.US);
        SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

        List<String> monthlyKeys = new ArrayList<>();
        List<String[]> monthlyRanges = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Calendar monthCal = Calendar.getInstance(Locale.US);
            monthCal.clear();
            monthCal.set(Calendar.YEAR, year);
            monthCal.set(Calendar.MONTH, startMonth + i);
            monthCal.set(Calendar.DAY_OF_MONTH, 1);

            monthlyKeys.add(monthFmt.format(monthCal.getTime()));

            String mStart = dayFmt.format(monthCal.getTime());
            Calendar nextMonth = (Calendar) monthCal.clone();
            nextMonth.add(Calendar.MONTH, 1);
            String mEnd = dayFmt.format(nextMonth.getTime());
            monthlyRanges.add(new String[]{mStart, mEnd});
        }

        return buildEntriesTextFromSubPeriods("monthly", getString(R.string.track_monthly_summary_label), monthlyKeys, monthlyRanges, dateRange);
    }

    /**
     * 公共方法：复用子周期总结构建输入文本，缺失子周期补原始日记。
     * @param subPeriodType 子周期类型（weekly/monthly）
     * @param summaryLabel 总结标签（每周/每月总结）
     * @param subKeys 子周期 key 列表
     * @param subRanges 子周期日期范围列表，与 subKeys 一一对应
     * @param dateRange 当前周期日期范围，用于全空时兜底
     */
    private String buildEntriesTextFromSubPeriods(String subPeriodType, String summaryLabel,
                                                   List<String> subKeys, List<String[]> subRanges,
                                                   String[] dateRange) {
        Set<String> existingKeys = summaryDao.getExistingSummaryKeys(subPeriodType, subKeys);
        List<String> existingList = new ArrayList<>();
        List<Integer> missingIndices = new ArrayList<>();
        for (int i = 0; i < subKeys.size(); i++) {
            if (existingKeys.contains(subKeys.get(i))) {
                existingList.add(subKeys.get(i));
            } else {
                missingIndices.add(i);
            }
        }

        StringBuilder sb = new StringBuilder();

        if (!existingList.isEmpty()) {
            String summariesText = summaryDao.getSummariesTextForKeys(subPeriodType, existingList);
            if (!summariesText.isEmpty()) {
                sb.append("=== ").append(summaryLabel).append(" ===\n").append(summariesText);
            }
        }

        for (int idx : missingIndices) {
            String[] range = subRanges.get(idx);
            String entriesText = summaryDao.getEntriesTextForPeriod(range[0], range[1]);
            if (!entriesText.isEmpty()) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(getString(R.string.track_raw_diary_section_format, subKeys.get(idx))).append(entriesText);
            }
        }

        if (sb.length() == 0) {
            return summaryDao.getEntriesTextForPeriod(dateRange[0], dateRange[1]);
        }

        return sb.toString().trim();
    }

    /**
     * 弹出确认对话框，确认后重新生成本周期总结。
     * 重新生成会清除当前周期的 summary 缓存并重新调用 AI。
     */
    private void confirmRegenerate() {
        if (!isAdded()) return;
        // 正在生成中，忽略重复触发
        if (generatingDialog != null && generatingDialog.isShowing()) return;
        // 自定义周期未选择日期，无可重新生成的总结
        if (selectedPosition == 5 && (customStartDate == null || customEndDate == null)) {
            return;
        }
        if (!isAiConnected()) {
            android.widget.Toast.makeText(requireContext(),
                    R.string.track_regenerate_no_ai, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Context ctx = getContext();
        if (ctx == null) return;

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setMessage(R.string.track_regenerate_confirm)
                .setPositiveButton(R.string.track_regenerate_confirm_ok,
                        (d, w) -> regenerateCurrentSummary())
                .setNegativeButton(R.string.diary_cancel, null)
                .create();
        registerPickerDialog(dialog);
        dialog.setOnDismissListener(d -> unregisterPickerDialog((Dialog) d));
        dialog.show();
    }

    /**
     * 清除当前周期的 summary 缓存并重新生成。
     * insight 数据会在生成成功后由 saveInsightFromJson 覆盖写入。
     */
    private void regenerateCurrentSummary() {
        if (!isAdded()) return;
        final int position = selectedPosition;
        final String periodType = periodTypes[position];
        final String periodKey = getPeriodKey(position);

        AppExecutors.io().execute(() -> {
            summaryDao.deleteSummary(periodType, periodKey);
            if (isAdded()) {
                runOnUiIfAlive(() -> {
                    if (!isAdded()) return;
                    // 异步删除缓存期间用户可能已切换周期，此时不应再加载旧周期；
                    // 缓存已被删除，用户切回来时 loadPeriodSummary 会自动重新生成
                    if (selectedPosition != position) return;
                    loadPeriodSummary(position, true);
                });
            }
        });
    }

    private void loadPeriodSummary(int position, boolean animate) {
        if (position == 5 && (customStartDate == null || customEndDate == null)) {
            updateContentUI(periodNames[5], getString(R.string.track_custom_period_prompt), animate);
            return;
        }

        String[] dateRange = getDateRange(position);
        String periodType = periodTypes[position];
        String periodKey = getPeriodKey(position);

        currentPeriodKey = periodKey;
        currentPeriodType = periodType;

        // 加载时显示骨架屏
        showSkeleton();

        AppExecutors.io().execute(() -> {
            int entryCount = summaryDao.getEntryCountForPeriod(dateRange[0], dateRange[1]);

            if (entryCount == 0) {
                if (isAdded()) {
                    runOnUiIfAlive(() -> renderEmptyState());
                }
                return;
            }

            String[] cached = summaryDao.getSummary(periodType, periodKey);
            if (cached != null) {
                int cachedCount;
                int cachedPromptVersion;
                try {
                    cachedCount = cached.length > 2 ? Integer.parseInt(cached[2]) : -1;
                } catch (NumberFormatException e) {
                    Log.w(TAG, "cache entry_count invalid, regenerating: " + (cached.length > 2 ? cached[2] : "null"), e);
                    cachedCount = -1;
                }
                try {
                    cachedPromptVersion = cached.length > 3 ? Integer.parseInt(cached[3]) : 0;
                } catch (NumberFormatException e) {
                    cachedPromptVersion = 0;
                }
                // 缓存失效条件：日记条数变化 或 prompt 版本不匹配
                if (cachedCount == entryCount && cachedPromptVersion == SummaryDao.CURRENT_PROMPT_VERSION) {
                    if (isAdded()) {
                        runOnUiIfAlive(() -> {
                            currentTitle = cached[0];
                            currentContent = cached[1];
                            updateContentUI(cached[0], cached[1], animate);
                            preloadAdjacentPeriods(position);
                        });
                    }
                    return;
                }
            }

            if (isAiConnected()) {
                String entriesText;
                boolean isSummaryBased;
                // 月度/季度/年度复用子周期总结，提升输入信息密度
                if (position == 2) {
                    entriesText = buildMonthlyEntriesText(dateRange);
                    isSummaryBased = true;
                } else if (position == 3) {
                    entriesText = buildQuarterlyEntriesText(dateRange);
                    isSummaryBased = true;
                } else if (position == 4) {
                    entriesText = buildYearlyEntriesText(dateRange);
                    isSummaryBased = true;
                } else {
                    entriesText = summaryDao.getEntriesTextForPeriod(dateRange[0], dateRange[1]);
                    isSummaryBased = false;
                }
                if (isAdded()) {
                    boolean finalIsSummaryBased = isSummaryBased;
                    runOnUiIfAlive(() ->
                            generateAndCache(position, dateRange, periodType, periodKey, entryCount, animate, entriesText, finalIsSummaryBased));
                }
            } else {
                if (isAdded()) {
                    runOnUiIfAlive(() ->
                        updateContentUI(periodNames[position], getString(R.string.track_no_ai), animate));
                }
            }
        });
    }

    private void preloadAdjacentPeriods(int currentPosition) {
        List<Integer> adjacentPositions = new ArrayList<>();

        if (currentPosition > 0) {
            adjacentPositions.add(currentPosition - 1);
        }
        if (currentPosition < periodTypes.length - 1) {
            adjacentPositions.add(currentPosition + 1);
        }

        AppExecutors.io().execute(() -> {
            for (int pos : adjacentPositions) {
                String periodType = periodTypes[pos];
                String periodKey = getPeriodKey(pos);
                String cacheKey = periodType + "_" + periodKey;

                if (preloadingKeys.containsKey(cacheKey)) {
                    continue;
                }

                String[] dateRange = getDateRange(pos);
                int entryCount = summaryDao.getEntryCountForPeriod(dateRange[0], dateRange[1]);

                if (entryCount == 0) {
                    continue;
                }

                String[] cached = summaryDao.getSummary(periodType, periodKey);
                if (cached != null) {
                    int cachedCount;
                    int cachedPromptVersion;
                    try {
                        cachedCount = cached.length > 2 ? Integer.parseInt(cached[2]) : -1;
                    } catch (NumberFormatException e) {
                        Log.w(TAG, "preload cache entry_count invalid: " + (cached.length > 2 ? cached[2] : "null"), e);
                        cachedCount = -1;
                    }
                    try {
                        cachedPromptVersion = cached.length > 3 ? Integer.parseInt(cached[3]) : 0;
                    } catch (NumberFormatException e) {
                        cachedPromptVersion = 0;
                    }
                    // 缓存失效条件：日记条数变化 或 prompt 版本不匹配
                    if (cachedCount == entryCount && cachedPromptVersion == SummaryDao.CURRENT_PROMPT_VERSION) {
                        continue;
                    }
                }

                final int preloadPos = pos;
                final String preloadPeriodLabel = getPeriodLabel(preloadPos);
                final String preloadPeriodType = periodType;
                final String preloadPeriodKey = periodKey;
                final String preloadEntriesText;
                final boolean preloadIsSummaryBased;
                // 月度/季度/年度复用子周期总结，提升输入信息密度
                if (pos == 2) {
                    preloadEntriesText = buildMonthlyEntriesText(dateRange);
                    preloadIsSummaryBased = true;
                } else if (pos == 3) {
                    preloadEntriesText = buildQuarterlyEntriesText(dateRange);
                    preloadIsSummaryBased = true;
                } else if (pos == 4) {
                    preloadEntriesText = buildYearlyEntriesText(dateRange);
                    preloadIsSummaryBased = true;
                } else {
                    preloadEntriesText = summaryDao.getEntriesTextForPeriod(dateRange[0], dateRange[1]);
                    preloadIsSummaryBased = false;
                }
                final int preloadEntryCount = entryCount;
                preloadingKeys.put(cacheKey, true);

                try {
                    AppExecutors.io().execute(() -> {
                        try {
                            if (Thread.currentThread().isInterrupted()) return;
                            // 注入历史洞察上下文，与主流程保持一致
                            String historyInsightContext = insightDao.buildInsightContext(preloadPeriodType, 5);

                            DeepSeekClient.SummaryWithInsightEntry entry = DeepSeekClient.generateSummaryWithInsightSync(
                                    preloadPeriodLabel, preloadEntriesText, preloadIsSummaryBased,
                                    preloadPeriodType, historyInsightContext);

                            if (entry == null) {
                                return;
                            }
                            // 保存前再次检查中断状态和 Fragment 存活状态，避免销毁后无效写入
                            if (Thread.currentThread().isInterrupted() || !isAdded()) return;
                            // 洞察数据是纯数据库操作，不依赖 Fragment 状态，直接在后台线程保存
                            saveInsightFromJson(preloadPeriodType, preloadPeriodKey, entry.insightJson);
                            // summary 也是数据库操作，直接后台保存即可
                            summaryDao.saveSummary(preloadPeriodType, preloadPeriodKey, entry.title, entry.content, preloadEntryCount, SummaryDao.CURRENT_PROMPT_VERSION);
                        } catch (Exception e) {
                            android.util.Log.w("TrackFragment", "preload failed for " + cacheKey, e);
                        } finally {
                            preloadingKeys.remove(cacheKey);
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    preloadingKeys.remove(cacheKey);
                    android.util.Log.d("TrackFragment", "preload rejected (executor shutdown): " + cacheKey);
                }
            }
        });
    }

    private void generateAndCache(int position, String[] dateRange, String periodType, String periodKey, int entryCount, boolean animate, String entriesText, boolean isSummaryBased) {
        showGeneratingDialog(getString(R.string.track_generating));

        String periodLabel = getPeriodLabel(position);

        // Query history insight context in background
        AppExecutors.io().execute(() -> {
            String historyInsightContext = insightDao.buildInsightContext(periodType, 5);

            if (isAdded()) {
                runOnUiIfAlive(() -> {
                    DeepSeekClient.generateSummaryStreamWithInsight(periodLabel, entriesText, isSummaryBased,
                            periodType, historyInsightContext,
                            new DeepSeekClient.SummaryWithInsightStreamCallback() {
                                @Override
                                public void onPartialResult(String title, String content, boolean isComplete) {
                                    if (!isAdded()) return;
                                    runOnUiIfAlive(() -> {
                                        if (!isComplete) {
                                            currentTitle = title;
                                            currentContent = content;
                                            updateGeneratingProgress();
                                        }
                                    });
                                }

                                @Override
                                public void onComplete(String title, String content, String insightJson) {
                                    if (!isAdded()) return;
                                    // Save insight data on background thread（数据层保留，用于洞察加强回答质量）
                                    AppExecutors.io().execute(() -> {
                                        saveInsightFromJson(periodType, periodKey, insightJson);
                                    });

                                    runOnUiIfAlive(() -> {
                                        dismissGeneratingDialog();
                                        currentTitle = title;
                                        currentContent = content;
                                        updateContentUI(title, content, animate);
                                        preloadAdjacentPeriods(position);
                                    });

                                    // 数据库写操作放到后台线程，避免阻塞 UI
                                    AppExecutors.io().execute(() ->
                                            summaryDao.saveSummary(periodType, periodKey, title, content, entryCount, SummaryDao.CURRENT_PROMPT_VERSION));

                                    // 自检：校验卡片质量，不合格则后台用低 temperature 重新生成
                                    if (!validateSummary(content, periodType)) {
                                        Log.w(TAG, "Summary validation failed, retrying with lower temperature: " + periodType);
                                        AppExecutors.io().execute(() -> {
                                            DeepSeekClient.SummaryWithInsightEntry retry = DeepSeekClient.generateSummaryWithInsightSync(
                                                    periodLabel, entriesText, isSummaryBased, periodType,
                                                    historyInsightContext, 0.3);

                                            // 校验当前周期未变化，避免 UI 错乱
                                            if (retry != null && validateSummary(retry.content, periodType)
                                                    && isAdded() && periodKey.equals(currentPeriodKey)) {
                                                saveInsightFromJson(periodType, periodKey, retry.insightJson);
                                                summaryDao.saveSummary(periodType, periodKey, retry.title, retry.content, entryCount, SummaryDao.CURRENT_PROMPT_VERSION);
                                                runOnUiIfAlive(() -> {
                                                    if (!isAdded() || !periodKey.equals(currentPeriodKey)) return;
                                                    currentTitle = retry.title;
                                                    currentContent = retry.content;
                                                    updateContentUI(retry.title, retry.content, false);
                                                });
                                            }
                                        });
                                    }
                                }

                                @Override
                                public void onFailure(String message) {
                                    if (!isAdded()) return;
                                    runOnUiIfAlive(() -> {
                                        dismissGeneratingDialog();
                                        updateContentUI(periodNames[position], getString(R.string.track_ai_failed_format, message), animate);
                                    });
                                }
                            });
                });
            }
        });
    }

    /**
     * 校验总结卡片质量。
     * 检查项：JSON 可解析、卡片数量、type 合法性、内容字数、卡片间内容重复。
     * @return true 校验通过，false 校验失败需重新生成
     */
    private boolean validateSummary(String content, String periodType) {
        if (content == null || content.isEmpty()) return false;
        try {
            JSONArray cards = new JSONArray(content);
            if (cards.length() == 0) return false;

            Set<String> validTypes = new HashSet<>();
            validTypes.add("event");
            validTypes.add("habit");
            validTypes.add("quote");
            validTypes.add("default");
            validTypes.add("letter");

            // 按周期类型设定字数范围
            int minLen, maxLen;
            switch (periodType) {
                case "weekly": minLen = 50; maxLen = 200; break;
                case "monthly": minLen = 80; maxLen = 300; break;
                case "quarterly": minLen = 80; maxLen = 500; break;
                case "yearly": minLen = 100; maxLen = 2000; break;
                default: minLen = 40; maxLen = 200; break;
            }

            Set<String> seenPrefixes = new HashSet<>();
            for (int i = 0; i < cards.length(); i++) {
                JSONObject card = cards.getJSONObject(i);
                String type = card.optString("type", "");
                String cardContent = card.optString("content", "");
                // 显式 null 字段（"content": null）会被 optString 变成字面 "null"，视为空内容 → 不合格重生成
                if ("null".equalsIgnoreCase(cardContent.trim())) return false;

                // type 合法性
                if (!validTypes.contains(type)) return false;

                // letter 卡片（年度信）跳过字数检查
                if (!"letter".equals(type)) {
                    if (cardContent.length() < minLen || cardContent.length() > maxLen) return false;
                }

                // 内容重复检查：前 40 字符相同视为重复
                String prefix = cardContent.length() > 40 ? cardContent.substring(0, 40) : cardContent;
                if (seenPrefixes.contains(prefix)) return false;
                seenPrefixes.add(prefix);
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "validateSummary: parse failed", e);
            return false;
        }
    }

    private void saveInsightFromJson(String periodType, String periodKey, String insightJson) {
        if (insightJson == null || insightJson.isEmpty()) return;
        try {
            JSONObject parsed = new JSONObject(insightJson);
            // 使用 joinArrayToString 正确拼接 JSONArray，避免 replace("\"","") 误删内容中的引号
            String keyPersons = joinArrayToString(parsed.optJSONArray("key_persons"));
            String keyProjects = joinArrayToString(parsed.optJSONArray("key_projects"));
            String keyEvents = joinArrayToString(parsed.optJSONArray("key_events"));
            String emotionThemes = joinArrayToString(parsed.optJSONArray("emotion_themes"));
            String decisions = joinArrayToString(parsed.optJSONArray("decisions"));
            insightDao.saveInsight(periodType, periodKey, keyPersons, keyProjects, keyEvents, emotionThemes, decisions);
        } catch (Exception e) {
            Log.w(TAG, "saveInsightFromJson: failed", e);
        }
    }

    /**
     * 将 JSONArray 拼接为逗号分隔字符串。
     * 比 join(",").replace("\"","") 更安全，不会误删字符串内容中的引号。
     */
    private String joinArrayToString(JSONArray arr) {
        if (arr == null || arr.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (s.isEmpty() || "null".equalsIgnoreCase(s.trim())) continue;
            if (sb.length() > 0) sb.append(",");
            sb.append(s);
        }
        return sb.toString();
    }

    /**
     * 净化 AI 返回的文本字段。JSON 中显式 null（如 "content": null）经 org.json 的 optString
     * 会变成字面 "null" 而非空串，这里统一按空处理，避免界面出现一大堆 "null"。
     */
    private static String sanitizeText(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.isEmpty() || "null".equalsIgnoreCase(t)) return "";
        return s;
    }

    private void showSkeleton() {
        cancelSkeletonAnimators();
        if (cardsContainer == null) return;
        Context ctx = getContext();
        if (ctx == null) return;
        cardsContainer.removeAllViews();
        if (summaryHeader != null) summaryHeader.setVisibility(View.VISIBLE);
        if (tvContentTitle != null) tvContentTitle.setText("");

        for (int i = 0; i < 3; i++) {
            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(ContextCompat.getDrawable(ctx, R.drawable.bg_card_inner));
            card.setPadding(dpToPx(20), dpToPx(18), dpToPx(20), dpToPx(18));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = dpToPx(12);
            card.setLayoutParams(cardParams);

            int[][] lineConfigs = {{80, 14}, {240, 12}, {200, 12}, {160, 12}};
            for (int[] config : lineConfigs) {
                View line = new View(ctx);
                LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(
                        dpToPx(config[0]), dpToPx(config[1]));
                lineParams.bottomMargin = dpToPx(config[1] > 13 ? 14 : 8);
                line.setLayoutParams(lineParams);

                GradientDrawable lineBg = new GradientDrawable();
                lineBg.setCornerRadius(dpToPx(4));
                lineBg.setColor(ContextCompat.getColor(ctx, R.color.skeleton_bg));
                line.setBackground(lineBg);
                card.addView(line);
            }

            cardsContainer.addView(card);

            ObjectAnimator pulse = ObjectAnimator.ofFloat(card, "alpha", 0.4f, 1f, 0.4f);
            pulse.setDuration(1500);
            pulse.setRepeatCount(ObjectAnimator.INFINITE);
            pulse.start();
            skeletonAnimators.add(pulse);
        }
    }

    private void cancelSkeletonAnimators() {
        for (ObjectAnimator animator : skeletonAnimators) {
            if (animator != null && animator.isRunning()) {
                animator.cancel();
            }
        }
        skeletonAnimators.clear();
    }

    private void renderEmptyState() {
        cancelSkeletonAnimators();
        if (tvContentTitle != null) tvContentTitle.setText("");
        if (cardsContainer == null) return;
        Context ctx = getContext();
        if (ctx == null) return;
        cardsContainer.removeAllViews();
        if (summaryHeader != null) summaryHeader.setVisibility(View.GONE);

        LinearLayout emptyLayout = new LinearLayout(ctx);
        emptyLayout.setOrientation(LinearLayout.VERTICAL);
        emptyLayout.setGravity(Gravity.CENTER_HORIZONTAL);
        emptyLayout.setPadding(0, dpToPx(40), 0, dpToPx(40));

        View icon = new View(ctx);
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setCornerRadius(dpToPx(28));
        iconBg.setColor(ContextCompat.getColor(ctx, R.color.empty_icon_bg));
        icon.setBackground(iconBg);
        int iconSize = dpToPx(56);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconParams.bottomMargin = dpToPx(16);
        icon.setLayoutParams(iconParams);
        emptyLayout.addView(icon);

        TextView tvMain = new TextView(ctx);
        tvMain.setText(R.string.track_empty_period_main);
        tvMain.setTextColor(ContextCompat.getColor(ctx, R.color.text_subtitle));
        tvMain.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tvMain.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams mainParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mainParams.bottomMargin = dpToPx(8);
        tvMain.setLayoutParams(mainParams);
        emptyLayout.addView(tvMain);

        TextView tvSub = new TextView(ctx);
        tvSub.setText(R.string.track_empty_period_sub);
        tvSub.setTextColor(ContextCompat.getColor(ctx, R.color.text_subtitle));
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvSub.setAlpha(0.6f);
        tvSub.setGravity(Gravity.CENTER);
        emptyLayout.addView(tvSub);

        cardsContainer.addView(emptyLayout);
    }

    private View createLetterCardView(String cardTitle, String cardContent,
                                       JSONArray keywords, String lifeStage, JSONArray turningPoints) {
        LinearLayout card = createBaseCard(24);
        if (card == null) return null;
        Context ctx = getContext();
        if (ctx == null) return null;

        // 关键词行
        if (keywords != null && keywords.length() > 0) {
            LinearLayout keywordRow = new LinearLayout(ctx);
            keywordRow.setOrientation(LinearLayout.HORIZONTAL);
            keywordRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams keywordRowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            keywordRowParams.bottomMargin = dpToPx(14);
            keywordRow.setLayoutParams(keywordRowParams);

            for (int i = 0; i < keywords.length(); i++) {
                String kw = sanitizeText(keywords.optString(i, ""));
                if (kw.isEmpty()) continue;

                TextView chip = new TextView(ctx);
                chip.setText(kw);
                chip.setTextColor(ContextCompat.getColor(ctx, R.color.white));
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                chip.setPadding(dpToPx(12), dpToPx(5), dpToPx(12), dpToPx(5));

                GradientDrawable chipBg = new GradientDrawable();
                chipBg.setCornerRadius(dpToPx(14));
                chipBg.setColor(ContextCompat.getColor(ctx, R.color.chip_letter_bg));
                chip.setBackground(chipBg);

                LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                chipParams.rightMargin = dpToPx(8);
                chip.setLayoutParams(chipParams);

                keywordRow.addView(chip);
            }
            card.addView(keywordRow);
        }

        // 人生阶段
        if (lifeStage != null && !lifeStage.isEmpty()) {
            TextView tvLifeStage = new TextView(ctx);
            tvLifeStage.setText(getString(R.string.track_life_stage_format, lifeStage));
            tvLifeStage.setTextColor(ContextCompat.getColor(ctx, R.color.text_subtitle));
            tvLifeStage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            LinearLayout.LayoutParams lsParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lsParams.bottomMargin = dpToPx(14);
            tvLifeStage.setLayoutParams(lsParams);
            card.addView(tvLifeStage);
        }

        // 信件正文
        if (!cardContent.isEmpty()) {
            TextView tvContent = new TextView(ctx);
            tvContent.setText(cardContent);
            tvContent.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
            tvContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tvContent.setTypeface(Typeface.create("serif", Typeface.NORMAL));
            tvContent.setLineSpacing(dpToPx(8), 1f);
            LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            contentParams.bottomMargin = dpToPx(14);
            tvContent.setLayoutParams(contentParams);
            card.addView(tvContent);
        }

        // 转折点
        if (turningPoints != null && turningPoints.length() > 0) {
            TextView tvTpTitle = new TextView(ctx);
            tvTpTitle.setText(R.string.track_turning_points);
            tvTpTitle.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
            tvTpTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tvTpTitle.setTypeface(Typeface.create("serif", Typeface.BOLD));
            LinearLayout.LayoutParams tpTitleParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tpTitleParams.bottomMargin = dpToPx(8);
            tvTpTitle.setLayoutParams(tpTitleParams);
            card.addView(tvTpTitle);

            for (int i = 0; i < turningPoints.length(); i++) {
                String tp = sanitizeText(turningPoints.optString(i, ""));
                if (tp.isEmpty()) continue;

                LinearLayout tpRow = new LinearLayout(ctx);
                tpRow.setOrientation(LinearLayout.HORIZONTAL);
                tpRow.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams tpRowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                tpRowParams.bottomMargin = dpToPx(6);
                tpRow.setLayoutParams(tpRowParams);

                View dot = new View(ctx);
                GradientDrawable dotBg = new GradientDrawable();
                dotBg.setCornerRadius(dpToPx(4));
                dotBg.setColor(ContextCompat.getColor(ctx, R.color.chip_letter_bg));
                dot.setBackground(dotBg);
                LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dpToPx(8), dpToPx(8));
                dotParams.rightMargin = dpToPx(10);
                dot.setLayoutParams(dotParams);
                tpRow.addView(dot);

                TextView tvTp = new TextView(ctx);
                tvTp.setText(tp);
                tvTp.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));
                tvTp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                tpRow.addView(tvTp);

                card.addView(tpRow);
            }
        }

        return card;
    }

    private void showGeneratingDialog(String message) {
        dismissGeneratingDialog();

        android.app.Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) return;
        generatingDialog = DialogHelper.create(activity, R.layout.dialog_analyzing, false);

        tvMsg = generatingDialog.findViewById(R.id.tvAnalyzingMessage);
        if (tvMsg != null) {
            tvMsg.setText(message);
        }

        tvGeneratingProgress = tvMsg;

        DialogHelper.setCloseButton(generatingDialog);
        generatingDialog.show();
    }

    private void updateGeneratingProgress() {
        if (tvGeneratingProgress != null) {
            tvGeneratingProgress.setText(getString(R.string.track_generating_streaming));
        }
    }

    private void dismissGeneratingDialog() {
        if (generatingDialog != null && generatingDialog.isShowing()) {
            generatingDialog.dismiss();
            generatingDialog = null;
        }
        tvMsg = null;
        tvGeneratingProgress = null;
    }

    private void updateContentUI(String title, String body, boolean animate) {
        if (contentArea == null) return;
        if (animate) {
            contentArea.animate()
                    .alpha(0f)
                    .translationY(12f)
                    .setDuration(150)
                    .withEndAction(() -> {
                        if (contentArea == null) return;
                        renderContent(title, body);
                        contentArea.setTranslationY(0f);
                        contentArea.animate()
                                .alpha(1f)
                                .setDuration(200)
                                .start();
                    })
                    .start();
        } else {
            renderContent(title, body);
        }
    }

    private void renderContent(String title, String body) {
        cancelSkeletonAnimators();
        if (tvContentTitle != null) tvContentTitle.setText(sanitizeText(title));
        if (cardsContainer == null) return;
        cardsContainer.removeAllViews();

        JSONArray cards = parseCardsJson(body);
        if (cards != null) {
            if (summaryHeader != null) summaryHeader.setVisibility(View.VISIBLE);
            for (int i = 0; i < cards.length(); i++) {
                try {
                    JSONObject card = cards.getJSONObject(i);
                    String cardType = sanitizeText(card.optString("type", "default"));
                    if (cardType.isEmpty()) cardType = "default";
                    String cardTitle = sanitizeText(card.optString("title", ""));
                    String cardContent = sanitizeText(card.optString("content", ""));
                    if (!cardTitle.isEmpty() || !cardContent.isEmpty()) {
                        View cardView;
                        switch (cardType) {
                            case "letter":
                                JSONArray keywords = card.optJSONArray("keywords");
                                String lifeStage = sanitizeText(card.optString("life_stage", ""));
                                JSONArray turningPoints = card.optJSONArray("turning_points");
                                cardView = createLetterCardView(cardTitle, cardContent, keywords, lifeStage, turningPoints);
                                break;
                            case "event":
                                cardView = createEventCardView(cardTitle, cardContent);
                                break;
                            case "habit":
                                cardView = createHabitCardView(cardTitle, cardContent);
                                break;
                            case "quote":
                                cardView = createQuoteCardView(cardTitle, cardContent);
                                break;
                            default:
                                cardView = createCardView(cardTitle, cardContent);
                                break;
                        }
                        cardsContainer.addView(cardView);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to render card", e);
                }
            }
        } else {
            if (summaryHeader != null) summaryHeader.setVisibility(View.VISIBLE);
            View fallbackCard = createCardView("", body);
            cardsContainer.addView(fallbackCard);
        }
    }

    private JSONArray parseCardsJson(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        try {
            String trimmed = text.trim();
            if (trimmed.startsWith("[")) {
                return new JSONArray(trimmed);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse cards JSON", e);
        }
        return null;
    }

    private LinearLayout createBaseCard(int padV) {
        Context ctx = getContext();
        if (ctx == null) return null;
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ContextCompat.getDrawable(ctx, R.drawable.bg_card_inner));
        int padH = dpToPx(20);
        card.setPadding(padH, dpToPx(padV), padH, dpToPx(padV));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.bottomMargin = dpToPx(12);
        card.setLayoutParams(cardParams);
        card.setElevation(dpToPx(2));
        return card;
    }

    private TextView createTitleText(String cardTitle) {
        Context ctx = getContext();
        if (ctx == null) return null;
        TextView tvCardTitle = new TextView(ctx);
        tvCardTitle.setText(cardTitle);
        tvCardTitle.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
        tvCardTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvCardTitle.setTypeface(Typeface.create("serif", Typeface.BOLD));
        tvCardTitle.setLetterSpacing(0.05f);
        return tvCardTitle;
    }

    private TextView createContentText(String cardContent, int textSizeSp, int lineSpacingDp) {
        Context ctx = getContext();
        if (ctx == null) return null;
        TextView tvCardContent = new TextView(ctx);
        tvCardContent.setText(cardContent);
        tvCardContent.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));
        tvCardContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp);
        tvCardContent.setLineSpacing(dpToPx(lineSpacingDp), 1f);
        return tvCardContent;
    }

    private View createCardView(String cardTitle, String cardContent) {
        LinearLayout card = createBaseCard(18);
        if (card == null) return null;

        if (!cardTitle.isEmpty()) {
            TextView tvCardTitle = createTitleText(cardTitle);
            if (tvCardTitle != null) {
                LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                titleParams.bottomMargin = dpToPx(10);
                tvCardTitle.setLayoutParams(titleParams);
                card.addView(tvCardTitle);
            }
        }

        TextView tvContent = createContentText(cardContent, 14, 6);
        if (tvContent != null) card.addView(tvContent);
        return card;
    }

    private View createEventCardView(String cardTitle, String cardContent) {
        LinearLayout card = createBaseCard(18);
        if (card == null) return null;
        Context ctx = getContext();
        if (ctx == null) return null;

        LinearLayout headerLayout = new LinearLayout(ctx);
        headerLayout.setOrientation(LinearLayout.HORIZONTAL);
        headerLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        headerParams.bottomMargin = dpToPx(10);
        headerLayout.setLayoutParams(headerParams);

        View dot = new View(ctx);
        dot.setBackground(ContextCompat.getDrawable(ctx, R.drawable.dot_event));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dpToPx(12), dpToPx(12));
        dotParams.rightMargin = dpToPx(10);
        dot.setLayoutParams(dotParams);
        headerLayout.addView(dot);

        if (!cardTitle.isEmpty()) {
            TextView tvTitle = createTitleText(cardTitle);
            if (tvTitle != null) headerLayout.addView(tvTitle);
        }

        card.addView(headerLayout);
        TextView tvContent = createContentText(cardContent, 14, 6);
        if (tvContent != null) card.addView(tvContent);
        return card;
    }

    private View createHabitCardView(String cardTitle, String cardContent) {
        LinearLayout card = createBaseCard(18);
        if (card == null) return null;
        Context ctx = getContext();
        if (ctx == null) return null;

        if (!cardTitle.isEmpty()) {
            TextView tvCardTitle = createTitleText(cardTitle);
            if (tvCardTitle != null) {
                LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                titleParams.bottomMargin = dpToPx(10);
                tvCardTitle.setLayoutParams(titleParams);
                card.addView(tvCardTitle);
            }
        }

        TextView tvCardContent = createContentText(cardContent, 14, 6);
        if (tvCardContent == null) return card;
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        contentParams.bottomMargin = dpToPx(12);
        tvCardContent.setLayoutParams(contentParams);
        card.addView(tvCardContent);

        LinearLayout progressContainer = new LinearLayout(ctx);
        progressContainer.setOrientation(LinearLayout.HORIZONTAL);
        progressContainer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(6)
        ));
        progressContainer.setBackgroundColor(ContextCompat.getColor(ctx, R.color.bg_placeholder));
        View progressBar = new View(ctx);
        progressBar.setBackground(ContextCompat.getDrawable(ctx, R.drawable.progress_habit));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                0.6f
        );
        progressBar.setLayoutParams(progressParams);
        progressContainer.addView(progressBar);
        card.addView(progressContainer);

        return card;
    }

    private View createQuoteCardView(String cardTitle, String cardContent) {
        LinearLayout card = createBaseCard(24);
        if (card == null) return null;
        Context ctx = getContext();
        if (ctx == null) return null;

        TextView tvQuoteMark = new TextView(ctx);
        tvQuoteMark.setText("\"");
        tvQuoteMark.setTextColor(ContextCompat.getColor(ctx, R.color.brown_accent));
        tvQuoteMark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48);
        tvQuoteMark.setTypeface(Typeface.create("serif", Typeface.BOLD));
        tvQuoteMark.setLineSpacing(0, 0.8f);
        LinearLayout.LayoutParams quoteMarkParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        quoteMarkParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        tvQuoteMark.setLayoutParams(quoteMarkParams);
        card.addView(tvQuoteMark);

        if (!cardTitle.isEmpty()) {
            TextView tvCardTitle = createTitleText(cardTitle);
            if (tvCardTitle != null) {
                tvCardTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                tvCardTitle.setGravity(android.view.Gravity.CENTER);
                LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                titleParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
                titleParams.bottomMargin = dpToPx(12);
                tvCardTitle.setLayoutParams(titleParams);
                card.addView(tvCardTitle);
            }
        }

        TextView tvCardContent = createContentText(cardContent, 15, 10);
        if (tvCardContent != null) {
            tvCardContent.setTypeface(Typeface.create("serif", Typeface.ITALIC));
            tvCardContent.setGravity(android.view.Gravity.CENTER);
            card.addView(tvCardContent);
        }

        return card;
    }

    private int dpToPx(int dp) {
        Context ctx = getContext();
        if (ctx == null) return dp;
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics());
    }

    private void positionDropdownMenu() {
        if (dropdownTrigger == null || dropdownMenu == null) return;
        View rootView = getView();
        if (rootView == null) return;

        int[] triggerLoc = new int[2];
        dropdownTrigger.getLocationOnScreen(triggerLoc);

        int[] rootLoc = new int[2];
        rootView.getLocationOnScreen(rootLoc);

        float targetY = triggerLoc[1] - rootLoc[1] + dropdownTrigger.getHeight() + 8;
        dropdownMenu.setTranslationY(targetY);
    }

    private void toggleDropdown() {
        if (dropdownOpen) {
            closeDropdown();
        } else {
            openDropdown();
        }
    }

    private void openDropdown() {
        isAnimating = true;
        dropdownOpen = true;

        dropdownMenu.setVisibility(View.VISIBLE);
        positionDropdownMenu();
        dropdownMenu.setPivotY(0f);
        dropdownMenu.setScaleY(0f);
        dropdownMenu.setAlpha(0f);

        dropdownMenu.post(() -> {
            if (dropdownMenu == null) return;
            if (dropdownMenu.getWidth() == 0 || dropdownMenu.getHeight() == 0) {
                isAnimating = false;
                dropdownOpen = false;
                dropdownMenu.setVisibility(View.GONE);
                return;
            }

            dropdownMenu.animate()
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(220)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .withEndAction(() -> isAnimating = false)
                    .start();
        });

        if (ivArrow != null) {
            ivArrow.animate()
                    .rotation(180f)
                    .setDuration(220)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    private void closeDropdown() {
        isAnimating = true;
        dropdownOpen = false;

        dropdownMenu.setPivotY(0f);
        dropdownMenu.animate()
                .scaleY(0f)
                .alpha(0f)
                .setDuration(180)
                .setInterpolator(new AccelerateInterpolator(1.2f))
                .withEndAction(() -> {
                    if (dropdownMenu == null) {
                        isAnimating = false;
                        return;
                    }
                    dropdownMenu.setVisibility(View.GONE);
                    dropdownMenu.setScaleY(1f);
                    dropdownMenu.setAlpha(1f);
                    isAnimating = false;
                })
                .start();

        if (ivArrow != null) {
            ivArrow.animate()
                    .rotation(0f)
                    .setDuration(180)
                    .setInterpolator(new AccelerateInterpolator())
                    .start();
        }
    }

    /**
     * 切换顶部 Tab：总结 / 运动 / 睡眠 / 社交。
     * 切换时关闭下拉菜单，并切换对应内容容器的可见性。
     * 当前仅「总结」Tab 有真实数据，其余 3 个为占位状态。
     */
    private void selectTab(int tab) {
        if (tab == selectedTab) return;
        // 切 tab 时关闭下拉菜单，避免悬浮菜单残留在错误的 tab 上
        if (dropdownOpen && dropdownMenu != null) {
            dropdownMenu.setVisibility(View.GONE);
            dropdownMenu.setScaleY(1f);
            dropdownMenu.setAlpha(1f);
            dropdownOpen = false;
            if (ivArrow != null) ivArrow.setRotation(0f);
        }

        selectedTab = tab;

        // 更新 tab 视觉态
        updateTabUI(tabSummary, tab == TAB_SUMMARY);
        updateTabUI(tabExercise, tab == TAB_EXERCISE);
        updateTabUI(tabSleep, tab == TAB_SLEEP);
        updateTabUI(tabSocial, tab == TAB_SOCIAL);

        // 切换内容容器
        if (summaryTabContent != null) {
            summaryTabContent.setVisibility(tab == TAB_SUMMARY ? View.VISIBLE : View.GONE);
        }
        if (exerciseTabContent != null) {
            exerciseTabContent.setVisibility(tab == TAB_EXERCISE ? View.VISIBLE : View.GONE);
        }
        if (sleepTabContent != null) {
            sleepTabContent.setVisibility(tab == TAB_SLEEP ? View.VISIBLE : View.GONE);
        }
        if (socialTabContent != null) {
            socialTabContent.setVisibility(tab == TAB_SOCIAL ? View.VISIBLE : View.GONE);
        }

        // 切换后加载对应 Tab 数据
        if (tab == TAB_EXERCISE) {
            loadExerciseData();
        } else if (tab == TAB_SLEEP) {
            loadSleepData();
        } else if (tab == TAB_SOCIAL) {
            loadSocialData();
        }
    }

    private void updateTabUI(TextView tab, boolean active) {
        if (tab == null) return;
        Context ctx = getContext();
        if (ctx == null) return;
        tab.setBackgroundResource(active
                ? R.drawable.bg_track_tab_active
                : R.drawable.bg_track_tab);
        tab.setTextColor(ContextCompat.getColor(ctx,
                active ? R.color.brown_period_text : R.color.text_subtitle));
    }

    // ==================== 维度 Tab 数据加载与渲染 ====================

    /**
     * 维度月份切换：运动/睡眠各自维护月份偏移。
     */
    private void navigateDimensionMonth(int tab, int direction) {
        Calendar cal = (tab == TAB_EXERCISE) ? exerciseMonthOffset : sleepMonthOffset;
        if (cal == null) return;
        cal.add(Calendar.MONTH, direction);
        updateDimensionPeriodBadge(tab);
        if (tab == TAB_EXERCISE) {
            loadExerciseData();
        } else if (tab == TAB_SLEEP) {
            loadSleepData();
        }
    }

    /**
     * 获取某个月份的起止日期字符串。
     * 返回 {"2026-07-01", "2026-07-31"}。
     */
    private String[] getMonthStartEnd(Calendar cal) {
        Calendar start = (Calendar) cal.clone();
        start.set(Calendar.DAY_OF_MONTH, 1);
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);

        Calendar end = (Calendar) cal.clone();
        end.set(Calendar.DAY_OF_MONTH, end.getActualMaximum(Calendar.DAY_OF_MONTH));

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        return new String[]{sdf.format(start.getTime()), sdf.format(end.getTime())};
    }

    private String formatMonthLabel(Calendar cal) {
        return cal.get(Calendar.YEAR) + "年" + (cal.get(Calendar.MONTH) + 1) + "月";
    }

    private void updateDimensionPeriodBadge(int tab) {
        Calendar cal = (tab == TAB_EXERCISE) ? exerciseMonthOffset : sleepMonthOffset;
        TextView badge = (tab == TAB_EXERCISE) ? tvExercisePeriodBadge : tvSleepPeriodBadge;
        if (cal != null && badge != null) {
            badge.setText(formatMonthLabel(cal));
        }
    }

    /**
     * 加载运动 Tab 数据：io 线程查 DB，主线程渲染。
     */
    private void loadExerciseData() {
        if (dimensionDao == null || exerciseMonthOffset == null) return;
        updateDimensionPeriodBadge(TAB_EXERCISE);
        final String[] range = getMonthStartEnd(exerciseMonthOffset);
        AppExecutors.io().execute(() -> {
            if (!isAdded()) return;
            final List<DimensionDao.DimensionRecord> records =
                    dimensionDao.getByDateRange(range[0], range[1]);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                renderDimensionChart(exerciseChart, records, true);
                renderExerciseTypes(records);
            });
        });
    }

    /**
     * 加载睡眠 Tab 数据。
     */
    private void loadSleepData() {
        if (dimensionDao == null || sleepMonthOffset == null) return;
        updateDimensionPeriodBadge(TAB_SLEEP);
        final String[] range = getMonthStartEnd(sleepMonthOffset);
        AppExecutors.io().execute(() -> {
            if (!isAdded()) return;
            final List<DimensionDao.DimensionRecord> records =
                    dimensionDao.getByDateRange(range[0], range[1]);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                renderDimensionChart(sleepChart, records, false);
                renderSleepStats(records);
            });
        });
    }

    /**
     * 加载社交 Tab 数据：近三个月。
     */
    private void loadSocialData() {
        if (dimensionDao == null) return;
        Calendar threeMonthsAgo = Calendar.getInstance(Locale.US);
        threeMonthsAgo.add(Calendar.MONTH, -3);
        threeMonthsAgo.set(Calendar.DAY_OF_MONTH, 1);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        final String startDate = sdf.format(threeMonthsAgo.getTime());

        AppExecutors.io().execute(() -> {
            if (!isAdded()) return;
            final List<DimensionDao.DimensionRecord> records =
                    dimensionDao.getSocialRecords(startDate);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                renderSocialContacts(records);
            });
        });
    }

    /**
     * 渲染波浪图：从 records 提取分数和日期标签。
     * isExercise=true 取运动分数，false 取睡眠分数。
     */
    private void renderDimensionChart(DimensionChartView chart, List<DimensionDao.DimensionRecord> records, boolean isExercise) {
        if (chart == null) return;
        List<Float> scores = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        if (records != null) {
            for (DimensionDao.DimensionRecord r : records) {
                Integer score = isExercise ? r.exerciseScore : r.sleepScore;
                scores.add(score != null ? score.floatValue() : null);
                labels.add(r.date);
            }
        }
        chart.setScores(scores, labels);
    }

    /**
     * 渲染运动类型频次卡：统计各运动类型出现次数。
     */
    private void renderExerciseTypes(List<DimensionDao.DimensionRecord> records) {
        if (exerciseTypesList == null) return;
        exerciseTypesList.removeAllViews();
        if (tvExerciseTypesEmpty == null) return;

        Map<String, Integer> typeCounts = new LinkedHashMap<>();
        if (records != null) {
            for (DimensionDao.DimensionRecord r : records) {
                if (r.exerciseTypes != null) {
                    for (String type : r.exerciseTypes) {
                        typeCounts.put(type, typeCounts.getOrDefault(type, 0) + 1);
                    }
                }
            }
        }

        if (typeCounts.isEmpty()) {
            tvExerciseTypesEmpty.setVisibility(View.VISIBLE);
            return;
        }
        tvExerciseTypesEmpty.setVisibility(View.GONE);

        Context ctx = getContext();
        if (ctx == null) return;
        for (Map.Entry<String, Integer> entry : typeCounts.entrySet()) {
            TextView tv = new TextView(ctx);
            tv.setText(entry.getKey() + "  ×" + entry.getValue());
            tv.setTextColor(ContextCompat.getColor(ctx, R.color.brown_period_text));
            tv.setTextSize(13);
            tv.setLetterSpacing(0.05f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dpToPx(6);
            tv.setLayoutParams(lp);
            exerciseTypesList.addView(tv);
        }
    }

    /**
     * 渲染睡眠统计卡：均分、最高分、最低分。
     */
    private void renderSleepStats(List<DimensionDao.DimensionRecord> records) {
        if (sleepStatsList == null) return;
        sleepStatsList.removeAllViews();
        if (tvSleepStatsEmpty == null) return;

        if (records == null || records.isEmpty()) {
            tvSleepStatsEmpty.setVisibility(View.VISIBLE);
            return;
        }
        // 收集所有非空睡眠分数
        List<DimensionDao.DimensionRecord> scored = new ArrayList<>();
        int sum = 0;
        int max = -1, min = 101;
        String maxDate = "", minDate = "";
        for (DimensionDao.DimensionRecord r : records) {
            if (r.sleepScore != null) {
                scored.add(r);
                sum += r.sleepScore;
                if (r.sleepScore > max) {
                    max = r.sleepScore;
                    maxDate = r.date;
                }
                if (r.sleepScore < min) {
                    min = r.sleepScore;
                    minDate = r.date;
                }
            }
        }
        if (scored.isEmpty()) {
            tvSleepStatsEmpty.setVisibility(View.VISIBLE);
            return;
        }
        tvSleepStatsEmpty.setVisibility(View.GONE);

        Context ctx = getContext();
        if (ctx == null) return;
        int avg = sum / scored.size();
        addStatRow(ctx, sleepStatsList, getString(R.string.track_dim_avg_score), avg + " 分");
        addStatRow(ctx, sleepStatsList, getString(R.string.track_dim_max_score), max + " 分 (" + maxDate.substring(5) + ")");
        addStatRow(ctx, sleepStatsList, getString(R.string.track_dim_min_score), min + " 分 (" + minDate.substring(5) + ")");
    }

    private void addStatRow(Context ctx, LinearLayout container, String label, String value) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dpToPx(6);
        row.setLayoutParams(rowLp);

        TextView tvLabel = new TextView(ctx);
        tvLabel.setText(label);
        tvLabel.setTextColor(ContextCompat.getColor(ctx, R.color.text_subtitle));
        tvLabel.setTextSize(13);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvLabel.setLayoutParams(labelLp);

        TextView tvValue = new TextView(ctx);
        tvValue.setText(value);
        tvValue.setTextColor(ContextCompat.getColor(ctx, R.color.brown_period_text));
        tvValue.setTextSize(13);

        row.addView(tvLabel);
        row.addView(tvValue);
        container.addView(row);
    }

    /**
     * 渲染社交卡片：按人物分组，每个人一张卡，列出最近交流日期 + 摘录。
     */
    private void renderSocialContacts(List<DimensionDao.DimensionRecord> records) {
        if (socialContactsList == null) return;
        socialContactsList.removeAllViews();
        if (tvSocialEmpty == null) return;

        if (records == null || records.isEmpty()) {
            tvSocialEmpty.setVisibility(View.VISIBLE);
            return;
        }
        tvSocialEmpty.setVisibility(View.GONE);

        // 按人物分组
        Map<String, List<DimensionDao.DimensionRecord>> byContact = new LinkedHashMap<>();
        for (DimensionDao.DimensionRecord r : records) {
            if (r.socialContacts == null) continue;
            for (String contact : r.socialContacts) {
                byContact.computeIfAbsent(contact, k -> new ArrayList<>()).add(r);
            }
        }

        Context ctx = getContext();
        if (ctx == null) return;
        for (Map.Entry<String, List<DimensionDao.DimensionRecord>> entry : byContact.entrySet()) {
            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.bg_card);
            int pad = dpToPx(16);
            card.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            cardLp.bottomMargin = dpToPx(10);
            card.setLayoutParams(cardLp);

            // 人物名称
            TextView tvName = new TextView(ctx);
            tvName.setText(entry.getKey());
            tvName.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
            tvName.setTextSize(15);
            tvName.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            nameLp.bottomMargin = dpToPx(8);
            tvName.setLayoutParams(nameLp);
            card.addView(tvName);

            // 最近交流记录
            List<DimensionDao.DimensionRecord> contactRecords = entry.getValue();
            int maxShow = Math.min(3, contactRecords.size());
            for (int i = 0; i < maxShow; i++) {
                DimensionDao.DimensionRecord r = contactRecords.get(i);
                TextView tvEntry = new TextView(ctx);
                String dateShort = r.date.length() > 5 ? r.date.substring(5) : r.date;
                String excerpt = r.rawExcerpt != null ? r.rawExcerpt : "";
                tvEntry.setText(dateShort + "  " + excerpt);
                tvEntry.setTextColor(ContextCompat.getColor(ctx, R.color.text_subtitle));
                tvEntry.setTextSize(12);
                tvEntry.setMaxLines(2);
                tvEntry.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams entryLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                entryLp.bottomMargin = dpToPx(4);
                tvEntry.setLayoutParams(entryLp);
                card.addView(tvEntry);
            }
            socialContactsList.addView(card);
        }
    }

    // ==================== 读取过往日记 ====================

    /**
     * 确认读取过往日记对话框。
     */
    private void confirmLoadHistory(int tab) {
        if (isLoadingHistory) return;
        Context ctx = getContext();
        if (ctx == null) return;
        new AlertDialog.Builder(ctx)
                .setMessage(R.string.track_dim_confirm_load_history)
                .setPositiveButton("继续", (d, w) -> startLoadHistory(tab))
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 开始批量读取过往日记：遍历所有历史日记，跳过已打分日期，分批调 AI。
     */
    private void startLoadHistory(int tab) {
        if (dimensionDao == null) return;
        isLoadingHistory = true;
        Context ctx = getContext();
        if (ctx == null) return;

        // 创建进度对话框
        final Dialog progressDlg = new Dialog(ctx);
        progressDlg.setContentView(R.layout.dialog_analyzing);
        progressDlg.setCancelable(false);
        final TextView tvProgress = progressDlg.findViewById(R.id.tvAnalyzingMessage);
        if (tvProgress != null) {
            tvProgress.setText(R.string.track_dim_loading_history);
        }
        activeDialogs.add(progressDlg);
        progressDlg.show();

        AppExecutors.io().execute(() -> {
            if (!isAdded()) return;
            DiaryDao diaryDao = new DiaryDao(requireContext().getApplicationContext());
            final List<String[]> allDiaries = diaryDao.getAll();

            // 过滤出没有维度记录的日记
            List<String[]> toProcess = new ArrayList<>();
            for (String[] entry : allDiaries) {
                String timestamp = entry[0];
                String date = timestamp.length() >= 10 ? timestamp.substring(0, 10) : timestamp;
                if (!dimensionDao.hasRecordForDate(date)) {
                    toProcess.add(entry);
                }
            }

            if (toProcess.isEmpty()) {
                finishLoadHistory(tab, progressDlg, 0);
                return;
            }
            processHistoryBatch(toProcess, 0, 3, tab, progressDlg, tvProgress);
        });
    }

    /**
     * 递归批处理：每批处理 batchSize 篇日记。
     */
    private void processHistoryBatch(List<String[]> diaries, int index, int batchSize,
                                     int tab, Dialog progressDlg, TextView tvProgress) {
        if (index >= diaries.size()) {
            finishLoadHistory(tab, progressDlg, diaries.size());
            return;
        }
        if (!isAdded()) {
            isLoadingHistory = false;
            return;
        }

        int end = Math.min(index + batchSize, diaries.size());
        // 拼接这批日记
        StringBuilder batchContent = new StringBuilder();
        String batchDate = DateUtils.currentDate();
        for (int i = index; i < end; i++) {
            String[] entry = diaries.get(i);
            String timestamp = entry[0];
            String date = timestamp.length() >= 10 ? timestamp.substring(0, 10) : timestamp;
            batchContent.append(date).append("：").append(entry[1]).append("\n\n");
            if (i == index) batchDate = date;
        }

        // 更新进度
        final String progressText = getString(R.string.track_dim_load_progress, end, diaries.size());
        if (tvProgress != null) {
            requireActivity().runOnUiThread(() -> {
                if (tvProgress != null) tvProgress.setText(progressText);
            });
        }

        final String currentDate = batchDate;
        final String historyContext = dimensionDao.getRecentScoresText(7);

        DeepSeekClient.analyzeDiaryDatesWithDimensions(batchContent.toString(), currentDate,
                historyContext, new DeepSeekClient.MultiDateAnalyzeCallback() {
                    @Override
                    public void onResults(List<DeepSeekClient.DateEntry> entries) {
                        if (!isAdded()) return;
                        AppExecutors.io().execute(() -> {
                            if (!isAdded()) return;
                            for (DeepSeekClient.DateEntry entry : entries) {
                                if (!dimensionDao.hasRecordForDate(entry.date)) {
                                    saveDimensionRecordFromEntry(entry.date, entry);
                                }
                            }
                            processHistoryBatch(diaries, end, batchSize, tab, progressDlg, tvProgress);
                        });
                    }

                    @Override
                    public void onFailure(String message) {
                        Log.w(TAG, "history batch failed at " + end + ": " + message);
                        // 跳过失败的批次，继续下一批
                        processHistoryBatch(diaries, end, batchSize, tab, progressDlg, tvProgress);
                    }
                });
    }

    /**
     * 完成读取过往日记：关闭进度框，刷新当前 Tab。
     */
    private void finishLoadHistory(int tab, Dialog progressDlg, int totalProcessed) {
        isLoadingHistory = false;
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            if (!isAdded()) return;
            if (progressDlg != null && progressDlg.isShowing()) {
                progressDlg.dismiss();
                activeDialogs.remove(progressDlg);
            }
            String msg = totalProcessed > 0
                    ? getString(R.string.track_dim_load_complete, totalProcessed)
                    : "所有日记已有维度记录，无需处理";
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
            // 刷新当前 Tab
            if (tab == TAB_EXERCISE) loadExerciseData();
            else if (tab == TAB_SLEEP) loadSleepData();
            else if (tab == TAB_SOCIAL) loadSocialData();
        });
    }

    /**
     * 从 DateEntry 保存维度记录到 DB（不关联 diary_id，因为历史回填）。
     */
    private void saveDimensionRecordFromEntry(String date, DeepSeekClient.DateEntry entry) {
        if (entry.dimensions == null || dimensionDao == null) return;
        DeepSeekClient.DimensionData dim = entry.dimensions;
        String excerpt = dim.exerciseExcerpt;
        if (excerpt == null) excerpt = dim.sleepExcerpt;
        if (excerpt == null) excerpt = dim.socialExcerpt;
        dimensionDao.insert(date, null,
                dim.exerciseScore, dim.exerciseTypes,
                dim.sleepScore,
                dim.socialContacts,
                excerpt);
    }

    // ==================== 聚合分析（按需调用 AI + 缓存） ====================

    /**
     * 运动能力进步分析：对比本月与上月数据，调 AI 生成分析文本。
     * 结果缓存在 summaries 表，当天内不重复调用。
     */
    private void loadExerciseProgress() {
        if (exerciseMonthOffset == null || dimensionDao == null) return;
        final String periodKey = formatMonthLabel(exerciseMonthOffset).replace("年", "-").replace("月", "");
        // 检查缓存：有缓存直接显示，无缓存调 AI 生成
        String[] cached = summaryDao.getSummary("exercise_progress", periodKey);
        if (cached != null && cached[1] != null) {
            if (tvExerciseProgressContent != null) {
                tvExerciseProgressContent.setText(cached[1]);
            }
            return;
        }
        if (tvExerciseProgressContent != null) {
            tvExerciseProgressContent.setText(R.string.track_dim_analyzing);
        }

        // 获取本月和上月数据
        final String[] thisMonthRange = getMonthStartEnd(exerciseMonthOffset);
        Calendar lastMonth = (Calendar) exerciseMonthOffset.clone();
        lastMonth.add(Calendar.MONTH, -1);
        final String[] lastMonthRange = getMonthStartEnd(lastMonth);

        AppExecutors.io().execute(() -> {
            if (!isAdded()) return;
            List<DimensionDao.DimensionRecord> thisMonth =
                    dimensionDao.getByDateRange(thisMonthRange[0], thisMonthRange[1]);
            List<DimensionDao.DimensionRecord> lastMonthRecords =
                    dimensionDao.getByDateRange(lastMonthRange[0], lastMonthRange[1]);

            String dataContext = buildExerciseDataContext(thisMonth, lastMonthRecords);
            String systemPrompt = "当前任务：运动能力进步分析。\n" +
                    "对比本月与上月的运动数据，分析运动强度变化、各类型频次变化，指出进步或退步的方面。\n" +
                    "用平实的语言描述，给出具体数字对比，避免空泛评价。200字以内。";

            DeepSeekClient.generateTextAnalysis(systemPrompt, dataContext, new DeepSeekClient.TextAnalysisCallback() {
                @Override
                public void onResult(String content) {
                    if (!isAdded()) return;
                    summaryDao.saveSummary("exercise_progress", periodKey, "运动进步分析", content, 1, 1);
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        if (tvExerciseProgressContent != null) {
                            tvExerciseProgressContent.setText(content);
                        }
                    });
                }

                @Override
                public void onFailure(String message) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        if (tvExerciseProgressContent != null) {
                            tvExerciseProgressContent.setText("分析失败：" + message);
                        }
                    });
                }
            });
        });
    }

    /**
     * 构建运动进步分析的数据上下文文本。
     */
    private String buildExerciseDataContext(List<DimensionDao.DimensionRecord> thisMonth,
                                            List<DimensionDao.DimensionRecord> lastMonthRecords) {
        StringBuilder sb = new StringBuilder();
        sb.append("【本月运动数据】\n");
        appendExerciseStats(sb, thisMonth);
        sb.append("\n【上月运动数据】\n");
        appendExerciseStats(sb, lastMonthRecords);
        return sb.toString();
    }

    private void appendExerciseStats(StringBuilder sb, List<DimensionDao.DimensionRecord> records) {
        int count = 0, sum = 0;
        Map<String, Integer> typeCounts = new LinkedHashMap<>();
        for (DimensionDao.DimensionRecord r : records) {
            if (r.exerciseScore != null) {
                count++;
                sum += r.exerciseScore;
            }
            if (r.exerciseTypes != null) {
                for (String t : r.exerciseTypes) {
                    typeCounts.put(t, typeCounts.getOrDefault(t, 0) + 1);
                }
            }
        }
        sb.append("运动天数：").append(count).append("\n");
        if (count > 0) {
            sb.append("平均强度：").append(sum / count).append("\n");
        }
        sb.append("运动类型：");
        if (typeCounts.isEmpty()) {
            sb.append("无\n");
        } else {
            for (Map.Entry<String, Integer> e : typeCounts.entrySet()) {
                sb.append(e.getKey()).append("×").append(e.getValue()).append(" ");
            }
            sb.append("\n");
        }
    }

    /**
     * 睡眠影响因素分析：把本月低分日（<50）的日记摘录喂给 AI，输出归因。
     */
    private void loadSleepFactors() {
        if (sleepMonthOffset == null || dimensionDao == null) return;
        final String periodKey = formatMonthLabel(sleepMonthOffset).replace("年", "-").replace("月", "");
        String[] cached = summaryDao.getSummary("sleep_factors", periodKey);
        if (cached != null && cached[1] != null) {
            if (tvSleepFactorsContent != null) {
                tvSleepFactorsContent.setText(cached[1]);
            }
            return;
        }
        if (tvSleepFactorsContent != null) {
            tvSleepFactorsContent.setText(R.string.track_dim_analyzing);
        }

        final String[] range = getMonthStartEnd(sleepMonthOffset);
        AppExecutors.io().execute(() -> {
            if (!isAdded()) return;
            List<DimensionDao.DimensionRecord> records =
                    dimensionDao.getByDateRange(range[0], range[1]);

            StringBuilder sb = new StringBuilder();
            int lowCount = 0;
            for (DimensionDao.DimensionRecord r : records) {
                if (r.sleepScore != null && r.sleepScore < 50) {
                    lowCount++;
                    sb.append(r.date).append("（").append(r.sleepScore).append("分）：")
                            .append(r.rawExcerpt != null ? r.rawExcerpt : "无摘录").append("\n");
                }
            }
            if (lowCount == 0) {
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    if (tvSleepFactorsContent != null) {
                        tvSleepFactorsContent.setText("本月没有低分睡眠日，睡眠质量良好。");
                    }
                });
                return;
            }

            String systemPrompt = "当前任务：恶性睡眠影响因素分析。\n" +
                    "以下是用户本月睡眠低分日（<50分）的日记摘录，请分析导致睡眠差的可能因素（如咖啡因、加班、运动、情绪等），" +
                    "统计各因素出现频次，给出改善建议。200字以内。";

            final int lowCountFinal = lowCount;
            DeepSeekClient.generateTextAnalysis(systemPrompt, sb.toString(), new DeepSeekClient.TextAnalysisCallback() {
                @Override
                public void onResult(String content) {
                    if (!isAdded()) return;
                    summaryDao.saveSummary("sleep_factors", periodKey, "睡眠影响因素", content, lowCountFinal, 1);
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        if (tvSleepFactorsContent != null) {
                            tvSleepFactorsContent.setText(content);
                        }
                    });
                }

                @Override
                public void onFailure(String message) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        if (tvSleepFactorsContent != null) {
                            tvSleepFactorsContent.setText("分析失败：" + message);
                        }
                    });
                }
            });
        });
    }

    /**
     * 检查时间戳是否是今天。
     */
    private boolean isToday(String timestamp) {
        if (timestamp == null) return false;
        String today = DateUtils.currentDateTime();
        // timestamp 格式 yyyy-MM-dd HH:mm:ss
        return timestamp.length() >= 10 && today.length() >= 10
                && timestamp.substring(0, 10).equals(today.substring(0, 10));
    }

    private void selectPeriod(int position) {
        if (position == selectedPosition) {
            closeDropdown();
            return;
        }

        selectedPosition = position;
        if (tvCurrentPeriod != null) tvCurrentPeriod.setText(periodNames[position]);
        if (position != 5) {
            offsetCalendar = Calendar.getInstance(Locale.US);
        }
        updatePeriodBadge();
        updateDropdownItemUI(true);

        if (position == 5) {
            if (customStartDate == null || customEndDate == null) {
                showCustomPeriodPicker();
            } else {
                loadPeriodSummary(position, true);
            }
        } else {
            loadPeriodSummary(position, true);
        }

        closeDropdown();
    }

    private void updateDropdownItemUI(boolean animate) {
        View rootView = getView();
        if (rootView == null) return;
        Context ctx = getContext();
        if (ctx == null) return;
        for (int i = 0; i < itemIds.length; i++) {
            LinearLayout item = rootView.findViewById(itemIds[i]);
            if (item == null) continue;
            boolean isSelected = (i == selectedPosition);
            item.setActivated(isSelected);

            // 遍历子视图查找 TextView，避免假设固定索引导致 ClassCastException
            TextView text = null;
            for (int j = 0; j < item.getChildCount(); j++) {
                View child = item.getChildAt(j);
                if (child instanceof TextView) {
                    text = (TextView) child;
                    break;
                }
            }

            if (text == null) continue;

            int targetColor = isSelected
                    ? ContextCompat.getColor(ctx, R.color.white)
                    : ContextCompat.getColor(ctx, R.color.text_primary);

            if (animate) {
                ObjectAnimator textColor = ObjectAnimator.ofArgb(text, "textColor", targetColor);
                textColor.setDuration(200);
                textColor.start();
                // 加入列表便于 onDestroyView 统一 cancel，避免持有已销毁的 TextView
                skeletonAnimators.add(textColor);
            } else {
                text.setTextColor(targetColor);
            }
        }
    }

    private void navigatePeriod(int direction) {
        if (selectedPosition == 5) return;

        // clone 后修改，避免原地修改导致 IO 线程 clone() 时读到不一致的中间状态
        Calendar cal = (Calendar) offsetCalendar.clone();
        switch (selectedPosition) {
            case 0:
                cal.add(Calendar.DAY_OF_MONTH, direction);
                break;
            case 1:
                cal.add(Calendar.WEEK_OF_YEAR, direction);
                break;
            case 2:
                cal.add(Calendar.MONTH, direction);
                break;
            case 3:
                cal.add(Calendar.MONTH, 3 * direction);
                break;
            case 4:
                cal.add(Calendar.YEAR, direction);
                break;
        }

        if (direction > 0) {
            Calendar now = Calendar.getInstance(Locale.US);
            if (cal.after(now)) {
                cal = now;
            }
        }
        offsetCalendar = cal;

        updatePeriodBadge();
        updateArrowState();
        loadPeriodSummary(selectedPosition, true);
    }

    private void updatePeriodBadge() {
        Calendar cal = (Calendar) offsetCalendar.clone();
        String text;

        switch (selectedPosition) {
            case 0:
                text = new SimpleDateFormat(getString(R.string.track_date_format_full), Locale.CHINA).format(cal.getTime());
                break;
            case 1:
                // 与 getDateRange/getPeriodKey 保持一致：Sunday 时回退一天，避免 set(MONDAY) 跳到下周
                if (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
                    cal.add(Calendar.DAY_OF_MONTH, -1);
                }
                cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
                int week = cal.get(Calendar.WEEK_OF_YEAR);
                text = getString(R.string.track_week_badge_format, cal.get(Calendar.YEAR), week);
                break;
            case 2:
                text = new SimpleDateFormat(getString(R.string.track_date_format_year_month), Locale.CHINA).format(cal.getTime());
                break;
            case 3:
                int quarter = (cal.get(Calendar.MONTH) / 3) + 1;
                text = getString(R.string.track_quarter_badge_format, cal.get(Calendar.YEAR), quarter);
                break;
            case 4:
                text = getString(R.string.track_year_badge_format, cal.get(Calendar.YEAR));
                break;
            case 5:
                if (customStartDate != null && customEndDate != null) {
                    text = customStartDate + " ~ " + customEndDate;
                } else {
                    text = getString(R.string.track_select_period);
                }
                break;
            default:
                text = new SimpleDateFormat(getString(R.string.track_date_format_full), Locale.CHINA).format(cal.getTime());
        }

        if (tvPeriodBadge != null) tvPeriodBadge.setText(text);
        updateArrowState();
    }

    private void updateArrowState() {
        if (btnPrevPeriod == null || btnNextPeriod == null) return;
        if (selectedPosition == 5) {
            btnPrevPeriod.setAlpha(0.3f);
            btnPrevPeriod.setClickable(false);
            btnNextPeriod.setAlpha(0.3f);
            btnNextPeriod.setClickable(false);
            return;
        }

        Calendar now = Calendar.getInstance(Locale.US);
        boolean isAtCurrent = isSamePeriod(offsetCalendar, now, selectedPosition);
        btnPrevPeriod.setAlpha(1f);
        btnPrevPeriod.setClickable(true);
        btnNextPeriod.setAlpha(isAtCurrent ? 0.3f : 1f);
        btnNextPeriod.setClickable(!isAtCurrent);
    }

    private boolean isSamePeriod(Calendar cal1, Calendar cal2, int position) {
        switch (position) {
            case 0:
                return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
                        && cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR);
            case 1:
                return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
                        && cal1.get(Calendar.WEEK_OF_YEAR) == cal2.get(Calendar.WEEK_OF_YEAR);
            case 2:
                return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
                        && cal1.get(Calendar.MONTH) == cal2.get(Calendar.MONTH);
            case 3:
                return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
                        && cal1.get(Calendar.MONTH) / 3 == cal2.get(Calendar.MONTH) / 3;
            case 4:
                return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR);
            case 5:
                return customStartDate != null && customEndDate != null;
            default:
                return false;
        }
    }

    private void showPeriodPicker() {
        switch (selectedPosition) {
            case 0:
                showDatePicker();
                break;
            case 1:
                showWeekPicker();
                break;
            case 2:
                showMonthPicker();
                break;
            case 3:
                showQuarterPicker();
                break;
            case 4:
                showYearPicker();
                break;
            case 5:
                showCustomPeriodPicker();
                break;
        }
    }

    private void showDatePicker() {
        Context ctx = getContext();
        if (ctx == null) return;
        int year = offsetCalendar.get(Calendar.YEAR);
        int month = offsetCalendar.get(Calendar.MONTH);
        int day = offsetCalendar.get(Calendar.DAY_OF_MONTH);

        DatePickerDialog dialog = new DatePickerDialog(ctx,
                (DatePicker view, int y, int m, int d) -> {
                    Calendar cal = (Calendar) offsetCalendar.clone();
                    cal.set(y, m, d);
                    Calendar now = Calendar.getInstance(Locale.US);
                    if (cal.after(now)) {
                        cal = now;
                    }
                    offsetCalendar = cal;
                    updatePeriodBadge();
                    loadPeriodSummary(selectedPosition, true);
                }, year, month, day);

        dialog.getDatePicker().setMaxDate(System.currentTimeMillis());
        registerPickerDialog(dialog);
        dialog.setOnDismissListener(d -> unregisterPickerDialog((Dialog) d));
        dialog.show();
    }

    private void showWeekPicker() {
        Context ctx = getContext();
        if (ctx == null) return;
        int year = offsetCalendar.get(Calendar.YEAR);
        int month = offsetCalendar.get(Calendar.MONTH);
        int day = offsetCalendar.get(Calendar.DAY_OF_MONTH);

        DatePickerDialog dialog = new DatePickerDialog(ctx,
                (DatePicker view, int y, int m, int d) -> {
                    Calendar cal = (Calendar) offsetCalendar.clone();
                    cal.set(y, m, d);
                    Calendar now = Calendar.getInstance(Locale.US);
                    if (cal.after(now)) {
                        cal = now;
                    }
                    offsetCalendar = cal;
                    updatePeriodBadge();
                    loadPeriodSummary(selectedPosition, true);
                }, year, month, day);

        dialog.getDatePicker().setMaxDate(System.currentTimeMillis());
        registerPickerDialog(dialog);
        dialog.setOnDismissListener(d -> unregisterPickerDialog((Dialog) d));
        dialog.show();
    }

    private void showMonthPicker() {
        Context ctx = getContext();
        if (ctx == null) return;
        int currentYear = offsetCalendar.get(Calendar.YEAR);
        int currentMonth = offsetCalendar.get(Calendar.MONTH);

        NumberPicker yearPicker = new NumberPicker(ctx);
        yearPicker.setMinValue(2020);
        yearPicker.setMaxValue(Calendar.getInstance().get(Calendar.YEAR));
        yearPicker.setValue(currentYear);
        yearPicker.setWrapSelectorWheel(false);

        NumberPicker monthPicker = new NumberPicker(ctx);
        monthPicker.setMinValue(1);
        monthPicker.setMaxValue(12);
        monthPicker.setValue(currentMonth + 1);
        monthPicker.setWrapSelectorWheel(false);
        monthPicker.setFormatter(value -> getString(R.string.track_month_value_format, value));

        showNumberPickerDialog(getString(R.string.track_select_year_month), new View[]{yearPicker, monthPicker}, () -> {
            int y = yearPicker.getValue();
            int m = monthPicker.getValue() - 1;
            Calendar cal = (Calendar) offsetCalendar.clone();
            cal.set(y, m, 1);
            offsetCalendar = cal;
            clampOffsetCalendarToNow();
            updatePeriodBadge();
            loadPeriodSummary(selectedPosition, true);
        });
    }

    private void showQuarterPicker() {
        Context ctx = getContext();
        if (ctx == null) return;
        int currentYear = offsetCalendar.get(Calendar.YEAR);
        int currentQuarter = (offsetCalendar.get(Calendar.MONTH) / 3) + 1;

        NumberPicker yearPicker = new NumberPicker(ctx);
        yearPicker.setMinValue(2020);
        yearPicker.setMaxValue(Calendar.getInstance().get(Calendar.YEAR));
        yearPicker.setValue(currentYear);
        yearPicker.setWrapSelectorWheel(false);

        NumberPicker quarterPicker = new NumberPicker(ctx);
        quarterPicker.setMinValue(1);
        quarterPicker.setMaxValue(4);
        quarterPicker.setValue(currentQuarter);
        quarterPicker.setWrapSelectorWheel(false);
        quarterPicker.setFormatter(value -> getString(R.string.track_quarter_format, value));

        showNumberPickerDialog(getString(R.string.track_select_year_quarter), new View[]{yearPicker, quarterPicker}, () -> {
            int y = yearPicker.getValue();
            int q = quarterPicker.getValue();
            Calendar cal = (Calendar) offsetCalendar.clone();
            cal.set(y, (q - 1) * 3, 1);
            offsetCalendar = cal;
            clampOffsetCalendarToNow();
            updatePeriodBadge();
            loadPeriodSummary(selectedPosition, true);
        });
    }

    private void showYearPicker() {
        Context ctx = getContext();
        if (ctx == null) return;
        int currentYear = offsetCalendar.get(Calendar.YEAR);

        NumberPicker yearPicker = new NumberPicker(ctx);
        yearPicker.setMinValue(2020);
        yearPicker.setMaxValue(Calendar.getInstance().get(Calendar.YEAR));
        yearPicker.setValue(currentYear);
        yearPicker.setWrapSelectorWheel(false);

        showNumberPickerDialog(getString(R.string.track_select_year), new View[]{yearPicker}, () -> {
            int y = yearPicker.getValue();
            Calendar cal = (Calendar) offsetCalendar.clone();
            cal.set(y, Calendar.JANUARY, 1);
            offsetCalendar = cal;
            clampOffsetCalendarToNow();
            updatePeriodBadge();
            loadPeriodSummary(selectedPosition, true);
        });
    }

    private void clampOffsetCalendarToNow() {
        Calendar now = Calendar.getInstance(Locale.US);
        if (offsetCalendar.after(now)) {
            offsetCalendar = now;
        }
    }

    private void showNumberPickerDialog(String title, View[] pickers, Runnable onConfirm) {
        Context ctx = getContext();
        if (ctx == null) return;
        Dialog dialog = new Dialog(ctx);
        dialog.setTitle(title);

        FrameLayout wrapper = new FrameLayout(ctx);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dpToPx(24);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout pickersRow = new LinearLayout(ctx);
        pickersRow.setOrientation(LinearLayout.HORIZONTAL);
        pickersRow.setGravity(Gravity.CENTER);
        for (int i = 0; i < pickers.length; i++) {
            pickersRow.addView(pickers[i]);
            if (i < pickers.length - 1) {
                TextView tvSep = new TextView(ctx);
                tvSep.setText(R.string.year_label);
                tvSep.setTextSize(16);
                tvSep.setPadding(dpToPx(8), 0, dpToPx(8), 0);
                pickersRow.addView(tvSep);
            }
        }
        root.addView(pickersRow);

        // 确认按钮：dismiss 不设 cancelled，使 onConfirm 在 onDismiss 中执行
        TextView btnConfirm = new TextView(ctx);
        btnConfirm.setText(R.string.month_picker_confirm);
        btnConfirm.setTextSize(16);
        btnConfirm.setTextColor(ContextCompat.getColor(ctx, R.color.white));
        btnConfirm.setGravity(Gravity.CENTER);
        btnConfirm.setBackground(ContextCompat.getDrawable(ctx, R.drawable.bg_btn_submit));
        int btnPadH = dpToPx(32);
        int btnPadV = dpToPx(12);
        btnConfirm.setPadding(btnPadH, btnPadV, btnPadH, btnPadV);
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        confirmParams.gravity = Gravity.CENTER_HORIZONTAL;
        confirmParams.topMargin = dpToPx(16);
        btnConfirm.setLayoutParams(confirmParams);
        btnConfirm.setClickable(true);
        btnConfirm.setFocusable(true);
        root.addView(btnConfirm);

        wrapper.addView(root);

        ImageView btnClose = new ImageView(ctx);
        btnClose.setImageResource(R.drawable.ic_close);
        btnClose.setBackground(ContextCompat.getDrawable(ctx, R.drawable.bg_dialog_close));
        int closePad = dpToPx(6);
        btnClose.setPadding(closePad, closePad, closePad, closePad);
        int closeSize = dpToPx(48);
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(closeSize, closeSize);
        closeParams.gravity = Gravity.TOP | Gravity.START;
        closeParams.leftMargin = dpToPx(8);
        closeParams.topMargin = dpToPx(8);
        btnClose.setLayoutParams(closeParams);
        btnClose.setClickable(true);
        btnClose.setFocusable(true);
        wrapper.addView(btnClose);

        dialog.setContentView(wrapper);

        final boolean[] cancelled = {false};
        btnClose.setOnClickListener(v -> {
            cancelled[0] = true;
            dialog.dismiss();
        });
        btnConfirm.setOnClickListener(v -> dialog.dismiss());
        // 返回键/外部取消时标记 cancelled，避免 onConfirm 在非用户确认路径下执行
        dialog.setOnCancelListener(d -> cancelled[0] = true);

        registerPickerDialog(dialog);
        dialog.show();

        dialog.setOnDismissListener(d -> {
            unregisterPickerDialog((Dialog) d);
            if (cancelled[0]) return;
            onConfirm.run();
        });
    }

    private void showCustomPeriodPicker() {
        Context ctx = getContext();
        if (ctx == null) return;
        Dialog dialog = new Dialog(ctx);
        dialog.setTitle(getString(R.string.track_select_period));

        FrameLayout wrapper = new FrameLayout(ctx);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int padH = dpToPx(24);
        int padV = dpToPx(20);
        root.setPadding(padH, padV, padH, padV);

        TextView tvStartLabel = new TextView(ctx);
        tvStartLabel.setText(getString(R.string.custom_period_hint_start));
        tvStartLabel.setTextSize(14);
        tvStartLabel.setTextColor(ContextCompat.getColor(ctx, R.color.text_subtitle));
        root.addView(tvStartLabel);

        TextView tvStartDate = new TextView(ctx);
        tvStartDate.setTextSize(16);
        tvStartDate.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
        tvStartDate.setPadding(0, dpToPx(8), 0, dpToPx(16));
        String startDisplay = customStartDate != null ? customStartDate : new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().getTime());
        tvStartDate.setText(startDisplay);
        root.addView(tvStartDate);

        final String[] selectedStart = {customStartDate};
        final String[] selectedEnd = {customEndDate};

        tvStartDate.setOnClickListener(v -> {
            Calendar cal = Calendar.getInstance(Locale.US);
            if (selectedStart[0] != null) {
                try {
                    cal.setTime(new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(selectedStart[0]));
                } catch (Exception e) {
                    Log.w(TAG, "Invalid start date format: " + selectedStart[0], e);
                }
            }
            DatePickerDialog dpd = new DatePickerDialog(ctx,
                    (view, y, m, d) -> {
                        String date = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d);
                        selectedStart[0] = date;
                        tvStartDate.setText(date);
                    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
            dpd.getDatePicker().setMaxDate(System.currentTimeMillis());
            registerPickerDialog(dpd);
            dpd.setOnDismissListener(d -> unregisterPickerDialog((Dialog) d));
            dpd.show();
        });

        TextView tvEndLabel = new TextView(ctx);
        tvEndLabel.setText(getString(R.string.custom_period_hint_end));
        tvEndLabel.setTextSize(14);
        tvEndLabel.setTextColor(ContextCompat.getColor(ctx, R.color.text_subtitle));
        root.addView(tvEndLabel);

        TextView tvEndDate = new TextView(ctx);
        tvEndDate.setTextSize(16);
        tvEndDate.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
        tvEndDate.setPadding(0, dpToPx(8), 0, dpToPx(16));
        String endDisplay = customEndDate != null ? customEndDate : new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().getTime());
        tvEndDate.setText(endDisplay);
        root.addView(tvEndDate);

        tvEndDate.setOnClickListener(v -> {
            Calendar cal = Calendar.getInstance(Locale.US);
            if (selectedEnd[0] != null) {
                try {
                    cal.setTime(new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(selectedEnd[0]));
                } catch (Exception e) {
                    Log.w(TAG, "Invalid end date format: " + selectedEnd[0], e);
                }
            }
            DatePickerDialog dpd = new DatePickerDialog(ctx,
                    (view, y, m, d) -> {
                        String date = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d);
                        selectedEnd[0] = date;
                        tvEndDate.setText(date);
                    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
            dpd.getDatePicker().setMaxDate(System.currentTimeMillis());
            registerPickerDialog(dpd);
            dpd.setOnDismissListener(d -> unregisterPickerDialog((Dialog) d));
            dpd.show();
        });

        TextView btnConfirm = new TextView(ctx);
        btnConfirm.setText(getString(R.string.custom_period_confirm));
        btnConfirm.setTextSize(16);
        btnConfirm.setTextColor(ContextCompat.getColor(ctx, R.color.white));
        btnConfirm.setGravity(Gravity.CENTER);
        btnConfirm.setBackground(ContextCompat.getDrawable(ctx, R.drawable.bg_btn_submit));
        int btnPadH = dpToPx(32);
        int btnPadV = dpToPx(12);
        btnConfirm.setPadding(btnPadH, btnPadV, btnPadH, btnPadV);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.gravity = Gravity.CENTER_HORIZONTAL;
        btnParams.topMargin = dpToPx(12);
        btnConfirm.setLayoutParams(btnParams);
        btnConfirm.setClickable(true);
        btnConfirm.setFocusable(true);
        root.addView(btnConfirm);

        btnConfirm.setOnClickListener(v -> {
            if (selectedStart[0] != null && selectedEnd[0] != null) {
                if (selectedStart[0].compareTo(selectedEnd[0]) > 0) {
                    String tmp = selectedStart[0];
                    selectedStart[0] = selectedEnd[0];
                    selectedEnd[0] = tmp;
                }
                customStartDate = selectedStart[0];
                customEndDate = selectedEnd[0];
                android.app.Activity activity = getActivity();
                if (activity != null) {
                    activity.getSharedPreferences(PREFS_TRACK, Context.MODE_PRIVATE)
                            .edit()
                            .putString(KEY_CUSTOM_START, customStartDate)
                            .putString(KEY_CUSTOM_END, customEndDate)
                            .apply();
                }
                updatePeriodBadge();
                loadPeriodSummary(5, true);
            }
            dialog.dismiss();
        });

        wrapper.addView(root);

        ImageView btnClose = new ImageView(ctx);
        btnClose.setImageResource(R.drawable.ic_close);
        btnClose.setBackground(ContextCompat.getDrawable(ctx, R.drawable.bg_dialog_close));
        int closePad = dpToPx(6);
        btnClose.setPadding(closePad, closePad, closePad, closePad);
        int closeSize = dpToPx(48);
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(closeSize, closeSize);
        closeParams.gravity = Gravity.TOP | Gravity.START;
        closeParams.leftMargin = dpToPx(8);
        closeParams.topMargin = dpToPx(8);
        btnClose.setLayoutParams(closeParams);
        btnClose.setClickable(true);
        btnClose.setFocusable(true);
        btnClose.setOnClickListener(v -> dialog.dismiss());
        wrapper.addView(btnClose);

        dialog.setContentView(wrapper);
        registerPickerDialog(dialog);
        dialog.setOnDismissListener(d -> unregisterPickerDialog((Dialog) d));
        dialog.show();
    }
}
