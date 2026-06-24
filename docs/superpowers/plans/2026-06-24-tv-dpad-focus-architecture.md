# TV DPAD Focus Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a shared TV DPAD focus skeleton, migrate P0/P1 focus flows, and add verification so TV focus remains visible, reachable, restorable, and returnable.

**Architecture:** Add focus protocols/controllers in `:core_ui_component`, wire dialog and TV shell boundaries, then migrate P0/P1 pages one at a time. Keep old `RecyclerViewFocusDelegate` available during migration; do not introduce global focus stealing or mobile/touch behavior changes.

**Tech Stack:** Kotlin 1.9.25, Android XML Views, RecyclerView, Fragment, BottomSheetDialog/AppCompat Dialogs, AndroidX Test, Gradle 8.9.

---

## File Structure

- Create: `core_ui_component/src/main/java/com/xyoye/common_component/focus/DpadDirection.kt`
  - Responsibility: common DPAD direction enum and key-code mapping helper.
- Create: `core_ui_component/src/main/java/com/xyoye/common_component/focus/DpadFocusOwner.kt`
  - Responsibility: page/dialog/overlay focus ownership protocol.
- Create: `core_ui_component/src/main/java/com/xyoye/common_component/focus/DpadFocusScope.kt`
  - Responsibility: save/restore/default focus for one focus domain.
- Create: `core_ui_component/src/main/java/com/xyoye/common_component/focus/DpadRecyclerFocusDelegate.kt`
  - Responsibility: shared vertical/horizontal/grid RecyclerView DPAD navigation and focus restore.
- Create: `core_ui_component/src/main/java/com/xyoye/common_component/focus/DpadDialogFocusController.kt`
  - Responsibility: dialog show/dismiss focus capture and restore.
- Create: `app/src/main/java/com/okamihoro/ddplaytv/ui/tv/TvShellFocusCoordinator.kt`
  - Responsibility: TV shell left-nav/content focus domain coordination.
- Modify: `core_ui_component/src/main/java/com/xyoye/common_component/weight/dialog/BaseBottomDialog.kt`
  - Responsibility: integrate dialog focus controller for bottom-sheet dialogs.
- Modify: `core_ui_component/src/main/java/com/xyoye/common_component/weight/BottomActionDialog.kt`
  - Responsibility: install dialog list focus behavior and first-item focus.
- Verify: `core_ui_component/src/main/java/com/xyoye/common_component/weight/dialog/CommonDialog.kt`
  - Responsibility: confirm `CommonDialog` inherits `BaseBottomDialog` focus restore without extra code.
- Modify: `app/src/main/java/com/okamihoro/ddplaytv/ui/tv/TvMainActivity.kt`
  - Responsibility: wire `TvShellFocusCoordinator`.
- Inspect: `app/src/main/java/com/okamihoro/ddplaytv/ui/shell/ShellFragmentSwitcher.kt`
  - Responsibility: keep fragment switching behavior unchanged; do not modify unless `TvMainActivity` cannot obtain current fragment by container id.
- Modify: `local_component/src/main/java/com/xyoye/local_component/ui/fragment/media/MediaFragment.kt`
  - Responsibility: migrate existing media-list focus behavior to new owner/delegate.
- Modify: `storage_component/src/main/java/com/xyoye/storage_component/ui/fragment/storage_file/StorageFileFragment.kt`
  - Responsibility: migrate unique-key storage-list focus behavior to new owner/delegate.
- Modify: `anime_component/src/main/java/com/xyoye/anime_component/ui/fragment/home/HomeFragment.kt`
  - Responsibility: bridge tab/content boundaries with focused page owner.
- Modify: `anime_component/src/main/java/com/xyoye/anime_component/ui/fragment/home_page/HomePageFragment.kt`
  - Responsibility: add grid focus owner/delegate.
- Modify: `player_component/src/main/java/com/xyoye/player/controller/setting/PlayerSettingView.kt`
  - Responsibility: first player overlay migration to owner/delegate pattern.
- Test: `app/src/androidTest/java/com/okamihoro/ddplaytv/ui/tv/TvMainFocusSmokeTest.kt`
  - Responsibility: TV shell DPAD focus smoke coverage.
- Test: `app/src/androidTest/java/com/xyoye/app/quality/DialogDpadFocusSmokeTest.kt`
  - Responsibility: bottom-action dialog show/dismiss focus smoke coverage.
- Test: existing `app/src/androidTest/java/com/okamihoro/ddplaytv/ui/setting/PlayerSettingDpadNavigationTest.kt`
  - Responsibility: keep current settings-tab regression covered without expanding it in this plan.

No commits in this plan unless user explicitly asks. Repo rule forbids proactive commits.

---

### Task 1: Add core focus protocol files

**Files:**
- Create: `core_ui_component/src/main/java/com/xyoye/common_component/focus/DpadDirection.kt`
- Create: `core_ui_component/src/main/java/com/xyoye/common_component/focus/DpadFocusOwner.kt`

- [ ] **Step 1: Create `DpadDirection.kt`**

Add:

