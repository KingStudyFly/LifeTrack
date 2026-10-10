package com.example.lifetrack;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class SummaryDao {

    private static final String TAG = "SummaryDao";
    private final DiaryDbHelper dbHelper;

    /**
     * 总结 prompt 版本号。每次修改 DeepSeekClient 中的总结 prompt 时递增，
     * 使旧缓存失效并触发重新生成。
     */
    public static final int CURRENT_PROMPT_VERSION = 2;

    public SummaryDao(Context context) {
        dbHelper = DiaryDbHelper.getInstance(context);
    }

    public String[] getSummary(String periodType, String periodKey) {
        SQLiteDatabase db = dbHelper.getReadableDb();
        String sql = "SELECT " + DiaryDbHelper.COLUMN_SUMMARY_TITLE + ", "
                + DiaryDbHelper.COLUMN_SUMMARY_CONTENT + ", "
                + DiaryDbHelper.COLUMN_ENTRY_COUNT + ", "
                + DiaryDbHelper.COLUMN_PROMPT_VERSION
                + " FROM " + DiaryDbHelper.TABLE_SUMMARIES
                + " WHERE " + DiaryDbHelper.COLUMN_PERIOD_TYPE + " = ? AND "
                + DiaryDbHelper.COLUMN_PERIOD_KEY + " = ?";

        String[] result = null;
        try (Cursor cursor = db.rawQuery(sql, new String[]{periodType, periodKey})) {
            if (cursor.moveToFirst()) {
                result = new String[]{
                        cursor.getString(0),
                        cursor.getString(1),
                        String.valueOf(cursor.getInt(2)),
                        String.valueOf(cursor.getInt(3))
                };
            }
        }
        Log.d(TAG, "getSummary: type=" + periodType + ", key=" + periodKey + ", found=" + (result != null));
        return result;
    }

    public long saveSummary(String periodType, String periodKey, String title, String content, int entryCount, int promptVersion) {
        SQLiteDatabase db = dbHelper.getWritableDb();
        ContentValues values = new ContentValues();
        values.put(DiaryDbHelper.COLUMN_PERIOD_TYPE, periodType);
        values.put(DiaryDbHelper.COLUMN_PERIOD_KEY, periodKey);
        values.put(DiaryDbHelper.COLUMN_SUMMARY_TITLE, title);
        values.put(DiaryDbHelper.COLUMN_SUMMARY_CONTENT, content);
        values.put(DiaryDbHelper.COLUMN_ENTRY_COUNT, entryCount);
        values.put(DiaryDbHelper.COLUMN_PROMPT_VERSION, promptVersion);
        values.put(DiaryDbHelper.COLUMN_CREATED_AT, DateUtils.currentDateTime());

        long result = db.insertWithOnConflict(
                DiaryDbHelper.TABLE_SUMMARIES, null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
        Log.d(TAG, "saveSummary: type=" + periodType + ", key=" + periodKey + ", entryCount=" + entryCount + ", promptVersion=" + promptVersion + ", result=" + result);
        return result;
    }

    public int getEntryCountForPeriod(String dateStart, String dateEnd) {
        SQLiteDatabase db = dbHelper.getReadableDb();
        String sql = "SELECT COUNT(*) FROM " + DiaryDbHelper.TABLE_DIARY
                + " WHERE " + DiaryDbHelper.COLUMN_TIMESTAMP + " >= ? AND "
                + DiaryDbHelper.COLUMN_TIMESTAMP + " < ?";
        int count = 0;
        try (Cursor c = db.rawQuery(sql, new String[]{dateStart, dateEnd})) {
            if (c.moveToFirst()) {
                count = c.getInt(0);
            }
        }
        Log.d(TAG, "getEntryCountForPeriod: from=" + dateStart + ", to=" + dateEnd + ", count=" + count);
        return count;
    }

    public String getEntriesTextForPeriod(String dateStart, String dateEnd) {
        SQLiteDatabase db = dbHelper.getReadableDb();
        String sql = "SELECT " + DiaryDbHelper.COLUMN_TIMESTAMP + ", " + DiaryDbHelper.COLUMN_CONTENT
                + " FROM " + DiaryDbHelper.TABLE_DIARY
                + " WHERE " + DiaryDbHelper.COLUMN_TIMESTAMP + " >= ? AND "
                + DiaryDbHelper.COLUMN_TIMESTAMP + " < ?"
                + " ORDER BY " + DiaryDbHelper.COLUMN_TIMESTAMP + " ASC";
        StringBuilder sb = new StringBuilder();
        try (Cursor cursor = db.rawQuery(sql, new String[]{dateStart, dateEnd})) {
            while (cursor.moveToNext()) {
                String timestamp = cursor.getString(0);
                String content = cursor.getString(1);
                sb.append("[").append(timestamp).append("] ").append(content).append("\n");
            }
        }
        Log.d(TAG, "getEntriesTextForPeriod: from=" + dateStart + ", to=" + dateEnd + ", length=" + sb.length());
        return sb.toString().trim();
    }

    public String getSummariesTextForKeys(String childPeriodType, List<String> periodKeys) {
        if (periodKeys == null || periodKeys.isEmpty()) {
            return "";
        }
        SQLiteDatabase db = dbHelper.getReadableDb();

        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < periodKeys.size(); i++) {
            if (i > 0) placeholders.append(",");
            placeholders.append("?");
        }

        String[] args = new String[periodKeys.size() + 1];
        args[0] = childPeriodType;
        for (int i = 0; i < periodKeys.size(); i++) {
            args[i + 1] = periodKeys.get(i);
        }

        String sql = "SELECT " + DiaryDbHelper.COLUMN_PERIOD_KEY + ", "
                + DiaryDbHelper.COLUMN_SUMMARY_TITLE + ", "
                + DiaryDbHelper.COLUMN_SUMMARY_CONTENT
                + " FROM " + DiaryDbHelper.TABLE_SUMMARIES
                + " WHERE " + DiaryDbHelper.COLUMN_PERIOD_TYPE + " = ? AND "
                + DiaryDbHelper.COLUMN_PERIOD_KEY + " IN (" + placeholders + ")"
                + " ORDER BY " + DiaryDbHelper.COLUMN_PERIOD_KEY + " ASC";

        StringBuilder sb = new StringBuilder();
        try (Cursor cursor = db.rawQuery(sql, args)) {
            while (cursor.moveToNext()) {
                String key = cursor.getString(0);
                String title = cursor.getString(1);
                String content = cursor.getString(2);
                sb.append("【").append(key).append(" ").append(title).append("】\n")
                        .append(content).append("\n\n");
            }
        }
        Log.d(TAG, "getSummariesTextForKeys: type=" + childPeriodType
                + ", keys=" + periodKeys.size() + ", length=" + sb.length());
        return sb.toString().trim();
    }

    public Set<String> getExistingSummaryKeys(String periodType, List<String> periodKeys) {
        if (periodKeys == null || periodKeys.isEmpty()) {
            return new HashSet<>();
        }
        SQLiteDatabase db = dbHelper.getReadableDb();

        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < periodKeys.size(); i++) {
            if (i > 0) placeholders.append(",");
            placeholders.append("?");
        }

        String[] args = new String[periodKeys.size() + 1];
        args[0] = periodType;
        for (int i = 0; i < periodKeys.size(); i++) {
            args[i + 1] = periodKeys.get(i);
        }

        String sql = "SELECT " + DiaryDbHelper.COLUMN_PERIOD_KEY
                + " FROM " + DiaryDbHelper.TABLE_SUMMARIES
                + " WHERE " + DiaryDbHelper.COLUMN_PERIOD_TYPE + " = ? AND "
                + DiaryDbHelper.COLUMN_PERIOD_KEY + " IN (" + placeholders + ")";

        Set<String> existing = new HashSet<>();
        try (Cursor cursor = db.rawQuery(sql, args)) {
            while (cursor.moveToNext()) {
                existing.add(cursor.getString(0));
            }
        }
        Log.d(TAG, "getExistingSummaryKeys: type=" + periodType
                + ", requested=" + periodKeys.size() + ", found=" + existing.size());
        return existing;
    }

    /**
     * 删除指定周期的 summary 缓存。
     * 用于"重新总结"功能：清除缓存后 loadPeriodSummary 会重新调用 AI 生成。
     */
    public int deleteSummary(String periodType, String periodKey) {
        SQLiteDatabase db = dbHelper.getWritableDb();
        int rows = db.delete(DiaryDbHelper.TABLE_SUMMARIES,
                DiaryDbHelper.COLUMN_PERIOD_TYPE + " = ? AND "
                        + DiaryDbHelper.COLUMN_PERIOD_KEY + " = ?",
                new String[]{periodType, periodKey});
        Log.d(TAG, "deleteSummary: type=" + periodType + ", key=" + periodKey + ", rows=" + rows);
        return rows;
    }
}
