package com.sjpickard.pcloudmusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sjpickard.pcloudmusic.data.AlbumEntity

private val TILE_WIDTH = 140.dp

/** Every album in the library in one flat, artist-agnostic grid - unlike
 * ArtistListScreen -> AlbumGridScreen, which is always scoped to one artist.
 * Same "browse by album title" gap search already covers by query, this
 * covers by just looking. */
@Composable
fun AllAlbumsScreen(viewModel: LibraryViewModel, onAlbumSelected: (Long) -> Unit) {
    val albums by viewModel.allAlbums().collectAsState(initial = emptyList())

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Albums",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        )
        if (albums.isEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "No albums yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            return
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = TILE_WIDTH),
            contentPadding = PaddingValues(12.dp),
        ) {
            items(albums, key = { it.id }) { album: AlbumEntity ->
                Column(modifier = Modifier.padding(8.dp).clickable { onAlbumSelected(album.id) }) {
                    CoverThumbnail(album.coverPath, size = TILE_WIDTH)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = album.title,
                        style = MaterialTheme.typography.bodyMedium,
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
}
