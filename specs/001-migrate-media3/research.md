# Phase 0 Research — Media3 Playback Migration

## Research Tasks
- **Task**: Research the Media3 modules/version required to cover streaming, downloads, casting, and telemetry entry points.  
  **Outcome**: Target Media3 `1.8.0` with `media3-exoplayer`, `media3-session`, `media3-ui`, HLS/DASH/WorkManager/Cast/data source/test artifacts per Jetpack release guidance so every module can share one capability layer.
- **Task**: Research platform prerequisites (minSdk/compileSdk/AGP) for Media3 to ensure device support.  
  **Outcome**: Confirm Media3 libraries support Android 5.0 (API 21) upward and the migration guide requires compileSdk ≥ 32 with modern AGP/Gradle before running migration tooling.
- **Task**: Research rollout toggle strategies that let legacy Exo sessions finish while new sessions launch on Media3.  
  **Outcome**: Use Remote Config (or gradle flag fallback) to gate Media3 at session creation and leverage MediaSession custom commands so cross-surface controls stay in sync even when the toggle flips.
- **Task**: Research sequencing patterns for migrating multi-module apps from Exo to Media3.  
  **Outcome**: Follow the migration guide order—update Exo modules first, then MediaSessionConnector/MediaBrowserService layers, and finally client controllers—mirroring our module boundaries.
- **Task**: Research telemetry/offline best practices for Media3 so FR-005/FR-006 can be proven.  
  **Outcome**: Adopt Media3 analytics hooks with providers such as Mux and reuse `media3-exoplayer-workmanager` for verifying downloads/background resumption.

## Media3 Dependency Stack & Version
- **Decision**: Pin `androidx.media3` artifacts to `1.8.0` and include `media3-exoplayer`, `media3-session`, `media3-ui`, `media3-exoplayer-hls`, `media3-exoplayer-dash`, `media3-cast`, `media3-exoplayer-workmanager`, `media3-datasource-okhttp`, `media3-common`, and `media3-test-utils(-robolectric)` in `player_component` plus test modules.  
- **Rationale**: The Jetpack release notes enumerate the canonical modules required for playback surfaces, network stacks, casting, background tasks, and testing so we can cover streaming, downloads, PiP, and instrumentation uniformly. The 1.8.0 release (Aug 2025) is the latest stable Media3 drop with transformer/analytics fixes, minimizing migration debt versus targeting older 1.6.x builds.  
- **Alternatives considered**:  
  - Stay on legacy `com.google.android.exoplayer2` 2.19.x: rejected because Google has discontinued it and spec explicitly calls for Media3.  
  - Use Media3 `1.6.x`: rejected since 1.8.x contains the most recent security/bug fixes for Transformer and frame extraction that we need for parity.  
- **Sources**: Jetpack Media3 release documentation (module list) [1]; Media3 1.8.0 release announcement [2].

## Platform Prerequisites
- **Decision**: Keep minSdk at ≥21 (Lollipop) and raise/confirm compileSdk + targetSdk ≥32 with AGP ≥7.1/Gradle ≥7.4 before wiring Media3.  
- **Rationale**: Media3 libraries—including Transformer and Exo replacements—officially support Android 5.0+; anything lower loses upstream fixes. The migration guide requires compileSdk ≥32 and modern Gradle/AGP before running the media3 migration script, so validating those versions early prevents build breaks in our CI matrix.  
- **Alternatives considered**:  
  - Supporting API <21 by backporting: rejected because Media3 artifacts themselves require API 21+; forcing it would fork libraries.  
  - Deferring compileSdk alignment: rejected because migration tooling halts when compileSdk/AGP versions lag, blocking the plan’s Gate 2 timeline.  
- **Sources**: Media3 Transformer announcement citing API 21+ support [3]; Media3 migration guide prerequisites for compileSdk/AGP [4].

## Rollout Toggle & Mid-Session Strategy
- **Decision**: Introduce a `Media3Enabled` feature flag pulled from Remote Config (with `gradle.properties` fallback) that is evaluated when a playback session is created; active sessions keep their original player instance until completion, while new sessions honor the latest flag. Use `MediaSession` custom commands to broadcast the active engine so widgets/casting/background surfaces stay coherent.  
- **Rationale**: Remote Config is purpose-built for runtime feature toggles and can instantly flip Media3 globally; falling back to a gradle flag keeps local/dev control. MediaSession already brokers playback commands across headphones, Assistant, and controllers, and its custom commands let us surface which engine is active without tearing down ongoing playback, matching the spec’s mid-session safety requirement.  
- **Alternatives considered**:  
  - App restart requirement after toggle: rejected because spec demands seamless rollout and on-call needs instant rollback capability.  
  - Per-module toggles: rejected since fragmentation would break the unified capability contract and create inconsistent telemetry.  
