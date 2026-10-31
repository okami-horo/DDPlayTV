 # System Patterns

 ## Architecture
 - Modular MVVM Android app.
 - `:app` is composition root and final APK shell.
 - Feature/business modules: `:anime_component`, `:local_component`, `:player_component`, `:storage_component`, `:user_component`.
 - Shared foundations: `:core_contract_component`, `:core_system_component`, `:core_log_component`, `:core_network_component`, `:core_database_component`, `:core_storage_component`, `:core_metadata_component`, `:core_ui_component`, `:data_component`.
 - Bundled AAR wrappers live under `:repository:*`.

 ## Playback Ownership (source and ledger reconciliation: 2026-10-28)
 - Contract-only descriptors and request IDs cross feature/navigation boundaries; `PlaybackRequestServiceImpl` owns shared encrypted handoff storage/resolution access in player.
 - `ActivePlaybackSession`, retained by PlayerViewModel, owns active/candidate sources, revisions, controlled/native transitions and recovery. Source owns the transport lease; only that lease retires protocol resources. The sole Bilibili Addon implementation and its settings/risk/recovery contracts have been removed. Activity/kernel adapters borrow sources.
 - The session publishes one immutable `PlaybackStateView`; provider/platform service/UI read the same complete identity/capability revision. Platform commands carry an expected version back to the owner. The process provider only weakly references runtime ownership.
 - Local playback never requires a remote gateway-created session. Download validation retains its local compatibility fallback. `Media3SessionService` is metadata/command adaptation only; pre-existing popup no-ops and missing notification hosting do not imply background/PIP support.
 - Exact migration/verification evidence is in `docs/superpowers/plans/2026-10-27-playback-handoff-session.md`. Retained integrated acceptance remains user-owned; current open work is summarized in `memory-bank/activeContext.md`, with historical implementation stages archived via `memory-bank/progress.md`.

 - Persistence/recovery decisions retained from the 2026-10-28 handoff record: controlled transitions persist before activation, and persistence failure preserves the old source. Native transitions update runtime first; failed snapshot persistence leaves playback active but recovery uses the last committed episode. Candidates are memory-only. Configuration recreation reuses live runtime; snapshot-over-history recovery, fixed TTL and periodic/event saves remain the agreed model.

 ## Dependency Rules
 - Dependencies flow from app/features to lower-level core/infra/data/contracts.
 - `core_*` modules must not depend on feature modules.
 - Feature modules must not depend on each other directly.
 - Cross-feature contracts/routing belong in `:core_contract_component`.
 - Repository AAR wrappers should be direct deps only where needed.

 ## Metadata Ownership (2026-10-31)
 - core_metadata orchestrates parsing, matching, source-scoped scanning and manual binding revisions; local/storage/user consume it. core_storage supplies independent read-only catalog snapshots and does not depend on metadata. app/player continue using contract playback descriptors, without metadata implementation dependencies.
 - File assets, source content generations and TMDB movie/tv/season/episode identities are separate. Complete-directory results alone may mark assets unavailable; manual results and valid cached snapshots survive errors and stale requests. Maintained design/acceptance: `docs/superpowers/designs/2026-10-31-tmdb-media-library-design.md`; implementation and verified limits: `docs/superpowers/plans/2026-10-31-tmdb-media-library.md`.

 ## UI Patterns
 - TV is the sole maintained UI target (2026-10-29). Both launcher categories use TvSplashActivity/TvMainActivity; old mobile component names are aliases. Shared feature pages remain in use.
 - TV lists, grids and dynamic menus use DpadRecyclerView 1.4.3. `TvViewInflater` replaces XML lists marked `tvDpadList` regardless of system UI mode. Focus selectors are default resources; the unused `isTelevisionUiMode()` helper is removed. Actual input-mode checks remain where needed. Layout helpers configure the library's layout manager instead of replacing it.
 - `TvCategoryPages` directly binds XML DpadRecyclerView/FragmentContainerView and displays one active Fragment with explicit category navigation; phone adapters and runtime TabLayout/ViewPager2 replacement are removed. Browsing focus and activating a category are separate. `DpadFocusOwner` retains only the cross-module content-entry operation.
 - Player controls inherit TvVideoController directly; touch-only gesture/acceleration, orientation sensors and the no-op popup host are removed. Seek feedback, media rotation/aspect and session ownership remain. mpv uses SurfaceView with Plezy's `vo=mediacodec` and `hwdec=mediacodec`; native mpv renders subtitles on a sibling OSD Surface. Media3 retains the app libass pipeline and independent video Surface selection. Current behavior is maintained in `document/mpv_conf_supported_options.md` and `document/player/subtitle-rendering.md`; subtitle migration evidence is in `docs/superpowers/plans/2026-10-29-mpv-native-subtitles.md`.
 - `MpvNativeBridge` owns a serial worker per native session, posts events on the main thread, and filters retired listeners/source revisions. The upstream AAR owns native lifecycle; a Java API boundary isolates its Kotlin metadata. Surface destruction hands off to an owned placeholder; paused seekable positions are restored by exact seek on rebind. Progress/duration/track reads use observed caches. Synchronous Surface/track operations have a bounded wait, not guaranteed native cancellation.
 - mpv subtitle ownership (2026-10-29): `MpvSubtitleRendererBackend` hosts the aligned transparent OSD Surface; native owns parsing, ASS/bitmap rendering, clocks and attachment fonts. Separate video/OSD generations preserve lifecycle ownership. External subtitles still use the existing controller/content-URI cache, then await native `sub-add`; revision and track ownership prevent cancelled work from replacing a newer choice. No `ass-full` parser or second app libass instance for mpv. Native mode hides unsupported global opacity without overwriting Media3 preferences; scale/position/delay use mpv properties.
 - Page-specific stable keys and library state restore lists; storage scopes DpadStateRegistry by directory. The shell provides top-navigation/content handoff (cinema implementation, 2026-10-31). Android window focus retains dialog callers without delayed recursive restoration. Player panel boundaries contain native/library navigation, and RemoteKeyDispatcher owns playback key semantics through one dispatch entry.
 - TV-only migration evidence is maintained in `docs/superpowers/plans/2026-10-29-tv-only.md`; the earlier DPAD rewrite evidence remains in `docs/superpowers/plans/2026-10-28-tv-dpad-library-rewrite.md`. Physical-TV acceptance remains distinct from AVD verification.

 ## Retired Integration Compatibility (2026-10-28)
 - Room 16->17 removes Bilibili media libraries and their history before removed enum values can fall back to generic streaming. Other rows and local danmaku bindings remain.
 - Startup removes dedicated Bilibili MMKV stores and legacy credential keys. Handoff decoding drops retired library requests/snapshots separately, preserving the other slot.
 - Source changes and validation are tracked in `docs/superpowers/plans/2026-10-28-remove-bilibili.md`; older Bilibili acceptance is superseded, not passed.
