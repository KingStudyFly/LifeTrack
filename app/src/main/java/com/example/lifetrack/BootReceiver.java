package com.example.lifetrack;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机后重新调度所有闹钟。
 *
 * AlarmManager 的闹钟存储在内存中，设备重启后全部丢失。
 * 开机完成后遍历所有 enabled 提醒，重新注册 setAlarmClock。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            Log.d(TAG, "Boot completed, rescheduling alarms");
            NotificationHelper helper = new NotificationHelper(context);
            helper.rescheduleAll();
        }
    }
}
