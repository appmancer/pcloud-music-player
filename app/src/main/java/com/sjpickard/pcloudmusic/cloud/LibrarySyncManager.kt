package com.sjpickard.pcloudmusic.cloud

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val UNIQUE_WORK_NAME = "library_sync"

/** Triggers and observes the pCloud library sync (LibrarySyncWorker) as a
 * WorkManager job - same shape as DownloadManager, and for the same reason:
 * a job enqueued here keeps running (and WorkManager's own retry/backoff
 * aside, this app's own scanRoot retry logic still applies within one run)
 * even if the screen locks or the Activity that started it goes away, unlike
 * a scan launched directly off a Composable's own coroutine scope. */
class LibrarySyncManager(private val context: Context) {

    fun sync() {
        val request = OneTimeWorkRequestBuilder<LibrarySyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** Null once nothing has ever been enqueued this install - AccountScreen
     * treats that the same as "idle". */
    fun observeState(): Flow<WorkInfo?> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME)
            .map { infos -> infos.firstOrNull() }
}
