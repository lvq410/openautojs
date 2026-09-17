package com.stardust.util;

import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Window;
import android.view.WindowManager;

/**
 * 窗口布局兼容工具：把悬浮窗坐标系校正为「屏幕物理绝对坐标」，以及让 Activity 铺满挖孔区。
 *
 * <h3>要解决的问题</h3>
 * {@link WindowManager.LayoutParams} 的 x/y <b>不是</b>屏幕绝对坐标，而是相对窗口 parent frame 的偏移。
 * 系统默认按 fitInsetsTypes（状态栏/导航栏）和挖孔安全区把 parent frame 向内收缩，于是：
 * <ul>
 *   <li>竖屏下 {@code setPosition(x, 0)} 实际落在状态栏底部（实测本机偏 152px）</li>
 *   <li>横屏且挖孔在左侧时，x 还会再偏移一个挖孔宽度</li>
 * </ul>
 *
 * <h3>为什么 FLAG_LAYOUT_NO_LIMITS 挡不住</h3>
 * Android 11(API 30) 起窗口 frame 改由 fitInsetsTypes 决定，NO_LIMITS 只解除 display frame 限制。
 * 实测 BlinkProbe 已带该 flag，请求 {@code lp=(1,0)} 仍被推到 {@code frame=[153,152]}
 * （当时 {@code parent=[152,152][2772,1280]}，正好是 parent+lp）—— 这也反过来证明：
 * <b>parent frame 归零后 frame 就等于 lp</b>，即 lp.x/y 天然成为绝对坐标，调用方无需任何换算。
 *
 * <h3>为什么拆成两个方法</h3>
 * 悬浮窗和 Activity 的需求不同，<b>绝不能给 Activity 用悬浮窗那一套</b>：
 * Activity 若被加上 NO_LIMITS / setFitInsetsTypes(0)，WindowInsets 将不再正常派发，
 * Material3 的 TopAppBar 就会失去状态栏避让、顶栏直接顶进状态栏。
 * 故 Activity 只用 {@link #applyDrawIntoCutout(Window)}，仅解除挖孔内缩。
 */
public class WindowLayoutCompat {

    /**
     * 让悬浮窗以屏幕物理左上角为坐标原点（调用后 lp.x/y 即屏幕绝对坐标）。
     *
     * <p><b>必须在 flags 全部设置完毕之后调用</b>：框架在未显式 setFitInsetsTypes 时，
     * 会从 FLAG_FULLSCREEN / FLAG_LAYOUT_IN_SCREEN 等旧 flag 反推 fitInsetsTypes；
     * setFitInsetsTypes() 会置上 PRIVATE_FLAG_FIT_INSETS_CONTROLLED 让框架停止反推。
     * 若把本调用挪到 new LayoutParams(...) 之前，后续设置的 flags 会让它失效。
     *
     * <p>调用方仍需自行把 gravity 设为 TOP|LEFT，否则原点不在左上角。
     *
     * @param lp 待校正的窗口参数
     */
    public static void applyAbsoluteScreenCoordinates(WindowManager.LayoutParams lp) {
        if (lp == null) return;

        // API 28+：允许窗口延伸进刘海/挖孔区。
        // 不声明时系统把 parent frame 缩到安全区，横屏挖孔在左就表现为左侧一条黑边。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }

        // API 30+：清空 fitInsetsTypes，令 frame 不再按状态栏/导航栏内缩。
        // 这是竖屏 y 偏移的直接原因，也是本方法的核心。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.setFitInsetsTypes(0);
        }

        // API 30 以下仍靠 NO_LIMITS 兜底——那些版本上 parent frame 不按 insets 收缩，该 flag 足够。
        lp.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
    }

    /**
     * 让 Activity 窗口铺满挖孔区（消除横屏下挖孔侧的黑边）。
     *
     * <p>与 {@link #applyAbsoluteScreenCoordinates} 的区别：这里<b>只</b>解除挖孔内缩，
     * 不动 fitInsetsTypes、不加 NO_LIMITS。因为 Activity 内的 Material3 组件
     * （TopAppBar / NavigationBar）依赖 WindowInsets 正常派发来做系统栏避让，
     * 一旦关掉，顶栏会直接顶进状态栏。
     *
     * <p>用 SHORT_EDGES 而非 ALWAYS：与本项目 SplashActivity 既有写法保持一致，
     * 是同机型上已验证生效的值；ALWAYS 在 Activity 上有触发系统 letterbox 的历史包袱。
     *
     * @param window Activity 的 window，通常在 onCreate 中调用
     */
    public static void applyDrawIntoCutout(Window window) {
        if (window == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;

        WindowManager.LayoutParams lp = window.getAttributes();
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        window.setAttributes(lp);
    }

    /**
     * 取当前方向下的屏幕<b>物理</b>宽度。
     *
     * <p>存在的理由：EnhancedFloaty aar 里 {@code WindowBridge.DefaultImpl} 用的是
     * {@code getDefaultDisplay().getMetrics()}——那是「应用可用区」（本机横屏 2620 = 2772-152），
     * 且它把 DisplayMetrics 缓存成字段永不刷新，转屏后是脏值。
     * 悬浮窗坐标既已改为 0..2772 的绝对坐标，贴边计算的尺寸源也必须同步换成物理尺寸。
     *
     * <p>{@code getRealMetrics()} 自带旋转感知（竖屏 1280x2772、横屏 2772x1280）且每次实时取，
     * 因此调用方<b>不需要</b>再做横竖屏宽高互换。
     */
    public static int getRealScreenWidth(WindowManager wm) {
        return getRealMetrics(wm).widthPixels;
    }

    /** 取当前方向下的屏幕<b>物理</b>高度。说明同 {@link #getRealScreenWidth(WindowManager)}。 */
    public static int getRealScreenHeight(WindowManager wm) {
        return getRealMetrics(wm).heightPixels;
    }

    private static DisplayMetrics getRealMetrics(WindowManager wm) {
        DisplayMetrics metrics = new DisplayMetrics();
        if (wm != null) {
            wm.getDefaultDisplay().getRealMetrics(metrics);
        }
        return metrics;
    }
}
