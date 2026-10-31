# Active Context

## Current State

Reconciled with Git and the maintained ledgers on 2026-10-31; no application tests were rerun for this memory-only cleanup.

- Confirmed UI implementation work is complete: Cinema correction, playback A/U01-U10, file-browser C and the seven final child groups R01-R07. Latest UI commit is `b0985b9b6`; C is `183374bd2`, and the first deep-page round is `50b6a770c`. The worktree was clean when this cleanup began. Earlier "not committed" records describe previous execution points.
- The latest local UI validation is recorded in [progress](progress.md) and the [UI-polish ledger](../docs/superpowers/plans/2026-10-31-ui-polish.md). Builds, AVD tests and inspected screenshots establish their stated local scope, not all service states or physical-TV acceptance.
- Release packaging/TV installation of the final UI changes is not recorded in that ledger. Earlier TV installation records refer to older builds. The latest task-owned AVD, Edge and preview server were closed; query current device/process state when needed. The user previously objected to unsolicited TV usage; no additional TV navigation/playback test is currently authorized.

## Open Work and Verification Boundaries

These remain open outside completed UI implementation. Archiving records does not close them or resume implementation.

| Area | Remaining work / when to read | Authoritative entry |
| --- | --- | --- |
| UI delivery and acceptance | Latest Release/deployment when requested; physical-TV DPAD, long-hold/rapid-reversal, failed switching/non-seekable sources, conditional account/authorization/IME and complete source-entry states | [UI R01-R07 and A/U01-U10](../docs/superpowers/plans/2026-10-31-ui-polish.md), [player P01-P10](../docs/superpowers/plans/2026-10-31-player-controls.md), [file C01-C11](../docs/superpowers/plans/2026-10-31-file-browser-preview.md) |
| TMDB/source acceptance | Real local/SAF/SMB/WebDAV environments, recognition accuracy and full service/error matrix; additional protocols/background scanning remain deferred. Actual API/artwork has separate Cinema evidence | [TMDB ledger](../docs/superpowers/plans/2026-10-31-tmdb-media-library.md), [Cinema correction](../docs/superpowers/plans/2026-10-31-cinema-ui.md) |
| Integrated playback | User-owned repeated FTP replacement/seek, subtitle/danmaku continuity, nonzero process-restored position, retained-feature navigation and sequential playback. Historical 62/65 and 37/37 counts are not current all-feature acceptance totals | [handoff acceptance](../docs/superpowers/plans/2026-10-27-playback-handoff-session.md), [sequential playback](../document/player/playback-validation-followup.md) |
| Hardware/subtitles/performance | Physical-TV PQ/HDR/passthrough, API 25/Android 16 compatibility, representative fonts/bitmap/complex ASS and live timelines, long-run resource limits. Precise presentation/atlas/worker splitting remains measurement-gated | [subtitle behavior](../document/player/subtitle-rendering.md), [Media3 libass evidence](../docs/superpowers/plans/2026-10-30-media3-libass.md), [mpv evidence](../docs/superpowers/plans/2026-10-29-mpv-native-subtitles.md) |
| Retained feature backlog | Five historical danmaku matching repairs/tests and subtitle language conversion. The unified retrieval migration did not complete these or the sending API | [matching backlog](../docs/superpowers/archive/2026-06-player-focus-decisions.md), [subtitle conversion](../document/player/subtitle-language-conversion.md) |
| Engineering backlog | DIR-04 module/coverage attribution and DIR-05 auxiliary tools/workflows; historical global ktlint/Pager baseline failures remain distinct from scoped passing checks | [DIR-04/05](../document/architecture/directory-hygiene-followup.md), [governance](../document/architecture/architecture_governance_guardrails.md) |

Retired Bilibili/VLC/Anime4K/mobile/manual-stream/magnet checks are superseded, not marked passed. The historical directory-recreation failure now has passing local C evidence; its original cause was not conclusively attributed. Accepted subtitle/danmaku placement and chosen A/C designs remain fixed product decisions in [projectBrief](projectBrief.md).

## Retrieval Map

| Topic | Repository-relative entry | Read condition |
| --- | --- | --- |
| Selected UI and missing-design history | `memory-bank/projectBrief.md`; `docs/superpowers/plans/2026-10-31-ui-design-coverage-audit.md` | Before UI redesign; audit findings are historical, current repair state is in the UI ledger above |
| Architecture and data ownership | `memory-bank/systemPatterns.md`; `document/architecture/module_dependency_governance.md` | Before changing modules, session/lease boundaries, metadata or UI ownership |
| Resume/query/lifecycle and global settings | `docs/superpowers/plans/2026-10-30-ui-readiness-repairs.md`; `docs/superpowers/plans/2026-10-30-global-settings.md` | Before changing source-scoped history/queries, explicit resume, View lifetimes, player preference or PC grouping; ledgers link the designs |
| Kernels, native binaries and decoding | `document/player/player-kernels.md`; `document/mpv-build-notes.md`; `document/player/media3-decoding.md`; `document/mpv_conf_supported_options.md` | Before kernel/config/dependency or video-output changes |
| Environment and historical test results | `memory-bank/techContext.md`; `memory-bank/progress.md` | Before builds/device checks, or when an exact old failure/result matters; archives are loaded only for that topic |
| Dandanplay retrieval and upgrade compatibility | `memory-bank/archive/2026-10-completed-work.md` (Dandanplay Retrieval Migration); `docs/superpowers/plans/2026-10-31-remove-stream-magnet.md`; `docs/superpowers/plans/2026-10-28-remove-bilibili.md` | Before altering unified retrieval or retired-data cleanup; historical evidence is not a fresh service check |
