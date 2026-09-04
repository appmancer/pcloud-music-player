package com.sjpickard.pcloudmusic

import android.app.Application
import android.util.Log
import com.sjpickard.pcloudmusic.cloud.DownloadManager
import com.sjpickard.pcloudmusic.cloud.LibrarySyncManager
import com.sjpickard.pcloudmusic.cloud.PCloudApiClient
import com.sjpickard.pcloudmusic.cloud.PCloudAuthManager
import com.sjpickard.pcloudmusic.data.MusicDatabase
import com.sjpickard.pcloudmusic.playback.PlayerController
import com.sjpickard.pcloudmusic.widget.PlayerWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

private const val TAG = "PcloudMusicApplication"

class PcloudMusicApplication : Application() {
    val database: MusicDatabase by lazy { MusicDatabase.get(this) }
    val pCloudAuthManager: PCloudAuthManager by lazy { PCloudAuthManager(this) }
    val pCloudApiClient: PCloudApiClient by lazy { PCloudApiClient(pCloudAuthManager) }
    val playerController: PlayerController by lazy { PlayerController(this, database.dao(), pCloudApiClient) }
    val downloadManager: DownloadManager by lazy { DownloadManager(this, database.dao()) }
    val librarySyncManager: LibrarySyncManager by lazy { LibrarySyncManager(this) }

    override fun onCreate() {
        super.onCreate()
        playerController.connect()

        // Re-renders the home-screen widget (see widget/PlayerWidget.kt)
        // whenever now-playing/play-state changes - provideGlance() reads
        // PlayerController's current values fresh each time, so this is
        // just the trigger, not a second copy of the state. Debounced:
        // found live that a single skip fires this 3x within ~300ms (the
        // track transition, then isPlaying flipping false/true again as
        // playback restarts) - three widget.update() calls that close
        // together were racing (Glance/the AppWidgetHost applying them out
        // of order), so the title/art visually stuck on a stale track even
        // though every individual update logged the correct data. Debounce
        // collapses that burst down to the one settled state actually worth
        // painting.
        CoroutineScope(Dispatchers.Main.immediate).launch {
            combine(playerController.nowPlaying, playerController.isPlaying) { track, isPlaying -> track to isPlaying }
                .debounce(200)
                .collect { (track, isPlaying) ->
                    val ids = GlanceAppWidgetManager(this@PcloudMusicApplication).getGlanceIds(PlayerWidget::class.java)
                    Log.d(TAG, "widget update: track=${track?.title} isPlaying=$isPlaying widgetIds=${ids.size}")
                    val widget = PlayerWidget()
                    ids.forEach { id ->
                        try {
                            widget.update(this@PcloudMusicApplication, id)
                        } catch (e: Exception) {
                            Log.e(TAG, "widget.update failed for $id", e)
                        }
                    }
                }
        }
    }
}
