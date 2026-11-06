# Progress

## Atlas and Batched Subtitle Rendering (2026-11-05)

Completed the user-requested Atlas task against `ba9d6d045` plus this working tree. Ordered bounded pages and per-vertex colors reduce texture binds/draws while preserving legacy fallback, composition, timing, fonts and state/Surface recovery. The [implementation ledger](../docs/superpowers/plans/2026-11-04-media3-subtitle-hardening.md#atlas-rendering-implementation-2026-11-05) owns the pinned Plezy reference mapping, GLES 2/3 pixel equivalence, same-emulator CPU gains and increased texture/upload area. Final verification: `BUILD SUCCESSFUL in 7m 39s`, 536 JVM tests (534 passed, two existing skips), root Debug lint/scoped Kotlin/governance, 37 player plus six app device tests, repeated resource/release cycles and actually inspected final-package DPAD smoke. Physical-TV performance, high-FPS/4K improvement and the original 115-video acceptance remain unmeasured/unresolved. No version bump, Release packaging or TV access occurred; task emulator 5576/private adb 5095 were closed with zero owned processes/listeners. Precise presentation, worker splitting, prewarm and compressed-error recovery remain unimplemented.

## Subtitle Feature Defect Repairs (2026-11-04)

Implemented the user's requested fixes for persisted/UI/kernel offset consistency, active-format style controls with independent text/ASS scaling and reset/focus preservation, and stable external-subtitle identity with duplicate-track reuse. Global offset persistence and existing source/cancellation/queue contracts remain. The [follow-up ledger](../docs/superpowers/plans/2026-11-04-media3-subtitle-hardening.md#subtitle-feature-fixes-2026-11-04) owns reproduction and verification: `BUILD SUCCESSFUL in 12m 45s`, 535 JVM tests (533 passed, two skipped; 177 player tests passed), Debug lint/scoped Kotlin/governance, 26 player plus four app emulator regressions, two large-font reruns, actually inspected screenshots and final-package DPAD smoke. Real PGS geometry and the original 115 video remain unverified. No version bump, Release packaging, TV access, staging or commit occurred; the TV's 16:45 package predates these fixes. Task emulator 5574 and private adb 5093 were closed with process/listener cleanup verified. At that stage, cross-file preferences, text/bitmap geometry, compressed-error recovery and Atlas remained unimplemented; Atlas was subsequently completed in the 2026-11-05 follow-up above.

## Plezy Subtitle Release and TV Installation (2026-11-04)

At the user's request, rebuilt **4.2.7 / 74** without another version increment and installed ARM64 on Hisense VIDAA TV at **16:45:30**. `:app:assembleRelease` ended with `BUILD SUCCESSFUL in 2m 7s`, including vital lint. All three APKs passed manifest/ABI/non-debuggable, signature and alignment checks; native libraries and replacement subtitle classes are present. `adb install -r` returned Success; actual version and installed/local SHA-256 match, with UID/first-install time unchanged. The [delivery record](../docs/superpowers/plans/2026-11-04-media3-subtitle-hardening.md#plezy-release-427-tv-install-2026-11-04) owns hashes, evidence and private-ADB 5091 cleanup at 16:46:41. No app launch/navigation/playback or settings change occurred. This package replaces the earlier 4.2.7 that failed the user's retest; original-video acceptance is still pending. Work remains uncommitted and staging is untouched.

## Plezy Subtitle Implementation (2026-11-04)

Implemented the user-authorized format-routing design against `a33dd791a` / Media3 `1.11.1`: public extraction-time ASS input, per-source/track ownership, libass for embedded/external ASS, complete ordinary Cues and real-track external additions. Explicit/default MKV zlib and header stripping now pass reproduced extraction/native pixel comparisons. The [execution ledger](../docs/superpowers/plans/2026-11-04-media3-subtitle-hardening.md#plezy-subtitle-implementation-2026-11-04) owns failed attempts, timing/preload/seek/queue/cancellation repairs and evidence. Final build/JVM/lint/scoped Kotlin/governance succeeded; 172 player JVM tests, 24 native/Media3 tests and 3 app DPAD regressions passed, with actual subtitle screenshots and final-package startup/core-page/BACK/exit smoke inspected. Earlier root validation had 528 tests, zero failures/errors and two skips. Ordinary Cue offset changes may briefly buffer while recreating the source; native outline/shadow fidelity is documented. Original 115 compression/cause and physical-TV acceptance remain unconfirmed. Implementation used only the task emulator, without a version bump or Release at that stage; subsequent packaging/installation is recorded above. Work remains uncommitted and emulator/private adb cleanup is verified.

## Plezy Subtitle Architecture Target (2026-11-04)

After the user allowed using Plezy's design, revised the [target](../docs/superpowers/plans/2026-11-04-media3-subtitle-hardening.md#plezy-architecture-target-2026-11-04) to extraction-time ASS input, libass for embedded/external ASS, native ordinary Cues and unified track selection. That source-only assessment identified missing MKV zlib text handling before stock Media3 assembles/truncates data. It involved no production edit or runtime test at the time; subsequent authorized implementation and reproduction are recorded above. Actual-video compression/cause remains unconfirmed. The earlier embedded-native-by-default recommendation below is superseded.

## Subtitle Source-routing Assessment (2026-11-04)

Evaluated the user's external libass / embedded Media3 proposal against pinned Plezy and Jellyfin Android TV sources. The [assessment](../docs/superpowers/plans/2026-11-04-media3-subtitle-hardening.md#source-based-routing-assessment-2026-11-04) records both references' format-based ASS integration, this project's confirmed text-Cue flattening, and the then-proposed bounded split: embedded native by default, external ASS/SSA libass, ordinary external text native with legacy-format compatibility. Complex embedded ASS loses rendering fidelity; recovery of the actual 115 video is unverified. No strategy implementation, device operation, build or runtime test occurred. This proposal was not implemented and is superseded by the Plezy architecture target above.

## Release 4.2.7 Subtitle Retest Failed (2026-11-04)

The user reported Media3 subtitles still invisible after testing 4.2.7, then asked about native rendering. The [follow-up record](../docs/superpowers/plans/2026-11-04-media3-subtitle-hardening.md#release-427-user-retest-2026-11-04) owns successful direct-HTTP collection, missing new parser/render metrics, confirmed Cue suppression/no native fallback, and the proposed native comparison with ASS feature tradeoffs. The actual-video cause remains unresolved; no player code, device settings, navigation, playback control, build or test was changed or executed in this follow-up. Logs remain ignored local artifacts.

## Release 4.2.7 TV Installation (2026-11-04)

The user separately requested TV connection and installation. Installed the verified ARM64 **4.2.7 / 74** Release on Hisense VIDAA TV with `adb install -r` (Success), then verified actual version and installed/local APK SHA-256 equality; UID and first-install time are unchanged. The [installation record](../docs/superpowers/plans/2026-11-04-media3-subtitle-hardening.md#release-427-tv-install-2026-11-04) owns readback and private-ADB 5091 cleanup at 13:52:03. No app launch, navigation, playback or settings change occurred; original-video/TV runtime acceptance remains pending. No source change or commit occurred, and existing staged content is preserved.

## Release 4.2.7 Packaging (2026-11-04)

At the user's request, incremented 4.2.6 / 73 to **4.2.7 / 74** and built ARM64, ARM32 and universal Release APKs containing the subtitle hardening below. `:app:assembleRelease` ended with `BUILD SUCCESSFUL in 4m 24s`, including vital lint; actual manifest versions/ABIs/non-debuggable state, signatures matching 4.2.6 and ZIP alignment passed. The [packaging record](../docs/superpowers/plans/2026-11-04-media3-subtitle-hardening.md#release-427-2026-11-04) owns paths and hashes. Packaging itself involved no device operation; the later installation is recorded above. No publishing or commit occurred, and existing staged content is preserved.

## Media3 Subtitle Hardening (2026-11-04)

Implemented the user-authorized subtitle hardening while preserving UI and Media3/mpv ownership. The [execution ledger](../docs/superpowers/plans/2026-11-04-media3-subtitle-hardening.md#subtitle-hardening-2026-11-04) owns ASS header/query/binding/name/attachment/diagnostic fixes, red/green evidence, successful Debug builds/JVM/lint/scoped Kotlin/governance, 175 player JVM tests, 19 native/Media3 tests, 3 application DPAD regressions and final-package smoke. The [maintenance document](../document/player/subtitle-rendering.md) owns current contracts. Local validation is complete and the task emulator/private adb are closed; the actual 115-video cause and physical-TV/user acceptance remain open. No TV access or commit occurred; subsequent Release packaging is recorded above.

## Open Player Bottom Bar (2026-11-03)

Implemented the user-confirmed `variant=open` layout with the timeline above nine actions, independent circular track buttons and an episode pill. The [B01–B06 implementation ledger](../docs/superpowers/plans/2026-11-03-player-bottom-open.md) owns successful builds, all Debug JVM/lint/governance checks, scoped Kotlin checks, instrumented DPAD/kernel/persistence regressions, final native screenshot comparison and 1.3-font evidence. Production subtitle settings/placement and A06 are retained. Local verification is complete; physical-TV/user final acceptance remains pending, with no new TV operation authorized.

## User Acceptance and Archival (2026-11-03)

The user confirmed that all pending activeContext verification items passed and physical-TV testing found no problems. The batch is **accepted and archived**; its [single acceptance record](archive/2026-11-completed-work.md#user-confirmed-acceptance-2026-11-03) records scope and evidence attribution. Latest recorded delivery is ARM64 Release **4.2.5 / 72**, installed on **2026-11-02 at 21:49:17**; original build/install identities remain in the linked execution ledger. This documentation closeout adds no automated test, device operation or build result.

The former progress journal and unique completed active-context notes now live in the November archive. [activeContext](activeContext.md) retains only current status, deferred implementation, known engineering issues and conditional retrieval. Old pending-acceptance statements belong to their execution dates and do not reopen this accepted batch. Unimplemented recommendations and known tooling defects remain separate.

Documentation checks passed: archive transfer integrity, 433 local link/anchor occurrences, 38 retrieval-map paths and `git diff --check`.

## Completed-work Archive

| Need | November archive section |
| --- | --- |
| Low/limited-value backlog withdrawals and superseded old checkboxes | [Backlog pruning](archive/2026-11-completed-work.md#backlog-pruning-2026-11-03); current retained work is in [activeContext](activeContext.md) |
| Accepted UI/playback/metadata/cleanup/hardware verification scope | [User-confirmed acceptance](archive/2026-11-completed-work.md#user-confirmed-acceptance-2026-11-03) |
| Screenshot tracking policy migration and Java local-connection failure | [Screenshot tracking](archive/2026-11-completed-work.md#screenshot-tracking-2026-11-03) |
| A02 focus implementation and Apple-style audit baseline | [Additional context](archive/2026-11-completed-work.md#additional-archived-context) |
| 4.2.5 delivery, A03/A06, UX01, source cleanup, artwork/home/detail/queue repairs and earlier deliveries | [Implementation/delivery journal](archive/2026-11-completed-work.md#implementation-and-delivery-journal) |
| Automatic recognition and the original 4.2.3 metadata delivery | [Playback-triggered metadata](archive/2026-11-completed-work.md#playback-triggered-metadata-2026-11-01), [Release delivery](archive/2026-11-completed-work.md#automatic-metadata-release-delivery-2026-11-01) |
| File-menu simplification and exit/reentry playback-history repair | [Menu evidence](archive/2026-11-completed-work.md#file-browser-menu-simplification-2026-11-01), [history repair](archive/2026-11-completed-work.md#playback-history-repair-2026-10-31) |

[Completed work through 2026-10-31](archive/2026-10-completed-work.md) preserves dated results, failed attempts, superseded decisions and original evidence links. Read only the relevant section:

| Need | Archive section |
| --- | --- |
| UI corrections, C, R01-R07, TMDB, kernel/platform migrations, retirements and documentation cleanup | [Implementation journal](archive/2026-10-completed-work.md#implementation-journal) |
| Unified comment API, Long comment IDs, v2 search, real-service checks and ktlint failure boundaries | [Dandanplay retrieval](archive/2026-10-completed-work.md#dandanplay-retrieval-migration-2026-10-31) |
| Anime4K removal and the subsequently retired VLC failure | [Anime4K evidence](archive/2026-10-completed-work.md#anime4k-removal-verification-2026-10-28) |
| Handoff/transport implementation stages and historical counts | [Playback history](archive/2026-10-completed-work.md#playback-implementation-history) |
| Global-settings migration or minSdk 25 verification | [Additional records](archive/2026-10-completed-work.md#additional-completed-work-records) |
| Old AVD/display/toolchain/network workarounds | [Historical environments](archive/2026-10-completed-work.md#historical-local-test-environments) |

No task is reopened solely because its archived entry says "next", "pending" or "deferred". Archival alone does not establish acceptance; the user's 2026-11-03 confirmation closes this batch. Retired scenarios remain superseded, and original failed/skipped automated runs retain their recorded results.
