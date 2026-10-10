package com.example.lifetrack;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.text.TextUtils;
import android.util.Log;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * 维度记录 DAO：负责运动/睡眠/社交三维度打分的读写。
 * 数据来源由 DeepSeek 在日记日期拆分时一并分析得出，存入 dimension_records 表。
 */
public class DimensionDao {

    private static final String TAG = "DimensionDao";
    private final DiaryDbHelper dbHelper;

    public DimensionDao(Context context) {
        dbHelper = DiaryDbHelper.getInstance(context);
    }

    /**
     * 单条维度记录数据载体。
     * 各 score 字段为 null 表示当天日记未提及该维度。
     */
    public static class DimensionRecord {
        public final long id;
        public final String date;
        public final Long diaryId;
        public final Integer exerciseScore;
        public final List<String> exerciseTypes;
        public final Integer sleepScore;
        public final List<String> socialContacts;
        public final String rawExcerpt;
        public final String createdAt;

        public DimensionRecord(long id, String date, Long diaryId,
                              Integer exerciseScore, List<String> exerciseTypes,
                              Integer sleepScore, List<String> socialContacts,
                              String rawExcerpt, String createdAt) {
            this.id = id;
            this.date = date;
            this.diaryId = diaryId;
            this.exerciseScore = exerciseScore;
            this.exerciseTypes = exerciseTypes;
            this.sleepScore = sleepScore;
            this.socialContacts = socialContacts;
            this.rawExcerpt = rawExcerpt;
            this.createdAt = createdAt;
        }
    }

    /**
     * 写入一条维度记录。
     * 各 score 传 null 表示当天未提及该维度。
     * exerciseTypes / socialContacts 传 null 或空列表时存 NULL。
     */
    public long insert(String date, Long diaryId,
                       Integer exerciseScore, List<String> exerciseTypes,
                       Integer sleepScore, List<String> socialContacts,
                       String rawExcerpt) {
        SQLiteDatabase db = dbHelper.getWritableDb();
        ContentValues values = new ContentValues();
        values.put(DiaryDbHelper.COLUMN_DR_DATE, date);
        if (diaryId != null) {
            values.put(DiaryDbHelper.COLUMN_DR_DIARY_ID, diaryId);
        }
        if (exerciseScore != null) {
            values.put(DiaryDbHelper.COLUMN_DR_EXERCISE_SCORE, exerciseScore);
        }
        if (exerciseTypes != null && !exerciseTypes.isEmpty()) {
            values.put(DiaryDbHelper.COLUMN_DR_EXERCISE_TYPES, listToJsonArray(exerciseTypes));
        }
        if (sleepScore != null) {
            values.put(DiaryDbHelper.COLUMN_DR_SLEEP_SCORE, sleepScore);
        }
        if (socialContacts != null && !socialContacts.isEmpty()) {
            values.put(DiaryDbHelper.COLUMN_DR_SOCIAL_CONTACTS, listToJsonArray(socialContacts));
        }
        if (rawExcerpt != null) {
            values.put(DiaryDbHelper.COLUMN_DR_RAW_EXCERPT, rawExcerpt);
        }
        values.put(DiaryDbHelper.COLUMN_DR_CREATED_AT, DateUtils.currentDateTime());

        long result = db.insert(DiaryDbHelper.TABLE_DIMENSION_RECORDS, null, values);
        Log.d(TAG, "insert: date=" + date + ", diaryId=" + diaryId
                + ", exScore=" + exerciseScore + ", sleepScore=" + sleepScore
                + ", result=" + result);
        return result;
    }

