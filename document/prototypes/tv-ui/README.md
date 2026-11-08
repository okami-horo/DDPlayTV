# DDPlayTV 深色 UI 网页原型

## 剧集详情大画面提案（2026-11-08，待确认）

[打开设计稿](series-detail-prototype.html?review=cinema&variant=hero)。底部可切换 A「全景剧场」、B「画面与列表」及现状结构；顶部可查看缺图、多季、空态、加载、失败、长标题与 130% 字号。推荐 A，尚未选定或实施原生。

运行 `node document/prototypes/tv-ui/server.cjs 4191`，访问 `http://127.0.0.1:4191/series-detail-prototype.html?review=cinema&variant=hero`。方向键 / Enter / Esc 操作；`node document/prototypes/tv-ui/render-series-cinema.cjs` 生成网页截图及观察记录。

完整范围、SC01–SC13 矩阵、素材说明与验证边界见[本次设计](../../../docs/superpowers/designs/2026-11-08-series-cinema-design.md)。旧 `?variant=B` 仍展示原来的已选稿。

## 本地截图与预览

生成的预览图和原生验证截图仅在本地归档，图片链接需恢复原路径后查看；HTML、生成脚本和验证记录仍随仓库维护。六张交互原型输入素材继续追踪，具体范围和恢复方式见 [截图保存约定](../../README.md#截图与预览产物)。

## 剧集详情重设计（2026-11-01，已选 B）

用户已选择 **B「沉浸剧集」**：[详情页原型](series-detail-prototype.html?variant=B)现固定为上方作品信息与续播、下方横向剧集卡片；A/C 渲染器与切换条已移除。详情页完整重设计与首页观看足迹入口已在用户授权后实施，并完成本地构建、DPAD、状态截图及 1.3 字体验证；[实施记录](../../../docs/superpowers/plans/2026-11-01-home-watch-history.md)区分原生验证与未完成的实体电视／用户验收。[设计与范围](../../../docs/superpowers/designs/2026-11-01-home-watch-history-design.md)记录 Apple 官方参考、真实电视问题和验证边界。

启动：`node document/prototypes/tv-ui/server.cjs 4178`，访问 [详情页预览](http://127.0.0.1:4178/series-detail-prototype.html?variant=B)。无需安装依赖或访问外网。顶部可选常规、多季、多版本、尚未观看、未知时长、文件不可用、空、资料更新、电影、资料失效与首次读取状态。方向键移动、Enter 确认、Esc 返回；“电视现状”可对照改前截图。

这是独立设计原型，不连接真实播放器或写入媒体库。封面与前两集剧照来自用户授权的电视截图；数量、其余集数、进度是示例。B 是当前唯一权威稿，设计选择不代表原生实现或电视验收完成。

## 已选的整体视觉基准

状态：2026-10-30 用户已选择 **沉浸放映（`cinema`）**，并设为默认预览。它是原生 UI 的视觉基准。2026-10-31 第一轮“整套完成”的结论已撤回，随后按此原型重新整改并完成本地截图/DPAD 验证；以 [原生 UI 记录的整改章节](../../../docs/superpowers/plans/2026-10-31-cinema-ui.md#原型一致性整改2026-10-31本地验收通过) 为准。修正版已在随后获得用户授权后安装到电视，安装包哈希核对一致；随后用户根据当前电视截图否定文件浏览页效果；原型原本缺少完整目录页，文件页视觉验收结论已撤回。物理电视界面与播放验收仍待完成。

## 设计问题

A 和 U01–U10 已在后续授权下补稿并调整原生布局，详见 [实施和状态矩阵](../../../docs/superpowers/plans/2026-10-31-ui-polish.md)。条件页面/外部服务/物理电视仍有未覆盖状态。原始审查记录如下：[设计覆盖审查 U01–U10](../../../docs/superpowers/plans/2026-10-31-ui-design-coverage-audit.md) 记录了完整选集、播放器二级面板、绑定 / 下载、来源授权、深层设置、TMDB 页面等缺口；主原型或共用主题覆盖一部分，不代表这些深层页面已经有完整稿或完成视觉验收。

2026-10-31 新增 [播放器左下操作组设计](../../../docs/superpowers/designs/2026-10-31-player-controls-design.md)：用户仅要求调整播放 / 前后集 / 选集按钮组，明确字幕位置及弹幕覆盖标题不是问题。原播放原型漏了前后集和选集，该局部视觉完成结论撤回。用户已选择 A 单排分组，后续已实施并完成本地播放/焦点/截图核对，剩余状态见任务矩阵。

在用户偏好的暗色、苹果系风格下，比较三种信息组织方式：

- `?variant=sidebar`：侧栏影院，平衡继续观看、最近观看和媒体来源。
- `?variant=cinema`：沉浸放映，使用顶部导航和大画面，突出继续观看；**已选方案，默认预览**。
- `?variant=library`：媒体工作台，突出来源、文件列表和内容预览。

三种方案共用媒体库、播放控制、字幕/音轨/弹幕面板和设置预览。媒体名称、插画、来源、数量和轨道均为示例，不代表实际媒体库、刮削能力或已实现的新功能。

## 打开

在项目根目录运行：

```powershell
node document/prototypes/tv-ui/server.cjs
```

浏览器访问 `http://127.0.0.1:4173`。也可以直接打开同目录的 `index.html`。无需安装依赖，无外部字体、图片或网络请求。

- 底部切换条切换方案；切换条获得焦点时，左右键也可切换。
- 预览区域使用方向键移动、Enter 确认、Esc 返回，或使用鼠标。
- 搜索支持筛选示例片名；字幕、音轨、字号、设置可交互。
- 播放页是静态插画和模拟进度，不播放真实媒体。添加来源只展示入口。
- 状态仅保留在内存中，刷新恢复默认；界面中的“即时生效”不代表已写入 Android 设置。

## 确认边界

Android UI 以已选的沉浸放映方案为基准，保留暗色、苹果系风格，并复用原有设置、来源与播放行为。网页布局和动效用于表达设计，不能直接替代原生焦点、电视性能、视频 Surface、字幕渲染和真实遥控器验收。

媒体库元数据已决定统一采用 TMDB，接入范围和未完成事项见 [TMDB 媒体库待办](../../media-library/tmdb-metadata-followup.md)。

## 文件浏览页补充设计（2026-10-31）

用户已选择 **C「列表与预览」**，原型默认打开 C；A/B 只保留作历史对照。原生改造已实施，并于 2026-11-03 按用户确认验收关闭，见 [C 方案实施记录](../../../docs/superpowers/plans/2026-10-31-file-browser-preview.md)。设计阶段的暂缓决定已被后续实施覆盖。[文件浏览原型](files-prototype.html?variant=preview) 也可从原型首页右上角进入。

- 访问 `http://127.0.0.1:4173/files-prototype.html?variant=preview`，现有命令启动的服务同时支持这张设计。服务可加端口参数，例如 `node document/prototypes/tv-ui/server.cjs 4174`。
- 场景选择包含六个目录、视频及长名称、混排、空目录、首次加载失败、加载中、分页追加。
- 方向键、Enter、Esc、菜单键（键盘 M）可操作；视频行向右到更多按钮，向左返回主操作。
- [设计说明与边界](../../../docs/superpowers/designs/2026-10-31-file-browser-design.md)，[已选 C 截图](previews/files-preview.png)；历史对照：[A 目录](previews/files-grid.png)、[A 视频](previews/files-videos.png)、[B](previews/files-list.png)。
- 网页使用示例数据，不接入真实来源；原生实现、刷新失败、分页失败、登录过期等状态及验收证据以 C 方案实施记录为准，不再由本原型导航重复列为待办。

## 播放操作局部设计（2026-10-31）

[交互预览](player-controls-prototype.html?variant=grouped) 用已有电视截图合成，只替换左下按钮组。用户已选择 A 单排分组，交互原型固定为 A；B / C 仅保留历史对照图。文件浏览页继续使用其已选 C。

- 运行 `node document/prototypes/tv-ui/server.cjs 4175`，访问 `http://127.0.0.1:4175/player-controls-prototype.html?variant=grouped`；也支持直接打开 HTML。
- 页面固定为已选 A，顶部切换播放 / 暂停、首末集、单文件及不可跳转状态；方向键 / Enter / Esc 可操作。可切回当前电视截图对照。
- [历史方案对照](previews/player-controls-comparison.png)、[边界状态](previews/player-controls-states.png)、[后续任务](../../../docs/superpowers/plans/2026-10-31-player-controls.md)。此页操作为网页模拟；后续原生调整另见实施账本，未重新占用电视。

## 深层界面补充稿（2026-10-31）

[deep-pages.html](deep-pages.html) 覆盖选集、偏移/样式、绑定/下载、九类来源、深层工具、元数据、历史、账户、公共弹窗和启动页；参数及原生映射见 [补充设计](../../../docs/superpowers/designs/2026-10-31-ui-polish-design.md)。例如 `?page=source&kind=SMB`、`?page=match&state=loading`、`?page=match&kind=series&step=episodes`。`capture=1` 隐藏评审栏。

普通/空/加载/失败是示例状态，协议字段和条件显隐按现有业务保留；网页不是联网产品。原生实际看图、DPAD、构建及未覆盖项见实施矩阵，不能因存在补充稿就宣称完整视觉验收。
