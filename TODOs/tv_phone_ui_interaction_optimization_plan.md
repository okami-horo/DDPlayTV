# TV/Phone 双端交互与焦点体系优化方案（DDPlay-bilibili）

## 1. 背景与目标

当前工程在同一套 APK 内同时支持手机触摸与 TV 遥控器（Manifest 同时包含 `LAUNCHER` 与 `LEANBACK_LAUNCHER`）。项目已经具备一定的 TV 适配基础（例如播放器的 DPAD 分发、存储页的焦点恢复/滚动对齐），但整体仍处于“单套 UI + 局部补丁式适配”的阶段，导致：

- 手机端出现“第一次点击只出现焦点高亮，第二次才触发”的交互割裂（典型：播放器弹幕开关）。
- 大量控件/selector 的 `state_focused` 反馈在触摸场景渗透，产生明显的“TV 化白色描边/高亮”观感。
- 入口与页面结构未区分 TV/Phone，导致 TV 端需要为“手机优先的 UI 信息架构”强行适配焦点与可达性。

本方案目标不是做“最小侵入修补”，而是建立一致的双端交互架构：**业务语义统一、输入适配分层、焦点策略可控、资源与页面结构可演进**。

## 2. 现状盘点（关键证据）

### 2.1 入口与设备模式

- 同一 Activity 同时作为手机/TV 入口：`app/src/main/AndroidManifest.xml:35`（同一 `SplashActivity` 注册 `LAUNCHER` 与 `LEANBACK_LAUNCHER`）。
- 设备 TV 模式判断：`core_ui_component/src/main/java/com/xyoye/common_component/extension/ContextExt.kt:56`（`Context.isTelevisionUiMode()`）。

### 2.2 输入与交互：已有分层但不一致

- 播放器存在清晰的“遥控器输入适配”：
  - DPAD/Menu 分发：`player_component/src/main/java/com/xyoye/player/controller/base/TvVideoController.kt:61` + `player_component/src/main/java/com/xyoye/player/remote/RemoteKeyDispatcher.kt:38`
  - TV 上禁用触摸手势：`player_component/src/main/java/com/xyoye/player/controller/base/GestureVideoController.kt:63`
- 存储页存在更成熟的“触摸模式 vs 非触摸模式”区分：
  - 触摸模式不恢复列表焦点：`storage_component/src/main/java/com/xyoye/storage_component/ui/fragment/storage_file/StorageFileFragment.kt:108`
  - 焦点可达性/滚动对齐/手动焦点分发：`storage_component/src/main/java/com/xyoye/storage_component/ui/fragment/storage_file/StorageFileFragment.kt:224`
  - `focusableInTouchMode` 只在非触摸模式启用：`storage_component/src/main/java/com/xyoye/storage_component/ui/fragment/storage_file/StorageFileFragment.kt:186`
- `core_ui_component` 已有可复用的焦点基础设施：
  - 可聚焦 item tag + 按索引请求焦点：`core_ui_component/src/main/java/com/xyoye/common_component/extension/RecyclerViewExt.kt:56`
  - 非触摸模式启用 keyline 对齐：`core_ui_component/src/main/java/com/xyoye/common_component/extension/RecyclerViewExt.kt:71`

### 2.3 焦点样式在手机端渗透

多个 selector 内定义了 `state_focused` 的“描边/高亮”，且被手机端布局复用：

- 播放器控制按钮：`player_component/src/main/res/drawable/background_send_danmu_bt.xml:4`（焦点态白描边）
- 存储 item：`core_ui_component/src/main/res/drawable/background_storage_item_selector.xml:3`、`storage_component/src/main/res/drawable/background_storage_item_selector.xml:4`

### 2.4 播放器存在“触摸模式仍启用焦点/抢焦点”的行为

播放器底部控制条在显示时会统一将控件设置为可聚焦且 `focusableInTouchMode = true`：

- `player_component/src/main/java/com/xyoye/player/controller/video/PlayerBottomView.kt:362`

并且在显示时会主动 `requestFocus()`：

- 底部：`player_component/src/main/java/com/xyoye/player/controller/video/PlayerBottomView.kt:171`
- 顶部：`player_component/src/main/java/com/xyoye/player/controller/video/PlayerTopView.kt:61`

