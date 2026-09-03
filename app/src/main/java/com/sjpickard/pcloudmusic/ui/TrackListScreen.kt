package com.sjpickard.pcloudmusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import coil.compose.AsyncImage
import com.sjpickard.pcloudmusic.cloud.DownloadManager
import com.sjpickard.pcloudmusic.data.TrackEntity
import com.sjpickard.pcloudmusic.playback.PlayerController
import kotlinx.coroutines.launch

/** Tracks within one album, in disc/track order. Tapping one hands the rest
 * of the album to PlayerController as a playlist - playback stops once it
 * plays out (see PlayerController's "album only" doc comment), it does not
 * continue into whatever album happens to be next. */
@Composable
fun TrackListScreen(albumId: Long, viewModel: LibraryViewModel, playerController: PlayerController, downloadManager: DownloadManager) {
    val album by viewModel.album(albumId).collectAsState(initial = null)
    val tracks by viewModel.tracks(albumId).collectAsState(initial = emptyList())
    val nowPlaying by playerController.nowPlaying.collectAsState()
    val livePositionMs by playerController.positionMs.collectAsState()
    val liveDurationMs by playerController.durationMs.collectAsState()
    val scope = rememberCoroutineScope()

    LazyColumn {
        item {
            val coverPath = album?.coverPath
            if (coverPath != null) {
                AsyncImage(
                    model = coverPath,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
            }
            album?.let {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = it.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
                    Text(
                        text = it.displayArtist,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val downloadable = tracks.filter { t -> t.localPath == null && t.remotePath != null }
                    val removable = tracks.filter { t -> t.localPath != null && t.remotePath != null }
                    if (downloadable.isNotEmpty() || removable.isNotEmpty()) {
                        Row {
                            if (downloadable.isNotEmpty()) {
                                TextButton(onClick = { downloadManager.downloadAlbum(downloadable.map { t -> t.id }) }) {
                                    Text("Download all (${downloadable.size})")
                                }
                            }
                            if (removable.isNotEmpty()) {
                                TextButton(onClick = { scope.launch { downloadManager.removeLocalCopies(removable) } }) {
                                    Text("Remove downloads (${removable.size})")
                                }
                            }
                        }
                    }
                }
            }
        }
        items(tracks, key = { it.id }) { track: TrackEntity ->
            val isCurrent = track.id == nowPlaying?.id
            val positionMs = if (isCurrent) livePositionMs else track.lastPositionMs
            val durationMs = if (isCurrent && liveDurationMs > 0) liveDurationMs else track.durationMs
            val downloadState by downloadManager.observeState(track.id).collectAsState(initial = null)
            TrackRow(
                track = track,
                isCurrent = isCurrent,
                positionMs = positionMs,
                durationMs = durationMs,
                downloadState = downloadState,
                onClick = { playerController.play(album, tracks, startIndex = tracks.indexOf(track)) },
                onDownloadClick = { downloadManager.downloadTrack(track.id) },
                onRemoveDownloadClick = { scope.launch { downloadManager.removeLocalCopy(track) } },
            )
        }
    }
}

@Composable
fun TrackRow(
    track: TrackEntity,
    isCurrent: Boolean,
    positionMs: Long,
    durationMs: Long,
    downloadState: WorkInfo.State?,
    onClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onRemoveDownloadClick: () -> Unit,
) {
    val isDownloaded = track.localPath != null
    val isDownloading = downloadState == WorkInfo.State.ENQUEUED || downloadState == WorkInfo.State.RUNNING
    val canRemove = isDownloaded && track.remotePath != null

    Column(modifier = Modifier.clickable(onClick = onClick)) {
        ListItem(
            leadingContent = { TrackThumbnail(track.coverPath, track.trackNumber, size = 48.dp) },
            headlineContent = { Text(track.title) },
            supportingContent = if (track.performerArtist != null) {
                { Text(track.performerArtist) }
            } else {
                null
            },
            trailingContent = {
                when {
                    isDownloading -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    isDownloaded -> if (canRemove) {
                        IconButton(onClick = onRemoveDownloadClick) {
                            Icon(
                                imageVector = Icons.Filled.OfflinePin,
                                contentDescription = "Downloaded - tap to remove and free up space",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Filled.OfflinePin,
                            contentDescription = "Local file",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    track.remotePath == null -> {}
                    else -> IconButton(onClick = onDownloadClick) {
                        Icon(
                            imageVector = Icons.Filled.CloudQueue,
                            contentDescription = "Download for offline playback",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            colors = if (isCurrent) {
                ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            } else {
                ListItemDefaults.colors()
            },
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        val progressFraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        if (progressFraction > 0f) {
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
        }
    }
}

@Composable
private fun TrackThumbnail(coverPath: String?, trackNumber: Int, size: Dp) {
    Box(modifier = Modifier.size(size)) {
        CoverThumbnail(coverPath, size = size)
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.BottomEnd).size(18.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = trackNumber.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}
