# OpenAutoJS (fork)

## 背景 / 需求来源
- **Auto.js** 早已停止维护并全网下架。有人基于它做了开源的 **OpenAutoJS**，但同样已 2~3 年无人维护——好在它开源、有完整源码，可自行改造。
- 本 fork 的两个目标：
  1. **迁移兼容**：把既有的 AutoxScripts 自动化脚本从 Auto.js 迁到 OpenAutoJS，因此需修复 OpenAutoJS 与 Auto.js 不兼容之处（见「相对 AutoX.js 的本地修复记录」）。
  2. **按需增强**：在其上叠加自用功能（如悬浮小球运行时隐藏、悬浮球默认点击行为配置、横屏布局修复等，见「新增功能」）。

## 项目概况
- 包名: `com.lvt4j.ajs`（已从 `org.openautojs.autojs` 改为自用）
- 应用名: `Lvt4AJs`
- 版本配置: `project-versions.json`
- 签名配置: `app/build.gradle.kts` 中硬编码了原作者的路径 `E:/资料/jks/autojs-app/sign.properties`，不存在时自动跳过，debug 包用默认签名。打 release 签名包需自行创建 jks 和 sign.properties 并修改该路径

## 编译环境
- Gradle: 7.5（`D:/gradle/gradle-7.5`）
- JDK: 15（`D:/Java/jdk-15.0.1`）
- Android SDK: `C:/Users/chanceylee/AppData/Local/Android/Sdk`（通过 `local.properties` 配置）
- 需要 `-Xmx4g` 避免 OOM

## 打包命令
```bash
cd D:/Workspace4Course/mine/openautojs
JAVA_HOME="D:/Java/jdk-15.0.1" GRADLE_USER_HOME="D:/.gradle" \
  "D:/gradle/gradle-7.5/bin/gradle" :app:assembleDebug -Dorg.gradle.jvmargs="-Xmx4g"
```
产物路径: `app/build/outputs/apk/common/debug/`
- `app-common-universal-debug.apk`（通用）
- `app-common-arm64-v8a-debug.apk`
- `app-common-armeabi-v7a-debug.apk`

Release 包将 `assembleDebug` 换成 `assembleRelease`，需要签名文件存在。

## 包名修改要点
applicationId 和源码包路径是独立的，改包名只需改 `applicationId`，不用动源码目录。但注意：
- `app/build.gradle.kts` 中 `resourcePackageName` 必须固定为 `org.openautojs.autojs`（manifest package），不能跟随 applicationId，否则 kapt 找不到 R 类
- proguard 规则中的 BuildConfig 类名要跟随 applicationId
- 应用显示名在 `app/src/main/res-i18n/values/strings.xml` 的 `app_name`

## 依赖说明
- `RootShell` 原来从 JitPack 拉取（`com.github.Stericson:RootShell:1.6`），但 JitPack 上已 404，改为本地模块 `LocalRepo/RootShell/`（从 GitHub 源码编译的 AAR）

## AutoX.js 反编译参考
`autoxjs-decompiled/` 和 `autoxjs-decompiled-dex5/` 目录存放了 AutoX.js v6 6.5.8 APK 的反编译代码，供对比参考。
- `autoxjs-decompiled/` — classes3.dex（核心业务类：Images、ScreenCapturer、ScreenCaptureManager 等）
- `autoxjs-decompiled-dex5/` — classes5.dex（Rhino JS 引擎：Context、ScriptRuntime、NativeGlobal 等）

## 相对 AutoX.js 的本地修复记录

以下为对比 AutoX.js 后发现并修复的兼容性问题：

### 1. Rhino WrapFactory 未在 makeContext 中设置（Java String 被包装为 NativeJavaObject）
- **现象**: Java 回调传入 JS 的 String 参数无法用 `eval()` 执行，`==` 比较也失败
- **根因**: `InterruptibleAndroidContextFactory.makeContext()` 未设置 WrapFactory。Rhino 默认 `javaPrimitiveWrap=true`，会将 String 包装为 NativeJavaObject（不是 CharSequence），导致 eval 直接返回原值
- **AutoX.js**: 在 `AndroidContextFactory.makeContext()` → `setupContext()` 中设置了自定义 WrapFactory
- **修复**: `InterruptibleAndroidContextFactory.java` 的 `makeContext()` 中设置 WrapFactory，对 String/Number/Boolean 直接返回不包装
- **文件**: `autojs/src/main/java/com/stardust/autojs/rhino/InterruptibleAndroidContextFactory.java`

### 2. 截屏权限不跨引擎共享（每个脚本都弹授权）
- **现象**: 每次启动新脚本都弹 MediaProjection 权限对话框
- **根因**: OpenAutoJS 的 `mScreenCapturer` 存在每个引擎的 `Images` 实例上，不共享。AutoX.js 通过 `ScreenCaptureManager`（实现 `ScreenCaptureRequester` 接口）在全局共享 ScreenCapturer
- **修复**: 将 `mScreenCapturer` 从 `Images` 移到共享的 `ScreenCaptureRequester.AbstractScreenCaptureRequester`，添加 `getScreenCapturer()`/`setScreenCapturer()` 接口方法。`Images.releaseScreenCapturer()` 不再释放共享的 capturer
- **文件**: `autojs/src/main/java/com/stardust/autojs/core/image/capture/ScreenCaptureRequester.java`, `autojs/src/main/java/com/stardust/autojs/runtime/api/Images.java`

