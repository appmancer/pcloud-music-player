package com.sjpickard.pcloudmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sjpickard.pcloudmusic.cloud.DownloadManager
import com.sjpickard.pcloudmusic.data.DownloadedTrackItem
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val THUMB_SIZE = 48.dp

/** One section of the Downloads screen: either an album with at least one
 * downloaded track, or (albumId null) one artist's downloaded tracks that
 * aren't part of any album - mirrors the "album card, then a loose-tracks
 * section" split AlbumGridScreen already uses for a single artist. */
private data class DownloadGroup(
    val albumId: Long?,
    val albumTitle: String?,
    val artistId: Long,
    val artistName: String,
    val coverPath: String?,
    val tracks: List<DownloadedTrackItem>,
)

/** Groups consecutive tracks sharing an album (or, for loose tracks, an
 * artist) into sections. Relies on observeDownloadedTracks() already
 * ordering by artist then album, so matching tracks are always adjacent. */
private fun groupDownloads(tracks: List<DownloadedTrackItem>): List<DownloadGroup> {
    val groups = mutableListOf<DownloadGroup>()
    for (track in tracks) {
        val last = groups.lastOrNull()
        if (last != null && last.albumId == track.albumId && last.artistId == track.artistId) {
            groups[groups.lastIndex] = last.copy(tracks = last.tracks + track)
        } else {
            groups += DownloadGroup(
                albumId = track.albumId,
                albumTitle = track.albumTitle,
                artistId = track.artistId,
                artistName = track.artistName,
                coverPath = track.coverPath,
                tracks = listOf(track),
            )
        }
    }
    return groups
}

/** Every album (and every artist's loose tracks) with at least one track
 * downloaded for offline playback, across the whole library - the
 * counterpart to the per-track/per-album download controls in
 * TrackListScreen/AlbumGridScreen, which have no way to see or manage the
 * downloaded set as a whole. Shows one level - albums (and artists, for
 * loose tracks) - same as the artist/album browse screens; drilling into
 * one reuses TrackListScreen/AlbumGridScreen, which already mark each
 * track's download state and let it be removed there. */
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

        val groups = remember(tracks) { groupDownloads(tracks) }
        val albumGroups = remember(groups) { groups.filter { it.albumId != null } }
        val looseGroups = remember(groups) { groups.filter { it.albumId == null } }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(8.dp),
        ) {
            items(albumGroups, key = { "album-${it.albumId}" }) { group ->
                DownloadAlbumCard(group, onClick = { onAlbumSelected(group.albumId!!) })
            }
            if (looseGroups.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "Other tracks",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    )
                }
                items(looseGroups, key = { "artist-${it.artistId}" }, span = { GridItemSpan(maxLineSpan) }) { group ->
                    ListItem(
                        modifier = Modifier.clickable { onArtistSelected(group.artistId) },
                        leadingContent = { CoverThumbnail(group.coverPath, size = THUMB_SIZE) },
                        headlineContent = { Text(group.artistName) },
                        supportingContent = {
                            Text("${group.tracks.size} track${if (group.tracks.size == 1) "" else "s"} downloaded · not in an album")
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadAlbumCard(group: DownloadGroup, onClick: () -> Unit) {
    Card(modifier = Modifier.padding(8.dp).clickable(onClick = onClick)) {
        Column {
            if (group.coverPath != null) {
                AsyncImage(
                    model = group.coverPath,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = group.albumTitle.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = group.artistName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${group.tracks.size} track${if (group.tracks.size == 1) "" else "s"} downloaded",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
