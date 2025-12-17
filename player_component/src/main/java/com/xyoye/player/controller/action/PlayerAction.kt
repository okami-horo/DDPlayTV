package com.xyoye.player.controller.action

sealed class PlayerAction {
    object TogglePlay : PlayerAction()

    data class SeekBy(
        val offsetMs: Long
    ) : PlayerAction()

    object ShowController : PlayerAction()

    object OpenPlayerSettings : PlayerAction()

    object OpenEpisodePanel : PlayerAction()

    object NextSource : PlayerAction()

    object PreviousSource : PlayerAction()

    object OpenSourceList : PlayerAction()

    object ToggleDanmu : PlayerAction()
}