    /**
     * 取指定日期范围内所有维度记录，按日期升序。
     * 用于三个 Tab 渲染波浪图与卡片。
     */
    public List<DimensionRecord> getByDateRange(String startDate, String endDate) {
        SQLiteDatabase db = dbHelper.getReadableDb();
        String sql = "SELECT * FROM " + DiaryDbHelper.TABLE_DIMENSION_RECORDS
                + " WHERE " + DiaryDbHelper.COLUMN_DR_DATE + " >= ? AND "
                + DiaryDbHelper.COLUMN_DR_DATE + " <= ?"
                + " ORDER BY " + DiaryDbHelper.COLUMN_DR_DATE + " ASC";
        List<DimensionRecord> list = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, new String[]{startDate, endDate})) {
            while (c.moveToNext()) {
                list.add(cursorToRecord(c));
            }
        }
        Log.d(TAG, "getByDateRange: " + startDate + " ~ " + endDate + ", count=" + list.size());
        return list;
    }

    /**
     * 取最近 N 天的维度记录，用于 AI 打分时作为参考上下文、保证标准统一。
     * 排序按日期降序（近期在前）。
     */
    public List<DimensionRecord> getRecent(int days) {
        SQLiteDatabase db = dbHelper.getReadableDb();
        String sql = "SELECT * FROM " + DiaryDbHelper.TABLE_DIMENSION_RECORDS
                + " WHERE " + DiaryDbHelper.COLUMN_DR_DATE + " >= date('now', '-" + days + " days')"
                + " ORDER BY " + DiaryDbHelper.COLUMN_DR_DATE + " DESC";
        List<DimensionRecord> list = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, null)) {
            while (c.moveToNext()) {
                list.add(cursorToRecord(c));
            }
        }
        Log.d(TAG, "getRecent: days=" + days + ", count=" + list.size());
        return list;
    }

    /**
     * 拼接近期打分摘要文本，作为 AI prompt 的参考上下文。
     * 格式："2026-07-01 运动:65[上肢,核心] 睡眠:72 社交:[妈妈,小李]"
     * 只输出非空维度，便于 AI 校准标准。
     */
    public String getRecentScoresText(int days) {
        List<DimensionRecord> records = getRecent(days);
        if (records.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (DimensionRecord r : records) {
            sb.append(r.date);
            if (r.exerciseScore != null) {
                sb.append(" 运动:").append(r.exerciseScore);
                if (r.exerciseTypes != null && !r.exerciseTypes.isEmpty()) {
                    sb.append(r.exerciseTypes.toString());
                }
            }
            if (r.sleepScore != null) {
                sb.append(" 睡眠:").append(r.sleepScore);
            }
            if (r.socialContacts != null && !r.socialContacts.isEmpty()) {
                sb.append(" 社交:").append(r.socialContacts.toString());
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 取该日期已有维度记录的 id，用于回填时跳过。
     */
    public boolean hasRecordForDate(String date) {
        SQLiteDatabase db = dbHelper.getReadableDb();
        String sql = "SELECT 1 FROM " + DiaryDbHelper.TABLE_DIMENSION_RECORDS
                + " WHERE " + DiaryDbHelper.COLUMN_DR_DATE + " = ? LIMIT 1";
        try (Cursor c = db.rawQuery(sql, new String[]{date})) {
            return c.moveToFirst();
        }
    }

    /**
     * 取最近 N 个月（按自然月）该人物出现过的维度记录，用于社交 Tab。
     * 在 Java 层做人物匹配过滤，因为 SQLite 对 JSON 数组的 LIKE 匹配不够精确。
     */
    public List<DimensionRecord> getBySocialContact(String contactName, String startDate) {
        SQLiteDatabase db = dbHelper.getReadableDb();
        String sql = "SELECT * FROM " + DiaryDbHelper.TABLE_DIMENSION_RECORDS
                + " WHERE " + DiaryDbHelper.COLUMN_DR_SOCIAL_CONTACTS + " IS NOT NULL"
                + " AND " + DiaryDbHelper.COLUMN_DR_DATE + " >= ?"
                + " ORDER BY " + DiaryDbHelper.COLUMN_DR_DATE + " DESC";
        List<DimensionRecord> all = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, new String[]{startDate})) {
            while (c.moveToNext()) {
                all.add(cursorToRecord(c));
            }
        }
        // 在 Java 层过滤包含该人物的记录
        List<DimensionRecord> filtered = new ArrayList<>();
        for (DimensionRecord r : all) {
            if (r.socialContacts != null) {
                for (String contact : r.socialContacts) {
                    if (contact != null && contact.contains(contactName)) {
                        filtered.add(r);
                        break;
                    }
                }
            }
        }
        Log.d(TAG, "getBySocialContact: name=" + contactName
                + ", from=" + startDate + ", matched=" + filtered.size());
        return filtered;
    }

    /**
     * 取指定日期范围内所有社交维度记录（social_contacts 非空），用于社交 Tab 列表。
     */
    public List<DimensionRecord> getSocialRecords(String startDate) {
        SQLiteDatabase db = dbHelper.getReadableDb();
        String sql = "SELECT * FROM " + DiaryDbHelper.TABLE_DIMENSION_RECORDS
                + " WHERE " + DiaryDbHelper.COLUMN_DR_SOCIAL_CONTACTS + " IS NOT NULL"
                + " AND " + DiaryDbHelper.COLUMN_DR_DATE + " >= ?"
                + " ORDER BY " + DiaryDbHelper.COLUMN_DR_DATE + " DESC";
        List<DimensionRecord> list = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, new String[]{startDate})) {
            while (c.moveToNext()) {
                list.add(cursorToRecord(c));
            }
        }
        Log.d(TAG, "getSocialRecords: from=" + startDate + ", count=" + list.size());
        return list;
    }

    private DimensionRecord cursorToRecord(Cursor c) {
        long id = c.getLong(c.getColumnIndexOrThrow(DiaryDbHelper.COLUMN_DR_ID));
        String date = c.getString(c.getColumnIndexOrThrow(DiaryDbHelper.COLUMN_DR_DATE));
        Long diaryId = c.isNull(c.getColumnIndexOrThrow(DiaryDbHelper.COLUMN_DR_DIARY_ID))
                ? null : c.getLong(c.getColumnIndexOrThrow(DiaryDbHelper.COLUMN_DR_DIARY_ID));
        Integer exScore = c.isNull(c.getColumnIndexOrThrow(DiaryDbHelper.COLUMN_DR_EXERCISE_SCORE))
                ? null : c.getInt(c.getColumnIndexOrThrow(DiaryDbHelper.COLUMN_DR_EXERCISE_SCORE));
        List<String> exTypes = parseJsonArray(c.getString(c.getColumnIndexOrThrow(DiaryDbHelper.COLUMN_DR_EXERCISE_TYPES)));
        Integer sleepScore = c.isNull(c.getColumnIndexOrThrow(DiaryDbHelper.COLUMN_DR_SLEEP_SCORE))
                ? null : c.getInt(c.getColumnIndexOrThrow(DiaryDbHelper.COLUMN_DR_SLEEP_SCORE));
        List<String> contacts = parseJsonArray(c.getString(c.getColumnIndexOrThrow(DiaryDbHelper.COLUMN_DR_SOCIAL_CONTACTS)));
        String excerpt = c.getString(c.getColumnIndexOrThrow(DiaryDbHelper.COLUMN_DR_RAW_EXCERPT));
        String createdAt = c.getString(c.getColumnIndexOrThrow(DiaryDbHelper.COLUMN_DR_CREATED_AT));
        return new DimensionRecord(id, date, diaryId, exScore, exTypes, sleepScore, contacts, excerpt, createdAt);
    }

    private static String listToJsonArray(List<String> list) {
        JSONArray arr = new JSONArray();
        for (String s : list) {
            arr.put(s);
        }
        return arr.toString();
    }

    private static List<String> parseJsonArray(String json) {
        if (TextUtils.isEmpty(json)) return null;
        List<String> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                list.add(arr.getString(i));
            }
        } catch (Exception e) {
            Log.w(TAG, "parseJsonArray failed: " + json, e);
        }
        return list;
    }
}
