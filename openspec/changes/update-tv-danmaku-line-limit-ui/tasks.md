## 1. TV 端 UI：行数限制改为可切换控件

- [x] 1.1 调整 `player_component/src/main/res/layout/layout_setting_danmu_configure.xml`：为“弹幕行数限制”新增 TV 友好的展示控件（值显示 + 左右切换提示），并在 TV UI mode 下隐藏/禁用数字输入框的焦点。
- [x] 1.2 调整 `player_component/src/main/java/com/xyoye/player/controller/setting/SettingDanmuConfigureView.kt`：初始化控件、绑定点击/按键事件，确保切换行为不依赖软键盘。

## 2. 交互逻辑：切换立即生效且三种模式互不串扰

- [x] 2.1 在 `applyScrollDanmuConfigure/applyTopDanmuConfigure/applyBottomDanmuConfigure` 中补齐 TV 控件的状态回显（显示“无限制/数字行数”，并反映当前值）。
- [x] 2.2 在 TV 控件收到左右切换后，调用既有 `updateMaxLine(...)` 流程：写入 `PlayerInitializer.Danmu.*` + `DanmuConfig.put...` + `mControlWrapper.updateMaxLine()`，确保立即生效与持久化。
- [x] 2.3 更新 `handleKeyCode(...)`：当焦点位于“行数限制控件”时，左右键用于改值，上下键用于移动焦点；验证滚动/顶部/底部模式下焦点路径一致。

## 3. 验证

- [x] 3.1 编译验证：`./gradlew :player_component:assembleDebug`（确认输出尾部为 `BUILD SUCCESSFUL`）
- [x] 3.2 静态检查：`./gradlew lint`（或按仓库约定运行 `lintDebug`）
- [x] 3.3 依赖治理校验：`./gradlew verifyModuleDependencies`（确认输出尾部为 `BUILD SUCCESSFUL`）
- [ ] 3.4 手工验收（TV）：
  - 进入播放 → 弹幕设置 → 弹幕参数：DPAD 可聚焦到“行数限制”，左右切换立即生效（肉眼可见同屏弹幕密度变化）
  - 切换滚动/顶部/底部模式后，行数限制显示各自值且互不影响
  - 返回/关闭设置后再次进入，配置仍保持