```kotlin
package com.xyoye.common_component.focus

import android.view.KeyEvent

enum class DpadDirection {
    Up,
    Down,
    Left,
    Right,
}

fun Int.toDpadDirectionOrNull(): DpadDirection? =
    when (this) {
        KeyEvent.KEYCODE_DPAD_UP -> DpadDirection.Up
        KeyEvent.KEYCODE_DPAD_DOWN -> DpadDirection.Down
        KeyEvent.KEYCODE_DPAD_LEFT -> DpadDirection.Left
        KeyEvent.KEYCODE_DPAD_RIGHT -> DpadDirection.Right
        else -> null
    }
```

- [ ] **Step 2: Create `DpadFocusOwner.kt`**

Add:

```kotlin
package com.xyoye.common_component.focus

interface DpadFocusOwner {
    fun requestDefaultDpadFocus(): Boolean = false

    fun saveDpadFocus() {
    }

    fun restoreDpadFocus(): Boolean = false

    fun onDpadFocusBoundary(direction: DpadDirection): Boolean = false
}
```

- [ ] **Step 3: Run focused compile check**

Run:

```powershell
./gradlew :core_ui_component:compileDebugKotlin
```

Expected: task completes without Kotlin compile errors. If Gradle task name is unavailable for this module, run `./gradlew assembleDebug` and inspect tail.

---

### Task 2: Add `DpadFocusScope`

**Files:**
- Create: `core_ui_component/src/main/java/com/xyoye/common_component/focus/DpadFocusScope.kt`

- [ ] **Step 1: Create focus scope helper**

Add:

```kotlin
package com.xyoye.common_component.focus

import android.view.View
import java.lang.ref.WeakReference

class DpadFocusScope(
    private val isEnabled: () -> Boolean,
    private val defaultFocusProvider: () -> View?,
) {
    private var lastFocusedRef: WeakReference<View>? = null

    fun capture(view: View?) {
        if (!isEnabled()) return
        if (view == null) return
        if (!view.isShown || !view.isAttachedToWindow) return
        lastFocusedRef = WeakReference(view)
    }

    fun captureFrom(root: View?) {
        if (!isEnabled()) return
        capture(root?.findFocus())
    }

    fun restore(): Boolean {
        if (!isEnabled()) return false
        val lastFocused = lastFocusedRef?.get()
        if (lastFocused != null && lastFocused.isShown && lastFocused.isAttachedToWindow && lastFocused.isFocusable) {
            if (lastFocused.requestFocus()) return true
        }
        val defaultFocus = defaultFocusProvider()
        if (defaultFocus != null && defaultFocus.isShown && defaultFocus.isAttachedToWindow && defaultFocus.isFocusable) {
            return defaultFocus.requestFocus()
        }
        return false
    }

    fun clear() {
        lastFocusedRef = null
    }
}
```

- [ ] **Step 2: Check no broad lifecycle coupling**

Ensure this class does not import Activity, Fragment, RecyclerView, or any feature module class.

- [ ] **Step 3: Compile**

Run:

```powershell
./gradlew :core_ui_component:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL` or successful module compile tail.

---

### Task 3: Add `DpadRecyclerFocusDelegate`

**Files:**
- Create: `core_ui_component/src/main/java/com/xyoye/common_component/focus/DpadRecyclerFocusDelegate.kt`

- [ ] **Step 1: Create state and orientation types**

Add the file with these top-level declarations:

```kotlin
package com.xyoye.common_component.focus

import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xyoye.common_component.adapter.BaseAdapter
import com.xyoye.common_component.extension.FocusTarget
import com.xyoye.common_component.extension.requestIndexChildFocus
import com.xyoye.common_component.extension.toResString
import com.xyoye.core_ui_component.R

enum class DpadRecyclerOrientation {
    Vertical,
    Horizontal,
    Grid,
}

data class DpadRecyclerFocusState(
    val index: Int = RecyclerView.NO_POSITION,
    val uniqueKey: String? = null,
)
```

- [ ] **Step 2: Add constructor and public state**

Add class skeleton:

