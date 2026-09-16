package com.stardust.autojs.runtime.api

import android.content.Context
import com.baidu.paddle.lite.demo.ocr.OcrResult
import com.baidu.paddle.lite.demo.ocr.PaddleLog
import com.baidu.paddle.lite.demo.ocr.Predictor
import com.stardust.app.GlobalAppContext.get
import com.stardust.autojs.core.image.ImageWrapper

/**
 * PaddleOCR 的脚本层入口（对应 JS 的 `paddle.ocr(...)`）。
 *
 * 已知问题背景：长时间运行后会低概率出现「图片里明明有字，OCR 却返回空列表」，
 * 重启脚本或在应用间来回切换即可恢复。根因是模型侧的 predictor 已失效但无人感知
 * （详见 Predictor.loadModel / OCRPredictorNative 中的注释）。
 * 因此这里除了正常调用外，还支持调用方声明 `expectNonEmpty` 来触发失效重建，
 * 并把全过程写入 [PaddleLog]，便于长期运行后回溯分析。
 *
 * **模型全局共享**：[Predictor] 实例挂在伴生对象上，为整个进程共用。
 * 原先每个脚本引擎各 `new Paddle()` 并各持一个 Predictor，导致：
 * 1. 每个用 OCR 的脚本都要各自加载一整套 det/rec/cls 模型（native 内存成倍占用）
 * 2. 每次脚本重启都重新初始化一遍（实测日志中 13 次独立 INIT）
 * 3. 脚本引擎销毁时无人调 `releaseModel()`，旧 Predictor 的 native 内存只能等 GC；
 *    而 `OCRPredictorNative.finalize()` 已被移除（它与推理存在竞态），于是彻底泄漏
 * 改为全局单例后，模型只加载一次、被所有脚本共用，上述三点一并消除。
 *
 * 线程安全：多个脚本可能并发调用 OCR。推理本身由 `OCRPredictorNative.runImage` 的
 * ReentrantLock 串行化；此处额外用 [lock] 保护「初始化 / 重建」这类会替换模型实例的操作，
 * 避免一个脚本正重建模型时另一个脚本拿到半初始化的实例。
 */
class Paddle {

    private val availableProcessors = Runtime.getRuntime().availableProcessors()

    private fun initOcr(context: Context, cpuThreadNum: Int, useSlim: Boolean) {
        synchronized(lock) { predictor.initOcr(context, cpuThreadNum, useSlim) }
    }

    private fun initOcr(context: Context, myModelPath: String): Boolean {
        return synchronized(lock) { predictor.init(context, myModelPath) }
    }

    /**
     * @param expectNonEmpty 调用方是否**确信**这张图上有可识别文字。
     *   - false（默认）：结果为空视为正常，直接返回空。等同于不带此参数时的行为
     *   - true：结果为空即视为异常信号，判定模型疑似失效，重建模型后重试一次并返回重试结果
     *
     *   之所以交给调用方声明：一次 OCR 该不该有结果，只有调用方清楚
     *   （例如「已确认打开了某页面，标题处必然有字」，或「轮询等待元素已到最后一次」）。
     *   框架侧无论按次数还是按时间去猜，都会在「刚开始轮询」「长期空闲后重新运行」等
     *   正常场景下误判。
     */
    @JvmOverloads
    fun ocr(
        image: ImageWrapper,
        cpuThreadNum: Int = availableProcessors,
        useSlim: Boolean = true,
        expectNonEmpty: Boolean = false
    ): List<OcrResult> {
        val bitmap = image.bitmap
        if (bitmap == null || bitmap.isRecycled) {
            // 传入图片已被回收是脚本侧的问题（如提前 recycle 了截图），
            // 记下来以便与「模型失效」区分开——两者现象都是返回空列表
//            PaddleLog.log(
//                "ARG",
//                "输入图片不可用（bitmap=" + (bitmap != null) + " recycled="
//                        + (bitmap?.isRecycled ?: true) + "），返回空结果"
//            )
            return emptyList()
        }
        // 首次使用时加载模型。加锁并在锁内二次判断，避免多个脚本并发时各初始化一次
        if (!predictor.isLoaded()) {
            synchronized(lock) {
                if (!predictor.isLoaded()) initOcr(get(), cpuThreadNum, useSlim)
            }
        }
        val result = predictor.runOcr(bitmap, cpuThreadNum)
        if (result.isNotEmpty()) return result

        // 模型明确不可用（native 创建失败或已被释放）——与调用方预期无关的确定性故障，一律重建
        if (!predictor.isLoaded()) {
//            PaddleLog.log("HEAL", "空结果且模型已不可用，重建模型. freeMem=" + PaddleLog.freeMemMB() + "MB")
            return rebuildAndRetry(bitmap, cpuThreadNum, useSlim)
        }

        // 调用方未声明「必然有结果」时，空结果属正常，零额外开销直接返回
        if (!expectNonEmpty) return emptyList()

        // 调用方确信应有结果却识别为空——疑似模型失效
        val now = System.currentTimeMillis()
        if (now - lastRebuildTime < MIN_REBUILD_INTERVAL_MS) {
            // 刚重建过仍为空，多半是调用方预期有误（页面没真正加载出来等），
            // 此时继续重建只会拖慢脚本，故跳过
//            PaddleLog.log(
//                "HEAL",
//                "预期有结果却为空，但距上次重建仅 " + ((now - lastRebuildTime) / 1000) + "s，跳过本次重建"
//            )
            return emptyList()
        }
//        PaddleLog.log(
//            "HEAL",
//            "调用方预期有结果却识别为空，判定模型疑似失效并重建. freeMem=" + PaddleLog.freeMemMB() + "MB"
//        )
        return rebuildAndRetry(bitmap, cpuThreadNum, useSlim)
    }

