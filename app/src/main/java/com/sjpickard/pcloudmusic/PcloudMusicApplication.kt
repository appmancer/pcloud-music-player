package com.sjpickard.pcloudmusic

import android.app.Application
import com.sjpickard.pcloudmusic.cloud.DownloadManager
import com.sjpickard.pcloudmusic.cloud.LibrarySyncManager
import com.sjpickard.pcloudmusic.cloud.PCloudApiClient
import com.sjpickard.pcloudmusic.cloud.PCloudAuthManager
import com.sjpickard.pcloudmusic.data.MusicDatabase
import com.sjpickard.pcloudmusic.playback.PlayerController

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
    }
}
