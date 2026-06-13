# Player Subtitle Quick Toggle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a bottom player subtitle quick toggle that matches the existing danmu toggle style, toggles subtitle layer visibility only, and skips DPAD focus when no selected subtitle track exists.

**Architecture:** Keep the feature inside `:player_component`. Extend subtitle controller API with user-visible state, let `PlayerBottomView` drive the new checkbox through `ControlWrapper`, and reuse existing track update callbacks to refresh button state. Do not change subtitle track selection or playback kernel behavior.

**Tech Stack:** Kotlin 1.9.25, Android XML/DataBinding, AppCompatCheckBox, ConstraintLayout, Android resource selectors, Gradle 8.9.

---

## File Structure

- Modify `player_component/src/main/java/com/xyoye/player/wrapper/InterSubtitleController.kt`: add subtitle visibility API.
- Modify `player_component/src/main/java/com/xyoye/player/controller/subtitle/SubtitleController.kt`: store user visibility, gate subtitle rendering, clear/restore subtitle views.
- Modify `player_component/src/main/java/com/xyoye/player/controller/action/PlayerAction.kt`: add `ToggleSubtitle` action.
- Modify `player_component/src/main/java/com/xyoye/player/controller/VideoController.kt`: handle `ToggleSubtitle`.
- Modify `player_component/src/main/java/com/xyoye/player/controller/video/PlayerBottomView.kt`: add subtitle checkbox handling, state sync, readiness checks, hints, DPAD focus order.
- Modify `player_component/src/main/res/layout/layout_player_bottom.xml`: add `subtitle_control_iv` next to `danmu_control_iv` with matching style.
- Modify `player_component/src/main/res/values/strings.xml`: add `subtitle_switch`.
- Add `player_component/src/main/res/drawable/ic_subtitle_on_14dp.xml`: checked subtitle icon.
- Add `player_component/src/main/res/drawable/ic_subtitle_off_14dp.xml`: unchecked subtitle icon.
- Add `player_component/src/main/res/drawable/selector_subtitle_icon.xml`: checkbox icon selector.

No module dependency changes. No commit unless user explicitly requests one.

---

### Task 1: Add subtitle visibility contract and controller state

**Files:**
- Modify: `player_component/src/main/java/com/xyoye/player/wrapper/InterSubtitleController.kt`
- Modify: `player_component/src/main/java/com/xyoye/player/controller/subtitle/SubtitleController.kt`

- [ ] **Step 1: Extend subtitle controller interface**

In `InterSubtitleController.kt`, insert these methods after `fun updateShadow()` and before `fun onSubtitleTextOutput(...)`:

```kotlin
    /**
     * 改变字幕显示状态
     */
    fun toggleSubtitleVisible()

    /**
     * 显式设置字幕显示状态，供 UI 与状态恢复使用
     */
    fun setUserSubtitleVisible(visible: Boolean)

    /**
     * 当前用户层面的字幕显示状态
     */
    fun isUserSubtitleVisible(): Boolean
```

- [ ] **Step 2: Add state fields to SubtitleController**

In `SubtitleController.kt`, add these properties after `externalSubtitleView`:

```kotlin
    private var userSubtitleVisible = true
    private var lastSubtitle: MixedSubtitle? = null
```

- [ ] **Step 3: Implement visibility API in SubtitleController**

In `SubtitleController.kt`, add these overrides after `updateShadow()`:

```kotlin
    override fun toggleSubtitleVisible() {
        setUserSubtitleVisible(userSubtitleVisible.not())
    }

    override fun setUserSubtitleVisible(visible: Boolean) {
        if (userSubtitleVisible == visible) {
            return
        }
        userSubtitleVisible = visible
        if (visible) {
            restoreLastSubtitle()
        } else {
            clearSubtitleViews()
        }
    }

    override fun isUserSubtitleVisible(): Boolean = userSubtitleVisible
```

- [ ] **Step 4: Gate subtitle output and add helpers**

Replace existing `onSubtitleTextOutput(...)` in `SubtitleController.kt` with:

```kotlin
    override fun onSubtitleTextOutput(subtitle: MixedSubtitle) {
        lastSubtitle = subtitle
        if (userSubtitleVisible.not()) {
            clearSubtitleViews()
            return
        }
        applySubtitle(subtitle)
    }

    private fun restoreLastSubtitle() {
        lastSubtitle?.let { applySubtitle(it) }
    }

    private fun clearSubtitleViews() {
        if (!subtitleTextView.isEmptySubtitle()) {
            subtitleTextView.setSubtitle(null)
        }
        if (!subtitleImageView.isEmptySubtitle()) {
            subtitleImageView.setSubtitle(null)
        }
    }

    private fun applySubtitle(subtitle: MixedSubtitle) {
        when (subtitle.type) {
            SubtitleType.TEXT -> {
                // 显示文字字幕前，清空图片字幕
                if (!subtitleImageView.isEmptySubtitle()) {
                    subtitleImageView.setSubtitle(null)
                }
                subtitleTextView.setSubtitle(subtitle.text)
            }

            SubtitleType.BITMAP -> {
                // 显示图片字幕前，清空文字字幕
                if (!subtitleTextView.isEmptySubtitle()) {
                    subtitleTextView.setSubtitle(null)
                }
                subtitleImageView.setSubtitle(subtitle.bitmaps)
            }

            else -> {
            }
        }
    }
```

- [ ] **Step 5: Verify contract compiles conceptually**

Confirm `ControlWrapper` needs no explicit method body because it delegates `InterSubtitleController`:

```kotlin
class ControlWrapper(...) : InterVideoPlayer by mVideoPlayer,
    InterVideoController by mController,
    InterDanmuController by mDanmuController,
    InterSubtitleController by mSubtitleController,
    InterSettingController by mSettingController
```

Expected: `mControlWrapper.toggleSubtitleVisible()` and `mControlWrapper.isUserSubtitleVisible()` will resolve after this task.

---

### Task 2: Add player action routing for subtitle toggle

**Files:**
- Modify: `player_component/src/main/java/com/xyoye/player/controller/action/PlayerAction.kt`
- Modify: `player_component/src/main/java/com/xyoye/player/controller/VideoController.kt`

- [ ] **Step 1: Add new action**

In `PlayerAction.kt`, add after `object ToggleDanmu : PlayerAction()`:

```kotlin
    object ToggleSubtitle : PlayerAction()
```

The final bottom of the sealed class should be:

```kotlin
    object OpenSourceList : PlayerAction()

    object ToggleDanmu : PlayerAction()

    object ToggleSubtitle : PlayerAction()
}
```

- [ ] **Step 2: Route action in VideoController**

In `VideoController.kt`, update `handleAction(action: PlayerAction)` by adding this branch immediately after `PlayerAction.ToggleDanmu -> ...`:

```kotlin
            PlayerAction.ToggleDanmu -> mControlWrapper.toggleDanmuVisible()
            PlayerAction.ToggleSubtitle -> mControlWrapper.toggleSubtitleVisible()
```

Expected: all `when` branches remain exhaustive.

---

### Task 3: Add subtitle resources and bottom layout control

**Files:**
- Modify: `player_component/src/main/res/layout/layout_player_bottom.xml`
- Modify: `player_component/src/main/res/values/strings.xml`
- Add: `player_component/src/main/res/drawable/ic_subtitle_on_14dp.xml`
- Add: `player_component/src/main/res/drawable/ic_subtitle_off_14dp.xml`
- Add: `player_component/src/main/res/drawable/selector_subtitle_icon.xml`

- [ ] **Step 1: Add subtitle switch string**

In `strings.xml`, add this line after `<string name="danmu_switch">弹幕</string>`:

```xml
    <string name="subtitle_switch">字幕</string>
```

- [ ] **Step 2: Add checked subtitle icon**

Create `ic_subtitle_on_14dp.xml` with:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="14dp"
    android:height="14dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
  <path
      android:pathData="M20,4H4C2.9,4 2,4.9 2,6v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6C22,4.9 21.1,4 20,4z"
      android:strokeLineJoin="round"
      android:strokeWidth="2"
      android:fillColor="#00000000"
      android:strokeColor="#FFFFFF"
      android:strokeLineCap="round"/>
  <path
      android:pathData="M6,10h12"
      android:strokeLineJoin="round"
      android:strokeWidth="2"
      android:fillColor="#00000000"
      android:strokeColor="#FFFFFF"
      android:strokeLineCap="round"/>
  <path
      android:pathData="M6,14h8"
      android:strokeLineJoin="round"
      android:strokeWidth="2"
      android:fillColor="#00000000"
      android:strokeColor="#FFFFFF"
      android:strokeLineCap="round"/>
</vector>
```

- [ ] **Step 3: Add unchecked subtitle icon**

Create `ic_subtitle_off_14dp.xml` with:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="14dp"
    android:height="14dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
  <path
      android:pathData="M20,4H4C2.9,4 2,4.9 2,6v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6C22,4.9 21.1,4 20,4z"
      android:strokeLineJoin="round"
      android:strokeWidth="2"
      android:fillColor="#00000000"
      android:strokeColor="#FFFFFF"
      android:strokeLineCap="round"/>
  <path
      android:pathData="M5.5,18.5 L18.5,5.5"
      android:strokeLineJoin="round"
      android:strokeWidth="2"
      android:fillColor="#00000000"
      android:strokeColor="#FFFFFF"
      android:strokeLineCap="round"/>
</vector>
```

