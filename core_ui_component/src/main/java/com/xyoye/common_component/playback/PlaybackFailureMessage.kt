package com.xyoye.common_component.playback

import com.xyoye.common_component.playback.handoff.PlaybackPreparationFailure

fun PlaybackPreparationFailure.userMessage(): String =
    when (this) {
        PlaybackPreparationFailure.RequestExpired -> "播放请求已过期，请重新选择视频"
        PlaybackPreparationFailure.RequestMissing -> "播放请求不存在，请重新选择视频"
        PlaybackPreparationFailure.RequestSuperseded -> "播放请求已被替换"
        PlaybackPreparationFailure.RecoveryStatePersistenceFailed -> "无法保存播放恢复信息，请重试"
        PlaybackPreparationFailure.SensitivePayloadUnavailable -> "播放恢复信息不可用，请重新选择视频"
        PlaybackPreparationFailure.SessionSnapshotMissing -> "播放会话已失效，请重新选择视频"
        PlaybackPreparationFailure.LibraryMissing -> "媒体库不存在"
        PlaybackPreparationFailure.FileMissing -> "播放资源不存在"
        PlaybackPreparationFailure.PermissionDenied -> "没有访问播放资源的权限"
        PlaybackPreparationFailure.AuthenticationRequired, PlaybackPreparationFailure.CredentialUnavailable -> "请重新登录媒体库"
        PlaybackPreparationFailure.NetworkUnavailable -> "网络连接失败，请重试"
        PlaybackPreparationFailure.UnsupportedSource, PlaybackPreparationFailure.InvalidDescriptor -> "不支持的播放资源"
        is PlaybackPreparationFailure.Unknown -> "准备播放失败，请重试"
    }
