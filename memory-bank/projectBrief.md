 # Project Brief

 ## Project
 DDPlayTV is a modular Android TV media app built with Kotlin, XML views, MVVM, ARouter, and Gradle multi-module structure.

 ## Core Goals
 - Maintain a TV-only media experience controlled by DPAD/remote. The user retired mobile support and authorized migration on 2026-10-29; both launcher categories and shared controls now use TV defaults. Migration verification is tracked in `docs/superpowers/plans/2026-10-29-tv-only.md`.
 - Compose feature modules through `:app` while keeping reusable foundations in `core_*` modules.
 - Keep feature modules independent; cross-feature collaboration goes through `:core_contract_component` contracts/routing.
 - Support local media, storage/casting, player UI/capabilities, anime search, and user/settings. Bilibili integration is retired; PiliPlus owns that functionality (user decision, 2026-10-28).
 - Planned media-library metadata uses TMDB for movies, TV series and anime (user decision, 2026-10-30); implementation remains pending. Scope and UI linkage: `document/media-library/tmdb-metadata-followup.md`.
 - Refactor accumulated feature additions to reduce unclear ownership and maintenance cost while preserving existing functionality and TV behavior (user clarified this priority on 2026-10-27). Judge new layers by the old responsibilities they replace, using comparable source projects as references rather than templates to copy.

 ## Scope
 Main build modules are defined in `settings.gradle.kts`: `:app`, feature modules, core modules, data module, and bundled `repository:*` AAR wrappers.

 Current maintained documentation starts at `document/README.md`. Workflow design/execution ledgers, historical decisions and agent memory have distinct responsibilities described there. Former OpenSpec records were consolidated by topic; preserved validation gaps live in `document/player/playback-validation-followup.md`.

 ## Implementation References

 - [Jellyfin Android TV](https://github.com/jellyfin/jellyfin-androidtv) is an important implementation reference selected by the user on 2026-10-29.
 - Intended reference areas include TV media browsing/details, DPAD navigation and focus restoration, back behavior, playback controls, subtitle/audio selection, and settings.
 - [Plezy](https://github.com/edde746/plezy) is a secondary implementation reference selected by the user on 2026-10-29. Intended reference areas include mpv/ExoPlayer/libass integration, subtitle/audio handling, playback progress synchronization, and multi-server client flows. Its Flutter UI and cross-platform architecture require local adaptation.
 - Adapt relevant behavior and implementation to DDPlayTV's module ownership, multi-source media, danmaku/subtitles, and Media3/mpv setup. Assess upstream source and compatibility for each concrete change.

 ## Non-negotiables
 - TV/DPAD UX first: focus must be reachable, visible, restorable, and returnable.
 - Preserve module dependency layering; run `./gradlew verifyModuleDependencies` when dependency edges change.
 - Do not restore Bilibili video/live/library/danmaku integrations; local danmaku parsing remains supported.
 - VLC is retired on all devices (user decision, 2026-10-29). Maintain Media3 (default) and mpv only; old player values follow the compatibility rules in `document/player/player-kernels.md`.
 - Never hard-code secrets; use local/Gradle properties.
 - Do not revert user changes without explicit approval.

 ## Verification Baseline
 - Use Gradle from repo root.
 - Always inspect Gradle output tail and report explicit `BUILD SUCCESSFUL` or `BUILD FAILED`.
 - For TV UI changes, prefer emulator/ADB DPAD smoke verification when feasible.
