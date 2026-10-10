package com.example.lifetrack;

/**
 * 单条定时提醒的数据模型。
 *
 * id 同时承担三个职责：
 * 1. 数据库主键（实际存于 SharedPreferences JSON 数组）
 * 2. AlarmManager PendingIntent 的 requestCode，确保每条提醒独立调度
 * 3. NotificationManager 的通知 id，避免多条同时触发时互相覆盖
 *
 * 因此 id 必须在全局范围内唯一，由 NotificationHelper 通过自增计数器生成。
 *
 * enabled 语义：是否已调度闹钟。
 * - true：AlarmManager 中存在对应 setAlarmClock，到点会触发通知
 * - false：本地保留配置，但闹钟已取消，不会触发
 */
public class NotificationItem {

    private int id;
    private int hour;
    private int minute;
    private String content;
    private boolean enabled;

    public NotificationItem() {
    }

    public NotificationItem(int id, int hour, int minute, String content, boolean enabled) {
        this.id = id;
        this.hour = hour;
        this.minute = minute;
        this.content = content;
        this.enabled = enabled;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getHour() {
        return hour;
    }

    public void setHour(int hour) {
        this.hour = hour;
    }

    public int getMinute() {
        return minute;
    }

    public void setMinute(int minute) {
        this.minute = minute;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** HH:mm 格式时间字符串，用于列表展示。 */
    public String getTimeText() {
        return String.format(java.util.Locale.US, "%02d:%02d", hour, minute);
    }
}
