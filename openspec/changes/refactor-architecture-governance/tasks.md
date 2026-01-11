## 1. 规格与门禁（Spec + Guardrails）

- [x] 1.1 补齐本变更的 OpenSpec delta（`architecture-governance`）并通过 `openspec validate --strict`
- [x] 1.2 明确并固化“本地/CI 的推荐验证集合”（至少包含 `verifyModuleDependencies`、`ktlintCheck`、`lint`、单元测试）
- [x] 1.3 增加静态门禁：禁止新增 `FragmentPagerAdapter`/`androidx.viewpager.widget.ViewPager`

## 2. UI Pager 迁移（统一到 ViewPager2）

- [x] 2.1 迁移 `anime_component/src/main/java/com/xyoye/anime_component/ui/activities/search/SearchActivity.kt` 到 ViewPager2
- [x] 2.2 迁移 `anime_component/src/main/java/com/xyoye/anime_component/ui/fragment/home/HomeFragment.kt` 到 ViewPager2
- [x] 2.3 迁移 `user_component/src/main/java/com/xyoye/user_component/ui/activities/scan_manager/ScanManagerActivity.kt` 到 ViewPager2
- [x] 2.4 迁移 `user_component/src/main/java/com/xyoye/user_component/ui/activities/setting_player/SettingPlayerActivity.kt` 到 ViewPager2
- [x] 2.5 回归验证：页面切换/配置变更/返回栈/TV 遥控器焦点可达性（已通过 `./gradlew :anime_component:assembleDebug :user_component:assembleDebug`）

## 3. 播放代理链路：阻塞点治理（线程模型一致 + 性能稳定）

- [ ] 3.1 梳理 `core_storage_component` 内 `runBlocking` 使用点（如 `HttpPlayServer`/`FtpPlayServer`/`SmbPlayServer`），标注调用线程与并发模型
- [ ] 3.2 制定并落地替换策略：保证不阻塞主线程、并发可控、行为可回归
- [ ] 3.3 为关键行为补充最小化自动化验证（单元测试或仪表测试，视可行性而定）

## 4. 验证（本地与 CI）

- [ ] 4.1 `./gradlew verifyModuleDependencies`
- [ ] 4.2 `./gradlew ktlintCheck`
- [ ] 4.3 `./gradlew testDebugUnitTest`
- [ ] 4.4 `./gradlew lint`（或按工程约定的 Debug lint 任务）
- [ ] 4.5 若涉及播放链路：`./gradlew connectedDebugAndroidTest`（需要设备/模拟器）
