package com.sjpickard.pcloudmusic.data

import android.util.Log
import java.io.File

private const val TAG = "LocalLibraryScanner"

private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "flac", "wav", "ogg", "aac")
private val DISC_FOLDER_PATTERN = Regex("""^Disc\s*(\d+)$""", RegexOption.IGNORE_CASE)
private val LEADING_NUMBER_PREFIX = Regex("""^\d+\s*[-._]\s*""")
private val LEADING_NUMBER = Regex("""^(\d+)""")

/**
 * Scans a local folder tree (the FUSE-mounted ~/pCloudDrive on dev machines
 * today; RemoteLibraryScanner does the same job against the pCloud REST API
 * once that's wired in on-device) into a plain Artist -> Album -> Track
 * catalog:
 *
 * - Each top-level folder under [rootDir] is one artist.
 * - Each immediate child of an artist folder that itself holds audio files
 *   or `Disc N` subfolders is one album - `Disc N` subfolders collapse into
 *   that same album rather than becoming separate albums, with the disc
 *   number taken from the folder name.
 * - Audio files sitting directly in an artist folder (no album subfolder)
 *   become loose tracks with no album at all.
 *
 * Deliberately never groups by any per-track ID3 tag - see
 * MusicDao.findOrCreateAlbum's doc comment for why that's the actual fix for
 * the official pCloud app's Lakme bug. Tags are only ever used for track
 * *metadata* (title, track/disc number as a fallback, duration, cover, and
 * the album's display-only "artist" credit).
 */
class LocalLibraryScanner(private val dao: MusicDao) {

    private data class ArtistScanResult(val artistId: Long, val stillFailedAlbumCount: Int)

    /** Returns how many artist/album folders still failed to read even
     * after the one retry pass below (see RemoteLibraryScanner.scanRoot's
     * doc comment for the full reasoning - the same "don't delete what
     * merely failed to re-scan" preserve-not-prune protection applies here,
     * for e.g. a transient permission/IO error reading one folder). */
    suspend fun scan(rootDir: File, coverCacheDir: File, excludeFolders: Set<String> = setOf("Japanese")): Int {
        coverCacheDir.mkdirs()
        Log.d(TAG, "scan() rootDir=${rootDir.path} exists=${rootDir.exists()} isDirectory=${rootDir.isDirectory}")

        // Hidden dot-folders (e.g. MediaStore's own .thumbnails cache under
        // shared storage) aren't real artist folders - skip them regardless
        // of excludeFolders, which is meant for real content the user wants
        // hidden (e.g. "Japanese"), not filesystem/OS housekeeping.
        val artistDirs = rootDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") && f.name !in excludeFolders }
            ?.sortedBy { it.name } ?: emptyList()
        Log.d(TAG, "found ${artistDirs.size} artist folders")

        val seenArtistIds = mutableSetOf<Long>()
        var stillFailedAlbumCount = 0
        val failedArtistDirs = mutableListOf<File>()
        for (artistDir in artistDirs) {
            val result = scanArtistOrNull(artistDir, coverCacheDir)
            if (result != null) {
                seenArtistIds.add(result.artistId)
                stillFailedAlbumCount += result.stillFailedAlbumCount
            } else {
                failedArtistDirs.add(artistDir)
            }
        }

        val stillFailedArtistDirs = mutableListOf<File>()
        for (artistDir in failedArtistDirs) {
            Log.d(TAG, "retrying artist scan for ${artistDir.path}")
            val result = scanArtistOrNull(artistDir, coverCacheDir)
            if (result != null) {
                seenArtistIds.add(result.artistId)
                stillFailedAlbumCount += result.stillFailedAlbumCount
            } else {
                stillFailedArtistDirs.add(artistDir)
            }
        }

        // Preserve rather than prune: this run couldn't confirm these
        // artists are still there, that's not the same as confirming
        // they're gone (mirrors the Big Finish player's identical
        // seenDiscNumbers guard in its own scanners).
        for (artistDir in stillFailedArtistDirs) {
            dao.getArtistByName(artistDir.name)?.let { seenArtistIds.add(it.id) }
        }

        if (seenArtistIds.isNotEmpty()) {
            dao.deleteStaleArtists(seenArtistIds.toList())
        }
        dao.backfillMissingAlbumCovers()
        dao.backfillMissingArtistCovers()
        return stillFailedArtistDirs.size + stillFailedAlbumCount
    }