这类逻辑在 TV/DPAD 场景合理，但在手机触摸场景会导致“首次触摸先把焦点置到控件上并触发 focused selector”，甚至可能与控件点击/checked 回调形成时序耦合，出现双击问题。

## 3. 模块评估（是否符合成熟双端设计）

下面以“成熟双端设计”的核心原则作为对照：

> 原则：业务语义（Action/Intent）统一；输入适配（Touch/DPAD）与 UI 表现解耦；焦点策略在非触摸模式生效且可控；资源按 TV/Phone 可演进隔离；入口与页面结构允许分叉但共享核心模块。

### 3.1 app（入口/壳）

- 现状：手机与 TV 共用 `SplashActivity`/`MainActivity`，主导航为 `BottomNavigationView`（手机优先）。
- 问题：TV 端信息架构/导航模式与手机差异大，强行共用会导致后续全局适配成本指数上升。
- 建议：引入 TV Shell（独立入口 Activity），以“壳分离”降低全局耦合（见 5.4）。

### 3.2 core_ui_component（通用 UI 与焦点能力）

- 现状：已存在 `RecyclerView` 焦点请求与 keyline 对齐能力，且通过 `isInTouchMode` 做了部分区分。
- 问题：
  - focused selector 在多个模块重复定义（资源重复、策略不统一）。
  - 缺少统一的“控件焦点策略”抽象（目前靠各页面手写）。
- 建议：沉淀为统一 FocusPolicy/FocusDelegate，并集中管理 focused/pressed 视觉（见 5.2/5.3）。

### 3.3 storage_component（存储/媒体库列表）

- 现状：触摸/DPAD 双模区分较成熟，是当前工程里最接近“标准答案”的模块。
- 问题：部分能力可复用但尚未抽取到 core（焦点恢复、非当前 fragment 阻断焦点、菜单键映射等）。
- 建议：将其沉淀为可复用基建（例如 `FocusableListFragmentMixin` 或一组扩展/委托）。

### 3.4 player_component（播放器）

- 现状：存在 DPAD 分发与 TV 禁用触摸手势的分层；但 UI 控件焦点与触摸模式没有一致策略。
- 问题：
  - `focusableInTouchMode` 在显示控制条时被统一打开（触摸模式下产生 focused 高亮，并可能引发“第一次点击只聚焦不生效”）。
  - 控制条显示时强制抢焦点（`requestFocus()`），更像 TV 逻辑。
  - focus 样式与 phone 的 pressed/ripple 没有隔离（大量 `state_focused` selector）。
- 建议：播放器作为优先级最高的改造目标（P0/P1），建立“触摸模式不抢焦点、不启用 focusableInTouchMode；DPAD 模式才启用焦点链与默认焦点”的统一规则。

### 3.5 anime_component / local_component（搜索/绑定等页面）

- 现状：部分布局存在 `focusableInTouchMode="true"`（例如搜索页工具栏容器）。
- 问题：触摸模式下不必要的可聚焦容器会带来“白框高亮/焦点游走”，也会增加后续 DPAD 焦点规划难度。
- 建议：清理不必要的 `focusableInTouchMode`，并在需要 TV 专属交互时使用资源/壳分离承载。

## 4. 核心问题与根因总结

1. **缺少统一的“交互模式（Touch vs DPAD）”策略层**  
   当前有的模块靠 `isInTouchMode` 做得很好（storage），有的模块在触摸模式仍强制启用焦点（player），导致体验不一致。

2. **焦点样式与触摸样式混用**  
   focused selector（描边/高亮）在手机端渗透，且在多个模块重复定义，难以统一演进。

3. **入口/壳不分离导致全局适配成本过高**  
   TV 与 Phone 的主导航/信息架构差异巨大，强行共用会把“局部适配”变成“处处适配”。

## 5. 推荐总体方案（架构设计）

### 5.1 分层模型：Action 语义层 + 输入适配层 + 焦点策略层 + 资源层

建议统一采用以下四层结构（与当前模块化/播放器 `PlayerAction` 思路保持一致）：

