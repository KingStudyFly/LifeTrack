package com.example.lifetrack;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 定时提醒管理器（AlarmManager.setAlarmClock 方案）。
 *
 * 职责：
 * 1. 多条提醒的增删改查（持久化在 SharedPreferences 的 JSON 数组中）
 * 2. 通过 AlarmManager.set(RTC_WAKEUP) 调度到点通知
 *
 * 设计要点：
 * - 使用 AlarmManager.set(RTC_WAKEUP) 调度，无需 SCHEDULE_EXACT_ALARM 权限
 * - 闹钟注册在 system_server 进程，App 进程被杀不影响触发，
 *   到点时系统主动拉起 AlarmReceiver 发通知
 * - 不需要 App 持续后台运行，Receiver 发完通知立即退出
 * - enabled 字段语义为"是否已调度闹钟"
 * - 每次触发后在 AlarmReceiver 中重新调度次日（set 是一次性的）
 * - 设备重启后闹钟丢失（存储在内存），由 BootReceiver 重新调度
 * - Doze 模式下可能延迟几分钟触发，对每日提醒场景可接受
 */
public class NotificationHelper {

    private static final String TAG = "NotificationHelper";

    static final String PREFS_NAME = "lifetrack_notification";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_NEXT_ID = "next_id";

    /** AlarmReceiver Intent 的 action 与 extra key */
    static final String ACTION_ALARM = "com.example.lifetrack.ACTION_ALARM";
    static final String EXTRA_ID = "extra_id";

    /** 通知渠道 id */
    private static final String CHANNEL_ID = "lifetrack_reminder";
    private static final String CHANNEL_NAME = "定时提醒";

    /** 默认时间：21:00 */
    public static final int DEFAULT_HOUR = 21;
    public static final int DEFAULT_MINUTE = 0;

    private final Context appContext;
    private final SharedPreferences prefs;
    private final AlarmManager alarmManager;
    private final NotificationManager notificationManager;

    public NotificationHelper(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.alarmManager = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        this.notificationManager = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
        ensureChannel();
    }

    // -------------------- 通知渠道 --------------------

    /** 创建通知渠道（幂等，Android 8.0+ 必需） */
    private void ensureChannel() {
        if (notificationManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = notificationManager.getNotificationChannel(CHANNEL_ID);
            if (channel == null) {
                channel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_HIGH);
                channel.enableVibration(true);
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    // -------------------- 数据访问 --------------------

    /** 读取全部提醒（按 id 升序）。返回新 List，调用方可任意修改。 */
    public List<NotificationItem> getAll() {
        JSONArray arr = loadArray();
        List<NotificationItem> list = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                list.add(fromJson(o));
            } catch (JSONException e) {
                Log.e(TAG, "Skip malformed item at " + i, e);
            }
        }
        return list;
    }

    public NotificationItem getById(int id) {
        for (NotificationItem item : getAll()) {
            if (item.getId() == id) return item;
        }
        return null;
    }

    /**
     * 新增一条并返回新 id。
     * @param enabled 是否立即调度闹钟
     */
    public int add(int hour, int minute, String content, boolean enabled) {
        int id = nextId();
        NotificationItem item = new NotificationItem(id, hour, minute, content, enabled);
        JSONArray arr = loadArray();
        try {
            arr.put(toJson(item));
        } catch (JSONException e) {
            Log.e(TAG, "Failed to serialize new item", e);
            return -1;
        }
        saveArray(arr);
        if (enabled) {
            scheduleAlarm(item);
        }
        return id;
    }

