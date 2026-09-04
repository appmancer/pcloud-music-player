package com.sjpickard.pcloudmusic.data

import java.io.RandomAccessFile

/**
 * Tag/cover-art reader for the three formats this library actually contains
 * (mirrors AUDIO_EXTENSIONS in Local/RemoteLibraryScanner - OGG/WAV are
 * scanned as audio files but get no tags, same as an unsupported format
 * would): ID3v2.2/2.3/2.4 (MP3), FLAC (Vorbis comments + METADATA_BLOCK_PICTURE),
 * and MP4/M4A (iTunes-style ilst atoms). Dispatches on each format's magic
 * bytes - see [read]/[readFromPrefix]. The MP3 reader was carried over
 * verbatim from the Big Finish player (see that project's Id3Reader.kt for
 * the full history of why this exists instead of
 * android.media.MediaMetadataRetriever); FLAC/MP4 support was added once live
 * testing against a real pCloud library showed most albums had no cover art
 * at all - this app's library turned out to be mostly FLAC.
 *
 * Already reads the album-artist tag (TPE2 / ALBUMARTIST / aART) distinctly
 * from the track artist (TPE1 / ARTIST / ©ART) in all three formats - exactly
 * what this app's folder-based album grouping needs, since the official
 * pCloud app's bug (grouping by (album, artist) instead of
 * (album, album_artist)) is exactly what this app exists to avoid repeating.
 *
 * One asymmetry worth knowing: local reads ([read]) use RandomAccessFile, so
 * skipping past a large box/frame is a free seek regardless of size; remote
 * reads ([readFromPrefix]) only ever see whatever prefix of the file was
 * fetched (200KB by default - see RemoteLibraryScanner.ID3_PREFIX_BYTES).
 * ID3 and FLAC both guarantee their tag data sits at the very front of the
 * file, so this doesn't matter for them - but a badly-multiplexed MP4 (audio
 * `mdat` written before the `moov` box holding its tags, common for files
 * not encoded with "faststart") can have its tags sit past that prefix
 * entirely, in which case remote scanning just finds nothing, same as an
 * unsupported format would. Locally-scanned M4A files aren't affected either
 * way, since seeking past `mdat` costs nothing.
 */
object Id3Reader {

    private const val ASSUMED_BITRATE_BPS = 256_000L
    private const val FRAME_PROBE_BYTES = 64

    data class Id3Tags(
        val title: String? = null,
        val artist: String? = null,
        val albumArtist: String? = null,
        val album: String? = null,
        val trackNumber: Int? = null,
        val discNumber: Int? = null,
        val coverArt: ByteArray? = null,
        val estimatedDurationMs: Long = 0, // exact (not estimated) for FLAC/MP4 - see their doc comments
    )

    private interface FrameSource {
        fun readFully(buffer: ByteArray): Boolean
        fun skip(count: Int)
    }

    private class RandomAccessFileSource(private val raf: RandomAccessFile) : FrameSource {
        override fun readFully(buffer: ByteArray): Boolean = try {
            raf.readFully(buffer)
            true
        } catch (e: Exception) {
            false
        }

        override fun skip(count: Int) {
            raf.seek(raf.filePointer + count)
        }
    }

    private class ByteArraySource(private val bytes: ByteArray) : FrameSource {
        private var cursor = 0

        override fun readFully(buffer: ByteArray): Boolean {
            if (cursor + buffer.size > bytes.size) return false
            System.arraycopy(bytes, cursor, buffer, 0, buffer.size)
            cursor += buffer.size
            return true
        }

        override fun skip(count: Int) {
            cursor = (cursor + count).coerceAtMost(bytes.size)
        }
    }

    fun read(path: String, includeCoverArt: Boolean = false): Id3Tags? {
        RandomAccessFile(path, "r").use { raf ->
            val header = ByteArray(12)
            val n = raf.read(header)
            if (n < 8) return null
            return when {
                isId3(header) -> {
                    val tagSize = validateId3HeaderAndGetTagSize(header) ?: return null
                    val audioBytes = (raf.length() - 10 - tagSize).coerceAtLeast(0)
                    raf.seek(10) // the 12-byte peek above overshot the 10-byte ID3 header - realign before frame parsing
                    readId3Frames(RandomAccessFileSource(raf), header, tagSize, audioBytes, includeCoverArt)
                }
                isFlac(header) -> {
                    raf.seek(4)
                    readFlac(RandomAccessFileSource(raf), includeCoverArt)
                }
                isMp4(header) -> {
                    raf.seek(0)
                    readMp4(RandomAccessFileSource(raf), includeCoverArt)
                }
                else -> null
            }
        }
    }

