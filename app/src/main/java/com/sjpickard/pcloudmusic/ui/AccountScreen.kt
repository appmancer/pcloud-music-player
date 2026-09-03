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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sjpickard.pcloudmusic.cloud.PCloudApiClient
import com.sjpickard.pcloudmusic.cloud.PCloudAuthManager
import com.sjpickard.pcloudmusic.cloud.RemoteLibraryScanner
import com.sjpickard.pcloudmusic.data.MusicDao
import java.io.File
import kotlinx.coroutines.launch

/** pCloud account screen - sign-in (browser token-flow, see
 * PCloudAuthManager), plus a manual "Sync library" trigger for the same
 * whole-root scan MainActivity runs on every launch once signed in.
 *
 * The credentials wired into this build were originally registered for the
 * Big Finish player, reused here for convenience until a dedicated app is
 * registered - see local.properties' comment. If sign-in succeeds but the
 * sync below comes back empty (or fails outright against MUSIC_ROOT), that
 * app registration most likely only has access to pCloud's "Big Finish"
 * folder rather than the whole drive, and a separate registration (or
 * widened folder access) is needed for MUSIC_ROOT specifically. */
@Composable
fun AccountScreen(authManager: PCloudAuthManager, apiClient: PCloudApiClient, dao: MusicDao) {
    val context = LocalContext.current
    val cacheDir = context.cacheDir
    val scope = rememberCoroutineScope()
    val authState by authManager.authState.collectAsState()
    var scanStatus by remember { mutableStateOf("") }

    suspend fun sync() {
        scanStatus = "Syncing library from pCloud…"
        try {
            val coverCacheDir = File(cacheDir, "covers")
            val stillFailedCount = RemoteLibraryScanner(dao, apiClient).scanRoot(MUSIC_ROOT, coverCacheDir)
            scanStatus = if (stillFailedCount > 0) {
                "Sync complete, but $stillFailedCount folder${if (stillFailedCount == 1) "" else "s"} couldn't be reached " +
                    "even after a retry (network issue?) - their existing data was left as-is. Tap Sync library again to retry."
            } else {
                "Sync complete."
            }
        } catch (e: Exception) {
            scanStatus = "Sync failed: ${e.javaClass.simpleName}: ${e.message} " +
                "(if sign-in succeeded but this keeps failing, this app's pCloud registration may only reach the Big Finish folder - see local.properties)"
        }
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
        Button(onClick = { scope.launch { sync() } }, enabled = authState.isSignedIn) {
            Text("Sync library")
        }
    }
}
