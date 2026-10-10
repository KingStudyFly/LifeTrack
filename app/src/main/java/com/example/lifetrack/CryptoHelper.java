package com.example.lifetrack;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.UnrecoverableEntryException;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class CryptoHelper {

    private static final String TAG = "CryptoHelper";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "lifetrack_master_key";
    private static final String PREFS_NAME = "lifetrack_crypto";
    private static final String KEY_ENCRYPTED_DB_KEY = "encrypted_db_key";
    private static final String KEY_IV = "db_key_iv";
    private static final String KEY_DB_ENCRYPTED = "db_encrypted";

    private static volatile byte[] cachedDbKey;
    private static volatile boolean keyRegenerated = false;

    public static synchronized byte[] getDatabaseKey(Context context) {
        if (cachedDbKey != null) {
            return cachedDbKey.clone();
        }

        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String encryptedKeyBase64 = prefs.getString(KEY_ENCRYPTED_DB_KEY, null);
            String ivBase64 = prefs.getString(KEY_IV, null);

            if (encryptedKeyBase64 != null && ivBase64 != null) {
                byte[] encryptedKey = Base64.decode(encryptedKeyBase64, Base64.NO_WRAP);
                byte[] iv = Base64.decode(ivBase64, Base64.NO_WRAP);
                try {
                    cachedDbKey = decryptWithKeystoreKey(encryptedKey, iv);
                    return cachedDbKey.clone();
                } catch (Exception decryptEx) {
                    Log.w(TAG, "decrypt failed, regenerating DB key (old encrypted data will be inaccessible)", decryptEx);
                    prefs.edit()
                            .remove(KEY_ENCRYPTED_DB_KEY)
                            .remove(KEY_IV)
                            .apply();
                    invalidateCache();
                    keyRegenerated = true;
                }
            }

            byte[] newKey = generateRandomKey();
            SecretKey keystoreKey = getOrCreateKeystoreKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keystoreKey);
            byte[] encryptedKey = cipher.doFinal(newKey);
            byte[] iv = cipher.getIV();

            boolean committed = prefs.edit()
                    .putString(KEY_ENCRYPTED_DB_KEY, Base64.encodeToString(encryptedKey, Base64.NO_WRAP))
                    .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .commit();
            if (!committed) {
                throw new IllegalStateException("Failed to persist database key (SharedPreferences.commit returned false)");
            }
            cachedDbKey = newKey;
            return cachedDbKey.clone();
        } catch (Exception e) {
            Log.e(TAG, "getDatabaseKey failed", e);
            throw new RuntimeException("Failed to get database key", e);
        }
    }

    public static boolean consumeKeyRegeneratedFlag() {
        boolean flag = keyRegenerated;
        keyRegenerated = false;
        return flag;
    }

    public static String getDatabaseKeyAsString(Context context) {
        byte[] key = getDatabaseKey(context);
        StringBuilder sb = new StringBuilder(key.length * 2);
        for (byte b : key) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }

    public static boolean isDatabaseEncrypted(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_DB_ENCRYPTED, false);
    }

    public static void setDatabaseEncrypted(Context context, boolean encrypted) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_DB_ENCRYPTED, encrypted).apply();
    }

    private static SecretKey getOrCreateKeystoreKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);

        if (keyStore.containsAlias(KEY_ALIAS)) {
            try {
                return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
            } catch (UnrecoverableEntryException e) {
                // 涵盖 KeyPermanentlyInvalidatedException（其子类）及其他不可恢复场景，统一重建密钥
                Log.w(TAG, "Keystore key invalidated, regenerating", e);
                keyStore.deleteEntry(KEY_ALIAS);
            }
        }

        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build();
        keyGenerator.init(spec);
        return keyGenerator.generateKey();
    }

    private static byte[] decryptWithKeystoreKey(byte[] encryptedKey, byte[] iv) throws Exception {
        SecretKey keystoreKey = getOrCreateKeystoreKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey, spec);
        return cipher.doFinal(encryptedKey);
    }

    private static byte[] generateRandomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    public static synchronized void invalidateCache() {
        if (cachedDbKey != null) {
            for (int i = 0; i < cachedDbKey.length; i++) {
                cachedDbKey[i] = 0;
            }
        }
        cachedDbKey = null;
    }
}
