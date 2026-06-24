# Danmu Match Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix merge-blocking danmu match bugs around stale manual-bind state, unsafe URL fallback parsing, unsafe cache IDs, cancellation propagation, and missing coordinator branch tests.

**Architecture:** Keep fixes surgical inside existing ViewModel/source/coordinator files. Add focused unit coverage in `DanmuMatchCoordinatorTest` using existing fake collaborators, avoiding production API expansion.

**Tech Stack:** Kotlin 1.9.25, Android ViewModel/coroutines/Flow, JUnit4, Gradle `testDebugUnitTest`.

---

## File Structure

- Modify: `local_component/src/main/java/com/xyoye/local_component/ui/fragment/bind_danmu/BindDanmuSourceFragmentViewModel.kt`
  - Responsibility: clear matched/selected manual-bind UI state when automatic matching returns no usable episode.
- Modify: `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/source/impl/NetworkDanmuSource.kt`
  - Responsibility: keep network source file-name parsing safe and preserve coroutine cancellation.
- Modify: `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/DanmuMatchCoordinator.kt`
  - Responsibility: skip success cache writes when exact match episode ID cannot be represented safely as `Long`.
- Modify: `core_storage_component/src/test/java/com/xyoye/common_component/utils/danmu/DanmuMatchCoordinatorTest.kt`
  - Responsibility: cover exact download, cache hit, fail marker, empty exact result, and non-numeric episode ID cache safety.

No commits in this plan unless user explicitly asks. Repo rule forbids proactive commits.

---

### Task 1: Fix stale manual-bind match state

**Files:**
- Modify: `local_component/src/main/java/com/xyoye/local_component/ui/fragment/bind_danmu/BindDanmuSourceFragmentViewModel.kt`

- [ ] **Step 1: Locate `matchDanmu()` success-only branch**

Find this block:

```kotlin
if (matched != null && matched.episodes.isNotEmpty()) {
    val first = matched.episodes.first()
    val anime = DanmuAnimeData(
        animeId = first.animeId,
        animeTitle = first.animeTitle,
        episodes = matched.episodes,
        isRecommend = matched.isMatched,
        isSelected = true,
    )
    _matchedAnimeFlow.emit(anime)
    _selectedAnimeFlow.emit(anime)
}
```

- [ ] **Step 2: Add empty-match clear branch**

Replace with:

```kotlin
if (matched != null && matched.episodes.isNotEmpty()) {
    val first = matched.episodes.first()
    val anime = DanmuAnimeData(
        animeId = first.animeId,
        animeTitle = first.animeTitle,
        episodes = matched.episodes,
        isRecommend = matched.isMatched,
        isSelected = true,
    )
    _matchedAnimeFlow.emit(anime)
    _selectedAnimeFlow.emit(anime)
} else {
    _matchedAnimeFlow.emit(null)
    _selectedAnimeFlow.emit(null)
}
```

- [ ] **Step 3: Verify scope**

Do not clear `_searchedAnimeFlow`. Search results are non-goal for this fix.

---

### Task 2: Fix network URL fallback and cancellation propagation

**Files:**
- Modify: `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/source/impl/NetworkDanmuSource.kt`

- [ ] **Step 1: Add cancellation import**

Add import with existing coroutine imports:

```kotlin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
```

- [ ] **Step 2: Make `URI(url)` fallback safe**

Replace `fileName()` with:

```kotlin
override suspend fun fileName(): String? {
    val segment = Uri.parse(url)?.lastPathSegment
        ?: runCatching { URI(url).path?.substringAfterLast("/") }.getOrNull()
        ?: return null
    return URLDecoder.decode(segment, "UTF-8")
        .substringBeforeLast(".")
        .ifBlank { null }
}
```

- [ ] **Step 3: Re-throw cancellation in retry loop**

In `retryWithBackoff()`, add this catch before `catch (e: Exception)`:

```kotlin
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    // 其他异常直接返回null，不进行重试
    return null
}
```

