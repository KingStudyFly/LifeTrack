package com.example.lifetrack;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

public class InsightDao {

    private static final String TAG = "InsightDao";
    private final DiaryDbHelper dbHelper;

    public InsightDao(Context context) {
        dbHelper = DiaryDbHelper.getInstance(context);
    }

    public static class InsightData {
        public final String keyPersons;
        public final String keyProjects;
        public final String keyEvents;
        public final String emotionThemes;
        public final String decisions;

        public InsightData(String keyPersons, String keyProjects, String keyEvents,
                           String emotionThemes, String decisions) {
            this.keyPersons = keyPersons;
            this.keyProjects = keyProjects;
            this.keyEvents = keyEvents;
            this.emotionThemes = emotionThemes;
            this.decisions = decisions;
        }
    }

    public long saveInsight(String periodType, String periodKey, String keyPersons,
                            String keyProjects, String keyEvents, String emotionThemes,
                            String decisions) {
        SQLiteDatabase db = dbHelper.getWritableDb();
        ContentValues values = new ContentValues();
        values.put(DiaryDbHelper.COLUMN_INSIGHT_PERIOD_TYPE, periodType);
        values.put(DiaryDbHelper.COLUMN_INSIGHT_PERIOD_KEY, periodKey);
        values.put(DiaryDbHelper.COLUMN_INSIGHT_KEY_PERSONS, keyPersons != null ? keyPersons : "");
        values.put(DiaryDbHelper.COLUMN_INSIGHT_KEY_PROJECTS, keyProjects != null ? keyProjects : "");
        values.put(DiaryDbHelper.COLUMN_INSIGHT_KEY_EVENTS, keyEvents != null ? keyEvents : "");
        values.put(DiaryDbHelper.COLUMN_INSIGHT_EMOTION_THEMES, emotionThemes != null ? emotionThemes : "");
        values.put(DiaryDbHelper.COLUMN_INSIGHT_DECISIONS, decisions != null ? decisions : "");
        values.put(DiaryDbHelper.COLUMN_INSIGHT_CREATED_AT, DateUtils.currentDateTime());

        long result = db.insertWithOnConflict(
                DiaryDbHelper.TABLE_INSIGHTS, null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
        Log.d(TAG, "saveInsight: type=" + periodType + ", key=" + periodKey + ", result=" + result);
        return result;
    }

    public InsightData getInsight(String periodType, String periodKey) {
        SQLiteDatabase db = dbHelper.getReadableDb();
        String sql = "SELECT " + DiaryDbHelper.COLUMN_INSIGHT_KEY_PERSONS + ", "
                + DiaryDbHelper.COLUMN_INSIGHT_KEY_PROJECTS + ", "
                + DiaryDbHelper.COLUMN_INSIGHT_KEY_EVENTS + ", "
                + DiaryDbHelper.COLUMN_INSIGHT_EMOTION_THEMES + ", "
                + DiaryDbHelper.COLUMN_INSIGHT_DECISIONS
                + " FROM " + DiaryDbHelper.TABLE_INSIGHTS
                + " WHERE " + DiaryDbHelper.COLUMN_INSIGHT_PERIOD_TYPE + " = ? AND "
                + DiaryDbHelper.COLUMN_INSIGHT_PERIOD_KEY + " = ?";

        InsightData result = null;
        try (Cursor cursor = db.rawQuery(sql, new String[]{periodType, periodKey})) {
            if (cursor.moveToFirst()) {
                result = new InsightData(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getString(3),
                        cursor.getString(4)
                );
            }
        }
        return result;
    }

    public List<InsightData> getRecentInsights(String periodType, int limit) {
        SQLiteDatabase db = dbHelper.getReadableDb();
        int safeLimit = Math.max(0, limit);
        String sql = "SELECT " + DiaryDbHelper.COLUMN_INSIGHT_KEY_PERSONS + ", "
                + DiaryDbHelper.COLUMN_INSIGHT_KEY_PROJECTS + ", "
                + DiaryDbHelper.COLUMN_INSIGHT_KEY_EVENTS + ", "
                + DiaryDbHelper.COLUMN_INSIGHT_EMOTION_THEMES + ", "
                + DiaryDbHelper.COLUMN_INSIGHT_DECISIONS
                + " FROM " + DiaryDbHelper.TABLE_INSIGHTS
                + " WHERE " + DiaryDbHelper.COLUMN_INSIGHT_PERIOD_TYPE + " = ?"
                + " ORDER BY " + DiaryDbHelper.COLUMN_INSIGHT_CREATED_AT + " DESC"
                + " LIMIT ?";

        List<InsightData> results = new ArrayList<>();
        try (Cursor cursor = db.rawQuery(sql, new String[]{periodType, String.valueOf(safeLimit)})) {
            while (cursor.moveToNext()) {
                results.add(new InsightData(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getString(3),
                        cursor.getString(4)
                ));
            }
        }
        Log.d(TAG, "getRecentInsights: type=" + periodType + ", limit=" + limit + ", found=" + results.size());
        return results;
    }

    /**
     * 按时间范围查询洞察数据。
     * daily/monthly 类型用 period_key 精确过滤；
     * weekly/quarterly/yearly 类型用 created_at 日期部分近似过滤。
     */
    public List<InsightData> getInsightsByDateRange(String periodType, String startDate, String endDate, int limit) {
        SQLiteDatabase db = dbHelper.getReadableDb();
        int safeLimit = Math.max(0, limit);

        String columns = DiaryDbHelper.COLUMN_INSIGHT_KEY_PERSONS + ", "
                + DiaryDbHelper.COLUMN_INSIGHT_KEY_PROJECTS + ", "
                + DiaryDbHelper.COLUMN_INSIGHT_KEY_EVENTS + ", "
                + DiaryDbHelper.COLUMN_INSIGHT_EMOTION_THEMES + ", "
                + DiaryDbHelper.COLUMN_INSIGHT_DECISIONS;

        String sql;
        String[] args;

        switch (periodType) {
            case "daily":
                sql = "SELECT " + columns
                        + " FROM " + DiaryDbHelper.TABLE_INSIGHTS
                        + " WHERE " + DiaryDbHelper.COLUMN_INSIGHT_PERIOD_TYPE + " = ?"
                        + " AND " + DiaryDbHelper.COLUMN_INSIGHT_PERIOD_KEY + " >= ?"
                        + " AND " + DiaryDbHelper.COLUMN_INSIGHT_PERIOD_KEY + " <= ?"
                        + " ORDER BY " + DiaryDbHelper.COLUMN_INSIGHT_PERIOD_KEY + " DESC"
                        + " LIMIT ?";
                args = new String[]{periodType, startDate, endDate, String.valueOf(safeLimit)};
                break;
            case "monthly":
                String startMonth = startDate.length() >= 7 ? startDate.substring(0, 7) : startDate;
                String endMonth = endDate.length() >= 7 ? endDate.substring(0, 7) : endDate;
                sql = "SELECT " + columns
                        + " FROM " + DiaryDbHelper.TABLE_INSIGHTS
                        + " WHERE " + DiaryDbHelper.COLUMN_INSIGHT_PERIOD_TYPE + " = ?"
                        + " AND " + DiaryDbHelper.COLUMN_INSIGHT_PERIOD_KEY + " >= ?"
                        + " AND " + DiaryDbHelper.COLUMN_INSIGHT_PERIOD_KEY + " <= ?"
                        + " ORDER BY " + DiaryDbHelper.COLUMN_INSIGHT_PERIOD_KEY + " DESC"
                        + " LIMIT ?";
                args = new String[]{periodType, startMonth, endMonth, String.valueOf(safeLimit)};
                break;
            default:
                // weekly/quarterly/yearly: period_key 格式不统一，用 created_at 近似过滤
                sql = "SELECT " + columns
                        + " FROM " + DiaryDbHelper.TABLE_INSIGHTS
                        + " WHERE " + DiaryDbHelper.COLUMN_INSIGHT_PERIOD_TYPE + " = ?"
                        + " AND substr(" + DiaryDbHelper.COLUMN_INSIGHT_CREATED_AT + ", 1, 10) >= ?"
                        + " AND substr(" + DiaryDbHelper.COLUMN_INSIGHT_CREATED_AT + ", 1, 10) <= ?"
                        + " ORDER BY " + DiaryDbHelper.COLUMN_INSIGHT_CREATED_AT + " DESC"
                        + " LIMIT ?";
                args = new String[]{periodType, startDate, endDate, String.valueOf(safeLimit)};
                break;
        }

        List<InsightData> results = new ArrayList<>();
        try (Cursor cursor = db.rawQuery(sql, args)) {
            while (cursor.moveToNext()) {
                results.add(new InsightData(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getString(3),
                        cursor.getString(4)
                ));
            }
        }
        Log.d(TAG, "getInsightsByDateRange: type=" + periodType + ", range=" + startDate + "~" + endDate + ", found=" + results.size());
        return results;
    }

    public String buildInsightContext(String periodType, int limit) {
        List<InsightData> insights = getRecentInsights(periodType, limit);
        if (insights.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("以下是之前同类型周期的洞察摘要，供你参考认知的递进：\n");
        sb.append("请在分析中明确对比本期与历史洞察的变化方向（增强/减弱/新增/消失），不要简单复述历史数据。\n");
        for (int i = 0; i < insights.size(); i++) {
            InsightData d = insights.get(i);
            sb.append("历史洞察").append(i + 1).append("：");
            if (!d.keyPersons.isEmpty()) sb.append("关键人物=").append(d.keyPersons).append("；");
            if (!d.keyProjects.isEmpty()) sb.append("关键项目=").append(d.keyProjects).append("；");
            if (!d.keyEvents.isEmpty()) sb.append("关键事件=").append(d.keyEvents).append("；");
            if (!d.emotionThemes.isEmpty()) sb.append("情绪主题=").append(d.emotionThemes).append("；");
            if (!d.decisions.isEmpty()) sb.append("决策节点=").append(d.decisions).append("；");
            sb.append("\n");
        }
        return sb.toString().trim();
    }
}
