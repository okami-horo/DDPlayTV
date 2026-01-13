# 百度网盘开放平台 API 资料整理（用于本项目：存储源浏览 + 在线播放）

> 目标：支撑 `specs/001-baidu-pan-storage/spec.md` 的 MVP（浏览目录 + 选择视频直接播放），整理实现所需的官方 API 文档与关键字段/调用顺序。
>
> 范围：仅使用百度网盘开放平台 OAuth / OpenAPI（不包含模拟网页登录、BDUSS/Cookie 等非官方鉴权）。

---

## 0. 统一约定

### 0.1 鉴权参数位置

- 大部分网盘 OpenAPI 使用 `access_token` 作为 URL 参数（query）进行鉴权。
- 部分文档示例要求设置 `User-Agent: pan.baidu.com`（尤其是下载/直链与部分列表）。

### 0.2 scope（最小权限）

- 官方文档在授权码模式与设备码模式中均明确 `scope` 固定为：`basic,netdisk`。

文档：
- 设备码模式授权：`https://pan.baidu.com/union/doc/fl1x114ti`
- 授权码模式授权：`https://pan.baidu.com/union/doc/al0rwqzzl`

### 0.3 refresh_token 使用规则（重要）

- `access_token` 有效期：30 天（`expires_in=2592000` 秒）。
- `refresh_token` 有效期：10 年。
- **refresh_token 只支持使用一次**：每次刷新成功会返回新的 `refresh_token`，旧的立即失效。
- **刷新失败时旧 refresh_token 也会失效**：需要重新发起授权（这会显著影响产品体验，需要在实现里特别处理重试/兜底）。

来源：
- 设备码模式授权文档：`https://pan.baidu.com/union/doc/fl1x114ti`
- 授权码模式授权文档：`https://pan.baidu.com/union/doc/al0rwqzzl`

---

## 1. 授权（OAuth2）

本功能首选：**设备码模式（Device Code）+ 二维码授权**，与 `spec.md` 中“扫码授权登录”一致。

### 1.1 设备码模式授权（扫码）

文档：`https://pan.baidu.com/union/doc/fl1x114ti`

#### 1.1.1 获取 device_code / user_code / qrcode_url

- 请求：`GET https://openapi.baidu.com/oauth/2.0/device/code`
- 参数：
  - `response_type=device_code`（固定）
  - `client_id=<AppKey>`
  - `scope=basic,netdisk`（固定）
- 返回（关键字段）：
  - `device_code`：后续换取 token 的票据
  - `user_code`：用户码
  - `verification_url`：输入 user_code 的网页
  - `qrcode_url`：二维码 url（用于生成二维码图片）
  - `expires_in`：device_code 过期时间（秒）
  - `interval`：轮询间隔（秒，通常 5，且不应低于 5s）

补充：文档给出二维码内容拼接规则：
- `https://openapi.baidu.com/device?display=mobile&code=<user_code>`

#### 1.1.2 轮询换取 access_token / refresh_token

- 请求：`GET https://openapi.baidu.com/oauth/2.0/token`
- 参数：
  - `grant_type=device_token`
  - `code=<device_code>`
  - `client_id=<AppKey>`
  - `client_secret=<SecretKey>`
- 返回（关键字段）：
  - `access_token`
  - `refresh_token`
  - `expires_in`
  - `scope`（如 `basic netdisk`）

注意：
- 轮询间隔应 ≥ `interval`（且文档强调一般不能低于 5 秒）。

#### 1.1.3 刷新 access_token

- 请求：`GET https://openapi.baidu.com/oauth/2.0/token`
- 参数：
  - `grant_type=refresh_token`
  - `refresh_token=<refresh_token>`
  - `client_id=<AppKey>`
  - `client_secret=<SecretKey>`
- 返回：新的 `access_token` 与新的 `refresh_token`。

### 1.2 授权码模式（备用/参考）

文档：`https://pan.baidu.com/union/doc/al0rwqzzl`

- 授权页：`GET https://openapi.baidu.com/oauth/2.0/authorize?response_type=code&client_id=...&redirect_uri=...&scope=basic,netdisk...`
- 该模式支持 `qrcode=1` 让用户以扫码方式登录百度账号（但本项目的“扫码授权”首期不走此模式）。

---

## 2. 用户与账号信息（可用于存储源展示/多账号区分）

### 2.1 获取用户信息（uinfo）

文档：`https://pan.baidu.com/union/doc/pksg0s9ns`

- 请求：`GET https://pan.baidu.com/rest/2.0/xpan/nas?method=uinfo&access_token=...`
- 可选参数：`vip_version=v2`（返回真实身份信息）
- 返回关键字段：
  - `uk`：用户 ID（建议作为“百度网盘存储源”账号标识的一部分）
  - `netdisk_name` / `baidu_name`
  - `avatar_url`
  - `vip_type`

