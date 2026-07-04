# OpenAutoJS 与 AutoX.js 对比：待处理项

> 目的：记录 OpenAutoJS（本 fork，基于 AutoX.js **较早版本** ~v6.4.1）相对 AutoX.js v6.5.8 **尚未处理**的差异，供未来会话快速上手修复。
>
> **已修复项不在此列**：技术细节见 `CLAUDE.md`「相对 AutoX.js 的本地修复记录」，用户向说明见 `README.md`。
>
> 对比方法：AutoX.js v6.5.8 反编译源码在 `autoxjs-decompiled/sources/`(jadx，变量名混淆但控制流完整) 与 `autoxjs-decompiled-dex5/sources/`(Rhino 引擎类)；OpenAutoJS 源码在 `autojs/src/main/`、`automator/src/main/`、`app/src/main/`。反编译**只有 Java 无 JS 模块**，故只能对比 Java 层。
>
> 判断原则：脚本目前在本 fork 上能正常跑；很多差异是「核心重写」，收益不确定但回归风险高，故暂缓。下面标注 **影响** 与 **风险**，未来按需选修。

---

## 1. Loopers 保活模型：waitWhenIdle → AsyncTask（影响中，风险中）
- **现象**：`core/looper/Loopers.java` 用 `waitWhenIdle`(ThreadLocal boolean) + `LooperQuitHandler` 决定 looper 是否退出。`Events` 调 `waitWhenIdle(true)` 后 looper **永不退出**，即使监听已移除。
- **AutoX.js 做法**：改用 `Loopers.AsyncTask` 模型——每个子系统(Events/Sensors/TimerThread/Threads)注册命名 AsyncTask，所有 task 结束后 looper 才退出；`removeAsyncTask` 投递 EMPTY_RUNNABLE 触发 idle 重新检查。
- **影响**：长时间运行脚本可能线程/内存泄漏(looper 该退不退)；或事件监听移除后过早退出。
- **涉及文件**：`core/looper/Loopers.java`(整类)、`runtime/api/Events.java`(约 L114/130/228/235/249/274)、`runtime/api/Sensors.java`(保活也依赖它)。
- **修复思路**：移植 AutoX.js `AsyncTask` 内部类 + `addAsyncTask`/`removeAsyncTask`/`checkTask`，替换 Events/Sensors 里的 `waitWhenIdle`。**牵动 threads/定时/事件生命周期，改前需回归测试心跳、多脚本协调、晨跑定时等**。

## 2. Timer 实现：post() 空实现 + SparseArray 非线程安全（影响低-中，风险低-中）
- **现象**：`core/looper/Timer.java`：`post(Runnable)` **方法体为空**(死代码)；用 `SparseArray`(非线程安全) + 顺序 id；`hasPendingCallbacks()` 用 uptime 启发式。
- **AutoX.js 做法**：`post()` 实际 `mHandler.post(r)`；用 `ConcurrentHashMap` + 随机 id；`clearCallback` 清空时投递 EMPTY_RUNNABLE 触发退出检查；`hasPendingCallbacks` 直接看 size。
- **影响**：`post()` 空实现目前是死代码(本 fork 的 threads.start 不走它，没影响脚本)；SparseArray 在定时回调并发访问时有竞态崩溃风险(低概率)。
- **涉及文件**：`core/looper/Timer.java`(L19-20 字段、L83-94 post、L126-128 hasPendingCallbacks)。
- **修复思路**：SparseArray→ConcurrentHashMap；实现 post()；size 版 hasPendingCallbacks。**与项 1 同属 looper/timer 体系，建议一起做**。

## 3. ScriptBridges 改 Java/Kotlin 直连（影响中，风险高）
- **现象**：`runtime/ScriptBridges.java` 是薄壳 + `Bridges` 接口，真正的 call/toArray/asArray/toString 逻辑在 `__bridges__.js`(init.js 里 setBridges 注入)，每次 Java→JS 回调都经 JS 中转。
- **AutoX.js 做法**：`ScriptBridges.kt` 全 Java 实现，`callFunction` 直接 `BaseFunction.call()`；`toArray/asArray` 用 `context.newArray()`；有 `useJsContext` 帮助在非 Rhino 线程也能进入/退出 Context。
- **影响**：性能(每次回调/UiObjectCollection 转换/String 包装开销)；`useJsContext` 可避免非 Rhino 线程调 bridge 时崩溃。
- **涉及文件**：`runtime/ScriptBridges.java`(整类)、`engine/RhinoJavaScriptEngine.kt`(setRuntime 里 setBridges vs setup)。
- **修复思路**：整类改 Kotlin 对齐 AutoX.js，去掉 `__bridges__.js`。**引擎核心重写，风险高；现有 WrapFactory 修复已覆盖主要兼容症状，非必要不动**。

## 4. WrapFactory 下沉到 AndroidContextFactory（影响中，风险中）
- **现象**：完整 WrapFactory(String/Boolean/Number + UiObjectCollection asArray + View wrapAsJavaObject)是 `RhinoJavaScriptEngine` 内部类，只在引擎 setupContext 里设；而 `InterruptibleAndroidContextFactory.makeContext` 里的 WrapFactory **只处理 String/Number/Boolean**，缺 UiObjectCollection/View。
- **AutoX.js 做法**：把 UiObjectCollection/View 包装放进 `AndroidContextFactory` 的 WrapFactory，使**所有** Context(含 `ContextFactory.enterContext()` 创建的 worker 线程 Context)都生效。
- **影响**：理论上非引擎创建的 Context(某些 worker 线程)里用选择器，`selector().find()` 可能拿到裸 Java 对象而非 JS 数组(无 forEach)。**注意：实测本 fork 的 doRun worker(threads.start) 用选择器正常，此项可能不影响现有用法，需先复现再修**。
- **涉及文件**：`rhino/InterruptibleAndroidContextFactory.java`(makeContext)、`engine/RhinoJavaScriptEngine.kt`(内部 WrapFactory)。
- **修复思路**：把 UiObjectCollection asArray + View 包装移入 AndroidContextFactory 的 WrapFactory(需 bridges 静态引用，见项 3)。

