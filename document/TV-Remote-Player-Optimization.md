# 电视端遥控器按键适配：现状与优化方案（仅常见键）

本文聚焦播放器页面在电视端（DPAD/遥控器）上的按键适配。在设备仅提供“返回、菜单、上下左右、确认（中心键）”这些常见按键的前提下，梳理当前实现、指出问题，并给出统一且可配置的优化方案。

## 一、现状分析（基于 tv-improve 分支）

- 事件分发链路（播放页）
  - `player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt:308` 将按键事件传给 `DanDanVideoPlayer`。
  - `player_component/src/main/java/com/xyoye/player/DanDanVideoPlayer.kt:338` 里拦截音量键，其余转发给控制器。
  - `player_component/src/main/java/com/xyoye/player/controller/base/TvVideoController.kt:18` 处理 DPAD（CENTER/UP/DOWN/LEFT/RIGHT），部分场景把事件下发到 Setting 层。
  - `player_component/src/main/java/com/xyoye/player/wrapper/ControlWrapper.kt:363` 未消费的 KeyEvent 转发给 `SettingController`。
  - `player_component/src/main/java/com/xyoye/player/controller/setting/SettingController.kt:57` 将 KeyEvent 分发给当前显示中的具体 SettingView，各自实现 DPAD 焦点与行为。

- DPAD 行为（播放中、非弹出模式）
  - `CENTER`：切换播放/暂停（`TvVideoController.onActionCenter()`）。
  - `LEFT/RIGHT`：当控制器未显示且未锁定时，按次固定快退/快进 10s（`TvVideoController.changePosition(±10s)`）。若当前控制器已显示或锁定，仅唤起控制器并不消费事件（交由 Setting）。
  - `UP/DOWN`：仅唤起控制器，不直接消费（用于把焦点引导到控制条/设置）。

- 设置视图的 DPAD
  - `PlayerSettingView`、`SwitchSourceView`、`SwitchVideoSourceView`、`SettingVideoSpeedView`、`SettingSubtitleStyleView`、`SettingDanmuConfigureView` 等分散实现 DPAD 焦点移动，行为可达但风格不完全一致（是否环绕、初始焦点、恢复焦点）。

- 音量键说明
  - 电视端遥控器可直接控制系统音量。App 不应拦截 `KEYCODE_VOLUME_UP/DOWN/MUTE`，也不提供应用内音量 UI/OSD。

- 其他问题点
  - 进度步长固定（10s），无“长按加速”；
  - 缺少统一的“UP/DOWN/Menu”行为约定（快速设置、控制条、主设置面板）；
  - 个别按键判断错误：`SendDanmuDialog` 用了 `event.action == KeyEvent.KEYCODE_BACK`（应为 `event.keyCode`）。参见 `player_component/src/main/java/com/xyoye/player/controller/video/SendDanmuDialog.kt:108`。

注：本方案不考虑媒体键、频道键、字幕键、信息键、数字键等非常见键位。

## 二、优化目标

- 统一 DPAD 与菜单/返回的按键映射与优先级；
- 明确“状态 × 按键 → 行为”矩阵（控制器隐藏/显示、设置面板、悬浮窗、锁定）；
- 低学习成本，行为与主流 TV 播放器一致；
- 步长、加速阈值等参数可配置；
- 有清晰的 OSD 反馈与一致的焦点策略。

## 三、建议的键位映射（仅常见键）

- 播放态（控制器隐藏且未锁定）
  - `DPAD_CENTER`：播放/暂停切换。
  - `DPAD_LEFT/RIGHT`：
    - 短按：快退/快进 10s（可配置，如 5/10/15s）。
    - 长按（按住触发重复）：进入“加速快进/快退”，按住逐级提速（2x→4x→8x），松开恢复 1x 并停留在当前时间点。
  - `DPAD_UP`：打开“快速设置”面板（如：倍速、比例、字幕、弹幕开关），焦点定位到首个可操作控件。
  - `DPAD_DOWN`：显示控制条（进度、上下集入口、设置入口等）。
  - `MENU`：直达“播放器设置面板”（等价于从控制条点开设置，但更快捷）。

- 播放态（控制器显示或锁定）
  - `DPAD_LEFT/RIGHT/UP/DOWN`：遵循焦点移动，不触发时间跳转；
  - `DPAD_CENTER`：优先点击焦点控件；若未命中则作为播放/暂停回退；
  - `MENU`：打开或切换到“播放器设置面板”。

- 设置/选择类视图（如切源、倍速、字幕样式）
  - 统一 DPAD 焦点策略：
    - 左/右用于“同一行”内的选项切换；
    - 上/下在“行/组”之间移动；
    - 建议不环绕，减少跳焦困惑；
  - `BACK`：优先关闭当前设置视图；
  - `MENU`：关闭设置并返回播放态（或切换到主设置面板）。

- 返回键优先级（全局）
  1) 若任意设置视图显示 → 关闭设置视图；
  2) 否则若控制器显示 → 隐藏控制器；
  3) 否则 → 退出播放页（或弹出确认）。

