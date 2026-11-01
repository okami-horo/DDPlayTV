package com.xyoye.common_component.playback.handoff

/** Playback identity uses opaque IDs; a failed launch may return its user-facing message. */
object PlaybackNavigation {
    const val REQUEST_ID = "playback_request_id"
    const val SESSION_ID = "playback_session_id"
    const val FAILURE_MESSAGE = "playback_failure_message"
}
