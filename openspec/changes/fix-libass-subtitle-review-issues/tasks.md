## 1. Subtitle degradation and recovery wiring

- [x] 1.1 Update the shared libass fallback state flow so degraded subtitle sessions no longer imply a CPU renderer is actively rendering.
- [x] 1.2 Connect `LibassGpuSubtitleSession` and recovery coordination to retry GPU subtitle rendering on surface, seek, track, and timeline recovery boundaries.
- [x] 1.3 Adjust player-level fallback handling and logs so the UI/telemetry report degraded or recovery-pending behavior instead of “fallback ignored”.

## 2. libass vertical offset propagation

- [x] 2.1 Extend the subtitle renderer contract so libass backends can receive runtime vertical offset updates from player settings.
- [x] 2.2 Add native bridge support for libass vertical offset and apply the offset during GPU subtitle coordinate conversion.
- [x] 2.3 Ensure Media3 and mpv libass subtitle sessions apply updated vertical offset values to both embedded and external ASS/SSA rendering without restarting playback.

## 3. mpv ASS dedup state control

- [x] 3.1 Replace the unbounded mpv `ass-full` dedup cache with a bounded structure that can prune stale history during long playback.
- [x] 3.2 Preserve correct dedup behavior across seek, subtitle track changes, and other timeline resets while preventing stale state reuse.

## 4. Regression coverage and validation

- [x] 4.1 Add or update unit tests for degraded subtitle recovery behavior and recovery-oriented state reporting.
- [x] 4.2 Add or update unit tests for libass vertical offset propagation and bounded mpv ASS parser state.
- [x] 4.3 Run the focused subtitle/player verification commands needed to confirm the change is safe for Media3 and mpv subtitle flows.
