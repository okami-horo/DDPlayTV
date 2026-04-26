---
name: ddplaytv-http-logs
description: Project-local workflow for DDPlayTV HTTP log collection via direct HTTP server URL/IP first, with optional adb forward or run-as fallback. Use when debugging DDPlayTV playback, network, Bilibili, storage, UI, or crash issues and needing app HTTP logs, logcat-backed HTTP log segments, archived logs, or archived http_logs from package com.okamihoro.ddplaytv.
---

# DDPlayTV HTTP 日志采集

## 概览

使用本 skill 通过 DDPlayTV 应用内 HTTP 日志服务采集日志，用于问题排查。优先使用可配置的远端 HTTP 地址/IP 直连；未配置直连地址时才使用 `adb forward`；HTTP 失败且 adb 可用时，可尝试 `run-as` 直接打包应用私有目录中的 `files/http_logs`。

重要约束：真实 `.env` 由采集脚本自行读取，agent 默认不要手动读取或输出 `.env` 内容；仅在排查脚本配置问题且用户明确需要时，才可查看真实 `.env`，并且不得在回复中暴露 token。

## 项目事实

- 应用包名：`com.okamihoro.ddplaytv`。
- HTTP 日志服务默认端口：`17010`。
- HTTP 服务由 `core_log_component` 的 `HttpLogServer` 提供，鉴权优先使用 `Authorization: Bearer <token>`，也支持 `?token=<token>`。
- 服务只允许局域网/loopback 访问；如果电脑可直接访问设备 IP，优先使用 `http://<设备IP>:17010`。
- adb 不是 HTTP 采集的必需条件，仅用于无法直连设备 IP 时建立 `adb forward tcp:17010 tcp:17010`，或在 HTTP 失败时做 `run-as` 兜底。
- 主要端点：`GET /api/v1/logs/segments`、`GET /api/v1/logs/latest-segment`、`GET /api/v1/logs/segment-at?timestampMs=<ms>`、`GET /api/v1/logs/download`。
- `POST /api/v1/logs/clear` 会清空历史日志，只有用户明确要求时才允许调用。

## 标准流程

1. 确认应用内已开启 HTTP 日志服务：在 DDPlayTV 开发者设置中开启“HTTP 日志服务”，记录页面显示的端口、访问 Token，以及设备 IP（如果页面展示或系统可查）。
2. 将配置写入 `.codex\skills\ddplaytv-http-logs\.env`。agent 只需要运行脚本，不要为了采集而手动读取真实 `.env`。
3. 直连 HTTP（推荐）：配置完整服务地址或设备 IP。两者都配置时，`DDPLAYTV_HTTP_LOG_BASE_URI` 优先。

```dotenv
DDPLAYTV_HTTP_LOG_TOKEN=<token>
DDPLAYTV_HTTP_LOG_BASE_URI=http://<设备IP>:17010
DDPLAYTV_HTTP_LOG_HOST=
DDPLAYTV_HTTP_LOG_REMOTE_PORT=17010
```

4. adb forward（可选）：仅当没有可直连的设备 IP/URL 时，留空 `DDPLAYTV_HTTP_LOG_BASE_URI` / `DDPLAYTV_HTTP_LOG_HOST`，并确保 adb 设备在线。

```dotenv
DDPLAYTV_HTTP_LOG_TOKEN=<token>
DDPLAYTV_HTTP_LOG_BASE_URI=
DDPLAYTV_HTTP_LOG_HOST=
DDPLAYTV_ADB_SERIAL=
DDPLAYTV_HTTP_LOG_LOCAL_PORT=17010
DDPLAYTV_HTTP_LOG_REMOTE_PORT=17010
```

5. 从仓库根目录运行脚本；脚本默认读取 skill 目录下的 `.env`，其次读取仓库根目录 `.env`：

```powershell
powershell -ExecutionPolicy Bypass -File .codex\skills\ddplaytv-http-logs\scripts\collect-ddplaytv-http-logs.ps1
```

6. 如果要使用其它 env 文件，显式传入：

```powershell
powershell -ExecutionPolicy Bypass -File .codex\skills\ddplaytv-http-logs\scripts\collect-ddplaytv-http-logs.ps1 -EnvFile ".env"
```

7. 命令行参数优先于 `.env`，临时覆盖地址、设备或端口时可继续使用 `-HttpBaseUri`、`-HttpHost`、`-DeviceSerial`、`-LocalPort`、`-RemotePort`、`-Token`。
8. 连接模式选择顺序：
	- 配置了 `DDPLAYTV_HTTP_LOG_BASE_URI` 或 `DDPLAYTV_HTTP_LOG_HOST`：直接访问远端 HTTP 服务，不检查 adb。
	- 未配置直连地址：使用 adb forward，并要求 `adb devices -l` 中存在且只存在一个 `device` 状态设备，或显式配置 `DDPLAYTV_ADB_SERIAL`。
	- HTTP 失败且可用 adb 序列号存在：尝试 `adb exec-out run-as com.okamihoro.ddplaytv ...` 兜底。
9. token 暂时拿不到时可让 `.env` 中 `DDPLAYTV_HTTP_LOG_TOKEN` 保持空值，脚本会跳过认证 HTTP 端点；只有 adb 可用时才尝试 `run-as` 兜底。该兜底只适用于可 `run-as` 的调试包；正式包或不可调试包通常会失败。
10. 如果你想把缓存固定在项目目录内，推荐设置 `DDPLAYTV_HTTP_LOG_OUTPUT_DIR=Temp/ddplaytv-http-logs`；脚本会把它解析到仓库根目录下的 `Temp`，并自动追加时间戳子目录。

