package com.sjpickard.pcloudmusic

import android.app.Application
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
import kotlinx.coroutines.launch

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
        // just the trigger, not a second copy of the state.
        CoroutineScope(Dispatchers.Main.immediate).launch {
            combine(playerController.nowPlaying, playerController.isPlaying) { track, isPlaying -> track to isPlaying }
                .collect {
                    val widget = PlayerWidget()
                    val ids = GlanceAppWidgetManager(this@PcloudMusicApplication).getGlanceIds(PlayerWidget::class.java)
                    ids.forEach { id -> widget.update(this@PcloudMusicApplication, id) }
                }
        }
    }
}
