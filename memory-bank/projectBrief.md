# Project Brief

## Product and Scope

DDPlayTV is a modular Android TV media app using Kotlin, XML views, MVVM, ARouter and Gradle. TV/DPAD is the sole maintained UI target (user decision, 2026-10-29), including Android boxes without standard television UI mode. Focus must remain visible, reachable and returnable.

- Retained capabilities: local/network storage, file browsing, casting, media-library metadata, anime information, playback history, Media3/mpv, Dandanplay/local danmaku playback, subtitles and ordinary playback cache.
- TMDB is the selected unified metadata provider for movies, TV series and anime. Dandanplay danmaku remains separate. Playback-triggered recognition and same-directory series discovery cover all nine retained library types, without mandatory folder types or manual title/episode matching; current verification is tracked in the [all-source ledger](../docs/superpowers/plans/2026-11-01-all-source-auto-metadata.md). Current usage and limits: [metadata follow-up](../document/media-library/tmdb-metadata-followup.md).
- Retired by user decision: Dandanplay PC media-library integration and inaccessible danmaku sending (2026-11-01), Bilibili integration (standalone PiliPlus owns that workflow), VLC, Anime4K, mobile UI, manual URL/header playback, external video VIEW reception, torrent/magnet playback/search and Thunder. Older repository descriptions do not change this scope. PC legacy rows remain stored for compatibility, with no active source/player entry.
- Media3 is the default player; mpv is the other maintained kernel. Compatibility and old-setting behavior: [player kernels](../document/player/player-kernels.md).
- Identified series detail/resume/history share a whole-series cross-season queue of discovered files; specials stay separate. Prefer the current source and prompt for alternatives, retain per-file history, and deduplicate merged physical files. [Queue design and references](../docs/superpowers/designs/2026-11-01-series-playback-queue-design.md); [local verification/limits](../docs/superpowers/plans/2026-11-01-series-playback-queue.md).
- Preserve real source/file/history identities, existing manual metadata bindings, playback progress and settings persistence. Removing manual matching UI must not silently discard legacy data. Reduce unclear ownership while retaining supported behavior.
- Retired standalone anime discovery (2026-11-01): the unreachable old homepage, weekly/seasonal browsing and anime-name search pages are removed. Current media-library search, anime details/collections and danmaku retrieval remain; existing anime search-history rows are preserved. [Cleanup evidence](../docs/superpowers/plans/2026-11-01-legacy-anime-discovery-cleanup.md).

## Selected Visual Authority

