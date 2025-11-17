字幕字体方案（简化版：内置默认字体）

目标

- 稳定可控：全部由应用自身控制。
- 开箱即用：APK 内置一套覆盖面较广的默认字体，首次运行即可正确渲染。
- 可扩展：用户可向私有目录添加更多字体。
- 默认字体“可配置”暂不实现（优先级不高）；本文仅描述当前实现与后续预期逻辑。
- 改动最小：尽量不引入复杂的动态切换与多目录合并逻辑。

核心方案

- 在 APK 中打包默认字体：`app/src/main/assets/fonts/<Default>.ttf|otf`（建议选择覆盖 CJK 的开源字体，如 Noto Sans CJK SC）。
- 启动时检查 `<filesDir>/fonts` 是否包含“默认字体”文件；若缺失，则从 `assets/fonts` 复制对应文件到 `<filesDir>/fonts`（目录不存在则创建）。
- libass 配置固定为“扫描单一目录”：
  - provider = NONE（ASS_FONTPROVIDER_NONE）
  - directory = `<filesDir>/fonts`（只传这一个目录给 `ass_set_fonts_dir`）
  - defaultFont = 内置字体的 family 名（需与字体内部 family 完全一致）
- 用户额外字体也放在 `<filesDir>/fonts`。后续可提供设置页导入与选择默认字体。
- 兜底：默认字体必须内置并成功复制到 `<filesDir>/fonts`；若复制失败，视为严重错误，需提示用户并记录日志（可按产品决策选择：允许继续播放但字幕可能缺字，或切换到 legacy 字幕后端/隐藏字幕）。

实施步骤

- 资源与复制
- 将默认字体置于 `app/src/main/assets/fonts`。
- 启动时执行“默认字体检查与复制”：
  - 创建 `<filesDir>/fonts`（若不存在）。
  - 判断“默认字体”文件是否存在于 `<filesDir>/fonts`。
  - 缺失则从 `assets/fonts` 复制；存在则跳过。
- 打印复制结果日志（成功/跳过/失败）。

启动检查流程（明确）

- Application.onCreate：
  - 计算默认字体文件名（例如 `NotoSansCJKsc-Regular.otf`）。
  - 确保 `<filesDir>/fonts` 目录存在。
  - 若 `<filesDir>/fonts/<默认字体文件>` 不存在，则从 `assets/fonts/<默认字体文件>` 复制到该目录；
  - 复制失败则记录严重错误日志，并按产品策略提示用户（字幕可能缺字或切回旧后端）。

- 渲染配置
  - 仅设置一次目录：`ass_set_fonts_dir(library, <filesDir>/fonts)`。
  - 固定 provider=NONE：`ass_set_fonts(renderer, defaultFont, ..., ASS_FONTPROVIDER_NONE, ...)`。
  - 当前实现：始终使用“打包内置字体”的 family 作为 `defaultFont`。
  - 后续预期：若设置项未配置默认字体，则使用打包内置字体；若配置了且该字体存在于 `<filesDir>/fonts`，则使用配置的字体；若配置的字体不存在，则回退为打包内置字体。

- 设置项（后续迭代，当前不实现）
  - 新增“默认字幕字体” ListPreference（key：`subtitle_default_font_family`）。
  - 列表来源：枚举 `<filesDir>/fonts` 下的字体并读取 family 名作为 entries。
  - 应用逻辑：
    - 未配置 ⇒ 使用打包内置字体。
    - 已配置且存在 ⇒ 使用配置字体。
    - 已配置但不存在 ⇒ 回退为打包内置字体。

日志与验证

- 期望 ADB 日志（目录存在）
  - `libass font provider: NONE`
  - `libass font dir set: <filesDir>/fonts`
  - `libass font default: <family>`
  - `fontselect: (...) -> <filesDir>/fonts/xxx.ttf`


回退与缺字处理

- 缺字时 libass 会在目录内回退到其它字体；若设置了 `defaultFont`，将优先回退到该字体。
- 若仍缺字：提示用户添加更多字体到 `<filesDir>/fonts`，或在设置中调整默认字体。
- 不建议复制系统字体到私有目录（体积大、首启耗时长、低收益）。

权衡与注意

- APK 体积：内置 CJK 字体会增加体积（15–25MB 量级），建议只带一套“默认回退”字体，其它由用户按需添加。
- 许可合规：确保内置字体许可允许分发；family 名必须与 `setFonts` 传入值一致。
- 稳定性：始终使用 `<filesDir>/fonts`；若复制失败应当明确提示并记录，避免“无感失败”。

非目标（明确不做）

- 不做多目录扫描与合并（避免 `ass_set_fonts_dir` 最后覆盖的问题）。
 
- 不全量复制系统字体到私有目录。
