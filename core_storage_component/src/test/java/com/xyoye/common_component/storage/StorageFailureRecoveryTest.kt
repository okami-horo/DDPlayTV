package com.xyoye.common_component.storage

import androidx.room.Room
import com.tencent.mmkv.MMKV
import com.xyoye.common_component.config.BaiduPanOpenApiConfig
import com.xyoye.common_component.crypto.KeystoreAesGcmCrypto
import com.xyoye.common_component.database.DatabaseAccessProvider
import com.xyoye.common_component.database.DatabaseInfo
import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.common_component.network.RetrofitManager
import com.xyoye.common_component.network.RetrofitServiceProvider
import com.xyoye.common_component.network.repository.Open115Repository
import com.xyoye.common_component.network.service.BaiduPanService
import com.xyoye.common_component.network.service.Open115Service
import com.xyoye.common_component.storage.baidupan.auth.BaiduPanAuthStore
import com.xyoye.common_component.storage.baidupan.auth.BaiduPanReAuthRequiredException
import com.xyoye.common_component.storage.baidupan.auth.BaiduPanTokenManager
import com.xyoye.common_component.storage.credential.MediaLibraryCredentialMigration
import com.xyoye.common_component.storage.credential.MediaLibraryCredentialResolver
import com.xyoye.common_component.storage.credential.MediaLibraryCredentialStore
import com.xyoye.common_component.storage.open115.auth.Open115AuthStore
import com.xyoye.common_component.storage.open115.path.Open115FolderInfoCache
import com.xyoye.data_component.data.open115.Open115FolderInfoData
import com.xyoye.data_component.data.open115.Open115FolderInfoResponse
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import retrofit2.Response
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

@RunWith(RobolectricTestRunner::class)
@Config(
    manifest = Config.NONE,
    shadows = [StorageFailureRecoveryTest.MemoryMmkv::class, StorageFailureRecoveryTest.Crypto::class, StorageFailureRecoveryTest.BaiduConfig::class]
)
class StorageFailureRecoveryTest {
    private val key = "fixture"
    private val library = MediaLibraryEntity(42, "fixture", "smb://fixture", MediaType.SMB_SERVER)
    private val credentials get() = MemoryMmkv.stores.getOrPut("dandanplay.media_library.credentials") { mutableMapOf() }

    @Before fun setup() {
        MemoryMmkv.stores.values.forEach { it.clear() }
        MemoryMmkv.writable = true
        Crypto.available = true
        MediaLibraryCredentialMigration::class.java
            .getDeclaredField("scheduled")
            .apply { isAccessible = true }
            .get(null)
            .let { (it as AtomicBoolean).set(true) }
    }

    @After fun reset() {
        RetrofitManager.resetServiceProvider()
        DatabaseProvider.overrideProviderForTest(null)
    }

    @Test fun tokenNetworkFailureAndCancellationPreserveAuthorizationAndRecover() =
        runBlocking {
            BaiduPanAuthStore.writeTokens(key, "old-access", 1, "old-refresh", "scope", 1)
            BaiduPanAuthStore.writeProfile(key, 7, "fixture", null, 1)
            val original = BaiduPanAuthStore.read(key)
            var failure: Exception? = IOException("offline")
            install(BaiduPanService::class.java) { _, _ ->
                failure?.let { throw it }
                Response.success("""{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}""".toResponseBody())
            }
            val manager = BaiduPanTokenManager(key)
            for (error in listOf(IOException("offline"), CancellationException("cancel"))) {
                failure = error
                assertSame(error, runCatching { manager.refreshAccessToken(true) }.exceptionOrNull())
                assertEquals(original, BaiduPanAuthStore.read(key))
            }
            failure = null
            assertEquals("new-access", manager.refreshAccessToken(true))
            assertEquals("new-refresh", BaiduPanAuthStore.read(key).refreshToken)
        }

