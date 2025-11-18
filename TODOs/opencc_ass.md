字幕简繁转换方案（基于已集成 OpenCC）

目标

- 在运行时将外挂/内嵌字幕按用户所选语言（原始/简体/繁体）进行转换，且不破坏字幕控制标签与样式。
- 复用项目内已集成的 OpenCC 原生实现（libopen_cc.so），避免新增依赖（不引入 opencc4j/ICU）。
- 以“最小改造”为原则，覆盖文本字幕与 ASS/SSA（libass）两条渲染路径。

现状与约束

- 已集成：
  - 包装类与初始化：`common_component/src/main/java/com/xyoye/open_cc/OpenCC.kt`、`OpenCCFile.kt`；`BaseApplication.onCreate()` 中调用 `OpenCCFile.init(this)`
  - 词典与配置：`common_component/src/main/assets/open_cc/*`（`s2t.json`、`t2s.json`、`*.ocd2`）
  - 原生库：`common_component/libs/*/libopen_cc.so`
  - 已使用点（弹幕）：`player_component/src/main/java/com/xyoye/danmaku/filter/LanguageConverter.kt`
- 渲染路径：
  - 文本后端（LEGACY_CANVAS）：ExoPlayer 内嵌文本与外挂 SRT/WEBVTT 走 `MixedSubtitle.fromText()` → `SubtitleUtils.caption2Subtitle()` → `SubtitleController.onSubtitleTextOutput()`
  - libass 后端（ASS/SSA）：`LibassRendererBackend` 直接加载 ASS 文件并渲染位图（不经过 `SubtitleController` 文本通道）
- minSdk=21；不考虑 android.icu.*；文件大小通常较小但需避免每帧转换。

方案总览（最小改造）

- 文本字幕统一出口转换（不改解析逻辑）
  - 插入点：`player_component/src/main/java/com/xyoye/player/controller/subtitle/SubtitleController.kt:58` 处理 `SubtitleType.TEXT` 时，根据“字幕语言”设置对 `subtitle.text` 列表中每一行的 `text` 执行：
    - 原始：不变更
    - 简体中文：`OpenCC.convertSC()`（繁→简）
    - 繁体中文：`OpenCC.convertTC()`（简→繁）
  - 覆盖范围：Exo 内嵌文本、外挂 SRT/WEBVTT、以及通过 `MixedSubtitle.fromText()` 进入的所有文本字幕。
  - 优点：只改一处；不触碰 `SubtitleUtils` 的 ASS 标签处理；不影响位图字幕。

- libass 路径对 ASS 文件做一次性预处理
  - 插入点：`player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassRendererBackend.kt:82` 的 `loadExternalSubtitle(path: String)`。
  - 做法：在调 `loadTrack(path)` 前，将原始 ASS 复制到临时文件，遍历 `[Events]` 段 `Dialogue:` 行，仅对第 10 字段（Text）进行转换；转换方向取决于“字幕语言”设置：
    - 简体中文：繁→简（`OpenCC.convertSC()`）
    - 繁体中文：简→繁（`OpenCC.convertTC()`）
    - 原始：不转换
    - 花括号 `{...}` 内控制标签保持不变；保留 `\N/\n`。
  - 加载替换：将 `loadTrack(tempPath)` 作为输入；在 `release()` 时清理临时文件。

实现细节

- 文本出口转换（示例逻辑）
  - 伪代码（放在 `SubtitleController.onSubtitleTextOutput()`）：
    - 读取 `SubtitleLanguage` 设置：`ORIGINAL/SC/TC`
    - for each `line in subtitle.text`:
      - ORIGINAL：跳过
      - SC：`line.text = OpenCC.convertSC(line.text)`
      - TC：`line.text = OpenCC.convertTC(line.text)`
  - 可选优化：加入一个基于 `LinkedHashMap` 的 LRU（容量 1024–2048），缓存键为 `direction + "\u0000" + lineText`，降低重复转换开销。

- ASS 预处理规则（libass）
  - 仅处理 `[Events]` 段；其余段（`[Script Info]`、`[V4+ Styles]` 等）按原样拷贝。
  - `Dialogue:` 行解析：按照 ASS 规范使用“限制分割”拿到前 9 个逗号后，剩余部分为 Text 字段；示例：`line.split(",", limit = 10)`。
  - Text 字段转换：
    - 扫描 Text 内容，遇到 `{` 进入“标签模式”，直到匹配 `}` 期间原样保留；其余普通文本片段按 `SubtitleLanguage` 方向调用 `OpenCC.convertSC()` 或 `OpenCC.convertTC()`；保留 `\N/\n`。
  - 文件缓存与清理：
    - 临时文件放 `context.cacheDir/opencc_sub/`，命名建议包含 `md5(path + lastModified + size)`；
    - 同一路径重复加载时优先复用现有临时文件；在 `release()` 统一删除（或按 LRU 进行清理）。

语言设置与配置

- 新增“字幕语言”设置（与弹幕一致）：原始 / 简体中文 / 繁体中文（默认：原始）。
  - MMKV Key：`subtitle_language`（String），取值：`ORIGINAL`/`SC`/`TC`
  - 设置页：`user_component/src/main/res/xml/preference_subtitle_setting.xml` 新增 `ListPreference`；`SubtitleSettingFragment.kt` 读写持久化。
  - 逻辑生效：
    - 文本出口（`SubtitleController.onSubtitleTextOutput()`）按语言执行转换
    - libass 预处理（`LibassRendererBackend.loadExternalSubtitle()`）按语言执行转换

错误与回退

- OpenCC 抛异常时：记录错误并返回原文（或加载原文件），不影响播放。
- IO 失败时：打印日志并使用未转换内容。

性能与内存

- 文本出口：单行转换 < 1ms（设备相关），建议 LRU 提升命中率；方向不同应分开缓存。
- ASS 预处理：O(fileSize) 一次性开销，整份文件缓存后不再重复转换。

测试要点

- 文本字幕：普通中英文、混排、Emoji、长行；三种语言模式输出分别验证。
- ASS：包含 `\pos/\an/\bord/\N` 等标签的样例，校验标签保持；验证繁→简与简→繁两种转换正确。
- 回归：Bitmap 字幕路径不受影响；切换后端时行为一致。

文件变更清单（最小）

- 修改
  - `player_component/src/main/java/com/xyoye/player/controller/subtitle/SubtitleController.kt:58`：在 `SubtitleType.TEXT` 分支按 `SubtitleLanguage` 执行 `convertSC/convertTC/跳过`。
  - `player_component/src/main/java/com/xyoye/player/subtitle/backend/LibassRendererBackend.kt:82`：`loadExternalSubtitle()` 前按 `SubtitleLanguage` 对 ASS `Text` 字段做预处理并替换为临时文件。
  - `user_component/src/main/res/xml/preference_subtitle_setting.xml`、`user_component/.../SubtitleSettingFragment.kt`：新增“字幕语言”三选一配置并持久化。
  - `common_component/.../config/SubtitleConfigTable.kt`：新增 `subtitle_language` 的 MMKV 读写方法（可定义 `SubtitleLanguage` 枚举：`ORIGINAL/SC/TC`）。

后续可选增强

- 区域链路选择：根据字幕语言/文件名启用 `tw2s/hk2s` 的配置文件（目前默认 `t2s`）。
- 进一步去重：对整段对话做批量转换减少 JNI 往返。
- 与弹幕转换共用缓存，降低内存占用。
