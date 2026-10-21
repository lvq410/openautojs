package com.stardust.autojs.core.image.capture;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.stardust.util.ScreenMetrics;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 参照AutoX.js的ScreenCapturer实现，按需获取截图，不依赖特定引擎的Handler/Looper，
 * 支持跨引擎共享及Android 14+ VirtualDisplay复用
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
public class ScreenCapturer {

    public static final int ORIENTATION_AUTO = Configuration.ORIENTATION_UNDEFINED;
    public static final int ORIENTATION_LANDSCAPE = Configuration.ORIENTATION_LANDSCAPE;
    public static final int ORIENTATION_PORTRAIT = Configuration.ORIENTATION_PORTRAIT;

    private static final String LOG_TAG = "ScreenCapturer";

    private final MediaProjection mMediaProjection;
    private final int mScreenDensity;
    private final Context mContext;

    private ImageReader mImageReader;
    private VirtualDisplay mVirtualDisplay;
    private final AtomicReference<Image> mCachedImage = new AtomicReference<>();
    private volatile boolean mAvailable = true;
    private int mOrientation = -1;
    private int mDetectedOrientation;

    public ScreenCapturer(Context context, Intent data, int orientation, int screenDensity, Handler handler) {
        mContext = context;
        mScreenDensity = screenDensity;
        MediaProjectionManager projectionManager = (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mMediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, (Intent) data.clone());
        mMediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                //token 被系统或用户（通知栏「停止投屏」）收回。除了置不可用，还要把
                //VirtualDisplay/ImageReader 一并拆掉——否则它们只能等 GC finalize，
                //期间仍占着显示资源。对齐 AutoX.js 的处理。
                mAvailable = false;
                releaseDisplayResources();
            }
        }, new Handler(Looper.getMainLooper()));

        int screenHeight = ScreenMetrics.getOrientationAwareScreenHeight(orientation == ORIENTATION_AUTO ? context.getResources().getConfiguration().orientation : orientation);
        int screenWidth = ScreenMetrics.getOrientationAwareScreenWidth(orientation == ORIENTATION_AUTO ? context.getResources().getConfiguration().orientation : orientation);
        mOrientation = orientation;
        mDetectedOrientation = context.getResources().getConfiguration().orientation;
        mImageReader = createImageReader(screenWidth, screenHeight);
        mVirtualDisplay = createVirtualDisplay(screenWidth, screenHeight, screenDensity);
    }

    @SuppressLint("WrongConstant")
    private ImageReader createImageReader(int width, int height) {
        return ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
    }

    @SuppressLint("WrongConstant")
    private VirtualDisplay createVirtualDisplay(int width, int height, int screenDensity) {
        return mMediaProjection.createVirtualDisplay(LOG_TAG,
                width, height, screenDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mImageReader.getSurface(), null, null);
    }

    public void setOrientation(int orientation) {
        if (mOrientation == orientation)
            return;
        mOrientation = orientation;
        mDetectedOrientation = mContext.getResources().getConfiguration().orientation;
        refreshVirtualDisplay(mOrientation == ORIENTATION_AUTO ? mDetectedOrientation : mOrientation);
    }

    private synchronized void refreshVirtualDisplay(int orientation) {
        //复用已有VirtualDisplay，只替换ImageReader的Surface并resize，避免Android 14+上MediaProjection过期
        Image oldCached = mCachedImage.getAndSet(null);
        if (oldCached != null) {
            oldCached.close();
        }
        if (mImageReader != null) {
            mImageReader.close();
        }
        int screenHeight = ScreenMetrics.getOrientationAwareScreenHeight(orientation);
        int screenWidth = ScreenMetrics.getOrientationAwareScreenWidth(orientation);
        mImageReader = createImageReader(screenWidth, screenHeight);
        mVirtualDisplay.setSurface(mImageReader.getSurface());
        mVirtualDisplay.resize(screenWidth, screenHeight, mScreenDensity);
    }

    @Nullable
    public synchronized Image capture() {
        if (!mAvailable) {
            throw new IllegalStateException("ScreenCapturer is not available");
        }
        //按需获取最新截图，不依赖后台线程
        Image latestImage = mImageReader.acquireLatestImage();
        if (latestImage != null) {
            Image oldCached = mCachedImage.getAndSet(latestImage);
            if (oldCached != null) {
                oldCached.close();
            }
        }
        return latestImage;
    }

    /**
     * 检测projection是否仍然存活（其他app抢占MediaProjection后，MIUI不触发onStop回调，
     * 需要主动检测）。做法：排空缓冲区里可能的旧帧，再等待新帧到来。
     * live projection镜像屏幕会持续产帧，dead的排空后就没有新帧了。
     */
    public synchronized boolean checkAlive() {
        //已释放（release 后 mImageReader 置 null）直接判死，不靠下面 catch NPE 兜底
        if (!mAvailable || mImageReader == null) return false;
        try {
            //排空缓冲区里的旧帧（ImageReader最多3帧）
            for (int i = 0; i < 5; i++) {
                Image img = mImageReader.acquireLatestImage();
                if (img == null) break;
                img.close();
            }
            //等待新帧，最多2000ms（对齐AutoX.js）。转屏时VirtualDisplay会短暂暂停产帧，
            //窗口太短会误判dead，2000ms足够扛过转屏等瞬时暂停
            for (int i = 0; i < 40; i++) {
                Thread.sleep(50);
                Image img = mImageReader.acquireLatestImage();
                if (img != null) {
                    Image oldCached = mCachedImage.getAndSet(img);
                    if (oldCached != null) oldCached.close();
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public int getScreenDensity() {
        return mScreenDensity;
    }

    /**
     * 闪像素存活探测：通过 1px 悬浮窗挪动强制源屏幕重新合成一帧，据此区分"活着但静止无新帧"与"真失效/被抢占"。
     * 活的 projection 镜像会收到这帧；被抢占/已死的收不到（MIUI 抢占不触发 onStop 回调，纯等待无法区分，故用此法）。
     * 实测：活着静止约 100ms 收到帧，被抢占 800ms 也收不到。
     * @return 非 null=闪后取到的新帧(存活，可直接作为本次截图返回)；null=闪后无帧/异常(已失效)
     */
    public synchronized Image probeLivenessByBlink() {
        //已释放（release 后 mImageReader 置 null）直接判失效，说明同 checkAlive
        if (!mAvailable || mImageReader == null) return null;
        try {
            //先排空缓冲区旧帧，确保后面拿到的是"闪"之后源屏幕重合成的新帧
            for (int i = 0; i < 5; i++) {
                Image img = mImageReader.acquireLatestImage();
                if (img == null) break;
                img.close();
            }
            //闪一下：悬浮窗挪 1px → 强制真实屏幕重新合成
            BlinkProbe.nudge(mContext);
            for (int i = 0; i < 16; i++) {
                Thread.sleep(50);
                Image img = mImageReader.acquireLatestImage();
                if (img != null) {
                    Image old = mCachedImage.getAndSet(img);
                    if (old != null) old.close();
                    return img; //存活（活着但静止）
                }
            }
            return null; //闪后 800ms 仍无帧 → 已失效/被抢占
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 完整释放：先拆显示资源，再**停止 MediaProjection**。
     *
     * 关键：必须调 mMediaProjection.stop()，否则系统层面的投屏会话一直存活到进程结束——
     * 状态栏的投屏通知不消失，其他 app 也能据此判定"本机正在录屏"而拒绝提供服务
     * （部分 app 会检测录屏）。此前本方法只拆 VirtualDisplay/ImageReader，
     * 系统权限从未真正交还，只能靠别的 app 抢占 token 来被动解除。
     */
    public synchronized void release() {
        mAvailable = false;
        releaseDisplayResources();
        if (mMediaProjection != null) {
            //stop() 会触发上面注册的 onStop 回调，此时显示资源已拆完、mAvailable 已置 false，
            //回调内再走一遍 releaseDisplayResources 也是幂等的（各字段均已 null）
            mMediaProjection.stop();
        }
    }

    /**
     * 只拆与本次投屏绑定的显示资源（VirtualDisplay / ImageReader / 缓存帧），
     * 不动 MediaProjection 本身。供 {@link #release()} 与 onStop 回调共用。
     *
     * 幂等：各字段拆完置 null，重复调用无副作用。
     */
    private synchronized void releaseDisplayResources() {
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }
        Image cachedImage = mCachedImage.getAndSet(null);
        if (cachedImage != null) {
            cachedImage.close();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            release();
        } finally {
            super.finalize();
        }
    }
}
