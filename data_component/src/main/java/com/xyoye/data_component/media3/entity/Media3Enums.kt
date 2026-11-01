package com.xyoye.data_component.media3.entity

/**
 * Shared playback source, state and capability types.
 */
enum class Media3SourceType {
    STREAM,
    DOWNLOAD,
    CAST,
    PREVIEW
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

enum class Media3BackgroundMode {
    AUDIO_ONLY,
    PIP,
    NOTIFICATION
}
