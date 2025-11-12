package com.xyoye.data_component.entity.media3

/**
 * Enum types backing the Media3 migration models.
 */
enum class Media3SourceType {
    STREAM,
    DOWNLOAD,
    CAST,
    PREVIEW
}

enum class Media3PlayerEngine {
    EXO_LEGACY,
    MEDIA3
}

enum class Media3ToggleCohort {
    CONTROL,
    TREATMENT,
    ROLLBACK
}

enum class Media3PlaybackState {
    INITIALIZING,
    READY,
    PLAYING,
    BUFFERING,
    PAUSED,
    COMPLETED,
    FAILED
}

enum class Media3Capability {
    PLAY,
    PAUSE,
    SEEK,
    SPEED,
    SUBTITLE,
    AUDIO_TRACK,
    BACKGROUND,
    PIP,
    CAST,
    DOWNLOAD_VALIDATE,
    SESSION_COMMAND
}

enum class MediaTrackType {
    VIDEO,
    AUDIO,
    SUBTITLE
}

enum class Media3BackgroundMode {
    AUDIO_ONLY,
    PIP,
    NOTIFICATION
}

enum class Media3TelemetryEventType {
    STARTUP,
    FIRST_FRAME,
    BUFFERING,
    ERROR,
    TOGGLE_CHANGE,
    DOWNLOAD_VALIDATION,
    CAST_TRANSFER
}

enum class Media3RolloutSource {
    REMOTE_CONFIG,
    GRADLE_FALLBACK,
    MANUAL_OVERRIDE
}

enum class CastTargetType {
    CHROMECAST,
    DLNA,
    LOCAL
}

enum class DownloadRequiredAction {
    NONE,
    REVALIDATE,
    REDOWNLOAD,
    AUDIO_ONLY_FALLBACK
}
