package com.xyoye.common_component.config

import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.util.Base64
import androidx.annotation.RequiresApi
import com.xyoye.core_system_component.BuildConfig
import com.xyoye.common_component.base.app.BaseApplication
import java.math.BigInteger
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

object DeveloperCredentialStore {
    private const val ENCRYPTED_PREFIX = "ddenc1:"
    private const val ENCRYPTED_DELIMITER = ":"

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val AES_KEY_ALIAS = "dandanplay.developer.credential.aes"
    private const val RSA_KEY_ALIAS = "dandanplay.developer.credential.rsa"

    fun isBuildCredentialInjected(): Boolean = BuildConfig.DANDAN_DEV_CREDENTIAL_INJECTED

    fun getAppId(): String? {
        if (isBuildCredentialInjected()) {
            return BuildConfig.DANDAN_APP_ID.trim().takeIf { it.isNotEmpty() }
        }

        return getCredential(
            encrypted = DevelopConfig.getAppIdEncrypted(),
            legacyPlain = DevelopConfig.getAppId(),
            saveEncrypted = DevelopConfig::putAppIdEncrypted,
            clearLegacyPlain = { DevelopConfig.putAppId("") },
        )
    }

    fun getAppSecret(): String? {
        if (isBuildCredentialInjected()) {
            return BuildConfig.DANDAN_APP_SECRET.trim().takeIf { it.isNotEmpty() }
        }

        return getCredential(
            encrypted = DevelopConfig.getAppSecretEncrypted(),
            legacyPlain = DevelopConfig.getAppSecret(),
            saveEncrypted = DevelopConfig::putAppSecretEncrypted,
            clearLegacyPlain = { DevelopConfig.putAppSecret("") },
        )
    }

    /**
     * 仅用于认证输入框回显：只回显本地保存的值，不回显编译期注入的值，避免在UI里直接暴露。
     */
    fun getStoredAppIdForPrefill(): String? =
        getStoredCredentialForPrefill(
            encrypted = DevelopConfig.getAppIdEncrypted(),
            legacyPlain = DevelopConfig.getAppId(),
        )

    /**
     * 仅用于认证输入框回显：只回显本地保存的值，不回显编译期注入的值，避免在UI里直接暴露。
     */
    fun getStoredAppSecretForPrefill(): String? =
        getStoredCredentialForPrefill(
            encrypted = DevelopConfig.getAppSecretEncrypted(),
            legacyPlain = DevelopConfig.getAppSecret(),
        )

    fun putAppId(appId: String) {
        putCredential(
            value = appId,
            saveEncrypted = DevelopConfig::putAppIdEncrypted,
            clearLegacyPlain = { DevelopConfig.putAppId("") },
            saveLegacyPlain = DevelopConfig::putAppId,
        )
    }

    fun putAppSecret(appSecret: String) {
        putCredential(
            value = appSecret,
            saveEncrypted = DevelopConfig::putAppSecretEncrypted,
            clearLegacyPlain = { DevelopConfig.putAppSecret("") },
            saveLegacyPlain = DevelopConfig::putAppSecret,
        )
    }

    private fun getCredential(
        encrypted: String?,
        legacyPlain: String?,
        saveEncrypted: (String) -> Unit,
        clearLegacyPlain: () -> Unit,
    ): String? {
        if (!encrypted.isNullOrBlank()) {
            decrypt(encrypted)?.let { return it }
        }

        if (!legacyPlain.isNullOrBlank()) {
            encrypt(legacyPlain)?.let { encryptedValue ->
                saveEncrypted(encryptedValue)
                clearLegacyPlain()
            }
            return legacyPlain
        }

        return null
    }

    private fun getStoredCredentialForPrefill(
        encrypted: String?,
        legacyPlain: String?,
    ): String? {
        if (!encrypted.isNullOrBlank()) {
            decrypt(encrypted)?.let { return it }
        }
        return legacyPlain?.takeIf { it.isNotBlank() }
    }

