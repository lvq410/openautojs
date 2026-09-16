package com.baidu.paddle.lite.demo.ocr

import android.graphics.Bitmap
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.roundToInt

open class OCRPredictorNative(config: Config) {
    private var config: Config? = null

    // @Volatile：destroy() 可能在其他线程调用（如 releaseModel），
    // 必须保证 runImage 能立刻看到指针已被置 0，否则会把野指针传给 native 层
    @Volatile
    private var nativePointer: Long = 0

    /** native predictor 是否创建成功且尚未释放。init 失败时 nativePointer 为 0 */
    fun isValid(): Boolean = nativePointer != 0L

    fun runImage(
        inputData: FloatArray,
        width: Int,
        height: Int,
        channels: Int,
        originalImage: Bitmap?
    ): ArrayList<OcrResultModel> {
        lock.lock()
        return try {
            // 指针无效时直接返回空，不进 native——native 里虽也判空，但那边只能返回空数组，
            // 这里提前拦截可留下明确日志，便于事后定位"OCR 静默返回空"的根因
            if (nativePointer == 0L) {
                //PaddleLog.log("NATIVE", "runImage 时 nativePointer 为 0（未初始化或已释放），返回空结果")
                return ArrayList()
            }
            Log.i(
                "OCRPredictorNative",
                "begin to run image " + inputData.size + " " + width + " " + height
            )
            val dims = floatArrayOf(1f, channels.toFloat(), height.toFloat(), width.toFloat())
            val rawResults = forward(nativePointer, inputData, dims, originalImage)
            postprocess(rawResults)
        } finally {
            lock.unlock()
        }
    }

    class Config {
        var cpuThreadNum = 0
        var cpuPower: String? = null
        var detModelFilename: String? = null
        var recModelFilename: String? = null
        var clsModelFilename: String? = null
    }

    /**
     * 释放 native predictor。
     * 必须持有与 [runImage] 相同的锁：否则可能在推理进行中把 C++ 对象 delete 掉，
     * 造成 use-after-free（表现为闪退，或 OCR 此后永久返回空）。
     */
    fun destroy() {
        lock.lock()
        try {
            if (nativePointer != 0L) {
                val ptr = nativePointer
                nativePointer = 0  // 先置 0 再 release，确保并发的 runImage 看到的是无效指针
                release(ptr)
                //PaddleLog.log("NATIVE", "释放 native predictor ptr=0x" + java.lang.Long.toHexString(ptr))
            }
        } finally {
            lock.unlock()
        }
    }

    protected external fun init(
        detModelPath: String?,
        recModelPath: String?,
        clsModelPath: String?,
        threadNum: Int,
        cpuMode: String?
    ): Long

    protected external fun forward(
        pointer: Long,
        buf: FloatArray?,
        ddims: FloatArray?,
        originalImage: Bitmap?
    ): FloatArray

    protected external fun release(pointer: Long)
    private fun postprocess(raw: FloatArray): ArrayList<OcrResultModel> {
        val results = ArrayList<OcrResultModel>()
        var begin = 0
        while (begin < raw.size) {
            val pointNum = raw[begin].roundToInt()
            val wordNum = raw[begin + 1].roundToInt()
            val model = parse(raw, begin + 2, pointNum, wordNum)
            begin += 2 + 1 + pointNum * 2 + wordNum
            results.add(model)
        }
        return results
    }

    private fun parse(raw: FloatArray, begin: Int, pointNum: Int, wordNum: Int): OcrResultModel {
        var current = begin
        val model = OcrResultModel()
        model.confidence = raw[current]
        current++
        for (i in 0 until pointNum) {
            model.addPoints(raw[current + i * 2].roundToInt(),
                raw[current + i * 2 + 1].roundToInt()
            )
        }
        current += pointNum * 2
        for (i in 0 until wordNum) {
            val index = raw[current + i].roundToInt()
            model.addWordIndex(index)
        }
        Log.i("OCRPredictorNative", "word finished $wordNum")
        return model
    }

    // 不再重写 finalize：
    // finalize 由 GC 的 finalizer 线程调用，时机不可控，且与推理线程并发时可能
    // 把正在使用的 native 对象 delete 掉（destroy 虽已加锁，但对象仍可能在
    // 「Kotlin 引用仍在、native 已被回收」的窗口里被误用）。
    // 生命周期改由 Predictor.releaseModel() 显式管理——只要不再有重复创建、
    // 每个实例都能被显式 destroy，就不需要 finalizer 兜底。

    companion object {
        private val isSOLoaded = AtomicBoolean()
        private val lock = ReentrantLock()
        @Throws(RuntimeException::class)
        fun loadLibrary() {
            if (!isSOLoaded.get() && isSOLoaded.compareAndSet(false, true)) {
                try {
                    System.loadLibrary("Native")
                } catch (e: Throwable) {
                    throw RuntimeException(
                        "Load libNative.so failed, please check it exists in apk file.", e
                    )
                }
            }
        }
    }

    init {
        lock.lock()
        try {
            this.config = config
            loadLibrary()
            nativePointer = init(
                config.detModelFilename, config.recModelFilename, config.clsModelFilename,
                config.cpuThreadNum, config.cpuPower
            )
            Log.i("OCRPredictorNative", "load success $nativePointer")
            // native init 返回 0 表示三个子模型（det/rec/cls）中至少一个创建失败。
            // 原实现不检查此返回值，坏掉的 predictor 会被当成可用，此后每次 OCR 静默返回空。
            if (nativePointer == 0L) {
                //PaddleLog.log(
                    //"INIT",
                    //"native init 失败（返回空指针），模型不可用. det=" + config.detModelFilename
                            //+ " thread=" + config.cpuThreadNum + " freeMem=" + PaddleLog.freeMemMB() + "MB"
                //)
            } else {
                //PaddleLog.log(
                    //"INIT",
                    //"native predictor 创建成功 ptr=0x" + java.lang.Long.toHexString(nativePointer)
                            //+ " thread=" + config.cpuThreadNum + " freeMem=" + PaddleLog.freeMemMB() + "MB"
                //)
            }
        } finally {
            lock.unlock()
        }
    }
}