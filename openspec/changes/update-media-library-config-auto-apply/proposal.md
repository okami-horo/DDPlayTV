## Why

当前“媒体库配置/编辑存储源”采用底部弹窗的“确定/取消”按钮来提交修改。该交互在 TV 端需要额外一次遥控器确认，效率低且容易造成“改完忘点确定”的误解（实际未保存）。

本变更希望将媒体库配置调整为 **无需确认、修改即生效**：用户在界面中调整参数后，系统自动完成持久化与生效；离开界面（返回键/手势返回/下滑关闭）即可，不再需要显式的“确定/取消”按钮。

## What Changes

- `StoragePlus`（媒体库配置入口）下的各类存储源编辑弹窗，移除底部 `确定/取消` 按钮区域。
- 将“保存”从显式按钮改为自动保存：
  - 轻量配置（文本、开关、单选）在变更后自动持久化（带 debounce，避免频繁写库）。
  - 需要显式动作/网络校验的流程（如扫码登录、测试连接、投屏连接）保留其语义化动作按钮，并在动作成功后自动完成持久化，无需再点“确定”。
- 保持既有调用方兼容：对依赖 `RESULT_OK` 的返回结果（例如需要刷新目录/重新鉴权的场景）继续在“成功生效”后回传 OK。

## Capabilities

### New Capabilities

- `media-library-config-auto-apply`: 媒体库配置界面改为自动生效并移除确认/取消按钮。

### Modified Capabilities

- （无）

## Impact

- 影响模块：`storage_component`（主要）
  - `StoragePlusActivity` / `StoragePlusViewModel`
  - `StorageEditDialog` 各子类（WebDav/SMB/FTP/Remote/External/Alist/BaiduPan/Open115/Bilibili/Screencast）
- 可能涉及模块：`core_ui_component`
  - 若需要为底部弹窗提供统一的“隐藏操作按钮”能力（视实现选型而定）
- 风险与代价：
  - 自动保存需要处理“输入未完成/参数非法”状态，避免 toast 轰炸与写库抖动
  - 少数对话框当前将“确定”作为核心动作（如投屏手动连接），需要迁移到更明确的内容区按钮
