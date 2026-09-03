package com.sjpickard.pcloudmusic.data

/**
 * Find-or-create/find-or-merge helpers shared by LocalLibraryScanner and
 * RemoteLibraryScanner, so a folder scanned from either source converges on
 * one artist/album/track row rather than duplicating it - same pattern as
 * the Big Finish player's LibraryMerge.kt.
 */

suspend fun MusicDao.findOrCreateArtist(name: String, folderPath: String): Long {
    val existing = getArtistByName(name)
    return if (existing != null) {
        if (existing.folderPath != folderPath) updateArtistFolderPath(existing.id, folderPath)
        existing.id
    } else {
        insertArtist(ArtistEntity(name = name, folderPath = folderPath))
    }
}

/** Album identity is (artistId, folderPath) alone - never a tag value. This
 * is the actual fix for the official pCloud app's Lakme bug (it groups by
 * (album, artist) instead of (album, album_artist), splitting one opera
 * into ~12 "albums" because a different soloist/orchestra is credited per
 * track). One folder always means one album here, full stop. */
suspend fun MusicDao.findOrCreateAlbum(artistId: Long, folderPath: String, title: String, displayArtist: String, discTotal: Int): Long {
    val existing = findAlbumByFolder(artistId, folderPath)
    return if (existing != null) {
        updateAlbumMetadata(existing.id, title, displayArtist, discTotal)
        existing.id
    } else {
        insertAlbum(AlbumEntity(artistId = artistId, title = title, displayArtist = displayArtist, folderPath = folderPath, discTotal = discTotal))
    }
}

suspend fun MusicDao.findOrMergeTrack(
    artistId: Long,
    albumId: Long?,
    discNumber: Int,
    trackNumber: Int,
    title: String,
    performerArtist: String?,
    fileExtension: String,
    durationMs: Long,
    localPath: String?,
    remotePath: String?,
    coverPath: String?,
): Long {
    // Strongest evidence first: this exact physical file already has a row,
    // regardless of what title/position a re-scan computed for it this time.
    val byPath = findTrackByPath(albumId, remotePath, localPath)
    if (byPath != null) {
        updateTrackMetadata(byPath.id, title, discNumber, trackNumber, performerArtist)
        if (durationMs > 0) setDurationIfMissing(byPath.id, durationMs)
        coverPath?.let { setTrackCoverIfMissing(byPath.id, it) }
        localPath?.let { setLocalPathIfMissing(byPath.id, it) }
        remotePath?.let { setRemotePathIfMissing(byPath.id, it) }
        return byPath.id
    }

    val byPosition = findTrack(albumId, discNumber, trackNumber, title)
    if (byPosition != null) {
        localPath?.let { setLocalPathIfMissing(byPosition.id, it) }
        remotePath?.let { setRemotePathIfMissing(byPosition.id, it) }
        coverPath?.let { setTrackCoverIfMissing(byPosition.id, it) }
        return byPosition.id
    }

    return insertTrack(
        TrackEntity(
            artistId = artistId,
            albumId = albumId,
            discNumber = discNumber,
            trackNumber = trackNumber,
            title = title,
            performerArtist = performerArtist,
            fileExtension = fileExtension,
            durationMs = durationMs,
            localPath = localPath,
            remotePath = remotePath,
            coverPath = coverPath,
        )
    )
}
