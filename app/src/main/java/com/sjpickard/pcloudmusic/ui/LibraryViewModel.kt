package com.sjpickard.pcloudmusic.ui

import androidx.lifecycle.ViewModel
import com.sjpickard.pcloudmusic.data.AlbumEntity
import com.sjpickard.pcloudmusic.data.ArtistEntity
import com.sjpickard.pcloudmusic.data.ContinueListeningItem
import com.sjpickard.pcloudmusic.data.DownloadedTrackItem
import com.sjpickard.pcloudmusic.data.MusicDao
import com.sjpickard.pcloudmusic.data.TrackEntity
import kotlinx.coroutines.flow.Flow

/** Read-only catalog browsing. Playback (including position-saving) lives in
 * PlayerController - kept separate so browsing state and playback state
 * don't tangle, same split as the Big Finish player. */
class LibraryViewModel(private val dao: MusicDao) : ViewModel() {

    fun artists(): Flow<List<ArtistEntity>> = dao.observeArtists()

    fun artist(artistId: Long): Flow<ArtistEntity?> = dao.observeArtist(artistId)

    fun albums(artistId: Long): Flow<List<AlbumEntity>> = dao.observeAlbums(artistId)

    fun allAlbums(): Flow<List<AlbumEntity>> = dao.observeAllAlbums()

    fun album(albumId: Long): Flow<AlbumEntity?> = dao.observeAlbum(albumId)

    fun looseTracks(artistId: Long): Flow<List<TrackEntity>> = dao.observeLooseTracks(artistId)

    fun tracks(albumId: Long): Flow<List<TrackEntity>> = dao.observeTracksForAlbum(albumId)

    fun continueListening(): Flow<List<ContinueListeningItem>> = dao.observeContinueListening()

    fun downloadedTracks(): Flow<List<DownloadedTrackItem>> = dao.observeDownloadedTracks()

    fun searchArtists(query: String): Flow<List<ArtistEntity>> = dao.searchArtists(query)

    fun searchAlbums(query: String): Flow<List<AlbumEntity>> = dao.searchAlbums(query)

    /** Album plus its full track list, for tapping a specific track -
     * PlayerController.play() needs the whole album playlist, not just the
     * one track that was tapped. */
    suspend fun albumAndTracks(albumId: Long): Pair<AlbumEntity, List<TrackEntity>>? {
        val album = dao.getAlbum(albumId) ?: return null
        return album to dao.getTracksForAlbum(albumId)
    }
}
