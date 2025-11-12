# Media3 Ticket Analytics Dashboard (T042)

Use this doc to keep the Zendesk/BigQuery pipeline aligned with the 30% ticket reduction KPI.

## Data Sources
| Source | Description | Refresh |
| --- | --- | --- |
| `zendesk.media3_tickets_raw` | Nightly export of all tickets tagged `media3*`. Includes `ticket_id`, `created_at`, `updated_at`, `tags`, `custom_fields`. | 24h |
| `zendesk.media3_sessions` | Join between ticket IDs and telemetry uploads keyed by `media3_session_id`. | Hourly |
| `grafana.media3_support_dashboard` | Live Data Studio dashboard embedded in Grafana for Support+Playback teams. | 15 min |

## Baseline vs Post-Migration Queries
Baseline window: `2025-10-01` → `2025-10-31` (legacy Exo).  
Post-migration window: rolling 14 days after each cohort ramp.

```sql
-- Ticket volume + cohort breakdown
WITH windowed AS (
  SELECT
    DATE(created_at) AS day,
    CASE
      WHEN 'media3' = ANY(tags) THEN 'Media3'
      WHEN 'exo' = ANY(tags) THEN 'Legacy'
      ELSE 'Unknown'
    END AS player_bucket,
    ticket_id
  FROM zendesk.media3_tickets_raw
  WHERE DATE(created_at) BETWEEN @start AND @end
)
SELECT day, player_bucket, COUNT(DISTINCT ticket_id) AS tickets
FROM windowed
GROUP BY day, player_bucket
ORDER BY day;
```

```sql
-- KPI reduction calculation
SELECT
  post.cohort,
  SAFE_DIVIDE(post.tickets, NULLIF(base.tickets, 0)) AS pct_of_baseline
FROM (
  SELECT 'Post-Media3' AS cohort, COUNT(*) AS tickets
  FROM zendesk.media3_tickets_raw
  WHERE DATE(created_at) BETWEEN @post_start AND @post_end
    AND 'media3' = ANY(tags)
) post,
(
  SELECT 'Baseline' AS cohort, COUNT(*) AS tickets
  FROM zendesk.media3_tickets_raw
  WHERE DATE(created_at) BETWEEN DATE '2025-10-01' AND DATE '2025-10-31'
    AND 'exo' = ANY(tags)
) base;
```

## Dashboard Panels
1. **Ticket Volume (Stacked by Player Engine)** – shows progress toward 30% reduction goal.
2. **Top Symptoms** – counts tickets tagged `media3-codec-fallback`, `media3-download`, etc.
3. **Response SLA** – average first response + resolution time for Media3 tickets vs overall.
4. **Escalation Heatmap** – correlation between ticket tags and Grafana alerts.

## Maintenance
- Refresh Data Studio data sources after each release.
- Ensure `scripts/testing/media3-regression-report.sh` output is attached to any ticket used as an example in the dashboard.
- When new tags are introduced (e.g., `media3-pip`), update both the baseline query and the dashboard filters.
