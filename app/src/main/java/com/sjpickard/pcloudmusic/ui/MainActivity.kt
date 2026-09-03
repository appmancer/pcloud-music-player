package com.sjpickard.pcloudmusic.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sjpickard.pcloudmusic.PcloudMusicApplication
import com.sjpickard.pcloudmusic.cloud.DownloadManager
import com.sjpickard.pcloudmusic.cloud.LibrarySyncManager
import com.sjpickard.pcloudmusic.cloud.PCloudAuthManager
import com.sjpickard.pcloudmusic.data.LocalLibraryScanner
import com.sjpickard.pcloudmusic.playback.PlayerController
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "MainActivity"

/** Dev/prototype fallback used before pCloud sign-in - a folder on the
 * device's own external storage, populated by `adb push` from the FUSE-
 * mounted pCloud drive during development (same "local test push" precedent
 * the Big Finish player used before its OneDrive integration landed), e.g.:
 * `adb push ~/pCloudDrive/"My Music"/Music /sdcard/Music` */
private val LOCAL_MUSIC_ROOT = File(Environment.getExternalStorageDirectory(), "Music")

/** READ_MEDIA_AUDIO (API 33+) / READ_EXTERNAL_STORAGE (below) - without this
 * granted at runtime, java.io.File can still list *directory* entries under
 * shared storage but silently returns no files for listFiles() calls scoped
 * to audio content, which made an early build of LocalLibraryScanner appear
 * to find albums (folder structure only) with zero tracks in any of them. */
private fun readAudioPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as PcloudMusicApplication
        val dao = app.database.dao()
        val factory = viewModelFactory {
            initializer { LibraryViewModel(dao) }
        }

        setContent {
            MaterialTheme {
                Surface {
                    val viewModel: LibraryViewModel = viewModel(factory = factory)
                    val navController = rememberNavController()
                    val sheetState = rememberBottomSheetScaffoldState()
                    val coroutineScope = rememberCoroutineScope()
                    val nowPlaying by app.playerController.nowPlaying.collectAsState()
                    val navigationBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    val peekHeight = if (nowPlaying != null) 200.dp + navigationBarHeight else 0.dp

                    var audioPermissionGranted by remember {
                        mutableStateOf(
                            ContextCompat.checkSelfPermission(this@MainActivity, readAudioPermission()) == PackageManager.PERMISSION_GRANTED
                        )
                    }
                    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                        audioPermissionGranted = granted
                    }
                    LaunchedEffect(Unit) {
                        if (!audioPermissionGranted) permissionLauncher.launch(readAudioPermission())
                    }
                    // Re-runs whenever permission flips true (first grant, or a
                    // later grant after the user initially denied it) - not
                    // gated on isSignedIn changing, since AccountScreen's own
                    // "Sync library" button covers the post-sign-in rescan.
                    LaunchedEffect(audioPermissionGranted) {
                        if (!audioPermissionGranted) return@LaunchedEffect
                        withContext(Dispatchers.IO) {
                            dao.backfillMissingAlbumCovers()
                            dao.backfillMissingArtistCovers()
                        }
                        // Signed-in case goes through LibrarySyncManager (a
                        // WorkManager job - see its doc comment) so a scan
                        // started here keeps running even if this Activity
                        // doesn't survive that long. The local-file dev
                        // fallback has no such risk (no network round trips,
                        // seconds not minutes) so it stays inline.
                        if (app.pCloudAuthManager.authState.value.isSignedIn) {
                            app.librarySyncManager.sync()
                        } else if (LOCAL_MUSIC_ROOT.isDirectory) {
                            withContext(Dispatchers.IO) {
                                try {
                                    val coverCacheDir = File(cacheDir, "covers")
                                    val stillFailedCount = LocalLibraryScanner(dao).scan(LOCAL_MUSIC_ROOT, coverCacheDir)
                                    if (stillFailedCount > 0) {
                                        Log.w(TAG, "startup library scan complete with $stillFailedCount folder(s) unreachable - existing data for those was left untouched")
                                    } else {
                                        Log.d(TAG, "startup library scan complete")
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "startup library scan failed", e)
                                }
                            }
                        }
                    }

                    BackHandler(enabled = sheetState.bottomSheetState.currentValue == SheetValue.Expanded) {
                        coroutineScope.launch { sheetState.bottomSheetState.partialExpand() }
                    }

                    BottomSheetScaffold(
                        scaffoldState = sheetState,
                        sheetPeekHeight = peekHeight,
                        sheetContent = {
                            PlayerTray(
                                app.playerController,
                                onOpenQueue = { albumId -> navController.navigate("albums/$albumId/tracks") },
                                isExpanded = sheetState.bottomSheetState.currentValue == SheetValue.Expanded,
                            )
                        },
                    ) { innerPadding ->
                        Column(modifier = Modifier.padding(innerPadding)) {
                            PcloudMusicNavGraph(
                                navController,
                                viewModel,
                                app.playerController,
                                app.pCloudAuthManager,
                                app.downloadManager,
                                app.librarySyncManager,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PcloudMusicNavGraph(
    navController: NavHostController,
    viewModel: LibraryViewModel,
    playerController: PlayerController,
    pCloudAuthManager: PCloudAuthManager,
    downloadManager: DownloadManager,
    librarySyncManager: LibrarySyncManager,
) {
    NavHost(navController = navController, startDestination = "artists", modifier = Modifier) {
        composable("artists") {
            ArtistListScreen(
                viewModel = viewModel,
                playerController = playerController,
                onArtistSelected = { artistId -> navController.navigate("artists/$artistId/albums") },
                onSearchSelected = { navController.navigate("search") },
                onAccountSelected = { navController.navigate("account") },
                onAlbumsSelected = { navController.navigate("albums") },
                onDownloadsSelected = { navController.navigate("downloads") },
            )
        }
        composable("albums") {
            AllAlbumsScreen(
                viewModel = viewModel,
                onAlbumSelected = { albumId -> navController.navigate("albums/$albumId/tracks") },
            )
        }
        composable("downloads") {
            DownloadsScreen(
                viewModel = viewModel,
                downloadManager = downloadManager,
                onArtistSelected = { artistId -> navController.navigate("artists/$artistId/albums") },
                onAlbumSelected = { albumId -> navController.navigate("albums/$albumId/tracks") },
            )
        }
        composable("artists/{artistId}/albums") { backStackEntry ->
            val artistId = backStackEntry.arguments?.getString("artistId")?.toLongOrNull() ?: return@composable
            AlbumGridScreen(
                artistId = artistId,
                viewModel = viewModel,
                playerController = playerController,
                downloadManager = downloadManager,
                onAlbumSelected = { albumId -> navController.navigate("albums/$albumId/tracks") },
            )
        }
        composable("albums/{albumId}/tracks") { backStackEntry ->
            val albumId = backStackEntry.arguments?.getString("albumId")?.toLongOrNull() ?: return@composable
            TrackListScreen(albumId = albumId, viewModel = viewModel, playerController = playerController, downloadManager = downloadManager)
        }
        composable("search") {
            SearchScreen(
                viewModel = viewModel,
                onArtistSelected = { artistId -> navController.navigate("artists/$artistId/albums") },
                onAlbumSelected = { albumId -> navController.navigate("albums/$albumId/tracks") },
            )
        }
        composable("account") {
            AccountScreen(authManager = pCloudAuthManager, librarySyncManager = librarySyncManager)
        }
    }
}