    /**
     * 更新一条。若该提醒已启用，自动重新调度闹钟以同步新时间/内容。
     */
    public boolean update(NotificationItem item) {
        if (item == null || getById(item.getId()) == null) return false;

        JSONArray arr = loadArray();
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                if (o.getInt("id") == item.getId()) {
                    arr.put(i, toJson(item));
                    saveArray(arr);
                    // 已启用的提醒，时间/内容变化后重新调度
                    if (item.isEnabled()) {
                        scheduleAlarm(item);
                    }
                    return true;
                }
            } catch (JSONException e) {
                Log.e(TAG, "Skip update at " + i, e);
            }
        }
        return false;
    }

    /** 删除一条。同时取消对应的闹钟。 */
    public boolean remove(int id) {
        cancelAlarm(id);

        JSONArray arr = loadArray();
        JSONArray out = new JSONArray();
        boolean found = false;
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                if (o.getInt("id") == id) {
                    found = true;
                } else {
                    out.put(o);
                }
            } catch (JSONException e) {
                Log.e(TAG, "Skip remove at " + i, e);
            }
        }
        if (found) saveArray(out);
        return found;
    }

    /**
     * 切换闹钟调度状态。
     * - true：调度闹钟
     * - false：取消闹钟
     */
    public boolean setEnabled(int id, boolean enabled) {
        NotificationItem item = getById(id);
        if (item == null) return false;

        item.setEnabled(enabled);
        boolean ok = update(item); // update 内部：enabled 则 scheduleAlarm
        if (ok && !enabled) {
            cancelAlarm(id);
        }
        return ok;
    }

    // -------------------- 闹钟调度 --------------------

    /**
     * 调度闹钟：到点触发 AlarmReceiver。
     *
     * 采用 set(RTC_WAKEUP) 而非 setAlarmClock / setExactAndAllowWhileIdle：
     * - setAlarmClock 在 targetSdk 34+ 需要 SCHEDULE_EXACT_ALARM 权限，否则抛 SecurityException
     * - setExactAndAllowWhileIdle 同样需要该权限，且需用户手动去系统设置授权
     * - set(RTC_WAKEUP) 无需任何权限，Doze 下可能延迟几分钟，对每日提醒可接受
     */
    public void scheduleAlarm(NotificationItem item) {
        if (alarmManager == null) {
            Log.w(TAG, "AlarmManager not available");
            return;
        }
        ensureChannel();
        Intent intent = new Intent(appContext, AlarmReceiver.class);
        intent.setAction(ACTION_ALARM);
        intent.putExtra(EXTRA_ID, item.getId());
        PendingIntent pi = PendingIntent.getBroadcast(
                appContext, item.getId(), intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        long triggerAt = nextTriggerAt(item.getHour(), item.getMinute());
        alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
    }

    /** 取消对应 id 的闹钟。 */
    public void cancelAlarm(int id) {
        if (alarmManager == null) return;
        Intent intent = new Intent(appContext, AlarmReceiver.class);
        intent.setAction(ACTION_ALARM);
        PendingIntent pi = PendingIntent.getBroadcast(
                appContext, id, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE);
        if (pi != null) {
            alarmManager.cancel(pi);
            pi.cancel();
        }
    }

    /** 重新调度所有 enabled=true 的提醒（供 BootReceiver 使用）。 */
    public void rescheduleAll() {
        for (NotificationItem item : getAll()) {
            if (item.isEnabled()) {
                scheduleAlarm(item);
            }
        }
    }

    // -------------------- 通知发送 --------------------

    /** 发送一条通知（供 AlarmReceiver 调用）。 */
    public void showNotification(NotificationItem item) {
        ensureChannel();
        if (notificationManager == null) return;
        Intent intent = new Intent(appContext, NotificationListActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                appContext, item.getId(), intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(appContext.getString(R.string.app_name))
                .setContentText(item.getContent())
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        notificationManager.notify(item.getId(), n);
    }

    // -------------------- 内部工具 --------------------

    private int nextId() {
        int next = prefs.getInt(KEY_NEXT_ID, 1);
        prefs.edit().putInt(KEY_NEXT_ID, next + 1).apply();
        return next;
    }

    private JSONArray loadArray() {
        String raw = prefs.getString(KEY_ITEMS, "[]");
        try {
            return new JSONArray(raw);
        } catch (JSONException e) {
            Log.e(TAG, "Malformed items JSON, reset to empty", e);
            return new JSONArray();
        }
    }

    private void saveArray(JSONArray arr) {
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply();
    }

    private static NotificationItem fromJson(JSONObject o) throws JSONException {
        return new NotificationItem(
                o.getInt("id"),
                o.getInt("hour"),
                o.getInt("minute"),
                o.optString("content", ""),
                o.optBoolean("enabled", true));
    }

    private static JSONObject toJson(NotificationItem item) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", item.getId());
        o.put("hour", item.getHour());
        o.put("minute", item.getMinute());
        o.put("content", item.getContent());
        o.put("enabled", item.isEnabled());
        return o;
    }

    /** 计算下一次触发的时间戳：今天该时刻未过则今天，否则明天。 */
    static long nextTriggerAt(int hour, int minute) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long now = System.currentTimeMillis();
        if (cal.getTimeInMillis() <= now) {
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        return cal.getTimeInMillis();
    }
}