    private fun putCredential(
        value: String,
        saveEncrypted: (String) -> Unit,
        clearLegacyPlain: () -> Unit,
        saveLegacyPlain: (String) -> Unit,
    ) {
        if (value.isBlank()) {
            saveEncrypted("")
            clearLegacyPlain()
            return
        }

        val encrypted = encrypt(value)
        if (encrypted != null) {
            saveEncrypted(encrypted)
            clearLegacyPlain()
            return
        }

        // 加密失败：兜底仍然保存明文，避免功能不可用
        saveLegacyPlain(value)
        saveEncrypted("")
    }

    private fun encrypt(plainText: String): String? =
        runCatching {
            val secretKey = getOrCreateSecretKey() ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            ENCRYPTED_PREFIX +
                base64Encode(iv) +
                ENCRYPTED_DELIMITER +
                base64Encode(cipherText)
        }.getOrNull()

    private fun decrypt(encrypted: String): String? =
        runCatching {
            if (!encrypted.startsWith(ENCRYPTED_PREFIX)) {
                return null
            }
            val payload = encrypted.removePrefix(ENCRYPTED_PREFIX)
            val parts = payload.split(ENCRYPTED_DELIMITER, limit = 2)
            if (parts.size != 2) return null

            val iv = base64Decode(parts[0])
            val cipherText = base64Decode(parts[1])

            val secretKey = getOrCreateSecretKey() ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        }.getOrNull()

    private fun base64Encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun base64Decode(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)

    private fun getOrCreateSecretKey(): SecretKey? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getOrCreateSecretKeyM()
        } else {
            getOrCreateSecretKeyPreM()
        }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun getOrCreateSecretKeyM(): SecretKey? =
        runCatching {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val existing = keyStore.getKey(AES_KEY_ALIAS, null) as? SecretKey
            if (existing != null) return existing

            val keyGenerator = KeyGenerator.getInstance("AES", KEYSTORE_PROVIDER)
            val spec =
                android.security.keystore.KeyGenParameterSpec.Builder(
                    AES_KEY_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setKeySize(256)
                    .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }.recoverCatching {
            // 某些设备/ROM 可能不支持 256 bit AES，回退 128 bit
            val keyGenerator = KeyGenerator.getInstance("AES", KEYSTORE_PROVIDER)
            val spec =
                android.security.keystore.KeyGenParameterSpec.Builder(
                    AES_KEY_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setKeySize(128)
                    .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }.getOrNull()

    private fun getOrCreateSecretKeyPreM(): SecretKey? =
        runCatching {
            ensureRsaKeyPair()

            val wrappedKey = DevelopConfig.getDevCredentialAesKeyWrapped()
            if (!wrappedKey.isNullOrBlank()) {
                unwrapAesKey(wrappedKey)?.let { return it }
            }

            val rawKey = ByteArray(16).apply { SecureRandom().nextBytes(this) }
            val newWrappedKey = wrapAesKey(rawKey) ?: return null
            DevelopConfig.putDevCredentialAesKeyWrapped(newWrappedKey)
            SecretKeySpec(rawKey, "AES")
        }.getOrNull()

    private fun ensureRsaKeyPair() {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (keyStore.containsAlias(RSA_KEY_ALIAS)) return

        val context = BaseApplication.getAppContext()
        val start = Calendar.getInstance()
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }

        val spec =
            KeyPairGeneratorSpec.Builder(context)
                .setAlias(RSA_KEY_ALIAS)
                .setSubject(X500Principal("CN=$RSA_KEY_ALIAS"))
                .setSerialNumber(BigInteger.ONE)
                .setStartDate(start.time)
                .setEndDate(end.time)
                .build()

        val generator = java.security.KeyPairGenerator.getInstance("RSA", KEYSTORE_PROVIDER)
        generator.initialize(spec)
        generator.generateKeyPair()
    }

    private fun wrapAesKey(rawKey: ByteArray): String? =
        runCatching {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val publicKey = keyStore.getCertificate(RSA_KEY_ALIAS)?.publicKey ?: return null
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            base64Encode(cipher.doFinal(rawKey))
        }.getOrNull()

    private fun unwrapAesKey(wrappedKey: String): SecretKey? =
        runCatching {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val privateKey = keyStore.getKey(RSA_KEY_ALIAS, null) ?: return null
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.DECRYPT_MODE, privateKey)
            val rawKey = cipher.doFinal(base64Decode(wrappedKey))
            SecretKeySpec(rawKey, "AES")
        }.getOrNull()
}