### 3. requestScreenCapture 重复调用时 Promise.wait 死锁
- **现象**: 已有截屏权限时调用 `requestScreenCapture()` 永不返回
- **根因**: Java 端同步 resolve ScriptPromiseAdapter 后，JS 的 `Promise.prototype.wait()` 注册的 `.then()` 回调是微任务，需要事件循环处理，但 `blockedGet()` 已阻塞当前线程 → 死锁
- **修复**: `Images.requestScreenCapture()` 返回类型改为 `Object`。已有 capturer 时直接返回 `Boolean.TRUE`（绕过 Promise）；JS 端 `__images__.js` 判断返回值类型，非 Promise 直接返回
- **文件**: `autojs/src/main/java/com/stardust/autojs/runtime/api/Images.java`, `autojs/src/main/assets/modules/__images__.js`

### 4. ScreenCapturer.refreshVirtualDisplay 在 Android 14+ 崩溃
- **现象**: 切换屏幕方向时报 `SecurityException: Cannot create VirtualDisplay with non-current MediaProjection`
- **根因**: OpenAutoJS 的 `refreshVirtualDisplay` 释放旧 VirtualDisplay 再用 MediaProjection 重建，Android 14+ 不允许用"非当前"的 MediaProjection 创建新 VirtualDisplay。AutoX.js 复用已有 VirtualDisplay，只替换 Surface + resize
- **修复**: `refreshVirtualDisplay` 改为复用 VirtualDisplay：`mVirtualDisplay.setSurface(newSurface)` + `mVirtualDisplay.resize(...)`
- **文件**: `autojs/src/main/java/com/stardust/autojs/core/image/capture/ScreenCapturer.java`

### 5. ScreenCapturer 依赖引擎 Handler/Looper，跨引擎后失效
- **现象**: 第一个脚本退出后，共享的 ScreenCapturer 的图像获取失效（`Image is already closed`）
- **根因**: OpenAutoJS 的 `capture()` 通过后台线程 + Handler 异步缓存图片，Handler 绑定首个引擎的 servantLooper，引擎退出后 looper 回收。AutoX.js 的 `capture()` 直接调 `acquireLatestImage()` 按需获取
- **修复**: 重写 ScreenCapturer，`capture()` 改为直接 `mImageReader.acquireLatestImage()`，不依赖后台线程。`Images.captureScreen()` 加短暂重试处理首帧未就绪
- **文件**: `autojs/src/main/java/com/stardust/autojs/core/image/capture/ScreenCapturer.java`, `autojs/src/main/java/com/stardust/autojs/runtime/api/Images.java`

### 6. floaty.rawWindow 在 UI 线程调用时死锁
- **现象**: 在 "ui" 模式脚本中通过 `ui.run()` 创建 `floaty.rawWindow` 导致 ANR
- **根因**: `JsRawWindow` 构造函数无条件将 `FloatyService.addWindow` post 到 UI Handler，然后调 `waitForCreation()` 阻塞当前线程。若已在 UI 线程，post 的 Runnable 排在当前任务之后，而 `waitForCreation()` 阻塞了 UI 线程 → 死锁。AutoX.js 检查 `Looper.myLooper() == MainLooper`，UI 线程上直接执行不 post
- **修复**: `JsRawWindow` 构造函数中判断当前线程，UI 线程直接执行 `addWindow`，非 UI 线程才 post + waitForCreation
- **文件**: `autojs/src/main/java/com/stardust/autojs/runtime/api/Floaty.java`

### 7. UiObjectCollection 缺少 forEach/filter/map（脚本层面）
- **现象**: `selector().find().forEach(...)` 报错 `Cannot find function forEach`
- **根因**: OpenAutoJS 的 `UiObjectCollection` 只有 `each()`/`get()`/`size()` 方法，没有 JS 数组方法。WrapFactory 中有 `asArray` 转换逻辑但未被触发（同问题1，`javaToJS` 短路）
- **修复**: 脚本中 `forEach` 改为 `each()`（仅改了 `common.js`，企微机器人.js 待改）
- **文件**: `D:\Workspace4Course\mine\AutoxScripts\common.js`

### 8. 其他 app 抢占截屏权限后脚本卡死（MIUI onStop 不触发）
- **现象**: 其他 app 获取 MediaProjection 后，共享的 ScreenCapturer 已失效但重启脚本时无授权弹窗、无悬浮窗、脚本卡死
- **根因**: MIUI 上 MediaProjection 被系统作废后 `onStop` 回调可能不触发，`mAvailable` 仍为 true，`requestScreenCapture` 误判 capturer 可用；`captureScreen` 又会一直重试导致连锁卡住
- **修复**: 新增 `ScreenCapturer.checkAlive()`——排空缓冲区旧帧后等待新帧（最多 2000ms），live projection 持续产帧、dead 的排空后无新帧，据此判活。`requestScreenCapture` 已有 capturer 时先 `checkAlive()`：存活则复用，失效则 `release()` 并返回 `Boolean.FALSE`，脚本据此提示「没有授予屏幕截图权限」后退出/重新申请（对齐 AutoX.js 的失败即退出行为）。`captureScreen` 超时也清掉共享 capturer 并抛异常，脚本侧 `captureScreenx` 捕获后重新申请权限
- **文件**: `core/image/capture/ScreenCapturer.java`, `runtime/api/Images.java`；脚本侧 `common.js`（`requestCapturePermission`/`captureScreenx`）
- **说明**: `checkAlive()` 仍用于 `requestScreenCapture` 已有 capturer 时的复用判断。但本条为 `captureScreen` 引入的「超时即销毁 capturer + 重新授权」会在静止画面下误弹授权（详见 #13），该超时销毁逻辑已被 #13 的 blink 判活死方案取代

