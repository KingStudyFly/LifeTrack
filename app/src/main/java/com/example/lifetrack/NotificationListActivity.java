package com.example.lifetrack;

import android.Manifest;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Locale;

/**
 * 定时提醒二级界面（AlarmManager.setAlarmClock 方案）。
 *
 * 列表开关 = 是否调度闹钟：
 * - ON：调度 setAlarmClock，到点系统拉起 AlarmReceiver 发通知
 * - OFF：取消闹钟，本地保留配置
 *
 * 编辑保存时若已启用，自动重新调度以保持时间/内容一致。
 */
public class NotificationListActivity extends AppCompatActivity {

    private NotificationHelper helper;
    private NotificationListAdapter adapter;
    private Dialog currentDialog;
    private View emptyState;

    /** 切开关但缺通知权限时记录待操作项 */
    private NotificationItem pendingEnableItem;

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    if (pendingEnableItem != null) {
                        boolean ok = helper.setEnabled(pendingEnableItem.getId(), true);
                        Toast.makeText(this,
                                ok ? R.string.notification_alarm_enabled
                                   : R.string.notification_alarm_failed,
                                Toast.LENGTH_SHORT).show();
                        if (ok) showAutoStartGuideIfNeeded();
                    }
                } else {
                    Toast.makeText(this, R.string.notification_permission_required,
                            Toast.LENGTH_SHORT).show();
                }
                pendingEnableItem = null;
                refreshList();
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_notification_list);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootContainer), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        helper = new NotificationHelper(this);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnAdd).setOnClickListener(v -> showEditDialog(null));
        emptyState = findViewById(R.id.emptyState);

        RecyclerView rv = findViewById(R.id.recyclerNotifications);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new NotificationListAdapter(new NotificationListAdapter.Listener() {
            @Override
            public void onItemClick(NotificationItem item) {
                showEditDialog(item);
            }

            @Override
            public void onEnabledToggle(NotificationItem item, boolean enabled) {
                if (enabled && !hasNotificationPermission()) {
                    // 缺通知权限：记录待开启项，申请权限，回调里自动调度
                    pendingEnableItem = item;
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                    refreshList();
                    return;
                }
                boolean ok = helper.setEnabled(item.getId(), enabled);
                Toast.makeText(NotificationListActivity.this,
                        enabled ? (ok ? R.string.notification_alarm_enabled
                                     : R.string.notification_alarm_failed)
                               : (ok ? R.string.notification_alarm_disabled
                                     : R.string.notification_alarm_failed),
                        Toast.LENGTH_SHORT).show();
                if (enabled && ok) {
                    showAutoStartGuideIfNeeded();
                }
                refreshList();
            }
        });
        rv.setAdapter(adapter);

        refreshList();
    }

    /** Android 13+ 需要运行时申请通知权限 */
    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    /** 首次开启闹钟时引导用户开启自启动权限（国产 ROM 必需） */
    private void showAutoStartGuideIfNeeded() {
        SharedPreferences sp = getSharedPreferences("lifetrack_prefs", MODE_PRIVATE);
        if (sp.getBoolean("autostart_guided", false)) return;
        sp.edit().putBoolean("autostart_guided", true).apply();
        new AlertDialog.Builder(this)
                .setTitle(R.string.notification_autostart_title)
                .setMessage(R.string.notification_autostart_message)
                .setPositiveButton(R.string.notification_autostart_go_settings,
                        (d, w) -> {
                            try {
                                Intent intent = new Intent();
                                intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                intent.setData(Uri.parse("package:" + getPackageName()));
                                startActivity(intent);
                            } catch (Exception e) {
                                Toast.makeText(this, R.string.notification_autostart_open_failed,
                                        Toast.LENGTH_SHORT).show();
                            }
                        })
                .setNegativeButton(R.string.notification_autostart_later, null)
                .show();
    }

    @Override
    protected void onDestroy() {
        if (currentDialog != null && currentDialog.isShowing()) {
            currentDialog.dismiss();
        }
        currentDialog = null;
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
    }

    private void refreshList() {
        List<NotificationItem> items = helper.getAll();
        adapter.submitList(items);
        emptyState.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /**
     * 弹出新增 / 编辑对话框。
     * @param item null 表示新增；非 null 表示编辑该项
     */
    private void showEditDialog(@Nullable NotificationItem item) {
        if (currentDialog != null && currentDialog.isShowing()) {
            currentDialog.dismiss();
        }
        currentDialog = DialogHelper.create(this, R.layout.dialog_notification_settings, true);
        Dialog dialog = currentDialog;

        boolean isEdit = item != null;
        int hour = isEdit ? item.getHour() : NotificationHelper.DEFAULT_HOUR;
        int minute = isEdit ? item.getMinute() : NotificationHelper.DEFAULT_MINUTE;
        String content = isEdit ? item.getContent() : "";

        TextView tvTitle = dialog.findViewById(R.id.tvNotificationTitle);
        if (tvTitle != null) {
            tvTitle.setText(isEdit ? R.string.notification_edit_title : R.string.notification_add_title);
        }
        TextView btnConfirm = dialog.findViewById(R.id.btnNotificationConfirm);
        if (btnConfirm != null) {
            btnConfirm.setText(isEdit ? R.string.notification_save : R.string.notification_add);
        }

        View btnDelete = dialog.findViewById(R.id.btnNotificationDelete);
        if (btnDelete != null) {
            btnDelete.setVisibility(isEdit ? View.VISIBLE : View.GONE);
            btnDelete.setOnClickListener(v -> {
                if (item != null) {
                    helper.remove(item.getId());
                    Toast.makeText(this, R.string.notification_deleted, Toast.LENGTH_SHORT).show();
                }
                dialog.dismiss();
                refreshList();
            });
        }

        TimePicker picker = dialog.findViewById(R.id.timePicker);
        if (picker != null) {
            picker.setHour(hour);
            picker.setMinute(minute);
            picker.setIs24HourView(true);
        }
        EditText etContent = dialog.findViewById(R.id.etContent);
        if (etContent != null) {
            etContent.setText(content);
            if (!isEdit) {
                etContent.requestFocus();
            }
        }

        // 隐藏旧的"同步到日历"按钮（由列表开关控制调度状态）
        View btnToggleCalendar = dialog.findViewById(R.id.btnToggleCalendar);
        if (btnToggleCalendar != null) {
            btnToggleCalendar.setVisibility(View.GONE);
        }

        if (btnConfirm != null) {
            btnConfirm.setOnClickListener(v -> {
                int newHour = picker != null ? picker.getHour() : NotificationHelper.DEFAULT_HOUR;
                int newMinute = picker != null ? picker.getMinute() : NotificationHelper.DEFAULT_MINUTE;
                String newContent = etContent != null ? etContent.getText().toString().trim() : "";

                if (TextUtils.isEmpty(newContent)) {
                    Toast.makeText(this, R.string.notification_content_empty,
                            Toast.LENGTH_SHORT).show();
                    return;
                }

                if (isEdit) {
                    item.setHour(newHour);
                    item.setMinute(newMinute);
                    item.setContent(newContent);
                    helper.update(item); // update 内部：enabled 则重新调度
                    Toast.makeText(this,
                            getString(R.string.notification_updated,
                                    String.format(Locale.US, "%02d:%02d", newHour, newMinute)),
                            Toast.LENGTH_SHORT).show();
                } else {
                    helper.add(newHour, newMinute, newContent, false);
                    Toast.makeText(this,
                            getString(R.string.notification_added,
                                    String.format(Locale.US, "%02d:%02d", newHour, newMinute)),
                            Toast.LENGTH_SHORT).show();
                }
                dialog.dismiss();
                refreshList();
            });
        }

        View btnCancel = dialog.findViewById(R.id.btnNotificationCancel);
        if (btnCancel != null) btnCancel.setOnClickListener(v -> dialog.dismiss());
        DialogHelper.setCloseButton(dialog);
        dialog.setOnDismissListener(d -> {
            if (currentDialog == dialog) currentDialog = null;
        });
        dialog.show();
    }
}
