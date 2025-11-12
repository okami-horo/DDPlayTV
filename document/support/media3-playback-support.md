# Media3 Playback Support Runbook

This guide helps support engineers triage Media3 playback tickets, capture telemetry, and escalate issues with the right cohort data so we can sustain the 30% ticket reduction KPI (SC-004).

## Intake Checklist
1. **Confirm Cohort** – Ask the user to open *Settings → Playback* and share the `Media3 Enabled` toggle plus app version. If possible, have them tap “Copy Session ID” in the playback overlay.
2. **Gather Diagnostics**
   - Device + OS version
   - Network type (Wi-Fi, LTE, proxy/VPN)
   - Content identifier (anime/episode or download id)
   - Timestamp of the failure
3. **Identify Symptom Category**
   - Startup delay (>2s first frame)
   - Buffering / stutter
   - Codec fallback (audio-only, missing subtitles)
   - Cast / background / PiP issues
   - Offline download validation

## Quick Fix Playbook
| Symptom | checklist |
| --- | --- |
| **Audio-only fallback** | Confirm `CodecFallbackHandler` surfaced the message in-app. If not, ask user to capture a screen recording and attach telemetry logs (see below). |
| **Playback fails immediately** | Toggle `Media3 Enabled` off (Remote Config) for the impacted cohort via `AppConfig` debug menu, then retry. Document the rollout snapshot in the ticket. |
| **Cast failures** | Collect the `targetId` shown in the toast, confirm the Chromecast firmware version, and rerun with the cast device rebooted. |
| **Download validation blocked** | Run `scripts/testing/media3-regression-report.sh --reports <path>` locally with the user’s logs, and ensure the download asset was revalidated in the last 7 days. |

## Telemetry & Crash Capture
1. Ask the user to shake the device (or tap `Feedback → Upload Logs`). This uploads telemetry tagged with `sessionId`, `playerEngine`, `toggleCohort`, and `media3Version`.
2. For crashes, instruct users to enable crash report uploads (`Settings → Privacy → Crash Reports`) and reproduce once more so Bugly/Firebase receives the Media3 tags (`playerEngine=MEDIA3`, `snapshotId=...`).
3. When available, attach the output of `scripts/testing/media3-regression-report.sh` to the Zendesk ticket so engineering can see if ≥90% of `@Media3Dependent` suites already exercised the failing area.

## Ticket Tagging
- **Tags:** `media3`, `media3-codec-fallback`, `media3-download`, `media3-cast`, `media3-background`, `media3-rollback`.
- **Custom Fields:** `media3_session_id`, `media3_toggle_snapshot`, `media3_version`, `media3_required_action`.
- For duplicate issues, link to the existing Jira issue referenced in the release notes (`document/release-notes/media3-migration.md`).

## Escalation Matrix
1. **Playback On-Call** (`#media3-rollout`, PagerDuty `Playback-OnCall`) – startup regressions, codec fallback bugs, cast transfer failures.
2. **Storage Team** (`#downloads`) – download validation or offline resume issues.
3. **Data Platform** (`#data-platform`) – telemetry gaps or dashboard outages blocking KPI verification.

When escalating, always include:
- Session ID and toggle snapshot
- Exact app build + git SHA
- Output snippet from `scripts/ci/verify-media3.sh` or `scripts/testing/media3-regression-report.sh` if already re-run internally

## FAQ Snippets
- **“Why is my video audio-only?”** – Media3 detected unsupported codecs or DRM on this device. Audio continues by design; try another video quality, or switch to a device with hardware decode support.
- **“Can I revert to the old player?”** – The legacy Exo engine has been removed. Toggling `Media3 Enabled` off only stops new sessions temporarily for troubleshooting.
- **“Casting starts but controls freeze.”** – Ensure the cast device firmware is up to date. If the problem persists, capture the cast target ID and attach logs referencing `CAST_TRANSFER` telemetry events.
