package com.sjpickard.pcloudmusic.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A top-level folder under the music root, e.g. "Delibes" or "Duran Duran".
 * One artist folder can contain several albums (or loose tracks with no
 * album at all) - see AlbumEntity/TrackEntity.
 */
@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val folderPath: String,
    val coverPath: String? = null, // borrowed from the first album's cover
)

/**
 * One album folder. Identity comes from the folder alone, never from any
 * per-track ID3 `artist` tag - that's the whole fix for the official
 * pCloud app's Lakme bug, which groups by (album, artist) and ends up
 * splitting one opera into ~12 "albums" because a different soloist/
 * orchestra is credited per track. `Disc N` subfolders collapse into their
 * parent album rather than becoming separate albums - see
 * LocalLibraryScanner/RemoteLibraryScanner.
 */
@Entity(
    tableName = "albums",
    foreignKeys = [
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("artistId")],
)
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val artistId: Long,
    val title: String,
    // Display only - the ID3 `album_artist` tag if present (e.g. "Dame Joan
    // Sutherland" for Lakme), else just the folder-derived artist name.
    // Never used for grouping/identity, only for what's shown in the UI.
    val displayArtist: String,
    val folderPath: String,
    val coverPath: String? = null,
    val discTotal: Int = 1, // highest `Disc N` subfolder number seen, else 1
)

/**
 * A single playable track. `albumId` is null for a loose track sitting
 * directly in an artist folder with no album subfolder at all.
 */
@Entity(
    tableName = "tracks",
    foreignKeys = [
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("artistId"), Index("albumId")],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val artistId: Long,
    val albumId: Long?,
    val discNumber: Int,
    val trackNumber: Int,
    val title: String,
    // The per-track ID3 `artist` tag (e.g. the soloist/orchestra performing
    // this specific track) - kept for display/credits only, never used to
    // decide which album this track belongs to.
    val performerArtist: String?,
    val fileExtension: String, // "mp3"/"m4a"/"flac"/... - needed for DownloadWorker's destination filename, since remotePath is just a bare pCloud fileid, not a name
    val durationMs: Long,
    val localPath: String?,   // set once downloaded for offline playback
    val remotePath: String?,  // pCloud file id, once the API integration lands
    val lastPositionMs: Long = 0, // resume point
    val coverPath: String? = null,
    val lastPlayedAt: Long? = null, // epoch millis - drives the "Continue listening" shelf
)
