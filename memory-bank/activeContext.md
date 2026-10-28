# Active Context

## Current Focus
Media3 is upgraded from 1.8.0 to stable 1.11.1 (authorized 2026-10-28), with Kotlin/AGP/Gradle and SDK requirements in `memory-bank/techContext.md`. Moshi/Room processing moved to KSP1; the libass legacy decoder has a method-scoped ExperimentalApi opt-in. Final Debug/Release/test APKs, full lint, scoped ktlint and governance ended BUILD SUCCESSFUL; 379 JVM tests passed, two external protocol fixtures skipped. Two subtitle, nine TV playback/DPAD and five mobile page checks passed. Initial device failures under concurrent build load and the historical unresolved VLC seek test are explicitly retained in the upgrade ledger. The AVD is restored to its original mobile/display/non-root baseline. No commit was requested. See `docs/superpowers/plans/2026-10-28-media3-upgrade.md` for exact commands and device limits.

Anime4K video post-processing is removed on all devices by user decision (2026-10-28): TV GPU cost is unsuitable. Player settings/contracts, mpv shader JNI, Media3 effects/diagnostics, nine shader assets and the `media3-effect` dependency are deleted. Normal video outputs, subtitles and subtitle-backend rebuilding remain. Debug/test APKs, all JVM tests (376 passed, two external fixtures skipped), lint and governance passed. Seven distinct TV-mode AVD checks passed; an additional VLC immediate-seek snapshot test repeatedly timed out and remains unresolved, with no pre-change baseline comparison. See `memory-bank/progress.md` for exact verification and `document/mpv_conf_supported_options.md` for the retained mpv capabilities. No commit was requested.

Authorized removal of all Bilibili features, including local-video Bilibili danmaku matching/download, is committed as `b4b971fdd` (2026-10-28). The module, UI/routes, playback Addons/risk/live integration and build edges are removed; local XML danmaku and Dandanplay remain. Room 16->17, retired credential cleanup and independent legacy handoff-slot cleanup are covered. Build/full lint/module and playback governance passed; 376 JVM tests passed with two external FTP/SMB fixture tests skipped. Mobile pages/three kernels/credential cleanup, corrected reentry, four TV regressions and three real Keystore/MMKV tests passed. Commands, initial failures, screenshots and device limits are in `docs/superpowers/plans/2026-10-28-remove-bilibili.md`.

The authorized TV DPAD rewrite is implemented with DpadRecyclerView 1.4.3 across TV lists/menus, explicit categories and native static controls. Both legacy list engines, index/retry scrolling and focus-driven Pager switching are removed. Strict compilation and isolated R8 compatibility passed with Kotlin 1.9.25 / JVM 1.8 unchanged. Debug/Release, full JVM tests (376 passed, 2 external protocol fixtures skipped), lint, scoped ktlint and governance passed on 2026-10-28. Final TV-mode AVD regression passed 15/15, including empty-subtitle toolbar reachability; mobile page smoke passed 5/5. A touch settings round trip verified toggle persistence and restored the original value. Temporary AVD TV/display modifications were removed and non-root mobile mode restored. Track authoritative commands, exact coverage and remaining physical-TV/real-network acceptance in `docs/superpowers/plans/2026-10-28-tv-dpad-library-rewrite.md`. No commit was requested.

The user authorized removing low-value documentation and obsolete Bilibili material (2026-10-28). Redundant architecture analyses and the duplicate platform manual are removed; `document/` now has 20 files. Active playback designs and ledgers retain general constraints and historical verification evidence. Current manuals start at `document/README.md`; upgrade cleanup and its validation have one home in the Bilibili removal ledger. The earlier documentation consolidation is in commit `b02a1e963`. Fresh documentation-task verification ran `verifyModuleDependencies verifyPlaybackBoundaries --offline --console=plain`: BUILD SUCCESSFUL in 9s (`.tmp/document-cleanup-governance.log`). No business tests were rerun.

DIR-04 (coverage/module attribution and package ownership) and DIR-05 (auxiliary tools/workflows) remain deferred. Their eight completion conditions are retained in the shortened `document/architecture/directory-hygiene-followup.md`; completed cleanup transcripts are no longer maintained there.

## Playback Acceptance
The handoff and lease ledgers retain historical counts of **62/65** and **37/37**. The user owns the remaining integrated acceptance for retained features; retired platform checks are no longer requirements and are not marked passed. Documentation cleanup does not authorize new functional implementation.

The handoff implementation is committed in `69903c36d` after `b2ce478eb`, followed by the removal commit above. Its ledger contains the 2026-10-28 evidence: 478 JVM tests, module/playback governance, full lint, scoped ktlint and APK builds passed; five final device regressions passed. These are historical results, not newly executed checks for the documentation task.

