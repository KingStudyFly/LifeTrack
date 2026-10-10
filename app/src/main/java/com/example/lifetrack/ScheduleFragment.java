package com.example.lifetrack;

import android.graphics.Paint;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import java.util.List;

/**
 * 待办页。待办不按日期分区，创建后一直保留，直到用户手动勾选完成：
 * 未完成在上方按创建时间排列，已完成沉底并置灰加中划线。
 * 来源为手动添加，或由日记保存时经AI 提取补充（见 RecordFragment）。
 */
public class ScheduleFragment extends Fragment {
    private static final String TAG = "TodoFragment";

    private TodoDao todoDao;
    private int loadToken;
    /** 缓存 application context，供后台线程刷新小组件时使用，避免持有 Activity Context */
    private android.content.Context appContext;

    private TextView tvProgress;
    private TextView tvPendingHeader;
    private TextView tvCompletedHeader;
    private TextView tvEmpty;
    private TextView tvAiStatus;
    private LinearLayout pendingContainer;
    private LinearLayout completedContainer;
    private LinearLayout completedSection;
    private EditText editQuickAdd;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_schedule, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        appContext = requireContext().getApplicationContext();
        todoDao = new TodoDao(requireContext());

        tvProgress = view.findViewById(R.id.tvTodoProgress);
        tvPendingHeader = view.findViewById(R.id.tvTodoPendingHeader);
        tvCompletedHeader = view.findViewById(R.id.tvTodoCompletedHeader);
        tvEmpty = view.findViewById(R.id.tvTodoEmpty);
        tvAiStatus = view.findViewById(R.id.tvTodoAiStatus);
        pendingContainer = view.findViewById(R.id.todoPendingContainer);
        completedContainer = view.findViewById(R.id.todoCompletedContainer);
        completedSection = view.findViewById(R.id.todoCompletedSection);
        editQuickAdd = view.findViewById(R.id.editTodoQuickAdd);

        view.findViewById(R.id.btnTodoAdd).setOnClickListener(v -> addTodo());
        editQuickAdd.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addTodo();
                return true;
            }
            return false;
        });

        tvAiStatus.setText(R.string.todo_tip_diary_source);
        reload();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden && isAdded() && getView() != null) reload();
    }

    private void reload() {
        loadTasks(++loadToken);
    }

    private void loadTasks(int token) {
        AppExecutors.io().execute(() -> {
            List<TodoItem> items = todoDao.queryAll();
            runOnUi(() -> {
                if (token == loadToken) render(items);
            });
            // 数据变化后同步刷新桌面小组件；在 IO 线程上不能调用 requireContext()，
            // 故使用 onCreateView 时缓存的 application context
            if (appContext != null) {
                TodoWidgetProvider.notifyDataChanged(appContext);
            }
        });
    }

    private void render(List<TodoItem> items) {
        pendingContainer.removeAllViews();
        completedContainer.removeAllViews();
        int completed = 0;
        for (TodoItem item : items) {
            if (item.completed) completed++;
            (item.completed ? completedContainer : pendingContainer).addView(createTodoRow(item));
        }
        int pending = items.size() - completed;
        tvProgress.setText(getString(R.string.todo_progress_format, completed, items.size()));
        tvPendingHeader.setText(getString(R.string.todo_pending_format, pending));
        tvCompletedHeader.setText(getString(R.string.todo_completed_format, completed));
        tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        completedSection.setVisibility(completed > 0 ? View.VISIBLE : View.GONE);
    }

    private View createTodoRow(TodoItem item) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_schedule_todo,
                item.completed ? completedContainer : pendingContainer, false);
        CheckBox checkBox = row.findViewById(R.id.checkTodoDone);
        TextView title = row.findViewById(R.id.tvScheduleTitle);
        TextView description = row.findViewById(R.id.tvScheduleDesc);
        TextView time = row.findViewById(R.id.tvScheduleTime);
        TextView source = row.findViewById(R.id.tvTodoSource);

        checkBox.setChecked(item.completed);
        title.setText(item.title);
        if (item.completed) {
            // 已完成：置灰 + 中划线
            title.setPaintFlags(title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            title.setTextColor(getResources().getColor(R.color.text_hint, null));
        }
        time.setText(TextUtils.isEmpty(item.time) ? getString(R.string.todo_anytime) : item.time);
        if (TextUtils.isEmpty(item.description)) {
            description.setVisibility(View.GONE);
        } else {
            description.setText(item.description);
            description.setVisibility(View.VISIBLE);
            if (item.completed) {
                description.setTextColor(getResources().getColor(R.color.text_hint, null));
            }
        }
        source.setVisibility("ai".equals(item.source) ? View.VISIBLE : View.GONE);

        checkBox.setOnCheckedChangeListener((button, checked) -> updateCompleted(item.id, checked));
        row.setOnClickListener(v -> checkBox.setChecked(!checkBox.isChecked()));
        row.setOnLongClickListener(v -> {
            confirmDelete(item);
            return true;
        });
        return row;
    }

    private void addTodo() {
        String title = editQuickAdd.getText().toString().trim();
        if (title.isEmpty()) {
            editQuickAdd.setError(getString(R.string.todo_title_required));
            return;
        }
        editQuickAdd.setText("");
        AppExecutors.io().execute(() -> {
            todoDao.insert(DateUtils.currentDate(), "", title, "", "manual");
            runOnUi(() -> {
                loadTasks(loadToken);
                Toast.makeText(requireContext(), R.string.todo_added, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void updateCompleted(long id, boolean completed) {
        AppExecutors.io().execute(() -> {
            todoDao.setCompleted(id, completed);
            // 重绘后未完成项在上、已完成项自动沉底
            runOnUi(() -> loadTasks(loadToken));
        });
    }

    private void confirmDelete(TodoItem item) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.todo_delete_title)
                .setMessage(item.title)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.todo_delete, (dialog, which) -> AppExecutors.io().execute(() -> {
                    todoDao.delete(item.id);
                    runOnUi(() -> loadTasks(loadToken));
                }))
                .show();
    }

    private void runOnUi(Runnable runnable) {
        if (!isAdded() || getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (isAdded() && getView() != null) runnable.run();
        });
    }
}
