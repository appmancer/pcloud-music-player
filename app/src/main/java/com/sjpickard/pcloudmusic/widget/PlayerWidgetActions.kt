package com.sjpickard.pcloudmusic.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.sjpickard.pcloudmusic.PcloudMusicApplication

/** The three widget buttons - each just forwards to the same
 * PlayerController the in-app player tray already drives, so there's only
 * ever one source of playback truth. The widget itself updates via
 * PcloudMusicApplication observing PlayerController's state and calling
 * PlayerWidget().updateAll() - not from here directly. */
class TogglePlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        (context.applicationContext as PcloudMusicApplication).playerController.togglePlayPause()
    }
}

class SkipNextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        (context.applicationContext as PcloudMusicApplication).playerController.skipToNext()
    }
}

class SkipPreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        (context.applicationContext as PcloudMusicApplication).playerController.skipToPrevious()
    }
}
