package com.xyoye.common_component.focus

interface DpadFocusOwner {
    fun requestDefaultDpadFocus(): Boolean = false

    fun saveDpadFocus() {
    }

    fun restoreDpadFocus(): Boolean = false

    fun onDpadFocusBoundary(direction: DpadDirection): Boolean = false
}
