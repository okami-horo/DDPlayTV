package com.xyoye.common_component.storage.credential

import com.tencent.mmkv.MMKV
import com.xyoye.common_component.crypto.KeystoreAesGcmCrypto
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule

object MediaLibraryCredentialStore {
    private const val TAG = "MediaLibraryCredentialStore"

    private const val MMKV_ID = "dandanplay.media_library.credentials"
    private const val AES_KEY_ALIAS = "dandanplay.media_library.credential.aes"

    private val kv: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID) }
    private val crypto: KeystoreAesGcmCrypto by lazy { KeystoreAesGcmCrypto(AES_KEY_ALIAS) }

    fun scheduleMigration() {
        ensureMigrationScheduled()
    }

    fun readPassword(libraryId: Int, failOnUnavailable: Boolean = false): String? {
        ensureMigrationScheduled()
        return readCredential(keyPassword(libraryId), failOnUnavailable)
    }

    fun readRemoteSecret(libraryId: Int, failOnUnavailable: Boolean = false): String? {
        ensureMigrationScheduled()
        return readCredential(keyRemoteSecret(libraryId), failOnUnavailable)
    }

    fun writePassword(
        libraryId: Int,
        password: String?
    ): Boolean {
        ensureMigrationScheduled()
        return writeCredential(keyPassword(libraryId), password)
    }

    fun writeRemoteSecret(
        libraryId: Int,
        remoteSecret: String?
    ): Boolean {
        ensureMigrationScheduled()
        return writeCredential(keyRemoteSecret(libraryId), remoteSecret)
    }

    fun clear(libraryId: Int) {
        ensureMigrationScheduled()
        kv.removeValueForKey(keyPassword(libraryId))
        kv.removeValueForKey(keyRemoteSecret(libraryId))
    }

    private fun keyPassword(libraryId: Int): String = "pw_$libraryId"

    private fun keyRemoteSecret(libraryId: Int): String = "rs_$libraryId"

    private fun readCredential(key: String, failOnUnavailable: Boolean): String? {
        val stored = kv.decodeString(key)?.takeIf { it.isNotBlank() } ?: return null
        if (crypto.isEncrypted(stored)) {
            val decrypted = crypto.decrypt(stored)
            if (decrypted == null) {
                if (failOnUnavailable) throw CredentialUnavailableException()
                kv.removeValueForKey(key)
                LogFacade.w(
                    module = LogModule.STORAGE,
                    tag = TAG,
                    message = "decrypt failed, clear key=$key",
                )
            }
            return decrypted
        }

        // legacy plain → migrate to encrypted
        val encrypted = crypto.encrypt(stored)
        if (encrypted != null) {
            kv.encode(key, encrypted)
        } else {
            kv.removeValueForKey(key)
            LogFacade.w(
                module = LogModule.STORAGE,
                tag = TAG,
                message = "encrypt failed, clear legacy plain key=$key",
            )
        }
        return stored
    }

    private fun writeCredential(
        key: String,
        value: String?
    ): Boolean {
        val plain = value?.trim().orEmpty()
        if (plain.isBlank()) {
            kv.removeValueForKey(key)
            return true
        }

        val encrypted = crypto.encrypt(plain)
        if (encrypted != null) {
            kv.encode(key, encrypted)
            return true
        }

        kv.removeValueForKey(key)
        LogFacade.w(
            module = LogModule.STORAGE,
            tag = TAG,
            message = "encrypt failed, clear key=$key",
        )
        return false
    }

    private fun ensureMigrationScheduled() {
        MediaLibraryCredentialMigration.trySchedule()
    }
}

internal class CredentialUnavailableException : Exception("Media library credential unavailable")
