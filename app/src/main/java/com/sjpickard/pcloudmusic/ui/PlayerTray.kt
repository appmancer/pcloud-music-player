package com.sjpickard.pcloudmusic.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sjpickard.pcloudmusic.data.AlbumEntity
import com.sjpickard.pcloudmusic.data.TrackEntity
import com.sjpickard.pcloudmusic.playback.PlayerController
import java.io.File
import java.util.Locale

/** The pull-up player: the collapsed mini-player is always visible while
 * something's queued; dragging it up reveals cover art and technical
 * details read straight from the file. No cast/synopsis section, unlike the
 * Big Finish player's tray - there's no bigfinish.com-equivalent metadata
 * source for a general music library. */
@Composable
fun PlayerTray(
    playerController: PlayerController,
    onOpenQueue: (Long) -> Unit,
    isExpanded: Boolean,
) {
    val track by playerController.nowPlaying.collectAsState()
    if (track == null) {
        return
    }
    val album by playerController.currentAlbum.collectAsState()

    val scrollState = rememberScrollState()
    LaunchedEffect(isExpanded) {
        if (!isExpanded) scrollState.scrollTo(0)
    }

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(scrollState)) {
        CompactControls(playerController, track!!, albumId = album?.id, onOpenQueue = onOpenQueue)
        ExpandedDetails(track!!, album)
    }
}

@Composable
private fun CompactControls(
    playerController: PlayerController,
    track: TrackEntity,
    albumId: Long?,
    onOpenQueue: (Long) -> Unit,
) {
    val isPlaying by playerController.isPlaying.collectAsState()
    val positionMs by playerController.positionMs.collectAsState()
    val durationMs by playerController.durationMs.collectAsState()
    val hasNext by playerController.hasNext.collectAsState()
    val hasPrevious by playerController.hasPrevious.collectAsState()

    var dragPositionMs by remember { mutableStateOf<Float?>(null) }
    val displayedPositionMs = dragPositionMs ?: positionMs.toFloat()
    val sliderRange = 0f..durationMs.toFloat().coerceAtLeast(1f)

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).navigationBarsPadding()) {
        Text(
            text = track.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = formatTime(displayedPositionMs.toLong()), style = MaterialTheme.typography.labelSmall)
            Slider(
                value = displayedPositionMs.coerceIn(sliderRange.start, sliderRange.endInclusive),
                valueRange = sliderRange,
                onValueChange = { dragPositionMs = it },
                onValueChangeFinished = {
                    dragPositionMs?.let { playerController.seekTo(it.toLong()) }
                    dragPositionMs = null
                },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(text = formatTime(durationMs), style = MaterialTheme.typography.labelSmall)
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { playerController.skipToPrevious() }, enabled = hasPrevious) {
                Icon(imageVector = Icons.Filled.SkipPrevious, contentDescription = "Previous track")
            }
            IconButton(onClick = { playerController.seekBack30() }) {
                Icon(imageVector = Icons.Filled.Replay30, contentDescription = "Back 30 seconds")
            }
            IconButton(onClick = { playerController.togglePlayPause() }) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                )
            }
            IconButton(onClick = { playerController.seekForward30() }) {
                Icon(imageVector = Icons.Filled.Forward30, contentDescription = "Forward 30 seconds")
            }
            IconButton(onClick = { playerController.skipToNext() }, enabled = hasNext) {
                Icon(imageVector = Icons.Filled.SkipNext, contentDescription = "Next track")
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { albumId?.let(onOpenQueue) }, enabled = albumId != null) {
                Icon(imageVector = Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "View album")
            }
        }
    }
}

@Composable
private fun ExpandedDetails(track: TrackEntity, album: AlbumEntity?) {
    Column(modifier = Modifier.padding(16.dp)) {
        if (track.coverPath != null) {
            AsyncImage(
                model = track.coverPath,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (track.performerArtist != null) {
            Text(text = track.performerArtist, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
        }
        if (album != null) {
            Text(
                text = album.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(text = "Technical details", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        TechnicalDetails(track, album?.discTotal)
    }
}

@Composable
private fun TechnicalDetails(track: TrackEntity, discTotal: Int?) {
    val fileSizeMb = track.localPath?.let { path ->
        val bytes = File(path).length()
        if (bytes > 0) String.format(Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0) else null
    }
    val rows = listOfNotNull(
        "Format" to track.fileExtension.uppercase(),
        "Duration" to formatTime(track.durationMs),
        fileSizeMb?.let { "File size" to it },
        "Track" to track.trackNumber.toString(),
        if (discTotal != null && discTotal > 1) "Disc" to "${track.discNumber}/$discTotal" else null,
        if (track.localPath != null) "Storage" to "Downloaded on this device" else "Storage" to "Streaming (not downloaded)",
    )
    Column {
        for ((label, value) in rows) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(text = value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.5f))
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}
