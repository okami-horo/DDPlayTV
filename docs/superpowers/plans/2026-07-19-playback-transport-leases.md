# Playback Transport Leases Implementation Plan

> Scope superseded on 2026-10-28: all Bilibili functionality, its sole Addon implementation and dedicated token/transport paths are retired. Related tasks below are historical evidence, not instructions to restore that integration. Ordinary transport ownership remains applicable; the original 37/37 count is unchanged. See the [retirement record](2026-10-28-remove-bilibili.md).

> Revised 2026-10-27 after the [reference architecture review](../../../document/architecture/2026-10-27-playback-refactor-reference-review.md); status reconciled 2026-10-28. This document retains the implementation ledger and recorded verification. Task descriptions preserve their original execution order; completed steps must not be replayed merely because a file is described as "Create" or "Modify".

**Goal:** Candidate preparation and disposal must not alter the active source or the browser's connection. Each resource has one cleanup owner.

**Design:** [Playback transport leases](../designs/2026-07-19-playback-transport-leases-design.md). The dependent [handoff design](../designs/2026-07-18-playback-handoff-design.md) owns request/recovery policy in player; this plan establishes transport ownership without changing routes or implementing that session.

**Status:** Tasks 1–9 are complete (37/37 checks). The dependent [handoff/session plan](2026-10-27-playback-handoff-session.md) has reached 62/65 checks: Tasks 1–13 and two automated Task 14 checks are complete; three integrated acceptance items remain user-owned. Task 6 brought forward the Source/retirement prerequisites needed for production Bilibili token wiring; Task 8 completed its source-ownership verification. Historical authorization and task-local logs below describe their dated runs, not a new request to modify or commit code.

**Stack:** Kotlin 1.9.25, Gradle 8.9, NanoHTTPD, SMBJ, Commons Net, Thunder AAR, existing Bilibili sessions, JUnit4/Robolectric/AndroidX Test. Do not upgrade dependencies or introduce new Gradle modules for this refactor.

## Ownership and interface decisions

- `PlaybackTransportFactory` in core_storage is the only acquisition entry. Its legacy file input is borrowed. For SMB/FTP it creates a fresh Storage through the existing StorageFactory credential path, resolves the file on that Storage, then invokes the protocol provider.
- Acquisition returns `AcquiredPlaybackTransport(playbackFile, lease)`. This is an assembly result, not a second closeable owner. The lease owns its protocol resources; the file is a borrowed view of those resources.
- An internal `PlaybackTransportProvider` receives the file bound to the chosen playback Storage. It must not silently acquire another Storage after factory isolation or retain the browser Storage. Keep these types in core_storage; do not add a contract-to-storage dependency.
- Use a typed success/failure result for acquisition with the failure categories from design section 15. A coroutine cancellation is cleaned up and rethrown, never converted to a normal null/failure result.
- A preparation scope owns resources until lease creation succeeds. Source construction then takes ownership of the lease. On failure, clean only the current owner; never release a lease and independently close the same Storage again.
- Before provider success, factory owns newly created Storage/File and provider owns its newly created server/task/token while borrowing the file. On provider failure each cleans its own acquisitions; on success the lease takes both. Cover cancellation during result delivery across dispatcher boundaries, not only explicit throws inside the provider.
- `StorageVideoSource.releasePlaybackResources()` owns Addon cleanup followed by lease cleanup. Catch/report each cleanup failure independently. The later PreparedPlayback delegates to this source method; it does not acquire a duplicate lease owner.
- Bilibili Addons borrow a fixed token and stop their own work. Only the lease removes the token and its session/heartbeat resources.
- Preserve the source's existing profile, headers, subtitle/danmaku behavior and queue order. Queue items must remain resolvable after the preceding playback connection closes.
- Known direct sources without owned resources may use a no-op direct lease. Provider failure, resource-owning sources, or already-local proxy URLs must not be disguised as independent direct acquisitions.
- Cleanup first makes a transport unavailable for new work, coordinates in-flight work, then closes its own resources. Potentially blocking close operations do not run under the playback state lock.

## Task 1: Define acquisition and ownership at the real factory boundary

**Dependencies:** none.

**Files:**

- Create `core_storage_component/src/main/java/com/xyoye/common_component/storage/transport/PlaybackTransportLease.kt`.
- Create `core_storage_component/src/main/java/com/xyoye/common_component/storage/transport/PlaybackTransportFactory.kt`.
- Modify `core_storage_component/src/main/java/com/xyoye/common_component/storage/StorageFactory.kt` only if an injectable creation entry cannot be supplied at the new factory.
- Create `core_storage_component/src/test/java/com/xyoye/common_component/storage/transport/PlaybackTransportLeaseTest.kt` and `PlaybackTransportFactoryTest.kt` in the same test package.
- Add the repository's existing Robolectric test dependency to `core_storage_component/build.gradle.kts`: MediaLibraryEntity initializes an Android MediaStore URI, so its factory tests require Android shadows.

- [x] Add tests for idempotent release, cleanup continuation after an exception, typed failure without a direct-URL fallback, and cancellation propagation. Run the focused tests and inspect the actual failure; missing classes alone do not establish the eventual behavior.
- [x] Implement the small lease API: lease ID, playback URL, transport type, release status and idempotent release. Define the acquisition result and preparation ownership transfer. Add injectable creation functions for protocol Storage/client and server creation where the isolation tests need them; no service locator or general lifecycle framework.
- [x] Route SMB/FTP through fresh playback Storage and file resolution. Capture library identity/configuration and file identity while the input is borrowed. Preserve credential resolution; never log/copy credentials into a public lease. Other providers retain their actual resource semantics. Fail unsupported ownership cases explicitly.
- [x] Run both focused test classes and `:core_storage_component:compileDebugKotlin`. Inspect the diff for reverse module dependencies and confirm the test seam is used by production acquisition.