---

## 3. 文件浏览（目录/递归/分类/搜索）

### 3.1 获取目录文件列表（list）

文档：`https://pan.baidu.com/union/doc/nksg0sat9`

- 请求：`GET https://pan.baidu.com/rest/2.0/xpan/file?method=list&access_token=...`
- 常用参数：
  - `dir`：目录绝对路径（以 `/` 开头，含中文需 UrlEncode），默认 `/`
  - `order`：`name|time|size`
  - `desc`：`0|1`
  - `start` / `limit`：分页（文档称默认 1000，建议 ≤ 1000）
  - `web=1`：返回缩略图 `thumbs` 与 `dir_empty`
  - `folder=1`：只返回文件夹
- 返回（list[] 关键字段）：
  - `fs_id`：文件唯一 ID（后续 `filemetas` 获取 `dlink`/媒体信息用）
  - `path`：文件绝对路径
  - `server_filename`
  - `isdir`：0 文件 / 1 目录
  - `category`：1 视频 / 2 音频 / 3 图片 / 4 文档 / 5 应用 / 6 其他 / 7 种子
  - `size`、`server_mtime/server_ctime` 等
- 典型错误码：`-7` 无权访问、`-9` 不存在（更全见错误码文档）。

### 3.2 递归获取文件列表（listall）

文档：`https://pan.baidu.com/union/doc/Zksg0sb73`

- 请求：`GET https://pan.baidu.com/rest/2.0/xpan/multimedia?method=listall&access_token=...`
- 参数：
  - `path`：目录绝对路径（必填）
  - `recursion=1`：递归
  - `start` / `limit`：分页（返回 `cursor` 用于下一页起点）
  - `web=1`：返回缩略图
- 返回：`has_more`、`cursor`、`list[]`。

注意：文档提到 `31034` 频控（建议 `listall` 请求频率不超过每分钟 8-10 次）。

### 3.3 获取分类文件列表（categorylist）

文档：`https://pan.baidu.com/union/doc/Sksg0sb40`

- 请求：`GET https://pan.baidu.com/rest/2.0/xpan/multimedia?method=categorylist&access_token=...`
- 参数：
  - `category`：`1..7`，可多值逗号分隔
  - `parent_path`：筛选目录（可选）
  - `recursion=1`：递归（注意文档提示 recursion=1 时不支持 show_dir=1）
  - `ext`：后缀过滤（可选）
  - `start` / `limit`、`order` / `desc`

### 3.4 搜索文件（关键词搜索）

文档：`https://pan.baidu.com/union/doc/zksg0sb9z`

- 请求：`GET https://pan.baidu.com/rest/2.0/xpan/file?method=search&access_token=...`
- 参数（关键）：
  - `key`：搜索关键字（必填，<=30 字符 UTF8）
  - `dir`：搜索目录（可选，默认根目录）
  - `recursion`：是否递归
  - `category`：分类过滤（可选）
  - `num`：文档显示固定为 500（且不能修改）

---

## 4. 播放/下载：获取可播放 URL

本项目播放器链路（见仓库现有实现）：
- `Storage.createPlayUrl(...)` → 生成可播放 URL
- `Storage.getNetworkHeaders(file)` → 生成 headers（传给 Media3/mpv/VLC）
- mpv/VLC 场景常通过 `LocalProxy` + `HttpPlayServer` 代理，以强化 Range/headers 透传。

### 4.1 直链（dlink）+ Range（推荐：原画/通用播放器）

#### 4.1.1 查询文件信息（filemetas）

文档：`https://pan.baidu.com/union/doc/Fksg0sbcm`

- 请求：`GET https://pan.baidu.com/rest/2.0/xpan/multimedia?method=filemetas&access_token=...`
- 参数：
  - `fsids=[<fsid1>,<fsid2>]`（最多 100）
  - `dlink=1`：返回 `dlink`
  - `needmedia=1`：返回 `duration`（秒，上取整）
  - `detail=1`：返回 `media_info`（宽高、码率等）
  - `thumb=1` 等
- 返回（关键字段）：
  - `list[].dlink`：下载/播放直链（注意 unicode 解码）
  - `list[].path`、`filename`、`size`、`duration`、`media_info`...

#### 4.1.2 使用 dlink 下载/播放

文档：`https://pan.baidu.com/union/doc/pkuo3snyp`

- 访问方式：`GET <dlink>&access_token=...`
- 必须 header：`User-Agent: pan.baidu.com`
- 特性：
  - `dlink` 有效期 8 小时
  - `dlink` 存在 `302` 跳转
  - 支持 `Range` 断点续传（对播放器 seek 很关键）

