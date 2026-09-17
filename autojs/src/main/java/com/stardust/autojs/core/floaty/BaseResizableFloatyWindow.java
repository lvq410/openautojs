package com.stardust.autojs.core.floaty;

import android.content.Context;

import androidx.annotation.Nullable;

import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.stardust.autojs.R;
import com.stardust.autojs.core.ui.inflater.inflaters.Exceptions;
import com.stardust.autojs.runtime.exception.ScriptInterruptedException;
import com.stardust.concurrent.VolatileDispose;
import com.stardust.enhancedfloaty.FloatyService;
import com.stardust.enhancedfloaty.ResizableFloaty;
import com.stardust.enhancedfloaty.ResizableFloatyWindow;
import com.stardust.enhancedfloaty.WindowBridge;
import com.stardust.enhancedfloaty.gesture.DragGesture;
import com.stardust.enhancedfloaty.gesture.ResizeGesture;
import com.stardust.util.WindowLayoutCompat;

/**
 * Created by Stardust on 2017/12/5.
 */

public class BaseResizableFloatyWindow extends ResizableFloatyWindow {

    public interface ViewSupplier {

        View inflate(Context context, ViewGroup parent);

    }

    private VolatileDispose<RuntimeException> mInflateException = new VolatileDispose<>();
    private View mCloseButton;
    private int mOffset;


    public BaseResizableFloatyWindow(Context context, ViewSupplier viewSupplier) {
        this(new MyFloaty(context, viewSupplier));
        mOffset = context.getResources().getDimensionPixelSize(R.dimen.floaty_window_offset);
    }

    private BaseResizableFloatyWindow(MyFloaty floaty) {
        super(floaty);
    }

    public RuntimeException waitForCreation() {
        return mInflateException.blockedGetOrThrow(ScriptInterruptedException.class);
    }

    @Override
    protected WindowManager.LayoutParams onCreateWindowLayoutParams() {
        //父类 ResizableFloatyWindow 在第三方 aar（com.github.hyb1996:EnhancedFloaty:0.31）内无源码，
        //只能拿到它建好的 LayoutParams 再加工：校正坐标系，使 setPosition(x,y) 等于屏幕物理绝对坐标。
        WindowManager.LayoutParams params = super.onCreateWindowLayoutParams();
        WindowLayoutCompat.applyAbsoluteScreenCoordinates(params);
        return params;
    }

    @Override
    protected WindowBridge onCreateWindowBridge(WindowManager.LayoutParams params) {
        return new WindowBridge.DefaultImpl(params, getWindowManager(), getWindowView()) {
            @Override
            public int getX() {
                return super.getX() + mOffset;
            }

            @Override
            public int getY() {
                return super.getY() + mOffset;
            }

            @Override
            public void updatePosition(int x, int y) {
                super.updatePosition(x - mOffset, y - mOffset);
            }

            /**
             * 屏幕尺寸改用物理全屏值。
             *
             * aar 里的 DefaultImpl 用 getMetrics()（应用可用区，本机横屏 2620 = 2772-152），
             * 且把 DisplayMetrics 缓存成字段永不刷新、转屏后是脏值。
             * 窗口坐标既已改为 0..2772 的绝对坐标，依赖屏幕尺寸的拖拽/缩放边界也必须同步换算基准，
             * 否则窗口拖到右侧会提前 152px 被"卡住"。
             */
            @Override
            public int getScreenWidth() {
                return WindowLayoutCompat.getRealScreenWidth(getWindowManager());
            }

            /** 说明同 {@link #getScreenWidth()}。 */
            @Override
            public int getScreenHeight() {
                return WindowLayoutCompat.getRealScreenHeight(getWindowManager());
            }
        };
    }

    @Override
    public void onCreate(FloatyService service, WindowManager manager) {
        try {
            super.onCreate(service, manager);
        } catch (RuntimeException e) {
            mInflateException.setAndNotify(e);
            return;
        }
        mInflateException.setAndNotify(Exceptions.NO_EXCEPTION);
    }

    public void setOnCloseButtonClickListener(View.OnClickListener listener) {
        mCloseButton.setOnClickListener(listener);
    }

    public void setAdjustEnabled(boolean enabled) {
        if (!enabled) {
            getMoveCursor().setVisibility(View.GONE);
            getResizer().setVisibility(View.GONE);
            mCloseButton.setVisibility(View.GONE);
        } else {
            getMoveCursor().setVisibility(View.VISIBLE);
            getResizer().setVisibility(View.VISIBLE);
            mCloseButton.setVisibility(View.VISIBLE);
        }
    }

    public boolean isAdjustEnabled() {
        return getMoveCursor().getVisibility() == View.VISIBLE;
    }

    @Override
    protected void onViewCreated(View view) {
        super.onViewCreated(view);
        mCloseButton = view.findViewById(R.id.close);
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

    private static class MyFloaty implements ResizableFloaty {


        private ViewSupplier mContentViewSupplier;
        private View mRootView;
        private Context mContext;


        public MyFloaty(Context context, ViewSupplier supplier) {
            mContentViewSupplier = supplier;
            mContext = context;
        }

        @Override
        public View inflateView(FloatyService floatyService, ResizableFloatyWindow resizableFloatyWindow) {
            mRootView = View.inflate(mContext, R.layout.floaty_window, null);
            FrameLayout container = mRootView.findViewById(R.id.container);
            View contentView = mContentViewSupplier.inflate(mContext, container);
            return mRootView;
        }

        @Nullable
        @Override
        public View getResizerView(View view) {
            return view.findViewById(R.id.resizer);
        }

        @Nullable
        @Override
        public View getMoveCursorView(View view) {
            return view.findViewById(R.id.move_cursor);
        }
    }
}