## 四、实现方案设计

1) 中央按键分发器（集中路由）
- 新增 `RemoteKeyDispatcher`（建议：`player_component/src/main/java/com/xyoye/player/remote/RemoteKeyDispatcher.kt`）：
  - 接口：`fun onKeyDown(keyCode: Int, event: KeyEvent?, state: UiState): Boolean`
  - 维护 `UiState`（ControllerHidden / ControllerVisible / Settings / Popup / Locked）；
  - 封装“重复/长按”与加速：基于 `event?.repeatCount`/时间阈值，结合现有 `LongPressAccelerator` 实现按住渐进加速，松开复位；
  - 仅覆盖常见键：`DPAD_CENTER/UP/DOWN/LEFT/RIGHT`、`MENU`、`BACK`。

2) 注入点与事件链改造
- `PlayerActivity.onKeyDown` 保持不变；
- `DanDanVideoPlayer.onKeyDown`：去除对 `VOLUME_UP/DOWN` 的拦截，其他按键转发控制器；
- `TvVideoController.onKeyDown`：根据当前 UI 状态委派给 `RemoteKeyDispatcher`，保留“控制器显示时不触发跳转”的保护；
- `SettingController.onKeyDown`：仅在设置显示时消费，未显示时回落到 `RemoteKeyDispatcher`。

3) 统一设置面板 DPAD 风格
- 抽象 `FocusNavigator` 工具，统一“上一项/下一项/上一组/下一组”的计算；
- 统一“初始焦点/关闭后焦点恢复”策略，减少跳焦。

4) 视觉与反馈
- 使用 `PlayerControlView.MessageContainer` 标准化 OSD：
  - 显示“已快进 10s/…s”“倍速 2.0x”“字幕 已关闭/轨道 2/3”“跳转至 …%/…:…” 等反馈；
  - 长按加速时持续显示动态进度，松开后消失。

5) 配置项（删减为仅常见键相关）
- `PlayerConfig`/`PlayerInitializer` 扩展：
  - `tvSeekSmallStepSec`（默认 10）
  - `tvEnableKeyAccelerate`（默认 true）
  - `tvAccelerateThresholdMs`（默认 400）与 `tvAccelerateGears`（[2.0, 4.0, 8.0]）
  - `tvQuickSettingsOnUpEnabled`（默认 true）
  - `tvMenuOpensPlayerSettings`（默认 true）

6) 兼容与清理
- 修复 `SendDanmuDialog` 的返回键判断：`event.keyCode == KeyEvent.KEYCODE_BACK`；
- 移除 `DanDanVideoPlayer` 中对音量键的拦截；清理 `GestureVideoController.onVolumeKeyDown` 等与应用侧音量相关的遗留代码，确保不再消费系统音量键。

## 五、渐进式落地计划

- Phase 1（功能打通）
  - 引入 `RemoteKeyDispatcher`，打通 DPAD 与 `Menu/Back` 的路由；
  - 去除音量键拦截；
  - 固定 10s 步长与 OSD 反馈；
  - 修复 Back 按键细节；

- Phase 2（体验加强）
  - 长按加速（基于 repeat/阈值）；
  - 统一 SettingView 焦点策略与“初始/恢复焦点”。

- Phase 3（可配置 & 帮助）
  - 暴露配置项到设置；
  - 加入按键帮助浮层；
  - 补充自动化与人工测试用例矩阵。

## 六、测试建议（仅常见按键）

- 场景用例：
  - DPAD（短按/长按/连按）在控制器隐藏/显示、设置中、悬浮窗模式；
  - Menu 键：在播放态/控制器显示/设置中行为一致性；
  - Back 键：关闭设置 → 隐藏控制器 → 退出播放的优先级；
  - 长按加速：不同设备的 repeat/阈值差异容错；
  - 焦点移动：初始焦点、边界不环绕、关闭后焦点恢复。

## 七、风险与兼容性

- 不同品牌的按键重复率（long-press repeat）差异较大，建议采用时间阈值并容忍少量抖动；
- 个别遥控器没有 Menu 键，应提供回退路径（如 `UP` 打开快速设置、`DOWN` 打开控制条）；
- 应用不接管系统音量，避免与设备音量逻辑冲突。

---

附：关键代码定位（便于查阅）
- `player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt:308`
- `player_component/src/main/java/com/xyoye/player/DanDanVideoPlayer.kt:338`
- `player_component/src/main/java/com/xyoye/player/controller/base/TvVideoController.kt:18`
- `player_component/src/main/java/com/xyoye/player/wrapper/ControlWrapper.kt:363`
- `player_component/src/main/java/com/xyoye/player/controller/setting/SettingController.kt:57`
- `player_component/src/main/java/com/xyoye/player/controller/video/SendDanmuDialog.kt:108`

> 上述方案在“仅常见键”的限制下，仍提供一致、清晰且可扩展的体验。参数可根据真机反馈进行微调与 A/B 对比。