    private suspend fun scanArtistOrNull(artistDir: File, coverCacheDir: File): ArtistScanResult? =
        try {
            scanArtist(artistDir, coverCacheDir)
        } catch (e: Exception) {
            Log.e(TAG, "scanArtist failed for ${artistDir.path}", e)
            null
        }

    private suspend fun scanArtist(artistDir: File, coverCacheDir: File): ArtistScanResult {
        val artistName = artistDir.name
        val artistId = dao.findOrCreateArtist(artistName, artistDir.path)

        val children = artistDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()
        val looseFiles = artistDir.listFiles { f -> f.isFile && isAudioFile(f) }?.sortedBy { it.name } ?: emptyList()

        val seenAlbumIds = mutableSetOf<Long>()
        val failedAlbumDirs = mutableListOf<File>()
        for (childDir in children) {
            val albumId = scanAlbumOrNull(artistId, artistName, childDir, coverCacheDir)
            if (albumId != null) seenAlbumIds.add(albumId) else failedAlbumDirs.add(childDir)
        }

        val stillFailedAlbumDirs = mutableListOf<File>()
        for (childDir in failedAlbumDirs) {
            Log.d(TAG, "retrying album scan for ${childDir.path}")
            val albumId = scanAlbumOrNull(artistId, artistName, childDir, coverCacheDir)
            if (albumId != null) seenAlbumIds.add(albumId) else stillFailedAlbumDirs.add(childDir)
        }

        // Same preserve-not-prune protection as scan(), one level down.
        for (childDir in stillFailedAlbumDirs) {
            dao.findAlbumByFolder(artistId, childDir.path)?.let { seenAlbumIds.add(it.id) }
        }

        if (seenAlbumIds.isNotEmpty()) {
            dao.deleteStaleAlbums(artistId, seenAlbumIds.toList())
        }

        scanLooseTracks(artistId, looseFiles, coverCacheDir)

        return ArtistScanResult(artistId, stillFailedAlbumDirs.size)
    }

    /** Null both when [childDir] genuinely isn't an album folder (no audio,
     * no Disc N subfolders - logged and never retried) and when scanning it
     * threw - see RemoteLibraryScanner.scanAlbumOrNull's doc comment for why
     * that's an acceptable trade-off. */
    private suspend fun scanAlbumOrNull(artistId: Long, artistName: String, childDir: File, coverCacheDir: File): Long? {
        val discDirs = childDir.listFiles { f -> f.isDirectory && DISC_FOLDER_PATTERN.matches(f.name) }
            ?.sortedBy { discNumberOf(it) } ?: emptyList()
        val directAudioFiles = childDir.listFiles { f -> f.isFile && isAudioFile(f) }?.sortedBy { it.name } ?: emptyList()

        if (discDirs.isEmpty() && directAudioFiles.isEmpty()) {
            Log.d(TAG, "skipping non-album folder (no audio, no Disc N subfolders): ${childDir.path}")
            return null
        }
        return try {
            scanAlbum(artistId, artistName, childDir, discDirs, directAudioFiles, coverCacheDir)
        } catch (e: Exception) {
            Log.e(TAG, "scanAlbum failed for ${childDir.path}", e)
            null
        }
    }

    /** One album folder - [discDirs] non-empty means a multi-disc release
     * (`Disc N` subfolders collapse into this one album); otherwise
     * [directAudioFiles] sitting straight in the album folder are disc 1. */
    private suspend fun scanAlbum(
        artistId: Long,
        artistName: String,
        albumDir: File,
        discDirs: List<File>,
        directAudioFiles: List<File>,
        coverCacheDir: File,
    ): Long {
        val discTotal = if (discDirs.isNotEmpty()) discDirs.maxOf { discNumberOf(it) } else 1
        val albumId = dao.findOrCreateAlbum(artistId, albumDir.path, title = albumDir.name, displayArtist = artistName, discTotal = discTotal)

        val discGroups: List<Pair<Int, List<File>>> = if (discDirs.isNotEmpty()) {
            discDirs.map { dir -> discNumberOf(dir) to (dir.listFiles { f -> f.isFile && isAudioFile(f) }?.sortedBy { it.name } ?: emptyList()) }
        } else {
            listOf(1 to directAudioFiles)
        }

        var albumArtistTag: String? = null
        val seenTrackIds = mutableSetOf<Long>()
        for ((discNumber, files) in discGroups.sortedBy { it.first }) {
            files.forEachIndexed { index, file ->
                val tags = readTags(file, includeCoverArt = true)
                if (albumArtistTag == null) albumArtistTag = tags?.albumArtist?.takeIf { it.isNotBlank() }

                val trackNumber = tags?.trackNumber ?: trackNumberFromFileName(file) ?: (index + 1)
                val title = stripLeadingNumberPrefix(tags?.title?.takeIf { it.isNotBlank() } ?: titleFromFileName(file))
                val coverPath = tags?.let { cacheCoverArt(it, coverCacheDir, "album_${albumId}_${discNumber}_$trackNumber") }
                coverPath?.let { dao.setAlbumCoverIfMissing(albumId, it) }

                val trackId = dao.findOrMergeTrack(
                    artistId = artistId,
                    albumId = albumId,
                    discNumber = discNumber,
                    trackNumber = trackNumber,
                    title = title,
                    performerArtist = tags?.artist,
                    fileExtension = file.extension.lowercase(),
                    durationMs = tags?.estimatedDurationMs ?: 0,
                    localPath = file.path,
                    remotePath = null,
                    coverPath = coverPath,
                )
                seenTrackIds.add(trackId)
            }
        }
        dao.deleteStaleAlbumTracks(albumId, seenTrackIds.toList())

        // The album's displayed "artist" - the ID3 album_artist tag if any
        // track carried one (e.g. "Dame Joan Sutherland" for Lakme), else
        // just the folder-derived artist name. Display only, never identity.
        dao.updateAlbumMetadata(albumId, title = albumDir.name, displayArtist = albumArtistTag ?: artistName, discTotal = discTotal)

        return albumId
    }

