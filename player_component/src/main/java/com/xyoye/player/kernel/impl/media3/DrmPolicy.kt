package com.xyoye.player.kernel.impl.media3

import androidx.media3.common.util.UnstableApi

/**
 * 简易 DRM 策略：指示是否允许在 secure 解码器缺失时降级到 non-secure。
 * 默认允许降级，调用方可在内容要求严格时关闭。
 */
@UnstableApi
object DrmPolicy {
    /** 是否允许 secure decoder 缺失时降级到 non-secure。 */
    var allowInsecureFallback: Boolean = true

    /** 内容层可根据业务设置是否强制 secure。 */
    fun setRequireSecure(required: Boolean) {
        allowInsecureFallback = !required
    }
}