### 9. TemplateMatching 找图 Mat 泄漏（ResourceMonitor 刷屏）
- **现象**: 脚本找图后日志狂刷 `ResourceMonitor$UnclosedResourceException: resource = Mat [...]`，不影响运行但刷屏
- **根因**: `TemplateMatching.fastTemplateMatching` 金字塔循环里，早退 `break`（`!shouldContinueMatching`）在释放 `src`/`currentTemplate` 之前，导致这两个金字塔 Mat 泄漏。此路径是常见退出路径，故几乎每次找图都漏
- **修复**: `break` 前先 `OpenCVHelper.release(src/currentTemplate)`
- **文件**: `core/image/TemplateMatching.java`
- **说明**: AutoX.js 同有此泄漏，但未启用 ResourceMonitor 抛异常检测故不刷屏；本 fork 保留了检测（有用），改为根治泄漏

### 10. 非脚本线程 setTimeout 丢失（getTimerForThread 返回 null）
- **现象**: websocket 断线后 `onClosed`/`onFailure` 里的 `setTimeout(重连, 5000)` 从不触发，不自动重连
- **根因**: `Timers.getTimerForThread` 对「非主线程、非脚本 TimerThread」的线程（如 OkHttp/WebSocket 回调线程）返回 `null`，其上 `setTimeout` 直接 NPE、回调被静默丢弃
- **修复**: 对齐 AutoX.js，这种情况回退到 `mMainTimer`，由主 looper 调度执行
- **文件**: `runtime/api/Timers.java`

### 11. GlobalActionAutomator.scaleY 误用 scaleX（Y 坐标缩放错）
- **现象**: 用了 `setScreenMetrics(设计宽,设计高)` 且设计宽高比≠设备时，手势/点击的 Y 坐标偏
- **根因**: `GlobalActionAutomator.scaleY(y)` 内部调 `ScreenMetrics.scaleX(y)`，给 Y 用了宽度比例
- **修复**: 改用 `ScreenMetrics.scaleY(y)`（高度比例）。对不用 setScreenMetrics 的脚本无影响（缩放比=1）
- **文件**: `automator/.../GlobalActionAutomator.kt`
- **说明**: AutoX.js 同有此 bug。因唯一用 setScreenMetrics 的脚本(企微机器人.js)已弃用，改之无回归

### 12. UiObjectCollection.performAction(int, args) 成功/失败逻辑反转
- **现象**: 在控件**集合**上调带参动作（如 `setText`）时，返回值与实际相反（成功却返回 false）；动作本身照常执行，仅返回值错
- **根因**: 带参重载里 `if (succeed) fail = true` 写反了（应 `if (!succeed)`）
- **修复**: 改为 `if (!succeed) fail = true`
- **文件**: `automator/.../UiObjectCollection.kt`
- **说明**: AutoX.js 同有此 bug；仅影响集合带参动作的返回值，改之基本无回归

### 13. 静止画面偶发误弹截屏授权（blink 判活死）
- **现象**: 脚本运行中低概率弹出截屏授权对话框（无其他 app 抢占、无转屏时也弹）；页面「无变化元素」的静止画面更易触发
- **根因**: 静止画面下镜像 VirtualDisplay 不再产新帧，`acquireLatestImage()` 持续返回 null；#8 为 `captureScreen` 引入的「超时即 release + 重授权」把这误判为 projection 失效。而「活着但静止」与「被抢占已死」现象完全相同——MIUI 抢占既不触发 `onStop` 也不触发 `VirtualDisplay.Callback`，纯等待（`checkAlive`）或 `setSurface/resize`（只动消费端、撬不动源屏幕重合成）都无法区分（均已实测否定）
- **修复**:
  - `capture()` 无新帧且已有缓存时，信任窗口 `ALIVE_TRUST_MS`(1s) 内直接秒回上一帧（静止时旧帧即当前画面、准确），不再超时销毁
  - 超过信任窗口才用 `BlinkProbe` 挪动 1px 悬浮窗强制**源屏幕**重新合成一帧：活的 projection 镜像约 100ms 收到该帧、被抢占的 800ms 也收不到，据此判活死。存活→用该帧作为本次截图返回、不弹窗、刷新存活时间；失效→`release()` 重新授权
  - 关键：重授权前**必先 blink**，故活着绝不误弹（零误判）；真失效/被抢占约 1s 后自动恢复
- **文件**: `core/image/capture/BlinkProbe.java`（新增，1px 悬浮窗）、`core/image/capture/ScreenCapturer.java`（`probeLivenessByBlink()`）、`runtime/api/Images.java`（`captureScreen` 判定 + `mLastAliveTime`/`ALIVE_TRUST_MS`）
- **说明**: 悬浮窗 1px、屏幕左上角、alpha 8/255 肉眼不可见（圆角屏更看不到），app 已具悬浮窗权限（悬浮小球）。实测一天 11 次静止误判全被拦下、零弹窗，抢占约 1s 恢复。本方案取代 #8 的 `captureScreen` 超时销毁逻辑

