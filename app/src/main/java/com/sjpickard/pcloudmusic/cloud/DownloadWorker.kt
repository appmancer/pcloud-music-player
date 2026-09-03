package com.sjpickard.pcloudmusic.cloud

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sjpickard.pcloudmusic.PcloudMusicApplication
import java.io.File

private const val TAG = "DownloadWorker"
const val KEY_TRACK_ID = "trackId"

/** Downloads one track from pCloud for offline playback and records the
 * resulting file as the track's localPath - playback then prefers it over
 * streaming (see PlayerController.toMediaItemOrNull). Looks up its
 * dependencies off PcloudMusicApplication rather than a proper injected
 * constructor, same reasoning as the Big Finish player's DownloadWorker
 * (WorkManager instantiates Workers itself via reflection). */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val trackId = inputData.getLong(KEY_TRACK_ID, -1L)
        if (trackId == -1L) return Result.failure()

        val app = applicationContext as PcloudMusicApplication
        val dao = app.database.dao()
        val apiClient = app.pCloudApiClient

        val track = dao.getTrack(trackId) ?: return Result.failure()
        if (track.localPath != null) return Result.success() // already downloaded
        val remoteFileId = track.remotePath?.toLongOrNull() ?: return Result.failure()

        val artist = dao.getArtist(track.artistId) ?: return Result.failure()
        val album = track.albumId?.let { dao.getAlbum(it) }

        val downloadUrl = apiClient.getDownloadUrl(remoteFileId)
        if (downloadUrl == null) {
            Log.w(TAG, "no download URL for track $trackId - retrying")
            return Result.retry()
        }

        val fileName = "${track.discNumber.toString().padStart(2, '0')}.${track.trackNumber.toString().padStart(2, '0')} - ${track.title}.${track.fileExtension}"
        val destDir = File(applicationContext.getExternalFilesDir(null), "Offline/${artist.name}/${album?.title ?: "Loose"}")
        val destFile = File(destDir, fileName)

        return try {
            apiClient.downloadToFile(downloadUrl, destFile)
            dao.setLocalPathIfMissing(trackId, destFile.path)
            Log.d(TAG, "downloaded track $trackId -> ${destFile.path}")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "download failed for track $trackId", e)
            destFile.delete()
            Result.retry()
        }
    }
}