**Completed 2026-10-27:** The default factory uses StorageFactory's credential-resolution path; tests inject creation/provider operations into that same factory. Acquisition retains ownership until dispatcher delivery, cleans cancellation in a non-cancellable I/O context, and returns UnsupportedTransport instead of a legacy URL fallback. Protocol providers and the source cutover are intentionally still Tasks 2–8; this foundation alone does not establish actual SMB/FTP server isolation.

**Verification:** 13 new lease/factory tests passed. A controlled mutation disabling undelivered-lease cleanup made the cancellation test fail with `expected:<1> but was:<0>`; the source was restored before final verification. Full `:core_storage_component:testDebugUnitTest` then passed **38 tests in 11 suites, zero failures/errors**, together with `:core_storage_component:compileDebugKotlin`, `:core_storage_component:lintDebug` (No issues found), new-file-scoped ktlint and `verifyModuleDependencies`: **BUILD SUCCESSFUL**. Local logs: `.tmp/lease-task1-tests.log`, `.tmp/lease-task1-mutation.log`, `.tmp/lease-task1-verification.log`. No device/protocol fixture verification is claimed. Initial sandbox dependency failures were resolved by a fresh authorized `--no-daemon` build using the project-local JDK 17/Gradle cache. Formatter changes outside the four new Kotlin files were fully reverted; Git index contents were preserved.

## Task 2: Isolate HTTP proxy contexts

**Dependencies:** Task 1.

**Files:**

- Create `core_storage_component/src/main/java/com/xyoye/common_component/storage/transport/HttpPlaybackTransport.kt`.
- Modify `core_storage_component/src/main/java/com/xyoye/common_component/storage/file/helper/HttpPlayServer.kt` and `LocalProxy.kt`.
- Create `core_storage_component/src/main/java/com/xyoye/common_component/storage/file/helper/HttpUpstreamContext.kt` to hold each lease's request state and in-flight resources.
- Create `core_storage_component/src/test/java/com/xyoye/common_component/storage/transport/HttpPlaybackTransportTest.kt`.

- [x] Add tests using two distinct upstreams through the real registration/request path: independent content, headers, TLS policy, seek/Range/retry state; releasing A yields 404 for A while B remains readable. Registry-only unit tests are supplementary.
- [x] Move mutable upstream state into per-lease contexts. Keep one loopback server and route `/lease/<leaseId>/<encodedName>` to a context. Registration failure removes only the new context; release rejects new requests and coordinates any remaining work without stopping other leases.
- [x] Add lease-returning LocalProxy acquisition and lease-specific seek updates. Preserve existing proxy selection and Range behavior. An existing local lease URL is not a new resource: retain its existing owner for same-source use, or re-resolve the upstream for a new source.
- [x] Run the focused tests and core_storage compilation. Confirm generated URLs/logs contain no upstream headers, cookies or query secrets and a production lease never has an empty URL.

**Completed 2026-10-27:** HTTP leases register independent contexts on one loopback listener; retirement removes only the selected route, cancels its requests and closes its response bodies. LocalProxy returns typed acquisition failures without falling back after server startup failure. The old bare-URL API temporarily owns one legacy slot, independent of new leases; its production callers and deletion remain Tasks 7–8. Source integration is not yet complete.

**Verification:** The initial two-upstream test reproduced the original overwrite (`expected:<[first]> but was:<[other]>`). All seven HTTP tests now pass using real loopback requests, including independent headers/Range/retry, retirement during a blocked request, proxy selection and occupied-port failure. TLS checks cover per-context client-policy selection, not HTTPS handshakes. Final full storage tests passed **45 tests in 12 suites, zero failures/errors**; compilation, lint (No issues found), scoped ktlint and dependency governance ended **BUILD SUCCESSFUL**. The initial sandbox run failed on Robolectric cache/network access and an existing fixture's file access; the fresh authorized run passed. Logs: `.tmp/lease-task2-red.log`, `.tmp/lease-task2-tests.log`, `.tmp/lease-task2-verification.log`. Ktlint's historical format snapshot caused unrelated formatting; all 83 unrelated tracked files were restored from the unchanged index before final validation. No device acceptance is claimed.

## Task 3: Acquire an independent SMB connection and server

**Dependencies:** Task 1.

**Files:**

- Create `core_storage_component/src/main/java/com/xyoye/common_component/storage/transport/SmbPlaybackTransport.kt`.
- Modify `core_storage_component/src/main/java/com/xyoye/common_component/storage/transport/PlaybackTransportFactory.kt`.
- Modify `core_storage_component/src/main/java/com/xyoye/common_component/storage/impl/SmbStorage.kt`.
- Create `core_storage_component/src/main/java/com/xyoye/common_component/storage/impl/SmbConnection.kt`: keep SDK connection/share/handle operations behind the production Storage seam used by recording-connection tests.
- Modify `core_storage_component/src/main/java/com/xyoye/common_component/storage/file/helper/SmbPlayServer.kt`.
- Inspect `core_storage_component/src/main/java/com/xyoye/common_component/storage/file/impl/SmbStorageFile.kt`; its current clone retains the original Storage.
- Create `core_storage_component/src/test/java/com/xyoye/common_component/storage/transport/SmbPlaybackTransportTest.kt`.

