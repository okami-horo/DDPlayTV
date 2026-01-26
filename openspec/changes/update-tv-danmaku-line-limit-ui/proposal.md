## Why

当前播放界面的“弹幕参数设置”中，“弹幕行数限制”使用数字输入框（`EditText`）配置。该交互在 TV 端存在明显问题：

- 遥控器输入数字成本高，往往需要唤起软键盘或依赖不稳定的输入法
- 需要通过输入法 `Done`（或额外确认）触发保存，容易造成“已改但未生效”的误解
- 焦点在输入框内时上下左右导航体验差，易出现焦点陷阱

本变更希望将 TV 端“弹幕行数限制”改为更符合常见 TV 应用的离散切换/步进式交互：用户通过遥控器左右切换即可调整，**切换后立即生效**，无需点击确认等额外操作。

## What Changes

- 针对 TV UI mode（`Configuration.UI_MODE_TYPE_TELEVISION`）：
  - 将“弹幕行数限制”从数字输入改为可用 DPAD 左右切换的离散选项控件（不唤起软键盘）。
  - 每次切换后立即：
    - 更新当前弹幕类型（滚动/顶部/底部）的行数限制配置
    - 触发播放器弹幕渲染参数刷新（无需“确定/保存”）
    - 持久化配置，返回/关闭设置后仍保留
- 移动端保持既有输入体验（不在本提案中强制重做移动端输入交互）。
- 同步调整 TV 端焦点导航规则，确保 DPAD 可达且无焦点死角。

## Capabilities

### New Capabilities

- `player-danmaku-line-limit-tv-selector`: TV 播放界面弹幕行数限制支持遥控器切换并立即生效。

### Modified Capabilities

- （无）

## Impact

- 影响模块：`player_component`
  - `player_component/src/main/java/com/xyoye/player/controller/setting/SettingDanmuConfigureView.kt`
  - `player_component/src/main/res/layout/layout_setting_danmu_configure.xml`
- 风险与代价：
  - 需要仔细梳理 TV 端 DPAD 焦点流转，避免引入新的焦点陷阱
  - 离散选项需要定义上限/步进策略（过小不够用、过大切换成本高）
  - 需保证滚动/顶部/底部弹幕三种模式各自配置互不串扰

