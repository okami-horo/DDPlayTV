 # System Patterns

 ## Architecture
 - Modular MVVM Android app.
 - `:app` is composition root and final APK shell.
 - Feature/business modules: `:anime_component`, `:local_component`, `:player_component`, `:storage_component`, `:user_component`.
 - Shared foundations: `:core_contract_component`, `:core_system_component`, `:core_log_component`, `:core_network_component`, `:core_database_component`, `:core_storage_component`, `:core_ui_component`, `:data_component`.
 - Bundled AAR wrappers live under `:repository:*`.

 ## Playback Ownership (source and ledger reconciliation: 2026-10-28)
 - Contract-only descriptors and request IDs cross feature/navigation boundaries; `PlaybackRequestServiceImpl` owns shared encrypted handoff storage/resolution access in player.
 - `ActivePlaybackSession`, retained by PlayerViewModel, owns active/candidate sources, revisions, controlled/native transitions and recovery. Source owns the transport lease; only that lease retires protocol resources. The sole Bilibili Addon implementation and its settings/risk/recovery contracts have been removed. Activity/kernel adapters borrow sources.
 - The session publishes one immutable `PlaybackStateView`; provider/platform service/UI read the same complete identity/capability revision. Platform commands carry an expected version back to the owner. The process provider only weakly references runtime ownership.
 - Local playback never requires a remote gateway-created session. Download validation retains its local compatibility fallback. `Media3SessionService` is metadata/command adaptation only; pre-existing popup no-ops and missing notification hosting do not imply background/PIP support.
 - Exact migration/verification evidence is in `docs/superpowers/plans/2026-10-27-playback-handoff-session.md`. Task 13 legacy deletion/governance is complete; three Task 14 checks for retained features remain user-owned. The original 62/65 count describes the pre-retirement scope.

 ## Dependency Rules
 - Dependencies flow from app/features to lower-level core/infra/data/contracts.
 - `core_*` modules must not depend on feature modules.
 - Feature modules must not depend on each other directly.
 - Cross-feature contracts/routing belong in `:core_contract_component`.
 - Repository AAR wrappers should be direct deps only where needed.

 ## UI Patterns
 - TV-specific structural differences may use separate activities/pages.
 - Small same-screen differences should branch at runtime with `isTelevisionUiMode()`.
 - Pure visual TV differences should use resource qualifiers/selectors.
 - TV lists, grids and dynamic menus use DpadRecyclerView 1.4.3. `TvViewInflater` replaces only XML lists marked `tvDpadList` in TV mode; mobile retains ordinary RecyclerView. Layout helpers configure the library's layout manager instead of replacing it. Both old list delegates and index/retry navigation were removed on 2026-10-28.
 - `TvCategoryPages` displays one active Fragment and explicit category navigation; mobile keeps TabLayout/ViewPager2. Browsing focus and activating a category are separate. `DpadFocusOwner` retains only the cross-module content-entry operation.
 - Page-specific stable keys and library state restore lists; storage scopes DpadStateRegistry by directory. The shell only provides sidebar/content handoff. Android window focus retains dialog callers without delayed recursive restoration. Player panel boundaries contain native/library navigation, and RemoteKeyDispatcher owns playback key semantics through one dispatch entry.
 - Migration coverage and evidence are authoritative in `docs/superpowers/plans/2026-10-28-tv-dpad-library-rewrite.md`; physical-TV acceptance remains distinct from AVD verification.

 ## Retired Integration Compatibility (2026-10-28)
 - Room 16->17 removes Bilibili media libraries and their history before removed enum values can fall back to generic streaming. Other rows and local danmaku bindings remain.
 - Startup removes dedicated Bilibili MMKV stores and legacy credential keys. Handoff decoding drops retired library requests/snapshots separately, preserving the other slot.
 - Source changes and validation are tracked in `docs/superpowers/plans/2026-10-28-remove-bilibili.md`; older Bilibili acceptance is superseded, not passed.

 ## Implementation Style
 - Surgical changes only; preserve existing style.
 - Kotlin 4-space indentation, explicit visibility, no speculative abstractions.
 - Shared helpers should live in appropriate core modules rather than duplicated in features.