    fun readFromPrefix(prefix: ByteArray, totalFileSize: Long, includeCoverArt: Boolean = false): Id3Tags? {
        if (prefix.size < 8) return null
        val header = prefix.copyOfRange(0, minOf(12, prefix.size))
        return when {
            isId3(header) -> {
                val tagSize = validateId3HeaderAndGetTagSize(header) ?: return null
                val audioBytes = (totalFileSize - 10 - tagSize).coerceAtLeast(0)
                val body = ByteArraySource(prefix).also { it.skip(10) }
                readId3Frames(body, header, tagSize, audioBytes, includeCoverArt)
            }
            isFlac(header) -> readFlac(ByteArraySource(prefix).also { it.skip(4) }, includeCoverArt)
            isMp4(header) -> readMp4(ByteArraySource(prefix), includeCoverArt)
            else -> null
        }
    }

    /** ID3v2's 10-byte header declares the tag's exact total size up front
     * (unlike FLAC/MP4, which have no single equivalent field - their tag
     * data is scattered across separately-sized blocks/boxes instead), so
     * for an MP3 [prefix] this can say precisely how many bytes of the file
     * are needed to read the tag *in full*, including any embedded cover
     * art, before attempting to parse it. RemoteLibraryScanner uses this to
     * fetch exactly enough rather than either a fixed budget that silently
     * truncates a large embedded cover (a real embedded cover this was
     * built for came in at ~300KB, well past a 200KB fixed budget) or
     * wastefully over-fetching every track "just in case". Returns null for
     * a non-ID3 file, or one whose magic bytes [prefix] is too short to
     * even check. */
    fun peekId3RequiredBytes(prefix: ByteArray): Long? {
        if (prefix.size < 10 || !isId3(prefix)) return null
        val tagSize = synchsafeToInt(prefix, 6)
        return if (tagSize > 0) 10L + tagSize else null
    }

    /** MP4 has no single field declaring its whole tag region's size up
     * front the way ID3 does - `moov` (which nests `udta` -> `meta` ->
     * `ilst`, the tags/cover actually wanted) does declare its own size in
     * its own box header, though, and for a "moov early" file that's
     * enough: walks [prefix]'s top-level box headers only (never their
     * content, so this works even when a box's content is far larger than
     * [prefix] itself) looking for `moov`, and if found, returns how many
     * bytes from the start of the file are needed to fetch it *in full*.
     * Found live against "Opera on 3": `moov` sits right after `ftyp` (good),
     * but is itself ~2.9MB - multi-hour recordings carry huge sample-
     * position tables in `moov` too, not just the tags - so even the fixed
     * 200KB FLAC/MP4 budget came nowhere close to reaching `ilst`. Returns
     * null if `moov` isn't found within [prefix] at all, which most likely
     * means it comes after a large `mdat` - seeking past that costs nothing
     * for a local (RandomAccessFile) read, but a fundamentally different
     * fix (fetching at an arbitrary offset, not just a larger prefix from
     * the start) would be needed to handle it for a remote scan - not
     * attempted here. */
    fun peekMp4RequiredBytes(prefix: ByteArray): Long? {
        if (prefix.size < 8 || !isMp4(prefix.copyOfRange(0, minOf(12, prefix.size)))) return null
        var pos = 0L
        while (pos + 8 <= prefix.size) {
            val p = pos.toInt()
            var size = bigEndianToUInt(prefix, p)
            val type = String(prefix, p + 4, 4, Charsets.US_ASCII)
            var headerSize = 8L
            if (size == 1L) {
                if (pos + 16 > prefix.size) return null
                size = bigEndianToLong(prefix, p + 8)
                headerSize = 16L
            }
            if (size < headerSize) return null
            if (type == "moov") return pos + size
            pos += size
        }
        return null
    }

    private fun isId3(header: ByteArray): Boolean =
        header.size >= 3 && header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()