### 14. PaddleOCR 低概率永久失效（有字却识别为空）+ 小图必崩
- **现象一（失效）**: 长时间运行后低概率出现「图片里明明有字，`paddle.ocr()` 却返回空数组」，且此后持续为空；重启脚本、或在 AutoJS 与其他 app 间切换几次即可恢复。脚本侧的两次重试防不住（因为不抛异常），logcat 也无任何报错
- **现象二（崩溃）**: 传入宽高小于约 360x260 的图必崩（AutoX.js 时期即已实测，与是否有字无关），栈顶为 `libc __memset_aarch64` ← `libpaddle_light_api_shared.so`
- **根因**:
  - **现象二**：`ocr_ppredictor.cpp` 的 `calc_filtered_boxes` 里 `memcpy(pred_map.data, pred, pred_size * sizeof(float))` 隐含假设「det 输出元素数 == 输入图宽高之积」。小图时不成立（det 有下采样与最小特征图约束，输出反而多于按输入算出的 `pred_map` 容量），于是写穿 `cv::Mat` 堆缓冲区、破坏堆元数据，随后在任意一次分配/清零时才崩（`cv::Mat::zeros` 内部用 memset，故栈顶是 memset）。**每次小图 OCR 都在破坏堆，是否「立刻」崩只取决于堆布局运气**
  - **现象一**（多个缺陷叠加，按影响排序）:
    1. **每次 OCR 都在重建整个模型**：`Predictor.initOcr(ctx,thread,useSlim)` 漏了 `isLoaded = true`（另外三个 `init` 重载都有），而 `loadModel` 开头的 `releaseModel()` 会把 `isLoaded` 置 false 且结束时不设回。于是 `isLoaded()` 恒为 false → `Paddle.ocr` 的 `if (!predictor.isLoaded())` 每次都成立 → 每调一次 `paddle.ocr()` 就完整销毁+重新复制 3 个 `.nb` 模型文件+重建 det/rec/cls 一整套。反复 create/destroy paddle-lite predictor 累积劣化，最终某次创建失败后永久返回空
    2. **每次 loadModel 泄漏一整套 native 模型**：`loadModel` 创建了 `paddlePredictor` 和 `mPaddlePredictorNative` 两个 `OCRPredictorNative` 实例各加载一整套模型，但推理只用前者、`releaseModel()` 也只释放前者，后者直接被覆盖丢弃。叠加缺陷 1 后，**每次 OCR 泄漏一整套模型的 native 内存**
    3. **失败路径全部静默返回空、无一处抛异常**（脚本侧 try/catch 重试因此完全无效）：`PPredictor::_init` 不检查 `CreatePaddlePredictor` 返回值、无条件返回 `RETURN_OK`；`OCR_PPredictor::init_from_file` 忽略三个子模型的返回值；`native.cpp` 的 JNI `init` 忽略初始化结果、把半残 predictor 的指针交给 Java 层
    4. **`checkInitSuccess()` 会谎报成功**：末尾 `return initSuccess || retryTime++ >= 5`，自检失败 5 次后无条件返回 true
    5. **`finalize()` 与推理竞态**：`nativePointer` 非 volatile、`destroy()` 不加锁（`runImage` 却持锁）
    6. `Paddle.kt` 调 `predictor.runOcr()`，绕过了 `Predictor.ocr()` 里现成的「自检 + 重初始化」循环——这是「paddle 不会自动恢复」的直接原因
- **修复**:
  - **越界兜底（两道）**: `calc_filtered_boxes` 的 memcpy 按 `min(pred_size, height*width)` 截断并在尺寸不符时打 logcat；`Predictor.runOcr` 入口新增 `padToMinSize()`，小于 `MIN_OCR_WIDTH/HEIGHT`(360x260) 的图**补黑边**到安全尺寸（内容居左上，故结果坐标无需换算）。选补边而非拒绝，是因为脚本存在合法的窄条截图需求（如只 OCR 屏幕顶部状态区），拒绝会让功能失效。另给 `get_rotate_crop_image` 的 `cv::Rect` 加边界夹紧
  - `Predictor.loadModel` 成功后置 `isLoaded = true`（缺陷 1 的关键一行）；只创建一个 native predictor（缺陷 2）；创建后校验 `isValid()`，失败则 destroy 并返回 false
  - `releaseModel()` 统一释放、复位 `initSuccess`/`warmupIterNum`；各 `init` 重载不再无条件硬置 `isLoaded = true`，一律以 `loadModel` 真实返回值为准
  - cpp 三层逐级校验并向上报错：`_init` 检查 `CreatePaddlePredictor`、`init_from_file` 逐个检查子模型、JNI `init` 失败时 `delete` 并返回 0（新增 `RETURN_ERROR`）。Java 侧据 `nativePointer == 0` 判定不可用
  - `OCRPredictorNative`: `nativePointer` 加 `@Volatile`、`destroy()` 加锁并先置 0 再 release、移除 `finalize()`、新增 `isValid()`
  - `checkInitSuccess()` 如实返回；`Predictor.ocr()` 的 `while` 死循环改为最多 `MAX_INIT_ATTEMPT`(3) 次
  - **空结果自愈交由调用方声明**: `paddle.ocr(img, threadNum, useSlim, expectNonEmpty)` 新增第 4 个参数。默认 false 时空结果视为正常、直接返回（零额外开销）；传 true 表示调用方确信图上必有文字，此时空结果即判定模型疑似失效，`releaseModel()` + 重新初始化后重试一次（等价于「重启脚本」，但进程内自动完成）。两次自动重建之间有 `MIN_REBUILD_INTERVAL_MS`(1min) 冷却，防调用方预期有误时反复重建
  - 新增 `paddle.release()` JS 接口，供脚本主动回收 native 内存
