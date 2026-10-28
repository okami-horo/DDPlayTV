# 简繁转换：现有能力与字幕方案

源码核对日期：2026-10-28。本文替代原 `TODOs/opencc_ass.md`；现有弹幕转换与尚未实现的字幕转换分别说明，不代表新增功能已经交付。

## 当前实现

| 职责 | 当前入口 |
| --- | --- |
| 文本简繁转换与异常回退 | [OpenCC.kt](../../player_component/src/main/java/com/xyoye/open_cc/OpenCC.kt) |
| 字典与配置文件初始化 | [OpenCCFile.kt](../../player_component/src/main/java/com/xyoye/open_cc/OpenCCFile.kt) |
| 播放基础能力初始化 | [PlayerFoundationInitializer.kt](../../player_component/src/main/java/com/xyoye/player_component/providers/PlayerFoundationInitializer.kt) |
| 弹幕语言过滤 | [LanguageConverter.kt](../../player_component/src/main/java/com/xyoye/danmaku/filter/LanguageConverter.kt) |

转换封装和 `assets/open_cc/` 字典、`libs/<ABI>/libopen_cc.so` 均属于 `player_component`，已不在旧的 `common_component/` 模块。初始化由 AndroidX Startup 的 `PlayerFoundationInitializer` 触发。

`OpenCC.convertSC()` 表示转为简体，`convertTC()` 表示转为繁体。API 24 及以上优先尝试系统 ICU，失败后回退原生 OpenCC；较低版本使用原生路径。原生路径检查所需字典是否就绪，串行执行转换，并将空字符、代理字符等 JNI 修改版 UTF-8 不兼容内容保留在转换片段之外。可捕获的转换异常会记录并返回原文；这不等于能够捕获任意原生进程崩溃。

当前接入的是弹幕过滤器的原始／简体／繁体选择。下列字幕入口尚未接入该转换封装：

- [SubtitleController](../../player_component/src/main/java/com/xyoye/player/controller/subtitle/SubtitleController.kt) 的文本显示分支直接使用字幕文本。
- [LibassRendererBackend](../../player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassRendererBackend.kt) 将外挂 ASS/SSA 交给 GPU session；内嵌 ASS/SSA 由内核桥接进入同一 GPU 管线。
- [SubtitleConfigTable](../../core_system_component/src/main/java/com/xyoye/common_component/config/SubtitleConfigTable.kt) 与 [字幕设置页](../../user_component/src/main/res/xml/preference_subtitle_setting.xml) 没有字幕语言转换选项。

因此，弹幕语言切换不能视为字幕语言切换已经可用。

## 保留的字幕需求（待实现）

原方案中仍有价值的需求是：允许用户选择原始／简体／繁体，默认保留原文；在文本字幕与 ASS/SSA 路径中保持一致；转换不得破坏样式、控制标签、换行及非中文内容。

后续实施时应先明确以下边界，再编写实现任务：

1. 普通文本与 ASS/SSA 的入口不同，不能只在 `SubtitleController` 转换就宣称覆盖全部字幕。
2. ASS/SSA 必须区分正文和控制标签、绘图指令，遵守实际 `Format:` 字段布局；不应把所有事件无条件当成固定十列 CSV。
3. 外挂 ASS 文件可研究加载时转换；内嵌 ASS 样本仍需单独设计输入处理。仅改 `loadExternalSubtitle()` 无法覆盖内嵌字幕。
4. 转换失败应使用原文，位图字幕保持现有行为；切轨、重新加载、切换语言与会话退出时必须有明确的状态及临时资源处理方式。
5. 字典、缓存容量和耗时应以实际设备测量为依据。旧方案的“单行小于 1ms”、固定缓存容量和直接修改源码的行号没有验证依据，已移除。

本记录保留需求和约束，不预先规定持久化键名、缓存层或额外依赖。

## 验证范围

[OpenCCJvmLoadTest](../../player_component/src/test/java/com/xyoye/open_cc/OpenCCJvmLoadTest.kt) 仅检查 Kotlin object 初始化不立即加载原生库，不能证明真机转换正确、字幕已接入或多内核渲染一致。

若后续实现字幕转换，至少需要覆盖原始／简体／繁体、混排与 Emoji、ASS 标签和绘图保护、外挂与内嵌路径、转换失败回退、切轨／语言切换，以及 TV 的设置持久化和返回路径。本文整理仅核对源码，未新增运行验证。
