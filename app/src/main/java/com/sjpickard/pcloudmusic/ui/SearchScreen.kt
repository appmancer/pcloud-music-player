package com.sjpickard.pcloudmusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sjpickard.pcloudmusic.data.AlbumEntity
import com.sjpickard.pcloudmusic.data.ArtistEntity
import kotlinx.coroutines.delay

private const val MIN_QUERY_LENGTH = 2
private const val DEBOUNCE_MS = 200L
private val THUMB_SIZE = 48.dp

/** Simple local search across artist names and album titles/display-artist
 * credits - no network calls, updates as you type. */
@Composable
fun SearchScreen(
    viewModel: LibraryViewModel,
    onArtistSelected: (Long) -> Unit,
    onAlbumSelected: (Long) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var artists by remember { mutableStateOf<List<ArtistEntity>>(emptyList()) }
    var albums by remember { mutableStateOf<List<AlbumEntity>>(emptyList()) }

    LaunchedEffect(query) {
        if (query.length < MIN_QUERY_LENGTH) {
            artists = emptyList()
            albums = emptyList()
            return@LaunchedEffect
        }
        delay(DEBOUNCE_MS)
        viewModel.searchArtists(query).collect { artists = it }
    }
    LaunchedEffect(query) {
        if (query.length < MIN_QUERY_LENGTH) return@LaunchedEffect
        delay(DEBOUNCE_MS)
        viewModel.searchAlbums(query).collect { albums = it }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Artists and albums") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))
        when {
            query.length < MIN_QUERY_LENGTH -> Text(
                text = "Search your library by artist or album.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            artists.isEmpty() && albums.isEmpty() -> Text(
                text = "No matches for \"$query\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn {
                items(artists, key = { "artist-${it.id}" }) { artist ->
                    ListItem(
                        modifier = Modifier.clickable { onArtistSelected(artist.id) },
                        leadingContent = { CoverThumbnail(artist.coverPath, size = THUMB_SIZE) },
                        headlineContent = { Text(artist.name) },
                        supportingContent = { Text("Artist") },
                    )
                }
                items(albums, key = { "album-${it.id}" }) { album ->
                    ListItem(
                        modifier = Modifier.clickable { onAlbumSelected(album.id) },
                        leadingContent = { CoverThumbnail(album.coverPath, size = THUMB_SIZE) },
                        headlineContent = { Text(album.title) },
                        supportingContent = { Text(album.displayArtist) },
                    )
                }
            }
        }
    }
}