```kotlin
class DpadRecyclerFocusDelegate(
    private val recyclerView: RecyclerView,
    private val orientation: DpadRecyclerOrientation,
    private val spanCountProvider: (() -> Int)? = null,
    private val uniqueKeyProvider: ((Any) -> String?)? = null,
    private val focusTargetProvider: (RecyclerView) -> FocusTarget = { defaultFocusTarget() },
    private val boundaryHandler: ((DpadDirection) -> Boolean)? = null,
    private val fallbackFocusProvider: (() -> View?)? = null,
    private val extraKeyHandler: ((Int, KeyEvent) -> Boolean)? = null,
) {
    private var lastFocusState = DpadRecyclerFocusState()
    private var pendingFocusState = DpadRecyclerFocusState()

    fun install() {
        recyclerView.setOnKeyListener { _, keyCode, event ->
            if (event?.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (recyclerView.isInTouchMode) return@setOnKeyListener false
            val direction = keyCode.toDpadDirectionOrNull()
            if (direction != null) {
                return@setOnKeyListener moveFocus(direction)
            }
            extraKeyHandler?.invoke(keyCode, event) == true
        }
    }

    fun onResume() {
        if (lastFocusState.index != RecyclerView.NO_POSITION || lastFocusState.uniqueKey != null) {
            pendingFocusState = lastFocusState
        }
    }

    fun onPause() {
        saveFocus()
    }

    fun saveFocus() {
        lastFocusState = captureFocus()
        if (lastFocusState.index != RecyclerView.NO_POSITION || lastFocusState.uniqueKey != null) {
            pendingFocusState = lastFocusState
        }
    }

    fun captureFocus(): DpadRecyclerFocusState {
        val focusedChild = recyclerView.focusedChild ?: return DpadRecyclerFocusState()
        val index = recyclerView.getChildAdapterPosition(focusedChild)
        if (index == RecyclerView.NO_POSITION) return DpadRecyclerFocusState()
        return DpadRecyclerFocusState(index = index, uniqueKey = uniqueKeyAt(index))
    }

    fun setPendingFocus(index: Int = RecyclerView.NO_POSITION, uniqueKey: String? = null) {
        pendingFocusState = DpadRecyclerFocusState(index, uniqueKey)
    }

    fun clearPendingFocus() {
        pendingFocusState = DpadRecyclerFocusState()
    }

    fun requestDefaultFocus(reversed: Boolean = false): Boolean {
        val count = recyclerView.adapter?.itemCount ?: return fallbackFocus()
        if (count <= 0) return fallbackFocus()
        val index = if (reversed) count - 1 else 0
        return requestFocusAt(index) || fallbackFocus()
    }

    fun restoreFocus(reversed: Boolean = false): Boolean {
        val count = recyclerView.adapter?.itemCount ?: return fallbackFocus()
        if (count <= 0) return fallbackFocus()
        val desired = resolveIndex(emptyList(), count, pendingFocusState, reversed)
        clearPendingFocus()
        return requestFocusAt(desired) || fallbackFocus()
    }

    fun restoreAfterDataChanged(items: List<Any>, state: DpadRecyclerFocusState = pendingFocusState): Boolean {
        val count = recyclerView.adapter?.itemCount ?: return fallbackFocus()
        if (count <= 0) return fallbackFocus()
        val index = resolveIndex(items, count, state, reversed = false)
        pendingFocusState = DpadRecyclerFocusState(index, state.uniqueKey)
        return recyclerView.postRequestFocusAt(index)
    }
```

- [ ] **Step 3: Add movement helpers**

Append inside class:

```kotlin
    private fun moveFocus(direction: DpadDirection): Boolean {
        val count = recyclerView.adapter?.itemCount ?: return false
        if (count <= 0) return false
        val current = currentIndex(count)
        val target = targetIndex(current, count, direction)
        if (target == RecyclerView.NO_POSITION) {
            return boundaryHandler?.invoke(direction) == true
        }
        return requestFocusAt(target)
    }

    private fun currentIndex(count: Int): Int {
        val focusedChild = recyclerView.focusedChild
        val focusedIndex = if (focusedChild != null) recyclerView.getChildAdapterPosition(focusedChild) else RecyclerView.NO_POSITION
        if (focusedIndex in 0 until count) return focusedIndex
        if (lastFocusState.index in 0 until count) return lastFocusState.index
        if (pendingFocusState.index in 0 until count) return pendingFocusState.index
        return 0
    }

    private fun targetIndex(current: Int, count: Int, direction: DpadDirection): Int {
        val span = spanCount().coerceAtLeast(1)
        val target =
            when (orientation) {
                DpadRecyclerOrientation.Vertical -> when (direction) {
                    DpadDirection.Up -> current - 1
                    DpadDirection.Down -> current + 1
                    DpadDirection.Left,
                    DpadDirection.Right -> return RecyclerView.NO_POSITION
                }
                DpadRecyclerOrientation.Horizontal -> when (direction) {
                    DpadDirection.Left -> current - 1
                    DpadDirection.Right -> current + 1
                    DpadDirection.Up,
                    DpadDirection.Down -> return RecyclerView.NO_POSITION
                }
                DpadRecyclerOrientation.Grid -> when (direction) {
                    DpadDirection.Left -> if (current % span == 0) RecyclerView.NO_POSITION else current - 1
                    DpadDirection.Right -> if (current % span == span - 1) RecyclerView.NO_POSITION else current + 1
                    DpadDirection.Up -> current - span
                    DpadDirection.Down -> current + span
                }
            }
        return if (target in 0 until count) target else RecyclerView.NO_POSITION
    }

    private fun spanCount(): Int {
        spanCountProvider?.invoke()?.let { return it }
        return (recyclerView.layoutManager as? GridLayoutManager)?.spanCount ?: 1
    }
```

- [ ] **Step 4: Add restore helpers**

Append inside class:

```kotlin
    private fun resolveIndex(
        items: List<Any>,
        count: Int,
        state: DpadRecyclerFocusState,
        reversed: Boolean,
    ): Int {
        val key = state.uniqueKey
        if (!key.isNullOrEmpty() && uniqueKeyProvider != null && items.isNotEmpty()) {
            val keyIndex = items.indexOfFirst { uniqueKeyProvider.invoke(it) == key }
            if (keyIndex in 0 until count) return keyIndex
        }
        if (state.index in 0 until count) return state.index
        return if (reversed) count - 1 else 0
    }

    private fun requestFocusAt(index: Int): Boolean {
        val moved = recyclerView.requestIndexChildFocus(index, focusTargetProvider(recyclerView))
        if (moved) {
            lastFocusState = DpadRecyclerFocusState(index, uniqueKeyAt(index))
            clearPendingFocus()
        }
        return moved
    }

    private fun RecyclerView.postRequestFocusAt(index: Int): Boolean {
        post { requestFocusAt(index) }
        return true
    }

    private fun fallbackFocus(): Boolean {
        val fallback = fallbackFocusProvider?.invoke() ?: return false
        if (!fallback.isShown || !fallback.isAttachedToWindow || !fallback.isFocusable) return false
        return fallback.requestFocus()
    }

    private fun uniqueKeyAt(index: Int): String? {
        val provider = uniqueKeyProvider ?: return null
        val adapter = recyclerView.adapter as? BaseAdapter ?: return null
        val item = adapter.items.getOrNull(index) ?: return null
        return provider.invoke(item)
    }

    companion object {
        fun defaultFocusTarget(): FocusTarget = FocusTarget.Tag(R.string.focusable_item.toResString())
    }
}
```

- [ ] **Step 5: Compile and adjust only compile errors**

Run:

```powershell
./gradlew :core_ui_component:compileDebugKotlin
```

Expected: compile succeeds. If import/package names differ, fix imports only; do not change behavior scope.

---

### Task 4: Add `DpadDialogFocusController`

**Files:**
- Create: `core_ui_component/src/main/java/com/xyoye/common_component/focus/DpadDialogFocusController.kt`

- [ ] **Step 1: Create controller file**

Add:

```kotlin
package com.xyoye.common_component.focus

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.core.view.children
import com.xyoye.common_component.extension.isTelevisionUiMode
import com.xyoye.common_component.extension.toResString
import com.xyoye.core_ui_component.R
import java.lang.ref.WeakReference

class DpadDialogFocusController(
    private val activity: Activity,
    private val rootProvider: () -> View?,
    private val fallbackProvider: (() -> View?)? = null,
) {
    private var hostFocusRef: WeakReference<View>? = null

    fun captureHostFocus() {
        if (!isEnabled()) return
        val focus = activity.currentFocus ?: return
        if (focus.isShown && focus.isAttachedToWindow) {
            hostFocusRef = WeakReference(focus)
        }
    }

    fun requestDialogFocus() {
        if (!isEnabled()) return
        val root = rootProvider() ?: return
        root.post {
            findDialogFocusTarget(root)?.requestFocus()
        }
    }

    fun restoreHostFocus() {
        if (!isEnabled()) return
        val hostFocus = hostFocusRef?.get()
        val restored = hostFocus != null && hostFocus.isShown && hostFocus.isAttachedToWindow && hostFocus.isFocusable && hostFocus.requestFocus()
        if (!restored) {
            fallbackProvider?.invoke()?.takeIf { it.isShown && it.isAttachedToWindow && it.isFocusable }?.requestFocus()
        }
    }

    private fun isEnabled(): Boolean = activity.isTelevisionUiMode() && activity.currentFocus?.isInTouchMode != true

    private fun findDialogFocusTarget(root: View): View? {
        val tag = R.string.focusable_item.toResString()
        root.findViewWithTag<View>(tag)?.let { target ->
            if (target.isShown && target.isFocusable) return target
        }
        return findFirstFocusable(root)
    }

    private fun findFirstFocusable(view: View): View? {
        if (view.isShown && view.isFocusable) return view
        val group = view as? ViewGroup ?: return null
        group.children.forEach { child ->
            val found = findFirstFocusable(child)
            if (found != null) return found
        }
        return null
    }
}
```

- [ ] **Step 2: Compile**

Run:

```powershell
./gradlew :core_ui_component:compileDebugKotlin
```

Expected: compile succeeds. If `children` extension import conflicts, use explicit child loop.

---

### Task 5: Integrate bottom dialog focus restore

**Files:**
- Modify: `core_ui_component/src/main/java/com/xyoye/common_component/weight/dialog/BaseBottomDialog.kt`
- Modify: `core_ui_component/src/main/java/com/xyoye/common_component/weight/BottomActionDialog.kt`

- [ ] **Step 1: Add controller property to `BaseBottomDialog`**

Add import:

```kotlin
import com.xyoye.common_component.focus.DpadDialogFocusController
```

Add property near `rootViewBinding`:

```kotlin
private val focusController by lazy(LazyThreadSafetyMode.NONE) {
    DpadDialogFocusController(
        activity = activity,
        rootProvider = { rootViewBinding.root },
    )
}
```

If constructor parameter is unnamed today, change constructor to store it:

```kotlin
abstract class BaseBottomDialog<T : ViewDataBinding>(
    private val activity: Activity
) : BottomSheetDialog(activity, R.style.Bottom_Sheet_Dialog) {
```

- [ ] **Step 2: Capture before show and restore on dismiss**

Add overrides:

```kotlin
override fun show() {
    focusController.captureHostFocus()
    super.show()
    focusController.requestDialogFocus()
}

override fun dismiss() {
    super.dismiss()
    window?.decorView?.post {
        focusController.restoreHostFocus()
    }
}
```

If `window` is null after dismiss, use `rootViewBinding.root.post { focusController.restoreHostFocus() }` before `super.dismiss()`:

```kotlin
override fun dismiss() {
    val root = if (::rootViewBinding.isInitialized) rootViewBinding.root else null
    super.dismiss()
    root?.post { focusController.restoreHostFocus() }
}
```

- [ ] **Step 3: Install dialog action list delegate in `BottomActionDialog`**

Add imports:

```kotlin
import com.xyoye.common_component.focus.DpadRecyclerFocusDelegate
import com.xyoye.common_component.focus.DpadRecyclerOrientation
```

Inside `binding.contentRv.apply { ... }`, after `setData(actionData)`, add:

```kotlin
if (!isInTouchMode) {
    DpadRecyclerFocusDelegate(
        recyclerView = this,
        orientation = DpadRecyclerOrientation.Vertical,
    ).also { delegate ->
        delegate.install()
        post { delegate.requestDefaultFocus() }
    }
}
```

- [ ] **Step 4: Compile app**

Run:

```powershell
./gradlew assembleDebug
```

Expected: tail contains `BUILD SUCCESSFUL`.

---

### Task 6: Add TV shell focus coordinator

**Files:**
- Create: `app/src/main/java/com/okamihoro/ddplaytv/ui/tv/TvShellFocusCoordinator.kt`
- Modify: `app/src/main/java/com/okamihoro/ddplaytv/ui/tv/TvMainActivity.kt`

- [ ] **Step 1: Create coordinator**

Add:

```kotlin
package com.okamihoro.ddplaytv.ui.tv

import android.view.KeyEvent
import android.view.View
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.xyoye.common_component.extension.requestIndexChildFocus
import com.xyoye.common_component.focus.DpadDirection
import com.xyoye.common_component.focus.DpadFocusOwner
import com.xyoye.common_component.focus.DpadFocusScope

class TvShellFocusCoordinator(
    private val navRecyclerView: RecyclerView,
    private val contentRoot: View,
    private val selectedNavIndexProvider: () -> Int,
    private val currentFragmentProvider: () -> Fragment?,
) {
    private val navScope =
        DpadFocusScope(
            isEnabled = { !navRecyclerView.isInTouchMode },
            defaultFocusProvider = { navRecyclerView },
        )

    fun requestNavFocus(): Boolean {
        if (navRecyclerView.isInTouchMode) return false
        val index = selectedNavIndexProvider().coerceAtLeast(0)
        return navRecyclerView.requestIndexChildFocus(index)
    }

    fun requestContentFocus(): Boolean {
        if (contentRoot.isInTouchMode) return false
        val owner = currentFragmentProvider() as? DpadFocusOwner ?: return false
        return owner.restoreDpadFocus() || owner.requestDefaultDpadFocus()
    }

    fun saveContentFocus() {
        (currentFragmentProvider() as? DpadFocusOwner)?.saveDpadFocus()
    }

    fun onNavKeyDown(keyCode: Int): Boolean {
        if (keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false
        return requestContentFocus()
    }

    fun onContentBoundary(direction: DpadDirection): Boolean {
        if (direction != DpadDirection.Left) return false
        return requestNavFocus()
    }
}
```

- [ ] **Step 2: Wire property in `TvMainActivity`**

Add import:

```kotlin
import com.xyoye.common_component.focus.DpadFocusOwner
```

Add property:

```kotlin
private val focusCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    TvShellFocusCoordinator(
        navRecyclerView = dataBinding.tvNavRv,
        contentRoot = dataBinding.fragmentContainer,
        selectedNavIndexProvider = { navItems.indexOf(selectedSection) },
        currentFragmentProvider = { supportFragmentManager.findFragmentById(R.id.fragment_container) },
    )
}
```

- [ ] **Step 3: Replace startup focus request**

Replace:

```kotlin
dataBinding.tvNavRv.post {
    dataBinding.tvNavRv.requestIndexChildFocus(navItems.indexOf(selectedSection))
}
```

with:

```kotlin
dataBinding.tvNavRv.post {
    focusCoordinator.requestNavFocus()
}
```

- [ ] **Step 4: Add nav key bridge**

In `initNav()`, inside `dataBinding.tvNavRv.apply { ... }`, add:

```kotlin
setOnKeyListener { _, keyCode, event ->
    if (event?.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
    focusCoordinator.onNavKeyDown(keyCode)
}
```

- [ ] **Step 5: Save content focus before section switch**

At start of `handleNavClick()` section branch before changing `selectedSection`, add:

```kotlin
focusCoordinator.saveContentFocus()
```

- [ ] **Step 6: Compile app**

Run:

```powershell
./gradlew assembleDebug
```

Expected: tail contains `BUILD SUCCESSFUL`.

---

### Task 7: Migrate `MediaFragment` as sample focus owner

**Files:**
- Modify: `local_component/src/main/java/com/xyoye/local_component/ui/fragment/media/MediaFragment.kt`