- **⚠ 走过的弯路（勿重蹈）**:
  1. 曾在 `Paddle.ocr` 里用 `checkInitSuccess()` 的内嵌测试图做「空结果自检」来自动判定模型死活。该方案有两个致命问题：① 那张内嵌图仅 **84x57**，远低于安全尺寸，等于在最高频路径上反复触发上述堆越界，**实测运行几分钟即崩溃**；② 脚本靠轮询 OCR 等元素出现，空结果是常态，每次都多跑一次推理纯属浪费（实测日志清一色 `[EMPTY] 自检通过`，无一次真失效）。后又尝试改用「距上次成功识别超过 N 分钟」的时间阈值，同样不可行——脚本可能隔数小时才重新运行，刚开始轮询时为空完全正常，会被误判。**结论：一次 OCR 该不该有结果，只有调用方知道，框架侧无论按次数还是时间去猜都会误判**
  2. 曾以为修好 `calc_filtered_boxes` 的 memcpy 越界后，`padToMinSize` 补边就可以去掉了。**实测证伪**：`paddle.setPadEnabled(false)` 后用 84x57 连续 OCR，第 5 次即 SIGSEGV，且崩溃栈 `#01` 落在 **`libpaddle_light_api_shared.so`** 内部（而非我们的 `libNative.so`）——即 paddle-lite 库自身对极小输入也有越界，那是预编译第三方 so、无源码可改。**补边是唯一能挡住该路径的手段，不可去除**。（`setPadEnabled` 开关已保留，供将来排查特定尺寸是否危险）
- **诊断日志（已停用，保留备查）**: `PaddleLog`（异步单线程写、队列满即丢、按 2MB 轮转为 `.1`），落盘到 **`/sdcard/Lvt4AJs/paddle.log`**。全量记录每次 OCR 的尺寸/结果数/耗时/可用堆内存，以及 INIT/PAD/HEAL/ARG/RELEASE 等事件。该问题需长时间运行才复现，靠此文件事后回溯定位。**修复经长期运行验证稳定后，所有 `PaddleLog.log(...)` 调用已注释掉**（类本身保留）——若问题复发或需排查 OCR 相关新问题，取消注释重新编译即可恢复
- **文件**: `paddleocr/.../PaddleLog.kt`（新增）、`paddleocr/.../Predictor.kt`、`paddleocr/.../OCRPredictorNative.kt`、`paddleocr/src/main/cpp/{common.h,ppredictor.cpp,ocr_ppredictor.cpp,ocr_crnn_process.cpp,native.cpp}`、`autojs/.../runtime/api/Paddle.kt`、`autojs/src/main/assets/modules/__paddle__.js`；脚本侧 `common.js`（`ocr` 的 `expectNonEmpty` 透传）、`AntForest/playground.js`（裁剪高度按 `PaddleOcrMin.h` 兜底）
- **验证**: 连续 5 次 OCR，`[INIT]` 只出现 1 次（修复前每次 OCR 前都有一整套），耗时由首次 75ms 降至稳定 56~59ms，`freeMem` 在 464~484MB 间波动不单调下降（证实无泄漏）。**长期观察要点**：若 `freeMem` 持续走低或 `[OCR]` 耗时持续攀升，说明泄漏复发；出现 `[HEAL]` 说明自愈被触发过（原问题仍在但已能自恢复）；出现 `[PAD]` 说明有调用方传了过小的图，应回头修脚本侧
- **注意**: 脚本侧 `common.js` 的 `PaddleOcrMin = {w:360, h:260}` 仍需遵守——native 补边只是兜底，被补边的窄条图识别效果未必理想。`common.ocr` 不带 region 时不会走 `paddleOcrRegionAdjust`，此类调用（如 `playground_browserAd` 裁顶部窄条）需自行保证尺寸

### 15. 悬浮窗坐标非屏幕绝对坐标 + 主界面横屏挖孔黑边
- **现象一（坐标偏移）**: 竖屏下 `setPosition(x, 0)` 的窗口顶部不在屏幕顶部，而在状态栏底部（本机偏 152px）；横屏时 x 还额外偏一个挖孔宽度
- **现象二（黑边）**: 横屏下主界面左侧有一片约 152px 的黑边，自带悬浮小球也不贴屏幕边、而是贴那片黑边的右侧
- **根因**: `WindowManager.LayoutParams` 的 x/y **不是屏幕绝对坐标，而是相对窗口 parent frame 的偏移**，而 parent frame 被系统按两个源内缩：
  - 竖屏 top=152：`fitInsetsTypes` 默认含 STATUS_BARS
  - 横屏 left=152：未声明 `layoutInDisplayCutoutMode`，窗口被挤出挖孔安全区
  代码里只设了 `FLAG_LAYOUT_NO_LIMITS`——这在 Android 10 及以前够用，但 **Android 11(API30) 起窗口 frame 改由 `fitInsetsTypes` 决定**，NO_LIMITS 只解除 display frame 限制。实测 `BlinkProbe` 已带该 flag，请求 `lp=(1,0)` 仍被推到 `frame=[153,152]`（当时 `parent=[152,152][2772,1280]`，正好 parent+lp）——这也反证了 **parent 归零后 `frame == lp`**，故修复后 lp.x/y 天然就是绝对坐标，调用方无需换算
  主界面黑边是同一个病：`MainActivity` 的 `mAttrs` 里 `layoutInDisplayCutoutMode` 字段整个缺失 → `Requested w=2620`、`w806dp`；而声明了 cutout 模式的系统窗口都拿到完整 2772/`w853dp`。`letterBoxed=false`，**不是 max_aspect 信箱，是纯 cutout 内缩，可彻底消除**
