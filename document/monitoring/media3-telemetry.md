# Media3 Telemetry Runbook (T033)

Media3 replaces the legacy Exo telemetry stack, so every session must expose Media3-only identifiers (session id, toggle cohort, player engine, media3Version) to keep SC-001/SC-002 enforced. This document captures how telemetry flows, which dashboards to check, and which alerts/rollback steps ops must follow.

## Signal Sources

1. **Media3 Telemetry Events**
   - Emitted via `Media3TelemetryRepository` → `POST /v1/media3/telemetry`.
   - Mandatory events: `STARTUP`, `FIRST_FRAME`, `ERROR`, `CAST_TRANSFER`, `DOWNLOAD_VALIDATION`, `TOGGLE_CHANGE`.
   - Payload tags: `PlaybackSession.sessionId`, `Media3PlayerEngine`, `media3Version`, `Media3ToggleCohort`, module metadata (stream, download, cast).
   - Storage: BigQuery table `media3_sessions.telemetry_raw`.

2. **Crash / ANR Tagging**
   - `Media3CrashTagger` annotates Bugly + Firebase crashes with `playerEngine`, `media3_enabled`, and `sessionId`.
   - Queries in Grafana / Firebase Explorer filter on `playerEngine == MEDIA3` to isolate regressions.

3. **Download Validation Events**
   - `DownloadAssetCheck` results flow through telemetry to prove offline readiness.
   - Exposed in Grafana via panel `media3_download_validation`.

4. **Mux QoE Metrics**
   - The Mux Media3 SDK (see [Monitor AndroidX Media3 – Mux](https://www.mux.com/docs/guides/monitor-androidx-media3)) mirrors startup latency, rebuffer ratio, bitrate shifts, and codec fallbacks for long-term trend lines.

## Dashboards

| Dashboard | Location | Purpose | Key Panels |
|-----------|----------|---------|------------|
| `Media3 Playback KPI` | Grafana folder `Playback/Media3` | Real-time first-frame + startup latency (SC-001) plus fallback ratios | `first_frame_p95`, `% sessions > 2s`, `audio_only_fallback`, `cast_transfer_errors` |
| `Media3 Stability` | Grafana folder `Playback/Media3` | Crash/ANR trends filtered by player engine (SC-002) | `Crash rate (Bugly)`, `ANR rate`, `top exceptions`, `toggle cohort breakdown` |
| `Media3 Download Validation` | Grafana folder `Playback/Media3` | Offline validation + audio-only fallback readiness | `validate_success %`, `required_action != NONE` alerts |
| `Mux QoE Explorer` | Data Studio report `Media3 QoE` | Correlates mux startup latency, bitrate, and rebuffering with telemetry IDs | `Startup vs region`, `Rebuffer ratio`, `Codec fallback share` |

All dashboards must filter by `playerEngine` to ensure legacy Exo noise does not mask regressions.

## Alert Routing

| Alert | Threshold | Destination | SLA |
|-------|-----------|-------------|-----|
| **First Frame Degradation** | `p95(first_frame)` > 2.5s for 10 minutes OR `% sessions > 2s` ≥ 10% | PagerDuty `Playback-OnCall` (P1) + Slack `#media3-rollout` | 15 min |
| **Crash/ANR Spike** | Crash or ANR rate ≥ 0.25% for Media3 cohort | PagerDuty `Playback-OnCall` (P1) | 15 min |
| **Toggle Cohort Errors** | >50 errors tagged with `Media3ToggleSnapshot.value=true` in 30 min | Slack `#media3-rollout` (P2) | 30 min |
| **Download Validation Failures** | `requiredAction != NONE` ≥ 5% of validations | Slack `#media3-rollout` + email `downloads@ops` (P2) | 1 h |
| **Cast Transfer Failures** | >5 failed CAST_TRANSFER events per 10 minutes | Slack `#media3-rollout` | 30 min |

Alert webhooks originate from Grafana and Firebase; keep routing keys aligned with PagerDuty service `playback-media3`.

## On-Call Playbook

1. **Acknowledge Alert**
   - Confirm Grafana panel or Firebase alert details and note the cohort (control/treatment/rollback).

2. **Triage**
   - Check `Media3TelemetryRepository` logs in Cloud Logging filtered by `eventType` and module.
   - Compare with Mux QoE panels to see whether degradation is network-driven or player-specific.
   - Inspect Bugly crash tags for session IDs and toggle values.

3. **Mitigation Options**
   - **Ramp Down**: Flip Firebase Remote Config `media3_enabled` to `false` for affected cohorts (leave `gradle.properties` fallback untouched). Use staged rollout if only certain regions are impacted.
   - **Force Rollback**: If crash rate >0.4% or first frame p95 >3s for >30 min, set `media3_enabled=false` globally, then redeploy after verifying fix.
   - **Scoped Disable**: Add `Media3ToggleProvider` overrides via `AppConfig` for QA builds to reproduce issues.
   - **Download Fallback**: Instruct storage/local components to enforce audio-only flows by honoring `CodecFallbackHandler` telemetry.

4. **Post-Mitigation**
   - Re-run `./gradlew testDebugUnitTest` + `connectedDebugAndroidTest`.
   - Execute `scripts/testing/media3-regression-report.sh` (T041) once implemented to confirm ≥90% Media3 coverage.
   - Update `specs/001-migrate-media3/checklists/requirements.md` with incident summary, root cause, and action items.

## Rollback Procedure

1. Pause any active rollout (Remote Config percentage back to last good cohort).
2. Set `media3_enabled=false` default and publish update.
3. Notify `#media3-rollout` and `Playback-OnCall` email with incident ID.
4. Trigger `./gradlew clean build` on CI to ensure legacy path still compiles (should run in <15 minutes).
5. Once telemetry stabilizes (<0.2% crash, first-frame p95 <2s), plan fix + rehearse re-enable with canary cohort.

## Verification Checklist

- [ ] Grafana dashboards reflect Media3-only metrics with up-to-date panels.
- [ ] Alert rules above exist and route to PagerDuty + Slack.
- [ ] Rollback steps validated in staging (toggle flip observed via `Media3ToggleProvider` logs).
- [ ] Ops knows where to find `Media3TelemetryRepository` logs and Mux QoE drill-downs.
