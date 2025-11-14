# TV 端功能裁剪与追踪

目的：在不删除源码的前提下，通过“注释停用 + 隐藏入口”的方式裁剪对 TV 端无用或低价值的功能，并持续跟踪剩余项的改造进度。

## 已注释/停用的功能与入口

- 触摸手势控制（滑动调亮度/音量、水平快进、长按变速、双击等）
  - 源码：`player_component/src/main/java/com/xyoye/player/controller/base/GestureVideoController.kt`
  - 处理：将所有手势相关回调与滑动处理整体包裹为块注释，并提供空实现，TV 端不再响应触摸手势。

- 悬浮窗小窗播放（SYSTEM_ALERT_WINDOW）
  - 源码：`player_component/src/main/java/com/xyoye/player_component/widgets/popup/PlayerPopupManager.kt`
  - 处理：注释掉 `show/dismiss` 逻辑，保留空方法；
  - UI：`player_component/src/main/java/com/xyoye/player/controller/video/PlayerTopView.kt` 隐藏“悬浮窗”按钮并注释点击逻辑。

- 投屏发送链路（Cast Sender）
  - 源码：`app/src/main/java/com/xyoye/dandanplay/app/cast/Media3CastManager.kt`
  - 处理：原 `prepareCastSession` 逻辑注释，改为抛出 `UnsupportedOperationException`；
  - UI：`storage_component/src/main/java/com/xyoye/storage_component/ui/fragment/storage_file/StorageFileAdapter.kt` 注释“投屏”菜单项与点击分支；
  - 测试：`app/src/androidTest/java/com/xyoye/dandanplay/app/Media3CastFallbackTest.kt` 使用 `@Ignore` 屏蔽对应用例。
  - 服务链路：`storage_component/src/main/java/com/xyoye/storage_component/services/ScreencastProvideService.kt`、`storage_component/src/main/java/com/xyoye/storage_component/services/ScreencastProvideNotifier.kt`、`common_component/src/main/java/com/xyoye/common_component/notification/Notifications.kt`（Sender Channel/Id）
  - 处理：TV 端仅作为接收端，不再发起投屏；上述服务、前台通知和投屏 HTTP Server 逻辑整体包裹为块注释，并给出空实现或直接抛出 `UnsupportedOperationException`，防止任何入口重新唤起 Sender。

- 扫码（相机）入口
  - 源码：`storage_component/src/main/java/com/xyoye/storage_component/ui/activities/remote_scan/RemoteScanActivity.kt`
  - 处理：`initView` 直接提示“电视端不支持扫码功能”并 `finish()`，原三星/ScanKit 实现整体块注释保留。

- 扫码入口调用（RemoteStorage/Screencast 对话框）
  - 源码：`storage_component/src/main/java/com/xyoye/storage_component/ui/dialog/RemoteStorageEditDialog.kt`、`storage_component/src/main/java/com/xyoye/storage_component/ui/dialog/ScreencastStorageEditDialog.kt`
  - 处理：扫码按钮默认 `isVisible = false`，点击监听置空，同时将 `ScanActivityLauncher` 成员与回调整体块注释，避免 TV 端再拉起摄像头能力。

- 摄像头/震动权限
  - 源码：`common_component/src/main/AndroidManifest.xml`、`storage_component/src/main/AndroidManifest.xml`、`common_component/src/main/java/com/xyoye/common_component/application/permission/Permission.kt`
  - 处理：`<uses-permission android:name="android.permission.CAMERA" />`、`android.permission.VIBRATE` 及 `Permission.camera` 的权限数组全部注释，改以 `emptyArray()` 保留占位，TV 包不再请求无用权限。

- 画中画（PIP）/后台播放协调
  - 源码：`app/src/main/java/com/xyoye/dandanplay/app/service/Media3BackgroundCoordinator.kt`
  - 处理：`sync` 逻辑直接清空所有 `MediaSession` 命令与 `BackgroundMode`，原能力实现整体块注释，防止 TV 端暴露 PIP/后台播放命令。
  - 测试：`app/src/androidTest/java/com/xyoye/dandanplay/app/Media3BackgroundTest.kt` 添加 `@Ignore`，记录 TV 端暂不验证该能力。

