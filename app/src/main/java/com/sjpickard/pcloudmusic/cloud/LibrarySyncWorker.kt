package com.sjpickard.pcloudmusic.cloud

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sjpickard.pcloudmusic.PcloudMusicApplication
import java.io.File

private const val TAG = "LibrarySyncWorker"

/** The pCloud path this app scans once signed in - mirrors bigfinish-
 * player's BIG_FINISH_ROOT constant. Folder/album structure under this root
 * is entirely what drives the catalog (see RemoteLibraryScanner) - a new
 * artist/album just needs to land in the right place in pCloud to show up. */
const val MUSIC_ROOT = "/My Music/Music"

/** Runs RemoteLibraryScanner.scanRoot as a WorkManager job rather than tied
 * to a Composable's lifecycle (rememberCoroutineScope()/LaunchedEffect) -
 * see LibrarySyncManager's doc comment for why: a full scan of a large
 * library runs well past a minute even on a good connection (~1.5s per
 * artist folder against the live pCloud API), and locking the screen or the
 * OS reclaiming the Activity mid-scan killed it outright (observed live:
 * androidx.compose.runtime.LeftCompositionCancellationException). */
class LibrarySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as PcloudMusicApplication
        if (!app.pCloudAuthManager.authState.value.isSignedIn) {
            return Result.failure(workDataOf(KEY_ERROR to "not signed in"))
        }

        val coverCacheDir = File(applicationContext.cacheDir, "covers")
        return try {
            val stillFailedCount = RemoteLibraryScanner(app.database.dao(), app.pCloudApiClient).scanRoot(MUSIC_ROOT, coverCacheDir)
            Log.d(TAG, "library sync complete, stillFailedCount=$stillFailedCount")
            Result.success(workDataOf(KEY_STILL_FAILED_COUNT to stillFailedCount))
        } catch (e: Exception) {
            Log.e(TAG, "library sync failed", e)
            Result.failure(workDataOf(KEY_ERROR to "${e.javaClass.simpleName}: ${e.message}"))
        }
    }

    companion object {
        const val KEY_STILL_FAILED_COUNT = "stillFailedCount"
        const val KEY_ERROR = "error"
    }
}
