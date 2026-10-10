package com.example.lifetrack;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import android.util.Log;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class DiaryDao {

    private static final String TAG = "DiaryDao";
    private final DiaryDbHelper dbHelper;

    public DiaryDao(Context context) {
        dbHelper = DiaryDbHelper.getInstance(context);
    }

    public long insert(String timestamp, String content) {
        SQLiteDatabase db = dbHelper.getWritableDb();
        ContentValues values = new ContentValues();
        values.put(DiaryDbHelper.COLUMN_TIMESTAMP, timestamp);
        values.put(DiaryDbHelper.COLUMN_CONTENT, content);
        long result = db.insert(DiaryDbHelper.TABLE_DIARY, null, values);
        if (result == -1) {
            Log.w(TAG, "insert failed: timestamp=" + timestamp);
        }
        return result;
    }

    public List<String[]> queryByDate(String dateStr) {
        List<String[]> entries = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDb();

        String nextDay = incrementDayOrThrow(dateStr);
        String sql = "SELECT " + DiaryDbHelper.COLUMN_ID + ", " + DiaryDbHelper.COLUMN_TIMESTAMP + ", " + DiaryDbHelper.COLUMN_CONTENT
                + " FROM " + DiaryDbHelper.TABLE_DIARY
                + " WHERE " + DiaryDbHelper.COLUMN_TIMESTAMP + " >= ? AND " + DiaryDbHelper.COLUMN_TIMESTAMP + " < ?"
                + " ORDER BY " + DiaryDbHelper.COLUMN_TIMESTAMP + " ASC";

        try (Cursor cursor = db.rawQuery(sql, new String[]{dateStr, nextDay})) {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String timestamp = cursor.getString(1);
                String content = cursor.getString(2);
                entries.add(new String[]{id, timestamp, content});
            }
        }
        return entries;
    }

    public Set<Integer> getDiaryDaysForMonth(int year, int month) {
        Set<Integer> days = new HashSet<>();
        String monthStart = String.format(Locale.US, "%04d-%02d-01", year, month + 1);
        String nextMonthStart = incrementMonth(year, month);
        SQLiteDatabase db = dbHelper.getReadableDb();

        String sql = "SELECT DISTINCT " + DiaryDbHelper.COLUMN_TIMESTAMP
                + " FROM " + DiaryDbHelper.TABLE_DIARY
                + " WHERE " + DiaryDbHelper.COLUMN_TIMESTAMP + " >= ? AND " + DiaryDbHelper.COLUMN_TIMESTAMP + " < ?"
                + " ORDER BY " + DiaryDbHelper.COLUMN_TIMESTAMP + " ASC";

        try (Cursor cursor = db.rawQuery(sql, new String[]{monthStart, nextMonthStart})) {
            while (cursor.moveToNext()) {
                String timestamp = cursor.getString(0);
                try {
                    int day = Integer.parseInt(timestamp.substring(8, 10));
                    days.add(day);
                } catch (Exception e) {
                    Log.e(TAG, "getDiaryDaysForMonth parse error: " + timestamp, e);
                }
            }
        }
        return days;
    }

    public Map<Integer, Integer> getDiaryDaysWithCounts(int year, int month) {
        Map<Integer, Integer> dayCounts = new HashMap<>();
        String monthStart = String.format(Locale.US, "%04d-%02d-01", year, month + 1);
        String nextMonthStart = incrementMonth(year, month);
        SQLiteDatabase db = dbHelper.getReadableDb();

        String sql = "SELECT substr(" + DiaryDbHelper.COLUMN_TIMESTAMP + ", 9, 2), COUNT(*)"
                + " FROM " + DiaryDbHelper.TABLE_DIARY
                + " WHERE " + DiaryDbHelper.COLUMN_TIMESTAMP + " >= ? AND " + DiaryDbHelper.COLUMN_TIMESTAMP + " < ?"
                + " GROUP BY substr(" + DiaryDbHelper.COLUMN_TIMESTAMP + ", 9, 2)";

        try (Cursor cursor = db.rawQuery(sql, new String[]{monthStart, nextMonthStart})) {
            while (cursor.moveToNext()) {
                try {
                    int day = Integer.parseInt(cursor.getString(0).trim());
                    int count = cursor.getInt(1);
                    dayCounts.put(day, count);
                } catch (NumberFormatException e) {
                    Log.e(TAG, "getDiaryDaysWithCounts parse error", e);
                }
            }
        }
        return dayCounts;
    }

    public List<String[]> getAll() {
        List<String[]> entries = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDb();
        String sql = "SELECT " + DiaryDbHelper.COLUMN_TIMESTAMP + ", " + DiaryDbHelper.COLUMN_CONTENT
                + " FROM " + DiaryDbHelper.TABLE_DIARY
                + " ORDER BY " + DiaryDbHelper.COLUMN_TIMESTAMP + " DESC";

        try (Cursor cursor = db.rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                String timestamp = cursor.getString(0);
                String content = cursor.getString(1);
                entries.add(new String[]{timestamp, content});
            }
        }
        return entries;
    }

    public List<String[]> queryByDateRange(String startDate, String endDate) {
        List<String[]> entries = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDb();

        String nextDay = incrementDayOrThrow(endDate);
        String sql = "SELECT " + DiaryDbHelper.COLUMN_ID + ", " + DiaryDbHelper.COLUMN_TIMESTAMP + ", " + DiaryDbHelper.COLUMN_CONTENT
                + " FROM " + DiaryDbHelper.TABLE_DIARY
                + " WHERE " + DiaryDbHelper.COLUMN_TIMESTAMP + " >= ? AND " + DiaryDbHelper.COLUMN_TIMESTAMP + " < ?"
                + " ORDER BY " + DiaryDbHelper.COLUMN_TIMESTAMP + " ASC";

        try (Cursor cursor = db.rawQuery(sql, new String[]{startDate, nextDay})) {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String timestamp = cursor.getString(1);
                String content = cursor.getString(2);
                entries.add(new String[]{id, timestamp, content});
            }
        }
        return entries;
    }

    /**
     * 按关键词搜索日记内容，返回匹配的条目（id, timestamp, content）。
     * 对 LIKE 通配符（%、_、\）进行转义，避免用户输入被解释为通配符。
     */
    public List<String[]> searchByKeyword(String keyword) {
        List<String[]> entries = new ArrayList<>();
        if (keyword == null || keyword.trim().isEmpty()) {
            return entries;
        }
        SQLiteDatabase db = dbHelper.getReadableDb();
        String escapedKeyword = escapeLikePattern(keyword);
        String sql = "SELECT " + DiaryDbHelper.COLUMN_ID + ", " + DiaryDbHelper.COLUMN_TIMESTAMP + ", " + DiaryDbHelper.COLUMN_CONTENT
                + " FROM " + DiaryDbHelper.TABLE_DIARY
                + " WHERE " + DiaryDbHelper.COLUMN_CONTENT + " LIKE ? ESCAPE '\\'"
                + " ORDER BY " + DiaryDbHelper.COLUMN_TIMESTAMP + " DESC";
        try (Cursor cursor = db.rawQuery(sql, new String[]{"%" + escapedKeyword + "%"})) {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String timestamp = cursor.getString(1);
                String content = cursor.getString(2);
                entries.add(new String[]{id, timestamp, content});
            }
        }
        return entries;
    }

    private String escapeLikePattern(String input) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '%' || c == '_' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public int getCount() {
        SQLiteDatabase db = dbHelper.getReadableDb();
        String sql = "SELECT COUNT(*) FROM " + DiaryDbHelper.TABLE_DIARY;
        int count = 0;
        try (Cursor cursor = db.rawQuery(sql, null)) {
            if (cursor.moveToFirst()) {
                count = cursor.getInt(0);
            }
        }
        return count;
    }

    public int getUniqueDayCount() {
        SQLiteDatabase db = dbHelper.getReadableDb();
        String sql = "SELECT COUNT(DISTINCT SUBSTR(" + DiaryDbHelper.COLUMN_TIMESTAMP + ", 1, 10)) FROM " + DiaryDbHelper.TABLE_DIARY;
        int count = 0;
        try (Cursor cursor = db.rawQuery(sql, null)) {
            if (cursor.moveToFirst()) {
                count = cursor.getInt(0);
            }
        }
        return count;
    }

    public Set<String> getUniqueDays() {
        Set<String> days = new HashSet<>();
        SQLiteDatabase db = dbHelper.getReadableDb();
        String sql = "SELECT DISTINCT SUBSTR(" + DiaryDbHelper.COLUMN_TIMESTAMP + ", 1, 10) FROM " + DiaryDbHelper.TABLE_DIARY;
        try (Cursor cursor = db.rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                String day = cursor.getString(0);
                if (day != null) {
                    days.add(day);
                }
            }
        }
        return days;
    }

    public int delete(long id) {
        SQLiteDatabase db = dbHelper.getWritableDb();
        return db.delete(
                DiaryDbHelper.TABLE_DIARY,
                DiaryDbHelper.COLUMN_ID + " = ?",
                new String[]{String.valueOf(id)}
        );
    }

    public int update(long id, String content) {
        SQLiteDatabase db = dbHelper.getWritableDb();
        ContentValues values = new ContentValues();
        values.put(DiaryDbHelper.COLUMN_CONTENT, content);
        return db.update(
                DiaryDbHelper.TABLE_DIARY,
                values,
                DiaryDbHelper.COLUMN_ID + " = ?",
                new String[]{String.valueOf(id)}
        );
    }

    private String incrementDayOrThrow(String dateStr) {
        if (dateStr == null || dateStr.length() < 10) {
            throw new IllegalArgumentException("Invalid date format: " + dateStr);
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        sdf.setLenient(false);
        try {
            java.util.Date parsed = sdf.parse(dateStr);
            if (parsed == null) {
                throw new IllegalArgumentException("Parsed date is null for: " + dateStr);
            }
            java.util.Calendar cal = java.util.Calendar.getInstance(Locale.US);
            cal.setTime(parsed);
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1);
            return sdf.format(cal.getTime());
        } catch (ParseException e) {
            throw new IllegalArgumentException("Failed to parse date: " + dateStr, e);
        }
    }

    private String incrementMonth(int year, int month) {
        java.util.Calendar cal = java.util.Calendar.getInstance(Locale.US);
        cal.set(year, month, 1);
        cal.add(java.util.Calendar.MONTH, 1);
        return String.format(Locale.US, "%04d-%02d-01",
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1);
    }

    /**
     * 查询所有未打标签的日记（status=0），用于批量补全。
     *
     * @return List<String[]>，每个元素 = {id, content}
     */
    public List<String[]> getUnlabeledDiaries() {
        List<String[]> entries = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDb();
        String sql = "SELECT " + DiaryDbHelper.COLUMN_ID + ", " + DiaryDbHelper.COLUMN_CONTENT +
                " FROM " + DiaryDbHelper.TABLE_DIARY +
                " WHERE " + DiaryDbHelper.COLUMN_STATUS + " = 0" +
                " ORDER BY " + DiaryDbHelper.COLUMN_TIMESTAMP + " ASC";
        try (Cursor cursor = db.rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                entries.add(new String[]{cursor.getString(0), cursor.getString(1)});
            }
        }
        return entries;
    }

    /**
     * 统计未打标签的日记数量（用于"我的"页面显示）。
     */
    public int getUnlabeledCount() {
        SQLiteDatabase db = dbHelper.getReadableDb();
        String sql = "SELECT COUNT(*) FROM " + DiaryDbHelper.TABLE_DIARY +
                " WHERE " + DiaryDbHelper.COLUMN_STATUS + " = 0";
        try (Cursor cursor = db.rawQuery(sql, null)) {
            if (cursor.moveToFirst()) return cursor.getInt(0);
        }
        return 0;
    }
}
