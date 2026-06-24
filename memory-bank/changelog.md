 # Changelog

## [Unreleased] - 2026-06-06
### Added
- Initialized Memory Bank core files for DDPlayTV: project brief, product context, active context, system patterns, tech context, progress, and changelog.
- Added player subtitle quick toggle design/plan and implementation in `:player_component`.
- Refreshed Memory Bank session context on 2026-06-13 after confirming all core files are present.
- Added danmu match result domain model, source metadata APIs, exact/fail match cache, and `DanmuMatchCoordinator`.
- Added unit coverage for danmu match model parsing, source metadata, hash concurrency, finder match semantics, and coordinator fuzzy-candidate behavior.

### Changed
- Committed unrelated pre-existing sequential playback completion changes separately as `0daecc89a` before continuing subtitle toggle work.
- Updated Dandanplay match calls to use full `hashAndFileName` parameters where filename metadata exists.
- Updated player auto danmu matching to download exact matches only and leave fuzzy candidates for manual binding/search UI.
- Updated manual danmu binding to consume `MatchResult` while preserving candidate display.

### Fixed
- Fixed danmu hash calculation to use per-call MD5 instances and network hash timeout handling.
- Fixed TV video controller action handling for `ToggleSubtitle` after the subtitle quick toggle action was added.
