package com.sjpickard.pcloudmusic.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.sjpickard.pcloudmusic.cloud.LibrarySyncManager
import com.sjpickard.pcloudmusic.cloud.LibrarySyncWorker
import com.sjpickard.pcloudmusic.cloud.PCloudAuthManager

/** pCloud account screen - sign-in (browser token-flow, see
 * PCloudAuthManager), plus a manual "Sync library" trigger for the same
 * whole-root scan MainActivity runs on every launch once signed in. The
 * scan itself runs as a WorkManager job (LibrarySyncManager/LibrarySyncWorker)
 * rather than directly off this screen's own coroutine scope, so it isn't
 * cancelled by leaving this screen, locking the device, or the Activity
 * being torn down mid-scan - this composable only observes its WorkInfo.
 *
 * The credentials wired into this build were originally registered for the
 * Big Finish player, reused here for convenience until a dedicated app is
 * registered - see local.properties' comment. If sign-in succeeds but the
 * sync below comes back empty (or fails outright against MUSIC_ROOT), that
 * app registration most likely only has access to pCloud's "Big Finish"
 * folder rather than the whole drive, and a separate registration (or
 * widened folder access) is needed for MUSIC_ROOT specifically. */
@Composable
fun AccountScreen(authManager: PCloudAuthManager, librarySyncManager: LibrarySyncManager) {
    val context = LocalContext.current
    val authState by authManager.authState.collectAsState()
    val workInfo by librarySyncManager.observeState().collectAsState(initial = null)

    val scanStatus = when (workInfo?.state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> "Syncing library from pCloud…"
        WorkInfo.State.SUCCEEDED -> {
            val stillFailedCount = workInfo?.outputData?.getInt(LibrarySyncWorker.KEY_STILL_FAILED_COUNT, 0) ?: 0
            if (stillFailedCount > 0) {
                "Sync complete, but $stillFailedCount folder${if (stillFailedCount == 1) "" else "s"} couldn't be reached " +
                    "even after a retry (network issue?) - their existing data was left as-is. Tap Sync library again to retry."
            } else {
                "Sync complete."
            }
        }
        WorkInfo.State.FAILED -> {
            val error = workInfo?.outputData?.getString(LibrarySyncWorker.KEY_ERROR)
            "Sync failed: $error " +
                "(if sign-in succeeded but this keeps failing, this app's pCloud registration may only reach the Big Finish folder - see local.properties)"
        }
        else -> ""
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "pCloud account", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (authState.isSignedIn) "Signed in" else "Not signed in",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (authState.isSignedIn) {
            Button(onClick = { authManager.signOut() }) {
                Text("Sign out")
            }
        } else {
            Button(onClick = { context.startActivity(authManager.buildAuthorizationIntent()) }) {
                Text("Sign in to pCloud")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text(text = scanStatus, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { librarySyncManager.sync() }, enabled = authState.isSignedIn) {
            Text("Sync library")
        }
    }
}
