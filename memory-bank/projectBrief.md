# Project Brief

## Product and Scope

DDPlayTV is a modular Android TV media app using Kotlin, XML views, MVVM, ARouter and Gradle. TV/DPAD is the sole maintained UI target (user decision, 2026-10-29), including Android boxes without standard television UI mode. Focus must remain visible, reachable and returnable.

- Retained capabilities: local/network storage, file browsing, casting, media-library metadata, anime information, playback history, Media3/mpv, Dandanplay/local danmaku playback, subtitles and ordinary playback cache.
- TMDB is the selected unified metadata provider for movies, TV series and anime. Dandanplay danmaku remains separate. Playback-triggered recognition and same-directory series discovery cover all nine retained library types, without mandatory folder types or manual title/episode matching; current verification is tracked in the [all-source ledger](../docs/superpowers/plans/2026-11-01-all-source-auto-metadata.md). Current usage and limits: [metadata follow-up](../document/media-library/tmdb-metadata-followup.md).
- Retired by user decision: Dandanplay PC media-library integration and inaccessible danmaku sending (2026-11-01), Bilibili integration (standalone PiliPlus owns that workflow), VLC, Anime4K, mobile UI, manual URL/header playback, external video VIEW reception, torrent/magnet playback/search and Thunder. Older repository descriptions do not change this scope. PC legacy rows remain stored for compatibility, with no active source/player entry.
- Media3 is the default player; mpv is the other maintained kernel. Compatibility and old-setting behavior: [player kernels](../document/player/player-kernels.md).
- Preserve real source/file/history identities, existing manual metadata bindings, playback progress and settings persistence. Removing manual matching UI must not silently discard legacy data. Reduce unclear ownership while retaining supported behavior.
- Retired standalone anime discovery (2026-11-01): the unreachable old homepage, weekly/seasonal browsing and anime-name search pages are removed. Current media-library search, anime details/collections and danmaku retrieval remain; existing anime search-history rows are preserved. [Cleanup evidence](../docs/superpowers/plans/2026-11-01-legacy-anime-discovery-cleanup.md).

## Selected Visual Authority

| Surface | Authoritative reference |
| --- | --- |
| Main Cinema experience | [index.html, cinema](../document/prototypes/tv-ui/index.html?variant=cinema) |
| Playback controls A | [grouped single row](../document/prototypes/tv-ui/player-controls-prototype.html?variant=grouped) |
| File browser C | [list and preview](../document/prototypes/tv-ui/files-prototype.html?variant=preview) |
| Series detail B (selected 2026-11-01; native pending) | [cinematic episode shelf](../document/prototypes/tv-ui/series-detail-prototype.html?variant=B), [scope and acceptance](../docs/superpowers/designs/2026-11-01-home-watch-history-design.md); supersedes the old metadata detail layout below |
| Deep pages and final seven child groups | [deep pages](../document/prototypes/tv-ui/deep-pages.html), [remaining pages](../document/prototypes/tv-ui/remaining-pages.html), [supplemental design](../docs/superpowers/designs/2026-10-31-ui-polish-design.md) |

The user's 2026-11-01 decision removes on-screen back buttons and player-panel close icons in favor of remote BACK navigation. This supersedes those controls in the prototypes; [the cleanup ledger](../docs/superpowers/plans/2026-11-01-remote-back-cleanup.md) records focus replacements and verification. Business confirmation/cancellation actions remain.

The user accepted subtitle positioning and danmaku/header overlap. Earlier UI completion claims were withdrawn because implementation structure did not match the selected prototype; page/state coverage and native screenshot comparison are part of the recorded acceptance basis. Current implementation and remaining verification are in [activeContext](activeContext.md), not in old deferral or completion statements. Agent workflow requirements live in AGENTS.md.

## Architecture and Documentation

- App/features compose lower-level core, contract and data modules; features do not directly depend on one another. Included modules are defined by settings.gradle.kts. Ownership and dependency rules: [systemPatterns](systemPatterns.md).
- The authorized [A01–A08 cleanup](../docs/superpowers/plans/2026-11-01-tv-scope-cleanup.md) removes unused Media3 scaffolding, the forwarding service, sending chain, orphan modules/entries/resources and obsolete dependencies. Schema 22 removes only the two retired Media3 tables; real playback ownership and retained data remain.
- Current manuals start at [document/README.md](../document/README.md); designs and execution evidence start at [docs/superpowers/README.md](../docs/superpowers/README.md).
- [activeContext](activeContext.md) contains current status, open work and conditional retrieval. [progress](progress.md) indexes the completed-work archive. [techContext](techContext.md) contains the technical baseline and tooling entry points. Secrets belong in local/Gradle properties, never memory records.

## User-selected Implementation References

- [Jellyfin Android TV](https://github.com/jellyfin/jellyfin-androidtv), selected 2026-10-29: TV browsing/details, DPAD focus and restoration, BACK, playback controls, subtitle/audio selection and settings.
- [Plezy](https://github.com/edde746/plezy), selected 2026-10-29: mpv/ExoPlayer/libass, subtitle/audio handling, progress synchronization and multi-server flows. Its Flutter UI and cross-platform architecture require local adaptation.
- References inform behavior; DDPlayTV retains its module boundaries, multi-source identities, TV interaction and current playback ownership.