    private fun isFlac(header: ByteArray): Boolean =
        header.size >= 4 && header[0] == 'f'.code.toByte() && header[1] == 'L'.code.toByte() &&
            header[2] == 'a'.code.toByte() && header[3] == 'C'.code.toByte()

    /** The box right after an MP4/M4A file's leading 4-byte size is (almost)
     * always `ftyp` - good enough as a magic check without a full box walk. */
    private fun isMp4(header: ByteArray): Boolean =
        header.size >= 8 && header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
            header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()

    // --- ID3v2 (MP3) -----------------------------------------------------

    private fun validateId3HeaderAndGetTagSize(header: ByteArray): Int? {
        val tagSize = synchsafeToInt(header, 6)
        return tagSize.takeIf { it > 0 }
    }

    private fun readId3Frames(
        source: FrameSource,
        header: ByteArray,
        tagSize: Int,
        audioBytes: Long,
        includeCoverArt: Boolean,
    ): Id3Tags {
        val majorVersion = header[3].toInt()
        if (majorVersion == 2) {
            return readId3FramesV22(source, tagSize, audioBytes, includeCoverArt)
        }

        var title: String? = null
        var artist: String? = null
        var albumArtist: String? = null
        var album: String? = null
        var trackNumber: Int? = null
        var discNumber: Int? = null
        var coverArt: ByteArray? = null

        val frameHeader = ByteArray(10)
        var consumed = 0
        while (consumed + 10 <= tagSize) {
            if (!source.readFully(frameHeader)) break
            val frameId = String(frameHeader, 0, 4, Charsets.US_ASCII)
            if (frameId.isBlank() || frameId[0] == '\u0000') break // padding reached (ID3v2 pads with NUL bytes, not spaces)

            val frameSize = if (majorVersion >= 4) {
                synchsafeToInt(frameHeader, 4)
            } else {
                bigEndianToInt(frameHeader, 4)
            }
            consumed += 10
            if (frameSize <= 0 || consumed + frameSize > tagSize) break

            when (frameId) {
                "TIT2", "TPE1", "TPE2", "TALB", "TRCK", "TPOS" -> {
                    val frameBytes = ByteArray(frameSize)
                    if (source.readFully(frameBytes)) {
                        val text = decodeTextFrame(frameBytes)
                        when (frameId) {
                            "TIT2" -> title = text
                            "TPE1" -> artist = text
                            "TPE2" -> albumArtist = text
                            "TALB" -> album = text
                            "TRCK" -> trackNumber = parseLeadingNumber(text)
                            "TPOS" -> discNumber = parseLeadingNumber(text)
                        }
                    }
                }
                "APIC" -> {
                    if (includeCoverArt) {
                        val frameBytes = ByteArray(frameSize)
                        if (source.readFully(frameBytes)) {
                            coverArt = extractPictureBytes(frameBytes)
                        }
                    } else {
                        source.skip(frameSize)
                    }
                }
                else -> source.skip(frameSize)
            }
            consumed += frameSize
        }

        return finishReadingId3(source, tagSize, consumed, audioBytes, title, artist, albumArtist, album, trackNumber, discNumber, coverArt)
    }

    private fun readId3FramesV22(
        source: FrameSource,
        tagSize: Int,
        audioBytes: Long,
        includeCoverArt: Boolean,
    ): Id3Tags {
        var title: String? = null
        var artist: String? = null
        var albumArtist: String? = null
        var album: String? = null
        var trackNumber: Int? = null
        var discNumber: Int? = null
        var coverArt: ByteArray? = null

        val frameHeader = ByteArray(6)
        var consumed = 0
        while (consumed + 6 <= tagSize) {
            if (!source.readFully(frameHeader)) break
            val frameId = String(frameHeader, 0, 3, Charsets.US_ASCII)
            if (frameId.isBlank() || frameId[0] == '\u0000') break // padding reached (ID3v2 pads with NUL bytes, not spaces)

            val frameSize = bigEndianToInt24(frameHeader, 3)
            consumed += 6
            if (frameSize <= 0 || consumed + frameSize > tagSize) break

            when (frameId) {
                "TT2", "TP1", "TP2", "TAL", "TRK", "TPA" -> {
                    val frameBytes = ByteArray(frameSize)
                    if (source.readFully(frameBytes)) {
                        val text = decodeTextFrame(frameBytes)
                        when (frameId) {
                            "TT2" -> title = text
                            "TP1" -> artist = text
                            "TP2" -> albumArtist = text
                            "TAL" -> album = text
                            "TRK" -> trackNumber = parseLeadingNumber(text)
                            "TPA" -> discNumber = parseLeadingNumber(text)
                        }
                    }
                }
                "PIC" -> {
                    if (includeCoverArt) {
                        val frameBytes = ByteArray(frameSize)
                        if (source.readFully(frameBytes)) {
                            coverArt = extractPictureBytesV22(frameBytes)
                        }
                    } else {
                        source.skip(frameSize)
                    }
                }
                else -> source.skip(frameSize)
            }
            consumed += frameSize
        }

        return finishReadingId3(source, tagSize, consumed, audioBytes, title, artist, albumArtist, album, trackNumber, discNumber, coverArt)
    }

