package com.example.lifetrack;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class EncryptedPrefsHelper {

    private static final String TAG = "EncryptedPrefsHelper";
    private static final String PREFS_CRYPTO = "lifetrack_crypto";
    private static final String KEY_MIGRATION_PREFIX = "prefs_migrated_";

    // 缓存已创建的 SharedPreferences 实例，避免重复创建 MasterKey 和 EncryptedSharedPreferences
    private static final ConcurrentHashMap<String, SharedPreferences> cache = new ConcurrentHashMap<>();

    public static SharedPreferences create(Context context, String fileName) {
        Context appContext = context.getApplicationContext();
        SharedPreferences cached = cache.get(fileName);
        if (cached != null) {
            return cached;
        }
        try {
            migrateIfNeeded(appContext, fileName);

            MasterKey masterKey = new MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            SharedPreferences prefs = EncryptedSharedPreferences.create(
                    appContext,
                    fileName + "_enc",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            cache.put(fileName, prefs);
            return prefs;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create EncryptedSharedPreferences for " + fileName, e);
            try {
                recreateEncryptedPrefs(appContext, fileName);
                MasterKey masterKey = new MasterKey.Builder(appContext)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();
                SharedPreferences prefs = EncryptedSharedPreferences.create(
                        appContext,
                        fileName + "_enc",
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
                cache.put(fileName, prefs);
                return prefs;
            } catch (Exception e2) {
                Log.e(TAG, "Fallback also failed for " + fileName
                        + " — DEGRADING TO PLAINTEXT STORAGE. Sensitive data should not be written.", e2);
                // 缓存 fallback 实例，避免后续每次调用都反复触发 recreateEncryptedPrefs 删除并重建加密文件
                SharedPreferences fallback = appContext.getSharedPreferences(fileName + "_fallback", Context.MODE_PRIVATE);
                cache.put(fileName, fallback);
                return fallback;
            }
        }
    }

    public static boolean isEncrypted(SharedPreferences prefs) {
        return prefs instanceof EncryptedSharedPreferences;
    }

    private static void migrateIfNeeded(Context context, String oldPrefsName) {
        SharedPreferences cryptoPrefs = context.getSharedPreferences(PREFS_CRYPTO, Context.MODE_PRIVATE);
        String migrationKey = KEY_MIGRATION_PREFIX + oldPrefsName;

        if (cryptoPrefs.getBoolean(migrationKey, false)) {
            return;
        }

        SharedPreferences oldPrefs = context.getSharedPreferences(oldPrefsName, Context.MODE_PRIVATE);
        Map<String, ?> oldData = new HashMap<>(oldPrefs.getAll());

        if (oldData.isEmpty()) {
            cryptoPrefs.edit().putBoolean(migrationKey, true).apply();
            return;
        }

        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            SharedPreferences newPrefs = EncryptedSharedPreferences.create(
                    context,
                    oldPrefsName + "_enc",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );

            SharedPreferences.Editor editor = newPrefs.edit();
            for (Map.Entry<String, ?> entry : oldData.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String) {
                    editor.putString(entry.getKey(), (String) value);
                } else if (value instanceof Integer) {
                    editor.putInt(entry.getKey(), (Integer) value);
                } else if (value instanceof Long) {
                    editor.putLong(entry.getKey(), (Long) value);
                } else if (value instanceof Float) {
                    editor.putFloat(entry.getKey(), (Float) value);
                } else if (value instanceof Boolean) {
                    editor.putBoolean(entry.getKey(), (Boolean) value);
                }
            }
            // 迁移是关键路径，用 commit() 同步落盘，确认成功后再清空 oldPrefs。
            // apply() 异步落盘，若 newPrefs 写入失败而 oldPrefs 已 clear，数据会永久丢失。
            boolean committed = editor.commit();
            if (!committed) {
                Log.e(TAG, "Migration failed: commit returned false for " + oldPrefsName);
                return;
            }

            oldPrefs.edit().clear().commit();
            cryptoPrefs.edit().putBoolean(migrationKey, true).commit();
            Log.i(TAG, "Migrated prefs from " + oldPrefsName + " to encrypted storage");
        } catch (Exception e) {
            Log.e(TAG, "Migration failed for " + oldPrefsName, e);
        }
    }

    private static void recreateEncryptedPrefs(Context context, String fileName) {
        try {
            context.deleteSharedPreferences(fileName + "_enc");
            Log.i(TAG, "Deleted corrupted encrypted prefs: " + fileName + "_enc");
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete corrupted prefs", e);
        }
    }
}