- **Sources**: Firebase Remote Config usage for runtime feature flags [5]; Media3 MediaSession command handling guidance [6]; Media3 custom command pattern [7].

## Migration Sequencing Across Modules
- **Decision**: Sequence migration as (1) `player_component` core Exo wrappers → Media3 Player + capability interface, (2) replace `MediaSessionConnector`/`PlayerNotificationManager` usage in `app` shell with `media3-session`, (3) update feature modules (`stream_component`, `download_component`, `local_component`, casting entry points) to the unified contract, (4) refresh telemetry/data entities, (5) clean up legacy Exo-only APIs.  
- **Rationale**: The migration guide outlines the order—start with ExoPlayer packages, then MediaSessionConnector/MediaBrowser services, and finally MediaBrowserCompat clients—so applying the same progression across our modules limits breakage and keeps ARouter/service contracts stable.  
- **Alternatives considered**:  
  - Flip feature modules first: rejected because the shared capability interface would still depend on Exo wrappers, forcing dual maintenance longer.  
  - Big-bang change across all modules: rejected due to high regression risk without staged verification.  
- **Source**: AndroidX Media3 migration guide covering ExoPlayer, MediaSessionConnector, and MediaBrowserCompat transitions [4].

## Telemetry & Monitoring
- **Decision**: Instrument Media3 sessions with analytics hooks (e.g., Mux or existing telemetry service) by wrapping the new `ExoPlayer` instance via providers such as `monitorWithMuxData`, tagging each session with Media3 identifiers plus module metadata.  
- **Rationale**: Third-party telemetry SDKs already expose Media3 integrations that attach analytics listeners directly to the player, ensuring FR-005 metrics (player type/version/error codes) are emitted automatically without reinventing instrumentation.  
- **Alternatives considered**:  
  - Polling legacy Exo-only metrics: rejected because they disappear once Media3 is enabled, leaving ops blind.  
  - Building a custom analytics collector from scratch: slower and riskier than using proven hooks.  
- **Source**: Mux “Monitor AndroidX Media3” guide demonstrating how to wrap a Media3 player and tag sessions for analytics [8].

## Offline Downloads & Background Resumption
- **Decision**: Use `media3-exoplayer-workmanager` plus `media3-extractor` to validate downloaded assets before surfacing the Play action and to resume downloads/background playback with the same pipeline.  
- **Rationale**: Jetpack’s dependency guidance highlights the WorkManager integration for scheduling background operations with ExoPlayer/Media3, and extractor modules ensure file compatibility checks happen through the same codepath as streaming playback, satisfying FR-006.  
- **Alternatives considered**:  
  - Retain legacy download validation: rejected because it still depends on Exo-specific classes slated for removal.  
- **Source**: Jetpack Media3 release documentation covering `media3-exoplayer-workmanager` and extractor modules for validation [1].

---

**Source Index**
1. [Jetpack Media3 release documentation](https://developer.android.com/jetpack/androidx/releases/media3)  
2. [“Media3 1.8.0 — what’s new?”](https://android-developers.googleblog.com/2025/08/media3-180-whats-new.html)  
3. [“Media transcoding and editing, transform and roll out!”](https://android-developers.googleblog.com/2023/05/media-transcoding-and-editing-transform-and-roll-out.html)  
4. [AndroidX Media3 migration guide](https://developer.android.com/media/media3/exoplayer/migration-guide)  
5. [Firebase Remote Config – Get started](https://firebase.google.com/docs/remote-config/get-started)  
6. [“Control and advertise playback using a MediaSession”](https://developer.android.com/media/media3/session/control-playback)  
7. [“Media3 is ready to play!”](https://android-developers.googleblog.com/2023/03/media3-is-ready-to-play.html)  
8. [Mux – Monitor AndroidX Media3](https://www.mux.com/docs/guides/monitor-androidx-media3)
