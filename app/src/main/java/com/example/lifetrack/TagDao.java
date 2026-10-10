package com.example.lifetrack;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * 标签 DAO：负责标签 CRUD、日记-标签关联、回顾选取算法。
 *
 * 选取算法（pickTagAndDiaries）：
 * 1. 排除 7 天内已展示过的标签
 * 2. 从剩余标签中随机选 1 个
 * 3. 从该标签关联的日记中随机选最多 3 篇（排除今日）
 * 4. 更新该标签的 last_shown_date
 *
 * 兜底：若所有标签都在 7 天内展示过，放宽为全部标签。
 */
public class TagDao {

    private static final String TAG = "TagDao";
    private final DiaryDbHelper dbHelper;

    public TagDao(Context context) {
        this.dbHelper = DiaryDbHelper.getInstance(context.getApplicationContext());
    }

    // ===================== 标签查询 =====================

    /**
     * 获取所有标签名（按 id 升序），用于 AI prompt 动态拼接。
     */
    public List<String> getAllTagNames() {
        List<String> tags = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDb();
        String sql = "SELECT " + DiaryDbHelper.COLUMN_TAG_NAME +
                " FROM " + DiaryDbHelper.TABLE_TAG +
                " ORDER BY " + DiaryDbHelper.COLUMN_TAG_ID + " ASC";
        try (Cursor cursor = db.rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                tags.add(cursor.getString(0));
            }
        }
        return tags;
    }

    /**
     * 根据 id 查询标签名。
     */
    public String getTagName(long tagId) {
        SQLiteDatabase db = dbHelper.getReadableDb();
        String sql = "SELECT " + DiaryDbHelper.COLUMN_TAG_NAME +
                " FROM " + DiaryDbHelper.TABLE_TAG +
                " WHERE " + DiaryDbHelper.COLUMN_TAG_ID + " = ?";
        try (Cursor cursor = db.rawQuery(sql, new String[]{String.valueOf(tagId)})) {
            if (cursor.moveToFirst()) return cursor.getString(0);
        }
        return null;
    }

    /**
     * 根据标签名查询 id，不存在返回 -1。
     */
    public long getTagId(String name) {
        SQLiteDatabase db = dbHelper.getReadableDb();
        String sql = "SELECT " + DiaryDbHelper.COLUMN_TAG_ID +
                " FROM " + DiaryDbHelper.TABLE_TAG +
                " WHERE " + DiaryDbHelper.COLUMN_TAG_NAME + " = ?";
        try (Cursor cursor = db.rawQuery(sql, new String[]{name})) {
            if (cursor.moveToFirst()) return cursor.getLong(0);
        }
        return -1;
    }

    // ===================== 日记打标签 =====================

    /**
     * 为日记批量设置标签（先清除旧关联，再插入新关联）。
     * 调用方需保证 tags 都在标签库中（AI 返回后应校验）。
     * 同时更新 diary.status = 1。
     */
    public void setTagsForDiary(long diaryId, List<String> tags) {
        SQLiteDatabase db = dbHelper.getWritableDb();
        db.beginTransaction();
        try {
            // 清除旧关联
            db.delete(DiaryDbHelper.TABLE_DIARY_TAG,
                    DiaryDbHelper.COLUMN_DT_DIARY_ID + " = ?",
                    new String[]{String.valueOf(diaryId)});

            // 插入新关联
            for (String tagName : tags) {
                long tagId = getTagId(tagName);
                if (tagId < 0) {
                    Log.w(TAG, "setTagsForDiary: tag not found: " + tagName + ", skipped");
                    continue;
                }
                ContentValues values = new ContentValues();
                values.put(DiaryDbHelper.COLUMN_DT_DIARY_ID, diaryId);
                values.put(DiaryDbHelper.COLUMN_DT_TAG_ID, tagId);
                db.insertWithOnConflict(DiaryDbHelper.TABLE_DIARY_TAG, null, values,
                        SQLiteDatabase.CONFLICT_IGNORE);
            }

            // 更新 diary.status = 1（已打标签）
            ContentValues statusValues = new ContentValues();
            statusValues.put(DiaryDbHelper.COLUMN_STATUS, 1);
            db.update(DiaryDbHelper.TABLE_DIARY, statusValues,
                    DiaryDbHelper.COLUMN_ID + " = ?",
                    new String[]{String.valueOf(diaryId)});

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * 获取日记的所有标签名。
     */
    public List<String> getTagsForDiary(long diaryId) {
        List<String> tags = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDb();
        String sql = "SELECT t." + DiaryDbHelper.COLUMN_TAG_NAME +
                " FROM " + DiaryDbHelper.TABLE_TAG + " t" +
                " INNER JOIN " + DiaryDbHelper.TABLE_DIARY_TAG + " dt" +
                " ON t." + DiaryDbHelper.COLUMN_TAG_ID + " = dt." + DiaryDbHelper.COLUMN_DT_TAG_ID +
                " WHERE dt." + DiaryDbHelper.COLUMN_DT_DIARY_ID + " = ?" +
                " ORDER BY t." + DiaryDbHelper.COLUMN_TAG_ID + " ASC";
        try (Cursor cursor = db.rawQuery(sql, new String[]{String.valueOf(diaryId)})) {
            while (cursor.moveToNext()) {
                tags.add(cursor.getString(0));
            }
        }
        return tags;
    }

    // ===================== 选取算法 =====================

    /**
     * 选取 1 个标签 + 最多 3 篇日记用于今日回顾。
     *
     * @param today 今日日期字符串（yyyy-MM-dd）
     * @return 选取结果，tagId/tagName + 日记列表（id, timestamp, content）；无可用时返回 null
     */
    public ReviewPick pickTagAndDiaries(String today) {
        SQLiteDatabase db = dbHelper.getReadableDb();

        // 1. 查询所有标签 + 排除 7 天内展示过的
        // 计算 7 天前的日期（yyyy-MM-dd），last_shown_date > sevenDaysAgo 的标签被排除
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -7);
        String sevenDaysAgo = DateUtils.formatDate(cal.getTime());

        String sql = "SELECT t." + DiaryDbHelper.COLUMN_TAG_ID +
                " FROM " + DiaryDbHelper.TABLE_TAG + " t" +
                " WHERE t." + DiaryDbHelper.COLUMN_TAG_ID + " NOT IN (" +
                "  SELECT " + DiaryDbHelper.COLUMN_TLS_TAG_ID +
                "  FROM " + DiaryDbHelper.TABLE_TAG_LAST_SHOWN +
                "  WHERE " + DiaryDbHelper.COLUMN_TLS_LAST_SHOWN + " > ?" +
                ")";

        List<Long> tagIds = new ArrayList<>();
        try (Cursor cursor = db.rawQuery(sql, new String[]{sevenDaysAgo})) {
            while (cursor.moveToNext()) {
                tagIds.add(cursor.getLong(0));
            }
        }

        // 兜底：7 天内全展示过 → 放宽为全部标签
        if (tagIds.isEmpty()) {
            Log.w(TAG, "pickTagAndDiaries: all tags shown within 7 days, fallback to all");
            String allSql = "SELECT " + DiaryDbHelper.COLUMN_TAG_ID +
                    " FROM " + DiaryDbHelper.TABLE_TAG;
            try (Cursor cursor = db.rawQuery(allSql, null)) {
                while (cursor.moveToNext()) {
                    tagIds.add(cursor.getLong(0));
                }
            }
        }

        if (tagIds.isEmpty()) return null; // 标签库为空

        // 2. 随机选 1 个标签（优先选有关联日记的，重试 5 次）
        long pickedTagId = -1;
        Collections.shuffle(tagIds);
        for (long tid : tagIds) {
            int count = countDiariesWithTag(db, tid, today);
            if (count > 0) {
                pickedTagId = tid;
                break;
            }
        }
        if (pickedTagId < 0) return null; // 没有任何标签有关联日记

        String pickedTagName = getTagName(pickedTagId);
        if (pickedTagName == null) return null;

        // 3. 从该标签关联的日记中随机选最多 3 篇（排除今日）
        List<String[]> diaries = getRandomDiariesWithTag(db, pickedTagId, today, 3);

        // 4. 更新该标签的 last_shown_date = today
        updateTagLastShown(pickedTagId, today);

        return new ReviewPick(pickedTagId, pickedTagName, diaries);
    }

    /**
     * 统计某标签下可用日记数（排除今日）。
     */
    private int countDiariesWithTag(SQLiteDatabase db, long tagId, String today) {
        String todayPrefix = today + " %"; // timestamp 格式为 "yyyy-MM-dd HH:mm:ss"
        String sql = "SELECT COUNT(*) FROM " + DiaryDbHelper.TABLE_DIARY + " d" +
                " INNER JOIN " + DiaryDbHelper.TABLE_DIARY_TAG + " dt" +
                " ON d." + DiaryDbHelper.COLUMN_ID + " = dt." + DiaryDbHelper.COLUMN_DT_DIARY_ID +
                " WHERE dt." + DiaryDbHelper.COLUMN_DT_TAG_ID + " = ?" +
                " AND d." + DiaryDbHelper.COLUMN_TIMESTAMP + " NOT LIKE ?";
        try (Cursor cursor = db.rawQuery(sql, new String[]{String.valueOf(tagId), todayPrefix})) {
            if (cursor.moveToFirst()) return cursor.getInt(0);
        }
        return 0;
    }

    /**
     * 从某标签关联的日记中随机选最多 limit 篇（排除今日）。
     * 返回 List<String[]>，每个元素 = {id, timestamp, content}
     */
    private List<String[]> getRandomDiariesWithTag(SQLiteDatabase db, long tagId, String today, int limit) {
        List<String[]> entries = new ArrayList<>();
        String todayPrefix = today + " %";
        String sql = "SELECT d." + DiaryDbHelper.COLUMN_ID +
                ", d." + DiaryDbHelper.COLUMN_TIMESTAMP +
                ", d." + DiaryDbHelper.COLUMN_CONTENT +
                " FROM " + DiaryDbHelper.TABLE_DIARY + " d" +
                " INNER JOIN " + DiaryDbHelper.TABLE_DIARY_TAG + " dt" +
                " ON d." + DiaryDbHelper.COLUMN_ID + " = dt." + DiaryDbHelper.COLUMN_DT_DIARY_ID +
                " WHERE dt." + DiaryDbHelper.COLUMN_DT_TAG_ID + " = ?" +
                " AND d." + DiaryDbHelper.COLUMN_TIMESTAMP + " NOT LIKE ?" +
                " ORDER BY RANDOM() LIMIT ?";
        try (Cursor cursor = db.rawQuery(sql, new String[]{String.valueOf(tagId), todayPrefix, String.valueOf(limit)})) {
            while (cursor.moveToNext()) {
                entries.add(new String[]{
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getString(2)
                });
            }
        }
        return entries;
    }

    /**
     * 更新标签最近展示日期（UPSERT）。
     */
    private void updateTagLastShown(long tagId, String date) {
        SQLiteDatabase db = dbHelper.getWritableDb();
        ContentValues values = new ContentValues();
        values.put(DiaryDbHelper.COLUMN_TLS_TAG_ID, tagId);
        values.put(DiaryDbHelper.COLUMN_TLS_LAST_SHOWN, date);
        db.insertWithOnConflict(DiaryDbHelper.TABLE_TAG_LAST_SHOWN, null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ===================== 结果类 =====================

    /**
     * 选取结果：1 个标签 + 最多 3 篇日记。
     */
    public static class ReviewPick {
        public final long tagId;
        public final String tagName;
        public final List<String[]> diaries; // 每篇 = {id, timestamp, content}

        public ReviewPick(long tagId, String tagName, List<String[]> diaries) {
            this.tagId = tagId;
            this.tagName = tagName;
            this.diaries = diaries;
        }
    }
}
