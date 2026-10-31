package com.xyoye.common_component.focus

interface DpadFocusOwner {
    fun requestDefaultDpadFocus(): Boolean

    fun handleDpadBack(): Boolean = false
}
