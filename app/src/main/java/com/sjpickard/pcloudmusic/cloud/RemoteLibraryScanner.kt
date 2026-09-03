package com.sjpickard.pcloudmusic.cloud

import android.util.Log
import com.sjpickard.pcloudmusic.data.Id3Reader
import com.sjpickard.pcloudmusic.data.MusicDao
import com.sjpickard.pcloudmusic.data.findOrCreateAlbum
import com.sjpickard.pcloudmusic.data.findOrCreateArtist
import com.sjpickard.pcloudmusic.data.findOrMergeTrack
import java.io.File

private const val TAG = "RemoteLibraryScanner"
private const val ID3_PREFIX_BYTES = 200_000L // comfortably covers a tag + embedded cover art

private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "flac", "wav", "ogg", "aac")
private val DISC_FOLDER_PATTERN = Regex("""^Disc\s*(\d+)$""", RegexOption.IGNORE_CASE)
private val LEADING_NUMBER_PREFIX = Regex("""^\d+\s*[-._]\s*""")
private val LEADING_NUMBER = Regex("""^(\d+)""")

/**
 * Same Artist -> Album -> Track grouping as LocalLibraryScanner (see its doc
 * comment for the full "why folder, never tag" reasoning behind the Lakme
 * fix), against the pCloud REST API instead of local files. A folder scanned
 * from both sources - dev testing against ~/pCloudDrive locally, this against
 * the live API on-device - converges on one row each via MusicDao's find-
 * or-create/find-or-merge helpers (MusicLibraryMerge.kt), not a duplicate.
 */
class RemoteLibraryScanner(private val dao: MusicDao, private val apiClient: PCloudApiClient) {

    private data class ArtistScanResult(val artistId: Long, val stillFailedAlbumCount: Int)

    /** Returns how many artist/album folders still couldn't be listed even
     * after the one retry pass below, so the caller can tell the user a
     * sync was incomplete rather than silently presenting a stale-looking
     * library. A transient failure (e.g. the on-device network dropping for
     * part of a scan - observed live: a ~70s Wi-Fi blip mid-scan took out
     * listChildren for 46 of 112 artist folders in one run) must not also
     * cause dao.deleteStaleArtists/deleteStaleAlbums below to delete that
     * artist/album's already-synced catalog just because this run didn't
     * re-see it - see the "preserve" loops below, which add back the
     * existing DB id for anything still-failed so it survives the prune. */
    suspend fun scanRoot(rootPath: String, coverCacheDir: File, excludeFolders: Set<String> = setOf("Japanese")): Int {
        coverCacheDir.mkdirs()
        val rootItems = apiClient.listChildren(rootPath)
        val artistDirs = rootItems.filter { it.isFolder && it.name !in excludeFolders }.sortedBy { it.name }
        Log.d(TAG, "scanRoot($rootPath): found ${artistDirs.size} artist folders")

        val seenArtistIds = mutableSetOf<Long>()
        var stillFailedAlbumCount = 0
        val failedArtists = mutableListOf<PCloudItem>()
        for (artistItem in artistDirs) {
            val result = scanArtistOrNull(rootPath, artistItem, coverCacheDir)
            if (result != null) {
                seenArtistIds.add(result.artistId)
                stillFailedAlbumCount += result.stillFailedAlbumCount
            } else {
                failedArtists.add(artistItem)
            }
        }

        val stillFailedArtists = mutableListOf<PCloudItem>()
        for (artistItem in failedArtists) {
            Log.d(TAG, "retrying artist scan for ${artistItem.name}")
            val result = scanArtistOrNull(rootPath, artistItem, coverCacheDir)
            if (result != null) {
                seenArtistIds.add(result.artistId)
                stillFailedAlbumCount += result.stillFailedAlbumCount
            } else {
                stillFailedArtists.add(artistItem)
            }
        }

        // Preserve rather than prune: this run couldn't confirm these
        // artists are still there, that's not the same as confirming
        // they're gone.
        for (artistItem in stillFailedArtists) {
            dao.getArtistByName(artistItem.name)?.let { seenArtistIds.add(it.id) }
        }

        if (seenArtistIds.isNotEmpty()) {
            dao.deleteStaleArtists(seenArtistIds.toList())
        }
        dao.backfillMissingAlbumCovers()
        dao.backfillMissingArtistCovers()
        return stillFailedArtists.size + stillFailedAlbumCount
    }

    private suspend fun scanArtistOrNull(rootPath: String, artistItem: PCloudItem, coverCacheDir: File): ArtistScanResult? {
        val artistPath = "$rootPath/${artistItem.name}"
        return try {
            scanArtist(artistPath, artistItem.name, coverCacheDir)
        } catch (e: Exception) {
            Log.e(TAG, "scanArtist failed for $artistPath", e)
            null
        }
    }

