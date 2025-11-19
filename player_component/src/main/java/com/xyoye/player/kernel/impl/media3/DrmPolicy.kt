package com.xyoye.player.kernel.impl.media3

import androidx.media3.common.util.UnstableApi

/**
 * DRM 策略：指示在 secure 解码器缺失时是否允许降级到 non-secure。
 * 默认情况下开启“安全优先”，即 DRM 会话需要 secure decoder 时不允许降级；
 * 只有明确调用 {@link #setRequireSecure} 放宽限制时才会尝试非 secure。
 */
@UnstableApi
object DrmPolicy {
    /** 是否允许在 DRM 场景下回退到 non-secure。默认禁止。 */
    @Volatile
    var allowInsecureFallback: Boolean = false

    /** 内容层可根据业务设置是否强制 secure。 */
    fun setRequireSecure(required: Boolean) {
        allowInsecureFallback = !required
    }
}
