package com.sjpickard.pcloudmusic.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** One track in progress, for the home screen's "Continue listening" shelf. */
data class ContinueListeningItem(
    val trackId: Long,
    val trackTitle: String,
    val coverPath: String?,
    val positionMs: Long,
    val durationMs: Long,
    val albumId: Long?,
    val artistId: Long,
    val artistName: String,
)

/** One downloaded (localPath set) track, with just enough artist/album
 * context to display and navigate from the Downloads screen - albumId/
 * albumTitle are null for a loose track. */
data class DownloadedTrackItem(
    val trackId: Long,
    val trackTitle: String,
    val coverPath: String?,
    val localPath: String,
    val artistId: Long,
    val artistName: String,
    val albumId: Long?,
    val albumTitle: String?,
)

@Dao
interface MusicDao {

    // --- Artists ---------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArtist(artist: ArtistEntity): Long

    @Query("SELECT * FROM artists ORDER BY name COLLATE NOCASE ASC")
    fun observeArtists(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artists WHERE id = :artistId")
    suspend fun getArtist(artistId: Long): ArtistEntity?

    @Query("SELECT * FROM artists WHERE id = :artistId")
    fun observeArtist(artistId: Long): Flow<ArtistEntity?>

    /** Artist folders are unique by name at the scan root - the same artist
     * folder scanned again (a re-scan, or later a remote scan of the same
     * content already found locally) converges on one row. */
    @Query("SELECT * FROM artists WHERE name = :name LIMIT 1")
    suspend fun getArtistByName(name: String): ArtistEntity?

    @Query("UPDATE artists SET folderPath = :folderPath WHERE id = :artistId")
    suspend fun updateArtistFolderPath(artistId: Long, folderPath: String)

    @Query("UPDATE artists SET coverPath = :coverPath WHERE id = :artistId AND coverPath IS NULL")
    suspend fun setArtistCoverIfMissing(artistId: Long, coverPath: String)

    @Query(
        "UPDATE artists SET coverPath = (" +
            "SELECT a.coverPath FROM albums a WHERE a.artistId = artists.id AND a.coverPath IS NOT NULL LIMIT 1" +
            ") WHERE coverPath IS NULL"
    )
    suspend fun backfillMissingArtistCovers()

    @Query("DELETE FROM artists WHERE id NOT IN (:keepArtistIds)")
    suspend fun deleteStaleArtists(keepArtistIds: List<Long>)

    // --- Albums ------------------------------------------------------------

    @Query("SELECT * FROM albums WHERE id = :albumId")
    suspend fun getAlbum(albumId: Long): AlbumEntity?

    @Query("SELECT * FROM albums WHERE id = :albumId")
    fun observeAlbum(albumId: Long): Flow<AlbumEntity?>

    @Query("SELECT * FROM albums WHERE artistId = :artistId ORDER BY title COLLATE NOCASE ASC")
    fun observeAlbums(artistId: Long): Flow<List<AlbumEntity>>

    /** Every album in the library, artist-agnostic - backs the flat "browse
     * all albums" screen (as opposed to observeAlbums, which is scoped to
     * one artist). */
    @Query("SELECT * FROM albums ORDER BY title COLLATE NOCASE ASC")
    fun observeAllAlbums(): Flow<List<AlbumEntity>>

    /** Album identity is (artistId, folder path) - matches the "one folder =
     * one album" grouping rule, never a tag value. See
     * LocalLibraryScanner/RemoteLibraryScanner. */
    @Query("SELECT * FROM albums WHERE artistId = :artistId AND folderPath = :folderPath LIMIT 1")
    suspend fun findAlbumByFolder(artistId: Long, folderPath: String): AlbumEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlbum(album: AlbumEntity): Long

    @Query("UPDATE albums SET title = :title, displayArtist = :displayArtist, discTotal = :discTotal WHERE id = :albumId")
    suspend fun updateAlbumMetadata(albumId: Long, title: String, displayArtist: String, discTotal: Int)

    @Query("UPDATE albums SET coverPath = :coverPath WHERE id = :albumId AND coverPath IS NULL")
    suspend fun setAlbumCoverIfMissing(albumId: Long, coverPath: String)

    @Query(
        "UPDATE albums SET coverPath = (" +
            "SELECT t.coverPath FROM tracks t WHERE t.albumId = albums.id AND t.coverPath IS NOT NULL LIMIT 1" +
            ") WHERE coverPath IS NULL"
    )
    suspend fun backfillMissingAlbumCovers()

    @Query("DELETE FROM albums WHERE artistId = :artistId AND id NOT IN (:keepAlbumIds)")
    suspend fun deleteStaleAlbums(artistId: Long, keepAlbumIds: List<Long>)

    // --- Tracks --------------------------------------------------------

    @Query("SELECT * FROM tracks WHERE albumId = :albumId ORDER BY discNumber ASC, trackNumber ASC")
    fun observeTracksForAlbum(albumId: Long): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE albumId = :albumId ORDER BY discNumber ASC, trackNumber ASC")
    suspend fun getTracksForAlbum(albumId: Long): List<TrackEntity>

    /** Loose tracks directly under an artist folder, no album at all. */
    @Query("SELECT * FROM tracks WHERE artistId = :artistId AND albumId IS NULL ORDER BY title COLLATE NOCASE ASC")
    fun observeLooseTracks(artistId: Long): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :trackId")
    suspend fun getTrack(trackId: Long): TrackEntity?

    // Keyed on (albumId, discNumber, trackNumber, title) rather than just
    // position - two different pieces can legitimately share a track number
    // across a re-scan if numbering was ever ambiguous; matching title too
    // means a genuine retitle still self-heals via findTrackByPath below
    // rather than forking a duplicate row.
    @Query(
        "SELECT * FROM tracks WHERE " +
            "((:albumId IS NULL AND albumId IS NULL) OR albumId = :albumId) AND " +
            "discNumber = :discNumber AND trackNumber = :trackNumber AND title = :title LIMIT 1"
    )
    suspend fun findTrack(albumId: Long?, discNumber: Int, trackNumber: Int, title: String): TrackEntity?

    @Query(
        "SELECT * FROM tracks WHERE " +
            "((:albumId IS NULL AND albumId IS NULL) OR albumId = :albumId) AND " +
            "((:remotePath IS NOT NULL AND remotePath = :remotePath) OR (:localPath IS NOT NULL AND localPath = :localPath)) " +
            "LIMIT 1"
    )
    suspend fun findTrackByPath(albumId: Long?, remotePath: String?, localPath: String?): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: TrackEntity): Long