1. **Action/Intent（语义层）**  
   定义“用户意图/动作”，例如 `ToggleDanmu / ShowController / OpenSettings / FocusNext`。  
   特点：与输入设备无关，可复用、可测试。

2. **Input Router（输入适配层）**  
   触摸、手势、DPAD、键盘等输入全部映射到 Action。  
   播放器当前 `RemoteKeyDispatcher` 可视为该层的雏形，但建议扩展到“触摸 vs DPAD 模式切换”的统一管理。

3. **FocusPolicy（焦点策略层）**  
   约定并集中管理：
   - 什么时候允许控件获得焦点（仅非触摸模式？）
   - 什么时候抢默认焦点（仅 DPAD 模式首次显示？）
   - 列表焦点恢复规则、不可见页面焦点阻断规则

4. **Resources（资源层）**  
   focused/pressed/ripple 视觉与布局密度差异，尽量通过资源隔离实现（见 5.3）。

### 5.2 交互模式判定：以 `View.isInTouchMode` 为“统一真相”

理由：Android 原生的“触摸模式”本来就是为解决“触摸 vs 焦点导航”共存而设计的机制。项目现状也已经在 storage/core_ui 中使用该机制，说明体系与工程方向一致。

统一约定（建议写入工程规范/代码注释并在 PR Review 强制执行）：

- **触摸模式（`isInTouchMode == true`）**
  - 普通控件：`isFocusableInTouchMode = false`（避免触摸带来焦点框/抢焦点）
  - 不主动 `requestFocus()`（避免触摸操作被“焦点链”打断）
  - 列表不做“默认高亮/恢复焦点”
  - 视觉反馈以 pressed/ripple 为主
- **非触摸模式（`isInTouchMode == false`，通常是 DPAD/键盘）**
  - 启用焦点链、nextFocus、默认焦点
  - 允许 focused 视觉（描边/缩放/阴影）
  - 列表允许 keyline 对齐与焦点恢复

建议在 `core_ui_component` 提供统一工具：

- `View.applyDpadFocusable(enabled: Boolean)`：内部做 `isFocusable = enabled` 与 `isFocusableInTouchMode = enabled && !isInTouchMode`（参考 `StorageFileFragment.kt:186` 的写法）。
- `FocusPolicy.shouldRequestDefaultFocus(viewRoot: View)`：统一判断是否允许抢焦点。

### 5.3 资源隔离：以 `layout-television` / `drawable-television` 为主（辅以代码切换）

建议引入并逐步落地：

- `*/src/main/res/layout-television/`：TV 专属布局（更大间距、更清晰的焦点链、避免小尺寸触控控件）。
- `*/src/main/res/drawable-television/`：TV 专属 focused selector（描边/缩放/阴影），Phone 侧使用 ripple/pressed。
- `*/src/main/res/values-television/`：TV 专属 dimens（焦点描边宽度、圆角、item 间距等）。

说明：
- 仅用 `-television` 不能覆盖“手机外接遥控器/键盘”的场景；因此仍建议保留 5.2 的触摸模式策略，保证双模稳定。
- `core_ui_component` 与 `storage_component` 当前存在重复的 storage selector，可在该阶段顺便做资源归并，形成唯一来源。

### 5.4 TV Shell 分离（强烈建议，作为中长期演进主线）

建议新增 TV 专用入口与导航壳（不改变核心业务模块），典型做法：

- 新增 `TvSplashActivity` / `TvMainActivity`，仅注册 `LEANBACK_LAUNCHER`
- 现有 `SplashActivity`/`MainActivity` 仅保留手机入口（`LAUNCHER`）
- TV 端主导航替换为更适合 DPAD 的结构（侧边栏/顶栏 Tab/卡片网格等），并在壳层集中处理：
  - 全局按键映射（Menu/Back/Settings）
  - 全局焦点恢复策略（页面返回后默认焦点）
  - 进入播放器、媒体库等页面的跳转与焦点落点

好处：
- 大幅降低“所有页面都要完美双端”的改造压力；
- 允许 TV 端信息架构独立演进，同时复用现有 feature fragments/业务能力；
- 与成熟双端项目的长期维护成本模型更一致。

## 6. 分阶段落地计划（带优先级）

