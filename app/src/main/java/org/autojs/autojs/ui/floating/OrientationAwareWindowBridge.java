package org.autojs.autojs.ui.floating;

import android.content.Context;
import android.view.View;
import android.view.WindowManager;

import com.stardust.enhancedfloaty.WindowBridge;
import com.stardust.util.WindowLayoutCompat;

public class OrientationAwareWindowBridge extends WindowBridge.DefaultImpl {


    private Context mContext;
    private int mOrientation;
    /**
     * aar 里 DefaultImpl.mWindowManager 是 private、子类取不到，故自留一份用于取物理屏幕尺寸。
     */
    private WindowManager mWindowManager;

    public OrientationAwareWindowBridge(WindowManager.LayoutParams windowLayoutParams, WindowManager windowManager, View windowView, Context context) {
        super(windowLayoutParams, windowManager, windowView);
        mContext = context;
        mWindowManager = windowManager;
        mOrientation = mContext.getResources().getConfiguration().orientation;
    }

    public boolean isOrientationChanged(int newOrientation) {
        if (mOrientation != newOrientation) {
            mOrientation = newOrientation;
            return true;
        }
        return false;
    }

    /**
     * 返回当前方向下的屏幕<b>物理</b>高度。
     *
     * <p>改动前：调 super（aar 的 DefaultImpl）拿 {@code getDefaultDisplay().getMetrics()}，
     * 那是「应用可用区」（本机横屏 2620 = 2772 - 152 挖孔），且 DefaultImpl 把 DisplayMetrics
     * 缓存成字段永不刷新、转屏后是脏值——原先那段「横屏时宽高互换」正是为绕开这个脏缓存而写的补丁。
     *
     * <p>改动后：直接用 {@code getRealMetrics()}。它返回物理全屏尺寸、每次实时取、且自带旋转感知
     * （竖屏 1280x2772、横屏 2772x1280），因此<b>必须删掉原来的宽高互换</b>，
     * 否则会变成双重交换、横屏下宽高又反了。
     *
     * <p>这一改与「悬浮窗坐标改绝对」是配套的：{@code DragGesture.keepToEdge()} 用
     * {@code getScreenWidth()} 算贴右边的 x，坐标系已是 0..2772，尺寸源也必须是 2772。
     */
    @Override
    public int getScreenHeight() {
        return WindowLayoutCompat.getRealScreenHeight(mWindowManager);
    }

    /** 说明同 {@link #getScreenHeight()}。 */
    @Override
    public int getScreenWidth() {
        return WindowLayoutCompat.getRealScreenWidth(mWindowManager);
    }
}
