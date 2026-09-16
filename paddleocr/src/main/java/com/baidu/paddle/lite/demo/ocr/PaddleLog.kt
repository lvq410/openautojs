package com.baidu.paddle.lite.demo.ocr

import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * PaddleOCR 诊断日志（异步写本地文件）。
 *
 * 背景：paddle OCR 存在低概率"图片里明明有字却识别不出来（返回空列表）"的问题，
 * 需要长时间运行后才能复现，logcat 无法长期保留，故落盘到文件事后分析。
 *
 * 位置固定为 [LOG_DIR]（`/sdcard/Lvt4AJs/`）——不用 app 私有的
 * `getExternalFilesDir()`，因为 Android 11+ 的分区存储下 `Android/data/` 既无法用
 * adb 读取、也无法用文件管理器查看，日志写了也取不出来。
 *
 * 异步：[log] 只做「格式化时间戳 + 入队」，随即返回，不做任何 IO，不阻塞 OCR 主流程；
 * 实际落盘由后台守护线程串行完成，并做批量聚合（一次唤醒尽量多写几行再 flush）。
 * 队列满时直接丢弃新日志（见 [MAX_QUEUE]）——日志是诊断辅助，任何情况下都不能拖慢或拖垮主流程。
 *
 * 容量控制：单文件超过 [MAX_SIZE] 时轮转为 `.1`（只保留一份备份），避免长期运行撑爆存储。
 */
object PaddleLog {

    private const val TAG = "PaddleLog"

    /** 日志目录。放外部存储根目录下，adb 和文件管理器可直接访问。与应用名 Lvt4AJs 对应 */
    private const val LOG_DIR = "Lvt4AJs"

    /** 日志文件名 */
    private const val LOG_NAME = "paddle.log"

    /** 单个日志文件大小上限，超过则轮转。2MB 约可存 2 万行 */
    private const val MAX_SIZE = 2 * 1024 * 1024L

    /** 待写队列上限。超出后丢弃新日志，宁可丢日志也不阻塞 OCR */
    private const val MAX_QUEUE = 4096

    /** 后台线程空闲多久后关闭输出流（毫秒），避免长期持有 fd */
    private const val IDLE_CLOSE_MS = 5000L

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    private val queue = LinkedBlockingQueue<String>(MAX_QUEUE)

    /** 因目录不可写等原因彻底禁用文件日志后置 true，此后 [log] 只打 logcat */
    @Volatile
    private var disabled = false

    /** 因队列满而丢弃的日志条数，恢复写入时输出一行提示，避免"日志断档"被误读为无事发生 */
    @Volatile
    private var droppedCount = 0L

    private val writerThread: Thread by lazy {
        Thread({ writeLoop() }, "PaddleLog-writer").apply {
            isDaemon = true      // 守护线程，不阻止进程退出
            priority = Thread.MIN_PRIORITY  // 最低优先级，不与 OCR 抢 CPU
            start()
        }
    }

    /**
     * 写一行日志（异步，立即返回）。
     * @param tag 分类标签，如 INIT/OCR/EMPTY/HEAL/RELEASE，便于事后 grep 过滤
     * @param msg 日志内容
     */
    @JvmStatic
    fun log(tag: String, msg: String) {
        Log.i(TAG, "[$tag] $msg")
        enqueue(timeFormat.format(Date()) + " [" + tag + "] " + msg)
    }

    /**
     * 写一行日志并附带异常堆栈（异步，立即返回）。
     * 堆栈在调用线程展开为字符串——这是纯内存操作，开销远小于 IO。
     */
    @JvmStatic
    fun log(tag: String, msg: String, e: Throwable) {
        Log.e(TAG, "[$tag] $msg", e)
        val sw = StringWriter()
        PrintWriter(sw).use { e.printStackTrace(it) }
        enqueue(timeFormat.format(Date()) + " [" + tag + "] " + msg + "\n" + sw.toString().trimEnd())
    }

    /**
     * 当前可用堆内存（MB）。OCR 返回空结果时一并记录，
     * 用于验证"内存不足导致模型创建失败"的推测。
     */
    @JvmStatic
    fun freeMemMB(): Long {
        val rt = Runtime.getRuntime()
        // maxMemory - (total - free) 才是真正还能再分配的量
        return (rt.maxMemory() - rt.totalMemory() + rt.freeMemory()) / 1024 / 1024
    }

    /** 入队。队列满不阻塞、直接丢弃并计数——日志绝不拖慢主流程 */
    private fun enqueue(line: String) {
        if (disabled) return
        writerThread // 触发 lazy 初始化，启动后台线程
        if (!queue.offer(line)) droppedCount++
    }

    /** 后台写线程主循环：阻塞取一条 → 批量取尽队列中已有的 → 一次性写出并 flush */
    private fun writeLoop() {
        var writer: PrintWriter? = null
        while (true) {
            try {
                // 阻塞等待，空闲超时则关流释放 fd，下次有日志再重开
                val first = queue.poll(IDLE_CLOSE_MS, TimeUnit.MILLISECONDS)
                if (first == null) {
                    writer?.close()
                    writer = null
                    continue
                }
                val file = ensureFile()
                if (file == null) {
                    // 目录不可写，已在 ensureFile 内置 disabled，清空积压后退出循环
                    queue.clear()
                    writer?.close()
                    return
                }
                if (file.length() > MAX_SIZE) {
                    writer?.close()
                    writer = null
                    rotate(file)
                }
                if (writer == null) writer = PrintWriter(FileWriter(file, true))

                if (droppedCount > 0) {
                    writer.println(timeFormat.format(Date()) + " [DROP] 队列积压，已丢弃 " + droppedCount + " 条日志")
                    droppedCount = 0
                }
                writer.println(first)
                // 批量写出此刻队列中已积压的其余日志，减少 flush 次数
                while (true) {
                    val next = queue.poll() ?: break
                    writer.println(next)
                }
                writer.flush()
            } catch (e: Throwable) {
                // 写失败不能让线程死掉：关流、丢弃积压，下轮重试；连目录都没了会走 ensureFile 的 disabled 分支
                Log.e(TAG, "写日志失败", e)
                try {
                    writer?.close()
                } catch (ignore: Throwable) {
                }
                writer = null
                queue.clear()
            }
        }
    }

    private fun ensureFile(): File? {
        val dir = File(Environment.getExternalStorageDirectory(), LOG_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            // 目录建不出来（无权限等），禁用文件日志，只保留 logcat
            disabled = true
            Log.e(TAG, "日志目录创建失败: " + dir.absolutePath)
            return null
        }
        return File(dir, LOG_NAME)
    }

    /** 轮转：把当前日志改名为 .1（覆盖旧备份），原文件由下次写入重新创建 */
    private fun rotate(file: File) {
        val backup = File(file.parentFile, "$LOG_NAME.1")
        if (backup.exists()) backup.delete()
        file.renameTo(backup)
    }
}