    @Query("UPDATE tracks SET title = :title, discNumber = :discNumber, trackNumber = :trackNumber, performerArtist = :performerArtist WHERE id = :trackId")
    suspend fun updateTrackMetadata(trackId: Long, title: String, discNumber: Int, trackNumber: Int, performerArtist: String?)

    @Query("UPDATE tracks SET coverPath = :coverPath WHERE id = :trackId AND coverPath IS NULL")
    suspend fun setTrackCoverIfMissing(trackId: Long, coverPath: String)

    @Query("UPDATE tracks SET durationMs = :durationMs WHERE id = :trackId AND durationMs <= 0")
    suspend fun setDurationIfMissing(trackId: Long, durationMs: Long)

    @Query("UPDATE tracks SET lastPositionMs = :positionMs WHERE id = :trackId")
    suspend fun updatePositionMs(trackId: Long, positionMs: Long)

    @Query("UPDATE tracks SET lastPlayedAt = :timestamp WHERE id = :trackId")
    suspend fun touchLastPlayed(trackId: Long, timestamp: Long)

    @Query("UPDATE tracks SET localPath = :localPath WHERE id = :trackId AND localPath IS NULL")
    suspend fun setLocalPathIfMissing(trackId: Long, localPath: String)

    @Query("UPDATE tracks SET remotePath = :remotePath WHERE id = :trackId AND remotePath IS NULL")
    suspend fun setRemotePathIfMissing(trackId: Long, remotePath: String)

    /** Only meant to be called when remotePath is still set - see
     * DownloadManager.removeLocalCopy, which enforces that. */
    @Query("UPDATE tracks SET localPath = NULL WHERE id = :trackId")
    suspend fun clearLocalPath(trackId: Long)

    @Query("DELETE FROM tracks WHERE albumId = :albumId AND id NOT IN (:keepTrackIds)")
    suspend fun deleteStaleAlbumTracks(albumId: Long, keepTrackIds: List<Long>)

    @Query("DELETE FROM tracks WHERE artistId = :artistId AND albumId IS NULL AND id NOT IN (:keepTrackIds)")
    suspend fun deleteStaleLooseTracks(artistId: Long, keepTrackIds: List<Long>)

    /** In-progress tracks, most recently played first, for the home
     * screen's "Continue listening" shelf. */
    @Query(
        "SELECT t.id AS trackId, t.title AS trackTitle, t.coverPath AS coverPath, " +
            "t.lastPositionMs AS positionMs, t.durationMs AS durationMs, " +
            "t.albumId AS albumId, t.artistId AS artistId, ar.name AS artistName " +
            "FROM tracks t " +
            "JOIN artists ar ON ar.id = t.artistId " +
            "WHERE t.lastPositionMs > 0 " +
            "ORDER BY t.lastPlayedAt DESC " +
            "LIMIT 5"
    )
    fun observeContinueListening(): Flow<List<ContinueListeningItem>>

    /** Every downloaded track, for the Downloads screen - grouped by artist
     * then album in the query itself so the screen can render it straight
     * off the flow without any client-side sorting. */
    @Query(
        "SELECT t.id AS trackId, t.title AS trackTitle, t.coverPath AS coverPath, " +
            "t.localPath AS localPath, t.artistId AS artistId, ar.name AS artistName, " +
            "al.id AS albumId, al.title AS albumTitle " +
            "FROM tracks t " +
            "JOIN artists ar ON ar.id = t.artistId " +
            "LEFT JOIN albums al ON al.id = t.albumId " +
            "WHERE t.localPath IS NOT NULL " +
            "ORDER BY ar.name COLLATE NOCASE ASC, al.title COLLATE NOCASE ASC, t.discNumber ASC, t.trackNumber ASC"
    )
    fun observeDownloadedTracks(): Flow<List<DownloadedTrackItem>>

    @Query("SELECT * FROM tracks WHERE localPath IS NOT NULL")
    suspend fun getDownloadedTracks(): List<TrackEntity>

    /** Simple cross-library search by title (album/track) or artist name -
     * backs SearchScreen. */
    @Query("SELECT * FROM artists WHERE name LIKE '%' || :query || '%' COLLATE NOCASE ORDER BY name ASC")
    fun searchArtists(query: String): Flow<List<ArtistEntity>>

    @Query(
        "SELECT * FROM albums WHERE title LIKE '%' || :query || '%' COLLATE NOCASE " +
            "OR displayArtist LIKE '%' || :query || '%' COLLATE NOCASE ORDER BY title ASC"
    )
    fun searchAlbums(query: String): Flow<List<AlbumEntity>>
}
