# Phase 1：Quickstart（百度网盘存储源）

本文档面向后续实现与自测，给出最短路径的配置、运行与验收步骤，并附带关键官方文档来源，便于实现时查原文。

## 1. 前置条件

### 1.1 百度开放平台应用与密钥

需要一个已创建的百度网盘开放平台应用，用于获得：

- `client_id`（AppKey）
- `client_secret`（SecretKey）

> 授权流程参考（官方）：设备码模式授权 `https://pan.baidu.com/union/doc/fl1x114ti`

### 1.2 本地注入方式（建议）

按项目既有模式（参考 `core_system_component/build.gradle.kts` 对 BUGLY/DanDan 凭证的注入方式），建议新增：

- `BAIDU_PAN_CLIENT_ID`
- `BAIDU_PAN_CLIENT_SECRET`

优先级建议保持一致：`ENV` → `Gradle property` → `local.properties`。

> 注意：不要硬编码密钥到仓库；敏感信息应放在 `local.properties` 或 CI Secrets（项目宪章/README 已约束）。

## 2. 构建与运行

在仓库根目录执行：

- Debug 构建：`./gradlew assembleDebug`
- 完整校验（实现阶段若涉及依赖调整）：`./gradlew verifyModuleDependencies`（或推荐 `./gradlew verifyArchitectureGovernance`）

> 依赖变更门禁要求：必须确认输出末尾为 `BUILD SUCCESSFUL`。

## 3. 手动验收用例（对应 spec P1/P2/P3）

### 3.1 P1：挂载百度网盘并播放视频

1. 打开“新增存储源”，选择“百度网盘”
2. 出现二维码后，用**百度网盘官方 App** 扫码并确认授权
3. 授权成功后进入网盘根目录（`/`）文件列表
4. 点击一个视频文件，进入播放器开始播放
5. 返回后应能回到原目录位置

关键接口链路（实现参考）：

- 获取二维码：`GET https://openapi.baidu.com/oauth/2.0/device/code`
- 轮询换 token：`GET https://openapi.baidu.com/oauth/2.0/token?grant_type=device_token...`
- 根目录列表：`GET https://pan.baidu.com/rest/2.0/xpan/file?method=list&dir=/...`
- 播放直链：`GET https://pan.baidu.com/rest/2.0/xpan/multimedia?method=filemetas&dlink=1&fsids=[...]...`

来源（官方）：

- 设备码模式授权：`https://pan.baidu.com/union/doc/fl1x114ti`
- list：`https://pan.baidu.com/union/doc/nksg0sat9`
- filemetas：`https://pan.baidu.com/union/doc/Fksg0sbcm`

### 3.2 P2：在网盘中快速定位内容

- 刷新目录、排序、分页加载（如目录文件数较大）
- 关键词搜索（若实现）：`method=search`

来源（官方）：

- search：`https://pan.baidu.com/union/doc/zksg0sb9z`

### 3.3 P3：存储源与授权状态可管理

- 断网/弱网时：加载列表与播放应有明确提示与重试入口
- access_token 过期：自动刷新继续可用（无需重新扫码）
- refresh_token 刷新失败/用户撤销授权：提示重新扫码授权
- 移除存储源：清理该账号的授权态缓存与隐私数据

来源（官方）：

- refresh_token 规则（单次可用/失败失效）：`https://pan.baidu.com/union/doc/fl1x114ti`
- 错误码总表：`https://pan.baidu.com/union/doc/okumlx17r`

## 4. 播放兼容性提示（Media3/mpv/VLC）

本项目 MVP 选择 dlink 直链播放（而不是 streaming(m3u8)），实现时需注意：

- 访问 `dlink` 时需要拼接 `access_token`，并设置 `User-Agent: pan.baidu.com`
- `dlink` 有效期约 8 小时，且可能 302 跳转；播放器 seek 会触发 Range 请求
- mpv/VLC 场景建议复用 `LocalProxy/HttpPlayServer` 注入 headers、稳定 Range（与 Alist 的处理方式一致）

来源（官方）：

- dlink 下载/播放：`https://pan.baidu.com/union/doc/pkuo3snyp`

## 5. 已知限制/风险提示

- **测试应用授权用户数限制**：错误码 `20011`（官方错误码表）可能限制测试账号数量（常见为前 10 个）。
- **频控**：部分接口可能触发频控（例如 listall 文档提到 `31034`），实现上应控制请求频率并做退避。

来源（官方）：

- 错误码总表：`https://pan.baidu.com/union/doc/okumlx17r`
- listall（频控提示）：`https://pan.baidu.com/union/doc/Zksg0sb73`

