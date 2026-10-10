package com.example.lifetrack;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 接收 AlarmManager.set(RTC_WAKEUP) 触发的闹钟。
 *
 * 工作流程：
 * 1. 从 intent 取出提醒 id
 * 2. 从 NotificationHelper 读取该提醒
 * 3. 发送通知
 * 4. 重新调度次日（set 是一次性的，需每日重新注册）
 *
 * 该 Receiver 静态注册在 AndroidManifest，由系统从冷启动拉起，App 进程无需存活。
 */
public class AlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "AlarmReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        int id = intent.getIntExtra(NotificationHelper.EXTRA_ID, -1);
        if (id < 0) {
            Log.w(TAG, "Invalid id from intent");
            return;
        }
        NotificationHelper helper = new NotificationHelper(context);
        NotificationItem item = helper.getById(id);
        if (item == null) {
            Log.w(TAG, "Item " + id + " not found, maybe deleted");
            return;
        }
        if (!item.isEnabled()) {
            Log.w(TAG, "Item " + id + " disabled, skip");
            return;
        }
        helper.showNotification(item);
        // 调度次日
        helper.scheduleAlarm(item);
    }
}
