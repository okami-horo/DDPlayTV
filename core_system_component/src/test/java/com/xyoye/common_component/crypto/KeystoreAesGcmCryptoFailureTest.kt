package com.xyoye.common_component.crypto

import android.security.keystore.KeyGenParameterSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.UnrecoverableKeyException
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class KeystoreAesGcmCryptoFailureTest {
    private var previousProvider: Provider? = null
    private val crypto =
        KeystoreAesGcmCrypto(
            "fixture",
            object : WrappedKeyStore {
                override fun readWrappedKey(aesKeyAlias: String): String? = null

                override fun writeWrappedKey(
                    aesKeyAlias: String,
                    wrappedKey: String
                ) = Unit
            }
        )

    @Before fun installProvider() {
        previousProvider = Security.getProvider("AndroidKeyStore")
        Security.removeProvider("AndroidKeyStore")
        Security.addProvider(
            object : Provider("AndroidKeyStore", 1.0, "Test fixture") {
                init {
                    put("KeyStore.AndroidKeyStore", Store::class.java.name)
                    put("KeyGenerator.AES", Generator::class.java.name)
                }
            }
        )
        key = null
        failLoad = false
        failRead = false
        reject256 = false
        generatedSizes.clear()
    }

    @After fun restoreProvider() {
        Security.removeProvider("AndroidKeyStore")
        previousProvider?.let { Security.addProvider(it) }
    }

    @Test fun readFailureDoesNotReplaceExistingKey() {
        val encrypted = requireNotNull(crypto.encrypt("password"))
        val original = key
        generatedSizes.clear()
        failRead = true
        assertNull(crypto.decrypt(encrypted))
        assertNull(crypto.encrypt("replacement"))
        assertEquals(emptyList<Int>(), generatedSizes)
        assertSame(original, key)
        failRead = false
        assertEquals("password", crypto.decrypt(encrypted))
    }

    @Test fun loadFailureDoesNotReplaceExistingKey() {
        val encrypted = requireNotNull(crypto.encrypt("password"))
        val original = key
        generatedSizes.clear()
        failLoad = true
        assertNull(crypto.decrypt(encrypted))
        assertNull(crypto.encrypt("replacement"))
        assertEquals(emptyList<Int>(), generatedSizes)
        assertSame(original, key)
        failLoad = false
        assertEquals("password", crypto.decrypt(encrypted))
    }

    @Test fun unsupported256BitCreationCanStillUse128BitKey() {
        reject256 = true
        val encrypted = crypto.encrypt("password")
        assertNotNull(encrypted)
        assertEquals(listOf(256, 128), generatedSizes)
        assertEquals("password", crypto.decrypt(requireNotNull(encrypted)))
        assertEquals(listOf(256, 128), generatedSizes)
    }

    class Store : KeyStoreSpi() {
        override fun engineLoad(
            stream: InputStream?,
            password: CharArray?
        ) {
            if (failLoad) throw IOException("Keystore temporarily unavailable")
        }

        override fun engineGetKey(
            alias: String?,
            password: CharArray?
        ): Key? {
            if (failRead) throw UnrecoverableKeyException("Keystore temporarily unavailable")
            return key
        }

        override fun engineContainsAlias(alias: String?) = key != null

        override fun engineIsKeyEntry(alias: String?) = key != null

        override fun engineSize() = if (key == null) 0 else 1

        override fun engineAliases() = Collections.enumeration(listOf("fixture"))

        override fun engineGetCreationDate(alias: String?) = Date(0)

        override fun engineGetCertificate(alias: String?): Certificate? = null

        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null

        override fun engineGetCertificateAlias(cert: Certificate?): String? = null

        override fun engineIsCertificateEntry(alias: String?) = false

        override fun engineSetKeyEntry(
            alias: String?,
            key: Key?,
            password: CharArray?,
            chain: Array<out Certificate>?
        ) = Unit

        override fun engineSetKeyEntry(
            alias: String?,
            key: ByteArray?,
            chain: Array<out Certificate>?
        ) = Unit

        override fun engineSetCertificateEntry(
            alias: String?,
            cert: Certificate?
        ) = Unit

        override fun engineDeleteEntry(alias: String?) = Unit

        override fun engineStore(
            stream: OutputStream?,
            password: CharArray?
        ) = Unit
    }

    class Generator : KeyGeneratorSpi() {
        private var size = 0

        override fun engineInit(random: SecureRandom?) = Unit

        override fun engineInit(
            keysize: Int,
            random: SecureRandom?
        ) {
            size = keysize
        }

        override fun engineInit(
            params: AlgorithmParameterSpec?,
            random: SecureRandom?
        ) {
            size = (params as KeyGenParameterSpec).keySize
        }

        override fun engineGenerateKey(): SecretKey {
            generatedSizes.add(size)
            if (reject256 && size == 256) throw IllegalArgumentException("Unsupported key size")
            return SecretKeySpec(ByteArray(size / 8).also { SecureRandom().nextBytes(it) }, "AES").also { key = it }
        }
    }

    companion object {
        private var key: SecretKey? = null
        private var failLoad = false
        private var failRead = false
        private var reject256 = false
        private val generatedSizes = mutableListOf<Int>()
    }
}