- [x] Exercise the actual acquisition factory twice from files belonging to one browser Storage. Inject recording SMB clients and server creation at production seams. Assert distinct browser/A/B Storage and client instances, file-to-Storage binding, and successful reads/listing after either lease is released. Include two acquisitions of the same file.
- [x] Capture the full SMB `storagePath()`, including share identity. On each acquisition create a new credential-resolved SmbStorage and call its `pathFile(path, false)`; verify the resolved identity. Do not use the original file, `file.clone()`, or a server-only copy. Use the returned playback file for later source assembly.
- [x] Make SmbPlayServer per-instance and idempotently startable; remove Holder/getInstance. The lease owns that server, file and Storage. Server release owns its input stream and server stop, while lease cleanup closes the file and Storage exactly once. Reject new serve calls during retirement and coordinate stream creation with release.
- [x] Inject failure/cancellation after connection creation, file resolution, server start and URL creation. Assert exact cleanup counts, no active/browser closure, and no resource reopened after release. Run the focused test and core_storage compilation; reserve real SMB I/O smoke evidence for Task 9.

**Completed 2026-10-27:** SmbStorage now implements the provider on the factory-created playback connection. Each lease owns a separate loopback server, playback file and Storage; no SmbPlayServer singleton remains. The SDK adapter owns its client/share and closes temporary metadata/directory handles. Its returned stream also closes the remote file handle: local SMBJ 0.10 bytecode confirmed that FileInputStream.close alone does not. Server retirement rejects new requests, coordinates stream creation and closes only its own streams before Storage retirement. The temporary bare-URL API retains a server on its own Storage until Task 8 removes its callers.

**Verification:** The baseline regression failed with `Browser closure must not stop playback`. All nine SMB tests now pass through the real factory/provider/Storage/server with recording protocol connections: browser/A/B isolation, same-file acquisitions, either retirement order, independent share selection and Range reads, failure/cancellation at five boundaries, occupied port, directory rejection, idempotent start, opening-stream retirement race and cleanup failure. Final full storage tests passed **54 tests in 13 suites, zero failures/errors**; compilation, lint (No issues found), scoped ktlint and governance ended **BUILD SUCCESSFUL**. Logs: `.tmp/lease-task3-red.log`, `.tmp/lease-task3-tests.log`, `.tmp/lease-task3-verification.log`. The fresh scoped formatter snapshot kept changes within the intended files. These tests exercise actual loopback serving with a recording SMB adapter, not SMB wire traffic; real SMB service acceptance remains Task 9.

## Task 4: Acquire an independent FTP connection and server

**Dependencies:** Tasks 1 and 3's ownership pattern; no shared SMB implementation is required.

**Files:**

- Create `core_storage_component/src/main/java/com/xyoye/common_component/storage/transport/FtpPlaybackTransport.kt`.
- Modify `core_storage_component/src/main/java/com/xyoye/common_component/storage/transport/PlaybackTransportFactory.kt`.
- Modify `core_storage_component/src/main/java/com/xyoye/common_component/storage/impl/FtpStorage.kt`.
- Modify `core_storage_component/src/main/java/com/xyoye/common_component/storage/file/helper/FtpPlayServer.kt`.
- Inspect `core_storage_component/src/main/java/com/xyoye/common_component/storage/file/impl/FtpStorageFile.kt`.
- Create `core_storage_component/src/test/java/com/xyoye/common_component/storage/transport/FtpPlaybackTransportTest.kt`.

- [x] Test through the production acquisition factory with independent recording FTP clients for browser/A/B. Verify different connection/stream/restart-offset state and continued playback/listing after either lease closes; callback-only lease tests are insufficient.
- [x] Create a fresh credential-resolved FtpStorage, resolve the path on that connection and validate actual playability. Current pathFile may construct placeholder metadata after a lookup failure: a non-null result alone is not a successful playback probe. Keep browser compatibility while making playback failure structured.
- [x] Make FtpPlayServer per-instance. Retirement rejects new requests and stops/co-ordinates the server before Storage finishes its pending stream operation and disconnects. The Storage owns its stream and clears its reference; neither server nor upper source independently closes the same connection.
- [x] Test failure/cancellation at every acquisition boundary, cleanup continuation, duplicate release and release racing with a new read. Run the focused test and core_storage compilation; record real FTP smoke evidence separately.

**Completed 2026-10-27:** The factory resolves FTP playback files using real MLST metadata or a matching LIST entry, never the browser fallback object. Each playback Storage gets a distinct FTPClient and loopback server. Storage alone closes its data stream, completes the pending command and disconnects; late closure of an old HTTP response cannot complete a newer transfer. Server retirement waits for in-flight stream opening before Storage cleanup. Existing linear FTP proxy behavior is preserved (HTTP Range remains disabled); direct restart offsets stay connection-local. Browser completePending callers remain compatible, and the temporary Storage-owned bare-URL server remains until Task 8.

**Verification:** The baseline real-request test reproduced replacement (`expected:<[first]> but was:<[other]>`). All **11 FTP tests** passed through the factory/provider/Storage/server with recording FTPClient subclasses: same-file A/B/browser isolation, either retirement order, offsets, missing/unsupported MLST, LIST fallback, login/directory rejection, failure/cancellation at five acquisition boundaries, occupied port, late response close, cleanup failures and retirement racing an opening transfer. Final full storage tests passed **65 tests in 14 suites, zero failures/errors**; compilation, lint (No issues found), scoped ktlint and governance ended **BUILD SUCCESSFUL**. Logs: `.tmp/lease-task4-red.log`, `.tmp/lease-task4-tests.log`, `.tmp/lease-task4-verification.log`. Real FTP server acceptance and TV/device checks remain Task 9; production source creation still migrates in Task 8.

