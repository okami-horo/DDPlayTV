# Quickstart — Media3 Playback Migration

## Prerequisites
1. Update `gradle.properties` / module build files:
   ```kotlin
   val media3 = "1.8.0"
   implementation("androidx.media3:media3-exoplayer:$media3")
   implementation("androidx.media3:media3-session:$media3")
   implementation("androidx.media3:media3-ui:$media3")
   implementation("androidx.media3:media3-exoplayer-hls:$media3")
   implementation("androidx.media3:media3-exoplayer-dash:$media3")
   implementation("androidx.media3:media3-cast:$media3")
   implementation("androidx.media3:media3-exoplayer-workmanager:$media3")
   implementation("androidx.media3:media3-datasource-okhttp:$media3")
   testImplementation("androidx.media3:media3-test-utils:$media3")
   ```
2. Confirm `compileSdk >= 32`, `minSdk >= 21`, AGP ≥ 7.1, and run `./gradlew clean build` once to warm caches.
3. Add a Remote Config (or local flag) key named `media3_enabled` with default `false`.

## Dependency Audit — 2025-11-10
- Ran `./gradlew dependencyUpdates` after bumping AGP to 7.4.2 / Kotlin 1.7.22 to confirm Media3 prerequisites.
- Deferred upgrades to AndroidX (appcompat 1.7.1, core-ktx 1.17.0, Room 2.8.3, etc.) until after the Media3 rollout to avoid widening the regression surface; they remain tracked in the HTML report.
- Held Retrofit 2.9.0 and Moshi 1.14.0 for now because Retrofit 3.x is a breaking change; revisit during telemetry hardening.
- ExoPlayer 2.19.1 is already on the latest milestone and will be replaced by Media3 dependencies in Phase 2.

## Implementation Steps
1. **Wrap the player in `player_component`**  
   - Replace the legacy Exo wrapper with `ExoPlayer.Builder(context)` inside a `Media3PlayerDelegate`.  
   - Expose the delegate via ARouter so `stream_component`, `download_component`, and `local_component` never depend on Media3 classes directly.

2. **Evaluate the rollout flag once per session**  
   - Fetch `media3_enabled` from Remote Config; if network-unavailable, read `gradle.properties`.  
   - Persist the decision as a `RolloutToggleSnapshot` and pin it to the `PlaybackSession`.

3. **Create sessions via the capability API**  
   - Call `POST /v1/media3/sessions` (see `contracts/media3-player.yaml`) with `mediaId` + `sourceType`.  
   - Cache the returned `PlayerCapabilityContract` and forward allowed commands to UI widgets.

4. **Bind a `MediaSession`**  
   - Attach the new player to `MediaSession.Builder`, expose notification + PiP controls, and implement custom commands (e.g., `SWITCH_PLAYER_ENGINE`).  
   - Ensure background playback, widgets, and casting read from the shared `PlaybackSession` state.

5. **Instrument telemetry**  
   - Hook Analytics/Telemetry collectors (Mux or in-house) by registering listeners on the Media3 player.  
   - Send `POST /v1/media3/telemetry` events for `STARTUP`, `FIRST_FRAME`, errors, toggle changes, and download validations.

6. **Validate offline downloads**  
   - After each download completes, post to `/v1/media3/downloads/validate`.  
   - Block the UI Play action unless `requiredAction == NONE`; prompt re-download or audio-only fallback otherwise.

7. **Testing checklist**  
   - `./gradlew testDebugUnitTest` for capability adapters, toggle mappers, telemetry serializers.  
   - `./gradlew connectedDebugAndroidTest` for playback, casting, notification controls, and download resume.  
   - `./gradlew lint` before pushing.

## Verification Commands
Run the full gate before flipping `media3_enabled` for a larger cohort:
- `./gradlew clean build`
- `./gradlew lint`
- `./gradlew testDebugUnitTest`
- `./gradlew connectedDebugAndroidTest`

## Rollout Instructions
1. Keep `media3_enabled` defaulted to `false` in Remote Config; ramp cohorts (internal, 5%, 25%, 50%, 100%) only after the verification commands pass and telemetry dashboards stay green for ≥24h.
2. Use the Gradle fallback flag (`media3_enabled=true` in `gradle.properties`) for local validation or emergency rollback testing—the runtime snapshot in `AppConfig` persists the evaluated value per session.
3. Document cohort flips and crash/ANR deltas in `specs/001-migrate-media3/checklists/requirements.md`, noting any blockers that require pausing the rollout.

## Rollback / Dual-Run
- Keep the legacy Exo wrapper behind the same interface until telemetry shows Media3 stability.  
- Rolling back equals flipping `media3_enabled` to `false`, restarting only new sessions while in-flight sessions finish uninterrupted.
