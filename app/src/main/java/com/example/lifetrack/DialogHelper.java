package com.example.lifetrack;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.view.View;

public class DialogHelper {

    /** 默认背景变暗比例 */
    private static final float DEFAULT_DIM_AMOUNT = 0.5f;

    public static Dialog create(Context context, int layoutResId) {
        return create(context, layoutResId, true, DEFAULT_DIM_AMOUNT);
    }

    public static Dialog create(Context context, int layoutResId, boolean cancelable) {
        return create(context, layoutResId, cancelable, DEFAULT_DIM_AMOUNT);
    }

    /**
     * 创建 Dialog。
     *
     * @param context     必须为 Activity，传 ApplicationContext 等非 Activity 上下文会导致
     *                    Dialog 无法绑定窗口令牌而崩溃
     * @param layoutResId Dialog 内容布局资源 id
     * @param cancelable  是否可取消（点击外部/返回键）
     * @param dimAmount    背景变暗比例 0f~1f，传 0.5f 为常见值
     */
    public static Dialog create(Context context, int layoutResId, boolean cancelable, float dimAmount) {
        if (!(context instanceof Activity)) {
            throw new IllegalArgumentException("context must be an Activity, got: "
                    + (context == null ? "null" : context.getClass().getName()));
        }
        Dialog dialog = new Dialog(context);
        dialog.setContentView(layoutResId);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setDimAmount(dimAmount);
        }
        dialog.setCancelable(cancelable);
        return dialog;
    }

    public static void setConfirmButton(Dialog dialog, int buttonId, Runnable onConfirm) {
        View btn = dialog.findViewById(buttonId);
        if (btn != null) {
            btn.setOnClickListener(v -> {
                // 先执行回调再 dismiss。dismiss 后 Dialog 的 ContentView 会脱离窗口，
                // 若回调需要从 Dialog 内的 EditText/Spinner 取值，先 dismiss 可能取到异常状态。
                if (onConfirm != null) onConfirm.run();
                dialog.dismiss();
            });
        }
    }

    public static void setCloseButton(Dialog dialog) {
        View btnClose = dialog.findViewById(R.id.btnDialogClose);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }
    }
}