    private fun finishReadingId3(
        source: FrameSource,
        tagSize: Int,
        consumed: Int,
        audioBytes: Long,
        title: String?,
        artist: String?,
        albumArtist: String?,
        album: String?,
        trackNumber: Int?,
        discNumber: Int?,
        coverArt: ByteArray?,
    ): Id3Tags {
        if (consumed < tagSize) {
            source.skip(tagSize - consumed)
        }
        val audioProbe = ByteArray(FRAME_PROBE_BYTES)
        val estimatedDurationMs = if (source.readFully(audioProbe)) {
            estimateDurationFromAudio(audioProbe, audioBytes) ?: (audioBytes * 8000 / ASSUMED_BITRATE_BPS)
        } else {
            audioBytes * 8000 / ASSUMED_BITRATE_BPS
        }

        return Id3Tags(
            title = title,
            artist = artist,
            albumArtist = albumArtist,
            album = album,
            trackNumber = trackNumber,
            discNumber = discNumber,
            coverArt = coverArt,
            estimatedDurationMs = estimatedDurationMs,
        )
    }

    /** TRCK/TPOS frames (and FLAC TRACKNUMBER/DISCNUMBER comments) are "N" or
     * "N/total" - only the leading number matters here. */
    private fun parseLeadingNumber(text: String?): Int? =
        text?.substringBefore('/')?.trim()?.toIntOrNull()

    private fun estimateDurationFromAudio(audioProbe: ByteArray, audioBytes: Long): Long? {
        if (audioProbe.size < 4) return null
        val frame = parseMp3FrameHeader(
            audioProbe[0].toInt() and 0xFF,
            audioProbe[1].toInt() and 0xFF,
            audioProbe[2].toInt() and 0xFF,
            audioProbe[3].toInt() and 0xFF,
        ) ?: return null

        val xingOffset = 4 + frame.sideInfoSize
        if (audioProbe.size >= xingOffset + 16) {
            val magic = String(audioProbe, xingOffset, 4, Charsets.US_ASCII)
            if (magic == "Xing" || magic == "Info") {
                val flags = bigEndianToInt(audioProbe, xingOffset + 4)
                val hasFrameCount = (flags and 0x01) != 0
                val hasByteCount = (flags and 0x02) != 0
                if (hasFrameCount) {
                    val frameCount = bigEndianToInt(audioProbe, xingOffset + 8)
                    val byteCountPlausible = if (hasByteCount) {
                        val byteCount = bigEndianToInt(audioProbe, xingOffset + 12)
                        byteCount > 0 && audioBytes > 0 &&
                            byteCount.toLong() in (audioBytes / 2)..(audioBytes * 2)
                    } else {
                        true
                    }
                    if (frameCount > 0 && byteCountPlausible) {
                        return frameCount.toLong() * frame.samplesPerFrame * 1000L / frame.sampleRateHz
                    }
                }
            }
        }

        return audioBytes * 8000 / frame.bitrateBps
    }

    private data class Mp3FrameInfo(
        val bitrateBps: Long,
        val sampleRateHz: Int,
        val samplesPerFrame: Int,
        val sideInfoSize: Int,
    )

