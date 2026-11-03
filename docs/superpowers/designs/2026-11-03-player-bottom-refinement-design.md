# 播放底栏：轻盈同调对比稿

日期：2026-11-03。状态：**设计已生成，等待用户确认；尚未授权实施这份新稿。**

后续：用户查看苹果官方实例后要求调整设计并预览，已生成[开放式第二稿](2026-11-03-player-bottom-open-design.md)。本文保留第一稿双托盘方案及其历史验证；当前视觉确认请先看第二稿，尚未授权原生实施。

用户先要求评估底部菜单是否符合苹果设计美学，随后要求提供新效果图与原 UI 对比。当前交付仅为对比与交互原型，未修改 Android、构建、安装或操作实体电视。已实施并验收的 A 单排结构与 A02 焦点规则仍是当前原生基准，本稿不自动替代它们。

## 对比依据与范围

- [对比原型](../../../document/prototypes/tv-ui/player-bottom-refinement-prototype.html?variant=compare)：原 UI 为当前 `layout_player_bottom.xml`、`cinema_transport_tray`、`cinema_transport_button`、`cinema_play_circle` 和 `cinema_button` 的网页重建，核对 [2026-11-02 原生 N28–N30](../../../document/prototypes/tv-ui/previews/focus-feedback/native-2026-11-02/index.html)。网页字体与 Android 绘制仍有差异，不能称为本次电视截图。
- 两版共同使用已追踪的 `previews/player-panel-layers/cinema-frame.jpg` 视频截图裁切素材，共用视频、字幕、进度和焦点。没有将 2026-10-31 的旧底栏截图当作当前界面。
- 保留 A 单排、±15 秒、播放/暂停、上一集、下一集、选集及字幕/音轨/弹幕三个入口，保留首末集禁用占位、单文件收口与不可跳转状态。
- 字幕位置、弹幕与顶部标题关系沿用既定接受范围。本稿仅确认底部控制外观；右侧设置面板继续使用已接受的 A06。

## 建议变化

| 项目 | 新稿 |
| --- | --- |
| 组织 | 左侧播放/剧集分组不变；右侧三个独立底块合为一个托盘，仍分别聚焦和确认 |
| 材质 | 左右使用相同的柔和冷灰渐变、半透明底与低对比边缘；没有实时模糊依赖 |
| 高度 | 在 1440×810、约 1.5px/dp 的设计画布上，两托盘均高 80px；作为原生约 53dp 的验证候选，不能直接抄作 dp |
| 选集 | 使用矢量列表图标，文字由基线 15px 调至 20px，约对应 10sp → 13.3sp；与右侧入口同尺度 |
| 图标 | 统一线宽与光学大小；字幕保留字幕框语义，弹幕采用带尾部的消息框以增强辨识 |
| 焦点 | 当前项局部提亮、单层细边与约 1px 上浮；主播放键维持浅色。最终动效需原生 DPAD 验证 |
| 时间 | 位置不变，普通时间由 18px 提至 20px，技术状态由 15px 提至 16px |

