package com.sjpickard.pcloudmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sjpickard.pcloudmusic.data.ArtistEntity
import com.sjpickard.pcloudmusic.data.ContinueListeningItem
import com.sjpickard.pcloudmusic.playback.PlayerController
import kotlinx.coroutines.launch

private val TILE_WIDTH = 140.dp

/** Top-level browse: every artist, plain alphabetical grid - deliberately
 * not grouped by any genre/category, unlike the Big Finish player. See the
 * plan this app was built from: the whole point is a traditional
 * artist/album view, in contrast to both Big Finish's Genre/Franchise
 * structure and the official pCloud app's broken (album, artist) grouping. */
@Composable
fun ArtistListScreen(
    viewModel: LibraryViewModel,
    playerController: PlayerController,
    onArtistSelected: (Long) -> Unit,
    onSearchSelected: () -> Unit,
    onAccountSelected: () -> Unit,
    onAlbumsSelected: () -> Unit,
    onDownloadsSelected: () -> Unit,
) {
    val artists by viewModel.artists().collectAsState(initial = emptyList())
    val continueListening by viewModel.continueListening().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val continueListeningListState = rememberLazyListState()

    LaunchedEffect(continueListening.firstOrNull()?.trackId) {
        if (continueListening.isNotEmpty()) {
            continueListeningListState.animateScrollToItem(0)
        }
    }

    // One LazyVerticalGrid for the whole screen, with the top bar and
    // "Continue listening" row as full-width spanning items rather than
    // living in a separate, non-scrolling outer Column - same fix as
    // AlbumGridScreen's title-clipping bug (see its doc comment). There, a
    // fixed height estimate broke; here the whole "Continue listening"
    // section (plus the top icon row) stayed permanently pinned above the
    // artist grid's own internal scroll, eating vertical space that only
    // got tighter once the player tray started docking at the bottom -
    // this way everything scrolls away together, same as any other list.
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = TILE_WIDTH),
        contentPadding = PaddingValues(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = onSearchSelected) {
                    Icon(imageVector = Icons.Filled.Search, contentDescription = "Search artists and albums")
                }
                IconButton(onClick = onAlbumsSelected) {
                    Icon(imageVector = Icons.Filled.Album, contentDescription = "Browse all albums")
                }
                IconButton(onClick = onDownloadsSelected) {
                    Icon(imageVector = Icons.Filled.DownloadForOffline, contentDescription = "Downloads")
                }
                IconButton(onClick = onAccountSelected) {
                    Icon(imageVector = Icons.Filled.AccountCircle, contentDescription = "pCloud account")
                }
            }
        }

        if (continueListening.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(
                        text = "Continue listening",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                    )
                    LazyRow(
                        state = continueListeningListState,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(continueListening, key = { it.trackId }) { entry: ContinueListeningItem ->
                            ContinueListeningTile(
                                entry = entry,
                                onClick = {
                                    scope.launch {
                                        if (entry.albumId != null) {
                                            val (album, tracks) = viewModel.albumAndTracks(entry.albumId) ?: return@launch
                                            val startIndex = tracks.indexOfFirst { it.id == entry.trackId }
                                            if (startIndex < 0) return@launch
                                            playerController.play(album, tracks, startIndex)
                                        } else {
                                            onArtistSelected(entry.artistId)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = "Artists",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            )
        }

        items(artists, key = { it.id }) { artist: ArtistEntity ->
            Column(modifier = Modifier.padding(8.dp).clickable { onArtistSelected(artist.id) }) {
                CoverThumbnail(artist.coverPath, size = TILE_WIDTH)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ContinueListeningTile(entry: ContinueListeningItem, onClick: () -> Unit) {
    val progressFraction = if (entry.durationMs > 0) {
        (entry.positionMs.toFloat() / entry.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(modifier = Modifier.width(TILE_WIDTH).clickable(onClick = onClick)) {
        CoverThumbnail(entry.coverPath, size = TILE_WIDTH)
        LinearProgressIndicator(
            progress = { progressFraction },
            modifier = Modifier.fillMaxWidth().height(3.dp),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = entry.artistName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = entry.trackTitle,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A square cover thumbnail, falling back to a plain tinted square when no art was extracted. */
@Composable
fun CoverThumbnail(path: String?, size: Dp) {
    if (path != null) {
        AsyncImage(
            model = path,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(RoundedCornerShape(6.dp)),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}
