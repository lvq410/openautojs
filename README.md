# Lvt4AJs — 基于 OpenAutoJS 的定制版

这是 [OpenAutoJS](https://github.com/openautojs/openautojs) 的一个个人定制分支。

## 为什么有这个 fork

- **Auto.js** 早已停止维护并全网下架。
- 社区基于它做了开源的 **OpenAutoJS**，但同样已 2~3 年无人维护——好在它开源、有完整源码。
- 本 fork 在 OpenAutoJS 基础上做了两类改动：
  1. **兼容性修复**：为把原本跑在 Auto.js 上的自动化脚本迁到 OpenAutoJS，修复 OpenAutoJS 与 Auto.js 行为不一致、导致脚本跑不起来的地方；
  2. **按需增强**：叠加一些自用功能。

> 原始 OpenAutoJS 的项目介绍见 [README.openautojs.md](README.openautojs.md)（英文，含 [简体中文](README_zh-CN.md)）。

## 相对上游 OpenAutoJS 的改动

### 兼容性修复（对齐 Auto.js 行为）

| # | 问题 | 修复 |
|---|------|------|
| 1 | Java 回调传入 JS 的 String 无法 `eval`、`==` 比较失败 | `InterruptibleAndroidContextFactory.makeContext` 设置 WrapFactory，String/Number/Boolean 不再被包装成 `NativeJavaObject` |
| 2 | 每个脚本都弹截屏授权（截屏权限不跨引擎共享） | 把 `ScreenCapturer` 移到共享的 `ScreenCaptureRequester`，多脚本复用同一授权 |
| 3 | 已有权限时 `requestScreenCapture()` 永不返回（Promise.wait 死锁） | 已有 capturer 时直接返回 `Boolean`，绕过 Promise |
| 4 | 切换屏幕方向时崩溃（Android 14+ VirtualDisplay 限制） | 复用 VirtualDisplay，只换 Surface + resize |
| 5 | 首个脚本退出后共享截屏失效（依赖引擎 Handler/Looper） | 重写 `ScreenCapturer`，`capture()` 改为按需 `acquireLatestImage()`，不依赖后台线程 |
| 6 | `ui` 模式脚本中创建 `floaty` 窗口 ANR（UI 线程死锁） | `JsRawWindow`/`JsResizableWindow` 构造函数判断当前线程，UI 线程直接执行不 post |
| 7 | `UiObjectCollection` 缺少 `forEach` 等数组方法 | 脚本侧改用 `each()`（同问题 1 的 WrapFactory 根因） |
| 8 | 其他 app 抢占截屏权限后脚本卡死（MIUI 不触发 `onStop`） | 新增 `ScreenCapturer.checkAlive()` 试帧判活，失效则返回 `false`，脚本据此重新申请或干净退出 |

### 新增功能

- **悬浮小球（自带 CircularMenu）控制接口**：`floaty.isCircularMenuShowing()` / `hideCircularMenu()` / `showCircularMenu()`，让脚本运行时能隐藏自带悬浮球、结束后还原，避免它遮挡屏幕、干扰截图识别或误触。
- **悬浮球默认点击行为可配置**：侧边栏「悬浮窗」开关下方可选「打开菜单（默认）/ 打开脚本清单 / 停止所有脚本」。
- **主界面横屏布局修复**：修正横屏下抽屉过宽、界面错乱的问题。
- **文件列表排序**：脚本列表改为「文件在前、文件夹在后」。

## 构建与包名

- 应用名 `Lvt4AJs`、包名 `com.lvt4j.ajs`（`resourcePackageName` 仍固定为上游 `org.openautojs.autojs`）。
- 构建命令、环境、签名等开发说明见 [CLAUDE.md](CLAUDE.md)。

## 许可与致谢

基于 [OpenAutoJS](https://github.com/openautojs/openautojs)（其又基于 [AutoX.js](https://github.com/kkevsekk1/AutoX) / Auto.js），遵循原项目许可。感谢原作者与社区。