## Task 5: Make Thunder task acquisition independently owned

**Dependencies:** Task 1.

**Files:**

- Create `core_storage_component/src/main/java/com/xyoye/common_component/storage/transport/ThunderPlaybackTransport.kt`.
- Modify `core_storage_component/src/main/java/com/xyoye/common_component/utils/thunder/ThunderManager.kt` and `storage/impl/TorrentStorage.kt` under the same common_component source root.
- Create `core_storage_component/src/test/java/com/xyoye/common_component/storage/transport/ThunderPlaybackTransportTest.kt`.
- Create `core_storage_component/src/main/java/com/xyoye/common_component/utils/thunder/ThunderTaskOperations.kt` for the native-operation seam.
- Update `core_storage_component/src/main/java/com/xyoye/common_component/source/media/StorageVideoSource.kt` to look up its exact URL task during legacy migration, and `storage_component/src/main/java/com/xyoye/storage_component/ui/activities/storage_file/StorageFileViewModel.kt` to validate before source/task allocation.

- [x] Test creation through the production manager/provider using injected task operations: candidate B must not stop A; same torrent with different indexes must coexist; URL preparation failure/cancellation stops only the newly acquired task.
- [x] Return a task handle containing taskId and URL. Remove stopAllTask from normal playback creation, key ownership by taskId/leaseId, and keep global cleanup only for explicit global operations. Remove path-only current-task assumptions from playing-source lookup.
- [x] Implement the per-task lease and Torrent provider. Run compatibility checks before allocating the playback task; preserve AUDIO_ONLY_FALLBACK's existing advisory behavior. Do not infer AAR multi-task support solely from the fake.
- [x] Run the focused tests and existing core_storage tests. Record actual AAR multi-task behavior during integration verification; unresolved real multi-task support blocks claiming this transport complete.

**Implementation verified 2026-10-27; native acceptance completed during Task 9:** Each task has a task ID, lease generation and separate cache directory. AAR bytecode inspection showed that XLTaskHelper.deleteTask deletes its supplied directory, so ordinary retirement now invokes native stop/release and deletes only the generated lease child. Creation is separated from selection/start so duplicate IDs are rejected before mutating an active task. Selected indexes now use the actual index rather than an array of that length. Failure/cancellation cleans only the new task; metadata-download cancellation propagates and stops its own download task. The existing storage entry validates before setup; AUDIO_ONLY_FALLBACK remains advisory. Central validation for every future handoff entry stays in that plan's resolver/entry migration. Task 7 subsequently removed legacy bare-URL task ownership.

**Verification:** Nine manager/provider tests passed. A temporary mutation restoring global shutdown caused the coexistence test to fail (`expected:<[]> but was:<[1, 2]>`); the exact source was restored. Final **74 core_storage tests** and **13 existing DownloadValidator tests** passed, both modules compiled, core_storage lint found no issues, storage lint reported **0 errors / 52 warnings**, and scoped ktlint/governance passed: **BUILD SUCCESSFUL**. Logs: `.tmp/lease-task5-tests.log`, `.tmp/lease-task5-mutation.log`, `.tmp/lease-task5-verification.log`. `adb devices -l` currently shows only `emulator-5554 offline`; no successful ARM AAR multi-task run is claimed. Keep the final checkbox pending and continue independent Task 6; do not report the transport or lease plan fully accepted until real native coexistence is evidenced.