- [ ] **Step 1: Replace old delegate imports**

Replace old delegate import:

```kotlin
import com.xyoye.common_component.focus.RecyclerViewFocusDelegate
```

with:

```kotlin
import com.xyoye.common_component.focus.DpadDirection
import com.xyoye.common_component.focus.DpadFocusOwner
import com.xyoye.common_component.focus.DpadRecyclerFocusDelegate
import com.xyoye.common_component.focus.DpadRecyclerOrientation
```

- [ ] **Step 2: Implement owner**

Change class declaration:

```kotlin
class MediaFragment : BaseFragment<MediaViewModel, FragmentMediaBinding>(), DpadFocusOwner {
```

- [ ] **Step 3: Replace delegate construction**

Replace `RecyclerViewFocusDelegate(...)` with:

```kotlin
DpadRecyclerFocusDelegate(
    recyclerView = dataBinding.mediaLibRv,
    orientation = DpadRecyclerOrientation.Vertical,
    uniqueKeyProvider = { item ->
        (item as? MediaLibraryEntity)?.let { "${it.mediaType.name}|${it.url}" }
    },
    boundaryHandler = { direction -> onDpadFocusBoundary(direction) },
    fallbackFocusProvider = { dataBinding.addMediaStorageBt },
)
```

- [ ] **Step 4: Update lifecycle calls**

Keep existing `focusDelegate.onResume()` and `focusDelegate.onPause()` if signatures match. If old `requestFocus()` calls remain, replace:

```kotlin
focusDelegate.requestFocus()
```

with:

```kotlin
focusDelegate.restoreFocus()
```

- [ ] **Step 5: Update install call**

Replace `focusDelegate.installVerticalDpadKeyNavigation(...)` with:

```kotlin
focusDelegate.install()
```

Keep menu/settings behavior out of the first migration unless current user flow breaks. `TvMainActivity` already maps MENU/SETTINGS to settings globally.

- [ ] **Step 6: Add owner methods**

Add near `requestDefaultFocusIfNeeded()`:

```kotlin
override fun requestDefaultDpadFocus(): Boolean = focusDelegate.requestDefaultFocus()

override fun saveDpadFocus() {
    focusDelegate.saveFocus()
}

override fun restoreDpadFocus(): Boolean = focusDelegate.restoreFocus()

override fun onDpadFocusBoundary(direction: DpadDirection): Boolean {
    return false
}
```

- [ ] **Step 7: Compile**

Run:

```powershell
./gradlew assembleDebug
```

Expected: tail contains `BUILD SUCCESSFUL`.

---

### Task 8: Migrate `StorageFileFragment`

**Files:**
- Modify: `storage_component/src/main/java/com/xyoye/storage_component/ui/fragment/storage_file/StorageFileFragment.kt`

- [ ] **Step 1: Replace imports**

Replace old delegate import:

```kotlin
import com.xyoye.common_component.focus.RecyclerViewFocusDelegate
```

with:

```kotlin
import com.xyoye.common_component.focus.DpadDirection
import com.xyoye.common_component.focus.DpadFocusOwner
import com.xyoye.common_component.focus.DpadRecyclerFocusDelegate
import com.xyoye.common_component.focus.DpadRecyclerOrientation
```

- [ ] **Step 2: Implement owner**

Change class declaration:

```kotlin
class StorageFileFragment : BaseFragment<StorageFileFragmentViewModel, FragmentStorageFileBinding>(), DpadFocusOwner {
```

- [ ] **Step 3: Replace delegate construction**

Use existing unique key and target logic:

```kotlin
private val focusDelegate by lazy(LazyThreadSafetyMode.NONE) {
    DpadRecyclerFocusDelegate(
        recyclerView = dataBinding.storageFileRv,
        orientation = DpadRecyclerOrientation.Vertical,
        uniqueKeyProvider = { item -> (item as? StorageFile)?.uniqueKey() },
        focusTargetProvider = { rv -> rv.resolveVerticalMoveFocusTarget() },
        boundaryHandler = { direction -> onDpadFocusBoundary(direction) },
    )
}
```

- [ ] **Step 4: Replace install call**

Replace existing `installVerticalDpadKeyNavigation(...)` with:

```kotlin
focusDelegate.install()
```

Preserve refresh shortcuts by handling MENU/SETTINGS at Fragment/Activity level only if existing behavior is lost during testing.

- [ ] **Step 5: Update request focus method**

Replace body of `requestFocus(reversed: Boolean = false)` with:

```kotlin
fun requestFocus(reversed: Boolean = false) {
    val binding = bindingOrNull ?: return
    if (binding.storageFileRv.isInTouchMode) {
        LogFacade.d(LogModule.STORAGE, TAG, "requestFocus skipped (touch mode)")
        return
    }
    val moved = focusDelegate.restoreFocus(reversed = reversed)
    LogFacade.d(LogModule.STORAGE, TAG, "requestFocus reversed=$reversed moved=$moved")
}
```

If `restoreFocus(reversed = ...)` signature differs after Task 3 implementation, add the parameter to the delegate rather than changing call sites.

- [ ] **Step 6: Add owner methods**