    private val MPEG1_BITRATES_LAYER1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, -1)
    private val MPEG1_BITRATES_LAYER2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, -1)
    private val MPEG1_BITRATES_LAYER3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, -1)
    private val MPEG2_BITRATES_LAYER1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, -1)
    private val MPEG2_BITRATES_LAYER23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, -1)

    private val MPEG1_SAMPLE_RATES = intArrayOf(44100, 48000, 32000, -1)
    private val MPEG2_SAMPLE_RATES = intArrayOf(22050, 24000, 16000, -1)
    private val MPEG25_SAMPLE_RATES = intArrayOf(11025, 12000, 8000, -1)

    private fun parseMp3FrameHeader(b0: Int, b1: Int, b2: Int, b3: Int): Mp3FrameInfo? {
        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null
        val versionBits = (b1 shr 3) and 0x03
        val layerBits = (b1 shr 1) and 0x03
        if (versionBits == 1 || layerBits == 0) return null

        val bitrateIndex = (b2 shr 4) and 0x0F
        val sampleRateIndex = (b2 shr 2) and 0x03
        val channelMode = (b3 shr 6) and 0x03

        val isMpeg1 = versionBits == 3
        val sampleRateHz = when (versionBits) {
            3 -> MPEG1_SAMPLE_RATES.getOrElse(sampleRateIndex) { -1 }
            2 -> MPEG2_SAMPLE_RATES.getOrElse(sampleRateIndex) { -1 }
            else -> MPEG25_SAMPLE_RATES.getOrElse(sampleRateIndex) { -1 }
        }
        if (sampleRateHz <= 0) return null

        val bitrateTable = when {
            isMpeg1 && layerBits == 3 -> MPEG1_BITRATES_LAYER1
            isMpeg1 && layerBits == 2 -> MPEG1_BITRATES_LAYER2
            isMpeg1 && layerBits == 1 -> MPEG1_BITRATES_LAYER3
            !isMpeg1 && layerBits == 3 -> MPEG2_BITRATES_LAYER1
            else -> MPEG2_BITRATES_LAYER23
        }
        val bitrateKbps = bitrateTable.getOrElse(bitrateIndex) { -1 }
        if (bitrateKbps <= 0) return null

        val samplesPerFrame = when {
            layerBits == 3 -> 384
            layerBits == 1 && !isMpeg1 -> 576
            else -> 1152
        }
        val isMono = channelMode == 3
        val sideInfoSize = if (isMpeg1) {
            if (isMono) 17 else 32
        } else {
            if (isMono) 9 else 17
        }

        return Mp3FrameInfo(bitrateKbps * 1000L, sampleRateHz, samplesPerFrame, sideInfoSize)
    }

    private fun decodeTextFrame(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val encodingByte = bytes[0].toInt()
        val charset = when (encodingByte) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(bytes, 1, bytes.size - 1, charset).trimEnd('\u0000', ' ')
    }

    private fun extractPictureBytes(frame: ByteArray): ByteArray? {
        if (frame.isEmpty()) return null
        val encoding = frame[0].toInt()
        var i = 1

        while (i < frame.size && frame[i] != 0.toByte()) i++
        if (i >= frame.size) return null
        i++
        if (i >= frame.size) return null
        i++

        if (encoding == 1 || encoding == 2) {
            while (i + 1 < frame.size && !(frame[i] == 0.toByte() && frame[i + 1] == 0.toByte())) i += 2
            i += 2
        } else {
            while (i < frame.size && frame[i] != 0.toByte()) i++
            i += 1
        }
        if (i > frame.size) return null
        return frame.copyOfRange(i, frame.size)
    }

    private fun extractPictureBytesV22(frame: ByteArray): ByteArray? {
        if (frame.size < 5) return null
        val encoding = frame[0].toInt()
        var i = 4

        if (i >= frame.size) return null
        i++

        if (encoding == 1 || encoding == 2) {
            while (i + 1 < frame.size && !(frame[i] == 0.toByte() && frame[i + 1] == 0.toByte())) i += 2
            i += 2
        } else {
            while (i < frame.size && frame[i] != 0.toByte()) i++
            i += 1
        }
        if (i > frame.size) return null
        return frame.copyOfRange(i, frame.size)
    }

    // --- FLAC --------------------------------------------------------------

    /** Walks FLAC's metadata blocks (always right after the 4-byte "fLaC"
     * magic, before any audio frame - unlike MP4, there's no ordering risk
     * here). STREAMINFO gives an exact duration from its sample count/rate;
     * VORBIS_COMMENT carries the text tags; METADATA_BLOCK_PICTURE (type 6)
     * is FLAC's equivalent of ID3's APIC frame. */
    private fun readFlac(source: FrameSource, includeCoverArt: Boolean): Id3Tags {
        var title: String? = null
        var artist: String? = null
        var albumArtist: String? = null
        var album: String? = null
        var trackNumber: Int? = null
        var discNumber: Int? = null
        var coverArt: ByteArray? = null
        var coverArtIsFrontCover = false
        var durationMs = 0L

        val blockHeader = ByteArray(4)
        while (source.readFully(blockHeader)) {
            val isLast = (blockHeader[0].toInt() and 0x80) != 0
            val blockType = blockHeader[0].toInt() and 0x7F
            val blockLength = bigEndianToInt24(blockHeader, 1)
            if (blockLength < 0) break

            when (blockType) {
                0 -> { // STREAMINFO
                    val block = ByteArray(blockLength)
                    if (source.readFully(block) && block.size >= 18) {
                        val sampleRate = ((block[10].toInt() and 0xFF) shl 12) or
                            ((block[11].toInt() and 0xFF) shl 4) or
                            ((block[12].toInt() and 0xFF) shr 4)
                        val totalSamples = ((block[13].toLong() and 0x0F) shl 32) or
                            ((block[14].toLong() and 0xFF) shl 24) or
                            ((block[15].toLong() and 0xFF) shl 16) or
                            ((block[16].toLong() and 0xFF) shl 8) or
                            (block[17].toLong() and 0xFF)
                        if (sampleRate > 0 && totalSamples > 0) {
                            durationMs = totalSamples * 1000 / sampleRate
                        }
                    }
                }
                4 -> { // VORBIS_COMMENT
                    val block = ByteArray(blockLength)
                    if (source.readFully(block)) {
                        val comments = parseVorbisComments(block)
                        title = comments["TITLE"] ?: title
                        artist = comments["ARTIST"] ?: artist
                        albumArtist = comments["ALBUMARTIST"] ?: comments["ALBUM ARTIST"] ?: albumArtist
                        album = comments["ALBUM"] ?: album
                        trackNumber = parseLeadingNumber(comments["TRACKNUMBER"]) ?: trackNumber
                        discNumber = parseLeadingNumber(comments["DISCNUMBER"]) ?: discNumber
                    }
                }
                6 -> { // PICTURE
                    if (includeCoverArt) {
                        val block = ByteArray(blockLength)
                        if (source.readFully(block) && block.size >= 8) {
                            val pictureType = bigEndianToInt(block, 0)
                            if (coverArt == null || (pictureType == 3 && !coverArtIsFrontCover)) {
                                extractFlacPictureData(block)?.let {
                                    coverArt = it
                                    coverArtIsFrontCover = pictureType == 3
                                }
                            }
                        }
                    } else {
                        source.skip(blockLength)
                    }
                }
                else -> source.skip(blockLength)
            }

            if (isLast) break
        }

        return Id3Tags(title, artist, albumArtist, album, trackNumber, discNumber, coverArt, durationMs)
    }

    /** [block] is a Vorbis comment packet: a length-prefixed vendor string
     * followed by a count and that many length-prefixed "KEY=value" entries
     * - all lengths little-endian (unlike everything else in this file,
     * which is big-endian per the ID3/FLAC/MP4 container specs). Keys are
     * matched case-insensitively per the Vorbis comment spec. */
    private fun parseVorbisComments(block: ByteArray): Map<String, String> {
        if (block.size < 4) return emptyMap()
        var offset = 0
        val vendorLength = littleEndianToInt(block, offset)
        offset += 4 + vendorLength
        if (vendorLength < 0 || offset + 4 > block.size) return emptyMap()

        val commentCount = littleEndianToInt(block, offset)
        offset += 4
        val result = mutableMapOf<String, String>()
        var i = 0
        while (i < commentCount && offset + 4 <= block.size) {
            val len = littleEndianToInt(block, offset)
            offset += 4
            if (len < 0 || offset + len > block.size) break
            val entry = String(block, offset, len, Charsets.UTF_8)
            offset += len
            val eq = entry.indexOf('=')
            if (eq > 0) result[entry.substring(0, eq).uppercase()] = entry.substring(eq + 1)
            i++
        }
        return result
    }

    /** FLAC's METADATA_BLOCK_PICTURE: type(4) + mimeLength(4)+mime +
     * descLength(4)+desc + width(4) + height(4) + depth(4) + colors(4) +
     * dataLength(4) + data - all big-endian, all fixed order. */
    private fun extractFlacPictureData(block: ByteArray): ByteArray? {
        var offset = 4 // picture type, already read by the caller
        if (offset + 4 > block.size) return null
        val mimeLength = bigEndianToInt(block, offset)
        offset += 4 + mimeLength
        if (mimeLength < 0 || offset + 4 > block.size) return null

        val descLength = bigEndianToInt(block, offset)
        offset += 4 + descLength
        if (descLength < 0 || offset + 16 + 4 > block.size) return null

        offset += 16 // width, height, depth, colors-used
        val dataLength = bigEndianToInt(block, offset)
        offset += 4
        if (dataLength <= 0 || offset + dataLength > block.size) return null
        return block.copyOfRange(offset, offset + dataLength)
    }

    // --- MP4 / M4A -----------------------------------------------------

    private class Mp4Tags {
        var title: String? = null
        var artist: String? = null
        var albumArtist: String? = null
        var album: String? = null
        var trackNumber: Int? = null
        var discNumber: Int? = null
        var coverArt: ByteArray? = null
        var durationMs: Long = 0
    }

    private fun readMp4(source: FrameSource, includeCoverArt: Boolean): Id3Tags {
        val tags = Mp4Tags()
        walkMp4TopLevel(source, includeCoverArt, tags)
        return Id3Tags(
            title = tags.title,
            artist = tags.artist,
            albumArtist = tags.albumArtist,
            album = tags.album,
            trackNumber = tags.trackNumber,
            discNumber = tags.discNumber,
            coverArt = tags.coverArt,
            estimatedDurationMs = tags.durationMs,
        )
    }

    /** Top-level boxes (ftyp, moov, mdat, ...), stopping as soon as `moov`
     * is found - no need to keep scanning into (often huge) audio data
     * after it. See the class doc comment for why a `moov` that comes after
     * `mdat` only works out for local (freely-seekable) reads. */
    private fun walkMp4TopLevel(source: FrameSource, includeCoverArt: Boolean, tags: Mp4Tags) {
        val header = ByteArray(8)
        while (source.readFully(header)) {
            var size = bigEndianToUInt(header, 0)
            val type = String(header, 4, 4, Charsets.US_ASCII)
            var headerSize = 8
            if (size == 1L) {
                val ext = ByteArray(8)
                if (!source.readFully(ext)) return
                size = bigEndianToLong(ext, 0)
                headerSize = 16
            }
            if (size < headerSize) return // malformed, or size 0 ("rest of file") - nothing safe to do with either
            val payloadSize = size - headerSize

            if (type == "moov") {
                walkMp4Box(source, payloadSize, includeCoverArt, tags, insideMeta = false)
                return
            }
            source.skip(payloadSize.toIntClamped())
        }
    }

    /** Bounded to [budget] bytes so a box's children never spill into its
     * siblings. `meta` is a "full box" (a 4-byte version+flags field before
     * its own children, unlike a plain container) - [insideMeta] handles
     * that one alignment quirk. */
    private fun walkMp4Box(source: FrameSource, budget: Long, includeCoverArt: Boolean, tags: Mp4Tags, insideMeta: Boolean) {
        var remaining = budget
        if (insideMeta) {
            if (remaining < 4) return
            source.skip(4)
            remaining -= 4
        }

        val header = ByteArray(8)
        while (remaining >= 8) {
            if (!source.readFully(header)) return
            remaining -= 8
            val size = bigEndianToUInt(header, 0)
            val type = String(header, 4, 4, Charsets.US_ASCII)
            if (size < 8 || size - 8 > remaining) return
            val payloadSize = size - 8

            when (type) {
                "mvhd" -> {
                    val block = ByteArray(payloadSize.toIntClamped())
                    if (source.readFully(block)) parseMvhdDurationMs(block)?.let { tags.durationMs = it }
                }
                "udta" -> walkMp4Box(source, payloadSize, includeCoverArt, tags, insideMeta = false)
                "meta" -> walkMp4Box(source, payloadSize, includeCoverArt, tags, insideMeta = true)
                "ilst" -> walkMp4Ilst(source, payloadSize, includeCoverArt, tags)
                else -> source.skip(payloadSize.toIntClamped())
            }
            remaining -= payloadSize
        }
    }

    /** `ilst`'s children are one box per tag (©nam, ©ART, trkn, covr, ...),
     * each wrapping a single `data` box - see [extractMp4DataAtomContent]. */
    private fun walkMp4Ilst(source: FrameSource, budget: Long, includeCoverArt: Boolean, tags: Mp4Tags) {
        var remaining = budget
        val header = ByteArray(8)
        while (remaining >= 8) {
            if (!source.readFully(header)) return
            remaining -= 8
            val size = bigEndianToUInt(header, 0)
            // ©nam/©ART/©alb use the copyright-sign byte (0xA9), not valid
            // ASCII - ISO-8859-1 round-trips single bytes 1:1 so this still
            // compares correctly against the literal "©nam" etc. below.
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            if (size < 8 || size - 8 > remaining) return
            val payloadSize = size - 8

            val wanted = type in TEXT_ITEM_TYPES || (type == "covr" && includeCoverArt)
            if (wanted) {
                val block = ByteArray(payloadSize.toIntClamped())
                if (source.readFully(block)) {
                    extractMp4DataAtomContent(block)?.let { content -> applyMp4Item(type, content, tags) }
                }
            } else {
                source.skip(payloadSize.toIntClamped())
            }
            remaining -= payloadSize
        }
    }

    private val TEXT_ITEM_TYPES = setOf("©nam", "©ART", "aART", "©alb", "trkn", "disk")

    private fun applyMp4Item(type: String, content: ByteArray, tags: Mp4Tags) {
        when (type) {
            "©nam" -> tags.title = String(content, Charsets.UTF_8)
            "©ART" -> tags.artist = String(content, Charsets.UTF_8)
            "aART" -> tags.albumArtist = String(content, Charsets.UTF_8)
            "©alb" -> tags.album = String(content, Charsets.UTF_8)
            "trkn" -> if (content.size >= 4) tags.trackNumber = ((content[2].toInt() and 0xFF) shl 8) or (content[3].toInt() and 0xFF)
            "disk" -> if (content.size >= 4) tags.discNumber = ((content[2].toInt() and 0xFF) shl 8) or (content[3].toInt() and 0xFF)
            "covr" -> if (tags.coverArt == null) tags.coverArt = content
        }
    }

    /** Each ilst item wraps one `data` child box: an 8-byte box header, then
     * a 4-byte type indicator and 4-byte locale before the actual value. */
    private fun extractMp4DataAtomContent(itemPayload: ByteArray): ByteArray? {
        if (itemPayload.size < 16) return null
        val dataFourCc = String(itemPayload, 4, 4, Charsets.US_ASCII)
        if (dataFourCc != "data") return null
        return itemPayload.copyOfRange(16, itemPayload.size)
    }

    /** mvhd is a "full box": 1-byte version + 3-byte flags, then
     * creation/modification/timescale/duration - 32-bit fields for version
     * 0, 64-bit for version 1 (except timescale, always 32-bit). */
    private fun parseMvhdDurationMs(block: ByteArray): Long? {
        if (block.isEmpty()) return null
        val version = block[0].toInt() and 0xFF
        return if (version == 1) {
            if (block.size < 32) return null
            val timescale = bigEndianToInt(block, 20)
            val duration = bigEndianToLong(block, 24)
            if (timescale <= 0) null else duration * 1000 / timescale
        } else {
            if (block.size < 20) return null
            val timescale = bigEndianToInt(block, 12)
            val duration = bigEndianToUInt(block, 16)
            if (timescale <= 0) null else duration * 1000 / timescale
        }
    }

    private fun Long.toIntClamped(): Int = coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    // --- Shared byte helpers ---------------------------------------------

    private fun synchsafeToInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)

    private fun bigEndianToInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun bigEndianToInt24(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)

    private fun bigEndianToUInt(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private fun bigEndianToLong(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return result
    }

    private fun littleEndianToInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
