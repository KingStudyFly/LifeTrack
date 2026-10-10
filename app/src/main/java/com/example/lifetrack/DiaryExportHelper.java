package com.example.lifetrack;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 日记导出/导入工具类。
 * 使用 JSON 格式，通过 SAF 写入/读取用户选择的文件。
 */
public final class DiaryExportHelper {

    private static final String TAG = "DiaryExportHelper";
    private static final String KEY_EXPORT_TIME = "export_time";
    private static final String KEY_VERSION = "version";
    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_TIMESTAMP = "timestamp";
    private static final String KEY_CONTENT = "content";
    private static final int FORMAT_VERSION = 1;

    private DiaryExportHelper() {}

    /**
     * 导出所有日记到指定 Uri。
     * @return 导出的条数，-1 表示失败
     */
    public static int exportToUri(Context context, Uri targetUri) {
        try {
            DiaryDao dao = new DiaryDao(context);
            List<String[]> entries = dao.getAll();
            if (entries.isEmpty()) return 0;

            JSONArray arr = new JSONArray();
            int exported = 0;
            for (String[] entry : entries) {
                if (entry == null || entry.length < 2) continue;
                JSONObject obj = new JSONObject();
                obj.put(KEY_TIMESTAMP, entry[0]);
                obj.put(KEY_CONTENT, entry[1]);
                arr.put(obj);
                exported++;
            }

            JSONObject root = new JSONObject();
            root.put(KEY_VERSION, FORMAT_VERSION);
            root.put(KEY_EXPORT_TIME, new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            root.put(KEY_ENTRIES, arr);

            try (OutputStream os = context.getContentResolver().openOutputStream(targetUri)) {
                if (os == null) {
                    Log.e(TAG, "openOutputStream returned null for " + targetUri);
                    return -1;
                }
                try (OutputStreamWriter writer = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
                    writer.write(root.toString(2));
                    writer.flush();
                }
            }
            // 返回实际写入的条目数，而非 entries.size()，避免因跳过无效条目导致计数偏大
            return exported;
        } catch (Exception e) {
            Log.e(TAG, "exportToUri failed", e);
            return -1;
        }
    }

    /**
     * 从指定 Uri 读取日记并导入。
     * @return 导入的条数，-1 表示失败
     */
    public static int importFromUri(Context context, Uri sourceUri) {
        try {
            StringBuilder sb = new StringBuilder();
            try (InputStream is = context.getContentResolver().openInputStream(sourceUri)) {
                if (is == null) {
                    Log.e(TAG, "openInputStream returned null for " + sourceUri);
                    return -1;
                }
                try (BufferedReader reader = new BufferedReader(
                        new java.io.InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                }
            }

            JSONObject root = new JSONObject(sb.toString());
            JSONArray arr = root.optJSONArray(KEY_ENTRIES);
            if (arr == null) return -1;

            DiaryDao dao = new DiaryDao(context);
            int imported = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String timestamp = obj.optString(KEY_TIMESTAMP, "");
                String content = obj.optString(KEY_CONTENT, "");
                if (!timestamp.isEmpty() && !content.isEmpty()) {
                    long result = dao.insert(timestamp, content);
                    if (result != -1) imported++;
                }
            }
            return imported;
        } catch (Exception e) {
            Log.e(TAG, "importFromUri failed", e);
            return -1;
        }
    }

    /**
     * 生成默认导出文件名。
     */
    public static String generateExportFileName() {
        String dateStr = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return "lifetrack_diary_" + dateStr + ".json";
    }
}
