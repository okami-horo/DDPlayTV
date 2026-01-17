# 115 Open 存储源验收记录（P1-P4）

> 注意：请勿在本文档/截图/日志中粘贴完整 `access_token` / `refresh_token`。如需留存日志，务必先做脱敏（仅保留长度/前后若干位或使用 `<redacted>`）。

## 基本信息

- 日期：2026-01-14
- 分支：`001-115-open-storage`
- 提交：`0db41b776`
- 设备/系统：
- 构建类型：Debug / Release

## 构建门禁（已执行）

- `./gradlew verifyModuleDependencies`：`BUILD SUCCESSFUL`
- `./gradlew lint`：`BUILD SUCCESSFUL`
- `./gradlew assembleRelease`：`BUILD SUCCESSFUL`

## 手动验收用例（待执行）

### P1：新增 115 Open 并浏览文件

- 记录：未验收（待补充）
- 建议留存：进入根目录列表截图、进入子目录与返回截图（可选）

### P2：选择视频并开始播放（多内核）

- 记录：未验收（待补充）
- 建议留存：同一文件在 Media3/mpv/VLC 下的播放成功截图或关键日志（可选）

### P3：刷新/排序/搜索定位

- 记录：未验收（待补充）
- 建议留存：刷新前后、排序切换、搜索结果与清空后的截图（可选）

### P4：token 失效后的可恢复体验

- 记录：未验收（待补充）
- 建议留存：仅 `access_token` 失效时自动刷新恢复；`refresh_token` 失效时提示与编辑入口（可选）

