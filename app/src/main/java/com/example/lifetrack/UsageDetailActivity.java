package com.example.lifetrack;

import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class UsageDetailActivity extends AppCompatActivity {

    private static final String KEY_PENDING_IMPORT_ENTRIES = "pending_import_entries";

    private ActivityResultLauncher<Intent> exportLauncher;
    private ActivityResultLauncher<Intent> importLauncher;

    private DiaryDao diaryDao;
    private AnalyticsDao analyticsDao;
    private DiaryDbHelper diaryDbHelper;
    private Dialog importConfirmDialog;
    // 暂存已解析但未确认导入的 entries，旋转后用于恢复确认对话框
    private JSONArray importConfirmEntries;

    // 防重复点击标志
    private boolean isExporting = false;
    private boolean isImporting = false;

    // 缓存 View 引用，避免 loadUsageStats 在 runOnUiThread 内重复 findViewById
    private View btnExportDiary;
    private View btnImportDiary;
    private TextView tvUsageTotalDuration;
    private TextView tvUsageDaysUsed;
    private TextView tvUsageAvgDuration;
    private TextView tvUsageTodayDuration;
    private TextView tvUsageTotalOpens;
    private TextView tvUsageTodayOpens;
    private LinearLayout featureUsageContainer;
    private LinearLayout recentDaysContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_usage_detail);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // 初始化 DAO 一次，避免每次操作都新建（AnalyticsDao/DiaryDao 内部已通过 DiaryDbHelper 单例复用 SQLiteDatabase）
        diaryDbHelper = DiaryDbHelper.getInstance(this);
        diaryDao = new DiaryDao(this);
        analyticsDao = new AnalyticsDao(this);

        ImageView btnBack = findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        btnExportDiary = findViewById(R.id.btnExportDiary);
        if (btnExportDiary != null) {
            btnExportDiary.setOnClickListener(v -> startExportDiary());
        }
        btnImportDiary = findViewById(R.id.btnImportDiary);
        if (btnImportDiary != null) {
            btnImportDiary.setOnClickListener(v -> startImportDiary());
        }

        // 一次性 findViewById 并缓存，避免 loadUsageStats 在 runOnUiThread 内重复查找
        tvUsageTotalDuration = findViewById(R.id.tvUsageTotalDuration);
        tvUsageDaysUsed = findViewById(R.id.tvUsageDaysUsed);
        tvUsageAvgDuration = findViewById(R.id.tvUsageAvgDuration);
        tvUsageTodayDuration = findViewById(R.id.tvUsageTodayDuration);
        tvUsageTotalOpens = findViewById(R.id.tvUsageTotalOpens);
        tvUsageTodayOpens = findViewById(R.id.tvUsageTodayOpens);
        featureUsageContainer = findViewById(R.id.featureUsageContainer);
        recentDaysContainer = findViewById(R.id.recentDaysContainer);

        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    // 回调返回后恢复按钮状态，允许用户再次点击
                    isExporting = false;
                    if (btnExportDiary != null) btnExportDiary.setEnabled(true);
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            performExport(uri);
                        }
                    }
                }
        );

        importLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    // 回调返回后恢复按钮状态，允许用户再次点击
                    isImporting = false;
                    if (btnImportDiary != null) btnImportDiary.setEnabled(true);
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            performImport(uri);
                        }
                    }
                }
        );

        // 旋转恢复：如果有未确认的导入 entries（已解析但用户未点击确认），重新弹出确认对话框
        if (savedInstanceState != null) {
            String pendingEntries = savedInstanceState.getString(KEY_PENDING_IMPORT_ENTRIES);
            if (pendingEntries != null) {
                try {
                    JSONArray entries = new JSONArray(pendingEntries);
                    showImportConfirmDialog(entries, entries.length());
                } catch (Exception e) {
                    // 恢复失败时忽略，用户可重新触发导入
                }
            }
        }

        AppExecutors.io().execute(this::loadUsageStats);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // 暂存已解析但未确认导入的 entries，旋转后恢复确认对话框
        if (importConfirmDialog != null && importConfirmDialog.isShowing() && importConfirmEntries != null) {
            outState.putString(KEY_PENDING_IMPORT_ENTRIES, importConfirmEntries.toString());
        }
    }

    private void startExportDiary() {
        if (isExporting) return;
        isExporting = true;
        if (btnExportDiary != null) btnExportDiary.setEnabled(false);
        String fileName = "lifetrack_diary_" +
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".json";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, fileName);
        exportLauncher.launch(intent);
    }

    private void performExport(Uri uri) {
        final DiaryDao dao = diaryDao;
        if (dao == null) return;
        AppExecutors.io().execute(() -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                List<String[]> all = dao.getAll();

                if (all.isEmpty()) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        Toast.makeText(this, R.string.export_diary_empty, Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                JSONArray jsonArray = new JSONArray();
                for (String[] entry : all) {
                    if (entry == null || entry.length < 2) continue;
                    JSONObject obj = new JSONObject();
                    obj.put("timestamp", entry[0]);
                    obj.put("content", entry[1]);
                    jsonArray.put(obj);
                }

                if (jsonArray.length() == 0) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        Toast.makeText(this, R.string.export_diary_empty, Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                JSONObject root = new JSONObject();
                root.put("version", 1);
                root.put("export_time",
                        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
                root.put("count", jsonArray.length());
                root.put("entries", jsonArray);

                String jsonStr = root.toString(2);

                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os != null) {
                        os.write(jsonStr.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    }
                }

                int count = jsonArray.length();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this,
                            getString(R.string.export_diary_success, count),
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, R.string.export_diary_failed, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void startImportDiary() {
        if (isImporting) return;
        isImporting = true;
        if (btnImportDiary != null) btnImportDiary.setEnabled(false);
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        importLauncher.launch(intent);
    }

    private void performImport(Uri uri) {
        AppExecutors.io().execute(() -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                // 用 try-with-resources 包裹 InputStream，避免 InputStreamReader 构造失败时 InputStream 泄漏
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    if (is == null) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(this, R.string.import_diary_failed, Toast.LENGTH_SHORT).show();
                        });
                        return;
                    }

                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(is, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                    }

                    JSONObject root = new JSONObject(sb.toString());
                    if (!root.has("entries")) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(this, R.string.import_diary_invalid_file, Toast.LENGTH_SHORT).show();
                        });
                        return;
                    }

                    JSONArray entries = root.getJSONArray("entries");
                    int entryCount = entries.length();

                    if (entryCount == 0) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(this, R.string.import_diary_invalid_file, Toast.LENGTH_SHORT).show();
                        });
                        return;
                    }

                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        showImportConfirmDialog(entries, entryCount);
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, R.string.import_diary_invalid_file, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showImportConfirmDialog(JSONArray entries, int entryCount) {
        // 先清理上一个未关闭的实例，避免重复堆叠
        if (importConfirmDialog != null && importConfirmDialog.isShowing()) {
            importConfirmDialog.dismiss();
        }
        Dialog dialog = DialogHelper.create(this, R.layout.dialog_verify_result, false);
        importConfirmDialog = dialog;
        importConfirmEntries = entries;

        ImageView ivIcon = dialog.findViewById(R.id.ivVerifyIcon);
        TextView tvTitle = dialog.findViewById(R.id.tvVerifyTitle);
        LinearLayout rowDeepSeek = dialog.findViewById(R.id.rowDeepSeek);
        View btnDialogOk = dialog.findViewById(R.id.btnDialogOk);

        if (ivIcon != null) {
            // background 已在 XML 中设置，此处仅设置图标，避免重复设置
            ivIcon.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_export));
        }
        if (tvTitle != null) {
            tvTitle.setText(getString(R.string.import_diary_confirm_message, entryCount));
        }
        if (rowDeepSeek != null) rowDeepSeek.setVisibility(View.GONE);

        if (btnDialogOk instanceof TextView) {
            ((TextView) btnDialogOk).setText(R.string.import_diary_confirm_ok);
        }
        if (btnDialogOk != null) {
            btnDialogOk.setOnClickListener(v -> {
                dialog.dismiss();
                executeImport(entries);
            });
        }

        DialogHelper.setCloseButton(dialog);
        dialog.show();
    }

    private void executeImport(JSONArray entries) {
        final DiaryDao dao = diaryDao;
        final DiaryDbHelper dbHelper = diaryDbHelper;
        if (dao == null || dbHelper == null) return;
        AppExecutors.io().execute(() -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                // 用事务包裹整个循环：失败可整体回滚，且批量插入性能远优于逐条自动提交
                SQLiteDatabase db = dbHelper.getWritableDb();
                int imported = 0;
                db.beginTransaction();
                try {
                    for (int i = 0; i < entries.length(); i++) {
                        JSONObject obj = entries.getJSONObject(i);
                        String timestamp = obj.optString("timestamp", "");
                        String content = obj.optString("content", "");
                        if (!timestamp.isEmpty() && !content.isEmpty()) {
                            dao.insert(timestamp, content);
                            imported++;
                        }
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }

                int finalImported = imported;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this,
                            getString(R.string.import_diary_success, finalImported),
                            Toast.LENGTH_SHORT).show();
                    AppExecutors.io().execute(this::loadUsageStats);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, R.string.import_diary_failed, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void loadUsageStats() {
        if (isFinishing() || isDestroyed()) return;
        final AnalyticsDao dao = analyticsDao;
        if (dao == null) return;

        long totalSeconds = dao.getTotalSeconds();
        int daysUsed = dao.getDaysUsed();
        long todaySeconds = dao.getTodaySeconds();
        long avgSeconds = daysUsed > 0 ? totalSeconds / daysUsed : 0;

        List<AnalyticsDao.DailyUsage> recent = dao.getRecentDailyUsage(7);

        int totalAppOpens = 0;
        int todayAppOpens = 0;
        int totalRecordOpens = 0;
        int totalTrackOpens = 0;
        int totalDiaryOpens = 0;
        int totalProfileOpens = 0;
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());

        List<AnalyticsDao.DailyUsage> all = dao.getAllDailyUsage();
        for (AnalyticsDao.DailyUsage du : all) {
            totalAppOpens += du.appOpens;
            totalRecordOpens += du.recordOpens;
            totalTrackOpens += du.trackOpens;
            totalDiaryOpens += du.diaryOpens;
            totalProfileOpens += du.profileOpens;
            if (today.equals(du.date)) {
                todayAppOpens = du.appOpens;
            }
        }

        int finalTotalAppOpens = totalAppOpens;
        int finalTodayAppOpens = todayAppOpens;
        int finalTotalRecordOpens = totalRecordOpens;
        int finalTotalTrackOpens = totalTrackOpens;
        int finalTotalDiaryOpens = totalDiaryOpens;
        int finalTotalProfileOpens = totalProfileOpens;

        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (tvUsageTotalDuration != null) tvUsageTotalDuration.setText(formatDuration(totalSeconds));
            if (tvUsageDaysUsed != null) tvUsageDaysUsed.setText(String.valueOf(daysUsed));
            if (tvUsageAvgDuration != null) tvUsageAvgDuration.setText(formatDuration(avgSeconds));
            if (tvUsageTodayDuration != null) tvUsageTodayDuration.setText(formatDuration(todaySeconds));
            if (tvUsageTotalOpens != null) tvUsageTotalOpens.setText(String.valueOf(finalTotalAppOpens));
            if (tvUsageTodayOpens != null) tvUsageTodayOpens.setText(String.valueOf(finalTodayAppOpens));

            buildFeatureUsageList(finalTotalRecordOpens, finalTotalTrackOpens, finalTotalDiaryOpens, finalTotalProfileOpens);
            buildRecentDaysList(recent);
        });
    }

    private void buildFeatureUsageList(int recordOpens, int trackOpens, int diaryOpens, int profileOpens) {
        LinearLayout container = featureUsageContainer;
        if (container == null) return;
        container.removeAllViews();

        int greenColor = ContextCompat.getColor(this, R.color.green_primary);
        int textColor = ContextCompat.getColor(this, R.color.text_primary);
        int borderColor = ContextCompat.getColor(this, R.color.border_light);
        int padV = getResources().getDimensionPixelSize(R.dimen.spacing_sm);
        float textSizePx = getResources().getDimension(R.dimen.text_md);

        String[] names = {
                getString(R.string.tab_record),
                getString(R.string.tab_track),
                getString(R.string.tab_diary),
                getString(R.string.tab_profile)
        };
        int[] counts = {recordOpens, trackOpens, diaryOpens, profileOpens};

        for (int i = 0; i < names.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, padV, 0, padV);

            TextView tvName = new TextView(this);
            tvName.setText(names[i]);
            tvName.setTextColor(textColor);
            tvName.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tvName.setLayoutParams(nameParams);
            row.addView(tvName);

            TextView tvCount = new TextView(this);
            tvCount.setText(counts[i] + getString(R.string.unit_count));
            tvCount.setTextColor(greenColor);
            tvCount.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx);
            row.addView(tvCount);

            container.addView(row);

            if (i < names.length - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(borderColor);
                LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1);
                divider.setLayoutParams(divParams);
                container.addView(divider);
            }
        }
    }

    private void buildRecentDaysList(List<AnalyticsDao.DailyUsage> recent) {
        LinearLayout container = recentDaysContainer;
        if (container == null) return;
        container.removeAllViews();

        SimpleDateFormat displayFmt = new SimpleDateFormat("M月d日 EEE", Locale.CHINESE);
        SimpleDateFormat parseFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

        int greenColor = ContextCompat.getColor(this, R.color.green_primary);
        int textColor = ContextCompat.getColor(this, R.color.text_primary);
        int borderColor = ContextCompat.getColor(this, R.color.border_light);
        int padV = getResources().getDimensionPixelSize(R.dimen.spacing_sm);
        float textSizePx = getResources().getDimension(R.dimen.text_md);

        for (int i = 0; i < recent.size(); i++) {
            AnalyticsDao.DailyUsage du = recent.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, padV, 0, padV);

            try {
                Date date = parseFmt.parse(du.date);
                TextView tvDate = new TextView(this);
                tvDate.setText(displayFmt.format(date));
                tvDate.setTextColor(textColor);
                tvDate.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx);
                LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                tvDate.setLayoutParams(dateParams);
                row.addView(tvDate);
            } catch (Exception e) {
                TextView tvDate = new TextView(this);
                tvDate.setText(du.date);
                tvDate.setTextColor(textColor);
                tvDate.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx);
                LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                tvDate.setLayoutParams(dateParams);
                row.addView(tvDate);
            }

            TextView tvDuration = new TextView(this);
            tvDuration.setText(formatDuration(du.foregroundSeconds));
            tvDuration.setTextColor(greenColor);
            tvDuration.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx);
            row.addView(tvDuration);

            container.addView(row);

            if (i < recent.size() - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(borderColor);
                LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1);
                divider.setLayoutParams(divParams);
                container.addView(divider);
            }
        }
    }

    private String formatDuration(long seconds) {
        if (seconds <= 0) return getString(R.string.unit_minute, 0);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        if (hours > 0) {
            return getString(R.string.unit_hour, hours)
                    + (minutes > 0 ? getString(R.string.unit_minute, minutes) : "");
        }
        return getString(R.string.unit_minute, minutes);
    }

    @Override
    protected void onDestroy() {
        // 防止窗口泄漏：销毁前关闭仍在显示的导入确认对话框
        if (importConfirmDialog != null && importConfirmDialog.isShowing()) {
            importConfirmDialog.dismiss();
        }
        importConfirmDialog = null;
        importConfirmEntries = null;
        super.onDestroy();
    }
}