- **修复**:
  - 新增 `common/.../WindowLayoutCompat.java`，两个方法**刻意分开**：
    - `applyAbsoluteScreenCoordinates(lp)`（悬浮窗用）：`layoutInDisplayCutoutMode=ALWAYS` + `setFitInsetsTypes(0)` + `NO_LIMITS`，带 API 28/30 版本守卫
    - `applyDrawIntoCutout(window)`（Activity 用）：**只**设 `SHORT_EDGES`。绝不能给 Activity 用前者——一旦关掉 fitInsetsTypes，WindowInsets 不再正常派发，Material3 的 TopAppBar 会失去状态栏避让、顶栏直接顶进状态栏
    - `getRealScreenWidth/Height(wm)`：用 `getRealMetrics()` 取物理尺寸
  - 六处窗口接入：`RawWindow`、`BaseResizableFloatyWindow`、`BlinkProbe`、`CircularMenuWindow`、`FullScreenFloatyWindow`、`ConsoleImpl`（后两者与 aar 父类打交道，只能 `super.onCreateWindowLayoutParams()` 拿到结果再加工）
  - `OrientationAwareWindowBridge` 的 `getScreenWidth/Height` 改用 `getRealMetrics()`，并**删掉原有的横竖屏宽高互换**——`getRealMetrics()` 自带旋转感知，保留互换会变成双重交换。原先那段互换本就是为绕开 aar 中 `DefaultImpl` 缓存 DisplayMetrics 永不刷新的脏值而写的补丁
  - `MainActivity.onCreate` 调 `applyDrawIntoCutout(window)`。**不改主题**（`AppTheme` 是 manifest 里的 application 级主题，改它会波及编辑器/设置页等全部 Activity），**不动 Compose 树**（竖屏顶栏当前正常，靠 Material3 组件自带的 `windowInsets` 避让）
- **⚠ 注意事项**:
  - `applyAbsoluteScreenCoordinates` **必须在 flags 全部设置完之后调用**：框架会从 `FLAG_FULLSCREEN`/`FLAG_LAYOUT_IN_SCREEN` 反推 fitInsetsTypes，`setFitInsetsTypes()` 置上 `FIT_INSETS_CONTROLLED` 后才停止反推。挪到 `new LayoutParams(...)` 之前会失效
  - `CircularMenuWindow` 与 `OrientationAwareWindowBridge` **必须同批次改**：改动前是「lp.x 偏内 152」与「getScreenWidth() 少算 152」两个错误互相抵消，只改一边会让小球贴边比原来更错
  - 按需求「横屏完全铺满、内容不避让」，刻意**不加** displayCutout padding，横屏下顶栏菜单按钮可能被挖孔压住，属已知取舍
  - `Floaty.getX/getY`、`device.width/height`、布局分析器的 `mStatusBarHeight` 三处**均无需改动**：前两者本就读绝对值（`Device.width/height` 是 `static final`，取自 `ScreenMetrics` 的 `getRealMetrics()`）；后者靠 `getLocationOnScreen()` 动态取偏移，窗口归零后自动退化为恒等变换，顺带修掉横屏 X 方向从未补偿的老 bug
- **文件**: `common/.../WindowLayoutCompat.java`（新增）、`autojs/.../core/floaty/{RawWindow,BaseResizableFloatyWindow}.java`、`autojs/.../core/image/capture/BlinkProbe.java`、`autojs/.../core/console/ConsoleImpl.java`、`app/.../ui/floating/{CircularMenuWindow,FullScreenFloatyWindow,OrientationAwareWindowBridge}.java`、`app/.../ui/main/MainActivity.kt`；脚本侧 `AutoxScripts` 见下
- **脚本侧连带改动（AutoxScripts）**: 坐标系变了，此前手工补偿过的地方必须同步清理
  - `王者换装/Redmi_25053RT47C/resources.js` 的 `offset:{x:120,y:0}` 删除，`王者换装.js` 里 29 处 `+Res.offset.x/y` 一并去掉。点位数值**不用重标**（存的是悬浮窗坐标、靠 offset 换算到屏幕坐标，两者同步归零后抵消），顺带修掉 120 与真值 152 之间一直存在的 32px 误差
  - 蚂蚁脚本 17 处硬编码 y 值 **+152**（这些 y 是为避免状态提示窗遮挡待识图元素而逐个调出来的，上移后避让关系会失效）
  - 3 处 `device.height - w.getHeight() - 100` 的底部对齐窗口（`forest.js:29`、`goldBean.js:29`、`manor.js:339`）**保持不变**——`device.height` 是物理全高 2772 不受影响，这类窗口此前实际落点是 `2772-高-100+152`、超出屏幕底部 152px 下半截被裁，一直是错的，修完才第一次真正对齐
  - `debug/坐标查看.js` 按 rotation 加 `getVirtualBarHeigh()` 的 switch 删除（修完会变成重复补偿）
- **验证**: 实测 `MainActivity` 横屏 `Requested w=2772`（此前 2620）；悬浮窗/小球 `parent=[0,0][2772,1280]`（此前 `[152,152]`）；小球贴左边时 frame left 为 `-34`（`-hiddenWidth`，即真正贴到物理左缘）

