package com.example.lifetrack;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DateUtils {

    private static final String PATTERN_DATE = "yyyy-MM-dd";
    private static final String PATTERN_TIME = "HH:mm:ss";
    private static final String PATTERN_DATETIME = "yyyy-MM-dd HH:mm:ss";
    private static final String PATTERN_FILE_DATE = "yyyyMMdd_HHmmss";

    private static final ThreadLocal<SimpleDateFormat> DATE_FORMATTER =
            ThreadLocal.withInitial(() -> new SimpleDateFormat(PATTERN_DATE, Locale.US));
    private static final ThreadLocal<SimpleDateFormat> TIME_FORMATTER =
            ThreadLocal.withInitial(() -> new SimpleDateFormat(PATTERN_TIME, Locale.US));
    private static final ThreadLocal<SimpleDateFormat> DATETIME_FORMATTER =
            ThreadLocal.withInitial(() -> new SimpleDateFormat(PATTERN_DATETIME, Locale.US));
    private static final ThreadLocal<SimpleDateFormat> FILE_DATE_FORMATTER =
            ThreadLocal.withInitial(() -> new SimpleDateFormat(PATTERN_FILE_DATE, Locale.US));

    public static String formatDate(Date date) {
        return date == null ? "" : DATE_FORMATTER.get().format(date);
    }

    public static String formatTime(Date date) {
        return date == null ? "" : TIME_FORMATTER.get().format(date);
    }

    public static String formatDateTime(Date date) {
        return date == null ? "" : DATETIME_FORMATTER.get().format(date);
    }

    public static String formatFileDate(Date date) {
        return date == null ? "" : FILE_DATE_FORMATTER.get().format(date);
    }

    public static String currentDate() {
        return formatDate(new Date());
    }

    public static String currentTime() {
        return formatTime(new Date());
    }

    public static String currentDateTime() {
        return formatDateTime(new Date());
    }

    public static String currentFileDate() {
        return formatFileDate(new Date());
    }
}
