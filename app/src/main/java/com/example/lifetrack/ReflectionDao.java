package com.example.lifetrack;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/**
 * 回顾感想 DAO：负责 reflection 表的增删查。
 * 一篇日记可以有多条感想（一对多），按创建时间倒序展示。
 */
public class ReflectionDao {

    private final DiaryDbHelper dbHelper;

    public ReflectionDao(Context context) {
        this.dbHelper = DiaryDbHelper.getInstance(context.getApplicationContext());
    }

    /**
     * 添加一条回顾感想。
     *
     * @param diaryId 关联的日记 id
     * @param content 感想内容
     * @param createdAt 创建时间（yyyy-MM-dd HH:mm:ss）
     * @return 新插入的感想 id，失败返回 -1
     */
    public long addReflection(long diaryId, String content, String createdAt) {
        SQLiteDatabase db = dbHelper.getWritableDb();
        ContentValues values = new ContentValues();
        values.put(DiaryDbHelper.COLUMN_REFLECTION_DIARY_ID, diaryId);
        values.put(DiaryDbHelper.COLUMN_REFLECTION_CONTENT, content);
        values.put(DiaryDbHelper.COLUMN_REFLECTION_CREATED_AT, createdAt);
        return db.insert(DiaryDbHelper.TABLE_REFLECTION, null, values);
    }

    /**
     * 查询某篇日记的所有感想，按创建时间倒序。
     *
     * @return List<Reflection>，每条含 id/diaryId/content/createdAt
     */
    public List<Reflection> getReflectionsForDiary(long diaryId) {
        List<Reflection> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDb();
        String sql = "SELECT " + DiaryDbHelper.COLUMN_REFLECTION_ID +
                ", " + DiaryDbHelper.COLUMN_REFLECTION_DIARY_ID +
                ", " + DiaryDbHelper.COLUMN_REFLECTION_CONTENT +
                ", " + DiaryDbHelper.COLUMN_REFLECTION_CREATED_AT +
                " FROM " + DiaryDbHelper.TABLE_REFLECTION +
                " WHERE " + DiaryDbHelper.COLUMN_REFLECTION_DIARY_ID + " = ?" +
                " ORDER BY " + DiaryDbHelper.COLUMN_REFLECTION_CREATED_AT + " DESC";
        try (Cursor cursor = db.rawQuery(sql, new String[]{String.valueOf(diaryId)})) {
            while (cursor.moveToNext()) {
                list.add(new Reflection(
                        cursor.getLong(0),
                        cursor.getLong(1),
                        cursor.getString(2),
                        cursor.getString(3)
                ));
            }
        }
        return list;
    }

    /**
     * 统计某篇日记的感想数量（用于卡片上显示"已有 N 条感想"）。
     */
    public int countReflections(long diaryId) {
        SQLiteDatabase db = dbHelper.getReadableDb();
        String sql = "SELECT COUNT(*) FROM " + DiaryDbHelper.TABLE_REFLECTION +
                " WHERE " + DiaryDbHelper.COLUMN_REFLECTION_DIARY_ID + " = ?";
        try (Cursor cursor = db.rawQuery(sql, new String[]{String.valueOf(diaryId)})) {
            if (cursor.moveToFirst()) return cursor.getInt(0);
        }
        return 0;
    }

    /**
     * 删除单条感想。
     */
    public void deleteReflection(long reflectionId) {
        SQLiteDatabase db = dbHelper.getWritableDb();
        db.delete(DiaryDbHelper.TABLE_REFLECTION,
                DiaryDbHelper.COLUMN_REFLECTION_ID + " = ?",
                new String[]{String.valueOf(reflectionId)});
    }

    /**
     * 感想数据模型。
     */
    public static class Reflection {
        public final long id;
        public final long diaryId;
        public final String content;
        public final String createdAt;

        public Reflection(long id, long diaryId, String content, String createdAt) {
            this.id = id;
            this.diaryId = diaryId;
            this.content = content;
            this.createdAt = createdAt;
        }
    }
}