### 16. 横屏启动 app 后，横屏脚本截图变成竖图
- **现象**: 横屏脚本（`common.setCaptureScreenLandscape(true)`，如王者换装）日志刷 `captureScreenx: 截图方向(竖)与预期(横)不符，重新请求截图权限`，反复重新申请截屏权限
- **根因**: `ScreenMetrics.getOrientationAwareScreenWidth/Height()` 原实现是「横屏就把 deviceScreenWidth/Height 交换返回」，**隐含假设这两个字段存的是竖屏基准值（短边/长边）**。但 `initIfNeeded()`（`ScreenMetrics.java:25`）用 `getRealMetrics()` 取值，存的是**初始化那一刻的方向**，且由 `AutoJs.java:155` 的 `onActivityCreated` 触发、靠 `initialized` 标志**整个进程只取一次、转屏不刷新**。于是：
  - 竖屏启动 app（常态）→ 存 `W=1280,H=2772`，符合假设，一切正常
  - **横屏启动 app** → 存 `W=2772,H=1280`，交换逻辑全反 → `getOrientationAwareScreenWidth(LANDSCAPE)` 返回 1280 → `ScreenCapturer.refreshVirtualDisplay` 按 `1280x2772` 建了个**竖向** VirtualDisplay → 横屏脚本截出竖图
  一旦存错，**整个进程生命周期内**所有横屏截图都是竖的，直到 app 重启
- **修复**: `getOrientationAware*` 改用 `min/max` 归一化取短边/长边，不再依赖存值时的方向。**只改这两个方法，不动 `getDeviceScreenWidth/Height`**——后者被 `Device.width/height`(`static final`) 使用，改动会波及所有脚本的坐标语义
- **文件**: `common/src/main/java/com/stardust/util/ScreenMetrics.java`
- **验证**（横屏下重启 app 再跑横屏脚本）:

  | | 修复前 | 修复后 |
  |---|---|---|
  | `getOrientationAwareScreenWidth(LANDSCAPE)` | 1280 ❌ | 2772 ✅ |
  | `captureScreen()` 尺寸 | 1280x2772（竖）❌ | 2772x1280（横）✅ |

  注意 `deviceScreenWidth/Height` 修复后仍存横屏值（2772/1280）——修复不是靠「存对值」，而是靠归一化让结果与存值方向无关，因此也顺带免疫了「转屏后不刷新」这个隐患
- **说明**: 与修复记录 #15 的悬浮窗坐标系问题**无关**，是独立的既有 bug，只因排查 #15 时 force-stop 了 app、恰好在横屏下重启才暴露出来。影响范围不限于王者脚本：任何方向与 app 初始化方向相反的截图请求都会中招

## 日志文件位置速查

排查问题时优先看这些落盘日志（logcat 只在连着 adb 时可见、且不保留历史）：

| 文件 | 内容 | 产生者 |
|---|---|---|
| `/sdcard/Lvt4AJs/crash.log` | Java 层未捕获异常堆栈（含线程名、设备指纹） | `CrashHandler.writeCrashLog` |
| `/sdcard/Lvt4AJs/paddle.log`（+ `.1` 轮转备份） | PaddleOCR 每次调用的尺寸/结果数/耗时/可用内存，及初始化、自愈等事件。**当前已停用**（调用处均已注释，需要时取消注释重编） | `PaddleLog` |
| `/sdcard/脚本/AntForest/logs/error.*.log` + 同名 `.png` | 脚本自身异常快照（AutoxScripts 项目的 `common.saveErrorLog`） | 脚本侧 |

取日志：`adb shell cat /sdcard/Lvt4AJs/paddle.log` 或 `adb pull /sdcard/Lvt4AJs/`。

**注意**：这些路径均为外部存储根目录下，adb 与文件管理器可直接访问。**不要**改回 `getExternalFilesDir()`（`/sdcard/Android/data/包名/files/`）——Android 11+ 分区存储下该目录既无法用 adb 读取（连 `run-as` 也是 Permission denied）、也无法用文件管理器查看，写了也取不出来。此前 `crash.log` 正是写在那里，且父目录不存在时 `FileWriter` 抛异常被 `catch` 吞掉，导致该文件从未成功写出过（已修，见修复记录 #14）。

native crash（SIGSEGV 等）无法被 Java 的 `UncaughtExceptionHandler` 捕获，只能看系统 tombstone：`adb shell ls /data/tombstones/`（需 root）或 `adb bugreport`。

## 新增功能

### floaty 控制系统自带悬浮小球（CircularMenu）
- **背景**: 脚本靠截图识别/点击工作，AutoJS 自带的悬浮小球（CircularMenu）会遮挡屏幕、干扰截图识别或造成误触。开放接口让脚本运行时隐藏它、结束后还原
- **新增 JS 接口**（挂在全局 `floaty` 上）:
  - `floaty.isCircularMenuShowing()` → boolean，查询小球当前是否显示
  - `floaty.hideCircularMenu()` → boolean，隐藏小球，返回值为隐藏前是否处于显示状态（供脚本记录、结束后据此还原）
  - `floaty.showCircularMenu()` → boolean，还原显示小球（已显示则不重复创建），返回接口是否可用
- **实现要点**:
  - 小球由 `app` 模块的 `org.autojs.autojs.ui.floating.FloatyWindowManger` 管理（`isCircularMenuShowing`/`hideCircularMenu`/`showCircularMenuIfNeeded` 静态方法）
  - `autojs` 模块无法在编译期反向依赖 `app` 模块，但 Rhino 运行时是单一 classloader，故在 `__floaty__.js` 中按全限定类名运行时解析 `FloatyWindowManger`；类缺失（如精简打包）时探测调用抛错，接口自动降级为无操作
  - 增删悬浮窗必须在主线程执行，`__floaty__.js` 用 `runtime.uiHandler.post` + `VolatileDispose` 同步切主线程（隐藏后脚本通常紧接着截图，故同步等待执行完成）
- **文件**: `autojs/src/main/assets/modules/__floaty__.js`
- **脚本侧用法**（示例见 `蚂蚁全套.js` 的 `doRun`）: 运行前 `var was = floaty.hideCircularMenu()`，`finally` 中 `if(was) floaty.showCircularMenu()` 还原

