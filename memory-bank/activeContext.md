# Active Context

## Current Focus
DIR-03 documentation consolidation is complete (2026-10-28). Current manuals, workflow designs/ledgers, artifact notes and agent memory have separate responsibilities and linked navigation in `document/README.md`. Obsolete June execution templates and raw TODO transcripts were replaced by concise decisions and an accurate pending-work record. The user additionally authorized OpenSpec value review: five changes were consolidated into current playback design/manuals and test guidance, with three original validation gaps preserved; redundant records and the default-only config were removed. Source/configuration checks, document/link/ledger validation, exact snapshot regeneration and module/playback governance results are recorded in `document/architecture/directory-hygiene-followup.md`. These documentation changes are uncommitted; the user's existing index has been preserved.

Directory cleanup items 1–2 were committed as `b545eb3ba`. DIR-04 (coverage/module attribution and package ownership) and DIR-05 (auxiliary tools/workflows) remain deferred. Record status and completion criteria in `document/architecture/directory-hygiene-followup.md`, not in a second task list.

## Playback Acceptance
Handoff Tasks 1–13 and two Task 14 checks remain recorded as complete: **62/65**. The user explicitly owns the three remaining integrated acceptance checks. Lease implementation is **37/37**. The documentation cleanup does not change these results or authorize new functional implementation.

The latest playback implementation is committed in `69903c36d` after `b2ce478eb`. The handoff ledger contains the 2026-10-28 evidence: 478 JVM tests, module/playback governance, full lint, scoped ktlint and APK builds passed; five final device regressions passed. These are historical results, not newly executed checks for the documentation task.

## Decisions and Limits
- Stable descriptors/IDs cross entry boundaries. Player owns the encrypted handoff store, active session, revisions, queue/candidate state and recovery; storage owns source resolution and independent transport acquisition.
- A controlled switch requires pre-activation persistence: encryption/save failure cancels the candidate and preserves old playback; initial playback cannot activate on failure.
- Native transitions update runtime first and save afterward. Candidates are memory-only. Failed post-transition persistence does not stop real playback; until retry succeeds, process recovery may return to the prior committed episode.
- Configuration recreation reuses live runtime. Snapshot-over-history recovery, fixed TTL and periodic/event-driven saves remain accepted decisions.
- The app Media3 service adapts metadata/commands. Existing popup no-ops do not provide full background/PIP hosting.
- Full architecture aggregation has known global ktlint/Pager baseline failures. Earlier intermittent FTP EOF/invalid-range failures were not causally isolated despite final/repeated passing runs.
- June matching support exists, but five repair/test items are still open in the historical decision summary. Subtitle language conversion is a pending requirement, distinct from existing danmaku conversion.
- Former OpenSpec seamless tasks 5.1/5.2 and Bilibili unexpected-completion task 2.3 remain unverified in `document/player/playback-validation-followup.md`. Partial newer tests do not close these original scenarios or the separate user-owned handoff acceptance.

## Next Steps
- DIR-03 is ready for review; do not infer authorization to commit or implement deferred work from its completion.
- Await the user's playback acceptance findings: repeated FTP replacement/seek, authenticated Bilibili quality/URL refresh/risk success, seeded BT, subtitle/danmaku continuity, nonzero process-restored position and full mobile/physical-TV navigation.
- Address DIR-04/DIR-05 or the preserved danmaku repairs only under a subsequent implementation request. Do not request test credentials again or expand the user-owned acceptance independently.

## Retrieval Map
- Current documentation navigation and responsibility map -> `document/README.md` -> start here when finding a maintained manual.
- Directory cleanup status, source of deferred DIR-04/05 tasks -> `document/architecture/directory-hygiene-followup.md` -> read before further directory/package/tool changes.
- Retained June behavior decisions and five pending matching repairs -> `docs/superpowers/archive/2026-06-player-focus-decisions.md` -> read before subtitle-toggle, matching or focus changes.
- Existing text conversion and pending subtitle conversion -> `document/player/subtitle-language-conversion.md` -> read before implementing subtitle-language settings.
- Bilibili history sync, legacy migration and completion trust -> `document/player/bilibili-playback-policy.md` -> read before changing heartbeat or abnormal-completion behavior.
- libass GPU degradation/recovery, vertical offset and mpv dedup -> `document/player/subtitle-rendering.md` -> read before changing subtitle rendering.
- Preserved seamless and Bilibili validation gaps -> `document/player/playback-validation-followup.md` -> read before claiming original OpenSpec acceptance complete.
- Playback reference rationale and accepted boundaries -> `document/architecture/2026-10-27-playback-refactor-reference-review.md` -> read before revising playback ownership.
- Stable requests, recovery and platform adaptation -> `docs/superpowers/designs/2026-07-18-playback-handoff-design.md` -> read before changing session/recovery contracts.
- Transport ownership design -> `docs/superpowers/designs/2026-07-19-playback-transport-leases-design.md` -> read before acquisition/release changes.
- Transport implementation evidence -> `docs/superpowers/plans/2026-07-19-playback-transport-leases.md` -> authoritative 37/37 ledger and protocol/device limits.
- Handoff implementation/acceptance evidence -> `docs/superpowers/plans/2026-10-27-playback-handoff-session.md` -> authoritative 62/65 ledger, failures and user-owned acceptance.