视觉方向参考 Apple 的[视频播放](https://developer.apple.com/design/human-interface-guidelines/playing-video)、[材质](https://developer.apple.com/design/human-interface-guidelines/materials)与[焦点](https://developer.apple.com/design/human-interface-guidelines/focus-and-selection/)指南；这些是设计参考，不是 Android 性能或交互验证结果。

## 苹果官方播放器实图核对

2026-11-03 按用户要求，实际查看了 Apple 于 **2025-06-09** 发布的 [tvOS 26 官方演示](https://www.apple.com/newsroom/2025/06/apple-tv-brings-a-beautiful-redesign-and-enhanced-home-entertainment-experience/)中的[播放器视频](https://www.apple.com/newsroom/videos/2025/autoplay/06/apple-tv-brings-a-beautiful-redesign-and-enhanced-home-entertainment-experience/apple-wwdc25-tvos-26-updated-design-player-250609/large_2x.mp4)。这是注明版本和日期的实例，不代表已核对 2026 年最新系统。已查看的本地证据为 `.tmp/apple-player-reference/tvos26-player-contact.png` 与 `tvos26-player-bottom-official.png`（3 秒位置底栏裁切）。

- 官方实例采用开放叠加布局：左侧季集与标题、横向长时间轴，整组控件没有外层大圆角托盘。
- 字幕、音频与画中画位于时间轴右上方，各自使用小圆形玻璃按钮；下方 Info、InSight、Continue Watching 各自使用胶囊形入口。
- 玻璃材质用于局部控件；不能由此推导出“整个进度条菜单应当用大框包起来”。

**此前两块大托盘是本稿的独立设计选择，并非苹果原版播放器结构。** 用户随后要求调整设计并预览，已生成使用开放时间轴和独立小按钮的[第二稿](2026-11-03-player-bottom-open-design.md)，保留 DDPlayTV 的九个操作与 DPAD 可达性。第二稿等待视觉确认，原生权威范围尚未变更。

## 页面与状态覆盖

所有图片位于本地预览目录 [player-bottom-refinement-2026-11-03](../../../document/prototypes/tv-ui/previews/player-bottom-refinement-2026-11-03/)，依项目规则不追踪生成图片。原型、渲染脚本与验证 JSON 可重新生成或核对。

| 状态 | 原型入口 | 设计证据 | 原生实现与验证 |
| --- | --- | --- | --- |
| 普通暂停、前进聚焦 | `scene=middle&focus=forward` | `comparison-bottom.png`、`comparison-full.png`，已查看 | 尚未实施新稿 |
| 播放中 | `scene=playing` | 图标切换与两版同状态检查 | 尚未实施新稿 |
| 首集、末集 | `scene=first` / `last` | `new-states.png`；禁用占位与跳过焦点 | 尚未实施新稿 |
| 单个视频 | `scene=single` | `new-states.png`；收起剧集组 | 尚未实施新稿 |
| 不可快进/快退 | `scene=unseekable` | `new-states.png`；禁用两键，聚焦回播放 | 尚未实施新稿 |
| 播放、选集及三个轨道入口聚焦 | `focus=play` / `picker` / `subtitle` / `audio` / `danmaku` | `new-focus-play.png`、`new-focus-subtitle.png` 与原型状态检查 | 尚未实施新稿 |
| 较亮/较暗视频帧 | `background=bright` / `dark` | `new-bright.png`、`new-dark.png`，同一图片亮度模拟 | 动态视频及设备表现未验证 |
| 隐藏/恢复控制条 | Esc / Enter | 网页模拟，恢复原焦点 | 不代表原生返回栈验证 |
| 轨道空/加载/错误与选集列表 | 继续使用既有 A06 面板 | 不在本次外观变更范围；未制作替代稿 | 沿用已接受的实现 |

## 本地查看与检查

```powershell
node document/prototypes/tv-ui/server.cjs 4189
node document/prototypes/tv-ui/render-player-bottom-refinement.cjs
```

打开 `http://127.0.0.1:4189/player-bottom-refinement-prototype.html?variant=compare`。可切换原 UI/新稿、场景、焦点及背景亮度；方向键、Enter、Esc 仅操作网页模拟状态。渲染依赖使用 Codex 已有 Playwright、Sharp 与本机 Edge，不安装新依赖。

2026-11-03 最终渲染：24 个观察记录、浏览器运行异常 0。检查了九个操作均在底栏可视范围内、左托盘不越过已接受的字幕左缘、两版焦点一致、首末集/单文件/禁用状态，以及网页方向键、±15 秒与隐藏/恢复。实际查看了底栏对比、新稿全屏、播放/字幕焦点、边界状态和较亮/较暗画面。

这只验证 HTML 设计交付。Android 构建、原生动效、真实 DPAD、播放器运行与实体电视验收均不是本轮结果。待用户确认设计后再确定原生实施范围。