- [ ] **Step 4: Add subtitle icon selector**

Create `selector_subtitle_icon.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_checked="true" android:drawable="@drawable/ic_subtitle_on_14dp" />
    <item android:drawable="@drawable/ic_subtitle_off_14dp" />
</selector>
```

- [ ] **Step 5: Add subtitle checkbox to bottom layout**

In `layout_player_bottom.xml`, insert this block immediately before existing `danmu_control_iv`:

```xml
        <androidx.appcompat.widget.AppCompatCheckBox
            android:id="@+id/subtitle_control_iv"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginEnd="12dp"
            android:background="@drawable/background_send_danmu_bt"
            android:button="@null"
            android:drawablePadding="4dp"
            android:focusable="true"
            android:gravity="center"
            android:nextFocusUp="@id/video_title_tv"
            android:paddingHorizontal="10dp"
            android:paddingVertical="6dp"
            android:text="@string/subtitle_switch"
            android:textColor="@color/text_white_immutable"
            android:textSize="12sp"
            app:drawableStartCompat="@drawable/selector_subtitle_icon"
            app:layout_constraintBottom_toBottomOf="@id/play_iv"
            app:layout_constraintEnd_toStartOf="@id/danmu_control_iv"
            app:layout_constraintTop_toTopOf="@id/play_iv" />
```

Keep existing `danmu_control_iv` constrained to parent end:

```xml
            app:layout_constraintEnd_toEndOf="parent"
```

Expected: subtitle appears left of danmu, with same background, text style, padding, and TV focused feedback via existing background resource.

---

### Task 4: Wire PlayerBottomView subtitle behavior

**Files:**
- Modify: `player_component/src/main/java/com/xyoye/player/controller/video/PlayerBottomView.kt`

- [ ] **Step 1: Add subtitle checkbox listener**

In `init`, add this listener after the existing `danmuControlIv.setOnCheckedChangeListener` block:

```kotlin
        viewBinding.subtitleControlIv.setOnCheckedChangeListener { _, isChecked ->
            if (!controlsInputEnabled) return@setOnCheckedChangeListener
            val isSubtitleVisible = mControlWrapper.isUserSubtitleVisible()
            if (isChecked == isSubtitleVisible) {
                return@setOnCheckedChangeListener
            }
            if (!isSubtitleToggleReady()) {
                mControlWrapper.showMessage(resolveSubtitleToggleUnavailableHint())
                syncSubtitleToggleState()
                return@setOnCheckedChangeListener
            }
            actionHandler?.invoke(PlayerAction.ToggleSubtitle) ?: mControlWrapper.toggleSubtitleVisible()
            syncSubtitleToggleState()
        }
```

- [ ] **Step 2: Sync subtitle state on attach and visibility**

In `attach(controlWrapper: ControlWrapper)`, add `syncSubtitleToggleState()` after `syncDanmuToggleState()`:

```kotlin
        syncDanmuToggleState()
        syncSubtitleToggleState()
        updateDecodeTypeHint()
```

In `onVisibilityChanged(isVisible: Boolean)`, in both visible branches that call `syncDanmuToggleState()`, add `syncSubtitleToggleState()` immediately after it:

```kotlin
                syncDanmuToggleState()
                syncSubtitleToggleState()
                updateDecodeTypeHint()
```

and:

```kotlin
            syncDanmuToggleState()
            syncSubtitleToggleState()
            updateDecodeTypeHint()
```

- [ ] **Step 3: Refresh on subtitle track changes**

Replace existing `onTrackChanged` body with:

```kotlin
    override fun onTrackChanged(type: TrackType) {
        if (type == TrackType.DANMU) {
            syncDanmuToggleState()
            updateControlsInteractiveState(controlsInputEnabled)
        }
        if (type == TrackType.SUBTITLE) {
            syncSubtitleToggleState()
            updateControlsInteractiveState(controlsInputEnabled)
        }
    }
```

- [ ] **Step 4: Add subtitle focusability to interactive state**

In `updateControlsInteractiveState(enabled: Boolean)`, after the existing danmu block, add:

```kotlin
        val subtitleReady = enabled && isSubtitleToggleReady()
        viewBinding.subtitleControlIv.applyDpadFocusable(
            enabled = subtitleReady,
            inTouchMode = inTouchMode,
            clickable = enabled,
        )
        viewBinding.subtitleControlIv.alpha = if (isSubtitleToggleReady()) 1f else 0.5f
        if (!subtitleReady && viewBinding.subtitleControlIv.hasFocus() && !inTouchMode) {
            viewBinding.playIv.requestDefaultFocus()
        }
```