**Native completion evidence (2026-10-27):** `ThunderLeaseCoexistenceTest` ran the actual bundled AAR on the online API 34 AVD through ARM64 translation, using the public [Sintel video torrent](https://webtorrent.io/free-torrents). Same-torrent/same-file A/B tasks received distinct IDs and URLs; both retirement orders preserved the survivor in Running/Success state with no native task error. Result: **OK (1 test)**, `.tmp/lease-task9-native-video.log`; test APK build ended **BUILD SUCCESSFUL**. This caught a startup regression in the new adapter: `setTaskLxState` returned `INDEX_NOT_READY` while the bundled helper treats that hint as advisory. Restored that behavior while keeping actual creation/selection/start checks. Earlier generated/unseeded and non-video fixtures were unsuitable; their failures do not invalidate the final valid-video result. This establishes native task coexistence; full player/TV and transport data acceptance remain Task 9.

## Task 6: Make Bilibili session tokens lease-owned

**Dependencies:** Task 1; read the reference mapping in handoff design section 4.4 first.

**Files:**

- Create `core_storage_component/src/main/java/com/xyoye/common_component/storage/transport/BilibiliPlaybackTransport.kt`.
- Modify `core_storage_component/src/main/java/com/xyoye/common_component/storage/impl/BilibiliStorage.kt`.
- Modify `BilibiliPlaybackSessionStore.kt`, `BilibiliLivePlaybackSessionStore.kt`, `BilibiliPlaybackAddon.kt` and `BilibiliPlaybackHeartbeat.kt` under `bilibili_component/src/main/java/com/xyoye/common_component/bilibili/playback/`.
- Create `bilibili_component/src/test/java/com/xyoye/common_component/bilibili/playback/BilibiliPlaybackSessionStoreTest.kt`.
- Modify both playback Session implementations; add `BilibiliPlaybackWork.kt` and `BilibiliSessionManifest.kt` to cancel owned work and isolate/retire MPD files.
- Add the existing coroutines-test dependency to `bilibili_component/build.gradle.kts`.
- Create `core_storage_component/src/test/java/com/xyoye/common_component/storage/transport/BilibiliPlaybackTransportTest.kt`.
- Bring forward the Bilibili portion of Task 8: SourceFactory/Source token assembly, original-error propagation, VideoSourceManager instance-aware clearing, PlayerActivity detachment ordering and sequential queue retirement feedback/tests. Other transports and complete source-construction/entry ownership tests remain Task 8.

- [x] Test regular/live stores through token acquisition and removal: same-identity A/B coexist, old lease deletion preserves new session/heartbeat state, and Addon-only cleanup deletes no token. Include failure before source/Addon construction.
- [x] Make put return an unpredictable token; get/remove require identity plus that token. Heartbeat resources must be keyed by the same generation. Update production callers, not only store overloads.
- [x] BilibiliStorage captures the token, prepares the URL from that exact session, and returns a lease that alone owns deletion. Failed preparation deletes only the acquired token. Addon receives a fixed borrowed token and cancels only its own work onRelease.
- [x] Run the focused store tests and full bilibili tests. Keep account/login behavior unchanged. Candidate-based quality/URL switching is completed by the dependent handoff session; this lease phase must not introduce a second playback coordinator or re-enable the disabled gateway.

**Completed 2026-10-27:** Regular/live stores require an unpredictable generation token for lookup/removal; Addons borrow that exact token and only retire their own work. Session removal cancels its work and heartbeat jobs. Each regular session owns a separate MPD file, with publication serialized against retirement so a late result cannot recreate it. Playback preferences are still loaded during prepare; constructor-time MMKV access was deferred. BilibiliStorage's default provider path registers/prepares the exact session and returns its lease; failures/cancellation remove only that token. Factory failures preserve the original exception for the existing risk-control/voucher flow.

**Production wiring:** Bilibili Source creation now acquires the lease and passes its token directly into the Addon; no identity/latest-token bridge exists. Source owns Addon/lease cleanup. Existing same-source quality/recovery reuses that owner; candidate-based quality switching remains the handoff plan. The minimal safe retirement prerequisites were brought forward from Task 8: release after backend detachment, compare source instances, retain the current Source through configuration recreation, clean late preload results, and retain queued candidates that became active until backend release. Native transition removes the old queue item before notifying its owner. The rest of Task 8 remains unchecked, including all-protocol assembly, entry cancellation, complete ownership tests and full player/device acceptance.

**Reference:** Reused PiliPlus `lib/plugin/pl_player/playback_source.dart` and targeted `lib/pages/video/controller.dart` source-selection/recovery behavior. Mapping remains Bilibili protocol/session resources -> bilibili_component; lease/source assembly -> core_storage; backend retirement -> player_component. No Flutter UI or account/login policy was copied or changed.

**Verification:** The original regression failed when old Addon release deleted a newer same-identity session. Eight store/work/manifest/heartbeat tests and six transport tests now pass; the latter use the actual acquisition factory, BilibiliStorage provider and source token assembly with only URL preparation replaced by a recording operation. Final checks passed **63 bilibili tests / 15 suites**, **80 core_storage tests / 16 suites**, and **25 targeted player tests / 4 suites**, all with zero failures/errors. Compilation, bilibili/core_storage lint (No issues found), scoped ktlint and governance passed. Player lint initially found an existing missing Media3 opt-in; after adding its two-line declaration, the final lint/governance run ended **BUILD SUCCESSFUL** (0 errors / 20 warnings). The existing subtitle-controller fake also lacked three required interface methods; these were supplied to unblock player test compilation and its five regression tests passed. Initial transient Windows port exhaustion and a Kotlin 1.9.25 compiler failure for a default suspend lambda were resolved without changing system settings or dependencies; the unused default lambda was removed. Logs: `.tmp/lease-task6-red.log`, `.tmp/lease-task6-tests.log`, `.tmp/lease-task6-verification.log`, `.tmp/lease-task6-final-check.log`, `.tmp/lease-task6-format.log`. Bilibili live-service, device/TV acceptance and full player tests are still pending Tasks 8–9; the 25 targeted tests are not the full player suite.

## Task 7: Migrate upstream providers and validate real factory routing

**Dependencies:** Tasks 1–6 for the corresponding provider.

**Files:**

- Modify `WebDavStorage.kt`, `RemoteStorage.kt`, `AlistStorage.kt`, `BaiduPanStorage.kt`, `Open115Storage.kt` and `Cloud115Storage.kt` under `core_storage_component/src/main/java/com/xyoye/common_component/storage/impl/`.
- Modify `PlaybackTransportFactory.kt` and its tests introduced in Task 1.
- Add `DirectPlaybackTransport.kt` and `PlaybackTransportRoutingTest.kt`; migrate Link/Video/DocumentFile/Screencast providers explicitly.
- Cut over `StorageVideoSourceFactory` using Task 6's Source ownership foundation. Remove the obsolete URL methods from `Storage`, all providers, `LocalProxy`, `HttpPlayServer` and `ThunderManager`.
- Remove the duplicate Thunder stop-event path in `PlayTaskBridge`, `PlayTaskManager` and PlayerActivity; keep task diagnostics. Remove the now-obsolete HTTP legacy-slot test and factory URL-fallback spy.

- [x] Add factory routing tests for every resource-owning transport and known direct source. Assert provider failure does not invoke legacy createPlayUrl and SMB/FTP receive the newly resolved playback file. Releasing a result never closes borrowed browser state.
- [x] Separate upstream resolution from LocalProxy acquisition within each affected Storage, preserving profile-dependent headers/TLS/proxy/Range behavior without duplicate network requests. Retry callbacks must not retain connections that the browser can close; give any retained resource an owner in the lease.
- [x] Inventory legacy createPlayUrl/wrapIfNeeded callers and migrate all resource-producing playback paths together with Task 8. Do not silently substitute a direct URL where proxying is required, and do not leave a temporary lease with no release owner. Delete obsolete playback compatibility paths once migrated; retain only independently required, explicitly owned non-playback uses.
- [x] Run factory tests and core_storage tests. Verify the production assembly result's file is bound to the connection used by the lease; direct sources with borrowed pure metadata must be explicitly covered.

**Completed 2026-10-27:** All production Source creation now goes through PlaybackTransportFactory; the returned file supplies source extras/metadata and the returned lease remains Source-owned. Six HTTP upstream providers retain their existing resolution, headers, TLS, profile/proxy, Range and retry policies while returning independently owned leases. Their retained retry repositories/caches have no browser-owned closable connection (Baidu close is also a no-op). Link/local/document/cast providers explicitly return resource-free direct leases, rejecting this app's already-owned local proxy URL. The old bare-URL contract and compatibility slots were deleted instead of retaining a second resource path. Thunder task removal is now lease-only; the bridge still supplies diagnostics.

**Verification:** The initial direct-link test failed with the expected missing-provider assertion. Six real default-factory routing tests now pass: known direct sources, WebDAV URL/auth, remote token isolation, Alist cached-upstream resolution and all three cloud providers' non-video rejection without direct fallback. Existing SMB/FTP/Thunder/Bilibili factory tests cover their acquisition/cleanup paths. Cloud successful live-API resolution and TLS handshakes remain protocol acceptance in Task 9, not claimed by these routing tests. Final **85 core_storage tests / 17 suites** passed with zero failures/errors; player compilation, core_storage/core_contract lint (No issues found), core_system lint (0 errors / 2 warnings), scoped ktlint and governance ended **BUILD SUCCESSFUL**. Logs: `.tmp/lease-task7-red.log`, `.tmp/lease-task7-tests.log`, `.tmp/lease-task7-format.log`, `.tmp/lease-task7-verification.log`. Static caller inspection used git grep/targeted reads; no old URL/stop API invocation remains. Task 8 still owns complete Source assembly/cancellation/queue tests and full player acceptance; no device or whole-plan completion is claimed.

## Task 8: Transfer ownership into sources and retire only detached sources

**Dependencies:** Tasks 1–7.

**Files:**

- Modify `core_storage_component/src/main/java/com/xyoye/common_component/source/factory/StorageVideoSourceFactory.kt` and `source/media/StorageVideoSource.kt`.
- Modify `player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt`.
- Modify `player_component/src/main/java/com/xyoye/player_component/sequential/SequentialPlaybackCoordinator.kt` and `Media3SequentialPlaybackAdapter.kt` only where candidate retirement requires backend detachment feedback.
- Create `core_storage_component/src/test/java/com/xyoye/common_component/source/StorageVideoSourceFactoryTest.kt`.
- Create `player_component/src/test/java/com/xyoye/player_component/playback/StorageVideoSourceTransportTest.kt`.
- Add `player_component/src/main/java/com/xyoye/player_component/playback/PreparedSourceOwnership.kt` for the two asynchronous manual/fallback source-application paths.
- Modify `core_storage_component/src/main/java/com/xyoye/common_component/source/VideoSourceManager.kt` and add `source/VideoSourceManagerOwnershipTest.kt` to distinguish pending sources from the backend's claimed owner without changing routes.

- [x] Test real source creation over the acquisition seam: use the returned playbackFile for subtitles/danmaku/source data, retain queue identities after old source closure, and release the acquired owner on construction failure/cancellation. Include the compatibility indexSource path and two same-identity source instances.
- [x] Capture queue/history metadata without transferring the browser connection. Acquire transport, prepare using its playback file, then construct Source and transfer ownership once. If Addon creation succeeds but later construction fails, stop that Addon and release the lease independently. Do not allow the original input file to slip back into source construction.
- [x] Add one idempotent source release entry. Addon failure cannot skip lease cleanup; Source does not separately close lease-owned Storage/File, and future PreparedPlayback delegates rather than extracts a second lease owner. Direct-source resource borrowing must remain explicit.
- [x] Move existing early Addon release calls after backend detachment. Retire by source instance/lease, not only media identity. Cover normal switch, native transition, abandoned preload, late preparation, same-identity URL replacement, exit, and recreation. A queued item can be released only after the backend confirms removal or reports that it already became active; blind token invalidation is insufficient.
- [x] Run factory/ownership tests and full player/core_storage tests. Preserve current supported background/PIP and TV behavior. This task establishes safe release hooks; the dependent handoff work migrates all business state writers into ActivePlaybackSession.

**Completed 2026-10-27:** Source assembly now captures queue/history metadata, prepares extras from the acquired playback file, snapshots headers, explicitly tracks Addon creation and transfers one mandatory lease into Source. Failure/cancellation before delivery retires the current owner in a non-cancellable I/O context; Addon cleanup failure cannot skip lease cleanup. Queue advancement passes the captured queue back through the same assembly/acquisition path instead of rereading the browser's current directory. Same-source URL recovery keeps its existing owner; separate same-identity Sources retire independently.

**Retirement:** The Task 6 backend-detachment hooks are now combined with cancellation-safe manual/fallback dispatch and tested Source cleanup. The existing VideoSourceManager claims a Source before backend setup, retires overwritten unclaimed candidates and preserves a newer pending request while the current Source is applied/cleared. It delegates cleanup to Source; no second lease owner or new playback coordinator was introduced. Configuration recreation retains the current owner only when it is still the source the next Activity will claim. Sequential preparation stays on its worker dispatcher while queue mutation stays on the owning scope; late results and rejected queues retire correctly, and already-active queued items wait for backend release.

**Verification:** Seven source-assembly tests cover acquired-file use, header isolation, queue resolution after old playback-file closure, same-identity/URL reuse, failure before Addon, constructor failure with a throwing Addon, and two cancellation boundaries. Three manager tests cover pending replacement, claim protection and native-current updates. Four player dispatch tests cover main-thread cancellation, late preparation, instance identity and failure after attachment; Task 6's queue tests cover confirmed removal and transition/invalidation races. Final **95 core_storage tests / 19 suites** and **80 full player tests / 19 suites** passed, zero failures/errors. Compilation, core_storage lint (No issues found), player lint (0 errors / 20 warnings), scoped ktlint and governance ended **BUILD SUCCESSFUL**. Logs: `.tmp/lease-task8-tests.log`, `.tmp/lease-task8-full-tests.log`, `.tmp/lease-task8-format.log`, `.tmp/lease-task8-verification.log`. These are JVM/compilation/static checks; device PIP/background/TV smoke and real protocol acceptance remain Task 9.

## Task 9: Enforce boundaries and finish implementation verification

**Dependencies:** Tasks 1–8.

**Files:**

- Scoped production/tests above for failures found by verification.
- `.github/workflows/sonarcloud.yml` to invoke the existing dependency governance task.
- No unrelated module cleanup or dependency upgrades.

- [x] Inspect production callers for SMB/FTP singleton use, shared browser connection transfer, ordinary stopAllTask, identity-only Bilibili lookup/removal, unowned proxy URLs and duplicate close owners. Prefer AST search when available; use git grep/targeted reads if tools are unavailable and report the limitation. Static pattern absence is supplementary to behavioral factory tests.
- [x] Add `verifyModuleDependencies` to the existing CI verification command. Run core_storage, bilibili and player unit tests, then full JVM tests, dependency governance, lint and Debug build. Reuse unchanged successful checks; inspect each Gradle output tail.
- [x] Verify protocol integration with real fixtures: concurrent HTTP reads; SMB/FTP A/B playback plus browser access; Thunder same-torrent task coexistence; Bilibili old-token retirement preserving a new session. Missing fixtures are pending evidence, not passes.
- [x] Run the applicable TV DPAD smoke path and settings/back-stack/error-recovery regression. Use a device in `device` state for instrumentation. Record commands, results and remaining fixture/device limits; do not mark the implementation complete while required behavior is unverified.

**Task 9 implementation (2026-10-27):** CI now invokes `verifyModuleDependencies`. The initial full `testDebugUnitTest verifyModuleDependencies lint assembleDebug` run ended **BUILD SUCCESSFUL** (`.tmp/lease-task9-full-verification.log`). Static review then exposed a real ownership gap: SMB/FTP proxy URLs could be reacquired as direct links. Two production-factory regression tests failed for that behavior (`.tmp/lease-task9-alias-red.log`); the fix rejects reserved loopback lease routes and active Thunder URLs at direct/HTTP acquisition and HTTP refresh boundaries. The intermediate storage run had 97 passing tests and two skipped fixture tests; final verification below includes both real fixture tests. App/storage instrumentation APK compilation succeeded (`.tmp/lease-task9-device-build.log`).

**Earlier device diagnostics (superseded by final native evidence):** The Pixel_5_API_34 phone AVD (`emulator-5556`, ABI `x86_64,arm64-v8a`, `libndk_translation.so`) passed five core/settings instrumentation cases (`.tmp/lease-task9-app-smoke.log`). Native instrumentation required explicit `--abi arm64-v8a`. The strict advisory hint check and unsuitable generated/non-video torrent fixtures initially failed; Task 5's final Sintel test now establishes native coexistence. These earlier failures are diagnostic history, not current blockers. The phone AVD was stopped before starting the temporary TV-configured instance; phone tests alone do not establish TV acceptance. No Git index changes or commits were made.

**Final full verification (2026-10-27):** After the proxy-alias and native-hint fixes, the separate scoped `:core_storage_component:ktlintCheck`, full `testDebugUnitTest`, `verifyModuleDependencies`, `lint`, `assembleDebug` and `:app:assembleDebugAndroidTest` invocation ended **BUILD SUCCESSFUL in 2m 47s** (`.tmp/lease-task9-final-check.log`). All **386 JVM tests** passed with zero failures/errors/skips, including both real SMB/FTP fixture tests. The earlier combined format/check invocation raced and failed before formatting completed; format and final check ran separately.

**TV completion (2026-10-27):** Temporary API 34 `Pixel_5_API_34` instance `emulator-5558` ran with `-read-only -writable-system -no-snapshot -gpu swiftshader_indirect`, television/leanback features in its disposable system overlay, and observed `mCurUiMode=0x14`. This verifies the TV application branch on an AVD, not physical TV hardware/performance. Eight core/settings/dialog/TV instrumentation cases passed without skips (`.tmp/lease-task9-tv-input-final.log`, **OK (8 tests)**). The three DPAD tests now explicitly select non-touch input and inject keys through `Instrumentation.sendKeyDownUpSync`; direct Activity dispatch had bypassed ViewRoot unhandled-key routing, and the original dialog test had not checked dismissal. No production focus behavior was changed. Test APK compilation and app lint ended **BUILD SUCCESSFUL** (`.tmp/lease-task9-tv-input-build.log`).

**Manual TV evidence:** From `TvSplashActivity`, DPAD reached the personal/player-settings pages, changed VLC to Media3, returned with focus restored and retained the setting on re-entry (`.tmp/lease-tv-settings-before.xml`, `lease-tv-return.xml`, `lease-tv-persisted.xml`). A missing local test file displayed the playback error; DOWN/CENTER reached its exit action. A valid two-minute H.264/AAC fixture then rendered visible video; CENTER paused at 50.51 seconds, DOWN showed the visibly focused control bar, and BACK closed controls and exited through the existing confirmation behavior. Screenshots: `.tmp/lease-tv-main.png`, `lease-tv-settings.png`, `lease-tv-error.png`, `lease-tv-playing.png`, `lease-tv-panel.png`. Final hierarchy returned to `SettingPlayerActivity` (`.tmp/lease-tv-error-dismissed.xml`); player events are in `.tmp/lease-task9-tv-app-final.log`. Launching another external request before dismissing the old error can leave two legacy PlayerActivity instances; this is the already-planned singleTask/ID routing migration, recorded as a handoff baseline rather than a transport-isolation failure.

**Limits:** HTTP/SMB/FTP byte isolation, native Thunder task coexistence and Bilibili token/store ownership have the stated evidence; live authenticated cloud/Bilibili APIs, end-to-end BT streaming, physical TV codecs/performance and full background/PIP entry matrices are not implied. The dependent handoff plan retains its broader integration acceptance matrix. Intermittent Windows socket exhaustion affected ADB commands; rejected/failed commands were not counted as passes and no system service was modified. All 37 lease checks are complete; no Git index changes or commits were made.

**Protocol acceptance (2026-10-27):** `PlaybackTransportProtocolIntegrationTest` passed both opt-in tests against real loopback SMB2 (Impacket 0.13.0) and FTP (pyftpdlib 2.1.0) servers, using the default `StorageFactory`/`PlaybackTransportFactory`. Verified three independent connections, concurrent A/B byte reads, browser listing during playback, both retirement orders, and continued playback after browser closure. Result: **2 tests, 0 failures/errors/skips; BUILD SUCCESSFUL**, `.tmp/lease-task9-wire-final.log`. The fixture script is `scripts/testing/playback_lease_fixtures.py`; FTP normalizes the existing `LIST -a <path>` syntax because pyftpdlib only handles `-a` alone. The app already emitted that syntax before this refactor. HTTP real-loopback and Bilibili actual store/token retirement checks reuse Tasks 2/6 evidence; no live Bilibili account/API validation is implied. Thunder actual AAR coexistence is recorded under Task 5.

Reproduce the wire fixture tests in separate terminals: run the script with `smb` and `ftp` plus a dedicated fixture directory, set `DDPLAYTV_LEASE_FIXTURES=1`, then run `:core_storage_component:testDebugUnitTest --tests '*PlaybackTransportProtocolIntegrationTest'`. Without the explicit flag the two external-fixture tests are skipped. Run native instrumentation with `-e class com.xyoye.common_component.storage.transport.ThunderLeaseCoexistenceTest -e leaseTorrentPath <readable-video-torrent>`; the bundled AAR requires ARM, or explicit ARM64 translation on this AVD. Generated unseeded/non-video torrents are not valid native streaming fixtures.

## Verification commands

For focused tests use the classes named in each task, for example:

```powershell
.\gradlew.bat :core_storage_component:testDebugUnitTest --tests "com.xyoye.common_component.storage.transport.PlaybackTransportFactoryTest"
.\gradlew.bat :core_storage_component:testDebugUnitTest
.\gradlew.bat :bilibili_component:testDebugUnitTest
.\gradlew.bat :player_component:testDebugUnitTest
.\gradlew.bat verifyModuleDependencies
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lint
.\gradlew.bat assembleDebug
```

When a usable device and protocol fixtures are available:

```powershell
adb devices -l
.\gradlew.bat connectedDebugAndroidTest
```

Expected successful tail is `BUILD SUCCESSFUL`; report `BUILD FAILED` or wrapper/environment failures accurately. The previous documentation audit could not download Gradle 8.9 under the restricted network. That is not implementation verification and does not waive these checks.

## Completion gate

Only after these conditions are evidenced may the dependent handoff implementation rely on transport isolation:

- Source owns its Addon/lease through one release path; each underlying resource has exactly one close owner.
- Real factory acquisition proves SMB/FTP browser/A/B connections are independent, with failure/cancellation cleanup.
- HTTP contexts, Thunder task IDs and Bilibili tokens remain independent across preparation and retirement.
- Source assembly uses the acquired file/connection, and queue advancement does not depend on a closed prior connection.
- No provider failure falls back to an unowned raw URL; no ordinary path changes unrelated transport state.
- Backend detachment precedes source retirement, including native queued playback and same-identity replacement.
- Required unit, governance, lint, build, protocol and TV verification is recorded with actual results.

The dependent [handoff and active session implementation plan](2026-10-27-playback-handoff-session.md) now defines 14 tasks and 65 pending checks for request submission, recovery, entry/internal-switch migration and legacy-writer removal. Execute its production integration only after the lease completion gate above is evidenced. Both plans are written; neither document revision establishes completed implementation or runtime verification.
