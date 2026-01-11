package com.xyoye.common_component.bilibili.risk

import com.tencent.mmkv.MMKV
import com.xyoye.common_component.extension.toMd5String

/**
 * Bilibili 风控相关状态缓存（按媒体库 storageKey 隔离）。
 *
 * 目标：
 * - 避免每次请求都触发预热/激活
 * - 在风控命中后可强制刷新
 */
class BilibiliRiskStateStore(
    storageKey: String
) {
    private val kv: MMKV =
        MMKV.mmkvWithID("bilibili_risk_state_${storageKey.toMd5String()}")

    fun lastPreheatAt(): Long = kv.decodeLong(KEY_LAST_PREHEAT_AT, 0L)

    fun updatePreheatAt(value: Long) {
        kv.encode(KEY_LAST_PREHEAT_AT, value)
    }

    fun lastGaiaActivateAt(): Long = kv.decodeLong(KEY_LAST_GAIA_ACTIVATE_AT, 0L)

    fun updateGaiaActivateAt(value: Long) {
        kv.encode(KEY_LAST_GAIA_ACTIVATE_AT, value)
    }

    private companion object {
        private const val KEY_LAST_PREHEAT_AT = "last_preheat_at"
        private const val KEY_LAST_GAIA_ACTIVATE_AT = "last_gaia_activate_at"
    }
}