    /**
     * 彻底释放并重建模型后重试一次。
     * 这等价于「重启脚本」——那正是此前手动恢复该问题的办法，现在进程内自动完成。
     *
     * 加锁：模型为全局共享，重建期间会短暂处于「已释放、未加载」状态，
     * 必须防止其他脚本此刻并发进入而拿到不可用的实例。
     */
    private fun rebuildAndRetry(
        bitmap: android.graphics.Bitmap,
        cpuThreadNum: Int,
        useSlim: Boolean
    ): List<OcrResult> {
        synchronized(lock) {
            // 双重检查：等锁期间可能已被其他线程重建好，此时直接重试即可，不必再建一次
            val now = System.currentTimeMillis()
            if (now - lastRebuildTime < MIN_REBUILD_INTERVAL_MS && predictor.isLoaded()) {
                val r = predictor.runOcr(bitmap, cpuThreadNum)
                //PaddleLog.log("HEAL", "等锁期间模型已由其他线程重建，直接重试得到 " + r.size + " 条结果")
                return r
            }
            lastRebuildTime = now

            predictor.releaseModel()
            val ok = predictor.initOcr(get(), cpuThreadNum, useSlim)
            if (!ok) {
                //PaddleLog.log("HEAL", "模型重建失败，本次 OCR 放弃. freeMem=" + PaddleLog.freeMemMB() + "MB")
                return emptyList()
            }
            val retryResult = predictor.runOcr(bitmap, cpuThreadNum)
            //PaddleLog.log("HEAL", "模型重建完成，重试 OCR 得到 " + retryResult.size + " 条结果")
            return retryResult
        }
    }

    fun ocr(
        image: ImageWrapper,
        cpuThreadNum: Int,
        myModelPath: String
    ): List<OcrResult> {

        val bitmap = image.bitmap
        if (bitmap == null || bitmap.isRecycled) {
            //PaddleLog.log("ARG", "输入图片不可用（自定义模型路径），返回空结果")
            return emptyList()
        }
        // 同上：锁内二次判断，避免并发重复初始化。
        // 注意模型全局共享，若某个脚本用自定义模型路径，会影响后续所有脚本用到的模型——
        // 混用内置模型与自定义模型的场景需自行调 release() 切换
        if (!predictor.isLoaded()) {
            synchronized(lock) {
                if (!predictor.isLoaded()) initOcr(get(), myModelPath)
            }
        }
        return predictor.runOcr(bitmap, cpuThreadNum)
    }

    fun ocr(image: ImageWrapper, useSlim: Boolean): List<OcrResult> {
        return ocr(image, availableProcessors, useSlim)
    }

    fun ocr(image: ImageWrapper, myModelPath: String): List<OcrResult> {
        return ocr(image, availableProcessors, myModelPath)
    }

    /**
     * 主动释放 OCR 模型，回收 native 内存。对应 JS 的 `paddle.release()`。
     *
     * 注意：模型为全局共享，此调用会影响所有脚本——其他脚本下次 OCR 时会自动重新加载，
     * 功能上无碍，但会多付一次约 30ms 的初始化。仅在确实需要回收内存时调用。
     */
    fun release() {
        synchronized(lock) {
            //PaddleLog.log("RELEASE", "脚本主动释放 OCR 模型（全局共享，影响所有脚本）")
            predictor.releaseModel()
            lastRebuildTime = 0
        }
    }

    /**
     * 开关小图补边（仅用于验证 native 越界修复是否已足够，正常运行请保持开启）。
     * 关闭后小于安全尺寸的图会原样送入模型——若 native 层仍有未修复的越界点，进程会崩溃。
     */
    fun setPadEnabled(enabled: Boolean) {
        Predictor.padEnabled = enabled
        //PaddleLog.log("PAD", "小图补边开关设为 " + enabled)
    }

    companion object {
        /**
         * 全局共享的 OCR 模型。整个进程只加载一份，被所有脚本引擎共用。
         * 不随脚本启停而重建——脚本引擎销毁时不应释放它，否则其他脚本会受影响。
         */
        private val predictor = Predictor()

        /** 保护「初始化 / 重建 / 释放」这类会替换模型状态的操作。推理本身由 native 层的锁串行化 */
        private val lock = Any()

        /** 上次重建模型的时间戳，用于限制重建频率，避免调用方误判时反复重建 */
        @Volatile
        private var lastRebuildTime = 0L

        /**
         * 两次自动重建之间的最小间隔。
         * 防止调用方 `expectNonEmpty=true` 的预期本身有误（页面实际没加载出来等）时，
         * 每次空结果都触发一次重建拖慢脚本。真失效时重建一次即可恢复，故该间隔不影响自愈效果。
         */
        const val MIN_REBUILD_INTERVAL_MS = 60 * 1000L
    }

}