- 意见反馈与分享/邮件
  - 源码：`user_component/src/main/java/com/xyoye/user_component/ui/activities/feedback/FeedbackActivity.kt`
  - 处理：保留 FAQ 展示，仅对 Email/QQ/Issues 的入口 `View` 调用 `isVisible = false` 隐藏；原 QQ/邮件/Issues 逻辑仍使用块注释保留，TV 端不会再出现分享按钮。

## 保留且适合 TV 的能力（未裁剪）

- 播放器 DPAD 控制：`player_component/src/main/java/com/xyoye/player/controller/base/TvVideoController.kt`
- 投屏接收端（Receiver）相关：`storage_component/src/main/java/com/xyoye/storage_component/services/ScreencastReceiveService.kt` 等（TV 通常作为接收端，保留）。

## 待裁剪 / 待改造清单（未注释但建议跟进）

1) 权限与 Manifest 层
- `player_component/src/main/AndroidManifest.xml` 中 `SYSTEM_ALERT_WINDOW`、`REORDER_TASKS`（已隐藏入口，但建议 TV flavor 下移除权限）

2) UI/交互与入口
- 下拉刷新（`SwipeRefreshLayout`）在 TV 上不可达：
  - `local_component/src/main/res/layout/activity_shooter_subtitle.xml`
  - `storage_component/src/main/res/layout/fragment_storage_file.xml`
  - 建议：TV 专用布局移除 SwipeRefresh，改为“刷新”按钮。
- 账户（登录/注册/找回）入口
  - `login` 已在 `LoginActivity` 弹窗拦截；其他页面（`register/forgot`）仍存在，建议 TV 下移除入口或统一拦截弹窗。
- 投屏发送链路的后台类仍在（虽然入口已隐藏）
  - `storage_component/src/main/java/com/xyoye/storage_component/services/ScreencastProvideService.kt`
  - `storage_component/src/main/java/com/xyoye/storage_component/services/ScreencastProvideNotifier.kt`
  - `common_component/.../notification/Notifications.kt` 中 Sender 相关 Channel/Id
  - 建议：TV flavor 下不编译或在运行期隐藏所有 Sender 相关触发点。

3) 功能点评估（按需）
- 截图功能：`player_component/src/main/java/com/xyoye/player/controller/setting/ScreenShotView.kt`
  - TV 上导出/查看不便，建议 TV 默认隐藏入口；必要时保留设置开关。

4) 构建策略
- 建议新增 `tv` productFlavor：
  - 在 `tv` 下移除上述不必要权限与依赖（如 ScanKit），不编译 Sender/Overlay 相关源码；
  - 以 `BuildConfig` 或资源限定符切换 TV 布局与入口可见性。

## 风险与回滚
- 本次均采用“注释 + 隐藏入口”，保留源码，便于回滚与对比；后续切换到 flavor/源集隔离时可平移这部分变更。

## 验证建议（TV 端）
- 构建：`./gradlew assembleDebug`
- 快验清单：
  - 播放页无手势调节，DPAD 左右快进可用；
  - 顶部“悬浮窗”按钮不可见；
  - 文件列表“更多”菜单无“投屏”；
  - 扫码功能点击时提示不支持并返回；
  - 反馈页不触发“发送邮件”的外跳（若未隐藏，需跟进）。

## 附：本次改动明细（供追踪）
- 手势控制注释：`player_component/.../GestureVideoController.kt`
- 悬浮窗管理注释：`player_component/.../widgets/popup/PlayerPopupManager.kt`
- 悬浮窗入口隐藏：`player_component/.../controller/video/PlayerTopView.kt`
- 投屏 Sender 屏蔽：
  - `app/.../cast/Media3CastManager.kt`
  - `storage_component/.../ui/fragment/storage_file/StorageFileAdapter.kt`
  - 测试忽略：`app/src/androidTest/.../Media3CastFallbackTest.kt`
- 扫码页面禁用：`storage_component/.../ui/activities/remote_scan/RemoteScanActivity.kt`