### P0（1~2 天）：先止血，解决已暴露的触摸双击/焦点渗透

目标：手机端触摸不出现“先聚焦后生效”的双击问题，且不破坏 TV 端 DPAD 可达性。

建议改造点（优先播放器）：
- 播放器：控制条显示时，不在触摸模式开启 `focusableInTouchMode`，不抢默认焦点  
  - 对照：`player_component/src/main/java/com/xyoye/player/controller/video/PlayerBottomView.kt:362`、`PlayerTopView.kt:61`
- 弹幕开关：当弹幕轨道尚未 ready/selected 时，UI 侧给出明确反馈（禁用态/提示），避免“点击无效但出现焦点高亮”的误导。

验收：
- 手机端：弹幕开关单击必然生效或给出可理解的禁用反馈；不出现白色焦点框“闪一下但没反应”。
- TV 端：DPAD 焦点链仍可达，且默认焦点逻辑符合预期。

### P1（3~5 天）：抽取统一 FocusPolicy 工具与规范化改造

- 在 `core_ui_component` 新增 FocusPolicy/FocusDelegate（或扩展函数集合），形成统一 API。
- 将 `storage_component` 的成熟实现抽取可复用能力（列表焦点恢复/阻断/手动分发）。
- 在 player/anime/local 等模块逐步替换“手写 focusableInTouchMode/抢焦点”的零散逻辑。

验收：
- 触摸模式：关键页面不出现默认高亮；焦点不会莫名跳走。
- 非触摸模式：列表滚动与焦点对齐一致；跨页面返回焦点落点稳定。

### P2（1~2 周）：资源隔离与视觉统一

- 建立 `drawable-television`/`layout-television` 的目录规范与模板
- 逐模块迁移 focused selector 到 TV 资源目录；Phone 资源改为 ripple/pressed（减少 focused 态）
- 归并重复资源（例如 storage item selector 在 core/storage 两处重复）

验收：
- TV：焦点视觉统一（描边/阴影/缩放规则一致）
- Phone：以 ripple/pressed 为主，不再出现“TV 风格白框”视觉渗透

### P3（2~4 周）：TV Shell 分离（推荐主线）

- 新增 TV 入口 Activity 与 TV 主导航
- 逐步把 TV 端关键路径（媒体库/播放/搜索）迁移到 TV Shell
- Phone 保持现有导航与交互，不再被 TV 诉求反向污染

验收：
- TV：主导航、页面可达性、焦点回路与操作效率达到可用水准（类似 Leanback 思维但不强依赖 Leanback UI）
- Phone：现有体验不回退，触摸逻辑更纯粹

## 7. 验收清单（建议写入 PR 模板）

- Phone（触摸）：
  - 控制条/弹幕/设置等关键控件单击必然生效
  - 无“默认焦点框/白描边”突兀闪烁
  - 无 `requestFocus()` 导致的输入法/触摸中断
- TV（DPAD）：
  - 所有操作按钮 DPAD 可达，焦点回路合理（上下左右不会陷入死角）
  - Menu/Settings 键在关键页面有可理解的行为（刷新/设置/更多操作）
  - 隐藏页面不会抢焦点（多 Fragment add() 栈、弹窗层级等）

## 8. 附录：关键代码位置索引（便于后续改造）

- TV 模式判断：`core_ui_component/src/main/java/com/xyoye/common_component/extension/ContextExt.kt:56`
- 入口：`app/src/main/AndroidManifest.xml:35`
- 播放器 DPAD 分发：`player_component/src/main/java/com/xyoye/player/controller/base/TvVideoController.kt:61`
- 播放器触摸手势禁用：`player_component/src/main/java/com/xyoye/player/controller/base/GestureVideoController.kt:63`
- 播放器底部控件启用焦点：`player_component/src/main/java/com/xyoye/player/controller/video/PlayerBottomView.kt:362`
- 存储页触摸/非触摸区分：`storage_component/src/main/java/com/xyoye/storage_component/ui/fragment/storage_file/StorageFileFragment.kt:108`
- RecyclerView 统一请求焦点能力：`core_ui_component/src/main/java/com/xyoye/common_component/extension/RecyclerViewExt.kt:56`

