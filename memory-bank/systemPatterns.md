 # System Patterns

 ## Architecture
 - Modular MVVM Android app.
 - `:app` is composition root and final APK shell.
 - Feature/business modules: `:anime_component`, `:local_component`, `:player_component`, `:storage_component`, `:user_component`.
 - Infra integration: `:bilibili_component`.
 - Shared foundations: `:core_contract_component`, `:core_system_component`, `:core_log_component`, `:core_network_component`, `:core_database_component`, `:core_storage_component`, `:core_ui_component`, `:data_component`.
 - Bundled AAR wrappers live under `:repository:*`.

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
 - RecyclerView/grid focus should use shared focus helpers when available, including `RecyclerViewFocusDelegate`, `DpadRecyclerFocusDelegate`, `FocusPolicy`, and `FocusTarget` conventions.
 - TV DPAD focus ownership should be explicit for migrated screens via `DpadFocusOwner`; shell/page/dialog/overlay transitions should save focus, restore focus, and provide a default fallback instead of relying on Android default focus search.
 - Dialog focus restore should be centralized through `BaseBottomDialog`/`DpadDialogFocusController` where possible, with TV/non-touch checks to avoid changing mobile behavior.

 ## Bilibili Pattern
 - Before changing Bilibili media/library logic, inspect `E:\Project\PiliPlus\lib` for relevant repository/service/provider/controller behavior.
 - Map concepts into `:bilibili_component` and calling feature modules without copying incompatible Flutter/Dart UI structure.
 - Prefer borrowing flow semantics, field meaning, error handling, pagination, and API composition.

 ## Implementation Style
 - Surgical changes only; preserve existing style.
 - Kotlin 4-space indentation, explicit visibility, no speculative abstractions.
 - Shared helpers should live in appropriate core modules rather than duplicated in features.