    private suspend fun scanArtist(artistPath: String, artistName: String, coverCacheDir: File): ArtistScanResult {
        val artistId = dao.findOrCreateArtist(artistName, artistPath)
        val children = apiClient.listChildren(artistPath)
        val albumDirs = children.filter { it.isFolder }
        val looseFiles = children.filter { !it.isFolder && isAudioFile(it.name) }.sortedBy { it.name }

        val seenAlbumIds = mutableSetOf<Long>()
        val failedAlbums = mutableListOf<PCloudItem>()
        for (albumItem in albumDirs) {
            when (val outcome = scanAlbumOutcome(artistId, artistName, artistPath, albumItem, coverCacheDir)) {
                is AlbumScanOutcome.Scanned -> seenAlbumIds.add(outcome.albumId)
                AlbumScanOutcome.NotAnAlbum -> {} // correctly excluded, not a failure - never retried
                AlbumScanOutcome.Failed -> failedAlbums.add(albumItem)
            }
        }

        val stillFailedAlbums = mutableListOf<PCloudItem>()
        for (albumItem in failedAlbums) {
            Log.d(TAG, "retrying album scan for ${albumItem.name}")
            when (val outcome = scanAlbumOutcome(artistId, artistName, artistPath, albumItem, coverCacheDir)) {
                is AlbumScanOutcome.Scanned -> seenAlbumIds.add(outcome.albumId)
                AlbumScanOutcome.NotAnAlbum -> {}
                AlbumScanOutcome.Failed -> stillFailedAlbums.add(albumItem)
            }
        }

        // Same preserve-not-prune protection as scanRoot, one level down -
        // a still-failed album (rather than one genuinely not an album at
        // all, which never reaches findOrCreateAlbum in the first place)
        // keeps its existing tracks.
        for (albumItem in stillFailedAlbums) {
            dao.findAlbumByFolder(artistId, "$artistPath/${albumItem.name}")?.let { seenAlbumIds.add(it.id) }
        }

        if (seenAlbumIds.isNotEmpty()) {
            dao.deleteStaleAlbums(artistId, seenAlbumIds.toList())
        }

        scanLooseTracks(artistId, looseFiles, coverCacheDir)
        return ArtistScanResult(artistId, stillFailedAlbums.size)
    }

    private sealed class AlbumScanOutcome {
        data class Scanned(val albumId: Long) : AlbumScanOutcome()
        data object NotAnAlbum : AlbumScanOutcome()
        data object Failed : AlbumScanOutcome()
    }

    /** Distinguishes a folder that's genuinely not an album (no audio, no
     * Disc N subfolders - correctly excluded, never retried, never counted
     * as a failure) from one whose listing actually threw (retried once,
     * and only still-failing ones are preserved from deletion / counted in
     * scanRoot's returned failure count). Collapsing these two into a
     * single null used to make every scan's "failed" count noisy with
     * ordinary non-album folders (e.g. bonus-content folders) alongside
     * genuine network failures. */
    private suspend fun scanAlbumOutcome(
        artistId: Long,
        artistName: String,
        artistPath: String,
        albumItem: PCloudItem,
        coverCacheDir: File,
    ): AlbumScanOutcome {
        val albumPath = "$artistPath/${albumItem.name}"
        return try {
            val albumChildren = apiClient.listChildren(albumPath)
            val discDirs = albumChildren.filter { it.isFolder && DISC_FOLDER_PATTERN.matches(it.name) }
                .sortedBy { discNumberOf(it.name) }
            val directAudioFiles = albumChildren.filter { !it.isFolder && isAudioFile(it.name) }.sortedBy { it.name }
            if (discDirs.isEmpty() && directAudioFiles.isEmpty()) {
                Log.d(TAG, "skipping non-album folder (no audio, no Disc N subfolders): $albumPath")
                return AlbumScanOutcome.NotAnAlbum
            }
            AlbumScanOutcome.Scanned(
                scanAlbum(artistId, artistName, albumPath, albumItem.name, discDirs.map { it.name }, directAudioFiles, coverCacheDir)
            )
        } catch (e: Exception) {
            Log.e(TAG, "scanAlbum failed for $albumPath", e)
            AlbumScanOutcome.Failed
        }
    }

