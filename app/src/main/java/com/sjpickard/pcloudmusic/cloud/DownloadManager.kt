package com.sjpickard.pcloudmusic.cloud

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.sjpickard.pcloudmusic.data.MusicDao
import com.sjpickard.pcloudmusic.data.TrackEntity
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private fun uniqueWorkName(trackId: Long) = "download_track_$trackId"

/** Enqueues and tracks per-track pCloud downloads via WorkManager, and owns
 * the reverse direction - deleting a downloaded file to free space. Same
 * shape as the Big Finish player's DownloadManager. */
class DownloadManager(private val context: Context, private val dao: MusicDao) {

    fun downloadTrack(trackId: Long) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(KEY_TRACK_ID to trackId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueWorkName(trackId), ExistingWorkPolicy.KEEP, request)
    }

    fun downloadAlbum(trackIds: List<Long>) {
        trackIds.forEach { downloadTrack(it) }
    }

    /** Null once nothing is queued/running for this track. */
    fun observeState(trackId: Long): Flow<WorkInfo.State?> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(uniqueWorkName(trackId))
            .map { infos -> infos.firstOrNull()?.state }

    /** Only when remotePath is still set, so this never leaves a track with
     * no playable source at all. */
    suspend fun removeLocalCopy(track: TrackEntity) {
        val localPath = track.localPath ?: return
        if (track.remotePath == null) return
        File(localPath).delete()
        dao.clearLocalPath(track.id)
    }

    suspend fun removeLocalCopy(trackId: Long) {
        dao.getTrack(trackId)?.let { removeLocalCopy(it) }
    }

    suspend fun removeLocalCopies(tracks: List<TrackEntity>) {
        tracks.forEach { removeLocalCopy(it) }
    }

    /** Backs the Downloads screen's "Remove all" action. */
    suspend fun removeAllDownloads() {
        removeLocalCopies(dao.getDownloadedTracks())
    }
}
