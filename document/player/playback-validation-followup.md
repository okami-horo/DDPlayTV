# TV 体验问题与播放验证待办

整理日期：2026-10-28；体验问题更新：2026-10-31。本文集中记录本轮用户反馈的 TV 体验问题与尚未完成的顺序续播验证；有效设计约束归入对应维护／设计文档。

综合验收由用户负责，进度见[交接实施账本](../../docs/superpowers/plans/2026-10-27-playback-handoff-session.md)。关闭以下验证项时需给出对应场景的实际证据，不能以部分构建／设备结果代替完整验收。

## TV 体验问题记录

本节按用户“先记录”的要求维护，尚未开始修复；后续反馈可继续追加。与已有计划重叠的要求同步修正文档，其他问题不自动扩大文件浏览菜单精简的实施范围。

### UX01：点击视频后出现两种加载界面

- **反馈日期与状态**：2026-10-31，用户报告；已核对相关源码，尚未在设备上复现或修复。
- **用户观察**：点击视频开始播放时，会出现两种“正在加载”的界面，用户质疑这种重复是否必要。具体来源、内核及两种界面的实际切换时序尚未确认。
- **初步判断**：内部可以有播放源准备、播放器初始化和缓冲等多个阶段，但没有必要仅因阶段切换而展示两套不同的加载界面。建议让首次打开视频呈现连续、一致的等待反馈，减少闪切和重复提示；保留必要的错误提示与退出能力。

2026-10-31 源码核对发现两套相关反馈，作为后续定位线索，不据此宣称已经复现用户所见的完整过程：

| 位置 | 当前行为 |
| --- | --- |
| [PlayerActivity.kt](../../player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt) 的 `preparationView` / `renderPlaybackScreen` | `state.preparing` 时显示文本；首次播放为全屏黑底“正在准备播放…”，已有播放时为顶部“正在准备新视频…” |
| [LoadingView.kt](../../player_component/src/main/java/com/xyoye/player/controller/video/LoadingView.kt) 与 [layout_loading.xml](../../player_component/src/main/res/layout/layout_loading.xml) | 内核进入准备或暂停缓冲状态时，显示转圈与“正在准备播放…”或“正在缓冲…”的居中面板 |

后续处理方向与验收要点（待实施任务确定，不在本次修改代码）：

- [ ] 复现并确认两种界面的出现顺序、持续时间与触发来源；区分首次播放、播放中换集和卡顿缓冲。
- [ ] 首次播放各准备阶段使用一致的加载反馈，同一时刻只显示一套提示，不因切换界面产生闪白、闪黑或短暂重复。
- [ ] 快速完成时避免无意义的加载闪现；慢网络时仍有持续反馈，失败能说明原因并返回，不能简单隐藏提示留下无反馈黑屏。
- [ ] 保留播放中缓冲、换集失败恢复及返回/取消行为；覆盖 Media3 与 mpv，不改变播放源、历史、字幕和弹幕的既有准备流程。

目前只有用户反馈与源码线索；没有新增截图、设备验证或构建结果。

### UX02：常规遥控器操作说明过多

- **反馈日期与状态**：2026-10-31，用户明确要求减少界面中的常规 TV 操作说明；仅记录，尚未修改界面。
- **用户观察**：上下左右导航、确认、返回属于常规 TV 操作，界面无需反复解释，常驻说明增加视觉负担。用户随后补充：播放器底部菜单部分元素获得焦点时还出现多余的中文说明，也应纳入精简。
- **适用范围**：全应用页面与弹窗，不限于播放器或文件浏览；本次仅核对以下样例，不代表完成全量排查。

| 已核实样例 | 处理方向 |
| --- | --- |
| [文件浏览底栏](../../storage_component/src/main/res/layout/activity_storage_file.xml)：方向键选择、确定打开、菜单更多、返回上一级 | 移除常驻按键教学；保留同区域内有用的真实状态，不因删说明留下空白占位 |
| [文件预览](../../storage_component/src/main/java/com/xyoye/storage_component/ui/fragment/storage_file/StorageFileFragment.kt)：按确定打开文件夹、按确定播放、按菜单查看文件操作 | 删除重复主操作说明；不把移除的文件菜单换成另一段常驻教学 |
| [播放器选集面板](../../player_component/src/main/res/layout/layout_switch_video_source.xml)：上下选择、确认切换、返回后回到选集按钮 | 删除常规导航说明，以选中态和清晰控件表达操作 |
| [PlayerBottomView.kt](../../player_component/src/main/java/com/xyoye/player/controller/video/PlayerBottomView.kt) 与 [底部布局](../../player_component/src/main/res/layout/layout_player_bottom.xml)：六个播放/选集控件聚焦时，将 `contentDescription` 复制到浮动的 `transport_hint`，包括播放/暂停、前后跳转、上一集/下一集、选集 | 精简重复解释图标或按钮名称的焦点附加文字；保留必要按钮名称、跳转步长等有效信息、焦点高亮及无障碍描述 |
| [作品详情提示](../../local_component/src/main/java/com/xyoye/local_component/ui/metadata/MetadataDetailActivity.kt)：选择文件播放、长按更正资料、合并集共享进度混在同一段 | 拆分评估：删除常规教学；有实际影响的状态/规则按需呈现，特殊入口随其功能退役安排处理 |

