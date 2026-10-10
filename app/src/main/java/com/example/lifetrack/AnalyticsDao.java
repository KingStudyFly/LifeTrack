package com.example.lifetrack;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class AnalyticsDao {

    private static final String TAG = "AnalyticsDao";
    private static final Set<String> ALLOWED_OPEN_COLUMNS = new HashSet<>(Arrays.asList(
            DiaryDbHelper.COLUMN_DU_APP_OPENS,
            DiaryDbHelper.COLUMN_DU_RECORD_OPENS,
            DiaryDbHelper.COLUMN_DU_TRACK_OPENS,
            DiaryDbHelper.COLUMN_DU_DIARY_OPENS,
            DiaryDbHelper.COLUMN_DU_PROFILE_OPENS
    ));
    private final DiaryDbHelper dbHelper;

    public AnalyticsDao(Context context) {
        dbHelper = DiaryDbHelper.getInstance(context);
    }

    public void addForegroundSeconds(String date, long seconds) {
        if (seconds <= 0) return;
        SQLiteDatabase db = dbHelper.getWritableDb();
        db.beginTransaction();
        try {
            try (Cursor cursor = db.query(DiaryDbHelper.TABLE_DAILY_USAGE,
                    new String[]{DiaryDbHelper.COLUMN_DU_SECONDS},
                    DiaryDbHelper.COLUMN_DU_DATE + "=?",
                    new String[]{date}, null, null, null)) {
                if (cursor.moveToFirst()) {
                    long current = cursor.getLong(0);
                    ContentValues values = new ContentValues();
                    values.put(DiaryDbHelper.COLUMN_DU_SECONDS, current + seconds);
                    db.update(DiaryDbHelper.TABLE_DAILY_USAGE, values,
                            DiaryDbHelper.COLUMN_DU_DATE + "=?",
                            new String[]{date});
                    Log.d(TAG, "addForegroundSeconds: date=" + date
                            + ", added=" + seconds + "s, total=" + (current + seconds) + "s");
                } else {
                    ContentValues values = new ContentValues();
                    values.put(DiaryDbHelper.COLUMN_DU_DATE, date);
                    values.put(DiaryDbHelper.COLUMN_DU_SECONDS, seconds);
                    db.insert(DiaryDbHelper.TABLE_DAILY_USAGE, null, values);
                    Log.d(TAG, "addForegroundSeconds: date=" + date + ", seconds=" + seconds + "s (new)");
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void incrementOpenCount(String date, String column) {
        if (column == null || !ALLOWED_OPEN_COLUMNS.contains(column)) {
            Log.w(TAG, "incrementOpenCount rejected invalid column: " + column);
            return;
        }
        SQLiteDatabase db = dbHelper.getWritableDb();
        db.beginTransaction();
        try {
            try (Cursor cursor = db.query(DiaryDbHelper.TABLE_DAILY_USAGE,
                    new String[]{column},
                    DiaryDbHelper.COLUMN_DU_DATE + "=?",
                    new String[]{date}, null, null, null)) {
                if (cursor.moveToFirst()) {
                    int current = cursor.getInt(0);
                    ContentValues values = new ContentValues();
                    values.put(column, current + 1);
                    db.update(DiaryDbHelper.TABLE_DAILY_USAGE, values,
                            DiaryDbHelper.COLUMN_DU_DATE + "=?",
                            new String[]{date});
                } else {
                    ContentValues values = new ContentValues();
                    values.put(DiaryDbHelper.COLUMN_DU_DATE, date);
                    values.put(DiaryDbHelper.COLUMN_DU_SECONDS, 0);
                    values.put(column, 1);
                    db.insert(DiaryDbHelper.TABLE_DAILY_USAGE, null, values);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public long getTodaySeconds() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String today = sdf.format(new Date());
        return getSecondsForDate(today);
    }

    public long getSecondsForDate(String date) {
        SQLiteDatabase db = dbHelper.getReadableDb();
        try (Cursor cursor = db.query(DiaryDbHelper.TABLE_DAILY_USAGE,
                new String[]{DiaryDbHelper.COLUMN_DU_SECONDS},
                DiaryDbHelper.COLUMN_DU_DATE + "=?",
                new String[]{date}, null, null, null)) {
            if (cursor.moveToFirst()) return cursor.getLong(0);
        }
        return 0;
    }

    public long getTotalSeconds() {
        SQLiteDatabase db = dbHelper.getReadableDb();
        try (Cursor cursor = db.query(DiaryDbHelper.TABLE_DAILY_USAGE,
                new String[]{"COALESCE(SUM(" + DiaryDbHelper.COLUMN_DU_SECONDS + "), 0)"},
                null, null, null, null, null)) {
            if (cursor.moveToFirst()) return cursor.getLong(0);
        }
        return 0;
    }

    public int getDaysUsed() {
        SQLiteDatabase db = dbHelper.getReadableDb();
        try (Cursor cursor = db.query(DiaryDbHelper.TABLE_DAILY_USAGE,
                new String[]{"COUNT(*)"},
                DiaryDbHelper.COLUMN_DU_SECONDS + " > 0",
                null, null, null, null)) {
            if (cursor.moveToFirst()) return cursor.getInt(0);
        }
        return 0;
    }

    public int getSessionCount() {
        SQLiteDatabase db = dbHelper.getReadableDb();
        try (Cursor cursor = db.query(DiaryDbHelper.TABLE_DAILY_USAGE,
                new String[]{"COUNT(*)"},
                null, null, null, null, null)) {
            if (cursor.moveToFirst()) return cursor.getInt(0);
        }
        return 0;
    }

    public static class DailyUsage {
        public final String date;
        public final long foregroundSeconds;
        public final int appOpens;
        public final int recordOpens;
        public final int trackOpens;
        public final int diaryOpens;
        public final int profileOpens;

        public DailyUsage(String date, long foregroundSeconds, int appOpens,
                          int recordOpens, int trackOpens, int diaryOpens, int profileOpens) {
            this.date = date;
            this.foregroundSeconds = foregroundSeconds;
            this.appOpens = appOpens;
            this.recordOpens = recordOpens;
            this.trackOpens = trackOpens;
            this.diaryOpens = diaryOpens;
            this.profileOpens = profileOpens;
        }
    }

    private static final String[] DAILY_USAGE_COLUMNS = {
            DiaryDbHelper.COLUMN_DU_DATE,
            DiaryDbHelper.COLUMN_DU_SECONDS,
            DiaryDbHelper.COLUMN_DU_APP_OPENS,
            DiaryDbHelper.COLUMN_DU_RECORD_OPENS,
            DiaryDbHelper.COLUMN_DU_TRACK_OPENS,
            DiaryDbHelper.COLUMN_DU_DIARY_OPENS,
            DiaryDbHelper.COLUMN_DU_PROFILE_OPENS
    };

    private DailyUsage cursorToDailyUsage(Cursor cursor) {
        return new DailyUsage(
                cursor.getString(0),
                cursor.getLong(1),
                cursor.getInt(2),
                cursor.getInt(3),
                cursor.getInt(4),
                cursor.getInt(5),
                cursor.getInt(6)
        );
    }

    public List<DailyUsage> getRecentDailyUsage(int limit) {
        List<DailyUsage> result = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDb();
        int safeLimit = Math.max(0, limit);
        try (Cursor cursor = db.query(DiaryDbHelper.TABLE_DAILY_USAGE,
                DAILY_USAGE_COLUMNS,
                null, null, null, null,
                DiaryDbHelper.COLUMN_DU_DATE + " DESC",
                String.valueOf(safeLimit))) {
            while (cursor.moveToNext()) {
                result.add(cursorToDailyUsage(cursor));
            }
        }
        return result;
    }

    public List<DailyUsage> getAllDailyUsage() {
        List<DailyUsage> result = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDb();
        try (Cursor cursor = db.query(DiaryDbHelper.TABLE_DAILY_USAGE,
                DAILY_USAGE_COLUMNS,
                null, null, null, null,
                DiaryDbHelper.COLUMN_DU_DATE + " ASC")) {
            while (cursor.moveToNext()) {
                result.add(cursorToDailyUsage(cursor));
            }
        }
        return result;
    }
}
