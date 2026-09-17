package com.stardust.util;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Point;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;

import static java.lang.System.out;

/**
 * Created by Stardust on 2017/4/26.
 */

public class ScreenMetrics {

    private static int deviceScreenHeight;
    private static int deviceScreenWidth;
    private static boolean initialized = false;
    private static int deviceScreenDensity;
    private static Display display;
    
    
    public static void initIfNeeded(Activity activity) {
        if (initialized && deviceScreenHeight != 0)
            return;
        DisplayMetrics metrics = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        display = activity.getWindowManager().getDefaultDisplay();
        DisplayMetrics metrics1 = activity.getResources().getDisplayMetrics();
        deviceScreenHeight = metrics.heightPixels;
        if(deviceScreenHeight==0){
            deviceScreenHeight=display.getHeight();
            if(deviceScreenHeight==0){
                deviceScreenHeight=metrics1.heightPixels;
            }
        }
        deviceScreenWidth = metrics.widthPixels;
        if(deviceScreenWidth==0){
            deviceScreenWidth=display.getWidth();
            if(deviceScreenWidth==0){
                deviceScreenWidth=metrics1.widthPixels;
            }
        }
        deviceScreenDensity = metrics.densityDpi;
        initialized = true;
    }

    public static int getDeviceScreenHeight() {
        return deviceScreenHeight;
    }

    public static int getDeviceScreenWidth() {
        return deviceScreenWidth;
    }

    public static int getDeviceScreenDensity() {
        return deviceScreenDensity;
    }

    /**
     * 返回指定方向下的屏幕宽度。
     *
     * 注意不能直接用 deviceScreenWidth/Height 做「横屏就交换」的判断：
     * initIfNeeded 用 getRealMetrics() 取值，存的是【初始化那一刻】的方向，
     * 若 app 恰好在横屏下启动，deviceScreenWidth 存的就是长边（如 2772）而非短边，
     * 原先「横屏返回 getDeviceScreenHeight()」的写法会把宽高返反，
     * 导致 ScreenCapturer 建出方向相反的 VirtualDisplay、截图变成竖图。
     * 故这里改用 min/max 归一化，与初始化时的方向无关。
     */
    public static int getOrientationAwareScreenWidth(int orientation) {
        int shortSide = Math.min(deviceScreenWidth, deviceScreenHeight);
        int longSide = Math.max(deviceScreenWidth, deviceScreenHeight);
        return orientation == Configuration.ORIENTATION_LANDSCAPE ? longSide : shortSide;
    }

    /** 返回指定方向下的屏幕高度。说明同 {@link #getOrientationAwareScreenWidth(int)}。 */
    public static int getOrientationAwareScreenHeight(int orientation) {
        int shortSide = Math.min(deviceScreenWidth, deviceScreenHeight);
        int longSide = Math.max(deviceScreenWidth, deviceScreenHeight);
        return orientation == Configuration.ORIENTATION_LANDSCAPE ? shortSide : longSide;
    }

    public static int scaleX(int x, int width) {
        if (width == 0 || !initialized)
            return x;
        return x * deviceScreenWidth / width;
    }

    public static int scaleY(int y, int height) {
        if (height == 0 || !initialized)
            return y;
        return y * deviceScreenHeight / height;
    }

    public static int rescaleX(int x, int width) {
        if (width == 0 || !initialized)
            return x;
        return x * width / deviceScreenWidth;
    }

    public static int rescaleY(int y, int height) {
        if (height == 0 || !initialized)
            return y;
        return y * height / deviceScreenHeight;
    }


    private int mDesignWidth;
    private int mDesignHeight;

    public ScreenMetrics(int designWidth, int designHeight) {
        mDesignWidth = designWidth;
        mDesignHeight = designHeight;
    }

    public ScreenMetrics() {
    }

    public void setDesignWidth(int designWidth) {
        mDesignWidth = designWidth;
    }

    public void setDesignHeight(int designHeight) {
        mDesignHeight = designHeight;
    }

    public int scaleX(int x) {
        return scaleX(x, mDesignWidth);
    }

    public int scaleY(int y) {
        return scaleY(y, mDesignHeight);
    }


    public void setScreenMetrics(int width, int height) {
        mDesignWidth = width;
        mDesignHeight = height;
    }

    public int rescaleX(int x) {
        return rescaleX(x, mDesignWidth);
    }


    public int rescaleY(int y) {
        return rescaleY(y, mDesignHeight);
    }
}