- [ ] **Step 4: Verify catch order**

Ensure `CancellationException` branch is before generic `Exception`, otherwise cancellation is still swallowed.

---

### Task 3: Fix unsafe exact-match cache write

**Files:**
- Modify: `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/DanmuMatchCoordinator.kt`

- [ ] **Step 1: Guard success cache write**

Replace:

```kotlin
if (fileSize > 0 && result.isMatched && result.episodes.isNotEmpty()) {
    danmuCache.putSuccess(hash, fileSize, result.episodes.first().toCachedMatch())
}
```

with:

```kotlin
if (fileSize > 0 && result.isMatched && result.episodes.isNotEmpty()) {
    result.episodes.first().toCachedMatch()?.let {
        danmuCache.putSuccess(hash, fileSize, it)
    }
}
```

- [ ] **Step 2: Make mapper nullable**

Replace:

```kotlin
private fun DanmuEpisodeData.toCachedMatch(): CachedMatch = CachedMatch(
    episodeId = episodeId.toLongOrNull() ?: 0L,
    animeId = animeId.toLong(),
    animeTitle = animeTitle,
    episodeTitle = episodeTitle,
    shift = shift,
    cachedAt = System.currentTimeMillis(),
)
```

with:

```kotlin
private fun DanmuEpisodeData.toCachedMatch(): CachedMatch? {
    val cachedEpisodeId = episodeId.toLongOrNull() ?: return null
    return CachedMatch(
        episodeId = cachedEpisodeId,
        animeId = animeId.toLong(),
        animeTitle = animeTitle,
        episodeTitle = episodeTitle,
        shift = shift,
        cachedAt = System.currentTimeMillis(),
    )
}
```

- [ ] **Step 3: Verify behavior remains unchanged except cache write**

Exact match with non-numeric `episodeId` must still call `downloadEpisode()` and return `MatchOutcome.Success` if download succeeds.

---

### Task 4: Extend coordinator unit tests

**Files:**
- Modify: `core_storage_component/src/test/java/com/xyoye/common_component/utils/danmu/DanmuMatchCoordinatorTest.kt`

- [ ] **Step 1: Add import for LocalFile assertion**

Add:

```kotlin
import com.xyoye.data_component.bean.DanmuTrackResource
```

- [ ] **Step 2: Add exact-match success test**

Add after `coordinatorReturnsFuzzyCandidatesWithoutDownloading()`:

```kotlin
@Test
fun coordinatorDownloadsExactMatch() = runBlocking {
    val file = createVideoFile("Exact 01.mkv")
    val episode = DanmuEpisodeData(episodeId = "101", animeTitle = "Anime", shift = 1.5)
    val finder = FakeDanmuFinder()
    val query = FakeDanmuQuery(MatchResult(true, listOf(episode), MatchMethod.HASH))
    val cache = FakeDanmuMatchCache()
    val coordinator = DanmuMatchCoordinator(finder, query, cache)

    val outcome = coordinator.match(FakeVideoSource(url = file.path.replace('\\', '/')), DanmuMatchCoordinator.MatchMode.AUTO)

    assertTrue(outcome is MatchOutcome.Success)
    val success = outcome as MatchOutcome.Success
    assertTrue(success.resource is DanmuTrackResource.LocalFile)
    assertEquals(MatchMethod.HASH, success.matchMethod)
    assertEquals(1.5, success.shift, 0.0)
    assertEquals(1, finder.downloadCount)
    assertEquals("101", finder.lastDownloadedEpisode?.episodeId)
    assertEquals(1, cache.putSuccessCount)
    assertEquals(101L, cache.lastSuccess?.episodeId)
}
```

- [ ] **Step 3: Add success cache hit test**

Add:

