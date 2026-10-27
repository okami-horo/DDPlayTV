package com.xyoye.common_component.playback

import com.xyoye.common_component.playback.handoff.PlaybackPreparationFailure
import com.xyoye.data_component.media3.entity.DownloadRequiredAction

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
        is PlaybackPreparationFailure.OfflineValidationBlocked ->
            when (reason) {
                DownloadRequiredAction.REDOWNLOAD -> "请重新下载当前资源后再播放"
                DownloadRequiredAction.REVALIDATE -> "当前下载需要重新验证，请联网后重试"
                else -> "当前下载资源不可播放，请检查下载状态"
            }
        is PlaybackPreparationFailure.InteractiveVerificationRequired -> "当前资源需要验证"
        is PlaybackPreparationFailure.Unknown -> "准备播放失败，请重试"
    }
