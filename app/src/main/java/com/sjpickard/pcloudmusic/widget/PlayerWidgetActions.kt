package com.sjpickard.pcloudmusic.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.sjpickard.pcloudmusic.PcloudMusicApplication

/** The three widget buttons - each just forwards to the same
 * PlayerController the in-app player tray already drives, so there's only
 * ever one source of playback truth. The widget itself updates via
 * PcloudMusicApplication observing PlayerController's state and updating
 * the widget - not from here directly. awaitConnected() covers a cold app
 * process (the widget doesn't keep it alive) - see its own doc comment. */
class TogglePlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val playerController = (context.applicationContext as PcloudMusicApplication).playerController
        playerController.awaitConnected()
        playerController.togglePlayPause()
    }
}

class SkipNextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val playerController = (context.applicationContext as PcloudMusicApplication).playerController
        playerController.awaitConnected()
        playerController.skipToNext()
    }
}

class SkipPreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val playerController = (context.applicationContext as PcloudMusicApplication).playerController
        playerController.awaitConnected()
        playerController.skipToPrevious()
    }
}