```kotlin
@Test
fun coordinatorUsesSuccessCacheBeforeQuery() = runBlocking {
    val file = createVideoFile("Cached 01.mkv")
    val finder = FakeDanmuFinder()
    val query = FakeDanmuQuery(MatchResult(false, emptyList(), MatchMethod.FILE_NAME))
    val cache = FakeDanmuMatchCache(
        success = CachedMatch(
            episodeId = 202L,
            animeId = 303L,
            animeTitle = "Cached Anime",
            episodeTitle = "Cached Episode",
            shift = 2.0,
            cachedAt = System.currentTimeMillis(),
        )
    )
    val coordinator = DanmuMatchCoordinator(finder, query, cache)

    val outcome = coordinator.match(FakeVideoSource(url = file.path.replace('\\', '/')), DanmuMatchCoordinator.MatchMode.AUTO)

    assertTrue(outcome is MatchOutcome.Success)
    val success = outcome as MatchOutcome.Success
    assertEquals(MatchMethod.HASH, success.matchMethod)
    assertEquals(2.0, success.shift, 0.0)
    assertEquals(1, finder.downloadCount)
    assertEquals("202", finder.lastDownloadedEpisode?.episodeId)
    assertEquals(0, query.matchCount)
}
```

- [ ] **Step 4: Add recent fail marker test**

Add:

```kotlin
@Test
fun coordinatorReturnsNotFoundWhenRecentFailExists() = runBlocking {
    val file = createVideoFile("Failed 01.mkv")
    val finder = FakeDanmuFinder()
    val query = FakeDanmuQuery(MatchResult(true, listOf(DanmuEpisodeData(episodeId = "301")), MatchMethod.HASH))
    val cache = FakeDanmuMatchCache(hasFail = true)
    val coordinator = DanmuMatchCoordinator(finder, query, cache)

    val outcome = coordinator.match(FakeVideoSource(url = file.path.replace('\\', '/')), DanmuMatchCoordinator.MatchMode.AUTO)

    assertEquals(MatchOutcome.NotFound, outcome)
    assertEquals(0, query.matchCount)
    assertEquals(0, finder.downloadCount)
}
```

- [ ] **Step 5: Add empty exact-result fail marker test**

Add:

```kotlin
@Test
fun coordinatorStoresFailWhenMatchReturnsEmptyEpisodes() = runBlocking {
    val file = createVideoFile("Empty 01.mkv")
    val finder = FakeDanmuFinder()
    val query = FakeDanmuQuery(MatchResult(true, emptyList(), MatchMethod.HASH))
    val cache = FakeDanmuMatchCache()
    val coordinator = DanmuMatchCoordinator(finder, query, cache)

    val outcome = coordinator.match(FakeVideoSource(url = file.path.replace('\\', '/')), DanmuMatchCoordinator.MatchMode.AUTO)

    assertEquals(MatchOutcome.NotFound, outcome)
    assertEquals(1, query.matchCount)
    assertEquals(1, cache.putFailCount)
    assertEquals(0, cache.putSuccessCount)
    assertEquals(0, finder.downloadCount)
}
```

- [ ] **Step 6: Add non-numeric episode ID cache safety test**

Add:

```kotlin
@Test
fun coordinatorSkipsSuccessCacheWhenEpisodeIdIsNotNumeric() = runBlocking {
    val file = createVideoFile("Non Numeric 01.mkv")
    val episode = DanmuEpisodeData(episodeId = "episode-abc", animeTitle = "Anime")
    val finder = FakeDanmuFinder()
    val query = FakeDanmuQuery(MatchResult(true, listOf(episode), MatchMethod.HASH))
    val cache = FakeDanmuMatchCache()
    val coordinator = DanmuMatchCoordinator(finder, query, cache)

    val outcome = coordinator.match(FakeVideoSource(url = file.path.replace('\\', '/')), DanmuMatchCoordinator.MatchMode.AUTO)

    assertTrue(outcome is MatchOutcome.Success)
    assertEquals(1, finder.downloadCount)
    assertEquals("episode-abc", finder.lastDownloadedEpisode?.episodeId)
    assertEquals(0, cache.putSuccessCount)
    assertEquals(0, cache.putFailCount)
}
```

