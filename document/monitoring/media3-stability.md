# Media3 Stability Dashboards (T035)

This guide explains how crash + telemetry dashboards are generated and kept in sync with Media3 rollout requirements (SC-001 through SC-004). It focuses on the automation that feeds Grafana/Data Studio plus the staffing expectations for on-call.

## Data Pipeline

1. **Telemetry Sink**
   - `Media3TelemetryRepository` posts JSON payloads to `/v1/media3/telemetry`.
   - Cloud Run ingests the payloads and writes them to BigQuery dataset `media3_sessions.telemetry_raw`.
   - A scheduled query (every 5 minutes) materializes the view `telemetry_kpis` with columns:
     - `session_id`, `event_type`, `module`, `first_frame_ms`, `toggle_cohort`, `player_engine`, `error_code`, `media3_version`.
2. **Crash + ANR Feed**
   - `Media3CrashTagger` attaches `playerEngine`, `toggleCohort`, `sessionId`, and `media3Version` to Bugly + Firebase events.
   - A BigQuery transfer job imports Bugly export CSVs hourly into `media3_sessions.crashes`.
3. **Mux QoE Metrics**
   - Mux Data SDK for Media3 (per [Mux guide](https://www.mux.com/docs/guides/monitor-androidx-media3)) streams engagement/perf metrics into `mux.media3_qoe`.
   - Connect this table to Data Studio via the native BigQuery connector.

## Dashboard Automation

| Target | Tool | Data | Refresh | Notes |
|--------|------|------|---------|-------|
| `Media3 Playback KPI` | Grafana (BigQuery datasource) | `telemetry_kpis` | 1 min | Panels for first-frame %, startup p95, codec fallback counts. |
| `Media3 Stability` | Grafana | `crashes`, `telemetry_kpis` | 1 min | Crash/ANR per cohort, error heat map, cast/download validation failures. |
| `Media3 QoE Explorer` | Data Studio | `mux.media3_qoe` | 15 min | Correlates mux metrics with toggle cohorts and player version. |
| `Download Validation` | Grafana | `telemetry_kpis` filtered on `event_type=DOWNLOAD_VALIDATION` | 1 min | Highlights `requiredAction != NONE`. |

Automation steps:

1. Terraform module (`infra/monitoring/media3_dashboards.tf` in the ops repo) provisions Grafana folders/panels. Re-run `terraform apply -var=env=prod` after editing.
2. CI cron job (`scripts/monitoring/sync_dashboards.sh` in ops) exports Grafana JSON, validates via `grafana-toolkit lint`, and alerts the platform team if drift is detected.
3. Data Studio report uses `media3_stability_template` as baseline; run the “Copy + Publish” automation once per release via `scripts/monitoring/publish_datastudio.sh`.

## Alert Thresholds (mirrors `media3-telemetry.md`)

| Metric | Warning | Critical | Action |
|--------|---------|----------|--------|
| First-frame p95 | >2.2s (5 min) | >2.5s (10 min) | Page on-call, evaluate network vs player. |
| Crash rate | >0.18% (15 min) | ≥0.25% (15 min) | Trigger rollback procedure. |
| ANR rate | >0.18% (15 min) | ≥0.25% (15 min) | Trigger rollback procedure. |
| Audio-only fallback | >8% of sessions | >12% of sessions | Audit `CodecFallbackHandler` inputs; ensure assets not regressing. |
| Download validation failures | >3% | >5% | Pause offline rollout, investigate asset pipeline. |

Thresholds are encoded directly inside Grafana alert rules (`media3_playback_alerts.jsonnet`). Keep warning < critical to give ops lead time.

## On-Call Rotation

| Role | Coverage | Channel | Escalation |
|------|----------|---------|------------|
| Playback Primary | 24/7 weekly rotation | PagerDuty `Playback-OnCall`, Slack `#media3-rollout` | escalate to Playback TL after 30 min unresolved |
| Playback Secondary | Mirrors primary | PagerDuty secondary schedule | assist with rollback + data pulls |
| Data Platform Support | Business hours | Slack `#data-platform` | fix BigQuery transfers or Grafana datasource issues |

Primary owns acknowledging alerts, triggering rollback, and updating `support/runbooks`.

## Maintenance Checklist

- [ ] Verify BigQuery scheduled queries succeeded (look for “OK” in Cloud Scheduler).
- [ ] Run `scripts/monitoring/sync_dashboards.sh --verify` weekly; fix drift before release.
- [ ] Re-record thresholds in `specs/001-migrate-media3/checklists/requirements.md` after each release.
- [ ] Ensure Data Studio report uses current release tag (displayed in report header).
- [ ] Confirm PagerDuty schedules match current on-call roster after team changes.
