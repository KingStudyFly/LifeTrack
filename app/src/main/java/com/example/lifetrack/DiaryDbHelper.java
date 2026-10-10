package com.example.lifetrack;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import net.zetetic.database.sqlcipher.SQLiteDatabase;
import net.zetetic.database.sqlcipher.SQLiteOpenHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DiaryDbHelper extends SQLiteOpenHelper {

    private static final String TAG = "DiaryDbHelper";
    private static final String DATABASE_NAME = "lifetrack.db";
    private static final int DATABASE_VERSION = 13;

    public static final String TABLE_TODO = "todo";
    public static final String COLUMN_TODO_ID = "_id";
    public static final String COLUMN_TODO_DATE = "date";
    public static final String COLUMN_TODO_TIME = "time";
    public static final String COLUMN_TODO_TITLE = "title";
    public static final String COLUMN_TODO_DESC = "description";
    public static final String COLUMN_TODO_COMPLETED = "completed";
    public static final String COLUMN_TODO_SOURCE = "source";
    public static final String COLUMN_TODO_CREATED_AT = "created_at";

    // 维度记录表：运动/睡眠/社交三个维度的打分与结构化提取
    public static final String TABLE_DIMENSION_RECORDS = "dimension_records";
    public static final String COLUMN_DR_ID = "_id";
    public static final String COLUMN_DR_DATE = "date";
    public static final String COLUMN_DR_DIARY_ID = "diary_id";
    public static final String COLUMN_DR_EXERCISE_SCORE = "exercise_score";
    public static final String COLUMN_DR_EXERCISE_TYPES = "exercise_types";
    public static final String COLUMN_DR_SLEEP_SCORE = "sleep_score";
    public static final String COLUMN_DR_SOCIAL_CONTACTS = "social_contacts";
    public static final String COLUMN_DR_RAW_EXCERPT = "raw_excerpt";
    public static final String COLUMN_DR_CREATED_AT = "created_at";

    public static final String TABLE_DIARY = "diary";
    public static final String COLUMN_ID = "_id";
    public static final String COLUMN_TIMESTAMP = "timestamp";
    public static final String COLUMN_CONTENT = "content";
    public static final String COLUMN_STATUS = "status";

    public static final String TABLE_TAG = "tag";
    public static final String COLUMN_TAG_ID = "_id";
    public static final String COLUMN_TAG_NAME = "name";

    public static final String TABLE_DIARY_TAG = "diary_tag";
    public static final String COLUMN_DT_DIARY_ID = "diary_id";
    public static final String COLUMN_DT_TAG_ID = "tag_id";

    public static final String TABLE_TAG_LAST_SHOWN = "tag_last_shown";
    public static final String COLUMN_TLS_TAG_ID = "tag_id";
    public static final String COLUMN_TLS_LAST_SHOWN = "last_shown_date";

    public static final String TABLE_REFLECTION = "reflection";
    public static final String COLUMN_REFLECTION_ID = "_id";
    public static final String COLUMN_REFLECTION_DIARY_ID = "diary_id";
    public static final String COLUMN_REFLECTION_CONTENT = "content";
    public static final String COLUMN_REFLECTION_CREATED_AT = "created_at";

    public static final String TABLE_SUMMARIES = "summaries";
    public static final String COLUMN_SUMMARY_ID = "_id";
    public static final String COLUMN_PERIOD_TYPE = "period_type";
    public static final String COLUMN_PERIOD_KEY = "period_key";
    public static final String COLUMN_SUMMARY_TITLE = "title";
    public static final String COLUMN_SUMMARY_CONTENT = "content";
    public static final String COLUMN_ENTRY_COUNT = "entry_count";
    public static final String COLUMN_PROMPT_VERSION = "prompt_version";
    public static final String COLUMN_CREATED_AT = "created_at";

    public static final String TABLE_SESSIONS = "sessions";
    public static final String COLUMN_SESSION_ID = "_id";
    public static final String COLUMN_START_TIME = "start_time";
    public static final String COLUMN_END_TIME = "end_time";
    public static final String COLUMN_DURATION = "duration";

    public static final String TABLE_FEATURE_USAGE = "feature_usage";
    public static final String COLUMN_FEATURE_ID = "_id";
    public static final String COLUMN_FEATURE_NAME = "feature_name";
    public static final String COLUMN_FEATURE_START = "start_time";
    public static final String COLUMN_FEATURE_END = "end_time";
    public static final String COLUMN_FEATURE_DURATION = "duration";

    public static final String TABLE_DAILY_USAGE = "daily_usage";
    public static final String COLUMN_DU_DATE = "date";
    public static final String COLUMN_DU_SECONDS = "foreground_seconds";
    public static final String COLUMN_DU_APP_OPENS = "app_opens";
    public static final String COLUMN_DU_RECORD_OPENS = "record_opens";
    public static final String COLUMN_DU_TRACK_OPENS = "track_opens";
    public static final String COLUMN_DU_DIARY_OPENS = "diary_opens";
    public static final String COLUMN_DU_PROFILE_OPENS = "profile_opens";

    // insights table
    public static final String TABLE_INSIGHTS = "insights";
    public static final String COLUMN_INSIGHT_ID = "_id";
    public static final String COLUMN_INSIGHT_PERIOD_TYPE = "period_type";
    public static final String COLUMN_INSIGHT_PERIOD_KEY = "period_key";
    public static final String COLUMN_INSIGHT_KEY_PERSONS = "key_persons";
    public static final String COLUMN_INSIGHT_KEY_PROJECTS = "key_projects";
    public static final String COLUMN_INSIGHT_KEY_EVENTS = "key_events";
    public static final String COLUMN_INSIGHT_EMOTION_THEMES = "emotion_themes";
    public static final String COLUMN_INSIGHT_DECISIONS = "decisions";
    public static final String COLUMN_INSIGHT_CREATED_AT = "created_at";

    private static final String SQL_CREATE_DIARY =
            "CREATE TABLE IF NOT EXISTS " + TABLE_DIARY + " (" +
                    COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COLUMN_TIMESTAMP + " TEXT NOT NULL, " +
                    COLUMN_CONTENT + " TEXT NOT NULL, " +
                    COLUMN_STATUS + " INTEGER NOT NULL DEFAULT 0)";

    private static final String SQL_CREATE_TAG =
            "CREATE TABLE IF NOT EXISTS " + TABLE_TAG + " (" +
                    COLUMN_TAG_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COLUMN_TAG_NAME + " TEXT UNIQUE NOT NULL)";

    private static final String SQL_CREATE_DIARY_TAG =
            "CREATE TABLE IF NOT EXISTS " + TABLE_DIARY_TAG + " (" +
                    COLUMN_DT_DIARY_ID + " INTEGER NOT NULL, " +
                    COLUMN_DT_TAG_ID + " INTEGER NOT NULL, " +
                    "PRIMARY KEY (" + COLUMN_DT_DIARY_ID + ", " + COLUMN_DT_TAG_ID + "), " +
                    "FOREIGN KEY (" + COLUMN_DT_DIARY_ID + ") REFERENCES " + TABLE_DIARY + "(" + COLUMN_ID + ") ON DELETE CASCADE, " +
                    "FOREIGN KEY (" + COLUMN_DT_TAG_ID + ") REFERENCES " + TABLE_TAG + "(" + COLUMN_TAG_ID + "))";

    private static final String SQL_CREATE_TAG_LAST_SHOWN =
            "CREATE TABLE IF NOT EXISTS " + TABLE_TAG_LAST_SHOWN + " (" +
                    COLUMN_TLS_TAG_ID + " INTEGER PRIMARY KEY, " +
                    COLUMN_TLS_LAST_SHOWN + " TEXT NOT NULL, " +
                    "FOREIGN KEY (" + COLUMN_TLS_TAG_ID + ") REFERENCES " + TABLE_TAG + "(" + COLUMN_TAG_ID + "))";

    private static final String SQL_CREATE_REFLECTION =
            "CREATE TABLE IF NOT EXISTS " + TABLE_REFLECTION + " (" +
                    COLUMN_REFLECTION_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COLUMN_REFLECTION_DIARY_ID + " INTEGER NOT NULL, " +
                    COLUMN_REFLECTION_CONTENT + " TEXT NOT NULL, " +
                    COLUMN_REFLECTION_CREATED_AT + " TEXT NOT NULL, " +
                    "FOREIGN KEY (" + COLUMN_REFLECTION_DIARY_ID + ") REFERENCES " + TABLE_DIARY + "(" + COLUMN_ID + ") ON DELETE CASCADE)";

    private static final String SQL_CREATE_INDEX_DIARY_TAG_TAG =
            "CREATE INDEX IF NOT EXISTS idx_diary_tag_tag ON " + TABLE_DIARY_TAG + "(" + COLUMN_DT_TAG_ID + ")";


    private static final String SQL_CREATE_SUMMARIES =
            "CREATE TABLE IF NOT EXISTS " + TABLE_SUMMARIES + " (" +
                    COLUMN_SUMMARY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COLUMN_PERIOD_TYPE + " TEXT NOT NULL, " +
                    COLUMN_PERIOD_KEY + " TEXT NOT NULL, " +
                    COLUMN_SUMMARY_TITLE + " TEXT NOT NULL, " +
                    COLUMN_SUMMARY_CONTENT + " TEXT NOT NULL, " +
                    COLUMN_ENTRY_COUNT + " INTEGER NOT NULL, " +
                    COLUMN_PROMPT_VERSION + " INTEGER NOT NULL DEFAULT 0, " +
                    COLUMN_CREATED_AT + " TEXT NOT NULL, " +
                    "UNIQUE(" + COLUMN_PERIOD_TYPE + ", " + COLUMN_PERIOD_KEY + "))";

    private static final String SQL_CREATE_SESSIONS =
            "CREATE TABLE IF NOT EXISTS " + TABLE_SESSIONS + " (" +
                    COLUMN_SESSION_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COLUMN_START_TIME + " TEXT NOT NULL, " +
                    COLUMN_END_TIME + " TEXT, " +
                    COLUMN_DURATION + " INTEGER)";

    private static final String SQL_CREATE_FEATURE_USAGE =
            "CREATE TABLE IF NOT EXISTS " + TABLE_FEATURE_USAGE + " (" +
                    COLUMN_FEATURE_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COLUMN_FEATURE_NAME + " TEXT NOT NULL, " +
                    COLUMN_FEATURE_START + " TEXT NOT NULL, " +
                    COLUMN_FEATURE_END + " TEXT, " +
                    COLUMN_FEATURE_DURATION + " INTEGER)";

    private static final String SQL_CREATE_DAILY_USAGE =
            "CREATE TABLE IF NOT EXISTS " + TABLE_DAILY_USAGE + " (" +
                    COLUMN_DU_DATE + " TEXT PRIMARY KEY, " +
                    COLUMN_DU_SECONDS + " INTEGER NOT NULL DEFAULT 0, " +
                    COLUMN_DU_APP_OPENS + " INTEGER NOT NULL DEFAULT 0, " +
                    COLUMN_DU_RECORD_OPENS + " INTEGER NOT NULL DEFAULT 0, " +
                    COLUMN_DU_TRACK_OPENS + " INTEGER NOT NULL DEFAULT 0, " +
                    COLUMN_DU_DIARY_OPENS + " INTEGER NOT NULL DEFAULT 0, " +
                    COLUMN_DU_PROFILE_OPENS + " INTEGER NOT NULL DEFAULT 0)";

    private static final String SQL_CREATE_INSIGHTS =
            "CREATE TABLE IF NOT EXISTS " + TABLE_INSIGHTS + " (" +
                    COLUMN_INSIGHT_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COLUMN_INSIGHT_PERIOD_TYPE + " TEXT NOT NULL, " +
                    COLUMN_INSIGHT_PERIOD_KEY + " TEXT NOT NULL, " +
                    COLUMN_INSIGHT_KEY_PERSONS + " TEXT NOT NULL DEFAULT '', " +
                    COLUMN_INSIGHT_KEY_PROJECTS + " TEXT NOT NULL DEFAULT '', " +
                    COLUMN_INSIGHT_KEY_EVENTS + " TEXT NOT NULL DEFAULT '', " +
                    COLUMN_INSIGHT_EMOTION_THEMES + " TEXT NOT NULL DEFAULT '', " +
                    COLUMN_INSIGHT_DECISIONS + " TEXT NOT NULL DEFAULT '', " +
                    COLUMN_INSIGHT_CREATED_AT + " TEXT NOT NULL, " +
                    "UNIQUE(" + COLUMN_INSIGHT_PERIOD_TYPE + ", " + COLUMN_INSIGHT_PERIOD_KEY + "))";

    private static final String SQL_CREATE_INDEX_DIARY_TIMESTAMP =
            "CREATE INDEX IF NOT EXISTS idx_diary_timestamp ON " + TABLE_DIARY + "(" + COLUMN_TIMESTAMP + ")";
    private static final String SQL_CREATE_INDEX_INSIGHTS_CREATED =
            "CREATE INDEX IF NOT EXISTS idx_insights_created ON " + TABLE_INSIGHTS + "(" + COLUMN_INSIGHT_CREATED_AT + ")";

    // 维度记录表：每篇日记的运动/睡眠/社交维度打分（0-100），diary_id 可空（读取过往日记回填时尚无 diary_id）
    private static final String SQL_CREATE_DIMENSION_RECORDS =
            "CREATE TABLE IF NOT EXISTS " + TABLE_DIMENSION_RECORDS + " (" +
                    COLUMN_DR_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COLUMN_DR_DATE + " TEXT NOT NULL, " +
                    COLUMN_DR_DIARY_ID + " INTEGER, " +
                    COLUMN_DR_EXERCISE_SCORE + " INTEGER, " +
                    COLUMN_DR_EXERCISE_TYPES + " TEXT, " +
                    COLUMN_DR_SLEEP_SCORE + " INTEGER, " +
                    COLUMN_DR_SOCIAL_CONTACTS + " TEXT, " +
                    COLUMN_DR_RAW_EXCERPT + " TEXT, " +
                    COLUMN_DR_CREATED_AT + " TEXT NOT NULL, " +
                    "FOREIGN KEY (" + COLUMN_DR_DIARY_ID + ") REFERENCES " + TABLE_DIARY + "(" + COLUMN_ID + ") ON DELETE CASCADE)";
    private static final String SQL_CREATE_INDEX_DR_DATE =
            "CREATE INDEX IF NOT EXISTS idx_dimension_records_date ON " + TABLE_DIMENSION_RECORDS + "(" + COLUMN_DR_DATE + ")";

    private static final String SQL_CREATE_TODO =
            "CREATE TABLE IF NOT EXISTS " + TABLE_TODO + " (" +
                    COLUMN_TODO_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COLUMN_TODO_DATE + " TEXT NOT NULL, " +
                    COLUMN_TODO_TIME + " TEXT NOT NULL DEFAULT '', " +
                    COLUMN_TODO_TITLE + " TEXT NOT NULL, " +
                    COLUMN_TODO_DESC + " TEXT NOT NULL DEFAULT '', " +
                    COLUMN_TODO_COMPLETED + " INTEGER NOT NULL DEFAULT 0, " +
                    COLUMN_TODO_SOURCE + " TEXT NOT NULL DEFAULT 'manual', " +
                    COLUMN_TODO_CREATED_AT + " TEXT NOT NULL)";
    private static final String SQL_CREATE_INDEX_TODO_DATE =
            "CREATE INDEX IF NOT EXISTS idx_todo_date ON " + TABLE_TODO + "(" + COLUMN_TODO_DATE + ")";

    private static DiaryDbHelper instance;
    private final Context appContext;

    public static synchronized DiaryDbHelper getInstance(Context context) {
        if (instance == null) {
            Context appContext = context.getApplicationContext();
            migrateIfNeeded(appContext);
            CryptoHelper.getDatabaseKeyAsString(appContext);
            if (CryptoHelper.consumeKeyRegeneratedFlag()) {
                Log.w(TAG, "DB key was regenerated, deleting old encrypted database file");
                File dbFile = appContext.getDatabasePath(DATABASE_NAME);
                if (dbFile.exists()) {
                    boolean deleted = dbFile.delete();
                    Log.w(TAG, "Old DB file deleted: " + deleted);
                }
                new File(dbFile.getAbsolutePath() + "-wal").delete();
                new File(dbFile.getAbsolutePath() + "-shm").delete();
                CryptoHelper.setDatabaseEncrypted(appContext, true);
            }
            instance = new DiaryDbHelper(appContext);
        }
        return instance;
    }

    private DiaryDbHelper(Context context) {
        super(context, DATABASE_NAME, CryptoHelper.getDatabaseKeyAsString(context), null, DATABASE_VERSION, 0, null, null, false);
        this.appContext = context;
        System.loadLibrary("sqlcipher");
    }

    public SQLiteDatabase getWritableDb() {
        return getWritableDatabase();
    }

    public SQLiteDatabase getReadableDb() {
        return getReadableDatabase();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_DIARY);
        db.execSQL(SQL_CREATE_SUMMARIES);
        db.execSQL(SQL_CREATE_SESSIONS);
        db.execSQL(SQL_CREATE_FEATURE_USAGE);
        db.execSQL(SQL_CREATE_DAILY_USAGE);
        db.execSQL(SQL_CREATE_INSIGHTS);
        db.execSQL(SQL_CREATE_TAG);
        db.execSQL(SQL_CREATE_DIARY_TAG);
        db.execSQL(SQL_CREATE_TAG_LAST_SHOWN);
        db.execSQL(SQL_CREATE_REFLECTION);
        db.execSQL(SQL_CREATE_INDEX_DIARY_TIMESTAMP);
        db.execSQL(SQL_CREATE_INDEX_INSIGHTS_CREATED);
        db.execSQL(SQL_CREATE_INDEX_DIARY_TAG_TAG);
        db.execSQL(SQL_CREATE_DIMENSION_RECORDS);
        db.execSQL(SQL_CREATE_INDEX_DR_DATE);
        db.execSQL(SQL_CREATE_TODO);
        db.execSQL(SQL_CREATE_INDEX_TODO_DATE);
        initDefaultTags(db);
    }

    /**
     * 20 个预定义标签，约束 AI 选择范围，避免漂移。
     * 后续增删标签在此处维护，AI prompt 中标签列表从 DB 动态读取。
     */
    public static final String[] DEFAULT_TAGS = {
            "工作", "学习", "情感", "健康", "家庭", "朋友", "旅行", "阅读",
            "运动", "美食", "思考", "创作", "休息", "财务", "其他",
            "梦境", "欲望", "灵感", "焦虑", "目标"
    };

    private static void initDefaultTags(SQLiteDatabase db) {
        db.beginTransaction();
        try {
            for (String tag : DEFAULT_TAGS) {
                ContentValues values = new ContentValues();
                values.put(COLUMN_TAG_NAME, tag);
                // INSERT OR IGNORE，避免重复插入
                db.insertWithOnConflict(TABLE_TAG, null, values,
                        SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.beginTransaction();
        try {
            if (oldVersion < 2) {
                db.execSQL(SQL_CREATE_SUMMARIES);
            }
            if (oldVersion < 3) {
                db.execSQL(SQL_CREATE_SESSIONS);
            }
            if (oldVersion < 4) {
                db.execSQL(SQL_CREATE_FEATURE_USAGE);
            }
            if (oldVersion < 5) {
                db.execSQL(SQL_CREATE_DAILY_USAGE);
            }
            if (oldVersion < 6) {
                String[] alterSqls = {
                    "ALTER TABLE " + TABLE_DAILY_USAGE + " ADD COLUMN " + COLUMN_DU_APP_OPENS + " INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE " + TABLE_DAILY_USAGE + " ADD COLUMN " + COLUMN_DU_RECORD_OPENS + " INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE " + TABLE_DAILY_USAGE + " ADD COLUMN " + COLUMN_DU_TRACK_OPENS + " INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE " + TABLE_DAILY_USAGE + " ADD COLUMN " + COLUMN_DU_DIARY_OPENS + " INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE " + TABLE_DAILY_USAGE + " ADD COLUMN " + COLUMN_DU_PROFILE_OPENS + " INTEGER NOT NULL DEFAULT 0",
                };
                for (String sql : alterSqls) {
                    try {
                        db.execSQL(sql);
                    } catch (Exception e) {
                        Log.w(TAG, "Migration v6 skipped (column may exist): " + sql, e);
                    }
                }
            }
            if (oldVersion < 7) {
                db.execSQL(SQL_CREATE_INSIGHTS);
            }
            if (oldVersion < 8) {
                // 与 v6 风格统一：用循环数组 + try-catch，便于后续扩展
                String[] alterSqlsV8 = {
                    "ALTER TABLE " + TABLE_SUMMARIES + " ADD COLUMN "
                            + COLUMN_PROMPT_VERSION + " INTEGER NOT NULL DEFAULT 0",
                };
                for (String sql : alterSqlsV8) {
                    try {
                        db.execSQL(sql);
                    } catch (Exception e) {
                        Log.w(TAG, "Migration v8 skipped (column may exist): " + sql, e);
                    }
                }
            }
            if (oldVersion < 9) {
                db.execSQL(SQL_CREATE_INDEX_DIARY_TIMESTAMP);
                db.execSQL(SQL_CREATE_INDEX_INSIGHTS_CREATED);
            }
            if (oldVersion < 10) {
                // 五维趋势量化指标功能已移除，删除遗留表和索引
                db.execSQL("DROP INDEX IF EXISTS idx_trend_created");
                db.execSQL("DROP TABLE IF EXISTS trend_metrics");
            }
            if (oldVersion < 11) {
                // 标签系统 + 回顾感想功能
                db.execSQL(SQL_CREATE_TAG);
                db.execSQL(SQL_CREATE_DIARY_TAG);
                db.execSQL(SQL_CREATE_TAG_LAST_SHOWN);
                db.execSQL(SQL_CREATE_REFLECTION);
                db.execSQL(SQL_CREATE_INDEX_DIARY_TAG_TAG);
                // diary 表加 status 字段（0=未打标签, 1=已打标签）
                try {
                    db.execSQL("ALTER TABLE " + TABLE_DIARY + " ADD COLUMN " + COLUMN_STATUS + " INTEGER NOT NULL DEFAULT 0");
                } catch (Exception e) {
                    Log.w(TAG, "Migration v11 skipped (column may exist): ALTER TABLE diary ADD status", e);
                }
                initDefaultTags(db);
            }
            if (oldVersion < 12) {
                // 维度记录表：运动/睡眠/社交三维度打分
                db.execSQL(SQL_CREATE_DIMENSION_RECORDS);
                db.execSQL(SQL_CREATE_INDEX_DR_DATE);
            }
            if (oldVersion < 13) {
                db.execSQL(SQL_CREATE_TODO);
                db.execSQL(SQL_CREATE_INDEX_TODO_DATE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.w(TAG, "onDowngrade: from " + oldVersion + " to " + newVersion + ", keeping existing data");
    }

    private static void migrateIfNeeded(Context context) {
        if (CryptoHelper.isDatabaseEncrypted(context)) {
            return;
        }

        File dbFile = context.getDatabasePath(DATABASE_NAME);
        File backupFile = new File(dbFile.getAbsolutePath() + ".bak");
        File migratingFile = new File(dbFile.getPath() + ".migrating");

        // Bug 2.2: 检测上次迁移是否被中断，尝试恢复备份
        if (migratingFile.exists()) {
            Log.w(TAG, "Previous migration was interrupted, attempting to recover backup");
            if (backupFile.exists() && (!dbFile.exists() || dbFile.length() == 0)) {
                if (dbFile.exists()) {
                    dbFile.delete();
                }
                backupFile.renameTo(dbFile);
            }
            migratingFile.delete();
        }

        if (!dbFile.exists()) {
            CryptoHelper.setDatabaseEncrypted(context, true);
            return;
        }

        Log.i(TAG, "Starting database encryption migration...");

        // Bug 2.2: 写入迁移标记文件，防止非原子操作中断导致数据丢失
        try {
            if (!migratingFile.exists() && !migratingFile.createNewFile()) {
                Log.e(TAG, "Failed to create migrating marker file");
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create migrating marker file", e);
            return;
        }

        List<ContentValues> diaryRows = new ArrayList<>();
        List<ContentValues> summaryRows = new ArrayList<>();
        List<ContentValues> sessionRows = new ArrayList<>();
        List<ContentValues> featureRows = new ArrayList<>();
        List<ContentValues> dailyUsageRows = new ArrayList<>();
        List<ContentValues> insightsRows = new ArrayList<>();

        android.database.sqlite.SQLiteDatabase oldDb = null;
        try {
            oldDb = android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbFile.getAbsolutePath(), null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY);

            try {
                diaryRows = readTable(oldDb, TABLE_DIARY,
                        new String[]{COLUMN_TIMESTAMP, COLUMN_CONTENT});
            } catch (Exception e) {
                Log.w(TAG, "Could not read table " + TABLE_DIARY, e);
            }
            try {
                // 包含 COLUMN_PROMPT_VERSION，避免迁移后版本号丢失导致全部总结被判定过期重新生成
                // 对 v7 及更早旧库该列不存在，filterExistingColumns 会过滤掉，行为与原代码一致
                String[] summaryCols = filterExistingColumns(oldDb, TABLE_SUMMARIES,
                        new String[]{COLUMN_PERIOD_TYPE, COLUMN_PERIOD_KEY,
                                COLUMN_SUMMARY_TITLE, COLUMN_SUMMARY_CONTENT,
                                COLUMN_ENTRY_COUNT, COLUMN_PROMPT_VERSION, COLUMN_CREATED_AT});
                summaryRows = readTable(oldDb, TABLE_SUMMARIES, summaryCols);
            } catch (Exception e) {
                Log.w(TAG, "Could not read table " + TABLE_SUMMARIES, e);
            }
            try {
                sessionRows = readTable(oldDb, TABLE_SESSIONS,
                        new String[]{COLUMN_START_TIME, COLUMN_END_TIME, COLUMN_DURATION});
            } catch (Exception e) {
                Log.w(TAG, "Could not read table " + TABLE_SESSIONS, e);
            }
            try {
                featureRows = readTable(oldDb, TABLE_FEATURE_USAGE,
                        new String[]{COLUMN_FEATURE_NAME, COLUMN_FEATURE_START,
                                COLUMN_FEATURE_END, COLUMN_FEATURE_DURATION});
            } catch (Exception e) {
                Log.w(TAG, "Could not read table " + TABLE_FEATURE_USAGE, e);
            }
            try {
                // 用 filterExistingColumns 兼容 v5 旧库（只有 date/foreground_seconds 两列），
                // 避免直接读 7 列时抛 "no such column" 导致整表迁移失败丢失统计数据
                String[] duCols = filterExistingColumns(oldDb, TABLE_DAILY_USAGE,
                        new String[]{COLUMN_DU_DATE, COLUMN_DU_SECONDS, COLUMN_DU_APP_OPENS,
                                COLUMN_DU_RECORD_OPENS, COLUMN_DU_TRACK_OPENS,
                                COLUMN_DU_DIARY_OPENS, COLUMN_DU_PROFILE_OPENS});
                dailyUsageRows = readTable(oldDb, TABLE_DAILY_USAGE, duCols);
            } catch (Exception e) {
                Log.w(TAG, "Could not read table " + TABLE_DAILY_USAGE, e);
            }
            try {
                insightsRows = readTable(oldDb, TABLE_INSIGHTS,
                        new String[]{COLUMN_INSIGHT_PERIOD_TYPE, COLUMN_INSIGHT_PERIOD_KEY,
                                COLUMN_INSIGHT_KEY_PERSONS, COLUMN_INSIGHT_KEY_PROJECTS,
                                COLUMN_INSIGHT_KEY_EVENTS, COLUMN_INSIGHT_EMOTION_THEMES,
                                COLUMN_INSIGHT_DECISIONS, COLUMN_INSIGHT_CREATED_AT});
            } catch (Exception e) {
                Log.w(TAG, "Could not read table " + TABLE_INSIGHTS, e);
            }

            oldDb.close();
            oldDb = null;
        } catch (Exception e) {
            Log.e(TAG, "Failed to read unencrypted database", e);
            if (oldDb != null) {
                try { oldDb.close(); } catch (Exception ex) {
                    Log.w(TAG, "Failed to close old unencrypted database", ex);
                }
            }
            migratingFile.delete();
            return;
        }

        // Bug 2.1: renameTo 失败时直接中止迁移，避免 backupFile 指向 dbFile 导致误删
        boolean renamed = dbFile.renameTo(backupFile);
        if (!renamed) {
            Log.e(TAG, "Failed to rename old database, aborting migration");
            migratingFile.delete();
            return;
        }
        new File(dbFile.getAbsolutePath() + "-wal").delete();
        new File(dbFile.getAbsolutePath() + "-shm").delete();

        System.loadLibrary("sqlcipher");

        DiaryDbHelper tempHelper = null;
        boolean migrationSuccess = false;
        try {
            tempHelper = new DiaryDbHelper(context);
            SQLiteDatabase newDb = tempHelper.getWritableDatabase();
            insertRows(newDb, TABLE_DIARY, diaryRows);
            insertRows(newDb, TABLE_SUMMARIES, summaryRows);
            insertRows(newDb, TABLE_SESSIONS, sessionRows);
            insertRows(newDb, TABLE_FEATURE_USAGE, featureRows);
            insertRows(newDb, TABLE_DAILY_USAGE, dailyUsageRows);
            insertRows(newDb, TABLE_INSIGHTS, insightsRows);
            newDb.close();
            migrationSuccess = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to write encrypted database", e);
        } finally {
            if (tempHelper != null) {
                try { tempHelper.close(); } catch (Exception e) {
                    Log.w(TAG, "Failed to close temp helper", e);
                }
            }
        }

        if (migrationSuccess) {
            // Bug 2.1: 仅当 rename 成功时才删除备份，防止误删新建的加密库
            if (renamed && backupFile.exists()) {
                backupFile.delete();
            }
            CryptoHelper.setDatabaseEncrypted(context, true);
            // Bug 2.5: 强制下次 getInstance 重建 instance，使用新的加密数据库连接
            instance = null;
            Log.i(TAG, "Migration complete. Diary: " + diaryRows.size() +
                    ", Summaries: " + summaryRows.size() +
                    ", Sessions: " + sessionRows.size() +
                    ", Features: " + featureRows.size() +
                    ", DailyUsage: " + dailyUsageRows.size() +
                    ", Insights: " + insightsRows.size());
        } else {
            // Bug 2.6: 无条件恢复备份——先删除可能存在的不完整 dbFile，再 rename 回来
            if (dbFile.exists()) {
                dbFile.delete();
            }
            if (backupFile.exists()) {
                backupFile.renameTo(dbFile);
            }
            Log.e(TAG, "Migration failed, old database preserved");
        }

        // Bug 2.2: 迁移完成（无论成功失败）后删除标记
        migratingFile.delete();
    }

    private static List<ContentValues> readTable(android.database.sqlite.SQLiteDatabase db,
                                                  String table, String[] columns) {
        List<ContentValues> rows = new ArrayList<>();
        Cursor cursor = db.query(table, columns, null, null, null, null, null);
        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    ContentValues values = new ContentValues();
                    for (String column : columns) {
                        int colIndex = cursor.getColumnIndex(column);
                        if (colIndex < 0) continue;
                        if (cursor.isNull(colIndex)) {
                            values.putNull(column);
                        } else {
                            switch (cursor.getType(colIndex)) {
                                case Cursor.FIELD_TYPE_INTEGER:
                                    values.put(column, cursor.getLong(colIndex));
                                    break;
                                case Cursor.FIELD_TYPE_FLOAT:
                                    values.put(column, cursor.getDouble(colIndex));
                                    break;
                                case Cursor.FIELD_TYPE_BLOB:
                                    values.put(column, cursor.getBlob(colIndex));
                                    break;
                                default:
                                    values.put(column, cursor.getString(colIndex));
                                    break;
                            }
                        }
                    }
                    rows.add(values);
                }
            } finally {
                cursor.close();
            }
        }
        return rows;
    }

    /**
     * 探测旧库实际存在的列，过滤掉不存在的列。
     * 用于迁移时兼容低版本旧库（如 v5 的 daily_usage 只有 2 列，v7 的 summaries 没有 prompt_version），
     * 避免直接查询不存在的列导致整表迁移失败。
     */
    private static String[] filterExistingColumns(android.database.sqlite.SQLiteDatabase db,
                                                    String table, String[] desiredColumns) {
        java.util.Set<String> existing = new java.util.HashSet<>();
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            int nameIndex = c.getColumnIndex("name");
            while (c.moveToNext()) {
                existing.add(c.getString(nameIndex));
            }
        } catch (Exception e) {
            Log.w(TAG, "PRAGMA table_info failed for " + table + ", fallback to all desired columns", e);
            return desiredColumns;
        }
        java.util.List<String> result = new java.util.ArrayList<>();
        for (String col : desiredColumns) {
            if (existing.contains(col)) {
                result.add(col);
            } else {
                Log.d(TAG, "filterExistingColumns: column " + col + " not in " + table + ", skipped");
            }
        }
        return result.toArray(new String[0]);
    }

    private static void insertRows(SQLiteDatabase db, String table, List<ContentValues> rows) {
        db.beginTransaction();
        try {
            for (ContentValues values : rows) {
                db.insert(table, null, values);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
}