| Surface | Authoritative reference |
| --- | --- |
| Main Cinema experience | [index.html, cinema](../document/prototypes/tv-ui/index.html?variant=cinema) |
| Playback controls (implemented and locally verified 2026-11-03; user/TV acceptance pending) | [open controls with timeline above](../document/prototypes/tv-ui/player-bottom-refinement-prototype.html?variant=open), [single-line subtitle quick menu](../document/prototypes/tv-ui/player-bottom-refinement-prototype.html?variant=open&review=subtitle&layout=vertical), [design](../docs/superpowers/designs/2026-11-03-player-bottom-open-design.md), [implementation](../docs/superpowers/plans/2026-11-03-player-bottom-open.md); supersedes the A bottom tray, preserving nine actions, subtitle rendering/settings and A06; subtitle selection/Off now belongs to the bottom menu, details to the sidebar |
| File browser C | [list and preview](../document/prototypes/tv-ui/files-prototype.html?variant=preview) |
| Series detail B (implemented 2026-11-01; user/TV accepted 2026-11-03) | [cinematic episode shelf](../document/prototypes/tv-ui/series-detail-prototype.html?variant=B), [scope and acceptance](../docs/superpowers/designs/2026-11-01-home-watch-history-design.md); supersedes the old metadata detail layout below |
| Deep pages and final seven child groups | [deep pages](../document/prototypes/tv-ui/deep-pages.html), [remaining pages](../document/prototypes/tv-ui/remaining-pages.html), [supplemental design](../docs/superpowers/designs/2026-10-31-ui-polish-design.md) |
| A02 focus-state refinement (implemented 2026-11-02; user/TV accepted 2026-11-03) | [continuous primary fill, single focus edge and independent selection](../document/prototypes/tv-ui/focus-feedback-prototype.html), [implementation and F01–F12 evidence](../docs/superpowers/plans/2026-11-02-a02-focus-feedback.md); refines the states above while preserving their page structures, dimensions and typography |
| A06 right-side player-panel materials (implemented 2026-11-02; user/TV accepted 2026-11-03) | [opaque cold-gray surface and right-side dimming](../document/prototypes/tv-ui/player-panel-layers-prototype.html?panel=settings&mode=after), [nine-template implementation and M01–M11 evidence](../docs/superpowers/plans/2026-11-02-a06-player-panel-layers.md); preserve existing geometry and A02 states, excluding the left picker, keyword form and old 320dp danmaku configuration |
| A03 container hierarchy (implemented 2026-11-02; user/TV accepted 2026-11-03) | [open tool lists and anime children](../document/prototypes/tv-ui/container-hierarchy-prototype.html), [C01–C09 implementation and native evidence](../docs/superpowers/plans/2026-11-02-a03-container-hierarchy.md); preserve real actions, full reading/attribution, A02/A06 and the original hidden recommendation tab; large-font directory rows and website scrolling must fit |

The user's 2026-11-01 decision removes on-screen back buttons and player-panel close icons in favor of remote BACK navigation. This supersedes those controls in the prototypes; [the cleanup ledger](../docs/superpowers/plans/2026-11-01-remote-back-cleanup.md) records focus replacements and verification. Business confirmation/cancellation actions remain.

The user accepted subtitle positioning and danmaku/header overlap. Earlier UI completion claims were withdrawn because implementation structure did not match the selected prototype; page/state coverage and native screenshot comparison are part of the recorded acceptance basis. On 2026-11-03 the user confirmed all pending active-context verification and physical-TV testing passed; the [acceptance archive](archive/2026-11-completed-work.md#user-confirmed-acceptance-2026-11-03) owns that closeout. Current status, deferred implementation and known engineering issues are in [activeContext](activeContext.md); old pending-acceptance statements describe their original execution dates. Agent workflow requirements live in AGENTS.md.

## Architecture and Documentation

- App/features compose lower-level core, contract and data modules; features do not directly depend on one another. Included modules are defined by settings.gradle.kts. Ownership and dependency rules: [systemPatterns](systemPatterns.md).
- The authorized [A01–A08 cleanup](../docs/superpowers/plans/2026-11-01-tv-scope-cleanup.md) removes unused Media3 scaffolding, the forwarding service, sending chain, orphan modules/entries/resources and obsolete dependencies. Schema 22 removes only the two retired Media3 tables; real playback ownership and retained data remain.
- Current manuals start at [document/README.md](../document/README.md); designs and execution evidence start at [docs/superpowers/README.md](../docs/superpowers/README.md).
- [activeContext](activeContext.md) contains current status, open work and conditional retrieval. [progress](progress.md) indexes the completed-work archive. [techContext](techContext.md) contains the technical baseline and tooling entry points. Secrets belong in local/Gradle properties, never memory records.

## User-selected Implementation References

- [Jellyfin Android TV](https://github.com/jellyfin/jellyfin-androidtv), selected 2026-10-29: TV browsing/details, DPAD focus and restoration, BACK, playback controls, subtitle/audio selection and settings.
- [Plezy](https://github.com/edde746/plezy), selected 2026-10-29: mpv/ExoPlayer/libass, subtitle/audio handling, progress synchronization and multi-server flows. Its Flutter UI and cross-platform architecture require local adaptation.
- References inform behavior; DDPlayTV retains its module boundaries, multi-source identities, TV interaction and current playback ownership.
