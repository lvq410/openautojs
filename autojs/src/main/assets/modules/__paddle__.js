module.exports = function (runtime, global) {
    let paddleApi = new com.stardust.autojs.runtime.api.Paddle();
    let paddle = {}

    /**
     * OCR 识别
     * @param image 图片
     * @param cpuThreadNum 线程数，可选
     * @param useSlim 是否用精简模型，可选。也可传字符串表示自定义模型路径
     * @param expectNonEmpty 可选，调用方是否确信图上必然有文字。
     *        默认 false：识别为空视为正常，直接返回空。
     *        传 true：识别为空即视为模型疑似失效，自动重建模型后重试一次并返回重试结果。
     *        仅在调用方确实能断定「这里必然有字」时才传 true（如已确认页面加载完成、
     *        或轮询等待已到最后一次），否则会造成无谓的模型重建。
     */
    paddle.ocr = function () {
        if (!arguments[0]) return []
        let result
        switch (arguments.length) {
            case 1:
                result = paddleApi.ocr(arguments[0])
                break
            case 2:
                result = paddleApi.ocr(arguments[0], arguments[1])
                break
            case 3:
                result = paddleApi.ocr(arguments[0], arguments[1], arguments[2])
                break
            default:
                result = paddleApi.ocr(arguments[0], arguments[1], arguments[2], arguments[3])
        }
        return global.util.java.toJsArray(result)
    }

    paddle.ocrText = function (image, param2, param3) {
        if (!arguments[0]) return []
        let result
        switch (arguments.length) {
            case 1:
                result = paddle.ocr(arguments[0])
                break
            case 2:
                result = paddle.ocr(arguments[0], arguments[1])
                break
            case 3:
                result = paddle.ocr(arguments[0], arguments[1], arguments[2])
                break
            default:
                result = paddle.ocr(arguments[0], arguments[1], arguments[2], arguments[3])
        }
        return result.map(e => e.words)
    }

    // 主动释放 OCR 模型，回收 native 内存。长时间不用 OCR 时可调用，
    // 下次调 paddle.ocr() 会自动重新初始化
    paddle.release = function () {
        paddleApi.release()
    }

    // 开关小图补边（小于 360x260 的图会被补黑边至安全尺寸后再识别）。
    // 仅用于验证 native 层越界修复是否已足够，正常运行保持默认开启
    paddle.setPadEnabled = function (enabled) {
        paddleApi.setPadEnabled(!!enabled)
    }

    return paddle
}