## .env 字段

- `DDPLAYTV_HTTP_LOG_TOKEN`：开发者设置中显示的访问 Token；不要在最终回复中输出。
- `DDPLAYTV_HTTP_LOG_BASE_URI`：远端 HTTP 日志服务完整地址，例如 `http://192.168.1.50:17010`；配置后脚本直接走 HTTP，不需要 adb。
- `DDPLAYTV_HTTP_LOG_HOST`：远端设备 IP/主机名，例如 `192.168.1.50`；未配置 `BASE_URI` 时，脚本会拼成 `http://<host>:<REMOTE_PORT>`。
- `DDPLAYTV_ADB_SERIAL`：可选。adb forward 模式下用于指定设备；直连 HTTP 模式下仅用于 HTTP 失败后的可选 `run-as` 兜底。
- `DDPLAYTV_HTTP_LOG_LOCAL_PORT`：adb forward 模式下的本机转发端口，默认 `17010`。
- `DDPLAYTV_HTTP_LOG_REMOTE_PORT`：设备侧 HTTP 日志服务端口，默认 `17010`；直连 `HOST` 模式也会使用该端口。
- `DDPLAYTV_PACKAGE_NAME`：应用包名，默认 `com.okamihoro.ddplaytv`。
- `DDPLAYTV_HTTP_LOG_OUTPUT_DIR`：输出目录；留空时写入 `artifacts/ddplaytv-http-logs/<timestamp>/`。
- `DDPLAYTV_HTTP_LOG_OUTPUT_DIR`：输出目录；留空时写入项目根目录下 `Temp/ddplaytv-http-logs/<timestamp>/`，也可以显式设置为 `Temp/ddplaytv-http-logs` 作为项目内基目录，脚本会自动追加时间戳。
- `DDPLAYTV_HTTP_LOG_KEEP_FORWARD`：设为 `true` 时采集后保留 adb forward。
- `DDPLAYTV_HTTP_LOG_SKIP_DIRECT_FALLBACK`：设为 `true` 时 HTTP 失败后不尝试 `run-as` 兜底。

## 输出文件

脚本默认写入 `Temp/ddplaytv-http-logs/<timestamp>/`：

- `collection-info.txt`：设备、包名、端口、采集时间、是否使用 token 等元信息；不要写入 token。
- `segments.json`：日志段索引。
- `latest-segment.ndjson`：最新日志段，适合快速查看最近问题。
- `ddplaytv-http-logs.zip`：HTTP 服务导出的完整日志归档。
- `direct-http-logs.tgz`：HTTP 服务不可用时，通过 `run-as` 直接打包得到的兜底文件。

## 手动访问

脚本不可用时，优先按 HTTP 直连采集：

```powershell
$headers = @{ Authorization = "Bearer <token>" }
Invoke-WebRequest -Uri "http://<设备IP>:17010/api/v1/logs/segments" -Headers $headers -OutFile segments.json
Invoke-WebRequest -Uri "http://<设备IP>:17010/api/v1/logs/latest-segment" -Headers $headers -OutFile latest-segment.ndjson
Invoke-WebRequest -Uri "http://<设备IP>:17010/api/v1/logs/download" -Headers $headers -OutFile ddplaytv-http-logs.zip
```

设备 IP 不可直连时，再使用 adb forward：

```powershell
adb -s <serial> forward tcp:17010 tcp:17010
Invoke-WebRequest -Uri "http://127.0.0.1:17010/api/v1/logs/segments" -Headers @{ Authorization = "Bearer <token>" } -OutFile segments.json
Invoke-WebRequest -Uri "http://127.0.0.1:17010/api/v1/logs/latest-segment" -Headers @{ Authorization = "Bearer <token>" } -OutFile latest-segment.ndjson
Invoke-WebRequest -Uri "http://127.0.0.1:17010/api/v1/logs/download" -Headers @{ Authorization = "Bearer <token>" } -OutFile ddplaytv-http-logs.zip
adb -s <serial> forward --remove tcp:17010
```

## 排障准则

- `connection refused`：通常是应用内 HTTP 日志服务未开启、端口不一致、应用未运行或 adb forward 未建立。
- `401 unauthorized`：token 为空、复制错误、已重置，或请求未携带 `Authorization: Bearer <token>`。
- `429 rate limited`：请求过频，等待后重试；不要循环刷接口。
- `run-as: package not debuggable`：当前安装包不可调试，不能直接读取私有目录；改用应用内 token + HTTP 服务。
- 直连 HTTP 失败：优先检查电脑是否能访问设备 IP、设备是否同网段、路由/AP 是否启用客户端隔离、防火墙是否阻断端口。
- adb forward 模式下，必须先确认 adb 设备可用：运行 `adb devices -l`，只接受状态为 `device` 的设备；`offline`、`unauthorized`、空列表都不能宣称采集成功。
- 采集后汇报必须包含：连接模式（direct-http / adb-forward）、输出目录、成功得到的文件、失败端点及错误摘要；如果使用了 adb，还必须包含 adb 设备状态和使用的设备序列号。
- 不要在最终回复、日志文件名、元信息中暴露 token。
