---

description: "Task list for mpv player integration"
---

# Tasks: 集成 mpv 播放引擎

**Input**: 设计文档来自 `/specs/002-mpv-player-integration/`
**Prerequisites**: plan.md（required）, spec.md（required）, research.md, data-model.md, quickstart.md

**Tests**: 本特性文档（quickstart/spec）明确建议补充 mpv 播放烟测，因此包含对应测试任务。

**Organization**: 按用户故事分组，确保每个故事可独立实现/验证。

## Format: `- [ ] Txxx [P?] [US?] 描述（含文件路径）`

- **[P]**: 可并行执行（不同文件、无未完成依赖）
- **[US]**: 对应 spec.md 中的用户故事

---

## Phase 1: Setup（共享基础设施）

**Purpose**: 为 libmpv 接入准备工程与目录结构

- [X] T001 创建 libmpv 依赖目录与占位：`player_component/libs/armeabi-v7a/`、`player_component/libs/arm64-v8a/`（放置 `libmpv.so` 及其依赖）
- [X] T002 [P] 检查并更新 `player_component/build.gradle.kts`，确保 libmpv 的 `jniLibs.srcDir("libs")`、`abiFilters`、`packagingOptions.pickFirsts` 配置满足打包与冲突规避
- [X] T003 [P] 更新 `player_component/src/main/cpp/CMakeLists.txt`：导入预编译 `libmpv.so`，新增 JNI 桥接库目标（如 `mpv_bridge.cpp` → `libmpv_bridge.so`）并链接到 libmpv

---

## Phase 2: Foundational（阻塞性前置）

**Purpose**: 跨模块枚举/配置扩展，阻塞所有用户故事

- [X] T004 在 `data_component/src/main/java/com/xyoye/data_component/enums/PlayerType.kt` 新增 `TYPE_MPV_PLAYER(4)` 并更新 `valueOf()` 回读规则（未知值回退到 Media3）

**Checkpoint**: Foundation ready，开始用户故事实现

---

## Phase 3: User Story 1 - 选择并使用 mpv 播放视频（Priority: P1）🎯 MVP

**Goal**: 用户可在设置中选择 mpv，并用 mpv 完成基础播放与控制

**Independent Test**: 设置切换为 mpv 后，打开任意视频可正常播放并响应播放/暂停、seek、音量、倍速、全屏/旋转等操作

### Tests for User Story 1（OPTIONAL → 本特性包含） ⚠️

- [ ] T005 [P] [US1] 新增基础播放烟测 `player_component/src/androidTest/java/com/xyoye/player_component/ui/MpvPlaybackSmokeTest.kt`（首帧、播放/暂停、seek、倍速、音量）

### Implementation for User Story 1

- [X] T006 [US1] 新建 mpv Kotlin JNI 桥接封装 `player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/MpvNativeBridge.kt`，负责加载 native 库并声明 mpv 相关 native 方法
- [X] T007 [US1] 新建 JNI 实现 `player_component/src/main/cpp/mpv_bridge.cpp`（或等价文件），封装 mpv 句柄创建/销毁、命令发送、事件轮询、OpenGL 渲染回调
- [X] T008 [US1] 新建 mpv 内核 `player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/MpvVideoPlayer.kt`，实现 `AbstractVideoPlayer` 基本生命周期与控制接口，并将 mpv 事件映射到 `VideoPlayerEventListener`
- [X] T009 [US1] 新建工厂 `player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/MpvPlayerFactory.kt`，并在 `player_component/src/main/java/com/xyoye/player/kernel/facoty/PlayerFactory.kt` 增加 `TYPE_MPV_PLAYER` 分发
- [X] T010 [US1] 新建渲染视图 `player_component/src/main/java/com/xyoye/player/surface/RenderMpvView.kt`（基于 `GLSurfaceView/GLTextureView`），实现 `InterSurfaceView` 并将 Surface/OpenGL 上下文交给 mpv
- [X] T011 [US1] 新建 `player_component/src/main/java/com/xyoye/player/surface/MpvViewFactory.kt`，并在 `player_component/src/main/java/com/xyoye/player/surface/SurfaceFactory.kt` 增加 mpv 分发（忽略 surfaceType，始终返回 mpv 视图工厂）
- [X] T012 [P] [US1] 修改设置页 `user_component/src/main/java/com/xyoye/user_component/ui/fragment/PlayerSettingFragment.kt`：加入 mpv 选项并调整 `getString/putString` 安全回读允许 `TYPE_MPV_PLAYER`
- [X] T013 [P] [US1] 修改 `player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt#initPlayerConfig`：读取/记录 mpv 类型并避免被错误回退
- [X] T014 [US1] 在 `MpvVideoPlayer.setDataSource` 中实现 headers → mpv `http-header-fields`（或等价属性）映射，保持网络源鉴权策略一致

**Checkpoint**: mpv 可被选择并完成基础播放

---

## Phase 4: User Story 2 - 与现有播放体验保持一致（Priority: P2）

**Goal**: mpv 下弹幕/字幕/播放列表等关键体验对齐现有内核

**Independent Test**: 使用 mpv 播放带弹幕/字幕的视频，验证弹幕/字幕开关、字幕轨道切换、下一集等行为一致

