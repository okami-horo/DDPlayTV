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
  - Manifest / 入口：`storage_component/src/main/AndroidManifest.xml` 中的 `<service android:name=".services.ScreencastProvideService">` 以注释方式下线；`app/src/main/java/com/xyoye/dandanplay/ui/main/MainActivity.kt`、`local_component/src/main/java/com/xyoye/local_component/ui/fragment/media/MediaFragment.kt`、`storage_component/src/main/java/com/xyoye/storage_component/ui/activities/storage_file/StorageFileActivity.kt` 移除 `ScreencastProvideService` 注入及启动逻辑，Media 页面对 `MediaType.SCREEN_CAST` 直接提示“电视端不支持发起投屏”，并过滤掉新增/展示入口，彻底杜绝 Sender 触发点。

- 扫码（相机）入口
  - 源码：`storage_component/src/main/java/com/xyoye/storage_component/ui/activities/remote_scan/RemoteScanActivity.kt`
  - 处理：`initView` 直接提示“电视端不支持扫码功能”并 `finish()`，原三星/ScanKit 实现整体块注释保留。

- 扫码入口调用（RemoteStorage/Screencast 对话框）
  - 源码：`storage_component/src/main/java/com/xyoye/storage_component/ui/dialog/RemoteStorageEditDialog.kt`、`storage_component/src/main/java/com/xyoye/storage_component/ui/dialog/ScreencastStorageEditDialog.kt`
  - 处理：扫码按钮默认 `isVisible = false`，点击监听置空，同时将 `ScanActivityLauncher` 成员与回调整体块注释，避免 TV 端再拉起摄像头能力。

- 摄像头/震动权限
  - 源码：`common_component/src/main/AndroidManifest.xml`、`storage_component/src/main/AndroidManifest.xml`、`common_component/src/main/java/com/xyoye/common_component/application/permission/Permission.kt`
  - 处理：`<uses-permission android:name="android.permission.CAMERA" />`、`android.permission.VIBRATE` 及 `Permission.camera` 的权限数组全部注释，改以 `emptyArray()` 保留占位，TV 包不再请求无用权限。

- 播放器模块权限声明（SYSTEM_ALERT_WINDOW / REORDER_TASKS）
  - 源码：`player_component/src/main/AndroidManifest.xml`
  - 处理：TV flavor 下直接删除上述 `<uses-permission>`，与已隐藏的悬浮窗/任务管理入口保持一致，避免 Manifest 再暴露多余权限。

- 画中画（PIP）/后台播放协调
  - 源码：`app/src/main/java/com/xyoye/dandanplay/app/service/Media3BackgroundCoordinator.kt`
  - 处理：`sync` 逻辑直接清空所有 `MediaSession` 命令与 `BackgroundMode`，原能力实现整体块注释，防止 TV 端暴露 PIP/后台播放命令。
  - 测试：`app/src/androidTest/java/com/xyoye/dandanplay/app/Media3BackgroundTest.kt` 添加 `@Ignore`，记录 TV 端暂不验证该能力。

- 播放器设置中的“后台播放”开关
  - 源码：`player_component/src/main/java/com/xyoye/player/controller/setting/PlayerSettingView.kt`
  - 处理：`SettingAction.BACKGROUND_PLAY` 在 `generateItems()` 中加入 `disabledActions`，TV 端不再展示该入口（即使 `PlayerConfig.isBackgroundPlay()` 切换也不会生效），避免误导用户。

- 下拉刷新（`SwipeRefreshLayout`）入口
  - 源码：`local_component/src/main/res/layout/activity_shooter_subtitle.xml`、`storage_component/src/main/res/layout/fragment_storage_file.xml`
  - 处理：TV 端移除 `SwipeRefreshLayout`，改为纯 `RecyclerView`；刷新通过“返回上级后重新进入”实现，不再保留不可达的下拉动作，`StorageFileFragment` 也同步删去 `refreshLayout` 逻辑。

- 账户（登录/注册/找回）入口
  - 源码：`user_component/src/main/java/com/xyoye/user_component/ui/activities/login/LoginActivity.kt`
  - 处理：仅保留登录功能；注册/找回相关 Activity、ViewModel、布局及路由 (`/user/register`、`/user/forgot`) 全部移除，Manifest 及 `RouteTable` 也删去对应声明，登陆页 UI 不再展示“注册/找回”按钮。

