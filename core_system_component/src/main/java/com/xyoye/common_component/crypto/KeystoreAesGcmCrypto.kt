package com.xyoye.common_component.crypto

import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.util.Base64
import androidx.annotation.RequiresApi
import com.tencent.mmkv.MMKV
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

/**
 * Keystore-backed AES-GCM string crypto helper.
 *
 * - Output format: `ddenc1:<base64(iv)>:<base64(cipherText)>`
 * - API >= 23: AES key stored in AndroidKeyStore
 * - API < 23: AES key generated in memory and wrapped by an RSA key pair stored in AndroidKeyStore
 */
class KeystoreAesGcmCrypto(
    private val aesKeyAlias: String,
    private val wrappedKeyStore: WrappedKeyStore = MmkvWrappedKeyStore(),
    private val rsaKeyAlias: String = DEFAULT_RSA_KEY_ALIAS
) {
    fun isEncrypted(value: String?): Boolean = value?.startsWith(ENCRYPTED_PREFIX) == true

    fun encrypt(plainText: String): String? =
        runCatching {
            val secretKey = getOrCreateSecretKey() ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            ENCRYPTED_PREFIX + base64Encode(iv) + ENCRYPTED_DELIMITER + base64Encode(cipherText)
        }.getOrNull()

    fun decrypt(encrypted: String): String? =
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

    private fun base64Encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun base64Decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

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
            if (keyStore.containsAlias(aesKeyAlias)) {
                return keyStore.getKey(aesKeyAlias, null) as? SecretKey
            }
            // Only a new key may fall back to 128 bits; read failures must preserve existing keys.
            runCatching { generateSecretKeyM(256) }
                .recoverCatching { generateSecretKeyM(128) }
                .getOrThrow()
        }.getOrNull()

    @RequiresApi(Build.VERSION_CODES.M)
    private fun generateSecretKeyM(keySize: Int): SecretKey {
        val keyGenerator = KeyGenerator.getInstance("AES", KEYSTORE_PROVIDER)
        val spec =
            android.security.keystore.KeyGenParameterSpec
                .Builder(
                    aesKeyAlias,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(keySize)
                .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun getOrCreateSecretKeyPreM(): SecretKey? =
        runCatching {
            ensureRsaKeyPair()

            val wrappedKey = wrappedKeyStore.readWrappedKey(aesKeyAlias)
            if (!wrappedKey.isNullOrBlank()) {
                unwrapAesKeyWithOaep(wrappedKey)?.let { return it }
                unwrapAesKeyWithLegacyPkcs1(wrappedKey)?.let { legacyKey ->
                    wrapAesKey(legacyKey.encoded ?: return@let)?.let {
                        wrappedKeyStore.writeWrappedKey(aesKeyAlias, it)
                    }
                    return legacyKey
                }
            }

            val rawKey = ByteArray(16).apply { SecureRandom().nextBytes(this) }
            val newWrappedKey = wrapAesKey(rawKey) ?: return null
            wrappedKeyStore.writeWrappedKey(aesKeyAlias, newWrappedKey)
            SecretKeySpec(rawKey, "AES")
        }.getOrNull()

    private fun ensureRsaKeyPair() {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (keyStore.containsAlias(rsaKeyAlias)) return

        val context = BaseApplication.getAppContext()
        val start = Calendar.getInstance()
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }

        val spec =
            KeyPairGeneratorSpec
                .Builder(context)
                .setAlias(rsaKeyAlias)
                .setSubject(X500Principal("CN=$rsaKeyAlias"))
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
            val publicKey = keyStore.getCertificate(rsaKeyAlias)?.publicKey ?: return null
            val cipher = createRsaOaepCipher() ?: return null
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            base64Encode(cipher.doFinal(rawKey))
        }.getOrNull()

    private fun unwrapAesKeyWithOaep(wrappedKey: String): SecretKey? =
        runCatching {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val privateKey = keyStore.getKey(rsaKeyAlias, null) ?: return null
            val cipher = createRsaOaepCipher() ?: return null
            cipher.init(Cipher.DECRYPT_MODE, privateKey)
            val rawKey = cipher.doFinal(base64Decode(wrappedKey))
            SecretKeySpec(rawKey, "AES")
        }.getOrNull()

    @Suppress("kotlin:S5542")
    private fun unwrapAesKeyWithLegacyPkcs1(wrappedKey: String): SecretKey? =
        runCatching {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val privateKey = keyStore.getKey(rsaKeyAlias, null) ?: return null
            // Legacy fallback to keep pre-OAEP wrapped keys readable during migration. // NOSONAR
            val cipher = Cipher.getInstance(LEGACY_RSA_WRAP_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, privateKey)
            val rawKey = cipher.doFinal(base64Decode(wrappedKey))
            SecretKeySpec(rawKey, "AES")
        }.getOrNull()

    private fun createRsaOaepCipher(): Cipher? =
        runCatching { Cipher.getInstance(RSA_OAEP_SHA256_TRANSFORMATION) }
            .recoverCatching { Cipher.getInstance(RSA_OAEP_SHA1_TRANSFORMATION) }
            .getOrNull()

    private companion object {
        private const val ENCRYPTED_PREFIX = "ddenc1:"
        private const val ENCRYPTED_DELIMITER = ":"

        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val DEFAULT_RSA_KEY_ALIAS = "dandanplay.crypto.rsa"

        private const val RSA_OAEP_SHA256_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        private const val RSA_OAEP_SHA1_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-1AndMGF1Padding"
        private const val LEGACY_RSA_WRAP_TRANSFORMATION = "RSA/ECB/PKCS1Padding"
    }
}

interface WrappedKeyStore {
    fun readWrappedKey(aesKeyAlias: String): String?

    fun writeWrappedKey(
        aesKeyAlias: String,
        wrappedKey: String
    )
}

class MmkvWrappedKeyStore(
    private val mmkvId: String = "dandanplay.crypto.wrapped_keys"
) : WrappedKeyStore {
    private val kv: MMKV by lazy { MMKV.mmkvWithID(mmkvId) }

    override fun readWrappedKey(aesKeyAlias: String): String? = kv.decodeString(aesKeyAlias)

    override fun writeWrappedKey(
        aesKeyAlias: String,
        wrappedKey: String
    ) {
        kv.encode(aesKeyAlias, wrappedKey)
    }
}
