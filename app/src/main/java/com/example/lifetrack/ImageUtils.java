package com.example.lifetrack;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class ImageUtils {

    private static final String TAG = "ImageUtils";
    private static final int MAX_DIMENSION = 768;
    private static final int COMPRESS_QUALITY = 60;

    /**
     * 压缩 Bitmap：若任一边超过 MAX_DIMENSION，则按比例缩放到限定范围内。
     * <p>
     * 所有权约定：
     * - 入参 bitmap 不会被回收，调用方仍持有所有权。
     * - 返回值可能是入参 bitmap 本身（无需缩放时），也可能是新创建的 Bitmap。
     * - 若返回值是新 Bitmap，调用方负责在不再使用时 recycle。
     * - 切勿对入参与返回值同时 recycle（当二者为同一对象时会重复回收）。
     *
     * @param bitmap 源 Bitmap，可为 null
     * @return 压缩后的 Bitmap（可能等于入参），入参为 null 时返回 null
     */
    public static Bitmap compressBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) {
            return bitmap;
        }
        if (width <= MAX_DIMENSION && height <= MAX_DIMENSION) {
            return bitmap;
        }
        float scale = Math.min(
                (float) MAX_DIMENSION / width,
                (float) MAX_DIMENSION / height);
        int newWidth = Math.max(1, Math.round(width * scale));
        int newHeight = Math.max(1, Math.round(height * scale));
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
    }

    public static byte[] bitmapToBytes(Bitmap bitmap) {
        Bitmap resized = compressBitmap(bitmap);
        if (resized == null) {
            return new byte[0];
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        boolean compressed = resized.compress(Bitmap.CompressFormat.JPEG, COMPRESS_QUALITY, outputStream);
        byte[] bytes = outputStream.toByteArray();
        if (resized != bitmap) {
            resized.recycle();
        }
        if (!compressed) {
            Log.e(TAG, "Bitmap.compress returned false, data may be invalid");
        }
        return bytes;
    }

    public static String bitmapToBase64(Bitmap bitmap) {
        byte[] bytes = bitmapToBytes(bitmap);
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    public static Bitmap decodeSampledBitmap(Context context, android.net.Uri uri,
                                              int reqWidth, int reqHeight) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        try {
            options.inJustDecodeBounds = true;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                if (is == null) {
                    Log.e(TAG, "openInputStream returned null for bounds: " + uri);
                    return null;
                }
                BitmapFactory.decodeStream(is, null, options);
            }

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            options.inJustDecodeBounds = false;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                if (is == null) {
                    Log.e(TAG, "openInputStream returned null for decode: " + uri);
                    return null;
                }
                return BitmapFactory.decodeStream(is, null, options);
            }
        } catch (Exception e) {
            Log.e(TAG, "decodeSampledBitmap failed for uri=" + uri, e);
            return null;
        }
    }

    public static int calculateInSampleSize(BitmapFactory.Options options,
                                             int reqWidth, int reqHeight) {
        if (reqWidth <= 0 || reqHeight <= 0) {
            return 1;
        }
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }
}
