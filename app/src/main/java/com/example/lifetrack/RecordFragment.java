package com.example.lifetrack;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;



import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class RecordFragment extends Fragment {

    private static final String TAG = "RecordFragment";

    private LinearLayout btnModeImage;
    private LinearLayout btnModeAudio;
    private ImageView iconModeImage;
    private ImageView iconModeAudio;
    private TextView labelModeImage;
    private TextView labelModeAudio;
    private EditText editInput;
    private TextView tvCharCount;
    private View btnSave;

    // 小回顾相关字段
    private LinearLayout flashbackSection;
    private TextView tvFlashbackTag;
    private TextView btnRefreshFlashback;
    private LinearLayout flashbackCard1;
    private LinearLayout flashbackCard2;
    private LinearLayout flashbackCard3;
    private TextView fbDate1;
    private TextView fbText1;
    private TextView fbReflection1;
    private TextView fbDate2;
    private TextView fbText2;
    private TextView fbDate3;
    private TextView fbText3;
    private TextView tvFlashbackEmpty;
    private TextView tvFlashbackLoading;
    // 当日选中的 3 篇日记 id（可能不足 3 篇），点击卡片时用其查询完整内容
    private long[] flashbackSelectedIds = new long[0];
    // 感想对话框，onDestroyView 时主动 dismiss，避免窗口泄漏
    private Dialog reflectionDialog;

    private TagDao tagDao;
    private ReflectionDao reflectionDao;

    private boolean imageSelected = true;
    private DiaryDao diaryDao;
    private DimensionDao dimensionDao;
    private TodoDao todoDao;
    // 流式识别文本节流：避免每个 partial 都触发 setText 引发布局重排
    private long lastStreamUpdateMs = 0;
    private static final long STREAM_UPDATE_INTERVAL_MS = 100;
    private Dialog analyzingDialog;
    private TextView tvAnalyzingProgress;
    // 临时 Dialog 引用，onDestroyView 时主动 dismiss，避免窗口泄漏
    private Dialog successDialog;
    private Dialog errorDialog;
    private Dialog imagePickerDialog;

    private static final int MAX_GALLERY_IMAGES = 20;
    private static final int MAX_RECORDING_SECONDS = 300;
    private static final int MAX_AUDIO_FILE_BYTES = 8 * 1024 * 1024;
    // DoubaoClient 批量识别返回的错误占位标记，包含此串的结果视为失败
    private static final String ANALYSIS_FAILED_MARKER = "分析失败";
    // 待办提取时送入模型的日记正文长度上限
    private static final int MAX_TODO_EXTRACT_CHARS = 6000;

    private Uri cameraImageUri;

    private MediaRecorder mediaRecorder;
    private File audioFile;
    private volatile boolean isRecording = false;
    private Dialog recordingDialog;
    private TextView tvRecordingTime;
    private Handler recordingHandler;
    private int recordingSeconds = 0;
    private final Runnable recordingTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRecording && tvRecordingTime != null) {
                recordingSeconds++;
                int minutes = recordingSeconds / 60;
                int seconds = recordingSeconds % 60;
                tvRecordingTime.setText(String.format(Locale.US, "%02d:%02d", minutes, seconds));
                if (recordingSeconds >= MAX_RECORDING_SECONDS) {
                    stopRecording();
                    return;
                }
                recordingHandler.postDelayed(this, 1000);
            }
        }
    };

    private final ActivityResultLauncher<Intent> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && cameraImageUri != null) {
                    handleImageResult(cameraImageUri);
                }
            });

    private final ActivityResultLauncher<Intent> galleryLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    List<Uri> uris = new ArrayList<>();

                    if (data.getClipData() != null) {
                        int count = Math.min(data.getClipData().getItemCount(), MAX_GALLERY_IMAGES);
                        for (int i = 0; i < count; i++) {
                            uris.add(data.getClipData().getItemAt(i).getUri());
                        }
                    } else if (data.getData() != null) {
                        uris.add(data.getData());
                    }

                    if (!uris.isEmpty()) {
                        handleMultipleImageResults(uris);
                    }
                }
            });

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), permissions -> {
                Boolean cameraGranted = permissions.get(Manifest.permission.CAMERA);
                if (cameraGranted != null && cameraGranted) {
                    if (!isAdded()) return;
                    openCamera();
                } else {
                    if (!isAdded()) return;
                    android.app.Activity activity = getActivity();
                    if (activity != null && !activity.isFinishing()) {
                        Toast.makeText(activity, getString(R.string.permission_camera_denied), Toast.LENGTH_SHORT).show();
                    }
                }
            });

    private final ActivityResultLauncher<String> storagePermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    if (!isAdded()) return;
                    openGallery();
                } else {
                    if (!isAdded()) return;
                    android.app.Activity activity = getActivity();
                    if (activity != null && !activity.isFinishing()) {
                        Toast.makeText(activity, getString(R.string.permission_storage_denied), Toast.LENGTH_SHORT).show();
                    }
                }
            });

    private final ActivityResultLauncher<String> audioPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    if (!isAdded()) return;
                    startRecording();
                } else {
                    if (!isAdded()) return;
                    android.app.Activity activity = getActivity();
                    if (activity != null && !activity.isFinishing()) {
                        Toast.makeText(activity, getString(R.string.audio_permission_denied), Toast.LENGTH_SHORT).show();
                    }
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_record, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (savedInstanceState != null && savedInstanceState.containsKey("camera_uri")) {
            cameraImageUri = Uri.parse(savedInstanceState.getString("camera_uri"));
        }
        // 恢复录音/图片模式状态。注意：MediaRecorder 已随 Fragment 销毁释放，无法实际恢复录音，
        // 检测到 isRecording 为 true 时重置为 false 并提示用户录音已中断
        if (savedInstanceState != null) {
            recordingSeconds = savedInstanceState.getInt("recording_seconds", 0);
            isRecording = savedInstanceState.getBoolean("is_recording", false);
            if (isRecording) {
                // 旋转后 MediaRecorder 已释放，录音无法继续；重置状态并提示用户
                isRecording = false;
                recordingSeconds = 0;
                audioFile = null;
                android.app.Activity activity = getActivity();
                if (activity != null && !activity.isFinishing()) {
                    Toast.makeText(activity, R.string.record_audio_interrupted, Toast.LENGTH_SHORT).show();
                }
            } else {
                String audioPath = savedInstanceState.getString("audio_file_path");
                if (audioPath != null) {
                    audioFile = new File(audioPath);
                }
            }
            imageSelected = savedInstanceState.getBoolean("image_selected", true);
        }

        btnModeImage = view.findViewById(R.id.btnModeImage);
        btnModeAudio = view.findViewById(R.id.btnModeAudio);
        iconModeImage = view.findViewById(R.id.iconModeImage);
        iconModeAudio = view.findViewById(R.id.iconModeAudio);
        labelModeImage = view.findViewById(R.id.labelModeImage);
        labelModeAudio = view.findViewById(R.id.labelModeAudio);
        editInput = view.findViewById(R.id.editInput);
        tvCharCount = view.findViewById(R.id.tvCharCount);
        btnSave = view.findViewById(R.id.btnSave);

        // 小回顾视图绑定
        flashbackSection = view.findViewById(R.id.flashbackSection);
        tvFlashbackTag = view.findViewById(R.id.tvFlashbackTag);
        btnRefreshFlashback = view.findViewById(R.id.btnRefreshFlashback);
        flashbackCard1 = view.findViewById(R.id.flashbackCard1);
        flashbackCard2 = view.findViewById(R.id.flashbackCard2);
        flashbackCard3 = view.findViewById(R.id.flashbackCard3);
        fbDate1 = view.findViewById(R.id.fbDate1);
        fbText1 = view.findViewById(R.id.fbText1);
        fbReflection1 = view.findViewById(R.id.fbReflection1);
        fbDate2 = view.findViewById(R.id.fbDate2);
        fbText2 = view.findViewById(R.id.fbText2);
        fbDate3 = view.findViewById(R.id.fbDate3);
        fbText3 = view.findViewById(R.id.fbText3);
        tvFlashbackEmpty = view.findViewById(R.id.tvFlashbackEmpty);
        tvFlashbackLoading = view.findViewById(R.id.tvFlashbackLoading);

        // 3 个小回顾卡片点击查看完整内容 + 添加感想
        View.OnClickListener flashbackCardClick = v -> {
            int idx = -1;
            if (v == flashbackCard1) idx = 0;
            else if (v == flashbackCard2) idx = 1;
            else if (v == flashbackCard3) idx = 2;
            if (idx < 0 || idx >= flashbackSelectedIds.length) return;
            showReflectionDialog(flashbackSelectedIds[idx]);
        };
        if (flashbackCard1 != null) flashbackCard1.setOnClickListener(flashbackCardClick);
        if (flashbackCard2 != null) flashbackCard2.setOnClickListener(flashbackCardClick);
        if (flashbackCard3 != null) flashbackCard3.setOnClickListener(flashbackCardClick);
        if (btnRefreshFlashback != null) {
            btnRefreshFlashback.setOnClickListener(v -> {
                // 清除当日缓存，强制重新选取
                clearFlashbackCache();
                loadFlashback();
            });
        }

        // 使用 ApplicationContext 避免持有 Activity Context 导致内存泄漏
        diaryDao = new DiaryDao(requireContext().getApplicationContext());
        dimensionDao = new DimensionDao(requireContext().getApplicationContext());
        todoDao = new TodoDao(requireContext().getApplicationContext());
        tagDao = new TagDao(requireContext().getApplicationContext());
        reflectionDao = new ReflectionDao(requireContext().getApplicationContext());

        if (editInput != null && tvCharCount != null) {
            editInput.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (tvCharCount != null) {
                        tvCharCount.setText(String.valueOf(s.length()));
                    }
                }

                @Override
                public void afterTextChanged(android.text.Editable s) {}
            });
        }

        updateModeUI();
        loadFlashback();

        if (btnModeImage != null) {
            btnModeImage.setOnClickListener(v -> {
                imageSelected = true;
                updateModeUI();
                showImagePickerDialog();
            });
        }

        if (btnModeAudio != null) {
            btnModeAudio.setOnClickListener(v -> {
                imageSelected = false;
                updateModeUI();
                checkAudioPermissionAndRecord();
            });
        }

        if (btnSave != null) {
            btnSave.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
                saveRecord();
            });
        }
    }

    // ===================== 小回顾加载 =====================

    /**
     * 异步为新保存的日记打标签。
     * 调用 DeepSeek AI，从标签库中选 1-3 个标签，写入 diary_tag 表。
     */
    private void tagNewDiary(long diaryId, String content) {
        if (diaryId < 0 || content == null || content.trim().isEmpty()) return;
        List<String> availableTags = tagDao.getAllTagNames();
        if (availableTags.isEmpty()) return;
        DeepSeekClient.tagDiary(content, availableTags, new DeepSeekClient.TagCallback() {
            @Override
            public void onResult(List<String> tags) {
                tagDao.setTagsForDiary(diaryId, tags);
                Log.d(TAG, "tagNewDiary: success, diaryId=" + diaryId + ", tags=" + tags);
            }
            @Override
            public void onFailure(String message) {
                Log.w(TAG, "tagNewDiary: failed, diaryId=" + diaryId + ", " + message);
            }
        });
    }

    /**
     * 日记保存成功后，异步从正文中提取待办写入待办列表。
     * 全程非阻塞：提取失败、超时或返回空结果都只记日志，不影响日记保存。
     */
    private void extractTodosFromDiary(String content, String date) {
        if (todoDao == null || content == null || content.trim().isEmpty()) return;
        if (!isAiConnected()) return;
        final Context appCtx = getContext() != null
                ? requireContext().getApplicationContext() : null;
        final String text = content.length() > MAX_TODO_EXTRACT_CHARS
                ? content.substring(0, MAX_TODO_EXTRACT_CHARS) : content;
        DeepSeekClient.extractSchedule(text, new DeepSeekClient.ScheduleCallback() {
            @Override
            public void onResult(List<DeepSeekClient.ScheduleItem> items) {
                if (items == null || items.isEmpty()) return;
                AppExecutors.io().execute(() -> {
                    int added = todoDao.appendExtracted(date, items);
                    Log.d(TAG, "extractTodosFromDiary: added " + added + " todos from " + date);
                    if (added > 0 && appCtx != null) {
                        TodoWidgetProvider.notifyDataChanged(appCtx);
                    }
                });
            }

            @Override
            public void onFailure(String message) {
                Log.w(TAG, "extractTodosFromDiary: failed, " + message);
            }
        });
    }

    /**
     * 加载今日小回顾：1 个标签 + 最多 3 篇同标签日记。
     * 先查当日缓存（SharedPreferences），未命中则调 TagDao 选取并缓存。
     */
    private void loadFlashback() {
        if (flashbackSection == null) return;
        String today = DateUtils.formatDate(Calendar.getInstance().getTime());
        final Context appCtx = getContext() != null ? requireContext().getApplicationContext() : null;
        if (appCtx == null) return;

        showFlashbackLoading();

        AppExecutors.io().execute(() -> {
            SharedPreferences prefs = appCtx.getSharedPreferences("lifetrack_flashback", Context.MODE_PRIVATE);
            String cachedIds = prefs.getString(today, null);
            String cachedTagName = prefs.getString(today + "_tag", null);

            TagDao.ReviewPick pick = null;
            boolean fromCache = false;

            if (cachedIds != null && cachedTagName != null) {
                // 命中缓存：按 id 取日记内容
                List<String[]> diaries = new ArrayList<>();
                String[] parts = cachedIds.split(",");
                for (String p : parts) {
                    try {
                        long id = Long.parseLong(p.trim());
                        // 用 DiaryDao 按 id 查询
                        String[] entry = getDiaryById(id);
                        if (entry != null) diaries.add(entry);
                    } catch (NumberFormatException ignored) {}
                }
                if (!diaries.isEmpty()) {
                    // 构造 ReviewPick（tagId 不重要，只需 tagName）
                    pick = new TagDao.ReviewPick(-1, cachedTagName, diaries);
                    fromCache = true;
                }
            }

            if (pick == null) {
                // 未命中缓存：调选取算法
                pick = tagDao.pickTagAndDiaries(today);
                if (pick != null && !pick.diaries.isEmpty()) {
                    // 缓存当日选取结果
                    StringBuilder sb = new StringBuilder();
                    for (String[] e : pick.diaries) {
                        if (sb.length() > 0) sb.append(",");
                        sb.append(e[0]);
                    }
                    prefs.edit()
                            .putString(today, sb.toString())
                            .putString(today + "_tag", pick.tagName)
                            .apply();
                }
            }

            if (!isAdded()) return;
            final TagDao.ReviewPick finalPick = pick;
            final boolean finalFromCache = fromCache;
            runOnUiIfAlive(() -> {
                if (finalPick == null || finalPick.diaries.isEmpty()) {
                    renderFlashbackEmpty();
                } else {
                    renderFlashbackCards(finalPick, finalFromCache);
                }
            });
        });
    }

    /**
     * 按 id 查询单条日记（id, timestamp, content）。
     */
    private String[] getDiaryById(long id) {
        if (id < 0) return null;
        Context appCtx = getContext() != null ? requireContext().getApplicationContext() : null;
        if (appCtx == null) return null;
        DiaryDbHelper helper = DiaryDbHelper.getInstance(appCtx);
        net.zetetic.database.sqlcipher.SQLiteDatabase db = helper.getReadableDb();
        String sql = "SELECT " + DiaryDbHelper.COLUMN_ID + ", " + DiaryDbHelper.COLUMN_TIMESTAMP + ", " + DiaryDbHelper.COLUMN_CONTENT +
                " FROM " + DiaryDbHelper.TABLE_DIARY +
                " WHERE " + DiaryDbHelper.COLUMN_ID + " = ?";
        try (android.database.Cursor cursor = db.rawQuery(sql, new String[]{String.valueOf(id)})) {
            if (cursor.moveToFirst()) {
                return new String[]{cursor.getString(0), cursor.getString(1), cursor.getString(2)};
            }
        }
        return null;
    }

    /**
     * 清除当日缓存，用于"换一批"按钮。
     */
    private void clearFlashbackCache() {
        String today = DateUtils.formatDate(Calendar.getInstance().getTime());
        Context appCtx = getContext() != null ? requireContext().getApplicationContext() : null;
        if (appCtx == null) return;
        SharedPreferences prefs = appCtx.getSharedPreferences("lifetrack_flashback", Context.MODE_PRIVATE);
        prefs.edit().remove(today).remove(today + "_tag").apply();
    }

    /**
     * 加载中状态：隐藏卡片，显示 loading 文案。
     */
    private void showFlashbackLoading() {
        if (flashbackSection == null) return;
        flashbackSection.setVisibility(View.VISIBLE);
        if (flashbackCard1 != null) flashbackCard1.setVisibility(View.GONE);
        if (flashbackCard2 != null) flashbackCard2.setVisibility(View.GONE);
        if (flashbackCard3 != null) flashbackCard3.setVisibility(View.GONE);
        if (tvFlashbackEmpty != null) tvFlashbackEmpty.setVisibility(View.GONE);
        if (tvFlashbackLoading != null) {
            tvFlashbackLoading.setVisibility(View.VISIBLE);
            tvFlashbackLoading.setText(R.string.flashback_loading);
        }
    }

    /**
     * 空状态：无已打标签的日记。
     */
    private void renderFlashbackEmpty() {
        if (flashbackSection == null) return;
        flashbackSection.setVisibility(View.VISIBLE);
        if (flashbackCard1 != null) flashbackCard1.setVisibility(View.GONE);
        if (flashbackCard2 != null) flashbackCard2.setVisibility(View.GONE);
        if (flashbackCard3 != null) flashbackCard3.setVisibility(View.GONE);
        if (tvFlashbackLoading != null) tvFlashbackLoading.setVisibility(View.GONE);
        if (tvFlashbackEmpty != null) {
            tvFlashbackEmpty.setVisibility(View.VISIBLE);
            tvFlashbackEmpty.setText(R.string.flashback_no_data);
        }
        flashbackSelectedIds = new long[0];
    }

    /**
     * 渲染 3 张小回顾卡片 + 标签头。
     */
    private void renderFlashbackCards(TagDao.ReviewPick pick, boolean fromCache) {
        if (flashbackSection == null || pick == null || pick.diaries.isEmpty()) {
            renderFlashbackEmpty();
            return;
        }

        flashbackSection.setVisibility(View.VISIBLE);
        if (tvFlashbackLoading != null) tvFlashbackLoading.setVisibility(View.GONE);
        if (tvFlashbackEmpty != null) tvFlashbackEmpty.setVisibility(View.GONE);

        // 显示标签头
        if (tvFlashbackTag != null) {
            tvFlashbackTag.setText(pick.tagName);
        }

        // 记录选中日记 id
        List<String[]> entries = pick.diaries;
        flashbackSelectedIds = new long[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            try {
                flashbackSelectedIds[i] = Long.parseLong(entries.get(i)[0]);
            } catch (Exception e) {
                flashbackSelectedIds[i] = -1;
            }
        }

        // 绑定卡片
        bindFlashbackCard(0, flashbackCard1, fbDate1, fbText1, fbReflection1, entries);
        bindFlashbackCard(1, flashbackCard2, fbDate2, fbText2, null, entries);
        bindFlashbackCard(2, flashbackCard3, fbDate3, fbText3, null, entries);
    }

    /**
     * 绑定单张卡片。
     */
    private void bindFlashbackCard(int idx, LinearLayout card, TextView tvDate, TextView tvText,
                                   TextView tvReflection, List<String[]> entries) {
        if (card == null) return;
        if (idx >= entries.size()) {
            card.setVisibility(View.GONE);
            return;
        }
        String[] entry = entries.get(idx);
        if (entry == null || entry.length < 3) {
            card.setVisibility(View.GONE);
            return;
        }
        String timestamp = entry[1];
        String content = entry[2];
        String dateLabel = timestamp != null && timestamp.length() >= 10 ? timestamp.substring(0, 10) : (timestamp != null ? timestamp : "");
        if (tvDate != null) tvDate.setText(dateLabel);
        if (tvText != null) {
            String summary = content != null ? (content.length() > 150 ? content.substring(0, 150) + "…" : content) : "";
            summary = summary.replace("\n", " ");
            tvText.setText(summary);
        }
        // 显示感想数量（仅卡片1有 tvReflection）
        if (tvReflection != null) {
            try {
                long diaryId = Long.parseLong(entry[0]);
                int count = reflectionDao.countReflections(diaryId);
                if (count > 0) {
                    tvReflection.setVisibility(View.VISIBLE);
                    tvReflection.setText(getString(R.string.flashback_reflection_count_format, count));
                } else {
                    tvReflection.setVisibility(View.GONE);
                }
            } catch (Exception e) {
                tvReflection.setVisibility(View.GONE);
            }
        }
        card.setVisibility(View.VISIBLE);
    }

    // ===================== 回顾感想对话框 =====================

    /**
     * 显示回顾感想对话框：上半显示日记原文，下半显示感想列表 + 输入框。
     */
    private void showReflectionDialog(long diaryId) {
        if (diaryId < 0) return;
        AppExecutors.io().execute(() -> {
            if (!isAdded()) return;
            final String[] entry = getDiaryById(diaryId);
            if (entry == null || entry.length < 3) {
                runOnUiIfAlive(() -> {
                    Activity activity = getActivity();
                    if (activity != null && !activity.isFinishing()) {
                        Toast.makeText(activity, R.string.flashback_no_data, Toast.LENGTH_SHORT).show();
                    }
                });
                return;
            }
            final List<String> tags = tagDao.getTagsForDiary(diaryId);
            final List<ReflectionDao.Reflection> reflections = reflectionDao.getReflectionsForDiary(diaryId);
            runOnUiIfAlive(() -> renderReflectionDialog(entry, tags, reflections));
        });
    }

    /**
     * 渲染感想对话框。
     */
    private void renderReflectionDialog(String[] entry, List<String> tags, List<ReflectionDao.Reflection> reflections) {
        Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) return;
        dismissDialogQuietly(reflectionDialog);
        reflectionDialog = DialogHelper.create(activity, R.layout.dialog_reflection, false);
        Dialog dialog = reflectionDialog;

        // 日记原文
        TextView tvDate = dialog.findViewById(R.id.tvReflectionDiaryDate);
        TextView tvTags = dialog.findViewById(R.id.tvReflectionDiaryTags);
        TextView tvContent = dialog.findViewById(R.id.tvReflectionDiaryContent);
        if (tvDate != null) tvDate.setText(entry[1] != null ? entry[1] : "");
        if (tvContent != null) tvContent.setText(entry[2] != null ? entry[2] : "");
        if (tvTags != null) {
            if (tags != null && !tags.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String tag : tags) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append("#").append(tag);
                }
                tvTags.setText(sb.toString());
                tvTags.setVisibility(View.VISIBLE);
            } else {
                tvTags.setVisibility(View.GONE);
            }
        }

        // 感想列表
        LinearLayout container = dialog.findViewById(R.id.reflectionListContainer);
        TextView tvEmpty = dialog.findViewById(R.id.tvReflectionEmpty);
        if (container != null) {
            container.removeAllViews();
            if (reflections == null || reflections.isEmpty()) {
                if (tvEmpty != null) tvEmpty.setVisibility(View.VISIBLE);
            } else {
                if (tvEmpty != null) tvEmpty.setVisibility(View.GONE);
                for (ReflectionDao.Reflection r : reflections) {
                    View itemView = getLayoutInflater().inflate(R.layout.item_reflection, container, false);
                    TextView tvRDate = itemView.findViewById(R.id.tvReflectionDate);
                    TextView tvRContent = itemView.findViewById(R.id.tvReflectionContent);
                    TextView btnDelete = itemView.findViewById(R.id.btnReflectionDelete);
                    if (tvRDate != null) tvRDate.setText(r.createdAt);
                    if (tvRContent != null) tvRContent.setText(r.content);
                    if (btnDelete != null) {
                        btnDelete.setOnClickListener(v -> {
                            reflectionDao.deleteReflection(r.id);
                            container.removeView(itemView);
                            if (container.getChildCount() == 0 && tvEmpty != null) {
                                tvEmpty.setVisibility(View.VISIBLE);
                            }
                            Toast.makeText(activity, R.string.reflection_deleted, Toast.LENGTH_SHORT).show();
                        });
                    }
                    container.addView(itemView);
                }
            }
        }

        // 输入框 + 保存按钮
        EditText editInput = dialog.findViewById(R.id.editReflectionInput);
        View btnSave = dialog.findViewById(R.id.btnReflectionSave);
        View btnClose = dialog.findViewById(R.id.btnReflectionCancel);
        // diaryId：通过 entry[0] 获取（用临时变量避免 final 变量在 try/catch 中被判定重复赋值）
        long tmpDiaryId;
        try {
            tmpDiaryId = Long.parseLong(entry[0]);
        } catch (Exception e) {
            tmpDiaryId = -1;
        }
        final long currentDiaryId = tmpDiaryId;

        if (btnSave != null) {
            btnSave.setOnClickListener(v -> {
                if (editInput == null) return;
                String text = editInput.getText().toString().trim();
                if (text.isEmpty()) {
                    Toast.makeText(activity, R.string.reflection_empty_content, Toast.LENGTH_SHORT).show();
                    return;
                }
                String now = DateUtils.formatDateTime(java.util.Calendar.getInstance().getTime());
                AppExecutors.io().execute(() -> {
                    reflectionDao.addReflection(currentDiaryId, text, now);
                    runOnUiIfAlive(() -> {
                        Toast.makeText(activity, R.string.reflection_saved, Toast.LENGTH_SHORT).show();
                        editInput.setText("");
                        // 刷新感想列表
                        List<ReflectionDao.Reflection> newList = reflectionDao.getReflectionsForDiary(currentDiaryId);
                        renderReflectionDialog(entry, tags, newList);
                    });
                });
            });
        }
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }

        DialogHelper.setCloseButton(dialog);
        dialog.show();
        // 设置对话框宽度占满屏幕（左右留 16dp 边距），方便阅读长日记
        if (dialog.getWindow() != null) {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            dialog.getWindow().getWindowManager().getDefaultDisplay().getMetrics(dm);
            int screenWidth = dm.widthPixels;
            int margin = (int) (16 * getResources().getDisplayMetrics().density);
            dialog.getWindow().setLayout(screenWidth - margin * 2, android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private void showImagePickerDialog() {
        android.app.Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) return;
        // 保存引用，onDestroyView 时主动 dismiss，避免窗口泄漏
        imagePickerDialog = DialogHelper.create(activity, R.layout.dialog_image_picker);
        Dialog dialog = imagePickerDialog;

        View btnCamera = dialog.findViewById(R.id.btnPickerCamera);
        View btnGallery = dialog.findViewById(R.id.btnPickerGallery);
        View btnCancel = dialog.findViewById(R.id.btnPickerCancel);

        if (btnCamera != null) {
            btnCamera.setOnClickListener(v -> {
                dialog.dismiss();
                checkCameraPermissionAndOpen();
            });
        }

        if (btnGallery != null) {
            btnGallery.setOnClickListener(v -> {
                dialog.dismiss();
                checkStoragePermissionAndOpenGallery();
            });
        }

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dialog.dismiss());
        }

        DialogHelper.setCloseButton(dialog);
        dialog.show();
    }

    private void checkCameraPermissionAndOpen() {
        Context ctx = getContext();
        if (ctx == null) return;
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            permissionLauncher.launch(new String[]{Manifest.permission.CAMERA});
        }
    }

    private void checkStoragePermissionAndOpenGallery() {
        Context ctx = getContext();
        if (ctx == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED) {
                openGallery();
            } else {
                storagePermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES);
            }
        } else {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                openGallery();
            } else {
                storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
    }

    private void openCamera() {
        android.app.Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) return;
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(activity.getPackageManager()) != null) {
            File photoFile = createImageFile();
            if (photoFile != null) {
                cameraImageUri = FileProvider.getUriForFile(activity,
                        activity.getPackageName() + ".fileprovider", photoFile);
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri);
                cameraLauncher.launch(takePictureIntent);
            }
        }
    }

    private void openGallery() {
        Intent pickPhotoIntent = new Intent(Intent.ACTION_GET_CONTENT);
        pickPhotoIntent.setType("image/*");
        pickPhotoIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        galleryLauncher.launch(Intent.createChooser(pickPhotoIntent, getString(R.string.image_picker_gallery)));
    }

    private File createImageFile() {
        Context ctx = getContext();
        if (ctx == null) return null;
        String imageFileName = "IMG_" + DateUtils.currentFileDate() + "_";
        File storageDir = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        try {
            return File.createTempFile(imageFileName, ".jpg", storageDir);
        } catch (Exception e) {
            Log.e(TAG, "createImageFile: failed", e);
            return null;
        }
    }

    private void handleImageResult(Uri imageUri) {
        Context ctx = getContext();
        if (ctx == null) return;
        final Context appCtx = ctx.getApplicationContext();

        AppExecutors.io().execute(() -> {
            Bitmap bitmap = ImageUtils.decodeSampledBitmap(appCtx, imageUri, 1024, 1024);
            final String base64Image;
            if (bitmap != null) {
                base64Image = ImageUtils.bitmapToBase64(bitmap);
                bitmap.recycle();
            } else {
                base64Image = null;
            }

            if (!isAdded()) return;
            android.app.Activity activity = getActivity();
            if (activity == null || activity.isFinishing()) return;
            activity.runOnUiThread(() -> {
                if (base64Image != null) {
                    analyzeImageWithDoubao(base64Image);
                } else {
                    Toast.makeText(appCtx, getString(R.string.image_decode_failed), Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void handleMultipleImageResults(List<Uri> uris) {
        setBtnSaveEnabled(false);
        showAnalyzingDialog(getString(R.string.image_batch_analyzing, 0, uris.size()));

        Context ctx = getContext();
        if (ctx == null) return;
        final Context appCtx = ctx.getApplicationContext();

        AppExecutors.io().execute(() -> {
            List<String> base64Images = new ArrayList<>();
            List<String> failedUris = new ArrayList<>();

            for (int i = 0; i < uris.size(); i++) {
                if (!isAdded()) {
                    // Fragment 已销毁，onDestroyView 已 dismiss analyzingDialog；
                    // 复用 runOnUiIfAlive 统一入口，!isAdded 时它会直接 return 不执行
                    runOnUiIfAlive(() -> dismissDialogQuietly(analyzingDialog));
                    return;
                }
                Uri uri = uris.get(i);
                Bitmap bitmap = ImageUtils.decodeSampledBitmap(appCtx, uri, 1024, 1024);
                if (bitmap != null) {
                    base64Images.add(ImageUtils.bitmapToBase64(bitmap));
                    bitmap.recycle();
                } else {
                    failedUris.add(uri.toString());
                    Log.w(TAG, "handleMultipleImageResults: failed to decode uri=" + uri);
                }

                int progress = i + 1;
                if (isAdded()) {
                    android.app.Activity activity = getActivity();
                    if (activity != null && !activity.isFinishing()) {
                        activity.runOnUiThread(() ->
                                updateAnalyzingProgress(progress, uris.size()));
                    }
                }
            }

            if (isAdded()) {
                android.app.Activity activity = getActivity();
                if (activity == null || activity.isFinishing()) return;
                activity.runOnUiThread(() -> {
                    dismissAnalyzingDialog();

                    if (base64Images.isEmpty()) {
                        setBtnSaveEnabled(true);
                        Toast.makeText(appCtx, getString(R.string.image_decode_failed), Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (base64Images.size() == 1) {
                        analyzeImageWithDoubao(base64Images.get(0));
                    } else {
                        analyzeMultipleImagesWithDoubao(base64Images);
                    }
                });
            }
        });
    }

    private void analyzeImageWithDoubao(String base64Image) {
        setBtnSaveEnabled(false);
        showAnalyzingDialog();

        DoubaoClient.analyzeImageStreamBase64(base64Image, new DoubaoClient.VisionStreamCallback() {
            @Override
            public void onPartialResult(String text, boolean isComplete) {
                if (!isAdded()) return;
                runOnUiIfAlive(() -> {
                    if (isComplete) {
                        dismissAnalyzingDialog();
                        setBtnSaveEnabled(true);
                        // 重置节流时间戳，下次流式识别从头开始计时
                        lastStreamUpdateMs = 0;
                        if (text != null && !text.trim().isEmpty()) {
                            if (editInput == null) return;
                            editInput.setText(text.trim());
                            editInput.setSelection(text.trim().length());
                            android.app.Activity activity = getActivity();
                            if (activity != null && !activity.isFinishing()) {
                                Toast.makeText(activity, getString(R.string.image_analyze_success), Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            showErrorDialog(getString(R.string.image_analyze_failed));
                        }
                    } else {
                        if (editInput == null) return;
                        // 节流：距上次更新不足 100ms 则跳过，下个 partial 会补上；
                        // isComplete 时上方分支会立即应用最终文本，不会丢失结尾内容
                        long now = android.os.SystemClock.uptimeMillis();
                        if (now - lastStreamUpdateMs >= STREAM_UPDATE_INTERVAL_MS) {
                            lastStreamUpdateMs = now;
                            editInput.setText(text);
                            editInput.setSelection(text.length());
                        }
                    }
                });
            }

            @Override
            public void onFailure(String message) {
                if (!isAdded()) return;
                runOnUiIfAlive(() -> {
                    dismissAnalyzingDialog();
                    setBtnSaveEnabled(true);
                    showErrorDialog(message);
                });
            }
        });
    }

    private void analyzeMultipleImagesWithDoubao(List<String> base64Images) {
        setBtnSaveEnabled(false);
        int total = base64Images.size();
        showAnalyzingDialog(getString(R.string.image_batch_analyzing, 0, total));

        DoubaoClient.analyzeMultipleImagesConcurrent(base64Images, new DoubaoClient.BatchVisionStreamCallback() {
            @Override
            public void onPartialResults(List<String> texts, int completedCount, int totalCount, boolean isComplete) {
                if (!isAdded()) return;
                runOnUiIfAlive(() -> {
                    updateAnalyzingProgress(completedCount, totalCount);

                    if (isComplete) {
                        dismissAnalyzingDialog();
                        setBtnSaveEnabled(true);

                        StringBuilder combined = new StringBuilder();
                        for (String result : texts) {
                            if (result != null && !result.isEmpty() && !result.contains(ANALYSIS_FAILED_MARKER)) {
                                if (combined.length() > 0) combined.append("\n\n");
                                combined.append(result);
                            }
                        }

                        int successCount = 0;
                        for (String text : texts) {
                            if (text != null && !text.isEmpty() && !text.contains(ANALYSIS_FAILED_MARKER)) {
                                successCount++;
                            }
                        }

                        if (successCount == 0) {
                            showErrorDialog(getString(R.string.record_all_images_failed));
                        } else if (successCount < total) {
                            if (editInput == null) return;
                            editInput.setText(combined.toString());
                            editInput.setSelection(combined.length());
                            android.app.Activity activity = getActivity();
                            if (activity != null && !activity.isFinishing()) {
                                Toast.makeText(activity,
                                        getString(R.string.image_batch_partial_success, successCount, total),
                                        Toast.LENGTH_LONG).show();
                            }
                        } else {
                            if (editInput == null) return;
                            editInput.setText(combined.toString());
                            editInput.setSelection(combined.length());
                            android.app.Activity activity = getActivity();
                            if (activity != null && !activity.isFinishing()) {
                                Toast.makeText(activity,
                                        getString(R.string.image_batch_analyze_success, total),
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                    }
                });
            }

            @Override
            public void onFailure(String message) {
                if (!isAdded()) return;
                runOnUiIfAlive(() -> {
                    dismissAnalyzingDialog();
                    setBtnSaveEnabled(true);
                    Log.w(TAG, "analyzeMultipleImagesWithDoubao: failed, " + message);
                    showErrorDialog(message);
                });
            }
        });
    }

    private boolean isAiConnected() {
        return !DeepSeekClient.getApiKey().isEmpty();
    }

    private void saveRecord() {
        if (editInput == null) return;
        String content = editInput.getText().toString().trim();
        if (content.isEmpty()) {
            android.app.Activity activity = getActivity();
            if (activity != null && !activity.isFinishing()) {
                Toast.makeText(activity, getString(R.string.save_error_empty), Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (isAiConnected()) {
            saveWithAiAnalysis(content);
        } else {
            saveDirectly(content);
        }
    }

    private void saveDirectly(String content) {
        setBtnSaveEnabled(false);

        String timestamp = DateUtils.currentDateTime();
        AppExecutors.io().execute(() -> {
            long result = diaryDao.insert(timestamp, content);
            Log.d(TAG, "saveDirectly: timestamp=" + timestamp + ", result=" + result);
            if (result != -1) {
                tagNewDiary(result, content);
                extractTodosFromDiary(content, DateUtils.currentDate());
            }

            if (!isAdded()) return;
            android.app.Activity activity = getActivity();
            if (activity == null || activity.isFinishing()) return;
            activity.runOnUiThread(() -> {
                if (!isAdded()) return;

                if (result == -1) {
                    setBtnSaveEnabled(true);
                    Toast.makeText(activity, getString(R.string.record_save_db_error), Toast.LENGTH_LONG).show();
                    Log.e(TAG, "saveDirectly FAILED: insert returned -1");
                    return;
                }

                setBtnSaveEnabled(true);
                Toast.makeText(activity, getString(R.string.record_save_success_format, timestamp), Toast.LENGTH_SHORT).show();
                if (editInput != null) editInput.setText("");
                showSuccessDialog();
                loadFlashback();
            });
        });
    }

    private void saveWithAiAnalysis(String content) {
        setBtnSaveEnabled(false);
        showAnalyzingDialog(getString(R.string.dialog_analyzing_message));

        final String currentDate = DateUtils.currentDate();

        // 先在后台取过去7天打分作为参考，再调 AI 维度分析
        AppExecutors.io().execute(() -> {
            if (!isAdded()) return;
            final String historyContext = dimensionDao != null ? dimensionDao.getRecentScoresText(7) : "";

            if (!isAdded()) return;
            android.app.Activity activity = getActivity();
            if (activity == null || activity.isFinishing()) return;
            activity.runOnUiThread(() -> {
                if (!isAdded()) return;
                callDimensionAnalysis(content, currentDate, historyContext);
            });
        });
    }

    /**
     * 调用带维度分析的 AI 接口，写库时同时写入 dimension_records。
     */
    private void callDimensionAnalysis(String content, String currentDate, String historyContext) {
        DeepSeekClient.analyzeDiaryDatesWithDimensions(content, currentDate, historyContext, new DeepSeekClient.MultiDateAnalyzeCallback() {
            @Override
            public void onResults(List<DeepSeekClient.DateEntry> entries) {
                if (!isAdded()) return;
                // 数据库写入移到后台线程，避免阻塞 UI
                AppExecutors.io().execute(() -> {
                    if (!isAdded()) return;
                    final String time = DateUtils.currentTime();
                    final List<String> dates = new ArrayList<>();

                    for (DeepSeekClient.DateEntry entry : entries) {
                        String timestamp = entry.date + " " + time;
                        long result = diaryDao.insert(timestamp, entry.content);
                        Log.d(TAG, "saveWithAiAnalysis: date=" + entry.date + ", content=" + entry.content + ", result=" + result);
                        if (result != -1) {
                            dates.add(entry.date);
                            tagNewDiary(result, entry.content);
                            saveDimensionRecord(result, entry);
                            extractTodosFromDiary(entry.content, entry.date);
                        }
                    }

                    if (!isAdded()) return;
                    android.app.Activity activity = getActivity();
                    if (activity == null || activity.isFinishing()) return;
                    activity.runOnUiThread(() -> {
                        if (!isAdded()) return;
                        dismissAnalyzingDialog();
                        setBtnSaveEnabled(true);

                        if (dates.isEmpty()) {
                            Toast.makeText(activity, getString(R.string.record_save_db_error), Toast.LENGTH_LONG).show();
                            return;
                        }

                        String msg;
                        if (dates.size() == 1) {
                            msg = getString(R.string.save_ai_date_detected, dates.get(0));
                        } else {
                            msg = getString(R.string.save_ai_multi_date_detected, dates.size(), android.text.TextUtils.join("、", dates));
                        }
                        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
                        if (editInput != null) editInput.setText("");
                        showSuccessDialog();
                        loadFlashback();
                    });
                });
            }

            @Override
            public void onFailure(String message) {
                if (!isAdded()) return;
                Log.w(TAG, "saveWithAiAnalysis: AI failed, fallback to today. Reason: " + message);
                // 数据库写入移到后台线程，避免阻塞 UI
                AppExecutors.io().execute(() -> {
                    if (!isAdded()) return;
                    final String timestamp = DateUtils.currentDateTime();
                    final long result = diaryDao.insert(timestamp, content);
                    if (result != -1) {
                        tagNewDiary(result, content);
                        extractTodosFromDiary(content, DateUtils.currentDate());
                    }

                    if (!isAdded()) return;
                    android.app.Activity activity = getActivity();
                    if (activity == null || activity.isFinishing()) return;
                    activity.runOnUiThread(() -> {
                        if (!isAdded()) return;
                        dismissAnalyzingDialog();
                        setBtnSaveEnabled(true);

                        if (result == -1) {
                            Toast.makeText(activity, getString(R.string.record_save_db_error), Toast.LENGTH_LONG).show();
                            return;
                        }

                        Toast.makeText(activity, getString(R.string.save_ai_fallback_today), Toast.LENGTH_SHORT).show();
                        if (editInput != null) editInput.setText("");
                        showSuccessDialog();
                        loadFlashback();
                    });
                });
            }
        });
    }

    /**
     * 把 DateEntry 的维度数据写入 dimension_records。
     * dimensions 为 null（AI 未返回维度）时跳过。
     */
    private void saveDimensionRecord(long diaryId, DeepSeekClient.DateEntry entry) {
        if (entry.dimensions == null || dimensionDao == null) return;
        DeepSeekClient.DimensionData dim = entry.dimensions;
        // 选第一个非空 excerpt 作为 rawExcerpt 存档
        String excerpt = dim.exerciseExcerpt;
        if (excerpt == null) excerpt = dim.sleepExcerpt;
        if (excerpt == null) excerpt = dim.socialExcerpt;
        dimensionDao.insert(entry.date, diaryId,
                dim.exerciseScore, dim.exerciseTypes,
                dim.sleepScore,
                dim.socialContacts,
                excerpt);
    }

    private void showAnalyzingDialog() {
        showAnalyzingDialog(getString(R.string.dialog_analyzing_message));
    }

    private void showAnalyzingDialog(String message) {
        android.app.Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) return;
        analyzingDialog = DialogHelper.create(activity, R.layout.dialog_analyzing, false);

        TextView tvMsg = analyzingDialog.findViewById(R.id.tvAnalyzingMessage);
        if (tvMsg != null) {
            tvMsg.setText(message);
            tvAnalyzingProgress = tvMsg;
        }

        DialogHelper.setCloseButton(analyzingDialog);
        analyzingDialog.show();
    }

    private void updateAnalyzingProgress(int current, int total) {
        if (tvAnalyzingProgress != null) {
            tvAnalyzingProgress.setText(getString(R.string.image_batch_analyzing, current, total));
        }
    }

    private void dismissAnalyzingDialog() {
        if (analyzingDialog != null && analyzingDialog.isShowing()) {
            analyzingDialog.dismiss();
            analyzingDialog = null;
        }
        tvAnalyzingProgress = null;
    }

    /**
     * 在 Fragment 仍存活时切回 UI 线程执行，避免 requireActivity() 在异步回调中抛 IllegalStateException。
     */
    private void runOnUiIfAlive(Runnable action) {
        Activity activity = getActivity();
        if (activity == null || activity.isFinishing() || !isAdded()) return;
        activity.runOnUiThread(action);
    }

    /**
     * 安全地设置保存按钮可用状态，内含判空，避免异步回调中 btnSave 已被置 null 导致 NPE
     */
    private void setBtnSaveEnabled(boolean enabled) {
        if (btnSave != null) {
            btnSave.setEnabled(enabled);
        }
    }

    /**
     * 安全 dismiss Dialog，吞掉异常，用于 onDestroyView 清理
     */
    private void dismissDialogQuietly(Dialog dialog) {
        if (dialog != null && dialog.isShowing()) {
            try {
                dialog.dismiss();
            } catch (Exception e) {
                Log.w(TAG, "Dismiss dialog failed", e);
            }
        }
    }

    private void showSuccessDialog() {
        android.app.Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) return;
        // 保存引用，onDestroyView 时主动 dismiss，避免窗口泄漏
        successDialog = DialogHelper.create(activity, R.layout.dialog_save_success, false);
        Dialog dialog = successDialog;
        DialogHelper.setConfirmButton(dialog, R.id.btnDialogOk, null);
        DialogHelper.setCloseButton(dialog);
        dialog.show();
    }

    private void showErrorDialog(String errorMessage) {
        android.app.Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) return;
        // 保存引用，onDestroyView 时主动 dismiss，避免窗口泄漏
        errorDialog = DialogHelper.create(activity, R.layout.dialog_verify_result, false);
        Dialog dialog = errorDialog;

        TextView tvErrorMessage = dialog.findViewById(R.id.tvVerifyTitle);
        if (tvErrorMessage != null) {
            tvErrorMessage.setText(errorMessage);
        }

        DialogHelper.setConfirmButton(dialog, R.id.btnDialogOk, null);
        DialogHelper.setCloseButton(dialog);
        dialog.show();
    }

    private void checkAudioPermissionAndRecord() {
        Context ctx = getContext();
        if (ctx == null) return;
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startRecording();
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void startRecording() {
        try {
            audioFile = createAudioFile();
            if (audioFile == null) {
                android.app.Activity activity = getActivity();
                if (activity != null && !activity.isFinishing()) {
                    Toast.makeText(activity, getString(R.string.audio_record_error), Toast.LENGTH_SHORT).show();
                }
                return;
            }

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setOutputFile(audioFile.getAbsolutePath());
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioSamplingRate(16000);
            mediaRecorder.setAudioChannels(1);
            mediaRecorder.setAudioEncodingBitRate(128000);

            mediaRecorder.prepare();
            mediaRecorder.start();

            isRecording = true;
            recordingSeconds = 0;
            recordingHandler = new Handler(Looper.getMainLooper());

            showRecordingDialog();
        } catch (Exception e) {
            Log.e(TAG, "startRecording: failed", e);
            isRecording = false;
            releaseMediaRecorder();
            if (audioFile != null && audioFile.exists()) {
                audioFile.delete();
            }
            audioFile = null;
            android.app.Activity activity = getActivity();
            if (activity != null && !activity.isFinishing()) {
                Toast.makeText(activity, getString(R.string.audio_record_error), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void stopRecording() {
        if (!isRecording) return;

        isRecording = false;

        if (recordingHandler != null) {
            recordingHandler.removeCallbacks(recordingTimerRunnable);
            recordingHandler = null;
        }

        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "stopRecording: error", e);
            releaseMediaRecorder();
        }

        dismissRecordingDialog();

        if (audioFile != null && audioFile.exists() && audioFile.length() > 0) {
            recognizeAudioWithDoubao();
        } else {
            android.app.Activity activity = getActivity();
            if (activity != null && !activity.isFinishing()) {
                Toast.makeText(activity, getString(R.string.audio_record_error), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void releaseMediaRecorder() {
        try {
            if (mediaRecorder != null) {
                mediaRecorder.release();
                mediaRecorder = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to release MediaRecorder", e);
        }
    }

    private File createAudioFile() {
        Context ctx = getContext();
        if (ctx == null) return null;
        String audioFileName = "AUD_" + DateUtils.currentFileDate() + "_";
        File storageDir = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        try {
            return File.createTempFile(audioFileName, ".m4a", storageDir);
        } catch (Exception e) {
            Log.e(TAG, "createAudioFile: failed", e);
            return null;
        }
    }

    private void showRecordingDialog() {
        android.app.Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) return;
        recordingDialog = DialogHelper.create(activity, R.layout.dialog_recording, false);

        tvRecordingTime = recordingDialog.findViewById(R.id.tvRecordingTime);
        View btnStopRecording = recordingDialog.findViewById(R.id.btnStopRecording);

        if (btnStopRecording != null) {
            btnStopRecording.setOnClickListener(v -> stopRecording());
        }

        DialogHelper.setCloseButton(recordingDialog);
        recordingDialog.show();

        recordingHandler.post(recordingTimerRunnable);
    }

    private void dismissRecordingDialog() {
        if (recordingDialog != null && recordingDialog.isShowing()) {
            recordingDialog.dismiss();
            recordingDialog = null;
        }
        tvRecordingTime = null;
    }

    private void recognizeAudioWithDoubao() {
        setBtnSaveEnabled(false);
        showAnalyzingDialog(getString(R.string.audio_analyzing));

        final File file = audioFile;
        if (file == null) {
            dismissAnalyzingDialog();
            setBtnSaveEnabled(true);
            showErrorDialog(getString(R.string.record_audio_file_not_exist));
            return;
        }

        AppExecutors.io().execute(() -> {
            try {
                long fileLength = file.length();
                if (fileLength > MAX_AUDIO_FILE_BYTES) {
                    runOnUiIfAlive(() -> {
                        dismissAnalyzingDialog();
                        setBtnSaveEnabled(true);
                        showErrorDialog(getString(R.string.record_audio_file_too_large_format,
                                String.format(java.util.Locale.US, "%.1f", fileLength / 1024.0 / 1024.0)));
                        cleanupAudioFile();
                    });
                    return;
                }

                byte[] audioData = new byte[(int) fileLength];
                try (FileInputStream fis = new FileInputStream(file)) {
                    int offset = 0;
                    while (offset < audioData.length) {
                        int read = fis.read(audioData, offset, audioData.length - offset);
                        if (read == -1) break;
                        offset += read;
                    }
                    if (offset != audioData.length) {
                        Log.w(TAG, "recognizeAudioWithDoubao: audio file read incomplete, expected="
                                + audioData.length + ", actual=" + offset);
                    }
                }

                DoubaoClient.recognizeAudio(audioData, "mp4", new DoubaoClient.AsrCallback() {
                    @Override
                    public void onResult(String text) {
                        if (!isAdded()) return;
                        runOnUiIfAlive(() -> {
                            dismissAnalyzingDialog();
                            setBtnSaveEnabled(true);

                            if (text != null && !text.isEmpty()) {
                                if (editInput == null) return;
                                String existingText = editInput.getText().toString().trim();
                                if (!existingText.isEmpty()) {
                                    editInput.setText(existingText + "\n" + text);
                                } else {
                                    editInput.setText(text);
                                }
                                editInput.setSelection(editInput.getText().length());
                                android.app.Activity activity = getActivity();
                                if (activity != null && !activity.isFinishing()) {
                                    Toast.makeText(activity, getString(R.string.audio_analyze_success), Toast.LENGTH_SHORT).show();
                                }
                            } else {
                                showErrorDialog(getString(R.string.record_recognition_empty));
                            }

                            cleanupAudioFile();
                        });
                    }

                    @Override
                    public void onFailure(String message) {
                        if (!isAdded()) return;
                        runOnUiIfAlive(() -> {
                            dismissAnalyzingDialog();
                            setBtnSaveEnabled(true);

                            Log.w(TAG, "recognizeAudioWithDoubao: failed, " + message);
                            showErrorDialog(message);

                            cleanupAudioFile();
                        });
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "recognizeAudioWithDoubao: read audio file failed", e);
                runOnUiIfAlive(() -> {
                    dismissAnalyzingDialog();
                    setBtnSaveEnabled(true);
                    showErrorDialog(getString(R.string.record_read_audio_failed_format, e.getMessage()));
                    cleanupAudioFile();
                });
            }
        });
    }

    private void cleanupAudioFile() {
        if (audioFile != null && audioFile.exists()) {
            boolean deleted = audioFile.delete();
            if (!deleted) {
                Log.w(TAG, "cleanupAudioFile: failed to delete " + audioFile.getAbsolutePath());
            }
            audioFile = null;
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (cameraImageUri != null) {
            outState.putString("camera_uri", cameraImageUri.toString());
        }
        // 补充保存录音/图片模式状态，避免配置变化（如旋转）后丢失
        outState.putInt("recording_seconds", recordingSeconds);
        outState.putBoolean("is_recording", isRecording);
        if (audioFile != null) {
            outState.putString("audio_file_path", audioFile.getAbsolutePath());
        }
        outState.putBoolean("image_selected", imageSelected);
    }

    @Override
    public void onDestroyView() {
        if (isRecording) {
            isRecording = false;
            if (recordingHandler != null) {
                recordingHandler.removeCallbacks(recordingTimerRunnable);
            }
            releaseMediaRecorder();
        }
        // 清理未处理的临时音频文件，避免内存泄漏和文件残留
        cleanupAudioFile();
        dismissRecordingDialog();
        dismissAnalyzingDialog();
        // 主动 dismiss 临时 Dialog，避免窗口泄漏
        dismissDialogQuietly(successDialog);
        successDialog = null;
        dismissDialogQuietly(errorDialog);
        errorDialog = null;
        dismissDialogQuietly(imagePickerDialog);
        imagePickerDialog = null;
        dismissDialogQuietly(reflectionDialog);
        reflectionDialog = null;
        // 清理 View 引用，避免异步回调持有已销毁的 View 导致 Activity 无法被 GC 回收
        btnModeImage = null;
        btnModeAudio = null;
        iconModeImage = null;
        iconModeAudio = null;
        labelModeImage = null;
        labelModeAudio = null;
        editInput = null;
        tvCharCount = null;
        btnSave = null;
        flashbackSection = null;
        tvFlashbackTag = null;
        btnRefreshFlashback = null;
        flashbackCard1 = null;
        flashbackCard2 = null;
        flashbackCard3 = null;
        fbDate1 = null;
        fbText1 = null;
        fbReflection1 = null;
        fbDate2 = null;
        fbText2 = null;
        fbDate3 = null;
        fbText3 = null;
        tvFlashbackEmpty = null;
        tvFlashbackLoading = null;
        flashbackSelectedIds = new long[0];
        tvRecordingTime = null;
        // 彻底清空 Message 队列，避免 onDestroyView 后仍有延迟任务触发字段访问导致 NPE
        if (recordingHandler != null) {
            recordingHandler.removeCallbacksAndMessages(null);
        }
        super.onDestroyView();
    }

    private void updateModeUI() {
        // 颜色由 mode_btn_text_color.xml 这个 ColorStateList 跟随 activated 状态自动切换，
        // 这里只切换按钮/图标的 activated 状态，文字和图标颜色会自动联动。
        if (btnModeImage != null) btnModeImage.setActivated(imageSelected);
        if (btnModeAudio != null) btnModeAudio.setActivated(!imageSelected);
        if (iconModeImage != null) iconModeImage.setActivated(imageSelected);
        if (iconModeAudio != null) iconModeAudio.setActivated(!imageSelected);
        // label 也跟随 activated 状态（xml 中 textColor 已绑 ColorStateList），
        // 这里同步 setActivated 以兼容某些 Android 版本对 TextView state 的处理
        if (labelModeImage != null) labelModeImage.setActivated(imageSelected);
        if (labelModeAudio != null) labelModeAudio.setActivated(!imageSelected);
    }
}