## Decisions and Limits
- Stable descriptors/IDs cross entry boundaries. Player owns the encrypted handoff store, active session, revisions, queue/candidate state and recovery; storage owns source resolution and independent transport acquisition.
- A controlled switch requires pre-activation persistence: encryption/save failure cancels the candidate and preserves old playback; initial playback cannot activate on failure.
- Native transitions update runtime first and save afterward. Candidates are memory-only. Failed post-transition persistence does not stop real playback; until retry succeeds, process recovery may return to the prior committed episode.
- Configuration recreation reuses live runtime. Snapshot-over-history recovery, fixed TTL and periodic/event-driven saves remain accepted decisions.
- The app Media3 service adapts metadata/commands. Existing popup no-ops do not provide full background/PIP hosting.
- Full architecture aggregation has known global ktlint/Pager baseline failures. Earlier intermittent FTP EOF/invalid-range failures were not causally isolated despite final/repeated passing runs.
- June matching support exists, but five repair/test items are still open in the historical decision summary. Subtitle language conversion is a pending requirement, distinct from existing danmaku conversion.
- Sequential playback integration and TV smoke acceptance remain pending in `document/player/playback-validation-followup.md`. Partial newer tests do not close these scenarios or the separate user-owned handoff acceptance.

## Next Steps
- Media3 upgrade implementation and local verification are complete. Physical-TV/Android 6 and 16 compatibility and full HDR/DRM/network-media acceptance remain outside the observed AVD coverage; preserve the upgrade ledger's limits and KSP1 requirement when continuing.
- TV rewrite code and local verification are complete. Physical-TV Release navigation, long-hold/rapid-reversal responsiveness and the untested real-network scenarios remain pending; see the rewrite ledger before claiming full device acceptance.
- Documentation pruning is committed as `575b9db24`; no new commit was requested for the rewrite.
- Await the user's playback acceptance findings: repeated FTP replacement/seek, seeded BT, subtitle/danmaku continuity, nonzero process-restored position and full mobile/physical-TV navigation.
- Address DIR-04/DIR-05 or the preserved danmaku repairs only under a subsequent implementation request. Do not request test credentials again or expand the user-owned acceptance independently.

## Retrieval Map
- Media3 1.11.1 upgrade -> `docs/superpowers/plans/2026-10-28-media3-upgrade.md` -> read before changing the media/toolchain versions, Moshi/Room processing, legacy libass decoding or interpreting upgrade verification.
- Retired Anime4K and retained mpv options -> `document/mpv_conf_supported_options.md` -> read before changing video output or proposing shader post-processing; removal verification and the unresolved VLC test are in `memory-bank/progress.md`.
- Approved TV focus/library rewrite -> `docs/superpowers/designs/2026-10-28-tv-dpad-library-rewrite-design.md` -> read before DPAD migration; contains scope, dependency risks, removal targets and acceptance gates.
- TV rewrite execution -> `docs/superpowers/plans/2026-10-28-tv-dpad-library-rewrite.md` -> read before continuing implementation or interpreting verification status.
- Current documentation navigation and responsibility map -> `document/README.md` -> start here when finding a maintained manual.
- Deferred DIR-04/05 tasks -> `document/architecture/directory-hygiene-followup.md` -> read before further directory/package/tool changes.
- Retained June behavior decisions and five pending matching repairs -> `docs/superpowers/archive/2026-06-player-focus-decisions.md` -> read before subtitle-toggle, matching or focus changes.
- Existing text conversion and pending subtitle conversion -> `document/player/subtitle-language-conversion.md` -> read before implementing subtitle-language settings.
- Retired integration upgrade compatibility and verification -> `docs/superpowers/plans/2026-10-28-remove-bilibili.md` -> read before modifying legacy-data cleanup or interpreting old platform acceptance.
- libass GPU degradation/recovery, vertical offset and mpv dedup -> `document/player/subtitle-rendering.md` -> read before changing subtitle rendering.
- Sequential playback validation gaps -> `document/player/playback-validation-followup.md` -> read before claiming playback integration or TV acceptance complete.
- Stable requests, recovery and platform adaptation -> `docs/superpowers/designs/2026-07-18-playback-handoff-design.md` -> read before changing session/recovery contracts.
- Transport ownership design -> `docs/superpowers/designs/2026-07-19-playback-transport-leases-design.md` -> read before acquisition/release changes.
- Transport implementation evidence -> `docs/superpowers/plans/2026-07-19-playback-transport-leases.md` -> historical 37/37 ledger and protocol/device limits.
- Handoff implementation/acceptance evidence -> `docs/superpowers/plans/2026-10-27-playback-handoff-session.md` -> historical 62/65 ledger, failures and retained user-owned acceptance.