Expected: disabled subtitle button is dimmed, not DPAD-focusable, but still uses same clickable gating pattern as danmu.

- [ ] **Step 5: Add subtitle to focus order**

In `updateFocusNavigation()`, insert subtitle before danmu:

```kotlin
        if (viewBinding.subtitleControlIv.isFocusable) {
            focusables.add(viewBinding.subtitleControlIv)
        }
        if (viewBinding.danmuControlIv.isFocusable) {
            focusables.add(viewBinding.danmuControlIv)
        }
```

Expected DPAD order: video list -> previous source -> play -> next source -> subtitle -> danmu, skipping hidden/unfocusable controls.

- [ ] **Step 6: Add subtitle state helpers**

After `syncDanmuToggleState()`, add:

```kotlin
    private fun syncSubtitleToggleState() {
        if (this::mControlWrapper.isInitialized.not()) return
        val userVisible = mControlWrapper.isUserSubtitleVisible()
        if (viewBinding.subtitleControlIv.isChecked != userVisible) {
            viewBinding.subtitleControlIv.isChecked = userVisible
        }
        viewBinding.subtitleControlIv.alpha = if (isSubtitleToggleReady()) 1f else 0.5f
    }

    private fun isSubtitleToggleReady(): Boolean {
        if (this::mControlWrapper.isInitialized.not()) return false
        return mControlWrapper
            .getTracks(TrackType.SUBTITLE)
            .any { it.selected && !it.disable }
    }

    private fun resolveSubtitleToggleUnavailableHint(): String {
        val tracks = mControlWrapper.getTracks(TrackType.SUBTITLE).filterNot { it.disable }
        return if (tracks.isEmpty()) {
            "当前视频暂无字幕轨道"
        } else {
            "请先选择字幕轨道"
        }
    }
```

---

### Task 5: Verify build, lint diagnostics, and DPAD behavior

**Files:**
- Read diagnostics for changed Kotlin/XML files.
- Run Gradle command from repo root.

- [ ] **Step 1: Read lints for changed files**

Use diagnostics on:

```text
player_component/src/main/java/com/xyoye/player/wrapper/InterSubtitleController.kt
player_component/src/main/java/com/xyoye/player/controller/subtitle/SubtitleController.kt
player_component/src/main/java/com/xyoye/player/controller/action/PlayerAction.kt
player_component/src/main/java/com/xyoye/player/controller/VideoController.kt
player_component/src/main/java/com/xyoye/player/controller/video/PlayerBottomView.kt
player_component/src/main/res/layout/layout_player_bottom.xml
player_component/src/main/res/values/strings.xml
```

Expected: no new Kotlin unresolved symbols and no XML resource errors.

- [ ] **Step 2: Compile player module**

Run from `E:\Project\DDPlayTV`:

```powershell
.\gradlew.bat :player_component:compileDebugKotlin
```

Expected tail contains `BUILD SUCCESSFUL`. If tail contains `BUILD FAILED`, read the failure lines, fix only errors caused by this change, and rerun the same command.

- [ ] **Step 3: If Kotlin compile misses XML binding, run debug assemble**

Run from `E:\Project\DDPlayTV`:

```powershell
.\gradlew.bat assembleDebug
```

Expected tail contains `BUILD SUCCESSFUL`. If `BUILD FAILED`, inspect tail and fix changed-file issues only.

- [ ] **Step 4: Manual TV/DPAD smoke check when device is available**

If an emulator/device is already in `device` state, run a player smoke path. Use DPAD only:

```powershell
adb devices -l
adb -s emulator-5554 shell input keyevent 23
adb -s emulator-5554 shell input keyevent 21
adb -s emulator-5554 shell input keyevent 22
adb -s emulator-5554 shell input keyevent 23
adb -s emulator-5554 shell input keyevent 4
```

Expected: focus can move to subtitle button when a selected subtitle track exists; subtitle button is skipped and dimmed when no selected subtitle track exists; BACK exits/returns through existing controller behavior.

If no device is in `device` state, report DPAD smoke as not run and do not claim it passed.

---

## Self-Review

- Spec coverage: UI button, A+C semantics, no track mutation, DPAD skip, matching style, and build/manual verification are covered by Tasks 1-5.
- Placeholder scan: plan contains no deferred implementation items.
- Type consistency: `toggleSubtitleVisible`, `setUserSubtitleVisible`, `isUserSubtitleVisible`, `ToggleSubtitle`, and `subtitleControlIv` are defined before use.
- Scope: all changes remain in `:player_component`; no dependency matrix changes.
