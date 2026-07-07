package com.stardust.autojs.core.image.capture;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 闪像素存活探测的 1px 悬浮窗。
 *
 * 用途：配合 {@link ScreenCapturer#probeLivenessByBlink()} 区分"截屏 projection 活着但画面静止（无新帧）"
 *      与"被抢占/已死"。原理：镜像 VirtualDisplay 只在【源屏幕重新合成】时才产帧；悬浮窗是源屏幕合成的一部分，
 *      让这个 1px 悬浮窗在角落挪动 1px，即可强制真实屏幕重新合成一帧：
 *        - 活着的 projection：镜像会收到这一帧 → 判存活；
 *        - 已死/被抢占：镜像收不到任何帧 → 判失效。
 *      （setSurface/resize 只动消费端、不触发源端重合成，无法区分，故改用悬浮窗动源端。）
 *
 * 悬浮窗 1px、位于屏幕左上角、alpha 近乎不可见（0x08），常驻不销毁；仅在探测时挪动触发重合成。
 * app 已具备悬浮窗权限（用于悬浮小球），无需额外申请。
 */
public class BlinkProbe {

    private static final String TAG = "BlinkProbe";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static View sView;
    private static WindowManager sWm;
    private static WindowManager.LayoutParams sLp;

    /**
     * "闪"一下：把 1px 悬浮窗位置在 0/1 之间切换，强制源屏幕重新合成一帧。
     * 所有窗口操作在主线程同步执行（addView/updateViewLayout 必须在有 Looper 的线程）。
     */
    public static void nudge(Context ctx) {
        runOnMainSync(() -> {
            try {
                if (sView == null) {
                    createLocked(ctx);
                }
                sLp.x = (sLp.x == 0) ? 1 : 0;
                sWm.updateViewLayout(sView, sLp);
            } catch (Throwable t) {
                Log.w(TAG, "nudge failed", t);
            }
        });
    }

    private static void createLocked(Context ctx) {
        Context app = ctx.getApplicationContext();
        sWm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        View v = new View(app);
        v.setBackgroundColor(0x08000000); //近乎不可见的黑（alpha=8/255），仅为确保图层被合成
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                1, 1, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = 0;
        lp.y = 0;
        sWm.addView(v, lp);
        sView = v;
        sLp = lp;
    }

    private static void runOnMainSync(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
            return;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        MAIN.post(() -> {
            try {
                r.run();
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignore) {
        }
    }
}