### 悬浮小球默认点击行为可配置
- **背景**: 悬浮小球默认点击是展开二级菜单，但常见场景是直接打开脚本清单。开放为可切换配置
- **配置项**（侧边栏「悬浮窗」开关下方「悬浮窗默认行为」下拉）: 打开菜单（默认）/ 打开脚本清单 / 停止所有脚本
- **实现**:
  - `Pref.java` 新增 `KEY_FLOATING_BALL_ACTION`（int：`FLOATING_BALL_ACTION_MENU`/`_SCRIPT_LIST`/`_STOP_ALL`）及读写方法
  - `DrawerPage.kt` 新增 `FloatingBallDefaultActionItem`（DropdownMenu 三选）
  - `CircularMenu.kt` 的 `setupListeners` 按 `Pref.getFloatingBallDefaultAction()` 分派：`showScriptList()` / `stopAllScripts()` / 展开菜单（录制中、已展开状态仍走原逻辑）
  - `res-i18n/values/strings.xml` 新增 `text_floating_ball_default_action` 等 4 条文案
- **文件**: `Pref.java`, `ui/main/drawer/DrawerPage.kt`, `ui/floating/CircularMenu.kt`, `res-i18n/values/strings.xml`

### 主界面横屏布局修复
- **现象**: 横屏下主界面「整体错乱」——打开抽屉时抽屉过宽、右侧无遮罩，像左右裂开
- **根因**: `MainActivity.kt` 的 `ModalNavigationDrawer` 抽屉宽度用了 `width - 50.dp`（整屏级宽度），横屏下抽屉巨大、遮罩过窄。另有一行残留调试用 `Text("test")`
- **修复**: 抽屉宽度改为 `minOf(width - 50.dp, 360.dp)`（Material 标准上限）；删除残留 `Text("test")`。主界面本身（列表、顶栏、底部 tab）横屏布局正常，无需改动
- **文件**: `ui/main/MainActivity.kt`

### 文件列表排序：文件在前、文件夹在后
- **背景**: 原主界面脚本列表「文件夹」类别在上、「文件」类别在下，改为文件在前
- **实现**: 反转适配器的位置映射（类别标题位置、`getItemViewType`、`onBindViewHolder`、`getItemPosition`、spanSizeLookup）。主列表实际用的是 `ExplorerViewKt.kt`（Kotlin 重写版），`ExplorerView.java` 为另一处（对话框等）用同样逻辑一并改
- **文件**: `ui/explorer/ExplorerViewKt.kt`, `ui/explorer/ExplorerView.java`

### UI 脚本 "window" 执行模式（独立窗口）
- **背景**: UI 脚本（`"ui";`）默认与 AutoX.js 主 Activity 共享 task 栈，在 recents 中不独立显示。debug 工具类脚本（图片匹配、边缘检测等）需要独立窗口，之前靠脚本层 `FLAG_ACTIVITY_NEW_DOCUMENT` 自重启 hack 实现，但存在 recents 划掉后脚本引擎不终止、"运行中脚本"列表不更新、imagePool 泄漏导致 OOM 等问题
- **新增执行模式**: 脚本开头声明 `"ui window";` 即可，框架自动处理：
  - `JavaScriptSource.kt` 新增 `EXECUTION_MODE_WINDOW = 0x4`，`EXECUTION_MODES` map 加 `"window"` 条目，与 `"ui"` / `"auto"` 按位组合
  - `ScriptExecuteActivity.execute()` 检测到 window 模式时给 Intent 添加 `FLAG_ACTIVITY_NEW_DOCUMENT | FLAG_ACTIVITY_MULTIPLE_TASK`，使 Activity 在独立 recents task 中运行
  - `ScriptExecuteActivity.onCreate()` 对 window 模式自动设置 `TaskDescription` 为脚本文件名，recents 中显示有意义的标题
- **文件**: `autojs/.../script/JavaScriptSource.kt`, `autojs/.../execution/ScriptExecuteActivity.kt`

### ScriptExecuteActivity.onDestroy 修复（"运行中脚本"列表残留）
- **现象**: UI 脚本的 Activity 被系统销毁（如从 recents 划掉）后，脚本仍在"运行中脚本"列表中，且点 X 也无法终止
- **根因**: `onDestroy()` 只调了 `engine.destroy()`，没通知 `ScriptExecutionListener`（`onSuccess`/`onException`）——而 UI 层的任务列表移除靠的就是这个回调。正常流程中 `finish()` 会通知 listener，但从 recents 划掉时系统直接调 `onDestroy()`，不经过 `finish()`
- **修复**: `onDestroy()` 中先检查 listener 是否已通知（`mListenerNotified` 标志防重复），未通知则补发 `onSuccess`/`onException`，再 `engine.destroy()`。`finish()` 中通知 listener 后设 `mListenerNotified = true`。`engine.destroy()` 也包了 try-catch 防止异常中断清理流程
- **影响范围**: 所有 UI 模式脚本（不限于 window 模式），修复了通用的 Activity 被系统回收时的清理问题
- **文件**: `autojs/.../execution/ScriptExecuteActivity.kt`

## 已知待修复问题

原「其他 app 获取截屏权限后脚本状态异常」已解决（见上文本地修复记录第 8 条）。

与 AutoX.js v6.5.8 对比后**尚未处理**的差异（多为核心重写，风险高收益不确定，暂缓），详见 [`AutoXjs对比与待办.md`](AutoXjs对比与待办.md)，含每项的现象/AutoX.js 做法/涉及文件/影响/修复思路，供未来会话快速上手。
