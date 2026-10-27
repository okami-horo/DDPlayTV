package com.xyoye.player_component.playback.handoff

import com.tencent.mmkv.MMKV
import com.xyoye.common_component.crypto.KeystoreAesGcmCrypto

internal interface HandoffBlobStorage {
    fun read(): String?

    fun write(value: String?): Boolean
}

internal interface HandoffCipher {
    fun encrypt(plain: String): String?

    fun decrypt(encrypted: String): String?
}

internal class MmkvHandoffStorage : HandoffBlobStorage {
    private val kv by lazy { MMKV.mmkvWithID("dandanplay.playback.handoff") }

    override fun read(): String? = kv.decodeString("state")

    override fun write(value: String?): Boolean = kv.encode("state", value)
}

internal class KeystoreHandoffCipher : HandoffCipher {
    private val crypto by lazy {
        KeystoreAesGcmCrypto(
            aesKeyAlias = "dandanplay.playback.handoff.aes",
            rsaKeyAlias = "dandanplay.playback.handoff.rsa"
        )
    }

    override fun encrypt(plain: String): String? = crypto.encrypt(plain)

    override fun decrypt(encrypted: String): String? = crypto.decrypt(encrypted)
}
