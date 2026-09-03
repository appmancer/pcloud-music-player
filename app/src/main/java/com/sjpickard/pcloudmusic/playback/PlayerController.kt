package com.sjpickard.pcloudmusic.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.sjpickard.pcloudmusic.cloud.PCloudApiClient
import com.sjpickard.pcloudmusic.data.AlbumEntity
import com.sjpickard.pcloudmusic.data.MusicDao
import com.sjpickard.pcloudmusic.data.TrackEntity
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "PlayerController"
private const val SEEK_INCREMENT_MS = 30_000L
private const val POSITION_TICK_MS = 500L
private const val SAVE_INTERVAL_TICKS = 10 // 10 * 500ms = save every ~5s while playing

/**
 * Bridges the UI to PlaybackService via a MediaController. Same shape as the
 * Big Finish player's PlayerController - see that project's doc comment for
 * the general resume-position/position-ticker reasoning - with one
 * deliberate difference: "album only" queuing. play() hands the tapped
 * album's tracks to the player as one playlist, and when that playlist ends
 * (STATE_ENDED) playback simply stops - there's no continueIntoNextBox-style
 * auto-advance into whatever album happens to be next. A plain artist/album
 * browsing app has no equivalent of Big Finish's "box sets are meant to be
 * listened through in numbered order" - albums by different artists sitting
 * next to each other alphabetically have no such relationship.
 */
class PlayerController(
    private val context: Context,
    private val dao: MusicDao,
    private val pCloudApiClient: PCloudApiClient,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null

    private var currentTracks: List<TrackEntity> = emptyList()

    private val _nowPlaying = MutableStateFlow<TrackEntity?>(null)
    val nowPlaying: StateFlow<TrackEntity?> = _nowPlaying

    private val _currentAlbum = MutableStateFlow<AlbumEntity?>(null)
    val currentAlbum: StateFlow<AlbumEntity?> = _currentAlbum

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs

    private val _hasNext = MutableStateFlow(false)
    val hasNext: StateFlow<Boolean> = _hasNext

    private val _hasPrevious = MutableStateFlow(false)
    val hasPrevious: StateFlow<Boolean> = _hasPrevious

    fun connect() {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        future.addListener({
            controller = future.get().also { it.addListener(playerListener) }
            Log.d(TAG, "MediaController connected")
        }, MoreExecutors.directExecutor())
        startPositionTicker()
    }

    /** Plays [album]'s full track list as one playlist, jumping straight to
     * [startIndex] and resuming from that track's saved position - the whole
     * album is loaded (not just the tapped track onward) so previous-track/
     * back-skip works for tracks before the one tapped. [album] is null for
     * a loose (no-album) artist track, in which case [tracks] is that
     * artist's whole loose-track list. */
    fun play(album: AlbumEntity?, tracks: List<TrackEntity>, startIndex: Int) {
        Log.d(TAG, "play() album=${album?.title} startIndex=$startIndex tracks=${tracks.size} controllerReady=${controller != null}")
        val startTrack = tracks.getOrNull(startIndex) ?: return
        val c = controller ?: run {
            Log.w(TAG, "play() aborted: MediaController not connected yet")
            return
        }

        currentTracks = tracks
        _currentAlbum.value = album
        _nowPlaying.value = startTrack

        scope.launch { dao.touchLastPlayed(startTrack.id, System.currentTimeMillis()) }

        scope.launch {
            val items = tracks.mapNotNull { toMediaItemOrNull(it) }
            Log.d(TAG, "play() resolved ${items.size}/${tracks.size} media items")
            if (items.isEmpty()) {
                c.stop()
                _nowPlaying.value = null
                _currentAlbum.value = null
                return@launch
            }
            val mediaIndex = items.indexOfFirst { it.mediaId == startTrack.id.toString() }
            if (mediaIndex < 0) {
                Log.w(TAG, "play() aborted: tapped track ${startTrack.id} (${startTrack.title}) failed to resolve")
                c.stop()
                _nowPlaying.value = null
                _currentAlbum.value = null
                return@launch
            }
            c.setMediaItems(items, mediaIndex, startTrack.lastPositionMs)
            c.prepare()
            c.play()
            Log.d(TAG, "play() started mediaIndex=$mediaIndex uri=${items[mediaIndex].localConfiguration?.uri.toString().take(80)}…")
        }
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun skipToNext() = controller?.seekToNext()

    fun skipToPrevious() = controller?.seekToPrevious()

    fun seekBack30() = seekBy(-SEEK_INCREMENT_MS)

    fun seekForward30() = seekBy(SEEK_INCREMENT_MS)

    fun seekTo(positionMs: Long) {
        val c = controller ?: return
        val duration = c.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        c.seekTo(positionMs.coerceIn(0, duration))
        _positionMs.value = c.currentPosition
    }

    private fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        seekTo(c.currentPosition + deltaMs)
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (!isPlaying) savePosition()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                _nowPlaying.value?.let { markFinished(it) }
            }
            val id = mediaItem?.mediaId?.toLongOrNull() ?: return
            currentTracks.find { it.id == id }?.let { _nowPlaying.value = it }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                // Album-only queuing (see class doc) - mark the last track
                // finished and stop, no auto-advance into a following album.
                _nowPlaying.value?.let { markFinished(it) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "onPlayerError: ${error.errorCodeName}: ${error.message}", error)
        }
    }

    private fun startPositionTicker() {
        scope.launch {
            var tick = 0
            while (true) {
                delay(POSITION_TICK_MS)
                val c = controller ?: continue
                _positionMs.value = c.currentPosition
                _durationMs.value = c.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                _hasNext.value = c.hasNextMediaItem()
                _hasPrevious.value = c.hasPreviousMediaItem()
                if (c.isPlaying) {
                    tick++
                    if (tick >= SAVE_INTERVAL_TICKS) {
                        tick = 0
                        savePosition()
                    }
                } else {
                    tick = 0
                }
            }
        }
    }

    private fun savePosition() {
        val track = _nowPlaying.value ?: return
        val position = controller?.currentPosition ?: return
        scope.launch { dao.updatePositionMs(track.id, position) }
    }

    private fun markFinished(track: TrackEntity) {
        scope.launch { dao.updatePositionMs(track.id, 0) }
    }

    /** Prefers the local file (works offline) and falls back to streaming
     * from pCloud - remotePath is a stable fileid, not a URL, so it resolves
     * to a fresh download link right before playback rather than one fetched
     * and persisted back at scan time (pCloud links expire). */
    private suspend fun toMediaItemOrNull(track: TrackEntity): MediaItem? {
        val uri = when {
            track.localPath != null -> Uri.fromFile(File(track.localPath))
            track.remotePath != null -> {
                val fileId = track.remotePath.toLongOrNull()
                if (fileId == null) {
                    Log.w(TAG, "track ${track.id} has a non-numeric remotePath: ${track.remotePath}")
                    return null
                }
                Log.d(TAG, "resolving remote download URL for track ${track.id} (${track.title})")
                val downloadUrl = pCloudApiClient.getDownloadUrl(fileId)
                if (downloadUrl == null) {
                    Log.w(TAG, "getDownloadUrl returned null for track ${track.id} (${track.title})")
                    return null
                }
                Uri.parse(downloadUrl)
            }
            else -> {
                Log.w(TAG, "track ${track.id} (${track.title}) has neither localPath nor remotePath")
                return null
            }
        }
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(track.id.toString())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).build())
            .build()
    }
}
