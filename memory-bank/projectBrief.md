# Project Brief

## Product and Scope

DDPlayTV is a modular Android TV media app using Kotlin, XML views, MVVM, ARouter and Gradle. TV/DPAD is the sole maintained UI target (user decision, 2026-10-29), including Android boxes without standard television UI mode. Focus must remain visible, reachable and returnable.

- Retained capabilities: local/network storage, file browsing, casting, media-library metadata, anime information, playback history, Media3/mpv, Dandanplay/local danmaku, subtitles and ordinary playback cache.
- TMDB is the selected unified metadata provider for movies, TV series and anime. Dandanplay danmaku remains separate. Scope: [metadata follow-up](../document/media-library/tmdb-metadata-followup.md).
- Retired by user decision: Bilibili integration (standalone PiliPlus owns that workflow), VLC, Anime4K, mobile UI, manual URL/header playback, external video VIEW reception, torrent/magnet playback/search and Thunder. Older repository descriptions do not change this scope.
- Media3 is the default player; mpv is the other maintained kernel. Compatibility and old-setting behavior: [player kernels](../document/player/player-kernels.md).
- Preserve real source/file/history identities, manual metadata bindings, playback progress and settings persistence. Reduce unclear ownership while retaining supported behavior.

## Selected Visual Authority

| Surface | Authoritative reference |
| --- | --- |
| Main Cinema experience | [index.html, cinema](../document/prototypes/tv-ui/index.html?variant=cinema) |
| Playback controls A | [grouped single row](../document/prototypes/tv-ui/player-controls-prototype.html?variant=grouped) |
| File browser C | [list and preview](../document/prototypes/tv-ui/files-prototype.html?variant=preview) |
| Deep pages and final seven child groups | [deep pages](../document/prototypes/tv-ui/deep-pages.html), [remaining pages](../document/prototypes/tv-ui/remaining-pages.html), [supplemental design](../docs/superpowers/designs/2026-10-31-ui-polish-design.md) |

The user accepted subtitle positioning and danmaku/header overlap. Earlier UI completion claims were withdrawn because implementation structure did not match the selected prototype; page/state coverage and native screenshot comparison are part of the recorded acceptance basis. Current implementation and remaining verification are in [activeContext](activeContext.md), not in old deferral or completion statements. Agent workflow requirements live in AGENTS.md.

## Architecture and Documentation

- App/features compose lower-level core, contract and data modules; features do not directly depend on one another. Included modules are defined by settings.gradle.kts. Ownership and dependency rules: [systemPatterns](systemPatterns.md).
- Current manuals start at [document/README.md](../document/README.md); designs and execution evidence start at [docs/superpowers/README.md](../docs/superpowers/README.md).
- [activeContext](activeContext.md) contains current status, open work and conditional retrieval. [progress](progress.md) indexes the completed-work archive. [techContext](techContext.md) contains the technical baseline and tooling entry points. Secrets belong in local/Gradle properties, never memory records.

## User-selected Implementation References

- [Jellyfin Android TV](https://github.com/jellyfin/jellyfin-androidtv), selected 2026-10-29: TV browsing/details, DPAD focus and restoration, BACK, playback controls, subtitle/audio selection and settings.
- [Plezy](https://github.com/edde746/plezy), selected 2026-10-29: mpv/ExoPlayer/libass, subtitle/audio handling, progress synchronization and multi-server flows. Its Flutter UI and cross-platform architecture require local adaptation.
- References inform behavior; DDPlayTV retains its module boundaries, multi-source identities, TV interaction and current playback ownership.
