package org.autojs.autojs.ui.floating;

import android.graphics.PixelFormat;
import android.view.WindowManager;

import com.stardust.enhancedfloaty.FloatyWindow;
import com.stardust.util.WindowLayoutCompat;

/**
 * Created by Stardust on 2017/10/18.
 */

public abstract class FullScreenFloatyWindow extends FloatyWindow {

    @Override
    protected WindowManager.LayoutParams onCreateWindowLayoutParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                FloatyWindowManger.getWindowType(),
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        //布局分析器要覆盖整个物理屏幕才能准确框出控件——控件的 boundsInScreen 是物理绝对坐标，
        //窗口若被 insets 内缩，画出来的框就会整体偏移（横屏下 X 方向此前一直偏 152px）。
        //注：LayoutBoundsView 用 getLocationOnScreen() 动态取偏移量，窗口归零后它自动退化为恒等变换，无需改动。
        WindowLayoutCompat.applyAbsoluteScreenCoordinates(params);
        return params;
    }

}
