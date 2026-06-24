 # Danmu Match Optimization Implementation Plan

 > **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

 **Goal:** 提升 Dandanplay API 弹幕匹配成功率：使用 `hashAndFileName` + 真实参数，增加精确映射缓存，修复 hash 并发/超时问题，并保持 Bilibili 直播开关语义不变。

 **Architecture:** `DanmuSource` 提供文件元信息；`ResourceRepository` 调用完整 `/api/v2/match` 参数；`DanmuQuery` 返回 `MatchResult` 区分精确命中与模糊候选；`DanmuMatchCoordinator` 统一播放器自动匹配入口；缓存只保存 exact success / fail marker。

 **Tech Stack:** Kotlin 1.9.25, Coroutines, MMKV, Moshi codegen, Retrofit, Android XML/MVVM.

 ---

 ## File Map

 ### New Files

 | File | Responsibility |
 | --- | --- |
 | `data_component/src/main/java/com/xyoye/data_component/data/MatchResultData.kt` | `MatchResult` / `MatchMethod` domain model |
 | `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/cache/DanmuMatchCache.kt` | MMKV exact success / fail marker cache |
 | `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/DanmuMatchCoordinator.kt` | Unified player auto-match entry point |

 ### Modified Files

 | File | Responsibility |
 | --- | --- |
 | `data_component/src/main/java/com/xyoye/data_component/data/DanmuSearchData.kt` | Add parsed `shift` field |
 | `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/source/DanmuSource.kt` | Add metadata methods |
 | `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/source/AbstractDanmuSource.kt` | Default metadata implementations |
 | `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/source/impl/IOFileDanmuSource.kt` | Local file name/size |
 | `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/source/impl/DocumentFileDanmuSource.kt` | SAF file name/size |
 | `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/source/impl/NetworkDanmuSource.kt` | URL-decoded file name, timeout-safe hash |
 | `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/helper/DanmuHashCalculator.kt` | Per-call MD5 |
 | `core_network_component/src/main/java/com/xyoye/common_component/network/repository/ResourceRepository.kt` | Full match params |
 | `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/query/DanmuQuery.kt` | New match signature |
 | `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/query/DanmuQueryImpl.kt` | MatchResult mapping |
 | `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/DanmuFinder.kt` | `getMatched()` returns `MatchResult?` |
 | `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/DanmuFinderImpl.kt` | Exact-only `downloadMatched()` |
 | `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/StorageDanmuMatcher.kt` | `isSupported()` helper |
 | `player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerDanmuViewModel.kt` | Use coordinator; do not auto-download candidates |
 | `local_component/src/main/java/com/xyoye/local_component/ui/fragment/bind_danmu/BindDanmuSourceFragmentViewModel.kt` | Adapt to new `DanmuFinder.getMatched()` result |
 | `player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt` | Preserve Bilibili live switch + delegate matching |

 ---

 ## Task 1: Data model updates

 **Files:**
 - Modify: `data_component/src/main/java/com/xyoye/data_component/data/DanmuSearchData.kt`
 - Create: `data_component/src/main/java/com/xyoye/data_component/data/MatchResultData.kt`

 - [ ] **Step 1: Add `shift` to `DanmuEpisodeData`**

 ```kotlin
 @Parcelize
 @JsonClass(generateAdapter = true)
 data class DanmuEpisodeData(
     val animeId: Int = 0,
     val animeTitle: String = "",
     val episodeId: String = "",
     val episodeTitle: String = "",
     val shift: Double = 0.0,
     @Json(ignore = true)
     val isRecommend: Boolean = false,
     @Json(ignore = true)
     val isBound: Boolean = false
 ) : Parcelable
 ```

 - [ ] **Step 2: Create `MatchResultData.kt`**

 ```kotlin
 package com.xyoye.data_component.data

 data class MatchResult(
     val isMatched: Boolean,
     val episodes: List<DanmuEpisodeData>,
     val matchMethod: MatchMethod,
 )

 enum class MatchMethod {
     HASH,
     FILE_NAME,
     SEARCH_FALLBACK,
 }
 ```

 - [ ] **Step 3: Verify compile scope**

 Run after Task 6 when callers are adapted:

 ```powershell
 cmd /c pwsh -NoProfile -Command '.\gradlew :data_component:compileDebugKotlin'
 ```

 Expected: `BUILD SUCCESSFUL`.

 ---

 ## Task 2: Extend DanmuSource metadata

 **Files:**
 - Modify: `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/source/DanmuSource.kt`
 - Modify: `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/source/AbstractDanmuSource.kt`
 - Modify: `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/source/impl/IOFileDanmuSource.kt`
 - Modify: `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/source/impl/DocumentFileDanmuSource.kt`
 - Modify: `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/source/impl/NetworkDanmuSource.kt`

 - [ ] **Step 1: Update `DanmuSource`**

 ```kotlin
 interface DanmuSource {
     suspend fun hash(): String?
     suspend fun fileName(): String?
     suspend fun fileSize(): Long?
     suspend fun durationMs(): Long? = null
 }
 ```

 - [ ] **Step 2: Update `AbstractDanmuSource` defaults**

 ```kotlin
 abstract class AbstractDanmuSource : DanmuSource {
     private var hash: String? = null

     override suspend fun hash(): String? =
         hash ?: getStream()
             ?.use { DanmuHashCalculator.calculate(it) }
             .also { hash = it }

     override suspend fun fileName(): String? = null

     override suspend fun fileSize(): Long? = null

     abstract suspend fun getStream(): InputStream?
 }
 ```

 - [ ] **Step 3: Update `IOFileDanmuSource`**

 ```kotlin
 override suspend fun fileName(): String? {
     val file = File(path)
     return if (file.exists() && !file.isDirectory) file.nameWithoutExtension else null
 }

 override suspend fun fileSize(): Long? {
     val file = File(path)
     return if (file.exists() && !file.isDirectory) file.length() else null
 }
 ```

 - [ ] **Step 4: Update `DocumentFileDanmuSource`**

 ```kotlin
 override suspend fun fileName(): String? {
     val documentFile = getDocumentFile() ?: return null
     return documentFile.name?.substringBeforeLast(".")
 }

 override suspend fun fileSize(): Long? {
     val documentFile = getDocumentFile() ?: return null
     return documentFile.length().takeIf { it > 0 }
 }
 ```

 - [ ] **Step 5: Update `NetworkDanmuSource` file name only**

 ```kotlin
 override suspend fun fileName(): String? {
     val segment = Uri.parse(url).lastPathSegment ?: return null
     return URLDecoder.decode(segment, "UTF-8")
         .substringBeforeLast(".")
         .ifBlank { null }
 }

 override suspend fun fileSize(): Long? = null
 ```

 Required imports:

 ```kotlin
 import android.net.Uri
 import java.net.URLDecoder
 ```

 ---

 ## Task 3: Fix hash thread-safety and network timeout

 **Files:**
 - Modify: `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/helper/DanmuHashCalculator.kt`
 - Modify: `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/source/impl/NetworkDanmuSource.kt`

 - [ ] **Step 1: Replace shared `MessageDigest`**

 Remove class-level `private val MD5`. In `calculate()` use:

 ```kotlin
 val md5 = MessageDigest.getInstance("MD5")
 ```

 Replace `MD5.reset()`, `MD5.update(...)`, `MD5.digest()` with `md5.update(...)`, `md5.digest()`.

 - [ ] **Step 2: Use `withTimeoutOrNull` in `NetworkDanmuSource`**

 ```kotlin
 override suspend fun hash(): String? =
     withTimeoutOrNull(NETWORK_HASH_TIMEOUT_MS) {
         super.hash()
     }

 companion object {
     private const val NETWORK_HASH_TIMEOUT_MS = 30_000L
 }
 ```

 Required import:

 ```kotlin
 import kotlinx.coroutines.withTimeoutOrNull
 ```

 ---

 ## Task 4: Full Dandanplay match params

 **Files:**
 - Modify: `core_network_component/src/main/java/com/xyoye/common_component/network/repository/ResourceRepository.kt`

 - [ ] **Step 1: Replace `matchDanmu()`**

 ```kotlin
 suspend fun matchDanmu(
     fileHash: String,
     fileName: String,
     fileSize: Long,
     videoDuration: Int = 0,
     matchMode: String = "hashAndFileName"
 ) = request()
     .param("fileHash", fileHash)
     .param("fileName", fileName)
     .param("fileSize", fileSize)
     .param("videoDuration", videoDuration)
     .param("matchMode", matchMode)
     .doPost {
         RetrofitManager.danDanService.matchDanmu(it)
     }
 ```

 ---

 ## Task 5: Update DanmuQuery and DanmuFinder semantics

 **Files:**
 - Modify: `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/query/DanmuQuery.kt`
 - Modify: `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/query/DanmuQueryImpl.kt`
 - Modify: `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/DanmuFinder.kt`
 - Modify: `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/DanmuFinderImpl.kt`

 - [ ] **Step 1: Update `DanmuQuery.match()` signature**

 ```kotlin
 suspend fun match(
     hash: String,
     fileName: String?,
     fileSize: Long,
     videoDuration: Int = 0
 ): MatchResult
 ```

 - [ ] **Step 2: Implement match mode selection**

 ```kotlin
 override suspend fun match(
     hash: String,
     fileName: String?,
     fileSize: Long,
     videoDuration: Int
 ): MatchResult {
     val normalizedFileName = fileName?.takeIf { it.isNotBlank() }
     val requestFileName = normalizedFileName ?: "empty"
     val matchMode = if (normalizedFileName == null) "hashOnly" else "hashAndFileName"
     val result =
         ResourceRepository
             .matchDanmu(hash, requestFileName, fileSize, videoDuration, matchMode)
             .getOrNull()
             ?: return MatchResult(false, emptyList(), MatchMethod.HASH)

     return if (result.isMatched) {
         MatchResult(true, result.matches, MatchMethod.HASH)
     } else {
         MatchResult(false, result.matches, MatchMethod.FILE_NAME)
     }
 }
 ```

 - [ ] **Step 3: Update `DanmuFinder.getMatched()`**

 ```kotlin
 override suspend fun getMatched(source: DanmuSource): MatchResult? {
     val hash = source.hash() ?: return null
     val result = danmuQuery.match(
         hash = hash,
         fileName = source.fileName(),
         fileSize = source.fileSize() ?: 0L,
         videoDuration = ((source.durationMs() ?: 0L) / 1000).toInt(),
     )
     return if (result.episodes.isEmpty()) null else result
 }
 ```

 - [ ] **Step 4: Update `downloadMatched()` exact-only behavior**

 ```kotlin
 override suspend fun downloadMatched(source: DanmuSource): LocalDanmuBean? {
     val result = getMatched(source) ?: return null
     if (result.isMatched.not()) return null
     val episode = result.episodes.firstOrNull() ?: return null
     return downloadEpisode(episode)
 }
 ```

 ---

 ## Task 6: Add exact/fail DanmuMatchCache

 **Files:**
 - Create: `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/cache/DanmuMatchCache.kt`

 - [ ] **Step 1: Create cache using Moshi codegen**

 ```kotlin
 package com.xyoye.common_component.utils.danmu.cache

 import com.squareup.moshi.JsonClass
 import com.squareup.moshi.Moshi
 import com.tencent.mmkv.MMKV
 import java.util.concurrent.TimeUnit

 object DanmuMatchCache {
     private const val MMKV_ID = "danmu_match_cache"
     private const val SUCCESS_PREFIX = "danmu_match_success"
     private const val FAIL_PREFIX = "danmu_match_fail"

     private val mmkv: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID) }
     private val adapter by lazy { Moshi.Builder().build().adapter(CachedMatch::class.java) }

     fun getSuccess(hash: String, fileSize: Long): CachedMatch? {
         val key = successKey(hash, fileSize)
         val raw = mmkv.decodeString(key) ?: return null
         val cached = runCatching { adapter.fromJson(raw) }.getOrNull() ?: return null
         if (System.currentTimeMillis() - cached.cachedAt > TimeUnit.DAYS.toMillis(30)) {
             mmkv.removeValueForKey(key)
             return null
         }
         return cached
     }

     fun putSuccess(hash: String, fileSize: Long, match: CachedMatch) {
         mmkv.encode(successKey(hash, fileSize), adapter.toJson(match))
     }

     fun hasRecentFail(hash: String, fileSize: Long): Boolean {
         val key = failKey(hash, fileSize)
         val cachedAt = mmkv.decodeLong(key, 0L)
         if (cachedAt <= 0L) return false
         if (System.currentTimeMillis() - cachedAt > TimeUnit.DAYS.toMillis(1)) {
             mmkv.removeValueForKey(key)
             return false
         }
         return true
     }

     fun putFail(hash: String, fileSize: Long) {
         mmkv.encode(failKey(hash, fileSize), System.currentTimeMillis())
     }

     private fun successKey(hash: String, fileSize: Long): String = "${SUCCESS_PREFIX}_${hash}_${fileSize}"

     private fun failKey(hash: String, fileSize: Long): String = "${FAIL_PREFIX}_${hash}_${fileSize}"
 }

 @JsonClass(generateAdapter = true)
 data class CachedMatch(
     val episodeId: Long,
     val animeId: Long,
     val animeTitle: String,
     val episodeTitle: String,
     val shift: Double,
     val cachedAt: Long,
 )
 ```

 ---

 ## Task 7: Add StorageDanmuMatcher helper and coordinator

 **Files:**
 - Modify: `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/StorageDanmuMatcher.kt`
 - Create: `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/DanmuMatchCoordinator.kt`

 - [ ] **Step 1: Add `isSupported()`**

 ```kotlin
 fun isSupported(source: BaseVideoSource): Boolean =
     source.getMediaType() == MediaType.BILIBILI_STORAGE
 ```

 - [ ] **Step 2: Create coordinator**

 ```kotlin
 package com.xyoye.common_component.utils.danmu

 import com.xyoye.common_component.source.base.BaseVideoSource
 import com.xyoye.common_component.utils.danmu.cache.CachedMatch
 import com.xyoye.common_component.utils.danmu.cache.DanmuMatchCache
 import com.xyoye.common_component.utils.danmu.query.DanmuQuery
 import com.xyoye.common_component.utils.danmu.source.DanmuSourceFactory
 import com.xyoye.data_component.bean.DanmuTrackResource
 import com.xyoye.data_component.data.DanmuEpisodeData
 import com.xyoye.data_component.data.MatchMethod
 import com.xyoye.data_component.data.MatchResult

 class DanmuMatchCoordinator(
     private val danmuFinder: DanmuFinder = DanmuFinder.instance,
     private val danmuQuery: DanmuQuery = DanmuQuery.instance,
     private val danmuCache: DanmuMatchCache = DanmuMatchCache,
 ) {
     suspend fun match(source: BaseVideoSource, mode: MatchMode): MatchOutcome {
         if (StorageDanmuMatcher.isSupported(source)) return matchBilibili(source)

         val danmuSource = DanmuSourceFactory.build(source) ?: return MatchOutcome.NotFound
         val hash = danmuSource.hash()
         val fileName = danmuSource.fileName()
         val fileSize = danmuSource.fileSize() ?: 0L
         if (hash == null) return fileName?.let { searchFallback(it) } ?: MatchOutcome.NotFound

         if (mode == MatchMode.AUTO && fileSize > 0) {
             danmuCache.getSuccess(hash, fileSize)?.let { return cachedToOutcome(it) }
             if (danmuCache.hasRecentFail(hash, fileSize)) return MatchOutcome.NotFound
         }

         val result = danmuQuery.match(
             hash = hash,
             fileName = fileName,
             fileSize = fileSize,
             videoDuration = ((danmuSource.durationMs() ?: 0L) / 1000).toInt(),
         )

         if (fileSize > 0 && result.isMatched && result.episodes.isNotEmpty()) {
             val ep = result.episodes.first()
             danmuCache.putSuccess(hash, fileSize, ep.toCachedMatch())
         }
         if (fileSize > 0 && result.episodes.isEmpty()) {
             danmuCache.putFail(hash, fileSize)
         }

         return when {
             result.isMatched && result.episodes.isNotEmpty() ->
                 matchOutcomeFromEpisode(result.episodes.first(), result.matchMethod)
             result.episodes.isNotEmpty() ->
                 MatchOutcome.Candidates(result.episodes)
             fileName != null ->
                 searchFallback(fileName)
             else ->
                 MatchOutcome.NotFound
         }
     }

     private suspend fun matchBilibili(source: BaseVideoSource): MatchOutcome {
         val resource = StorageDanmuMatcher.matchDanmu(source)
         return if (resource != null) MatchOutcome.Success(resource, MatchMethod.HASH, 0.0) else MatchOutcome.NotFound
     }

     private suspend fun searchFallback(fileName: String): MatchOutcome {
         val episodes = danmuQuery.search(fileName).flatMap { it.episodes }
         return if (episodes.isNotEmpty()) {
             MatchOutcome.Candidates(episodes.map { it.copy(isRecommend = true) })
         } else {
             MatchOutcome.NotFound
         }
     }

     private suspend fun matchOutcomeFromEpisode(episode: DanmuEpisodeData, method: MatchMethod): MatchOutcome {
         val localDanmu = danmuFinder.downloadEpisode(episode)
         return if (localDanmu != null) {
             MatchOutcome.Success(DanmuTrackResource.LocalFile(localDanmu), method, episode.shift)
         } else {
             MatchOutcome.NotFound
         }
     }

     private suspend fun cachedToOutcome(cached: CachedMatch): MatchOutcome {
         val episode = DanmuEpisodeData(
             animeId = cached.animeId.toInt(),
             animeTitle = cached.animeTitle,
             episodeId = cached.episodeId.toString(),
             episodeTitle = cached.episodeTitle,
             shift = cached.shift,
         )
         return matchOutcomeFromEpisode(episode, MatchMethod.HASH)
     }

     private fun DanmuEpisodeData.toCachedMatch(): CachedMatch = CachedMatch(
         episodeId = episodeId.toLongOrNull() ?: 0L,
         animeId = animeId.toLong(),
         animeTitle = animeTitle,
         episodeTitle = episodeTitle,
         shift = shift,
         cachedAt = System.currentTimeMillis(),
     )

     enum class MatchMode { AUTO, MANUAL }
 }

 sealed class MatchOutcome {
     data class Success(
         val resource: DanmuTrackResource,
         val matchMethod: MatchMethod,
         val shift: Double,
     ) : MatchOutcome()

     data class Candidates(
         val episodes: List<DanmuEpisodeData>,
     ) : MatchOutcome()

     object NotFound : MatchOutcome()
 }
 ```

 ---

 ## Task 8: Update player auto-match caller safely

 **Files:**
 - Modify: `player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerDanmuViewModel.kt`
 - Modify: `player_component/src/main/java/com/xyoye/player_component/ui/activities/player/PlayerActivity.kt`

 - [ ] **Step 1: Use coordinator in `PlayerDanmuViewModel.matchDanmu()`**

 Keep `searchDanmu()` and `downloadDanmu()` using `DanmuFinder.instance`; do not expose `danmuFinder` from coordinator.

 ```kotlin
 private val coordinator = DanmuMatchCoordinator()

 fun matchDanmu(videoSource: BaseVideoSource) {
     viewModelScope.launch(Dispatchers.IO) {
         when (val outcome = coordinator.match(videoSource, DanmuMatchCoordinator.MatchMode.AUTO)) {
             is MatchOutcome.Success -> {
                 loadDanmuLiveData.postValue(videoSource.getVideoUrl() to outcome.resource)
             }
             is MatchOutcome.Candidates -> {
                 // Do not auto-download fuzzy candidates. Manual bind/search UI handles selection.
             }
             is MatchOutcome.NotFound -> Unit
         }
     }
 }
 ```

 - [ ] **Step 2: Preserve Bilibili live switch in `PlayerActivity`**

 ```kotlin
 val historyDanmu = source.getDanmu()
 if (historyDanmu != null) {
     LogFacade.i(LogModule.PLAYER, TAG_DANMAKU, "load history cid=${historyDanmu.episodeId}")
     videoController.addExtendTrack(VideoTrackBean.danmu(historyDanmu))
 } else {
     val shouldAutoMatchDanmu =
         if (StorageDanmuMatcher.isBilibiliLive(source)) {
             DanmuConfig.isAutoEnableBilibiliLiveDanmaku()
         } else {
             DanmuConfig.isAutoMatchDanmu() && source.getMediaType() != MediaType.FTP_SERVER
         }
     if (shouldAutoMatchDanmu && popupManager.isShowing().not()) {
         LogFacade.i(LogModule.PLAYER, TAG_DANMAKU, "auto match start title=${source.getVideoTitle()}")
         danmuViewModel.matchDanmu(source)
     }
 }
 ```

 ---

 ## Task 9: Update manual bind caller

 **Files:**
 - Modify: `local_component/src/main/java/com/xyoye/local_component/ui/fragment/bind_danmu/BindDanmuSourceFragmentViewModel.kt`

 - [ ] **Step 1: Adapt to new `DanmuFinder.getMatched()` result**

 This page starts from `StorageFile`, not `BaseVideoSource`, so keep `DanmuFinder` here. It is not a coordinator caller.

 ```kotlin
 fun matchDanmu() {
     val danmuSource = DanmuSourceFactory.build(storageFile) ?: return

     viewModelScope.launch {
         try {
             showLoading()
             val result = DanmuFinder.instance.getMatched(danmuSource)
             hideLoading()

             if (result != null && result.episodes.isNotEmpty()) {
                 val first = result.episodes.first()
                 val anime = DanmuAnimeData(
                     animeId = first.animeId,
                     animeTitle = first.animeTitle,
                     episodes = result.episodes,
                     isRecommend = result.isMatched,
                     isSelected = true,
                 )
                 _matchedAnimeFlow.emit(anime)
                 _selectedAnimeFlow.emit(anime)
             }
         } catch (e: Exception) {
             hideLoading()
             ErrorReportHelper.postCatchedExceptionWithContext(
                 e,
                 "BindDanmuSourceFragmentViewModel",
                 "matchDanmu",
                 "File: ${storageFile.fileName()}",
             )
         }
     }
 }
 ```

 ---

 ## Task 10: Verification

 **Files:**
 - No source edits.

 - [ ] **Step 1: Read lints for changed files**

 Use IDE lints or `ReadLints` on changed Kotlin files. Expected: no new diagnostics caused by these changes.

 - [ ] **Step 2: Run assembleDebug**

 ```powershell
 cmd /c pwsh -NoProfile -Command '.\gradlew assembleDebug'
 ```

 Expected tail contains `BUILD SUCCESSFUL`.

 - [ ] **Step 3: Run unit tests**

 ```powershell
 cmd /c pwsh -NoProfile -Command '.\gradlew testDebugUnitTest'
 ```

 Expected tail contains `BUILD SUCCESSFUL`.

 - [ ] **Step 4: Run module dependency check**

 ```powershell
 cmd /c pwsh -NoProfile -Command '.\gradlew verifyModuleDependencies'
 ```

 Expected tail contains `BUILD SUCCESSFUL`.

 - [ ] **Step 5: Commit only if user explicitly asks**

 This plan intentionally does not include mandatory `git commit` commands. If user asks to commit, inspect `git status`, `git diff`, and recent `git log` first, then commit relevant files only.

 ---

 ## Self-Review Notes

 - Bilibili live auto-enable semantics preserved.
 - `withTimeoutOrNull` used; timeout does not cancel fallback flow.
 - `Candidates` are never auto-downloaded in player auto-match flow.
 - `downloadMatched()` remains exact-only.
 - `shift` parsed but not applied.
 - Cache stores only exact success + fail marker; no candidate caching.
 - `LOCAL_ONLY` removed.
 - No mandatory commits in tasks.
 - Windows verification commands use `cmd /c pwsh -NoProfile -Command`.
