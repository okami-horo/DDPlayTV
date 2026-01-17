# DDPlayTV

一个弹幕/字幕视频播放器实现，主要优化 **Android TV（Leanback）** 端的遥控器（DPAD）操作与交互体验，移动端依然可用。

本项目使用的弹幕相关接口来自 [弹弹play 开放平台](https://api.dandanplay.net/swagger/ui/index)。

## 下载

- Releases：`../../releases`

## 简介

本仓库以“TV 优先的交互体验”为主要目标，在保留移动端可用性的前提下，围绕播放器内核、字幕渲染、网络媒体库等方向持续演进。

## 来源

本仓库基于源项目 **DanDanPlayForAndroid** 二次开发与演进：

- 源项目仓库：https://github.com/xyoye/DanDanPlayForAndroid

如需了解更完整的“手机端定位/功能介绍/截图”，请以源项目 README 为准。

### 与源项目的差异（简述）

- 以 TV 端交互为先（Leanback 启动入口、DPAD 焦点/遥控器导航），移动端依然可用
- 播放内核调整：移除已弃用的 **IJKPlayer**，新增 **mpv**，与 **Media3 / VLC** 组成多内核方案
- 字幕渲染增强：强化 **libass**（ASS/SSA）渲染能力，提升表现一致性
- 媒体库扩展：新增 **B 站媒体库 / 115 Open / 百度网盘** 等来源；其中 115 Open 的 `access_token` / `refresh_token` 获取可参考 [OpenList 官方文档](https://www.oplist.org.cn/ecosystem/official_docs)
- TV 默认策略：对部分不适合 TV 的能力做默认裁剪/关闭（如画中画/后台播放、投屏“发送端”等）

## 功能

### 播放

- 多内核：**Media3 / VLC / mpv**（可按设备兼容性与效果切换）
- 倍速播放与长按加速、进度记忆与续播
- 轨道管理：音轨 / 字幕 / 弹幕
- 对部分远端源提供本地 HTTP 代理，改善 Seek/Range 等兼容性问题

### 弹幕

- 基于弹弹play：自动匹配、搜索与下载弹幕
- 弹幕样式：字号、速度、描边、透明度等
- 弹幕过滤：关键字屏蔽、正则表达式屏蔽

### 字幕

- 自动匹配字幕、搜索与下载字幕
- 外挂字幕加载与字幕样式调整
- **libass** 渲染（ASS/SSA），更贴近原始字幕效果

### 媒体库与网盘

支持在应用内统一浏览并播放多种来源（不同来源的搜索/鉴权能力以实际实现为准）：

| 分类 | 来源 |
| --- | --- |
| 本地 | 本地媒体库、设备存储库、外部媒体库 |
| 网络 | SMB、FTP、WebDav、Alist、PC 端媒体库 |
| 云/平台 | Bilibili 媒体库、115 Open、百度网盘 |
| 其他 | 串流视频、磁链视频、远程投屏 |

> 115 Open：需要配置 `access_token` / `refresh_token`，获取方式参考 [OpenList 官方文档](https://www.oplist.org.cn/ecosystem/official_docs)。

### 动漫

- 每周番剧、番剧搜索与详情浏览

### TV 交互

- Leanback 入口与遥控器（DPAD）操作优化
- TV 场景下的默认策略调整（例如画中画/后台播放、投屏“发送端”等默认关闭）

## 构建

在仓库根目录执行：

- `./gradlew assembleDebug`
- `./gradlew assembleRelease`

## 开发者信息

<details>
<summary>模块一览（以 <code>settings.gradle.kts</code> 为准）</summary>

- 入口：`:app`
- 业务组件：`:anime_component`、`:local_component`、`:player_component`、`:storage_component`、`:user_component`
- 基建组件：`:core_contract_component`、`:core_system_component`、`:core_log_component`、`:core_network_component`、`:core_database_component`、`:core_storage_component`、`:core_ui_component`、`:data_component`
- 扩展组件：`:bilibili_component`
- 内置依赖封装：`:repository:*`
</details>

## 注意事项

- `user_component` 默认关闭远端用户相关接口调用；如需启用请评估安全与分发风险，并遵循项目约束。

## License

Apache-2.0（见 `LICENSE`）