    private suspend fun scanLooseTracks(artistId: Long, files: List<File>, coverCacheDir: File) {
        val seenTrackIds = mutableSetOf<Long>()
        files.forEachIndexed { index, file ->
            val tags = readTags(file, includeCoverArt = true)
            val trackNumber = tags?.trackNumber ?: trackNumberFromFileName(file) ?: (index + 1)
            val title = stripLeadingNumberPrefix(tags?.title?.takeIf { it.isNotBlank() } ?: titleFromFileName(file))
            val coverPath = tags?.let { cacheCoverArt(it, coverCacheDir, "loose_${artistId}_$trackNumber") }

            val trackId = dao.findOrMergeTrack(
                artistId = artistId,
                albumId = null,
                discNumber = 1,
                trackNumber = trackNumber,
                title = title,
                performerArtist = tags?.artist,
                fileExtension = file.extension.lowercase(),
                durationMs = tags?.estimatedDurationMs ?: 0,
                localPath = file.path,
                remotePath = null,
                coverPath = coverPath,
            )
            seenTrackIds.add(trackId)
        }
        if (seenTrackIds.isNotEmpty() || files.isEmpty()) {
            dao.deleteStaleLooseTracks(artistId, seenTrackIds.toList())
        }
    }

    private fun discNumberOf(dir: File): Int =
        DISC_FOLDER_PATTERN.matchEntire(dir.name)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    private fun isAudioFile(file: File): Boolean = file.extension.lowercase() in AUDIO_EXTENSIONS

    /** Falls back to the filename (minus extension and any leading track-
     * number prefix) when a file has no readable tag title at all - notably
     * every non-MP3 format today, since Id3Reader only understands ID3v2
     * (m4a/flac tag support is a later improvement, not needed for the
     * folder-grouping fix this scanner exists for). */
    private fun titleFromFileName(file: File): String =
        stripLeadingNumberPrefix(file.nameWithoutExtension)

    private fun stripLeadingNumberPrefix(text: String): String =
        text.replace(LEADING_NUMBER_PREFIX, "").trim().ifBlank { text }

    private fun trackNumberFromFileName(file: File): Int? =
        LEADING_NUMBER.find(file.nameWithoutExtension)?.groupValues?.get(1)?.toIntOrNull()

    private fun readTags(file: File, includeCoverArt: Boolean): Id3Reader.Id3Tags? =
        try {
            Id3Reader.read(file.path, includeCoverArt = includeCoverArt)
        } catch (e: Exception) {
            Log.e(TAG, "readTags failed for ${file.path}", e)
            null
        }

    private fun cacheCoverArt(tags: Id3Reader.Id3Tags, coverCacheDir: File, cacheKey: String): String? {
        val bytes = tags.coverArt ?: return null
        val extension = if (bytes.size >= 2 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) "png" else "jpg"
        val cacheFile = File(coverCacheDir, "$cacheKey.$extension")
        return try {
            cacheFile.writeBytes(bytes)
            cacheFile.path
        } catch (e: Exception) {
            Log.e(TAG, "failed to write cover cache file ${cacheFile.path}", e)
            null
        }
    }
}