Add:

```kotlin
override fun requestDefaultDpadFocus(): Boolean = focusDelegate.requestDefaultFocus()

override fun saveDpadFocus() {
    focusDelegate.saveFocus()
}

override fun restoreDpadFocus(): Boolean = focusDelegate.restoreFocus()

override fun onDpadFocusBoundary(direction: DpadDirection): Boolean = false
```

- [ ] **Step 7: Compile**

Run:

```powershell
./gradlew assembleDebug
```

Expected: tail contains `BUILD SUCCESSFUL`.

---

### Task 9: Migrate Anime home grid focus

**Files:**
- Modify: `anime_component/src/main/java/com/xyoye/anime_component/ui/fragment/home_page/HomePageFragment.kt`
- Modify: `anime_component/src/main/java/com/xyoye/anime_component/ui/fragment/home/HomeFragment.kt`

- [ ] **Step 1: Add imports to `HomePageFragment`**

Add:

```kotlin
import com.xyoye.common_component.focus.DpadDirection
import com.xyoye.common_component.focus.DpadFocusOwner
import com.xyoye.common_component.focus.DpadRecyclerFocusDelegate
import com.xyoye.common_component.focus.DpadRecyclerOrientation
```

- [ ] **Step 2: Implement owner and delegate**

Change class declaration:

```kotlin
class HomePageFragment : BaseAppFragment<FragmentHomePageBinding>(), DpadFocusOwner {
```

Add property:

```kotlin
private var spanCount: Int = 1

private val focusDelegate by lazy(LazyThreadSafetyMode.NONE) {
    DpadRecyclerFocusDelegate(
        recyclerView = dataBinding.pageAnimeRv,
        orientation = DpadRecyclerOrientation.Grid,
        spanCountProvider = { spanCount },
        boundaryHandler = { direction -> onDpadFocusBoundary(direction) },
    )
}
```

- [ ] **Step 3: Store span count and install delegate**

Replace local `val spanCount = ...` with property assignment:

```kotlin
spanCount = if (mAttachActivity.isLandScreen()) 4 else 3
```

After adapter and decorations are set, add:

```kotlin
focusDelegate.install()
```

- [ ] **Step 4: Add owner methods**

Add:

```kotlin
override fun requestDefaultDpadFocus(): Boolean = focusDelegate.requestDefaultFocus()

override fun saveDpadFocus() {
    focusDelegate.saveFocus()
}

override fun restoreDpadFocus(): Boolean = focusDelegate.restoreFocus()

override fun onDpadFocusBoundary(direction: DpadDirection): Boolean = false
```

- [ ] **Step 5: Compile**

Run:

```powershell
./gradlew assembleDebug
```

Expected: tail contains `BUILD SUCCESSFUL`.

---

### Task 10: Migrate `PlayerSettingView` overlay first

**Files:**
- Modify: `player_component/src/main/java/com/xyoye/player/controller/setting/PlayerSettingView.kt`

- [ ] **Step 1: Add imports**

Add:

```kotlin
import com.xyoye.common_component.focus.DpadDirection
import com.xyoye.common_component.focus.DpadFocusOwner
import com.xyoye.common_component.focus.DpadRecyclerFocusDelegate
import com.xyoye.common_component.focus.DpadRecyclerOrientation
```

- [ ] **Step 2: Implement owner and delegate**

Change class declaration:

```kotlin
class PlayerSettingView(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseSettingView<LayoutPlayerSettingBinding>(context, attrs, defStyleAttr), DpadFocusOwner {
```

Add property:

```kotlin
private val focusDelegate by lazy(LazyThreadSafetyMode.NONE) {
    DpadRecyclerFocusDelegate(
        recyclerView = viewBinding.settingRv,
        orientation = DpadRecyclerOrientation.Grid,
        spanCountProvider = { 4 },
        boundaryHandler = { direction -> onDpadFocusBoundary(direction) },
    )
}
```

- [ ] **Step 3: Install delegate**

At end of `initRv()` after adapter setup, add:

```kotlin
focusDelegate.install()
```

- [ ] **Step 4: Request default focus on show**

At end of `onViewShow()`, after `setData(settingItems)`, add:

```kotlin
viewBinding.settingRv.post {
    requestDefaultDpadFocus()
}
```

- [ ] **Step 5: Add owner methods**

Add:

```kotlin
override fun requestDefaultDpadFocus(): Boolean {
    val firstIndex = settingItems.indexOfFirst { it is SettingItem }
    if (firstIndex != -1) {
        focusDelegate.setPendingFocus(index = firstIndex)
        return focusDelegate.restoreFocus()
    }
    return false
}

override fun saveDpadFocus() {
    focusDelegate.saveFocus()
}

override fun restoreDpadFocus(): Boolean = focusDelegate.restoreFocus()

override fun onDpadFocusBoundary(direction: DpadDirection): Boolean = false
```

- [ ] **Step 6: Preserve existing custom key behavior**

Keep the existing `onKeyDown()` and `handleKeyCode()` implementations unchanged. In this task, `DpadRecyclerFocusDelegate` is used only for save/restore/default focus. Do not rewrite player setting grid navigation here.

