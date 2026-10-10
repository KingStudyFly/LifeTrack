package com.example.lifetrack;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class ProfileFragment extends Fragment {

    private static final String TAG = "ProfileFragment";
    // 头像目标尺寸（像素），保存到内部存储时按此值缩放，避免大图占用内存
    private static final int AVATAR_TARGET_PX = 256;

    private TextView tvStatRecords;
    private TextView tvStatDays;
    private TextView tvStatStreak;
    private View btnVerifyKey;
    private View btnSettings;
    private LinearLayout menuContainer;
    private ImageView ivAvatar;
    private View avatarArea;

    // Dialog 引用：在 onDestroyView 中 dismiss，避免窗口泄漏
    private AlertDialog themeDialog;
    private AlertDialog widgetDialog;
    private Dialog verifyResultDialog;

    // 头像文件（内部存储 filesDir/avatar.jpg），与 SharedPreferences 中记录的路径同步
    private File avatarFile;

    // 在字段初始化时注册 ActivityResultLauncher，确保在 Fragment STARTED 之前完成注册。
    // 回调内的 requireContext()/getString()/getActivity() 在结果返回时（Fragment 已 STARTED）调用，安全。
    private final ActivityResultLauncher<String> createDocumentLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.CreateDocument("application/json"),
                    uri -> {
                        if (uri == null) return;
                        if (!isAdded() || getContext() == null) return;
                        Toast.makeText(requireContext(), R.string.export_diary_progress, Toast.LENGTH_SHORT).show();
                        final Context appCtx = requireContext().getApplicationContext();
                        AppExecutors.io().execute(() -> {
                            int count = DiaryExportHelper.exportToUri(appCtx, uri);
                            if (!isAdded() || getView() == null) return;
                            Activity activity = getActivity();
                            if (activity == null || activity.isFinishing()) return;
                            activity.runOnUiThread(() -> {
                                if (!isAdded() || getView() == null) return;
                                if (count > 0) {
                                    Toast.makeText(requireContext(),
                                            getString(R.string.export_diary_success, count),
                                            Toast.LENGTH_SHORT).show();
                                } else if (count == 0) {
                                    Toast.makeText(requireContext(),
                                            R.string.export_diary_empty,
                                            Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(requireContext(),
                                            R.string.export_diary_failed,
                                            Toast.LENGTH_SHORT).show();
                                }
                            });
                        });
                    });

    private final ActivityResultLauncher<String[]> openDocumentLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri == null) return;
                        if (!isAdded() || getContext() == null) return;
                        Toast.makeText(requireContext(), R.string.import_diary_progress, Toast.LENGTH_SHORT).show();
                        final Context appCtx = requireContext().getApplicationContext();
                        AppExecutors.io().execute(() -> {
                            int count = DiaryExportHelper.importFromUri(appCtx, uri);
                            if (!isAdded() || getView() == null) return;
                            Activity activity = getActivity();
                            if (activity == null || activity.isFinishing()) return;
                            activity.runOnUiThread(() -> {
                                if (!isAdded() || getView() == null) return;
                                if (count > 0) {
                                    Toast.makeText(requireContext(),
                                            getString(R.string.import_diary_success, count),
                                            Toast.LENGTH_SHORT).show();
                                    loadStats();
                                } else {
                                    Toast.makeText(requireContext(),
                                            count == 0 ? R.string.import_diary_invalid_file : R.string.import_diary_failed,
                                            Toast.LENGTH_SHORT).show();
                                }
                            });
                        });
                    });

    // 头像选择 launcher：使用 GetContent 选取单张图片，自动处理权限（READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE）
    private final ActivityResultLauncher<String> avatarPickerLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.GetContent(),
                    uri -> {
                        if (uri == null) return;
                        if (!isAdded() || getContext() == null) return;
                        final Context appCtx = requireContext().getApplicationContext();
                        AppExecutors.io().execute(() -> {
                            Bitmap bitmap = null;
                            try {
                                bitmap = ImageUtils.decodeSampledBitmap(appCtx, uri, AVATAR_TARGET_PX, AVATAR_TARGET_PX);
                            } catch (Exception e) {
                                Log.e(TAG, "decode avatar uri failed", e);
                            }
                            if (bitmap == null) {
                                runOnUiIfAlive(() -> Toast.makeText(appCtx,
                                        R.string.profile_avatar_picker_failed, Toast.LENGTH_SHORT).show());
                                return;
                            }

                            // 中心方形裁剪 + 缩放到目标尺寸，避免比例失真
                            Bitmap cropped = cropCenterSquare(bitmap, AVATAR_TARGET_PX);
                            if (cropped != bitmap) {
                                bitmap.recycle();
                            }

                            // 保存到内部存储 filesDir/avatar.jpg（私有目录，无需存储权限）
                            File outFile = new File(appCtx.getFilesDir(), "avatar.jpg");
                            boolean saved = false;
                            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                saved = cropped.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                            } catch (Exception e) {
                                Log.e(TAG, "save avatar failed", e);
                            }
                            if (!saved) {
                                cropped.recycle();
                                runOnUiIfAlive(() -> Toast.makeText(appCtx,
                                        R.string.profile_avatar_save_failed, Toast.LENGTH_SHORT).show());
                                return;
                            }

                            // 持久化路径（用于下次进入页面时直接读文件加载，避免依赖 MediaStore Uri 跨进程失效）
                            appCtx.getSharedPreferences("lifetrack_profile", Context.MODE_PRIVATE)
                                    .edit().putString("avatar_path", outFile.getAbsolutePath()).apply();

                            final Bitmap finalCropped = cropped;
                            runOnUiIfAlive(() -> {
                                if (ivAvatar != null) {
                                // XML 中默认 app:tint 给 ic_person 着色（走 setImageTintList 通道）。
                                // 加载真实头像后必须用 setImageTintList(null) 清除 tint，
                                // setColorFilter(null) 只清 Paint 的 colorFilter，不影响 tintList，会导致头像被染棕色。
                                ivAvatar.setImageTintList(null);
                                ivAvatar.setImageBitmap(finalCropped);
                            }
                                Toast.makeText(appCtx,
                                        R.string.profile_avatar_changed, Toast.LENGTH_SHORT).show();
                            });
                        });
                    });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_profile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvStatRecords = view.findViewById(R.id.tvStatRecords);
        tvStatDays = view.findViewById(R.id.tvStatDays);
        tvStatStreak = view.findViewById(R.id.tvStatStreak);
        btnVerifyKey = view.findViewById(R.id.btnVerifyKey);
        btnSettings = view.findViewById(R.id.btnSettings);
        menuContainer = view.findViewById(R.id.menuContainer);
        ivAvatar = view.findViewById(R.id.ivAvatar);
        avatarArea = view.findViewById(R.id.avatarArea);

        loadStats();
        loadAvatar();

        if (avatarArea != null) {
            // 点击头像区域：启动系统图片选择器（GetContent 自动处理 READ_MEDIA_IMAGES 权限）
            avatarArea.setOnClickListener(v ->
                    avatarPickerLauncher.launch("image/*"));
        }

        if (btnVerifyKey != null) {
            btnVerifyKey.setOnClickListener(v -> verifyConnection());
        }
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v ->
                    Toast.makeText(requireContext(), R.string.profile_feature_in_development, Toast.LENGTH_SHORT).show());
        }

        addMenuItem(R.id.menuExport,
                R.drawable.bg_menu_icon_green, R.drawable.ic_export,
                R.string.profile_menu_export, R.string.profile_menu_export_desc,
                v -> startActivity(new Intent(requireContext(), UsageDetailActivity.class)));
        addMenuItem(R.id.menuExportDiary,
                R.drawable.bg_menu_icon_green, R.drawable.ic_export,
                R.string.export_diary_title, R.string.export_diary_desc,
                v -> createDocumentLauncher.launch(DiaryExportHelper.generateExportFileName()));
        addMenuItem(R.id.menuImportDiary,
                R.drawable.bg_menu_icon_brown, R.drawable.ic_about,
                R.string.import_diary_title, R.string.import_diary_desc,
                v -> openDocumentLauncher.launch(new String[]{"application/json"}));
        addMenuItem(R.id.menuPreferences,
                R.drawable.bg_menu_icon_brown, R.drawable.ic_settings,
                R.string.profile_menu_preferences, R.string.profile_menu_preferences_desc,
                v -> Toast.makeText(requireContext(), R.string.profile_feature_in_development, Toast.LENGTH_SHORT).show());
        addMenuItem(R.id.menuAbout,
                R.drawable.bg_menu_icon_accent, R.drawable.ic_about,
                R.string.profile_menu_about, R.string.profile_menu_about_desc,
                v -> Toast.makeText(requireContext(), R.string.profile_feature_in_development, Toast.LENGTH_SHORT).show());
        addMenuItem(R.id.menuTheme,
                R.drawable.bg_menu_icon_accent, R.drawable.ic_settings,
                R.string.profile_menu_theme, R.string.profile_menu_theme_desc,
                v -> showThemePickerDialog());
        addMenuItem(R.id.menuTagFill,
                R.drawable.bg_menu_icon_green, R.drawable.ic_settings,
                R.string.profile_tag_fill_title, R.string.profile_menu_export_desc,
                v -> startTagFill());
        addMenuItem(R.id.menuNotification,
                R.drawable.bg_menu_icon_gold, R.drawable.ic_notification,
                R.string.profile_menu_notification, R.string.profile_menu_notification_desc,
                v -> startActivity(new Intent(requireContext(), NotificationListActivity.class)));
        addMenuItem(R.id.menuWidget,
                R.drawable.bg_menu_icon_accent, R.drawable.ic_calendar,
                R.string.widget_label, R.string.widget_todo_description,
                v -> showWidgetSettingsDialog());
    }

    /**
     * 桌面小组件设置：背景不透明度（0-100%）与最多显示条数（1-10）。
     * 调整后立即写回偏好并刷新桌面小组件，无需重启应用。
     */
    private void showWidgetSettingsDialog() {
        if (!isAdded() || getContext() == null) return;
        final Context ctx = requireContext();
        View content = LayoutInflater.from(ctx).inflate(R.layout.dialog_widget_settings, null, false);
        final SeekBar seekAlpha = content.findViewById(R.id.seekWidgetAlpha);
        final SeekBar seekMax = content.findViewById(R.id.seekWidgetMaxItems);
        final TextView tvAlpha = content.findViewById(R.id.tvWidgetAlphaValue);
        final TextView tvMax = content.findViewById(R.id.tvWidgetMaxItemsValue);
        final ImageView preview = content.findViewById(R.id.viewWidgetAlphaPreview);
        final TextView previewText = content.findViewById(R.id.tvWidgetAlphaPreviewText);

        final int savedAlpha = TodoWidgetPrefs.getBgAlpha(ctx);
        final int savedMax = TodoWidgetPrefs.getMaxItems(ctx);
        seekAlpha.setProgress(savedAlpha);
        seekMax.setProgress(savedMax - 1);

        applyAlphaPreview(preview, previewText, tvAlpha, savedAlpha);
        tvMax.setText(getString(R.string.widget_max_items_desc_value, savedMax));

        seekAlpha.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                applyAlphaPreview(preview, previewText, tvAlpha, progress);
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                int value = bar.getProgress();
                TodoWidgetPrefs.setBgAlpha(ctx, value);
                TodoWidgetProvider.notifyDataChanged(ctx.getApplicationContext());
            }
        });

        seekMax.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                tvMax.setText(getString(R.string.widget_max_items_desc_value, progress + 1));
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                int value = bar.getProgress() + 1;
                TodoWidgetPrefs.setMaxItems(ctx, value);
                TodoWidgetProvider.notifyDataChanged(ctx.getApplicationContext());
            }
        });

        widgetDialog = new AlertDialog.Builder(ctx)
                .setTitle(R.string.widget_settings_title)
                .setView(content)
                .setPositiveButton(R.string.custom_period_confirm, null)
                .setOnDismissListener(d -> widgetDialog = null)
                .show();
    }

    /**
     * 预览：背景透明度跟随滑块，文字颜色与组件实际渲染保持一致
     * （背景过亮时白字会看不清，故同步切深色）。
     * 预览用 setImageAlpha 而非 setAlpha，与 Provider 保持同一套机制。
     */
    private void applyAlphaPreview(ImageView preview, TextView previewText,
                                   TextView label, int alphaPercent) {
        if (preview != null) {
            preview.setImageAlpha(Math.round(alphaPercent * 255f / 100f));
        }
        if (previewText != null) {
            previewText.setTextColor(ContextCompat.getColor(requireContext(),
                    TodoWidgetPrefs.needsDarkText(alphaPercent)
                            ? R.color.text_primary : android.R.color.white));
        }
        if (label != null) {
            label.setText(getString(R.string.widget_alpha_value, alphaPercent));
        }
    }

    /** 屏蔽未使用的 onStart / onProgressChanged 抽象方法，减少匿名类噪声 */
    private abstract static class SimpleSeekBarListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar bar) {}

        @Override
        public abstract void onProgressChanged(SeekBar bar, int progress, boolean fromUser);

        @Override
        public abstract void onStopTrackingTouch(SeekBar bar);
    }

    /**
     * 批量补全未打标签的日记。
     * 1. 查询未标记日记数量
     * 2. 弹窗确认
     * 3. 串行调用 AI，200ms 间隔，失败重试 2 次
     * 4. 进度对话框显示进度
     */
    private void startTagFill() {
        if (!isAdded() || getContext() == null) return;
        final Context ctx = requireContext().getApplicationContext();

        AppExecutors.io().execute(() -> {
            DiaryDao diaryDao = new DiaryDao(ctx);
            final int count = diaryDao.getUnlabeledCount();
            if (count == 0) {
                runOnUiIfAlive(() -> Toast.makeText(ctx, R.string.profile_tag_fill_nothing, Toast.LENGTH_SHORT).show());
                return;
            }

            // 弹窗确认
            runOnUiIfAlive(() -> {
                if (!isAdded()) return;
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.profile_tag_fill_title)
                        .setMessage(getString(R.string.profile_tag_fill_summary_format, count) + "\n\n" + getString(R.string.profile_tag_fill_confirm))
                        .setPositiveButton(android.R.string.ok, (d, w) -> executeTagFill(ctx))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        });
    }

    /**
     * 执行批量打标签：串行调用 AI，200ms 间隔，失败重试 2 次。
     */
    private void executeTagFill(Context ctx) {
        if (!isAdded()) return;
        // 进度对话框
        final android.app.Dialog[] progressHolder = new android.app.Dialog[1];

        AppExecutors.io().execute(() -> {
            DiaryDao diaryDao = new DiaryDao(ctx);
            TagDao tagDao = new TagDao(ctx);
            List<String> availableTags = tagDao.getAllTagNames();
            if (availableTags.isEmpty()) {
                runOnUiIfAlive(() -> Toast.makeText(ctx, R.string.profile_tag_fill_nothing, Toast.LENGTH_SHORT).show());
                return;
            }

            List<String[]> unlabeled = diaryDao.getUnlabeledDiaries();
            final int total = unlabeled.size();
            int success = 0;
            int failed = 0;

            // 显示进度对话框
            runOnUiIfAlive(() -> {
                if (!isAdded()) return;
                android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(requireContext());
                builder.setTitle(R.string.profile_tag_fill_title);
                builder.setMessage(getString(R.string.profile_tag_fill_progress_format, 0, total));
                builder.setCancelable(false);
                progressHolder[0] = builder.create();
                progressHolder[0].show();
            });

            for (int i = 0; i < total; i++) {
                String[] entry = unlabeled.get(i);
                if (entry == null || entry.length < 2) {
                    failed++;
                    continue;
                }
                long diaryId = -1;
                try {
                    diaryId = Long.parseLong(entry[0]);
                } catch (Exception e) {
                    failed++;
                    continue;
                }
                String content = entry[1];

                // 重试 2 次（共 3 次尝试）
                boolean done = false;
                for (int attempt = 0; attempt < 3 && !done; attempt++) {
                    List<String> tags = DeepSeekClient.tagDiarySync(content, availableTags);
                    if (tags != null && !tags.isEmpty()) {
                        tagDao.setTagsForDiary(diaryId, tags);
                        success++;
                        done = true;
                    } else {
                        // 重试间隔 300ms
                        try { Thread.sleep(300); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    }
                }
                if (!done) failed++;

                // 更新进度
                final int current = i + 1;
                runOnUiIfAlive(() -> {
                    if (progressHolder[0] != null && progressHolder[0].isShowing()) {
                        ((android.app.AlertDialog) progressHolder[0]).setMessage(getString(R.string.profile_tag_fill_progress_format, current, total));
                    }
                });

                // 间隔 200ms
                try { Thread.sleep(200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }

            // 完成
            final int finalSuccess = success;
            final int finalFailed = failed;
            runOnUiIfAlive(() -> {
                if (!isAdded()) return;
                if (progressHolder[0] != null && progressHolder[0].isShowing()) {
                    progressHolder[0].dismiss();
                }
                String msg = getString(R.string.profile_tag_fill_done_format, finalSuccess, finalFailed);
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void showThemePickerDialog() {
        if (!isAdded() || getContext() == null) return;
        final Context ctx = requireContext();
        int currentMode = ThemeHelper.getMode(ctx);
        String[] labels = {
                getString(R.string.theme_mode_light),
                getString(R.string.theme_mode_dark),
                getString(R.string.theme_mode_system)
        };
        int[] modes = {ThemeHelper.MODE_LIGHT, ThemeHelper.MODE_DARK, ThemeHelper.MODE_SYSTEM};
        int checked = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i] == currentMode) { checked = i; break; }
        }
        themeDialog = new AlertDialog.Builder(ctx)
                .setTitle(R.string.theme_dialog_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    int newMode = modes[which];
                    if (newMode != currentMode) {
                        ThemeHelper.applyMode(ctx, newMode);
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.diary_cancel, null)
                .setOnDismissListener(d -> themeDialog = null)
                .show();
    }

    private void verifyConnection() {
        btnVerifyKey.setEnabled(false);
        if (btnVerifyKey instanceof TextView) {
            ((TextView) btnVerifyKey).setText(getString(R.string.profile_api_verifying));
        }

        AtomicBoolean deepSeekDone = new AtomicBoolean(false);
        AtomicBoolean doubaoDone = new AtomicBoolean(false);
        AtomicBoolean deepSeekSuccess = new AtomicBoolean(false);
        AtomicBoolean doubaoSuccess = new AtomicBoolean(false);
        String[] deepSeekError = {null};
        String[] doubaoError = {null};

        Runnable mergeResults = () -> {
            if (!deepSeekDone.get() || !doubaoDone.get()) return;
            if (!isAdded() || getView() == null) return;
            Activity activity = getActivity();
            if (activity == null || activity.isFinishing()) return;
            activity.runOnUiThread(() -> {
                if (!isAdded() || getView() == null) return;
                if (btnVerifyKey != null) {
                    btnVerifyKey.setEnabled(true);
                    if (btnVerifyKey instanceof TextView) {
                        ((TextView) btnVerifyKey).setText(getString(R.string.profile_api_verify));
                    }
                }

                boolean analyzerOk = deepSeekSuccess.get() && doubaoSuccess.get();
                String analyzerError;
                if (analyzerOk) {
                    analyzerError = null;
                } else if (!deepSeekSuccess.get() && !doubaoSuccess.get()) {
                    analyzerError = getString(R.string.profile_verify_all_failed);
                } else if (!deepSeekSuccess.get()) {
                    analyzerError = deepSeekError[0] != null ? deepSeekError[0] : getString(R.string.profile_verify_connect_failed);
                } else {
                    analyzerError = doubaoError[0] != null ? doubaoError[0] : getString(R.string.profile_verify_connect_failed);
                }

                showVerifyResultDialog(analyzerOk, analyzerError);
            });
        };

        DeepSeekClient.verifyApiKey(new DeepSeekClient.VerifyCallback() {
            @Override
            public void onSuccess() {
                deepSeekSuccess.set(true);
                deepSeekDone.set(true);
                mergeResults.run();
            }

            @Override
            public void onFailure(String message) {
                deepSeekError[0] = message;
                deepSeekDone.set(true);
                mergeResults.run();
            }
        });

        DoubaoClient.verifyConnection(new DoubaoClient.VerifyCallback() {
            @Override
            public void onSuccess() {
                doubaoSuccess.set(true);
                doubaoDone.set(true);
                mergeResults.run();
            }

            @Override
            public void onFailure(String message) {
                doubaoError[0] = message;
                doubaoDone.set(true);
                mergeResults.run();
            }
        });
    }

    private void showVerifyResultDialog(boolean analyzerOk, String analyzerError) {
        verifyResultDialog = DialogHelper.create(requireContext(), R.layout.dialog_verify_result, false);
        Dialog dialog = verifyResultDialog;

        ImageView ivIcon = dialog.findViewById(R.id.ivVerifyIcon);
        TextView tvTitle = dialog.findViewById(R.id.tvVerifyTitle);

        ImageView ivDeepSeekStatus = dialog.findViewById(R.id.ivDeepSeekStatus);
        TextView tvDeepSeekLabel = dialog.findViewById(R.id.tvDeepSeekLabel);
        TextView tvDeepSeekDetail = dialog.findViewById(R.id.tvDeepSeekDetail);

        if (analyzerOk) {
            if (ivIcon != null) {
                ivIcon.setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.bg_dialog_icon));
                ivIcon.setImageDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.ic_check));
            }
            if (tvTitle != null) tvTitle.setText(getString(R.string.dialog_verify_success_title));
        } else {
            if (ivIcon != null) {
                ivIcon.setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.bg_dialog_icon_error));
                ivIcon.setImageDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.ic_close));
            }
            if (tvTitle != null) tvTitle.setText(getString(R.string.dialog_verify_fail_title));
        }

        Drawable checkIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_check);
        Drawable closeIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_close);
        int greenColor = ContextCompat.getColor(requireContext(), R.color.green_primary);
        int errorColor = ContextCompat.getColor(requireContext(), R.color.brown_accent);

        if (analyzerOk) {
            if (ivDeepSeekStatus != null) {
                ivDeepSeekStatus.setImageDrawable(checkIcon);
                ivDeepSeekStatus.setColorFilter(greenColor);
            }
            if (tvDeepSeekLabel != null) tvDeepSeekLabel.setText(getString(R.string.verify_service_analyzer));
            if (tvDeepSeekDetail != null) {
                tvDeepSeekDetail.setText(getString(R.string.verify_status_ok));
                tvDeepSeekDetail.setTextColor(greenColor);
            }
        } else {
            if (ivDeepSeekStatus != null) {
                ivDeepSeekStatus.setImageDrawable(closeIcon);
                ivDeepSeekStatus.setColorFilter(errorColor);
            }
            if (tvDeepSeekLabel != null) tvDeepSeekLabel.setText(getString(R.string.verify_service_analyzer));
            if (tvDeepSeekDetail != null) {
                tvDeepSeekDetail.setText(analyzerError != null ? analyzerError : getString(R.string.verify_status_fail));
                tvDeepSeekDetail.setTextColor(errorColor);
            }
        }

        View btnDialogOk = dialog.findViewById(R.id.btnDialogOk);
        if (btnDialogOk != null) {
            btnDialogOk.setOnClickListener(v -> dialog.dismiss());
        }

        DialogHelper.setCloseButton(dialog);
        dialog.setOnDismissListener(d -> verifyResultDialog = null);
        dialog.show();
    }

    private View addMenuItem(int id, int iconBgRes, int iconRes,
                             int titleRes, int descRes,
                             View.OnClickListener listener) {
        if (menuContainer.getChildCount() > 0) {
            View divider = new View(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1));
            lp.setMarginStart(dpToPx(68));
            lp.setMarginEnd(dpToPx(24));
            divider.setLayoutParams(lp);
            divider.setBackgroundResource(R.color.border_light);
            menuContainer.addView(divider);
        }

        View item = LayoutInflater.from(requireContext()).inflate(
                R.layout.item_profile_menu, menuContainer, false);
        item.setId(id);
        View iconBg = item.findViewById(R.id.menuIconBg);
        if (iconBg != null) iconBg.setBackgroundResource(iconBgRes);
        View menuIcon = item.findViewById(R.id.menuIcon);
        if (menuIcon instanceof ImageView) ((ImageView) menuIcon).setImageResource(iconRes);
        View menuTitle = item.findViewById(R.id.menuTitle);
        if (menuTitle instanceof TextView) ((TextView) menuTitle).setText(titleRes);
        View menuDesc = item.findViewById(R.id.menuDesc);
        if (menuDesc instanceof TextView) ((TextView) menuDesc).setText(descRes);
        if (listener != null) {
            item.setOnClickListener(listener);
        }
        menuContainer.addView(item);
        return item;
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void loadStats() {
        if (!isAdded() || getView() == null) return;

        final Context ctx = requireContext().getApplicationContext();
        AppExecutors.io().execute(() -> {
            try {
                DiaryDao diaryDao = new DiaryDao(ctx);

                int totalRecords = diaryDao.getCount();
                int uniqueDayCount = diaryDao.getUniqueDayCount();
                Set<String> uniqueDays = diaryDao.getUniqueDays();
                int streak = calculateStreak(uniqueDays);

                if (isAdded() && getView() != null) {
                    Activity activity = getActivity();
                    if (activity != null && !activity.isFinishing()) {
                        activity.runOnUiThread(() -> {
                            if (!isAdded() || getView() == null) return;
                            if (tvStatRecords != null) tvStatRecords.setText(String.valueOf(totalRecords));
                            if (tvStatDays != null) tvStatDays.setText(String.valueOf(uniqueDayCount));
                            if (tvStatStreak != null) tvStatStreak.setText(String.valueOf(streak));
                        });
                    }
                }
            } catch (Exception e) {
                Log.e("ProfileFragment", "loadStats failed", e);
                if (isAdded() && getView() != null) {
                    Activity activity = getActivity();
                    if (activity != null && !activity.isFinishing()) {
                        activity.runOnUiThread(() -> {
                            if (!isAdded() || getView() == null) return;
                            if (tvStatRecords != null) tvStatRecords.setText("0");
                            if (tvStatDays != null) tvStatDays.setText("0");
                            if (tvStatStreak != null) tvStatStreak.setText("0");
                        });
                    }
                }
            }
        });
    }

    private int calculateStreak(Set<String> uniqueDays) {
        if (uniqueDays.isEmpty()) return 0;

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Calendar cal = Calendar.getInstance(Locale.US);
        int streak = 0;

        String today = sdf.format(cal.getTime());
        if (!uniqueDays.contains(today)) {
            cal.add(Calendar.DAY_OF_MONTH, -1);
            String yesterday = sdf.format(cal.getTime());
            if (!uniqueDays.contains(yesterday)) return 0;
        }

        cal = Calendar.getInstance(Locale.US);
        if (!uniqueDays.contains(sdf.format(cal.getTime()))) {
            cal.add(Calendar.DAY_OF_MONTH, -1);
        }

        while (true) {
            String dateStr = sdf.format(cal.getTime());
            if (uniqueDays.contains(dateStr)) {
                streak++;
                cal.add(Calendar.DAY_OF_MONTH, -1);
            } else {
                break;
            }
        }

        return streak;
    }

    /**
     * 加载已保存的头像。从 SharedPreferences 读取路径，文件存在则解码显示。
     * 解码放在 io 线程，避免大图阻塞主线程。
     */
    private void loadAvatar() {
        if (!isAdded() || getContext() == null) return;
        final Context appCtx = requireContext().getApplicationContext();
        String path = appCtx.getSharedPreferences("lifetrack_profile", Context.MODE_PRIVATE)
                .getString("avatar_path", null);
        if (path == null) return;
        avatarFile = new File(path);
        if (!avatarFile.exists()) return;

        AppExecutors.io().execute(() -> {
            Bitmap bmp = null;
            try {
                bmp = BitmapFactory.decodeFile(path);
            } catch (Exception e) {
                Log.e(TAG, "loadAvatar: decode failed", e);
            }
            if (bmp == null) {
                runOnUiIfAlive(() -> Toast.makeText(appCtx,
                        R.string.profile_avatar_load_failed, Toast.LENGTH_SHORT).show());
                return;
            }
            final Bitmap finalBmp = bmp;
            runOnUiIfAlive(() -> {
                if (ivAvatar != null) {
                    // 同 avatarPickerLauncher：用 setImageTintList(null) 清除 XML 中的 app:tint，避免真实头像被染色
                    ivAvatar.setImageTintList(null);
                    ivAvatar.setImageBitmap(finalBmp);
                }
            });
        });
    }

    /**
     * 中心方形裁剪并缩放到 targetSize。若原图已是方形且尺寸匹配，原样返回。
     */
    private Bitmap cropCenterSquare(Bitmap src, int targetSize) {
        if (src == null) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        int side = Math.min(w, h);
        int left = (w - side) / 2;
        int top = (h - side) / 2;
        Bitmap square = Bitmap.createBitmap(src, left, top, side, side);
        if (side == targetSize) return square;
        Bitmap scaled = Bitmap.createScaledBitmap(square, targetSize, targetSize, true);
        if (scaled != square) square.recycle();
        return scaled;
    }

    /**
     * 在 Fragment 仍存活时切回 UI 线程执行，避免异步回调中 requireActivity() 抛 IllegalStateException。
     */
    private void runOnUiIfAlive(Runnable action) {
        Activity activity = getActivity();
        if (activity == null || activity.isFinishing() || !isAdded()) return;
        activity.runOnUiThread(action);
    }

    @Override
    public void onDestroyView() {
        // 先 dismiss Dialog 再清理引用，最后调用 super，避免窗口泄漏
        if (themeDialog != null && themeDialog.isShowing()) {
            themeDialog.dismiss();
        }
        themeDialog = null;
        if (widgetDialog != null && widgetDialog.isShowing()) {
            widgetDialog.dismiss();
        }
        widgetDialog = null;
        if (verifyResultDialog != null && verifyResultDialog.isShowing()) {
            verifyResultDialog.dismiss();
        }
        verifyResultDialog = null;
        tvStatRecords = null;
        tvStatDays = null;
        tvStatStreak = null;
        btnVerifyKey = null;
        btnSettings = null;
        menuContainer = null;
        ivAvatar = null;
        avatarArea = null;
        avatarFile = null;
        super.onDestroyView();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            loadStats();
        }
    }
}