    @Test fun onlyConfirmedInvalidRefreshTokensClearAuthorization() =
        runBlocking {
            var payload = ""
            install(BaiduPanService::class.java) { _, _ -> Response.success(payload.toResponseBody()) }
            val manager = BaiduPanTokenManager(key)
            for (error in listOf("invalid_client", "server_error", "invalid_request", "malformed", "invalid_grant", "expired_token")) {
                BaiduPanAuthStore.writeTokens(key, "old-access", 1, "old-refresh", "scope", 1)
                payload = if (error == "malformed") "not-json" else """{"error":"$error"}"""
                val failure = runCatching { manager.refreshAccessToken(true) }.exceptionOrNull()
                assertNotNull(failure)
                if (error in setOf("invalid_grant", "expired_token")) {
                    assertTrue(failure is BaiduPanReAuthRequiredException)
                    assertNull(BaiduPanAuthStore.read(key).refreshToken)
                } else {
                    assertEquals("old-refresh", BaiduPanAuthStore.read(key).refreshToken)
                    assertFalse(failure is BaiduPanReAuthRequiredException)
                }
            }
        }

    @Test fun credentialReadFailurePreservesCiphertextAndPreventsEmptyPasswordEditing() {
        credentials["pw_42"] = "ddenc1:old"
        Crypto.available = false
        assertNotNull(runCatching { MediaLibraryCredentialResolver.resolve(library) }.exceptionOrNull())
        assertEquals("ddenc1:old", credentials["pw_42"])
        Crypto.available = true
        assertEquals("old", MediaLibraryCredentialResolver.resolve(library).password)
    }

    @Test fun credentialWriteFailurePreservesOldValueAndSuccessfulWriteReplacesIt() {
        credentials["pw_42"] = "ddenc1:old"
        Crypto.available = false
        assertFalse(MediaLibraryCredentialStore.writePassword(42, "new"))
        assertEquals("ddenc1:old", credentials["pw_42"])
        Crypto.available = true
        MemoryMmkv.writable = false
        assertFalse(MediaLibraryCredentialStore.writePassword(42, "new"))
        assertEquals("ddenc1:old", credentials["pw_42"])
        MemoryMmkv.writable = true
        assertTrue(MediaLibraryCredentialStore.writePassword(42, "new"))
        assertEquals("new", MediaLibraryCredentialStore.readPassword(42))
        assertTrue(MediaLibraryCredentialStore.writePassword(42, null))
        assertNull(MediaLibraryCredentialStore.readPassword(42))
    }

    @Test fun legacyCredentialIsRetainedUntilEncryptedPersistenceSucceeds() {
        credentials["pw_42"] = "legacy"
        Crypto.available = false
        assertEquals("legacy", MediaLibraryCredentialStore.readPassword(42))
        assertEquals("legacy", credentials["pw_42"])
        Crypto.available = true
        assertEquals("legacy", MediaLibraryCredentialStore.readPassword(42))
        assertEquals("ddenc1:legacy", credentials["pw_42"])
    }