- [ ] **Step 7: Compile**

Run:

```powershell
./gradlew assembleDebug
```

Expected: tail contains `BUILD SUCCESSFUL`.

---

### Task 11: Add P0 instrumentation smoke tests

**Files:**
- Create: `app/src/androidTest/java/com/okamihoro/ddplaytv/ui/tv/TvMainFocusSmokeTest.kt`

- [ ] **Step 1: Create test file**

Add:

```kotlin
package com.okamihoro.ddplaytv.ui.tv

import android.content.res.Configuration
import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.okamihoro.ddplaytv.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvMainFocusSmokeTest {
    @Test
    fun tvShellKeepsVisibleFocusAcrossNavAndContentBoundary() {
        ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                assumeTrue(activity.isTvUiMode())
                assertVisibleFocus(activity.currentFocus)
                sendDpad(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
            }
            waitForIdle()
            scenario.onActivity { activity ->
                assertVisibleFocus(activity.currentFocus)
                sendDpad(activity, KeyEvent.KEYCODE_DPAD_LEFT)
            }
            waitForIdle()
            scenario.onActivity { activity ->
                assertVisibleFocus(activity.currentFocus)
                assertNotNull(activity.findViewById<View>(R.id.tv_nav_rv))
            }
        }
    }

    private fun sendDpad(activity: TvMainActivity, keyCode: Int) {
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun TvMainActivity.isTvUiMode(): Boolean {
        val mask = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        return mask == Configuration.UI_MODE_TYPE_TELEVISION
    }

    private fun assertVisibleFocus(focus: View?) {
        assertNotNull("Expected a focused view", focus)
        assertTrue("Expected focused view to be shown", focus!!.isShown)
    }
}
```

- [ ] **Step 2: Compile androidTest**

Run:

```powershell
./gradlew :app:compileDebugAndroidTestKotlin
```

Expected: compile succeeds.

---

### Task 12: Add dialog focus smoke test

**Files:**
- Create: `app/src/androidTest/java/com/xyoye/app/quality/DialogDpadFocusSmokeTest.kt`

- [ ] **Step 1: Create test file**

Add:

```kotlin
package com.xyoye.app.quality

import android.content.res.Configuration
import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.okamihoro.ddplaytv.ui.tv.TvMainActivity
import com.xyoye.common_component.weight.BottomActionDialog
import com.xyoye.data_component.bean.SheetActionBean
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DialogDpadFocusSmokeTest {
    @Test
    fun bottomActionDialogRestoresHostFocusAfterDismiss() {
        ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                assumeTrue(activity.isTvUiMode())
                assertVisibleFocus(activity.currentFocus)
                BottomActionDialog(
                    activity = activity,
                    actionData = listOf(SheetActionBean(actionId = 1, actionName = "测试动作", actionIconRes = -1)),
                    title = "测试",
                ) {
                    true
                }.show()
            }
            waitForIdle()
            scenario.onActivity { activity ->
                assertVisibleFocus(activity.currentFocus)
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            }
            waitForIdle()
            scenario.onActivity { activity ->
                assertVisibleFocus(activity.currentFocus)
            }
        }
    }

    private fun waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun TvMainActivity.isTvUiMode(): Boolean {
        val mask = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        return mask == Configuration.UI_MODE_TYPE_TELEVISION
    }

    private fun assertVisibleFocus(focus: View?) {
        assertNotNull("Expected a focused view", focus)
        assertTrue("Expected focused view to be shown", focus!!.isShown)
    }
}
```

- [ ] **Step 2: Compile androidTest**

Run:

```powershell
./gradlew :app:compileDebugAndroidTestKotlin
```

Expected: compile succeeds.

---

### Task 13: Final verification pass

**Files:**
- All files modified above.

- [ ] **Step 1: Run unit tests**

Run:

```powershell
./gradlew testDebugUnitTest
```

Expected: tail contains `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run module dependency governance**

Run:

```powershell
./gradlew verifyModuleDependencies
```

Expected: tail contains `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run debug build**

Run:

```powershell
./gradlew assembleDebug
```

Expected: tail contains `BUILD SUCCESSFUL`.

- [ ] **Step 4: Check emulator/device state before instrumentation**

Run:

```powershell
adb devices -l
```

Expected for instrumentation: at least one device line with state `device`. If only `offline`, `unauthorized`, or no device appears, report instrumentation skipped with reason.

- [ ] **Step 5: Run P0 instrumentation if device is available**

Run:

```powershell
./gradlew connectedDebugAndroidTest
```

Expected when a valid TV-mode device/emulator is available: tail contains `BUILD SUCCESSFUL`. If device is not TV UI mode, tests using `assumeTrue` may skip; report skip clearly.

- [ ] **Step 6: Read lint diagnostics for edited Kotlin files**

Use IDE diagnostics on changed files. Fix only new issues caused by this work.

- [ ] **Step 7: Report evidence**

Report:

- changed focus skeleton files,
- migrated P0/P1 pages,
- Gradle commands run,
- exact `BUILD SUCCESSFUL` / `BUILD FAILED` result from tails,
- instrumentation run or skipped reason,
- known remaining P2/P3 migration list.