后续整理原则与验收要点：

- [ ] 删除常驻的方向键、确认、常规返回说明，包括底栏、预览区和弹窗中的重复文案；不通过新增首次使用教学或提示开关补回同一负担。
- [ ] 清理播放器底部菜单随焦点出现的冗余文字，核对 `transport_hint` 及播放状态更新时的写入路径；保留焦点记录/恢复，焦点移动不再反复显示重复说明或引发布局跳动。具体控件逐项核对，必要名称、状态和数值仍清晰可见。
- [ ] 保留按钮名称、焦点反馈、选中状态与无障碍描述；错误原因、空状态、权限/登录步骤和真实任务进度不按“操作说明”一并删除。
- [ ] 非直观的特殊操作先考虑提供清晰可见的入口；确需提示时只在相关状态简短呈现，避免全页常驻的按键说明条。
- [ ] 删除说明后检查布局空隙及纯 DPAD 可达性，不能用说明文字代替合理的焦点与返回行为。

与[文件浏览菜单精简任务](../../docs/superpowers/plans/2026-10-31-file-browser-menu-simplification.md)重叠部分已同步为“移除常规说明”，替代原先把“菜单 更多”改写成“菜单 刷新”的计划。MENU/SETTINGS 的刷新行为保留，顶部已有可见刷新入口；长名溢出阅读的必要提示仅限溢出状态。其他页面仍属于后续待整理项。本次没有执行应用构建或设备测试。

播放器补充反馈更新了[已选 A 方案](../../docs/superpowers/designs/2026-10-31-player-controls-design.md)中“按钮聚焦显示短操作名”的要求；单排分组、按钮功能及焦点路径继续保留。源码已确认上述附加文字机制，尚未逐项在设备上核对，旧原型/截图通过不代表本轮精简已完成。

## 顺序续播

以下两项继续待验收。现行职责和行为边界见[活动会话设计第 12 节](../../docs/superpowers/designs/2026-07-18-playback-handoff-design.md#123-顺序续播的产品边界)。

- [ ] **Media3 集成／冒烟场景**。分别记录以下四类结果：有下一项时在原播放页续播；无下一项时正常完成；手动切换后旧准备结果／旧切换回调到达不覆盖当前来源和绑定；预准备失败时当前项不受影响，完成后按目标可用性降级切源或正常完成。同时核对目标项标题、历史、弹幕、字幕和外挂音轨，避免沿用上一项文件级绑定。
- [ ] **相关 Gradle 验证与 TV 播放主路径**。将适用的构建、单元测试、lint／治理结果与上述场景对应，并以纯 DPAD 完成进入播放器、续播／完成、返回及退出，确认焦点可见、可达、可返回。Gradle 证据必须包含命令、源码版本／工作区差异以及尾部 `BUILD SUCCESSFUL` 或 `BUILD FAILED`。

已有 [SequentialPlaybackCoordinatorTest](../../player_component/src/test/java/com/xyoye/player_component/sequential/SequentialPlaybackCoordinatorTest.kt)、[Media3SequentialPlaybackAdapterTest](../../player_component/src/test/java/com/xyoye/player_component/sequential/Media3SequentialPlaybackAdapterTest.kt) 和 [ActivePlaybackSessionTest](../../player_component/src/test/java/com/xyoye/player_component/playback/session/ActivePlaybackSessionTest.kt) 可作为策略、队列和会话层证据入口；源码中存在这些用例不等于已经完成上述播放器集成与 TV 冒烟。

交接账本中的字幕／弹幕连续性及完整导航验收与这里存在交集；只有实际覆盖对应场景的记录才能复用，同一证据无需重复执行，尚未覆盖的场景继续保留。
