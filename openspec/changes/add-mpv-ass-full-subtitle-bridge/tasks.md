## 1. MPV→Kotlin 字幕事件桥接（JNI）

- [x] 1.1 在 `player_component/src/main/cpp/mpv_bridge.cpp` 的 `observeProperties()` 增加 `sub-text/ass-full`、`sub-ass-extradata`、`sid` 的 `mpv_observe_property(...)`
- [x] 1.2 扩展 `MPV_EVENT_PROPERTY_CHANGE` 分支：识别上述属性并派发为新的 event type（注意 MPV_FORMAT_STRING 的拷贝与 `mpv_free(...)`，避免悬垂指针）
- [x] 1.3 保持现有事件回调签名不变（复用 `onNativeEvent(int,long,long,String?)`），仅新增 type 常量与 Kotlin 侧解析

## 2. Kotlin 事件类型与 ASS-FULL 解析

- [x] 2.1 扩展 `player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/MpvNativeBridge.kt`：
  - 新增 `Event`：`SubtitleAssFull` / `SubtitleAssExtradata` / `SubtitleSid`（命名可按现有风格调整）
  - 在 `onNativeEvent(...)` 的 type 映射中覆盖新增事件
- [x] 2.2 新增纯 Kotlin 解析器（建议放在 mpv kernel 包内，便于复用与单测）：
  - 输入：单行/多行 `Dialogue:` 文本
  - 输出：`timecodeMs`/`durationMs` + Matroska event fields（`ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text`）的 `ByteArray`
  - 支持 `Text` 字段包含逗号（只切前 9 个逗号）
- [x] 2.3 新增 JVM 单测覆盖关键解析场景：
  - 单行、多行、空字符串
  - `Text` 含逗号/转义
  - 时间格式 `h:mm:ss.cc` 的边界值

## 3. MpvVideoPlayer 接入 SubtitleKernelBridge 与复位策略

- [x] 3.1 调整 `player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/MpvVideoPlayer.kt`：
  - `canStartGpuSubtitlePipeline()`：仅当 `PlayerConfig.getMpvVideoOutput()` 解析为 `mediacodec_embed` 时返回 `true`
- [x] 3.2 实现 `setEmbeddedSubtitleSink(sink)`：
  - 保存/替换 sink 引用
  - 将来自 mpv 的 `ass-full`/`extradata` 事件转发给解析与投递组件
  - sink 置空或 player release 时停止桥接并清理状态
- [x] 3.3 在以下“时间轴跳变”点触发 flush/reset（`EmbeddedSubtitleSink.onFlush()` + 清空去重映射）：
  - `seekTo(...)`
  - `selectTrack(...)` / `deselectTrack(...)`（字幕轨道相关）
  - `setSubtitleOffset(...)`
- [x] 3.4 `setSubtitleOffset(offsetMs)` 语义对齐：
  - UI 语义：offset > 0 为“提前”
  - mpv `sub-delay` 语义相反：应设置为 `sub-delay = (-offsetMs)/1000.0`
  - offset 改变后对 embedded track 做 reset，避免旧事件污染

## 4. 验证

- [x] 4.1 运行单测：`./gradlew :player_component:testDebugUnitTest`
- [x] 4.2 运行依赖治理校验：`./gradlew verifyModuleDependencies`（需确认输出尾部为 `BUILD SUCCESSFUL`）
- [ ] 4.3 手工验收（建议准备一条含内封 ASS/SSA 的样例视频）：
  - mpv + `vo=mediacodec_embed`：字幕可见、随播放更新
  - seek 前进/后退：字幕不残留、不重复
  - 切换字幕轨道 / 关闭字幕（`sid=no`）：立即清屏与恢复
  - 偏移测试：offset>0（提前）不明显缺行；offset<0（延迟）显示符合预期
