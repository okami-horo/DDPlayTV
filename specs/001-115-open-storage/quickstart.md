# Phase 1：Quickstart（115 Open 存储源）

本文档面向后续实现与自测，给出最短路径的配置、运行与验收步骤，并附带关键资料来源，便于实现时查原文。

## 1. 前置条件

### 1.1 准备 token

本功能的鉴权约束为“用户手动填写 token”：

- `access_token`
- `refresh_token`

应用不提供账号密码登录，也不内置设备码扫码授权流程（详见 `spec.md` 的 Out of Scope 与 FR-002）。

> 资料来源：`specs/001-115-open-storage/115-open-openapi.md`（鉴权与 token 刷新接口整理）

### 1.2 115 Open API 基础信息（实现/联调用）

- 业务 Open API Base：`https://proapi.115.com`
- 鉴权/刷新 Base：`https://passportapi.115.com`
- proapi 鉴权：`Authorization: Bearer <access_token>`
- 刷新 token：`POST https://passportapi.115.com/open/refreshToken`（form: `refresh_token`）

> 资料来源：`specs/001-115-open-storage/115-open-openapi.md`（1.1/1.2/2.4）

## 2. 构建与运行

在仓库根目录执行：

- Debug 构建：`./gradlew assembleDebug`
- 完整校验（实现阶段若涉及依赖调整）：`./gradlew verifyModuleDependencies`（或推荐 `./gradlew verifyArchitectureGovernance`）

> 依赖变更门禁要求：必须确认输出末尾为 `BUILD SUCCESSFUL`。

## 3. 手动验收用例（对应 spec P1/P2/P3/P4）

### 3.1 P1：新增 115 Open 并浏览文件

1. 打开“新增存储源”，选择“115 Open”
2. 在配置页粘贴 `access_token` 与 `refresh_token`（UI 默认脱敏展示）
3. 点击“测试连接/保存”（具体交互对齐百度网盘）
4. 成功后进入 115 根目录（cid=0）文件列表
5. 进入任意子目录并返回，验证层级不混淆

关键接口链路（实现参考）：

- token 校验 + 取 uid：`GET https://proapi.115.com/open/user/info`
- 根目录列表：`GET https://proapi.115.com/open/ufile/files?cid=0&limit=...&offset=...`

### 3.2 P2：选择视频并开始播放（多内核）

1. 在 115 文件列表中点击一个视频文件
2. 验证进入播放器并开始播放
3. 在设置中分别切换 Media3/Exo、mpv、VLC，再次播放同一文件验证均可播放（FR-013）

关键接口链路（实现参考）：

- 直链：`POST https://proapi.115.com/open/ufile/downurl`（form: `pick_code`）
- 访问直链需带 `User-Agent`（具体 UA 取值见 `research.md` 的决策说明）

### 3.3 P3：刷新/排序/搜索定位

1. 在当前目录执行刷新，列表应更新且保持上下文稳定
2. 切换排序（名称/大小/时间等）并验证结果
3. 输入关键词搜索：结果仅返回“匹配的可播放视频文件”，清空关键词后恢复当前目录列表

关键接口链路（实现参考）：

- 搜索：`GET https://proapi.115.com/open/ufile/search?search_value=...&cid=<currentCid>&type=4&fc=2&limit=...&offset=...`

### 3.4 P4：token 失效后的可恢复体验

1. 将 `access_token` 改为过期/无效值（保留有效 `refresh_token`）后进入目录或播放
2. 预期：应用自动刷新 token 并恢复访问（FR-010）
3. 将 `refresh_token` 也改为无效值后重试
4. 预期：提示“授权失效/需要更新 token”，并提供编辑入口（FR-009）

关键接口链路（实现参考）：

- 自动刷新：`POST https://passportapi.115.com/open/refreshToken`
- 刷新触发条件：proapi `state=false` 且 `code==99` 或 `code` 以 `401` 开头（参考 `research.md`）

## 4. 播放稳定性提示（115 风控 / Range）

项目已有“mpv 播放 115（Alist 挂载）触发风控”经验：mpv 在探测阶段会高频随机 Range，易导致上游返回 403。建议：

- 对 mpv/VLC 默认走 `LocalProxy/HttpPlayServer`（上游 headers 注入 UA，并控制 Range 行为）

资料：`document/support/mpv-115-proxy.md`

## 5. 已知限制/风险提示

- **token 保护**：UI/日志不得输出完整 token（FR-012/FR-016）。
- **搜索限制**：`offset + limit` 最大不超过 10000（见 115-sdk-go 注释）；UI 需提示或做限制策略。
- **风控策略会变化**：115 对 Range/频率/UA 的判定可能随时间变化；实现需保留“代理/限速/降级”调参空间。

