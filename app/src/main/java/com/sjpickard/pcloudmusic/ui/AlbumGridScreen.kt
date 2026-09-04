package com.sjpickard.pcloudmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sjpickard.pcloudmusic.cloud.DownloadManager
import com.sjpickard.pcloudmusic.data.AlbumEntity
import com.sjpickard.pcloudmusic.data.TrackEntity
import com.sjpickard.pcloudmusic.playback.PlayerController
import kotlinx.coroutines.launch

/** Albums belonging to one artist, plus (below the grid) any loose tracks
 * sitting directly in that artist's folder with no album at all. Tapping a
 * loose track plays it, and the rest of the artist's loose tracks, as one
 * playlist - same "album only" queuing PlayerController applies everywhere
 * else, just with the loose-track list standing in for an album. */
@Composable
fun AlbumGridScreen(
    artistId: Long,
    viewModel: LibraryViewModel,
    playerController: PlayerController,
    downloadManager: DownloadManager,
    onAlbumSelected: (Long) -> Unit,
) {
    val artist by viewModel.artist(artistId).collectAsState(initial = null)
    val albums by viewModel.albums(artistId).collectAsState(initial = emptyList())
    val looseTracks by viewModel.looseTracks(artistId).collectAsState(initial = emptyList())
    val nowPlaying by playerController.nowPlaying.collectAsState()
    val scope = rememberCoroutineScope()

    // One LazyVerticalGrid for the whole screen, with the header/track rows
    // spanning both columns, rather than nesting a grid inside a LazyColumn
    // item - the previous nested-grid version needed an explicit height on
    // the inner grid (Compose requires a bounded height for a scrollable
    // nested inside another scrollable), which was hardcoded as a fixed
    // 220dp-per-row estimate. That estimate didn't account for a title
    // wrapping to 2 lines (AlbumCard allows up to 2), so any album with a
    // long enough title got its second line clipped - this structure lets
    // every row size itself from its actual content instead.
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            artist?.let {
                Text(
                    text = it.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        items(albums, key = { it.id }) { album: AlbumEntity ->
            AlbumCard(album, onClick = { onAlbumSelected(album.id) })
        }
        if (looseTracks.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "Tracks",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                )
            }
            items(looseTracks, key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { track: TrackEntity ->
                val isCurrent = track.id == nowPlaying?.id
                val downloadState by downloadManager.observeState(track.id).collectAsState(initial = null)
                TrackRow(
                    track = track,
                    isCurrent = isCurrent,
                    positionMs = track.lastPositionMs,
                    durationMs = track.durationMs,
                    downloadState = downloadState,
                    onClick = { playerController.play(null, looseTracks, looseTracks.indexOf(track)) },
                    onDownloadClick = { downloadManager.downloadTrack(track.id) },
                    onRemoveDownloadClick = { scope.launch { downloadManager.removeLocalCopy(track) } },
                )
            }
        }
    }
}

@Composable
private fun AlbumCard(album: AlbumEntity, onClick: () -> Unit) {
    Card(modifier = Modifier.padding(8.dp).clickable(onClick = onClick)) {
        Column {
            if (album.coverPath != null) {
                AsyncImage(
                    model = album.coverPath,
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
                    text = album.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = album.displayArtist,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