    private suspend fun scanAlbum(
        artistId: Long,
        artistName: String,
        albumPath: String,
        albumName: String,
        discDirNames: List<String>,
        directAudioFiles: List<PCloudItem>,
        coverCacheDir: File,
    ): Long {
        val discTotal = if (discDirNames.isNotEmpty()) discDirNames.maxOf { discNumberOf(it) } else 1
        val albumId = dao.findOrCreateAlbum(artistId, albumPath, title = albumName, displayArtist = artistName, discTotal = discTotal)

        val discGroups: List<Pair<Int, List<PCloudItem>>> = if (discDirNames.isNotEmpty()) {
            discDirNames.map { discName ->
                val files = apiClient.listChildren("$albumPath/$discName").filter { !it.isFolder && isAudioFile(it.name) }.sortedBy { it.name }
                discNumberOf(discName) to files
            }
        } else {
            listOf(1 to directAudioFiles)
        }

        var albumArtistTag: String? = null
        val seenTrackIds = mutableSetOf<Long>()
        for ((discNumber, files) in discGroups.sortedBy { it.first }) {
            files.forEachIndexed { index, item ->
                val tags = readTags(item)
                if (albumArtistTag == null) albumArtistTag = tags?.albumArtist?.takeIf { it.isNotBlank() }

                val trackNumber = tags?.trackNumber ?: trackNumberFromName(item.name) ?: (index + 1)
                val title = stripLeadingNumberPrefix(tags?.title?.takeIf { it.isNotBlank() } ?: titleFromName(item.name))
                val coverPath = tags?.let { cacheCoverArt(it, coverCacheDir, "album_${albumId}_${discNumber}_$trackNumber") }
                coverPath?.let { dao.setAlbumCoverIfMissing(albumId, it) }

                val trackId = dao.findOrMergeTrack(
                    artistId = artistId,
                    albumId = albumId,
                    discNumber = discNumber,
                    trackNumber = trackNumber,
                    title = title,
                    performerArtist = tags?.artist,
                    fileExtension = extensionOf(item.name),
                    durationMs = tags?.estimatedDurationMs ?: 0,
                    localPath = null,
                    remotePath = item.fileId?.toString(),
                    coverPath = coverPath,
                )
                seenTrackIds.add(trackId)
            }
        }
        dao.deleteStaleAlbumTracks(albumId, seenTrackIds.toList())
        dao.updateAlbumMetadata(albumId, title = albumName, displayArtist = albumArtistTag ?: artistName, discTotal = discTotal)
        return albumId
    }

    private suspend fun scanLooseTracks(artistId: Long, files: List<PCloudItem>, coverCacheDir: File) {
        val seenTrackIds = mutableSetOf<Long>()
        files.forEachIndexed { index, item ->
            val tags = readTags(item)
            val trackNumber = tags?.trackNumber ?: trackNumberFromName(item.name) ?: (index + 1)
            val title = stripLeadingNumberPrefix(tags?.title?.takeIf { it.isNotBlank() } ?: titleFromName(item.name))
            val coverPath = tags?.let { cacheCoverArt(it, coverCacheDir, "loose_${artistId}_$trackNumber") }

            val trackId = dao.findOrMergeTrack(
                artistId = artistId,
                albumId = null,
                discNumber = 1,
                trackNumber = trackNumber,
                title = title,
                performerArtist = tags?.artist,
                fileExtension = extensionOf(item.name),
                durationMs = tags?.estimatedDurationMs ?: 0,
                localPath = null,
                remotePath = item.fileId?.toString(),
                coverPath = coverPath,
            )
            seenTrackIds.add(trackId)
        }
        if (seenTrackIds.isNotEmpty() || files.isEmpty()) {
            dao.deleteStaleLooseTracks(artistId, seenTrackIds.toList())
        }
    }

    /** Fetches just enough of the file's front to read its ID3 tag, rather
     * than downloading the whole thing during a scan - a per-file round trip
     * (getDownloadUrl then a ranged GET), same shape as OneDriveApiClient/
     * bigfinish's RemoteLibraryScanner. */
    private suspend fun readTags(item: PCloudItem): Id3Reader.Id3Tags? {
        val fileId = item.fileId ?: return null
        return try {
            val downloadUrl = apiClient.getDownloadUrl(fileId) ?: return null
            val prefix = apiClient.fetchPrefix(downloadUrl, maxBytes = ID3_PREFIX_BYTES)
            if (prefix.isEmpty()) return null
            Id3Reader.readFromPrefix(prefix, totalFileSize = item.size, includeCoverArt = true)
        } catch (e: Exception) {
            Log.e(TAG, "readTags failed for ${item.name}", e)
            null
        }
    }

    private fun discNumberOf(name: String): Int =
        DISC_FOLDER_PATTERN.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    private fun isAudioFile(name: String): Boolean = extensionOf(name) in AUDIO_EXTENSIONS

    private fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    private fun titleFromName(name: String): String = stripLeadingNumberPrefix(name.substringBeforeLast('.'))

    private fun stripLeadingNumberPrefix(text: String): String =
        text.replace(LEADING_NUMBER_PREFIX, "").trim().ifBlank { text }

    private fun trackNumberFromName(name: String): Int? =
        LEADING_NUMBER.find(name.substringBeforeLast('.'))?.groupValues?.get(1)?.toIntOrNull()

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