与错误处理强相关的错误码：
- `31045`：token 校验失败
- `31326`：命中防盗链（通常 UA 不对或请求不合理）
- `31360`：链接过期（dlink 过期）
- `31362`：签名错误（链接不完整）

### 4.2 获取 m3u8（在线播放：HLS 转码流）

> 注：该路径会引入“转码/广告逻辑/轮询”的复杂性；但官方明确可用 VLC 播放 m3u8。

#### 4.2.1 获取视频流（streaming）

文档：`https://pan.baidu.com/union/doc/aksk0bacn`

- 请求：`GET https://pan.baidu.com/rest/2.0/xpan/file?method=streaming&access_token=...&path=...&type=...`
- 特点：**视频需要请求两次**：
  - 第一次返回广告逻辑：`adTime`、`adToken`、`ltime`
  - 等待 `ltime` 秒后，第二次携带 `adToken` 再请求，返回 m3u8 内容
- 参数：
  - `path`：视频文件路径
  - `type`：如 `M3U8_AUTO_480/720/1080` 或其他（文档也提示需特殊 UA 以拿到通用 mpegts ts 分片）
  - `nom3u8=1`：可不返回 m3u8
- 常见错误码（摘录）：`133` 播放广告、`31341` 转码中（需重试）、`31346` 转码失败、`31339` 非法媒体等。

#### 4.2.2 获取音频流（streaming）

文档：`https://pan.baidu.com/union/doc/mla814sos`

- 请求结构要求：
  - `Host: pan.baidu.com`（固定）
  - `User-Agent: xpanvideo;$appName;$appVersion;$sysName;$sysVersion;ts`（用于获取通用 mpegts）
- 参数：`path=<audioPath>`、`type=M3U8_HLS_MP3_128`
- 错误码：`31341` 转码中（轮询）、`31346` 转码失败、`31024` 无权限等。

---

## 5. 错误码与稳定性要点

### 5.1 错误码总表

文档：`https://pan.baidu.com/union/doc/okumlx17r`

与本功能强相关（摘录）：
- `-6` 身份验证失败（token/授权问题）
- `-7` 文件或目录名错误或无权访问
- `-9` 文件或目录不存在
- `20011` 测试应用授权用户数限制（前 10 个）
- `20012` 调用次数超限
- `20013` 接口权限不足
- `31034` 命中接口频控
- `31045` access_token 验证未通过
- `31326` 命中防盗链（UA/请求不合理）
- `31360` url 过期（例如 dlink 过期）
- `31341` 正在转码（streaming 场景需要轮询/重试）

### 5.2 与播放器/代理的结合建议（基于本仓库现状）

仓库内 Remote/WebDav/AList 的模式表明：
- Exo/Media3 可直接用 URL + headers。
- mpv/VLC 更稳妥走 `LocalProxy` → `HttpPlayServer`，以确保 Range 与 header 透传。

结合百度网盘：
- dlink 播放时：`headers` 至少需要 `User-Agent: pan.baidu.com`；并在 URL 上拼接 `access_token`。
- dlink 过期（8h）或 `31360`：应重新调用 `filemetas(dlink=1)` 获取新 dlink。

---

## 6. 开源实现参考（非官方，但有助于对照）

> 仅用于验证“大家都在这样用官方接口”，实现时以官方文档为准。

- `bpcs_uploader`（PHP CLI）：device-code 获取与轮询 token
  - https://github.com/oott123/bpcs_uploader/blob/3975d7efc38c1da2193e98a98e058e40e2d77d85/_bpcs_files_/core.php#L70-L111
- `SyncY`（Python）：device-code 授权与轮询
  - https://github.com/wishinlife/SyncY/blob/71597eb523cc411e5f41558570d3814d52cfe92e/syncy.py#L220-L276
- `baidu_pcs_cli`（C）：device-code 授权
  - https://github.com/emptyhua/baidu_pcs_cli/blob/a522e849a381400fb116acbda4eb48fd2e860ee6/pcs.c#L120-L195
- ScriptCat（TS）：`filemetas(dlink=1)` 获取直链
  - https://github.com/scriptscat/scriptcat/blob/5d70786e05d0ac20b5dd992c78092433d32ebc3d/packages/filesystem/baidu/rw.ts#L13-L22
- `jsyzchen/pan`（Go）：声明 `filemetas` / `streaming` / `list` 等 endpoint 常量
  - https://github.com/jsyzchen/pan/blob/b156e6b9798e957052e3ac0d0a8c516a50719bb1/file/file.go#L5-L18