- [ ] **Step 7: Add temp file helper**

Add helper inside `DanmuMatchCoordinatorTest` before fake classes:

```kotlin
private fun createVideoFile(name: String): File {
    val dir = File(System.getProperty("java.io.tmpdir"), "danmu-match-${System.nanoTime()}")
    val file = File(dir, name)
    dir.mkdirs()
    file.writeText("video")
    return file
}
```

- [ ] **Step 8: Update existing fuzzy test to use helper**

Replace:

```kotlin
val dir = File("/tmp/danmu-match-${System.nanoTime()}")
val file = File(dir, "Episode 01.mkv")
dir.mkdirs()
file.writeText("video")
```

with:

```kotlin
val file = createVideoFile("Episode 01.mkv")
```

This is allowed because it directly supports added tests and removes Windows-specific fragility in touched test setup.

- [ ] **Step 9: Enhance fake finder**

Replace `FakeDanmuFinder` fields/method body pieces with:

```kotlin
private class FakeDanmuFinder : DanmuFinder {
    var downloadCount = 0
    var lastDownloadedEpisode: DanmuEpisodeData? = null

    override suspend fun getMatched(source: com.xyoye.common_component.utils.danmu.source.DanmuSource): MatchResult? = null

    override suspend fun downloadMatched(source: com.xyoye.common_component.utils.danmu.source.DanmuSource): LocalDanmuBean? = null

    override suspend fun downloadEpisode(
        episode: DanmuEpisodeData,
        withRelated: Boolean
    ): LocalDanmuBean? {
        downloadCount++
        lastDownloadedEpisode = episode
        return LocalDanmuBean("/tmp/danmu.xml", episode.episodeId)
    }

    override suspend fun downloadRelated(
        episode: DanmuEpisodeData,
        related: List<DanmuRelatedUrlData>
    ): LocalDanmuBean? = null

    override suspend fun search(text: String): List<DanmuAnimeData> = emptyList()

    override suspend fun getRelated(episodeId: String): List<DanmuRelatedUrlData> = emptyList()

    override suspend fun saveStream(
        episode: DanmuEpisodeData,
        inputStream: java.io.InputStream
    ): LocalDanmuBean? = null
}
```

- [ ] **Step 10: Enhance fake query**

Add `matchCount` and increment in `match()`:

```kotlin
private class FakeDanmuQuery(
    private val result: MatchResult
) : DanmuQuery {
    var hash: String? = null
    var fileName: String? = null
    var fileSize: Long? = null
    var matchCount = 0

    override suspend fun match(
        hash: String,
        fileName: String?,
        fileSize: Long,
        videoDuration: Int
    ): MatchResult {
        matchCount++
        this.hash = hash
        this.fileName = fileName
        this.fileSize = fileSize
        return result
    }

    override suspend fun search(text: String): List<DanmuAnimeData> = emptyList()

    override suspend fun source(episodeId: String): List<DanmuRelatedUrlData> = emptyList()

    override suspend fun getContentByEpisodeId(
        episodeId: String,
        withRelated: Boolean
    ): List<DanmuContentData> = emptyList()

    override suspend fun getContentByUrl(url: String): List<DanmuContentData> = emptyList()
}
```

- [ ] **Step 11: Enhance fake cache**

Replace `FakeDanmuMatchCache` with:

```kotlin
private class FakeDanmuMatchCache(
    private val success: CachedMatch? = null,
    private val hasFail: Boolean = false,
) : DanmuMatchCacheStore {
    var putSuccessCount = 0
    var putFailCount = 0
    var lastSuccess: CachedMatch? = null

    override fun getSuccess(hash: String, fileSize: Long): CachedMatch? = success


    override fun putSuccess(hash: String, fileSize: Long, match: CachedMatch) {
        putSuccessCount++
        lastSuccess = match
    }

    override fun hasRecentFail(hash: String, fileSize: Long): Boolean = hasFail

    override fun putFail(hash: String, fileSize: Long) {
        putFailCount++
    }
}
```