- 意见反馈与分享/邮件
  - 源码：`user_component/src/main/java/com/xyoye/user_component/ui/activities/feedback/FeedbackActivity.kt`
  - 处理：保留 FAQ 展示，仅对 Email/QQ/Issues 的入口 `View` 调用 `isVisible = false` 隐藏；原 QQ/邮件/Issues 逻辑仍使用块注释保留，TV 端不会再出现分享按钮。

- 截图功能
  - 源码：`player_component/src/main/java/com/xyoye/player/controller/video/PlayerControlView.kt`、`player_component/src/main/java/com/xyoye/player/controller/setting/PlayerSettingView.kt`、`player_component/src/main/java/com/xyoye/player/controller/setting/SettingController.kt`
  - 处理：控制器上的截图按钮默认隐藏并禁用点击，播放器设置页 `SettingAction` 过滤掉“截屏”项，`SettingController` 中的 `SCREEN_SHOT` 分支改为直接抛出 `UnsupportedOperationException`，并通过块注释保留原实现。

## 保留且适合 TV 的能力（未裁剪）

- 播放器 DPAD 控制：`player_component/src/main/java/com/xyoye/player/controller/base/TvVideoController.kt`
- 投屏接收端（Receiver）相关：`storage_component/src/main/java/com/xyoye/storage_component/services/ScreencastReceiveService.kt` 等（TV 通常作为接收端，保留）。

## 待裁剪 / 待改造清单（未注释但建议跟进）

1) UI/交互与入口
- 番剧/磁链列表中的长按操作在 TV 上可用性较差
  - 源码：
    - 番剧分集：`anime_component/src/main/java/com/xyoye/anime_component/ui/fragment/anime_episode/AnimeEpisodeFragment.kt`
      - 长按分集条目进入“批量标记已观看”模式；
    - 磁链搜索：`anime_component/src/main/java/com/xyoye/anime_component/ui/fragment/search_magnet/SearchMagnetFragment.kt`
      - 长按结果条目弹出操作对话框。
  - 现状：遥控器理论上可以通过长按确认键触发 `setOnLongClickListener`，但入口不直观，也缺少 TV 端专门的焦点/引导设计；
  - 建议：后续在 TV 端增加显式的“更多操作/批量标记”按钮或菜单键支持（例如在条目右侧增加按钮，或在标题栏添加“操作”入口），将长按交互降级为辅助手段。

2) 功能点评估（按需）

3) 构建策略
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
  - 文件列表“更多”菜单无“投屏”，返回主界面时不会弹出“投屏投送服务正在运行”的退出提示（Sender 已关闭）；
  - 扫码功能点击时提示不支持并返回；
  - 反馈页不触发“发送邮件”的外跳（若未隐藏，需跟进）。
  - 播放器设置页已隐藏“后台播放”开关；需确认其余设置项仍可保存，并验证播放退出后不会残留后台播放提示。
  - 射手字幕页与网络存储页不再出现无法触发的下拉刷新动画，重新进入页面可刷新数据。
  - 个人中心/登录页仅提供“登录”入口，不再出现“注册/找回”按钮，尝试访问 `/user/register`、`/user/forgot` 不会被路由到 Activity。
  - 番剧详情 / 磁链搜索：
    - 使用遥控器长按剧集条目可否进入“标记模式”，长按磁链条目可否弹出操作对话框；
    - 如长按体验较差，优先评估是否需要按上述待改造建议添加显式入口。

## 附：本次改动明细（供追踪）
- 手势控制注释：`player_component/.../GestureVideoController.kt`
- 悬浮窗管理注释：`player_component/.../widgets/popup/PlayerPopupManager.kt`
- 悬浮窗入口隐藏：`player_component/.../controller/video/PlayerTopView.kt`
- 投屏 Sender 屏蔽：
  - `app/.../cast/Media3CastManager.kt`
  - `storage_component/.../ui/fragment/storage_file/StorageFileAdapter.kt`
  - 测试忽略：`app/src/androidTest/.../Media3CastFallbackTest.kt`
- Sender 服务摘除：`storage_component/src/main/AndroidManifest.xml`、`app/src/main/java/com/xyoye/dandanplay/ui/main/MainActivity.kt`、`local_component/src/main/java/com/xyoye/local_component/ui/fragment/media/MediaFragment.kt`、`storage_component/src/main/java/com/xyoye/storage_component/ui/activities/storage_file/StorageFileActivity.kt`
- 扫码页面禁用：`storage_component/.../ui/activities/remote_scan/RemoteScanActivity.kt`