### Tests for User Story 2（OPTIONAL → 本特性包含） ⚠️

- [ ] T015 [P] [US2] 新增体验一致性烟测 `player_component/src/androidTest/java/com/xyoye/player_component/ui/MpvSubtitleDanmakuSmokeTest.kt`（弹幕/字幕开关、字幕轨道切换、时间偏移）

### Implementation for User Story 2

- [X] T016 [US2] 在 `player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/MpvVideoPlayer.kt` 实现轨道能力：`getTracks/selectTrack/deselectTrack/supportAddTrack/addTrack` 映射 mpv `track-list/aid/sid/vid` 与 `audio-add/sub-add`
- [X] T017 [US2] 在 `player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/MpvVideoPlayer.kt` 实现字幕开关与偏移：`setSubtitleOffset` → `sub-delay`，关闭字幕时禁用 `sid`
- [X] T018 [P] [US2] 修改 `player_component/src/main/java/com/xyoye/player/DanDanVideoPlayer.kt`：当 `PlayerInitializer.playerType == TYPE_MPV_PLAYER` 时跳过 `ensureSubtitleRenderer()/configureSubtitleRenderer()`，避免双字幕
- [X] T019 [US2] 在 `player_component/src/main/java/com/xyoye/player/surface/RenderMpvView.kt` 中确认/调整透明与层级，保证弹幕覆盖层在 mpv 视频之上正常显示
- [X] T020 [US2] 校准 `MpvVideoPlayer.kt` 事件时序（`onPrepared/onCompletion/onVideoSizeChange/onInfo`）以复用现有播放列表/旋转/后台策略

**Checkpoint**: mpv 下关键体验与现有内核一致

---

## Phase 5: User Story 3 - 失败时可回退且不崩溃（Priority: P3）

**Goal**: mpv 播放失败时给出明确提示，并支持一键回退默认内核继续播放

**Independent Test**: 用不可播样例触发 mpv 错误，验证提示可理解且可一键回退重试成功

### Tests for User Story 3（OPTIONAL → 本特性包含） ⚠️

- [ ] T021 [P] [US3] 新增回退烟测 `player_component/src/androidTest/java/com/xyoye/player_component/ui/MpvFallbackSmokeTest.kt`（失败提示 + 一键回退）

### Implementation for User Story 3

- [X] T022 [US3] 在 `MpvNativeBridge.kt`/`MpvVideoPlayer.kt` 完善 libmpv 加载/解码/渲染错误捕获与可读异常，并透传到 `VideoPlayerEventListener.onError(e)`
- [X] T023 [P] [US3] 修改 `player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt#showPlayErrorDialog`：mpv 失败时展示“切换默认内核重试”按钮，回退到默认引擎并重试当前视频

**Checkpoint**: mpv 失败可恢复，不影响观看

---

## Phase N: Polish & Cross-Cutting Concerns

**Purpose**: 跨故事的文档/性能/稳定性收尾

- [X] T024 [P] 更新开发者说明与文案：`specs/002-mpv-player-integration/quickstart.md`（mpv 选项说明、许可证提醒、排障建议）
- [X] T025 [P] 在 `player_component/src/main/java/com/xyoye/player/kernel/impl/mpv/MpvVideoPlayer.kt#setOptions` 中根据性能目标补充默认 mpv 参数与日志（如 `vo=gpu-next`、`hwdec=auto`、日志等级）
- [ ] T026 参照 `specs/002-mpv-player-integration/quickstart.md` 完成手工回归（本地/远程设备），记录 mpv 首帧、seek、轨道、字幕与回退结果

---

## Dependencies & Execution Order

### User Story Dependencies

- **US1 (P1)**: 依赖 Phase 1+2 完成；为 US2/US3 的前置
- **US2 (P2)**: 依赖 US1（基于 mpv 可播放能力做体验对齐）
- **US3 (P3)**: 依赖 US1（基于 mpv 错误回调做回退与提示）

### Parallel Opportunities

- Phase 1 中标记 [P] 的 Gradle/CMake 变更可并行
- US1/US2/US3 的测试任务（T005/T015/T021）可在实现前并行编写
- US1 中 UI/配置改动（T012/T013）可与 mpv 内核实现并行
- US2 中字幕渲染禁用（T018）可与轨道实现（T016/T017）并行
- US3 中错误 UI 回退（T023）可与 mpv 错误捕获（T022）并行

---

## Parallel Example: User Story 1

```bash
# 并行编写测试 + UI/配置改动 + 内核实现
Task: "T005 MpvPlaybackSmokeTest.kt"
Task: "T012 PlayerSettingFragment.kt"
Task: "T013 PlayerActivity.kt#initPlayerConfig"
Task: "T006-T011 mpv 内核/渲染实现"
```

---

## Implementation Strategy

### MVP First（仅 US1）

1. 完成 Phase 1 Setup
2. 完成 Phase 2 Foundational
3. 完成 Phase 3（US1）
4. 独立验证 US1（参照 spec/quickstart）

### Incremental Delivery

1. US1 可用 → 验证/内测
2. US2 对齐体验 → 验证弹幕/字幕/播放列表
3. US3 提升可恢复性 → 验证失败回退
4. Polish 收尾 → 文档/性能/回归
