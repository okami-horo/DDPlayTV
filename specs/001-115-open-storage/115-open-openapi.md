# 115 Open API 资料整理（用于本项目：存储源浏览 + 在线播放）

> 目标：参考 OpenList（openlist）对 115 Open 的集成实现，梳理“后续在 DDPlayTV 原生集成 115 Open 云端存储”所需的全部 115 Open 平台 API，并标注关键字段、调用顺序、鉴权与坑点。
>
> 鉴权约束：与 openlist 一致——应用侧不引导用户登录账号密码；用户在应用中手动填写 `access_token` + `refresh_token` 即可（应用负责自动刷新与持久化）。

## 目录

- [0. Sources（证据来源）](#sec-0)
- [A. 源码索引（OpenList）](#sec-a)
- [1. 统一约定](#sec-1)
- [2. 鉴权（OAuth / 设备码）](#sec-2)
- [3. 用户信息](#sec-3)
- [4. 文件系统 API](#sec-4)
- [5. 上传](#sec-5)
- [6. 离线下载](#sec-6)
- [7. 回收站](#sec-7)
- [8. 最小闭环 API（建议）](#sec-8)
- [9. 实现侧注意事项](#sec-9)

---

<a id="sec-0"></a>

## 0. Sources（证据来源）

- OpenList 115 Open driver：`OpenListTeam/OpenList`（commit `85c69d853f7a6c9ffc2d3e76da9f3abaf9de5154`）
  - 入口：`drivers/115_open/driver.go`
  - 上传：`drivers/115_open/upload.go`
- 115 Open Go SDK：`OpenListTeam/115-sdk-go`（commit `eedd9bf71e55f06de6c8c2d8205051b982efaf01`）
  - API 常量：`const.go`
  - 请求封装/刷新逻辑：`request.go`、`auth.go`
  - 文件：`fs.go`
  - 上传：`upload.go`
  - 用户：`user_info.go`
- 官方公开入口：<https://open.115.com/>
- 官方语雀文档目录（可能需要登录）：<https://www.yuque.com/115yun/open>

---

<a id="sec-1"></a>

<a id="sec-a"></a>

## A. 源码索引（OpenList）

> 你已将 openlist clone 到仓库内：`OpenList/`。下面给出“从需求到源码”的导航索引，后续实现时可以直接点开定位。

### A.1 115 Open Driver（核心实现）

- Driver 注册入口：`OpenList/drivers/all.go`（包含 `drivers/115_open` 的注册）
- Driver 元信息（配置项定义）：`OpenList/drivers/115_open/meta.go`
  - `Addition.AccessToken / Addition.RefreshToken`
  - `Addition.PageSize`（默认 200，最大 1150）
  - `Addition.LimitRate`（全局请求限速）
  - `config.LinkCacheMode = driver.LinkCacheUA`
- Driver 主逻辑：`OpenList/drivers/115_open/driver.go`
  - `Init()`：初始化 SDK、注册 `onRefreshToken` 持久化 token
  - `List()`：目录分页（offset/limit 直到 `resp.Count`）
  - `Link()`：用 `pick_code` 获取直链，并返回 `User-Agent` header
  - `Put()`：上传（秒传 + 二次校验 + OSS 上传）
  - `WaitLimit()`：请求限速
- Driver 上传实现：`OpenList/drivers/115_open/upload.go`
  - `multpartUpload()`：OSS 分片上传（带 callback/callback_var）

### A.2 115 Open SDK（OpenListTeam/115-sdk-go）

> OpenList 在 `OpenList/go.mod` 中依赖 `github.com/OpenListTeam/115-sdk-go v0.2.2`。

- API 常量与 base url：参考 `115-sdk-go/const.go`
- proapi 请求封装与自动刷新：参考 `115-sdk-go/request.go`
- 设备码登录与 refresh：参考 `115-sdk-go/auth.go`
- 文件列表/搜索/直链：参考 `115-sdk-go/fs.go`
- 上传 init/get_token/resume：参考 `115-sdk-go/upload.go`

### A.3 离线下载（OpenList 内置工具链）

如果后续考虑“离线下载到 115 再转存/直下”，可参考：

- 115 Open 离线下载 tool：`OpenList/internal/offline_download/115_open/client.go`
- 离线下载任务创建与“同网盘直下”策略：`OpenList/internal/offline_download/tool/add.go`

---

## 1. 统一约定

### 1.1 Base URL

| 作用 | Base URL | 说明 |
|---|---|---|
| Open API（文件/用户/上传/离线） | `https://proapi.115.com` | 绝大多数业务 API |
| 鉴权 API（设备码/refresh） | `https://passportapi.115.com` | 获取/刷新 token |
| 二维码状态 | `https://qrcodeapi.115.com/get/status/` | 设备码登录轮询 |

（证据：`115-sdk-go/const.go`）

### 1.2 鉴权方式

- 业务 Open API：HTTP Header `Authorization: Bearer <access_token>`
  - SDK 使用 Resty 的 `SetAuthToken(access_token)` 注入（证据：`115-sdk-go/request.go`）。
- 刷新 token：调用 `POST https://passportapi.115.com/open/refreshToken`，仅携带 `refresh_token` 表单字段，不依赖 Bearer（证据：`115-sdk-go/auth.go`）。
- 下载直链/播放：除了 Bearer 外，还要注意 `User-Agent`（见 4.3）。

### 1.3 Content-Type / 参数位置

- `GET`：使用 query 参数
- `POST`：OpenList/SDK 主要用 `application/x-www-form-urlencoded`（表单）

### 1.4 通用响应结构（非常重要）

115 Open API 在不同域名/接口族上存在两类“外层响应包装”。后续实现时建议统一封装解析与错误映射。

#### A) passportapi（鉴权）返回结构（SDK `AuthResp`）

```json
{
  "state": 1,
  "code": 0,
  "message": "",
  "data": { ... },
  "error": "",
  "errno": 0
}
```

- `code != 0` 或 `error != ""` 视为失败（证据：`115-sdk-go/request.go:passportRequest`）。

#### B) proapi（业务）返回结构（SDK `Resp`）

```json
{
  "state": true,
  "code": 0,
  "message": "",
  "data": { ... }
}
```

- `state == false` 视为失败；其中部分 `code` 代表 token 失效（见 2.4）。

### 1.5 token 失效与自动刷新语义

115-sdk-go 的行为（建议我们实现保持一致）：

- 当 proapi 返回 `state=false` 且：
  - `code == 99`，或
  - `code` 以 `401` 开头（例如 `401xxxx`）
- SDK 会自动调用 `RefreshToken()` 刷新，再重试一次原请求（证据：`115-sdk-go/request.go:authRequest`）。

OpenList 的驱动在初始化 SDK 时注册了 `onRefreshToken` 回调，用于把新的 `access_token/refresh_token` 持久化（证据：`OpenList/drivers/115_open/driver.go:39-45`）。

---

<a id="sec-2"></a>

## 2. 鉴权（OAuth / 设备码）

> 说明：本项目集成目标是“用户手动填 token”，因此下面的“设备码登录”主要用于解释 token 如何产生、以及后续若要做 UI 引导/辅助获取 token 时的参考。

### 2.1 申请设备码（生成二维码）

- `POST https://passportapi.115.com/open/authDeviceCode`
- 表单参数：
  - `client_id`: 开放平台应用 ID
  - `code_challenge`: `base64(sha256(code_verifier))`
  - `code_challenge_method`: 固定 `sha256`
- 返回（关键字段）：
  - `uid`: 本次设备码会话 id（后续换 token）
  - `time`: 时间戳（后续查询二维码状态）
  - `qrcode`: 二维码内容（具体形式以官方返回为准）
  - `sign`: 签名（后续查询二维码状态）

（证据：`115-sdk-go/auth.go:AuthDeviceCode`、`115-sdk-go/const.go:ApiAuthDeviceCode`）

### 2.2 轮询二维码状态

- `GET https://qrcodeapi.115.com/get/status/`
- query 参数：`uid`、`time`、`sign`
- 返回（关键字段）：
  - `status`: 状态码（含义需对照官方文档；SDK 仅透传）
  - `msg`: 文案

（证据：`115-sdk-go/auth.go:QrCodeStatus`、`115-sdk-go/const.go:ApiQrCodeStatus`）

### 2.3 使用设备码换取 token

- `POST https://passportapi.115.com/open/deviceCodeToToken`
- 表单参数：
  - `uid`
  - `code_verifier`（与 2.1 生成 `code_challenge` 的 verifier 配对）
- 返回（关键字段）：
  - `access_token`
  - `refresh_token`
  - `expires_in`

（证据：`115-sdk-go/auth.go:CodeToToken`、`115-sdk-go/const.go:ApiCodeToToken`）

### 2.4 刷新 token

- `POST https://passportapi.115.com/open/refreshToken`
- 表单参数：
  - `refresh_token`
- 返回（关键字段）：
  - `access_token`
  - `refresh_token`
  - `expires_in`

（证据：`115-sdk-go/auth.go:RefreshToken`、`115-sdk-go/const.go:ApiRefreshToken`）

---

<a id="sec-3"></a>

## 3. 用户信息（用于：测试 token / 多账号区分 / 展示存储源）

### 3.1 获取用户信息与容量

- `GET https://proapi.115.com/open/user/info`
- Header：`Authorization: Bearer <access_token>`
- 返回（关键字段，SDK `UserInfoResp`）：
  - `user_id`
  - `user_name`
  - `user_face_s/m/l`
  - `rt_space_info.all_total/all_use/all_remain.size`
  - `vip_info.level_name/expire`

（证据：`115-sdk-go/user_info.go`）

建议用途：
- **连接测试**：新增存储源时，调用一次 `user/info` 判定 token 是否可用。
- **多账号唯一标识**：优先使用 `user_id` 构造 storageKey/URL（类似百度网盘用 `uk`）。

---

<a id="sec-4"></a>

## 4. 文件系统 API（目录浏览 / 搜索 / 播放直链）

> OpenList 的 115 Open driver 依赖的核心闭环：
> `GetFiles(列目录)` → `DownURL(取直链)` → 播放器使用该 URL（并带 UA）。

### 4.1 文件/目录数据模型（重要字段）

`GET /open/ufile/files` 返回的 `data[]` 元素（SDK `GetFilesResp_File`）常用字段：

- `fid`: 文件/文件夹 ID（字符串）
- `pid`: 父目录 ID
- `fc`: 文件分类：`"0"` 目录，`"1"` 文件（OpenList 以 `fc=="0"` 判定目录）
- `fn`: 名称
- `pc`: pick_code（提取码，获取下载直链用）
- `sha1`: 文件 SHA1（可用于上传/校验；目录通常为空）
- `fs`: 文件大小（字节）
- `upt/uet/uppt`: 时间戳（修改/更新时间/上传时间）
- `isv`: 是否视频（1/0）
- `ico`: 后缀名
- `thumb`: 缩略图

（证据：`115-sdk-go/fs.go:GetFilesResp_File`、`OpenList/drivers/115_open/types.go`）

### 4.2 列目录（核心）

- `GET https://proapi.115.com/open/ufile/files`
- Header：`Authorization: Bearer <access_token>`
- query 参数（SDK `GetFilesReq`）：
  - `cid`: 当前目录 ID（根目录通常为 `0`）
  - `limit`: 单页数量（OpenList 默认 200，最大 1150）
  - `offset`: 偏移量（OpenList 用 offset += limit 直到 `count`）
  - `asc`: `1` 升序 / `0` 降序
  - `o`: 排序字段：`file_name` / `file_size` / `user_utime` / `file_type`
  - `show_dir`: `1` 显示目录；OpenList 固定为 true
  - 其他可选：`type`、`suffix`、`star`、`cur`、`stdir`...
- 返回：
  - `data`: 文件数组
  - `count`: 当前目录总数

（证据：`115-sdk-go/fs.go:GetFiles`、`OpenList/drivers/115_open/driver.go:List`、`OpenList/drivers/115_open/meta.go`）

实现提示：
- **分页策略**：按 `offset/limit` 累积读取，直到 `len(res) >= resp.count`。
- **限速**：OpenList 在 driver 里提供 `limit_rate`（r/s）并在每次请求前 `WaitLimit()`；后续实现可参考此策略。

### 4.3 获取下载/播放直链（核心）

- `POST https://proapi.115.com/open/ufile/downurl`
- Header：
  - `Authorization: Bearer <access_token>`
  - `User-Agent: <ua>`（建议显式设置；OpenList 允许从调用方传入，否则用默认 UA）
- 表单参数：
  - `pick_code`: 从文件对象的 `pc` 字段获取
- 返回：`map[string]DownURLItem`（键通常是 `fid`；OpenList 通过 `resp[obj.fid]` 取值）
  - `url.url`: 直链
  - `file_name/file_size/pick_code/sha1`

（证据：`115-sdk-go/fs.go:DownURL`、`OpenList/drivers/115_open/driver.go:Link`）

播放注意事项（和本项目已有 115 风控经验有关）：
- 项目已有文档表明：115 对高频随机 `Range` 读取可能返回 `403`（见 `document/support/mpv-115-proxy.md`）。
- 因此后续实现建议：
  - **统一注入 UA**（至少对直链请求）
  - **对 mpv/VLC 优先走本地代理**（`HttpPlayServer`）以“串行化 Range + 限频 + 窗口裁剪”，降低风控风险

### 4.4 搜索（可选增强）

- `GET https://proapi.115.com/open/ufile/search`
- Header：`Authorization: Bearer <access_token>`
- query 参数（SDK `SearchFilesReq`）：
  - `search_value`: 关键词
  - `limit`: 单页数量（SDK 注释：默认 20，且 `offset + limit` 最大不超过 10000）
  - `offset`
  - `cid`: 目标目录；SDK 注释提到 `cid=-1` 表示不返回列表
  - `fc`: 仅文件夹/仅文件（注释：`1` 文件夹、`2` 文件）
  - `type`: 一级分类（文档/图片/音乐/视频/压缩包/应用）
  - `suffix`: 后缀

（证据：`115-sdk-go/fs.go:SearchFilesReq/SearchFiles`）

### 4.5 创建目录（mkdir）

- `POST https://proapi.115.com/open/folder/add`
- Header：`Authorization: Bearer <access_token>`
- 表单参数：
  - `pid`: 父目录 ID
  - `file_name`: 新目录名

（证据：`115-sdk-go/fs.go:Mkdir`、`OpenList/drivers/115_open/driver.go:MakeDir`）

### 4.6 文件操作（后续可按需支持）

#### 4.6.1 移动

- `POST https://proapi.115.com/open/ufile/move`
- 表单参数：
  - `file_ids`: 需要移动的文件(夹)ID（多个用 `,`）
  - `to_cid`: 目标目录 ID（根目录为 0）

（证据：`115-sdk-go/fs.go:Move`、`OpenList/drivers/115_open/driver.go:Move`）

#### 4.6.2 复制

- `POST https://proapi.115.com/open/ufile/copy`
- 表单参数：
  - `pid`: 目标目录
  - `file_id`: 要复制的文件/目录 ID（多个用 `,`）
  - `no_dupli`: 目标目录是否允许重名（`0` 允许，`1` 不允许）

（证据：`115-sdk-go/fs.go:Copy`、`OpenList/drivers/115_open/driver.go:Copy`）

#### 4.6.3 重命名/更新

- `POST https://proapi.115.com/open/ufile/update`
- 表单参数：
  - `file_id`: 文件(夹)ID
  - `file_name`: 新名字
  - `star`: 星标（可选）

（证据：`115-sdk-go/fs.go:UpdateFile`、`OpenList/drivers/115_open/driver.go:Rename`）

#### 4.6.4 删除

- `POST https://proapi.115.com/open/ufile/delete`
- 表单参数：
  - `file_ids`: 文件(夹)ID（多个用 `,`）
  - `parent_id`: 父目录 ID（被删对象所在目录）

（证据：`115-sdk-go/fs.go:DelFile`、`OpenList/drivers/115_open/driver.go:Remove`）

### 4.7 获取文件夹信息（OpenList 未用，但可能有价值）

- `GET https://proapi.115.com/open/folder/get_info`
- 参数：`file_id=<folderId>`

（证据：`115-sdk-go/const.go:ApiFsGetFolderInfo`、`115-sdk-go/fs.go:GetFolderInfo/GetFolderInfoByPath`）

可能用途：
- 从 `cid` 获取目录元数据（例如面包屑/目录树展示）。

---

<a id="sec-5"></a>

## 5. 上传（可选；OpenList 已实现完整流程）

> 若首期只做“浏览 + 播放”，上传可以不实现；但 API 文档仍整理完整，以便后续扩展。

### 5.1 获取上传 STS 凭证

- `GET https://proapi.115.com/open/upload/get_token`
- Header：`Authorization: Bearer <access_token>`
- 返回：OSS 临时凭证与 endpoint
  - `endpoint`
  - `AccessKeyId/AccessKeySecret`
  - `SecurityToken`
  - `expiration`

（证据：`115-sdk-go/upload.go:UploadGetToken`）

### 5.2 上传初始化（支持“秒传”与“二次校验”）

- `POST https://proapi.115.com/open/upload/init`
- 表单参数（SDK `UploadInitReq`）：
  - `file_name`
  - `file_size`
  - `target`: 目标目录 id（SDK 实际会拼 `U_1_<cid>`）
  - `fileid`: **文件全量 SHA1（大写）**
  - `preid`: **文件前 128KB 的 SHA1（大写）**
  - 可选：`sign_key` / `sign_val`
- 返回（关键字段，SDK `UploadInitResp`）：
  - `status`:
    - `2`：秒传成功（无需实际上传内容）
    - `6/7/8`：需要按 `sign_check` 指定的范围做二次校验
  - `sign_check`: 形如 `"2392148-2392298"`，表示需读取该范围（含端点）计算 SHA1
  - `sign_key`: 二次校验时回传
  - `bucket/object/callback`: OSS 上传所需信息

（证据：`OpenList/drivers/115_open/driver.go:Put`、`115-sdk-go/upload.go:UploadInit`）

### 5.3 二次校验（two-way verify）

当 `status` 为 6/7/8 时：
1. 按 `sign_check` 解析 range：`start-end`，读取 `[start, end]` 字节
2. 计算该段 SHA1 得到 `sign_val`
3. 再次调用 `upload/init`，携带 `sign_key` + `sign_val`

（证据：`OpenList/drivers/115_open/driver.go:264-299`）

### 5.4 实际上传：阿里云 OSS（不再走 115 API）

OpenList 的实现：
- 使用 `upload/get_token` 返回的 STS 创建 OSS client
- `upload/init` 返回 `bucket/object/callback` 等信息
- 单文件：`PutObject`
- 分片：`InitiateMultipartUpload` + `UploadPart` + `CompleteMultipartUpload`

（证据：`OpenList/drivers/115_open/upload.go`）

### 5.5 断点续传（SDK 提供，OpenList 当前未用）

- `POST https://proapi.115.com/open/upload/resume`
- 参数（SDK `UploadResumeReq`）：`file_size`、`target`、`fileid`、`pick_code` 等（具体以官方文档/SDK 为准）

（证据：`115-sdk-go/upload.go:UploadResume`）

---

<a id="sec-6"></a>

## 6. 离线下载（可选；OpenList 离线下载工具链使用）

> OpenList 内置 offline_download tool 对 115 Open 做了适配；DDPlayTV 是否需要可后续评估。

### 6.1 新增离线任务

- `POST https://proapi.115.com/open/offline/add_task_urls`
- 表单参数：
  - `urls`: 多个 URL 用 `\n` 换行拼接
  - `wp_path_id`: 保存到的目录 ID
- 返回：数组，每项可能包含 `state/code/message/info_hash/url`

（证据：`115-sdk-go/offline.go:AddOfflineTaskURIs`）

### 6.2 删除离线任务

- `POST https://proapi.115.com/open/offline/del_task`
- 表单参数：
  - `info_hash`
  - `del_source_file`: `0/1` 是否删除源文件

（证据：`115-sdk-go/offline.go:DeleteOfflineTask`）

### 6.3 查询离线任务列表

- `GET https://proapi.115.com/open/offline/get_task_list`
- 参数：`page`
- 返回：`tasks[]`，包含 `percentDone/status/file_id/wp_path_id` 等

（证据：`115-sdk-go/offline.go:OfflineTaskList`）

---

<a id="sec-7"></a>

## 7. 回收站（可选）

- `GET https://proapi.115.com/open/rb/list`
- `POST https://proapi.115.com/open/rb/revert`
- `POST https://proapi.115.com/open/rb/del`

（证据：`115-sdk-go/const.go`、`115-sdk-go/fs.go:RbList/RbRevert/RbDelete`）

---

<a id="sec-8"></a>

## 8. “本项目集成 115 Open”最小闭环所需 API（建议）

如果目标对齐百度网盘存储源 MVP（浏览 + 选视频播放），最小集合建议：

1. `GET /open/user/info`：验证 token、拿 `user_id` 做账号标识
2. `GET /open/ufile/files`：目录浏览（分页）
3. `POST /open/ufile/downurl`：获取播放直链
4. `POST /open/refreshToken`：token 失效自动刷新

可选增强：
- `GET /open/ufile/search`：搜索
- `POST /open/folder/add`：新建目录

---

<a id="sec-9"></a>

## 9. 实现侧注意事项（为后续设计/落地预留）

- **token 存储与刷新**：建议复用百度网盘的“按 storageKey 隔离 + 串行刷新 + 原子写入”思路；OpenList/SDK 的 `onRefreshToken` 机制可作为参考。
- **播放稳定性（115 风控）**：建议把 115 Open 的直链也纳入现有 `HttpPlayServer` 代理策略（至少对 mpv）；并确保 UA/Range 行为可控。
- **限速**：OpenList 暴露 `limit_rate`（r/s）对所有 API 做节流；如果 DDPlayTV 需要更稳健，建议在 Repository 层引入类似 limiter。
