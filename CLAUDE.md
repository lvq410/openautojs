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
- **说明**: 唯一未解的边角是「抢占后第一次截图会拿到一帧旧屏，第二次才触发重新授权」，与 AutoX.js 行为一致、下一次截图自动纠正，已放弃处理

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

## 已知待修复问题

原「其他 app 获取截屏权限后脚本状态异常」已解决（见上文本地修复记录第 8 条）。

与 AutoX.js v6.5.8 对比后**尚未处理**的差异（多为核心重写，风险高收益不确定，暂缓），详见 [`AutoXjs对比与待办.md`](AutoXjs对比与待办.md)，含每项的现象/AutoX.js 做法/涉及文件/影响/修复思路，供未来会话快速上手。
