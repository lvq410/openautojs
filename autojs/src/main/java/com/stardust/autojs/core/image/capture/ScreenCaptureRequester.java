package com.stardust.autojs.core.image.capture;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;

import androidx.annotation.RequiresApi;

import com.stardust.app.OnActivityResultDelegate;

/**
 * Created by Stardust on 2017/5/17.
 */

public interface ScreenCaptureRequester {

    void cancel();

    interface Callback {

        void onRequestResult(int result, Intent data);

    }

    void request();

    void setOnActivityResultCallback(Callback callback);

    void recycle();

    //共享的 ScreenCapturer，跨引擎复用截屏权限（对齐 AutoX.js 的 ScreenCaptureManager）
    ScreenCapturer getScreenCapturer();

    void setScreenCapturer(ScreenCapturer capturer);

    /**
     * 主动释放截屏权限：停止 MediaProjection 并清空共享 capturer。
     *
     * 与 {@link #recycle()} 的区别：recycle 只清本次授权请求的中间状态（回调、结果 Intent），
     * 本方法则是真正把系统层面的投屏会话交还——状态栏投屏通知随之消失。
     * 用于「脚本不再需要截图时主动交还权限」，避免其他检测录屏的 app 因此拒绝服务。
     *
     * @return 调用前是否确实持有截屏权限
     */
    boolean releaseScreenCapture();

    /** 当前是否持有截屏权限（持有期间系统会显示投屏通知、可被其他 app 探测到） */
    boolean isScreenCaptureHeld();

    /** {@link #requestScreenCaptureForUi} 的结果回调 */
    interface RequestResultCallback {
        /** @param granted 最终是否拿到截屏权限 */
        void onResult(boolean granted);
    }

    /**
     * 不依赖脚本运行时地申请截屏权限，供 UI 层（如侧边栏开关）使用。
     *
     * 与 {@link com.stardust.autojs.runtime.api.Images#requestScreenCapture(int)} 的区别：
     * 后者需要 ScriptRuntime 的 servantLooper 建 Handler、并通过 Promise 回给脚本；
     * UI 层没有 runtime，故这里用主线程 Handler，结果通过回调异步返回。
     *
     * 两种情形都会走到回调（调用方据此刷新 UI，不要依赖本方法的即时返回）：
     * - 常态：弹出系统授权对话框，用户确认后回调 true、取消则 false
     * - 已通过 `adb shell cmd appops set <pkg> PROJECT_MEDIA allow` 放行时：
     *   系统不弹窗、直接授予，回调几乎立即触发（实测约 150ms）
     *
     * 已持有权限时直接回调 true，不重新申请。
     *
     * @param callback 结果回调，在主线程执行
     */
    void requestScreenCaptureForUi(RequestResultCallback callback);

    abstract class AbstractScreenCaptureRequester implements ScreenCaptureRequester {

        protected Callback mCallback;
        protected Intent mResult;
        protected volatile ScreenCapturer mScreenCapturer;

        @Override
        public ScreenCapturer getScreenCapturer() {
            return mScreenCapturer;
        }

        @Override
        public void setScreenCapturer(ScreenCapturer capturer) {
            mScreenCapturer = capturer;
        }

        @Override
        public synchronized boolean releaseScreenCapture() {
            ScreenCapturer capturer = mScreenCapturer;
            if (capturer == null) return false;
            //release() 内含 mMediaProjection.stop()，这才是真正交还系统权限的那一步
            capturer.release();
            mScreenCapturer = null;
            //授权结果 Intent 也一并丢弃：Android 11+ 的 token 是一次性的，留着也无法二次换 projection，
            //且 cancel() 用它当「是否已有结果」的哨兵，不清会影响下次重新授权的回调判断
            mResult = null;
            return true;
        }

        @Override
        public boolean isScreenCaptureHeld() {
            return mScreenCapturer != null;
        }

        /**
         * 基类不实现：构造 ScreenCapturer 需要 Context，而本类拿不到。
         * 由持有 Context 的子类（AutoJs.ScreenCaptureRequesterImpl）覆写。
         */
        @Override
        public void requestScreenCaptureForUi(RequestResultCallback callback) {
            if (callback != null) callback.onResult(isScreenCaptureHeld());
        }

        @Override
        public void setOnActivityResultCallback(Callback callback) {
            mCallback = callback;
        }

        public void onResult(int resultCode, Intent data) {
            mResult = data;
            if (mCallback != null)
                mCallback.onRequestResult(resultCode, data);
        }

        @Override
        public void cancel() {
            if (mResult != null)
                return;
            if (mCallback != null)
                mCallback.onRequestResult(Activity.RESULT_CANCELED, null);
            mCallback = null;
        }

        @Override
        public void recycle() {
            //只清本次授权请求的中间状态，刻意不释放共享的 capturer——
            //它跨引擎复用，某个脚本退出不应影响其他正在截图的脚本。
            //要真正交还权限用 releaseScreenCapture()
            mResult = null;
            mCallback = null;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    class ActivityScreenCaptureRequester extends AbstractScreenCaptureRequester implements ScreenCaptureRequester, OnActivityResultDelegate {

        private static final int REQUEST_CODE_MEDIA_PROJECTION = 17777;
        private Mediator mMediator;
        private Activity mActivity;

        public ActivityScreenCaptureRequester(Mediator mediator, Activity activity) {
            mMediator = mediator;
            mActivity = activity;
            mMediator.addDelegate(REQUEST_CODE_MEDIA_PROJECTION, this);
        }


        @Override
        public void request() {
            mActivity.startActivityForResult(((MediaProjectionManager) mActivity.getSystemService(Context.MEDIA_PROJECTION_SERVICE)).createScreenCaptureIntent(), REQUEST_CODE_MEDIA_PROJECTION);
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            mResult = data;
            mMediator.removeDelegate(this);
            onResult(resultCode, data);
        }

        @Override
        public void recycle() {
            super.recycle();
            mMediator.removeDelegate(this);
        }
    }

}
