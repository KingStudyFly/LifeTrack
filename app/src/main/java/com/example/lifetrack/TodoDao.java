package com.example.lifetrack;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 待办数据访问。
 * 待办不再按日期分区：创建后一直保留，直到用户手动勾选完成。
 * date 字段仅记录来源（手动添加的当天日期，或提取该待办的日记日期），不参与查询过滤。
 */
public class TodoDao {
    /** 标题归一化：剥离标点与空白，避免"买牛奶。"与"买牛奶"被当作两条待办。 */
    private static final Pattern TITLE_NOISE = Pattern.compile("[\\p{P}\\p{Z}\\s]+");

    private final DiaryDbHelper dbHelper;

    public TodoDao(Context context) {
        dbHelper = DiaryDbHelper.getInstance(context);
    }

    /**
     * 查询全部待办。未完成在前并按创建时间升序，已完成沉底。
     */
    public List<TodoItem> queryAll() {
        List<TodoItem> result = new ArrayList<>();
        String sql = "SELECT _id,date,time,title,description,completed,source FROM todo " +
                "ORDER BY completed ASC, created_at ASC, _id ASC";
        try (Cursor cursor = dbHelper.getReadableDb().rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                result.add(readItem(cursor));
            }
        }
        return result;
    }

    public long insert(String date, String time, String title, String description, String source) {
        ContentValues values = values(date, time, title, description, source);
        return dbHelper.getWritableDb().insert(DiaryDbHelper.TABLE_TODO, null, values);
    }

    /**
     * 追加从日记中提取的待办。已存在同标题的待办（含已完成）会被跳过，
     * 本批内部的重复项也只保留一条。写入失败不影响调用方。
     *
     * @return 实际新增条数
     */
    public int appendExtracted(String date, List<DeepSeekClient.ScheduleItem> items) {
        if (items == null || items.isEmpty()) return 0;
        SQLiteDatabase db = dbHelper.getWritableDb();
        int added = 0;
        db.beginTransaction();
        try {
            Set<String> seen = loadNormalizedTitles(db);
            for (DeepSeekClient.ScheduleItem item : items) {
                if (item == null) continue;
                String title = clean(item.title);
                if (title.isEmpty()) continue;
                String key = normalizeTitle(title);
                if (key.isEmpty() || !seen.add(key)) continue;
                ContentValues values = values(date, item.time, title, item.desc, "ai");
                if (db.insert(DiaryDbHelper.TABLE_TODO, null, values) != -1) added++;
            }
            db.setTransactionSuccessful();
        } catch (Exception e) {
            android.util.Log.w("TodoDao", "appendExtracted failed", e);
        } finally {
            db.endTransaction();
        }
        return added;
    }

    private Set<String> loadNormalizedTitles(SQLiteDatabase db) {
        Set<String> result = new HashSet<>();
        try (Cursor cursor = db.rawQuery("SELECT title FROM todo", null)) {
            while (cursor.moveToNext()) {
                String key = normalizeTitle(cursor.getString(0));
                if (!key.isEmpty()) result.add(key);
            }
        }
        return result;
    }

    public void setCompleted(long id, boolean completed) {
        ContentValues values = new ContentValues();
        values.put(DiaryDbHelper.COLUMN_TODO_COMPLETED, completed ? 1 : 0);
        dbHelper.getWritableDb().update(DiaryDbHelper.TABLE_TODO, values, "_id=?",
                new String[]{String.valueOf(id)});
    }

    public void delete(long id) {
        dbHelper.getWritableDb().delete(DiaryDbHelper.TABLE_TODO, "_id=?",
                new String[]{String.valueOf(id)});
    }

    private static TodoItem readItem(Cursor cursor) {
        return new TodoItem(cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                cursor.getString(3), cursor.getString(4), cursor.getInt(5) == 1,
                cursor.getString(6));
    }

    private ContentValues values(String date, String time, String title, String description, String source) {
        ContentValues values = new ContentValues();
        values.put(DiaryDbHelper.COLUMN_TODO_DATE, clean(date));
        values.put(DiaryDbHelper.COLUMN_TODO_TIME, clean(time));
        values.put(DiaryDbHelper.COLUMN_TODO_TITLE, clean(title));
        values.put(DiaryDbHelper.COLUMN_TODO_DESC, clean(description));
        values.put(DiaryDbHelper.COLUMN_TODO_SOURCE, source);
        values.put(DiaryDbHelper.COLUMN_TODO_CREATED_AT,
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        return values;
    }

    /** 归一化待办标题用于去重比较：小写 + 去掉标点空白。 */
    static String normalizeTitle(String title) {
        if (title == null) return "";
        return TITLE_NOISE.matcher(title).replaceAll("").toLowerCase(Locale.US);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
