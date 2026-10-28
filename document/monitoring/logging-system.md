# 日志采集与 HTTP 下载

核对日期：2026-10-28。实现位于 `core_log_component` 的 `LogSystem`、`LogWriter`、`HttpLogServerManager`、`HttpLogServer` 和 `SegmentedJsonlLogStore`。

## 输出与存储

事件经级别与采样策略后输出 logcat。开启 HTTP 日志服务时，另保存应用事件和当前进程可读取的 logcat 到私有 `files/http_logs/` 分段 JSONL；这不是旧的 `log.txt` / `log_old.txt`。

关闭服务停止监听与新采集，不自动删除历史。保留档位为 7/14/30 天，上限 1/2/4 GiB；空间不足可能降档或暂停持久化。当前提供页面和下载接口，**没有 SSE 实时推送**。

## 应用操作

进入“设置 → 开发者设置”，开启 HTTP 日志服务，查看地址、实际端口、Token、保留档位和占用。可清空历史或重置凭证，重置后旧 Token 失效。默认请求端口 `17010`，以显示地址为准。

访问限于实现允许的回环/局域网来源，并需要 Token。HTTP 不提供传输加密，仅在可信网络开启。凭证与导出日志不要直接贴入公开反馈。

## 下载接口

脚本优先用 Header；浏览器可访问 `http://<设备IP>:17010/?token=<token>`，注意地址可能保存在浏览器历史。

```bash
curl -L -H "Authorization: Bearer <token>" \
  -o ddplaytv-logs.zip \
  "http://<设备IP>:17010/api/v1/logs/download"
```

| 方法与路径 | 用途 |
| --- | --- |
| `GET /` | 下载页面 |
| `GET /api/v1/logs/download` | 全量 ZIP |
| `GET /api/v1/logs/segments` | 分段目录与统计 |
| `GET /api/v1/logs/latest-segment` | 最新分段 |
| `GET /api/v1/logs/segment-at?timestampMs=<ms>` | 指定毫秒时间点分段 |
| `POST /api/v1/logs/clear` | 清空历史 |

目录包括分段的 `fileName/startMs/endMs/sizeBytes/lastModifiedMs/isLatest` 及 `totalCount/totalBytes/latestStartMs/oldestStartMs`。可使用同样 Header 替换请求路径获取所需内容。

## ADB

确认设备为 `device`，按 tag 或进程过滤，避免系统和其他应用日志：

```bash
adb -s <serial> logcat -s DDLog-CORE
```

`LogWriter` 的 tag 为 `DDLog-<模块>-<子tag>`，当前实现截断到 23 字符，筛选时按实际输出填写。需要多模块时保存有范围的结果再检索 `DDLog-`，不要无期限重定向全部日志。

## 状态与安全

- `401`：核对 Token、来源与重置情况；有效 Bearer Header 优先于 URL 参数。
- `404`：路径不存在或分段尚未生成/已清理。
- `405`：方法错误，例如清空需要 POST。
- `429`：限流，降低频率。
- `503`：下载/分段暂不可用，查看应用状态及存储情况。

导出默认经过 [脱敏](../architecture/log_redaction_policy.md)，提交前仍检查账号、Cookie、Token、地址和媒体名。Bugly 与 HTTP 日志是不同通道，见 [数据处理说明](<../Privacy policy.md>)。
