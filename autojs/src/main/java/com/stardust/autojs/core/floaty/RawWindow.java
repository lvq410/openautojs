package com.stardust.autojs.core.floaty;

import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.stardust.autojs.R;
import com.stardust.autojs.core.ui.inflater.inflaters.Exceptions;
import com.stardust.autojs.runtime.exception.ScriptInterruptedException;
import com.stardust.concurrent.VolatileBox;
import com.stardust.concurrent.VolatileDispose;
import com.stardust.enhancedfloaty.FloatyService;
import com.stardust.enhancedfloaty.FloatyWindow;
import com.stardust.enhancedfloaty.WindowBridge;
import com.stardust.enhancedfloaty.util.WindowTypeCompat;
import com.stardust.util.WindowLayoutCompat;

public class RawWindow extends FloatyWindow {



    public interface RawFloaty {

        View inflateWindowView(FloatyService service, ViewGroup parent);
    }

    private VolatileDispose<RuntimeException> mInflateException = new VolatileDispose<>();
    private RawFloaty mRawFloaty;
    private View mContentView;

    public RawWindow(RawFloaty rawFloaty) {
        mRawFloaty = rawFloaty;
    }

    @Override
    public void onCreate(FloatyService floatyService, WindowManager windowManager) {
        try {
            super.onCreate(floatyService, windowManager);
        } catch (RuntimeException e) {
            mInflateException.setAndNotify(e);
            return;
        }
        mInflateException.setAndNotify(Exceptions.NO_EXCEPTION);
    }

    @Override
    protected View onCreateView(FloatyService floatyService) {
        ViewGroup windowView = (ViewGroup) View.inflate(floatyService, R.layout.raw_window, null);
        mContentView = mRawFloaty.inflateWindowView(floatyService, windowView);
        return windowView;
    }

    public RuntimeException waitForCreation() {
        return mInflateException.blockedGetOrThrow(ScriptInterruptedException.class);
    }

    public View getContentView() {
        return mContentView;
    }

    @Override
    protected WindowManager.LayoutParams onCreateWindowLayoutParams() {
        int flags =
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            flags |= WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS;
        }
        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowTypeCompat.getWindowType(),
                flags,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        //校正坐标系：使 setPosition(x,y) 的 x/y 等于屏幕物理绝对坐标。
        //必须放在 flags 设置完成之后——框架会从 FLAG_FULLSCREEN 等旧 flag 反推 fitInsetsTypes，
        //setFitInsetsTypes 置上 FIT_INSETS_CONTROLLED 后才会停止反推。
        WindowLayoutCompat.applyAbsoluteScreenCoordinates(layoutParams);
        return layoutParams;
    }

    public void disableWindowFocus() {
        WindowManager.LayoutParams windowLayoutParams = getWindowLayoutParams();
        windowLayoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        updateWindowLayoutParams(windowLayoutParams);
    }

    public void requestWindowFocus() {
        WindowManager.LayoutParams windowLayoutParams = getWindowLayoutParams();
        windowLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        updateWindowLayoutParams(windowLayoutParams);
        getWindowView().requestLayout();
    }

    public void setTouchable(boolean touchable) {
        WindowManager.LayoutParams windowLayoutParams = getWindowLayoutParams();
        if (touchable) {
            windowLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            windowLayoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        updateWindowLayoutParams(windowLayoutParams);
    }

    /**
     * 临时隐藏/显示整个悬浮窗，窗口本身不销毁。
     *
     * 与 {@link #close()} 的区别：close() 会 removeView 并从 FloatyService 注销，之后无法再显示，
     * 要再用只能重建；本方法只切换根视图可见性，位置、大小、触摸开关以及所有已注册的事件监听
     * 全部原样保留，可反复切换。
     *
     * 注意：隐藏期间根视图不参与测量，getWidth()/getHeight() 会返回 0
     * （getX()/getY() 读的是 LayoutParams，不受影响）。
     */
    public void setWindowVisible(boolean visible) {
        View windowView = getWindowView();
        if (windowView == null) return;
        windowView.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public boolean isWindowVisible() {
        View windowView = getWindowView();
        return windowView != null && windowView.getVisibility() == View.VISIBLE;
    }

}