    @Test fun legacyDatabasePasswordSurvivesFailedMigration() =
        runBlocking {
            val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), DatabaseInfo::class.java).build()
            try {
                val legacy = library.copy(password = "legacy")
                database.getMediaLibraryDao().insert(legacy)
                DatabaseProvider.overrideProviderForTest(DatabaseAccessProvider { database })
                Crypto.available = false
                migrate(database, legacy)
                assertEquals("legacy", database.getMediaLibraryDao().getById(42)?.password)
                Crypto.available = true
                migrate(database, legacy)
                assertNull(database.getMediaLibraryDao().getById(42)?.password)
                assertEquals("legacy", MediaLibraryCredentialStore.readPassword(42))
            } finally {
                database.close()
            }
        }

    @Test fun failedParentLookupDoesNotCacheRootAndCanRecover() =
        runBlocking {
            Open115AuthStore.writeTokens(key, "access", Long.MAX_VALUE, "refresh", 1)
            var unavailable = true
            var calls = 0
            install(Open115Service::class.java) { name, args ->
                check(name == "folderGetInfo")
                calls++
                if (unavailable) throw IOException("offline")
                val folder = args.filterIsInstance<String>().last()
                Open115FolderInfoResponse(state = true, data = Open115FolderInfoData(folder, if (folder == "child") "parent" else "0"))
            }
            val cache = Open115FolderInfoCache(Open115Repository(key))
            assertNotNull(runCatching { cache.resolveBreadcrumbIds("child") }.exceptionOrNull())
            unavailable = false
            assertEquals(listOf("parent", "child"), cache.resolveBreadcrumbIds("child"))
            val resolvedCalls = calls
            assertEquals(listOf("parent", "child"), cache.resolveBreadcrumbIds("child"))
            assertEquals(resolvedCalls, calls)
            cache.cacheParent("child", "0")
            assertEquals(listOf("child"), cache.resolveBreadcrumbIds("child"))
        }

    @Test fun missingParentPayloadIsNotAConfirmedRoot() =
        runBlocking {
            Open115AuthStore.writeTokens(key, "access", Long.MAX_VALUE, "refresh", 1)
            var parent: String? = null
            install(
                Open115Service::class.java
            ) { _, _ -> Open115FolderInfoResponse(state = true, data = Open115FolderInfoData("child", parent)) }
            val cache = Open115FolderInfoCache(Open115Repository(key))
            assertNotNull(runCatching { cache.resolveBreadcrumbIds("child") }.exceptionOrNull())
            parent = "0"
            assertEquals(listOf("child"), cache.resolveBreadcrumbIds("child"))
            assertTrue(cache.resolveBreadcrumbIds("0").isEmpty())
            assertNotNull(runCatching { cache.resolveBreadcrumbIds("") }.exceptionOrNull())
        }

    private suspend fun migrate(
        database: DatabaseInfo,
        library: MediaLibraryEntity
    ) = suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
        MediaLibraryCredentialMigration::class.java.declaredMethods
            .single { it.name == "migrateLibrary" }
            .apply { isAccessible = true }
            .invoke(MediaLibraryCredentialMigration, database.getMediaLibraryDao(), library, continuation)
    }

    private fun <T> install(
        type: Class<T>,
        call: (String, Array<out Any?>) -> Any
    ) {
        val service =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                try {
                    call(method.name, args.orEmpty())
                } catch (error: Exception) {
                    @Suppress("UNCHECKED_CAST")
                    val continuation = args?.lastOrNull() as? Continuation<Any?> ?: throw error
                    continuation.resumeWith(Result.failure(error))
                    COROUTINE_SUSPENDED
                }
            }
        val provider =
            Proxy.newProxyInstance(
                RetrofitServiceProvider::class.java.classLoader,
                arrayOf(RetrofitServiceProvider::class.java)
            ) { _, _, _ -> service } as RetrofitServiceProvider
        RetrofitManager.replaceServiceProvider(provider)
    }

    @Implements(value = BaiduPanOpenApiConfig::class, isInAndroidSdk = false)
    class BaiduConfig {
        @Implementation fun isConfigured() = true

        @Implementation fun getClientId() = "fixture-client"

        @Implementation fun getClientSecret() = "fixture-secret"
    }

    @Implements(value = KeystoreAesGcmCrypto::class, isInAndroidSdk = false)
    class Crypto {
        @Implementation fun encrypt(value: String): String? = if (available) "ddenc1:$value" else null

        @Implementation fun decrypt(value: String): String? = if (available) value.removePrefix("ddenc1:") else null

        companion object {
            var available = true
        }
    }

    @Implements(value = MMKV::class, isInAndroidSdk = false)
    class MemoryMmkv {
        private lateinit var values: MutableMap<String, Any>

        @Implementation fun decodeString(key: String): String? = values[key] as? String

        @Implementation fun decodeString(
            key: String,
            default: String?
        ): String? = values[key] as? String ?: default

        @Implementation fun decodeLong(
            key: String,
            default: Long
        ): Long = values[key] as? Long ?: default

        @Implementation fun decodeBool(
            key: String,
            default: Boolean
        ): Boolean = values[key] as? Boolean ?: default

        @Implementation fun decodeInt(
            key: String,
            default: Int
        ): Int = values[key] as? Int ?: default

        @Implementation fun encode(
            key: String,
            value: String?
        ): Boolean {
            if (!writable) return false
            if (value == null) values.remove(key) else values[key] = value
            return true
        }

        @Implementation fun encode(
            key: String,
            value: Long
        ): Boolean {
            if (!writable) return false
            values[key] = value
            return true
        }

        @Implementation fun removeValueForKey(key: String) {
            values.remove(key)
        }

        companion object {
            val stores = mutableMapOf<String, MutableMap<String, Any>>()
            var writable = true

            @JvmStatic @Implementation
            fun defaultMMKV(): MMKV = mmkvWithID("default")

            @JvmStatic @Implementation
            fun mmkvWithID(id: String): MMKV =
                Shadow.newInstanceOf(MMKV::class.java).also {
                    Shadow.extract<MemoryMmkv>(it).values = stores.getOrPut(id) { mutableMapOf() }
                }
        }
    }
}
