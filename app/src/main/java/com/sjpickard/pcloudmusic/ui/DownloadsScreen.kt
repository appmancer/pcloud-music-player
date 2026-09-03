package com.sjpickard.pcloudmusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sjpickard.pcloudmusic.cloud.DownloadManager
import com.sjpickard.pcloudmusic.data.DownloadedTrackItem
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val THUMB_SIZE = 48.dp

/** Every track currently downloaded for offline playback, across the whole
 * library - the counterpart to the per-track/per-album download controls in
 * TrackListScreen/AlbumGridScreen, which have no way to see or manage the
 * downloaded set as a whole. */
@Composable
fun DownloadsScreen(
    viewModel: LibraryViewModel,
    downloadManager: DownloadManager,
    onArtistSelected: (Long) -> Unit,
    onAlbumSelected: (Long) -> Unit,
) {
    val tracks by viewModel.downloadedTracks().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var totalBytes by remember { mutableStateOf(0L) }

    LaunchedEffect(tracks) {
        totalBytes = withContext(Dispatchers.IO) {
            tracks.sumOf { File(it.localPath).length() }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
        ) {
            Text(text = "Downloads", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (tracks.isNotEmpty()) {
                TextButton(onClick = { scope.launch { downloadManager.removeAllDownloads() } }) {
                    Text("Remove all")
                }
            }
        }
        if (tracks.isEmpty()) {
            Text(
                text = "Nothing downloaded yet. Download tracks or albums from their track list for offline playback.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            return
        }
        Text(
            text = "${tracks.size} track${if (tracks.size == 1) "" else "s"} · ${formatBytes(totalBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn {
            items(tracks, key = { it.trackId }) { item: DownloadedTrackItem ->
                ListItem(
                    modifier = Modifier.clickable {
                        if (item.albumId != null) onAlbumSelected(item.albumId) else onArtistSelected(item.artistId)
                    },
                    leadingContent = { CoverThumbnail(item.coverPath, size = THUMB_SIZE) },
                    headlineContent = { Text(item.trackTitle) },
                    supportingContent = {
                        Text(if (item.albumTitle != null) "${item.artistName} · ${item.albumTitle}" else item.artistName)
                    },
                    trailingContent = {
                        IconButton(onClick = { scope.launch { downloadManager.removeLocalCopy(item.trackId) } }) {
                            Icon(imageVector = Icons.Filled.Delete, contentDescription = "Remove download")
                        }
                    },
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1_000.0)
    else -> "$bytes B"
}
