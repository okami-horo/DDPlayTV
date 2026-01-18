## 1. 方案落地：StoragePlus 不再依赖“确定/取消”

- [x] 1.1 调整 `storage_component/src/main/java/com/xyoye/storage_component/ui/activities/storage_plus/StoragePlusViewModel.kt`：抽出“可复用的 upsert/校验”能力，支持不退出的保存路径（用于自动保存）。
- [x] 1.2 调整 `storage_component/src/main/java/com/xyoye/storage_component/ui/activities/storage_plus/StoragePlusActivity.kt`：保存成功不再自动 `finish()`；仅在发生过成功持久化后 `setResult(RESULT_OK)`，由用户返回/关闭弹窗退出。

## 2. 通用自动保存机制（debounce + 合法才写入）

- [x] 2.1 在 `storage_component` 内新增统一的自动保存辅助（例如 debounce Job + “silent 校验”），避免各对话框重复实现与写库抖动。
- [x] 2.2 为 WebDav/SMB/FTP/Remote/Alist 等“轻量配置”对话框接入自动保存：文本/开关变更后触发保存；输入未完成时不保存、不弹必填 toast。

## 3. 各存储源对话框改造（移除确定/取消按钮）

- [x] 3.1 WebDav：`storage_component/src/main/java/com/xyoye/storage_component/ui/dialog/WebDavStorageEditDialog.kt` 移除 `setPositiveListener/setNegativeListener`，隐藏底部按钮并接入自动保存；保留“测试连接”。
- [x] 3.2 SMB：`storage_component/src/main/java/com/xyoye/storage_component/ui/dialog/SmbStorageEditDialog.kt` 同 3.1。
- [x] 3.3 FTP：`storage_component/src/main/java/com/xyoye/storage_component/ui/dialog/FTPStorageEditDialog.kt` 同 3.1。
- [x] 3.4 Remote：`storage_component/src/main/java/com/xyoye/storage_component/ui/dialog/RemoteStorageEditDialog.kt` 同 3.1。
- [x] 3.5 Alist：`storage_component/src/main/java/com/xyoye/storage_component/ui/dialog/AlistStorageEditDialog.kt` 同 3.1。
- [x] 3.6 External：`storage_component/src/main/java/com/xyoye/storage_component/ui/dialog/ExternalStorageEditDialog.kt` 将“选择目录/授权”成功后自动保存；移除底部按钮。
- [x] 3.7 BaiduPan：`storage_component/src/main/java/com/xyoye/storage_component/ui/dialog/BaiduPanStorageEditDialog.kt` 登录/授权成功后自动保存；移除底部按钮；保持断开连接清理逻辑可用。
- [x] 3.8 Open115：`storage_component/src/main/java/com/xyoye/storage_component/ui/dialog/Open115StorageEditDialog.kt` 将保存动作收敛到“测试连接成功/明确动作完成”后自动保存；移除底部按钮，并保持 `REQUEST_CODE_OPEN115_REAUTH` 返回刷新语义不变。
- [x] 3.9 Bilibili：`storage_component/src/main/java/com/xyoye/storage_component/ui/dialog/BilibiliStorageEditDialog.kt` 偏好（API 类型/画质/编码/CDN/AI 屏蔽等）变更即时写入；登录成功后自动保存媒体库记录；移除底部按钮；保持“断开并清除数据”“恢复默认”可用。
- [x] 3.10 Screencast：`storage_component/src/main/java/com/xyoye/storage_component/ui/dialog/ScreencastStorageEditDialog.kt` 将“手动连接”从底部“确定”迁移到内容区按钮（例如直连/密码连接按钮点击即连接）；连接成功后自动保存；移除底部按钮。

## 4. 验证

- [x] 4.1 编译验证：`./gradlew :storage_component:assembleDebug`（确认输出尾部为 `BUILD SUCCESSFUL`）
- [x] 4.2 静态检查：`./gradlew lint`（或按仓库约定运行 `lintDebug`）
- [x] 4.3 依赖治理校验：`./gradlew verifyModuleDependencies`（确认输出尾部为 `BUILD SUCCESSFUL`）
- [ ] 4.4 手工验收（TV + 移动端）：
  - 进入媒体库 → 长按存储源 → 编辑：修改后直接返回，重新进入仍保持修改
  - 新增存储源：输入未完成直接返回不产生记录；完成登录/测试后自动新增
  - Bilibili/BaiduPan/Open115：登录/授权/测试成功后不点确定也能生效并触发必要刷新
  - Screencast：手动输入模式下可通过内容区按钮触发连接并保存；返回键可正常退出
