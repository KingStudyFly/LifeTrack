package com.example.lifetrack;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class DiaryFragment extends Fragment {

    private static final String TAG = "DiaryFragment";
    // savedInstanceState 中保存当前显示月份 Calendar 的 key
    private static final String KEY_CURRENT_CAL = "current_cal_time";

    private TextView tvMonthYear;
    private GridView calendarGrid;
    private TextView tvSelectedDate;
    private TextView tvDiaryEmpty;
    private LinearLayout diaryEntryList;
    private ImageView btnPrevMonth;
    private NestedScrollView nestedScrollView;
    private View diaryContentArea;
    private SwipeRefreshLayout swipeRefreshLayout;

    private EditText editSearch;
    private TextView btnClearSearch;
    private LinearLayout searchResultList;
    private TextView tvSearchResultTitle;
    private TextView tvSearchEmpty;
    private boolean isSearchMode = false;
    private boolean suppressSearchWatcher = false;
    private Handler searchHandler;
    private Runnable searchRunnable;

    private Calendar currentCal;
    private CalendarAdapter adapter;
    private DiaryDao diaryDao;
    private final AtomicInteger refreshGeneration = new AtomicInteger(0);
    private final AtomicInteger loadGeneration = new AtomicInteger(0);

    // 缓存 GridView item 的 measure 结果，避免每次刷新都重新 measure（性能优化）
    private int cachedItemHeight = -1;
    // 编辑 Dialog 成员字段，用于在 onDestroyView 时主动 dismiss，避免窗口泄漏
    private Dialog editDialog;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_diary, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvMonthYear = view.findViewById(R.id.tvMonthYear);
        calendarGrid = view.findViewById(R.id.calendarGrid);
        tvSelectedDate = view.findViewById(R.id.tvSelectedDate);
        tvDiaryEmpty = view.findViewById(R.id.tvDiaryEmpty);
        diaryEntryList = view.findViewById(R.id.diaryEntryList);
        btnPrevMonth = view.findViewById(R.id.btnPrevMonth);
        ImageView btnNextMonth = view.findViewById(R.id.btnNextMonth);
        nestedScrollView = view.findViewById(R.id.nestedScrollView);
        diaryContentArea = view.findViewById(R.id.diaryContentArea);

        swipeRefreshLayout = view.findViewById(R.id.swipeRefresh);
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setColorSchemeColors(ContextCompat.getColor(requireContext(), R.color.green_primary));
            swipeRefreshLayout.setOnRefreshListener(() -> {
                refreshCalendar();
                swipeRefreshLayout.postDelayed(() -> swipeRefreshLayout.setRefreshing(false), 800);
            });
        }

        disableGridViewScroll();

        LinearLayout weekHeader = view.findViewById(R.id.weekHeader);
        if (weekHeader != null) {
            buildWeekHeader(weekHeader);
        }

        currentCal = Calendar.getInstance();
        // 使用 ApplicationContext 避免持有 Activity Context 导致内存泄漏
        diaryDao = new DiaryDao(requireContext().getApplicationContext());

        adapter = new CalendarAdapter(requireContext(), (year, month, day) -> {
            Log.d(TAG, "onDayClick: year=" + year + " month=" + month + " day=" + day);
            if (isSearchMode) {
                suppressSearchWatcher = true;
                if (editSearch != null) editSearch.setText("");
                suppressSearchWatcher = false;
                exitSearchMode();
            }
            updateSelectedDateLabel(year, month, day);
            loadDiaryForDate(year, month, day);
        });
        if (calendarGrid != null) {
            calendarGrid.setAdapter(adapter);
        }

        if (btnPrevMonth != null) {
            btnPrevMonth.setOnClickListener(v -> {
                currentCal.add(Calendar.MONTH, -1);
                refreshCalendar();
            });
        }

        if (btnNextMonth != null) {
            btnNextMonth.setOnClickListener(v -> {
                currentCal.add(Calendar.MONTH, 1);
                refreshCalendar();
            });
        }

        if (tvMonthYear != null) {
            tvMonthYear.setOnClickListener(v -> showMonthPickerDialog());

            tvMonthYear.setOnLongClickListener(v -> {
                showDebugDump();
                return true;
            });
        }

        initSearch(view);

        refreshCalendar();
    }

    private void initSearch(View view) {
        editSearch = view.findViewById(R.id.editSearch);
        btnClearSearch = view.findViewById(R.id.btnClearSearch);
        searchResultList = view.findViewById(R.id.searchResultList);
        tvSearchResultTitle = view.findViewById(R.id.tvSearchResultTitle);
        tvSearchEmpty = view.findViewById(R.id.tvSearchEmpty);
        searchHandler = new Handler(Looper.getMainLooper());

        if (editSearch != null) {
            editSearch.setOnEditorActionListener((v, actionId, event) -> {
                performSearch(v.getText().toString().trim());
                return true;
            });

            editSearch.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (suppressSearchWatcher) return;
                    String text = s.toString().trim();
                    if (text.isEmpty()) {
                        if (btnClearSearch != null) btnClearSearch.setVisibility(View.GONE);
                        exitSearchMode();
                        CalendarAdapter.DayItem selected = adapter != null ? adapter.getSelectedDay() : null;
                        if (selected != null) {
                            loadDiaryForDate(selected.year, selected.month, selected.day);
                        }
                    } else {
                        if (btnClearSearch != null) btnClearSearch.setVisibility(View.VISIBLE);
                        scheduleSearch(text);
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        }

        if (btnClearSearch != null) {
            btnClearSearch.setOnClickListener(v -> {
                suppressSearchWatcher = true;
                if (editSearch != null) editSearch.setText("");
                suppressSearchWatcher = false;
                exitSearchMode();
                CalendarAdapter.DayItem selected = adapter != null ? adapter.getSelectedDay() : null;
                if (selected != null) {
                    loadDiaryForDate(selected.year, selected.month, selected.day);
                }
            });
        }
    }

    private void scheduleSearch(String keyword) {
        if (searchHandler == null) return;
        if (searchRunnable != null) {
            searchHandler.removeCallbacks(searchRunnable);
        }
        searchRunnable = () -> performSearch(keyword);
        searchHandler.postDelayed(searchRunnable, 400);
    }

    private void performSearch(String keyword) {
        if (keyword.isEmpty()) {
            exitSearchMode();
            return;
        }

        isSearchMode = true;
        if (tvDiaryEmpty != null) tvDiaryEmpty.setVisibility(View.GONE);
        if (diaryEntryList != null) diaryEntryList.setVisibility(View.GONE);
        if (searchResultList != null) searchResultList.setVisibility(View.VISIBLE);
        if (tvSearchResultTitle != null) {
            tvSearchResultTitle.setVisibility(View.VISIBLE);
            tvSearchResultTitle.setText(getString(R.string.diary_search_result_count, 0));
        }
        if (tvSearchEmpty != null) tvSearchEmpty.setVisibility(View.GONE);

        AppExecutors.io().execute(() -> {
            List<String[]> results = diaryDao.searchByKeyword(keyword);
            if (!isAdded()) return;
            runOnUiIfAlive(() -> {
                if (!isSearchMode) return;
                if (searchResultList == null) return;

                searchResultList.removeAllViews();

                if (results.isEmpty()) {
                    if (tvSearchResultTitle != null) tvSearchResultTitle.setVisibility(View.GONE);
                    if (tvSearchEmpty != null) tvSearchEmpty.setVisibility(View.VISIBLE);
                } else {
                    if (tvSearchResultTitle != null) {
                        tvSearchResultTitle.setText(getString(R.string.diary_search_result_count, results.size()));
                        tvSearchResultTitle.setVisibility(View.VISIBLE);
                    }
                    if (tvSearchEmpty != null) tvSearchEmpty.setVisibility(View.GONE);

                    for (int i = 0; i < results.size(); i++) {
                        String[] entry = results.get(i);
                        if (entry == null || entry.length < 3) continue;
                        android.app.Activity activity = getActivity();
                        // 使用 break 而非 return，避免已加入列表的部分条目丢失
                        if (activity == null || activity.isFinishing()) break;
                        View itemView = LayoutInflater.from(activity)
                                .inflate(R.layout.item_search_result, searchResultList, false);

                        TextView tvDate = itemView.findViewById(R.id.tvSearchDate);
                        TextView tvContent = itemView.findViewById(R.id.tvSearchContent);

                        String timestamp = entry[1];
                        String content = entry[2];

                        String datePart;
                        if (timestamp != null && timestamp.length() >= 10) {
                            datePart = timestamp.substring(0, 10);
                            if (timestamp.length() > 11) {
                                datePart += " " + timestamp.substring(11);
                            }
                        } else {
                            datePart = timestamp != null ? timestamp : "";
                        }
                        if (tvDate != null) tvDate.setText(datePart);
                        if (tvContent != null) tvContent.setText(content != null ? content : "");

                        final String entryId = entry[0];
                        itemView.setOnClickListener(v -> showEditDialog(entryId, timestamp, content));

                        searchResultList.addView(itemView);
                    }
                }
            });
        });
    }

    private void exitSearchMode() {
        isSearchMode = false;
        if (searchRunnable != null && searchHandler != null) {
            searchHandler.removeCallbacks(searchRunnable);
        }
        if (searchResultList != null) {
            searchResultList.removeAllViews();
            searchResultList.setVisibility(View.GONE);
        }
        if (tvSearchResultTitle != null) tvSearchResultTitle.setVisibility(View.GONE);
        if (tvSearchEmpty != null) tvSearchEmpty.setVisibility(View.GONE);
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden && getView() != null) {
            refreshCalendar();
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        // 保存当前显示的月份，旋转/配置变化后可恢复
        if (currentCal != null) {
            outState.putLong(KEY_CURRENT_CAL, currentCal.getTimeInMillis());
        }
    }

    @Override
    public void onDestroyView() {
        // 彻底清空 Message 队列，避免 onDestroyView 后延迟搜索任务触发 View 访问导致 NPE
        if (searchHandler != null) {
            searchHandler.removeCallbacksAndMessages(null);
        }
        // 主动 dismiss 编辑 Dialog，避免 Activity 销毁后窗口泄漏 WindowManager$BadTokenException
        if (editDialog != null && editDialog.isShowing()) {
            editDialog.dismiss();
        }
        editDialog = null;
        // 重置 measure 缓存，下次进入 Fragment 时重新 measure 一次
        cachedItemHeight = -1;
        tvMonthYear = null;
        calendarGrid = null;
        tvSelectedDate = null;
        tvDiaryEmpty = null;
        diaryEntryList = null;
        btnPrevMonth = null;
        nestedScrollView = null;
        diaryContentArea = null;
        swipeRefreshLayout = null;
        editSearch = null;
        btnClearSearch = null;
        searchResultList = null;
        tvSearchResultTitle = null;
        tvSearchEmpty = null;
        adapter = null;
        super.onDestroyView();
    }

    /**
     * 安全地在主线程执行 UI 更新。
     * 检查 Fragment 是否仍附加且 Activity 未 finishing，避免 requireActivity() 抛异常。
     */
    private void runOnUiIfAlive(Runnable action) {
        if (!isAdded()) return;
        android.app.Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) return;
        activity.runOnUiThread(action);
    }

    private void buildWeekHeader(LinearLayout container) {
        if (container == null) return;
        String[] labels = {
                getString(R.string.week_mon), getString(R.string.week_tue),
                getString(R.string.week_wed), getString(R.string.week_thu),
                getString(R.string.week_fri), getString(R.string.week_sat),
                getString(R.string.week_sun)
        };
        for (String label : labels) {
            TextView tv = new TextView(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tv.setLayoutParams(lp);
            tv.setText(label);
            tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_subtitle));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_sm));
            tv.setGravity(Gravity.CENTER);
            container.addView(tv);
        }
    }

    private void refreshCalendar() {
        if (currentCal == null) return;
        int year = currentCal.get(Calendar.YEAR);
        int month = currentCal.get(Calendar.MONTH);

        if (tvMonthYear != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy年M月", Locale.CHINA);
            tvMonthYear.setText(sdf.format(currentCal.getTime()));
        }

        if (adapter != null) {
            adapter.setMonth(year, month);
        }
        updateGridViewHeight();

        int gen = refreshGeneration.incrementAndGet();

        AppExecutors.io().execute(() -> {
            try {
                Set<Integer> diaryDaysInMonth = diaryDao.getDiaryDaysForMonth(year, month);
                Map<Integer, Integer> dayCounts = diaryDao.getDiaryDaysWithCounts(year, month);
                if (isAdded()) {
                    runOnUiIfAlive(() -> {
                        if (gen != refreshGeneration.get()) return;
                        if (adapter == null) return;
                        adapter.setDiaryDays(diaryDaysInMonth);
                        adapter.setDiaryDayCounts(dayCounts);
                        updateGridViewHeight();
                        CalendarAdapter.DayItem selected = adapter.getSelectedDay();
                        if (selected != null) {
                            updateSelectedDateLabel(selected.year, selected.month, selected.day);
                            loadDiaryForDate(selected.year, selected.month, selected.day);
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "refreshCalendar: getDiaryDaysForMonth failed", e);
            }
        });
    }

    private void refreshCurrentDate() {
        if (adapter == null) {
            refreshCalendar();
            return;
        }
        CalendarAdapter.DayItem selected = adapter.getSelectedDay();
        if (selected == null) {
            refreshCalendar();
            return;
        }

        int gen = refreshGeneration.incrementAndGet();

        AppExecutors.io().execute(() -> {
            try {
                Set<Integer> diaryDaysInMonth = diaryDao.getDiaryDaysForMonth(selected.year, selected.month);
                Map<Integer, Integer> dayCounts = diaryDao.getDiaryDaysWithCounts(selected.year, selected.month);
                if (isAdded()) {
                    runOnUiIfAlive(() -> {
                        if (gen != refreshGeneration.get()) return;
                        if (adapter == null) return;
                        adapter.setDiaryDays(diaryDaysInMonth);
                        adapter.setDiaryDayCounts(dayCounts);
                        updateGridViewHeight();
                        loadDiaryForDate(selected.year, selected.month, selected.day);
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "refreshCurrentDate: getDiaryDaysForMonth failed", e);
            }
        });
    }

    private void updateSelectedDateLabel(int year, int month, int day) {
        if (tvSelectedDate == null) return;
        Calendar cal = Calendar.getInstance();
        cal.set(year, month, day);
        SimpleDateFormat sdf = new SimpleDateFormat("M月d日 EEEE", Locale.CHINA);
        tvSelectedDate.setText(sdf.format(cal.getTime()));
    }

    private void loadDiaryForDate(int year, int month, int day) {
        String dateStr = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day);
        Log.d(TAG, "loadDiaryForDate: dateStr=" + dateStr);

        int gen = loadGeneration.incrementAndGet();

        AppExecutors.io().execute(() -> {
            List<String[]> entries = diaryDao.queryByDate(dateStr);
            Log.d(TAG, "loadDiaryForDate: found " + entries.size() + " entries");
            if (!isAdded()) return;
            runOnUiIfAlive(() -> {
                if (gen != loadGeneration.get()) return;
                if (diaryEntryList == null) return;

                diaryEntryList.removeAllViews();

                if (entries.isEmpty()) {
                    if (tvDiaryEmpty != null) tvDiaryEmpty.setVisibility(View.VISIBLE);
                    diaryEntryList.setVisibility(View.GONE);
                } else {
                    if (tvDiaryEmpty != null) tvDiaryEmpty.setVisibility(View.GONE);
                    diaryEntryList.setVisibility(View.VISIBLE);

                    for (int i = 0; i < entries.size(); i++) {
                        String[] entry = entries.get(i);
                        if (entry == null || entry.length < 3) continue;
                        android.app.Activity activity = getActivity();
                        // 使用 break 而非 return，避免已加入列表的部分条目丢失
                        if (activity == null || activity.isFinishing()) break;
                        View itemView = LayoutInflater.from(activity)
                                .inflate(R.layout.item_diary_entry, diaryEntryList, false);

                        TextView tvTime = itemView.findViewById(R.id.tvEntryTime);
                        TextView tvContent = itemView.findViewById(R.id.tvEntryContent);
                        View divider = itemView.findViewById(R.id.divider);
                        TextView btnEdit = itemView.findViewById(R.id.btnEditEntry);

                        String entryId = entry[0];
                        String timestamp = entry[1];
                        String content = entry[2];

                        String timePart;
                        if (timestamp != null && timestamp.length() > 11) {
                            timePart = timestamp.substring(11);
                        } else {
                            timePart = timestamp != null ? timestamp : "";
                        }

                        if (tvTime != null) tvTime.setText(timePart);
                        if (tvContent != null) tvContent.setText(content != null ? content : "");

                        if (btnEdit != null) {
                            btnEdit.setOnClickListener(v -> showEditDialog(entryId, timestamp, content));
                        }

                        if (i == entries.size() - 1 && divider != null) {
                            divider.setVisibility(View.GONE);
                        }

                        diaryEntryList.addView(itemView);
                    }
                }
            });
        });
    }

    private void showEditDialog(String entryId, String timestamp, String content) {
        // 保存为成员字段，便于在 onDestroyView 时主动 dismiss，避免窗口泄漏
        editDialog = DialogHelper.create(requireContext(), R.layout.dialog_edit_diary);
        Dialog dialog = editDialog;

        TextView tvEditTimestamp = dialog.findViewById(R.id.tvEditTimestamp);
        EditText editContent = dialog.findViewById(R.id.editDiaryContent);
        View btnSave = dialog.findViewById(R.id.btnSaveEdit);
        View btnCancel = dialog.findViewById(R.id.btnCancelEdit);
        View btnDelete = dialog.findViewById(R.id.btnDeleteDiary);

        if (tvEditTimestamp != null) tvEditTimestamp.setText(timestamp);
        if (editContent != null) {
            editContent.setText(content != null ? content : "");
            editContent.setSelection(editContent.getText().length());
        }

        if (btnSave != null) {
            btnSave.setOnClickListener(v -> {
                String newContent = editContent != null ? editContent.getText().toString().trim() : "";
                if (newContent.isEmpty()) {
                    android.app.Activity activity = getActivity();
                    if (activity != null && !activity.isFinishing()) {
                        Toast.makeText(activity, getString(R.string.save_error_empty), Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                long parsedId;
                try {
                    parsedId = Long.parseLong(entryId);
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Invalid entryId on update: " + entryId, e);
                    android.app.Activity activity = getActivity();
                    if (activity != null && !activity.isFinishing()) {
                        Toast.makeText(activity, getString(R.string.error_data_error), Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                dialog.dismiss();
                // 数据库操作移到 IO 线程，避免阻塞 UI
                AppExecutors.io().execute(() -> {
                    int result = diaryDao.update(parsedId, newContent);
                    runOnUiIfAlive(() -> {
                        android.app.Activity a = getActivity();
                        if (a == null || a.isFinishing()) return;
                        if (result > 0) {
                            Toast.makeText(a, getString(R.string.diary_update_success), Toast.LENGTH_SHORT).show();
                            refreshCurrentDate();
                        } else {
                            Toast.makeText(a, getString(R.string.diary_update_failed), Toast.LENGTH_SHORT).show();
                        }
                    });
                });
            });
        }

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dialog.dismiss());
        }

        if (btnDelete != null) {
            btnDelete.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
                long parsedId;
                try {
                    parsedId = Long.parseLong(entryId);
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Invalid entryId on delete: " + entryId, e);
                    android.app.Activity activity = getActivity();
                    if (activity != null && !activity.isFinishing()) {
                        Toast.makeText(activity, getString(R.string.error_data_error), Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                dialog.dismiss();
                // 数据库操作移到 IO 线程，避免阻塞 UI
                AppExecutors.io().execute(() -> {
                    int result = diaryDao.delete(parsedId);
                    runOnUiIfAlive(() -> {
                        android.app.Activity a = getActivity();
                        if (a == null || a.isFinishing()) return;
                        if (result > 0) {
                            Toast.makeText(a, getString(R.string.diary_delete_success), Toast.LENGTH_SHORT).show();
                            refreshCurrentDate();
                        } else {
                            Toast.makeText(a, getString(R.string.diary_delete_failed), Toast.LENGTH_SHORT).show();
                        }
                    });
                });
            });
        }

        DialogHelper.setCloseButton(dialog);
        dialog.show();
    }

    private void disableGridViewScroll() {
        if (calendarGrid == null) return;
        calendarGrid.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });
    }

    private void updateGridViewHeight() {
        if (calendarGrid == null || adapter == null) return;
        int count = adapter.getCount();
        if (count == 0) return;
        int columns = 7;
        int rows = (int) Math.ceil((double) count / columns);
        // 性能优化：缓存 measure 结果，避免每次刷新都重新 measure
        // 注意：measure 在主线程同步执行，月切换会连续触发 refreshCalendar + updateGridViewHeight，
        // 复用缓存值可显著降低主线程开销。布局不变所以单次 measure 即可。
        if (cachedItemHeight <= 0) {
            View listItem = adapter.getView(0, null, calendarGrid);
            if (listItem == null) return;
            listItem.measure(0, 0);
            cachedItemHeight = listItem.getMeasuredHeight();
            if (cachedItemHeight <= 0) return;
        }
        int totalHeight = cachedItemHeight * rows;
        totalHeight += calendarGrid.getVerticalSpacing() * (rows - 1);
        ViewGroup.LayoutParams params = calendarGrid.getLayoutParams();
        if (params == null) return;
        params.height = totalHeight;
        calendarGrid.setLayoutParams(params);
    }

    private void showMonthPickerDialog() {
        Dialog dialog = DialogHelper.create(requireContext(), R.layout.dialog_month_picker);

        NumberPicker pickerYear = dialog.findViewById(R.id.pickerYear);
        NumberPicker pickerMonth = dialog.findViewById(R.id.pickerMonth);

        int currentYear = currentCal.get(Calendar.YEAR);
        int currentMonth = currentCal.get(Calendar.MONTH);

        if (pickerYear != null) {
            pickerYear.setMinValue(2000);
            pickerYear.setMaxValue(2100);
            pickerYear.setValue(currentYear);
            pickerYear.setWrapSelectorWheel(false);
        }

        if (pickerMonth != null) {
            pickerMonth.setMinValue(1);
            pickerMonth.setMaxValue(12);
            pickerMonth.setValue(currentMonth + 1);
            pickerMonth.setWrapSelectorWheel(false);

            String[] monthDisplay = new String[12];
            for (int i = 0; i < 12; i++) {
                monthDisplay[i] = getString(R.string.diary_month_format, i + 1);
            }
            pickerMonth.setDisplayedValues(monthDisplay);
        }

        View btnConfirm = dialog.findViewById(R.id.btnMonthPickerConfirm);
        View btnCancel = dialog.findViewById(R.id.btnMonthPickerCancel);

        if (btnConfirm != null) {
            btnConfirm.setOnClickListener(v -> {
                int year = pickerYear != null ? pickerYear.getValue() : currentYear;
                int month = (pickerMonth != null ? pickerMonth.getValue() : currentMonth + 1) - 1;
                currentCal.set(year, month, 1);
                refreshCalendar();
                dialog.dismiss();
            });
        }

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dialog.dismiss());
        }

        DialogHelper.setCloseButton(dialog);
        dialog.show();
    }

    private void showDebugDump() {
        // 使用 ApplicationContext 避免异步任务持有 Activity 引用导致泄漏
        final Context ctx = requireContext().getApplicationContext();
        AppExecutors.io().execute(() -> {
            final int count = diaryDao.getCount();
            final List<String[]> all = diaryDao.getAll();
            if (!isAdded()) return;
            runOnUiIfAlive(() -> {
                StringBuilder sb = new StringBuilder();
                sb.append("数据库共 ").append(count).append(" 条记录:\n\n");
                for (String[] entry : all) {
                    if (entry == null || entry.length < 2) continue;
                    sb.append("[").append(entry[0]).append("] ").append(entry[1]).append("\n");
                }
                if (all.isEmpty()) {
                    sb.append("(空)");
                }
                Toast.makeText(ctx, sb.toString(), Toast.LENGTH_LONG).show();
                Log.d(TAG, "Debug dump: " + sb.toString());
            });
        });
    }
}