## 5. AutoJsContext 持有引擎引用，去掉静态 Map（影响低，风险低）
- **现象**：`RhinoJavaScriptEngine` 用静态 `sContextEngineMap` 反查引擎，需手动 put/remove，destroy 未清会泄漏。
- **AutoX.js 做法**：`AutoJsContext` 加 `rhinoJavaScriptEngine` 字段，setupContext 时设，无需静态 map。
- **涉及文件**：`rhino/AutoJsContext.kt`、`engine/RhinoJavaScriptEngine.kt`(L102/142/183-188)。
- **修复思路**：给 AutoJsContext 加字段，删 sContextEngineMap/getEngineOfContext。低风险，可随项 3/4 一起做。

## 6. ScopeRequire：__filename/__dirname/npm require（影响低，风险中）
- **现象**：用 Rhino 内置 `RequireBuilder`，脚本里 `__filename`/`__dirname` 为 undefined，无 npm 模块解析。
- **AutoX.js 做法**：自定义 `ScopeRequire`，提供 __filename/__dirname、相对路径解析、多模块目录(MODULE_DIR + NPM_MODULE_DIR)。
- **影响**：用到 `__filename`/`__dirname` 或 npm 风格 require 的脚本才需要(现有脚本大概没用)。
- **涉及文件**：`engine/RhinoJavaScriptEngine.kt`(initRequireBuilder)。
- **修复思路**：移植 AutoX.js `ScopeRequire.kt`。

## 7. Floaty 窗口构造期预建（影响低，风险中）
- **现象**：`BaseResizableFloatyWindow`/`RawWindow` 把 view 创建放在 `onCreate()`(FloatyService 异步回调)，调用方需 `waitForCreation()` 阻塞。已通过「构造函数主线程判断」(CLAUDE.md 已修项)规避死锁。
- **AutoX.js 做法**：`BaseResizableFloatyWindow` 改为 extends `FloatyWindow`，**构造函数里预建全部 view + WindowManager + WindowBridge**，`onCreateView` 直接返回，无需 waitForCreation；`RawWindow` 构造函数加 `Context` 参数同理。
- **影响**：现有主线程判断已够用；此为更彻底架构改法，非必要不动。
- **涉及文件**：`core/floaty/BaseResizableFloatyWindow.java`、`core/floaty/RawWindow.java`、`runtime/api/Floaty.java`。

## 8. Threads.runTaskForThreadPool（影响低，风险低）
- **现象**：`Threads` 只有 `start()`(每次建完整 TimerThread + looper)。
- **AutoX.js 做法**：加 `runTaskForThreadPool(BaseFunction)`，用协程线程池(Dispatchers.Default)，shutDownAll 时取消协程 scope。
- **影响**：大量短异步任务时省 looper 开销；现有用法用不到。
- **涉及文件**：`runtime/api/Threads.java`。

## 9. Timers 变参签名（影响低，风险低）
- **现象**：`setTimeout(Object cb, long delay, Object... args)`，省略 delay 会报错。
- **AutoX.js 做法**：`setTimeout(Object... args)`，delay 缺省 1ms(Node 兼容)，显式 Double→long。
- **影响**：仅影响 `setTimeout(fn)`(不传 delay)；常规传 delay 无影响。
- **涉及文件**：`runtime/api/Timers.java`、`core/looper/TimerThread.java`。

---

## 两边共有的潜在 bug（AutoX.js 也有，非 fork 引入）

> 从 AutoX.js 迁来的脚本可能已「按错的行为调过」，修复前评估是否打乱既有脚本。

### A. ImageWrapper.pixel() 行列可能反（影响极低）
- `pixel(x,y)` 对**纯 Mat(无 bitmap)** 的 wrapper 调 `mMat.get(x, y)`，OpenCV 是 `get(row=y, col=x)`。实际截图都带 bitmap 走 bitmap 分支，基本触发不到。
- 文件：`core/image/ImageWrapper.java`。

### B. ColorFinder 的 x/y 都用 scaleX（影响低）
- `ColorFinder.java` 找色返回坐标时 x、y 都用 `mScreenMetrics.scaleX()`。仅当用了 `setScreenMetrics` 且设计宽高比≠设备时 y 偏。与已修的 `GlobalActionAutomator.scaleY` 同类问题，但在 ColorFinder 里**未修**。
- 文件：`core/image/ColorFinder.java`(约 L51-52、L68-69)。
- 说明：若未来统一「正确的 y 缩放」，这里 y 也应改用 `scaleY`。当前若无脚本依赖找色坐标 + setScreenMetrics，可暂不动。

---

## 备注
- 反编译目录：`autoxjs-decompiled/`(classes3.dex，核心业务类)、`autoxjs-decompiled-dex5/`(classes5.dex，Rhino 引擎类)。
- 对比已覆盖：图像/找色/资源、自动化/选择器/无障碍、App/Device/Media/Sensors、Files/Shell/Zip/Storage/Http/Crypto、Engines/Threads/Timers/Events/Console/Dialogs、UI/Floaty/inflater、Engine/Rhino/Bridges。除本文件所列，其余层基本等价。