- [ ] **Step 12: Keep hash-null fallback out of this task**

Do not add hash-null fallback test unless no production/fake restructuring is needed. Spec permits skipping it to avoid over-complex tests.

---

### Task 5: Run focused tests and fix compile issues

**Files:**
- Verify: `core_storage_component/src/test/java/com/xyoye/common_component/utils/danmu/DanmuMatchCoordinatorTest.kt`
- Verify: `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/DanmuMatchCoordinator.kt`
- Verify: `core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/source/impl/NetworkDanmuSource.kt`

- [ ] **Step 1: Run focused unit test task**

On Windows shell tool, run from repo root:

```powershell
cmd /c pwsh -NoProfile -Command './gradlew :core_storage_component:testDebugUnitTest'
```

Expected tail contains:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 2: If task is unavailable, run full debug unit tests**

Only if Step 1 fails because task name/module task is unavailable, run:

```powershell
cmd /c pwsh -NoProfile -Command './gradlew testDebugUnitTest'
```

Expected tail contains:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 3: Fix compile/test failures only in touched scope**

Allowed fixes:

```kotlin
// import order or missing import fixes in test/source files
import com.xyoye.data_component.bean.DanmuTrackResource
import kotlinx.coroutines.CancellationException
```

Not allowed in this task:

```kotlin
// Do not change public MatchOutcome.Candidates shape.
data class Candidates(
    val episodes: List<DanmuEpisodeData>,
    val matchMethod: MatchMethod,
) : MatchOutcome()
```

---

### Task 6: Lints and final diff review

**Files:**
- Verify: all touched files

- [ ] **Step 1: Read IDE diagnostics**

Use ReadLints for:

```text
local_component/src/main/java/com/xyoye/local_component/ui/fragment/bind_danmu/BindDanmuSourceFragmentViewModel.kt
core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/source/impl/NetworkDanmuSource.kt
core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/DanmuMatchCoordinator.kt
core_storage_component/src/test/java/com/xyoye/common_component/utils/danmu/DanmuMatchCoordinatorTest.kt
```

Expected: no new diagnostics introduced by this change.

- [ ] **Step 2: Review git diff**

Run:

```powershell
cmd /c pwsh -NoProfile -Command 'git diff -- local_component/src/main/java/com/xyoye/local_component/ui/fragment/bind_danmu/BindDanmuSourceFragmentViewModel.kt core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/source/impl/NetworkDanmuSource.kt core_storage_component/src/main/java/com/xyoye/common_component/utils/danmu/DanmuMatchCoordinator.kt core_storage_component/src/test/java/com/xyoye/common_component/utils/danmu/DanmuMatchCoordinatorTest.kt docs/superpowers/specs/2026-06-13-danmu-match-fixes-design.md docs/superpowers/plans/2026-06-13-danmu-match-fixes.md'
```

Expected: diff only contains planned fixes/spec/plan. No `TvVideoController`, `PlayerAction`, or unrelated files.

- [ ] **Step 3: Report verification exactly**

Final report must include:

```text
BUILD SUCCESSFUL
```

or

```text
BUILD FAILED
```

copied from Gradle output tail.

Do not claim tests passed unless Gradle tail was checked.

---

## Self-Review

- Spec coverage: all four important fixes map to Tasks 1-3; coordinator branch tests map to Task 4; verification maps to Tasks 5-6.
- Placeholder scan: no placeholder markers or fill-later text.
- Type consistency: uses existing `MatchOutcome.Success`, `DanmuTrackResource.LocalFile`, `CachedMatch`, `DanmuMatchCacheStore`, `DanmuQuery`, `DanmuFinder`, and `DanmuEpisodeData` names.
- Scope check: no changes to `MatchOutcome.Candidates`, `SEARCH_FALLBACK`, `DanmuHashCalculator`, `AbstractDanmuSource`, or subtitle controller code.
