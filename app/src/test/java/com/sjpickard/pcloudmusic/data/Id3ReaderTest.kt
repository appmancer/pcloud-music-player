package com.sjpickard.pcloudmusic.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures under src/test/resources/audio were generated with ffmpeg (1s of
 * silence, an attached picture, and title/artist/album/album_artist/
 * track=7/12/disc=2/3 tags, except test_bigcover.mp3 - see below) - see the
 * ffmpeg invocation in the PR/commit that added this test if they ever need
 * regenerating. test_bigcover.mp3's cover is a large, high-entropy (so it
 * doesn't compress away) JPEG specifically to reproduce a real bug found
 * live - see the test using it below.
 */
class Id3ReaderTest {

    private fun resourcePath(name: String): String {
        val url = javaClass.classLoader!!.getResource("audio/$name")!!
        return File(url.toURI()).path
    }

    @Test
    fun `reads FLAC tags and cover art from a local file`() {
        val tags = Id3Reader.read(resourcePath("test.flac"), includeCoverArt = true)
        assertNotNull(tags)
        assertEquals("Test Title", tags!!.title)
        assertEquals("Test Artist", tags.artist)
        assertEquals("Test AlbumArtist", tags.albumArtist)
        assertEquals("Test Album", tags.album)
        assertEquals(7, tags.trackNumber)
        assertEquals(2, tags.discNumber)
        assertEquals(1000L, tags.estimatedDurationMs) // exact, from STREAMINFO - 1s fixture
        assertNotNull(tags.coverArt)
        assertPng(tags.coverArt!!)
    }

    @Test
    fun `reads FLAC tags from a byte-array prefix, same as a remote scan would`() {
        val bytes = File(resourcePath("test.flac")).readBytes()
        val tags = Id3Reader.readFromPrefix(bytes, totalFileSize = bytes.size.toLong(), includeCoverArt = true)
        assertNotNull(tags)
        assertEquals("Test Title", tags!!.title)
        assertEquals("Test AlbumArtist", tags.albumArtist)
        assertNotNull(tags.coverArt)
    }

    @Test
    fun `skips FLAC cover art when includeCoverArt is false`() {
        val tags = Id3Reader.read(resourcePath("test.flac"), includeCoverArt = false)
        assertNotNull(tags)
        assertNull(tags!!.coverArt)
        assertEquals("Test Title", tags.title) // other tags still read
    }

    @Test
    fun `reads M4A tags and cover art from a local file`() {
        val tags = Id3Reader.read(resourcePath("test.m4a"), includeCoverArt = true)
        assertNotNull(tags)
        assertEquals("Test Title", tags!!.title)
        assertEquals("Test Artist", tags.artist)
        assertEquals("Test AlbumArtist", tags.albumArtist)
        assertEquals("Test Album", tags.album)
        assertEquals(7, tags.trackNumber)
        assertEquals(2, tags.discNumber)
        assertEquals(1000L, tags.estimatedDurationMs) // exact, from mvhd - 1s fixture
        assertNotNull(tags.coverArt)
        assertJpeg(tags.coverArt!!)
    }

    @Test
    fun `reads M4A tags from a byte-array prefix, same as a remote scan would`() {
        val bytes = File(resourcePath("test.m4a")).readBytes()
        val tags = Id3Reader.readFromPrefix(bytes, totalFileSize = bytes.size.toLong(), includeCoverArt = true)
        assertNotNull(tags)
        assertEquals("Test Title", tags!!.title)
        assertEquals("Test AlbumArtist", tags.albumArtist)
        assertNotNull(tags.coverArt)
    }

    @Test
    fun `returns null for an unsupported format`() {
        val bytes = "not an audio file, just some bytes".toByteArray()
        assertNull(Id3Reader.readFromPrefix(bytes, totalFileSize = bytes.size.toLong()))
    }

    @Test
    fun `reads MP3 tags and cover art from a local file`() {
        val tags = Id3Reader.read(resourcePath("test.mp3"), includeCoverArt = true)
        assertNotNull(tags)
        assertEquals("Test Title", tags!!.title)
        assertEquals("Test AlbumArtist", tags.albumArtist)
        assertEquals(7, tags.trackNumber)
        assertEquals(2, tags.discNumber)
        assertNotNull(tags.coverArt)
        assertPng(tags.coverArt!!)
    }

    @Test
    fun `peekId3RequiredBytes reports the tag's exact total size`() {
        val bytes = File(resourcePath("test_bigcover.mp3")).readBytes()
        val required = Id3Reader.peekId3RequiredBytes(bytes.copyOfRange(0, 10))
        assertNotNull(required)
        // ID3 header's declared tag size, plus the 10-byte header itself.
        val tagSize = ((bytes[6].toInt() and 0x7F) shl 21) or ((bytes[7].toInt() and 0x7F) shl 14) or
            ((bytes[8].toInt() and 0x7F) shl 7) or (bytes[9].toInt() and 0x7F)
        assertEquals(10L + tagSize, required)
    }

    @Test
    fun `peekId3RequiredBytes returns null for a non-ID3 prefix`() {
        assertNull(Id3Reader.peekId3RequiredBytes("not an mp3".toByteArray()))
    }

    /**
     * Reproduces the real bug found live against "Blind Faith" - an MP3
     * whose embedded cover alone was ~299KB inside a ~308KB tag, well past
     * RemoteLibraryScanner's old fixed 200KB prefix fetch, so the cover was
     * silently truncated to nothing. This fixture's tag is ~361KB, same
     * shape. A bounded prefix reproduces the old bug (no cover found);
     * fetching exactly what peekId3RequiredBytes says is needed - what
     * RemoteLibraryScanner now does - gets it in full.
     */
    @Test
    fun `a large embedded cover is truncated by a bounded prefix, but not by one sized via peekId3RequiredBytes`() {
        val bytes = File(resourcePath("test_bigcover.mp3")).readBytes()
        assertTrue("fixture should exceed the old fixed 200KB budget", bytes.size > 200_000)

        val oldFixedBudget = bytes.copyOfRange(0, 200_000)
        val tagsWithOldBudget = Id3Reader.readFromPrefix(oldFixedBudget, totalFileSize = bytes.size.toLong(), includeCoverArt = true)
        assertNotNull(tagsWithOldBudget)
        assertNull("reproduces the bug: cover truncated by a fixed 200KB prefix", tagsWithOldBudget!!.coverArt)

        val probe = bytes.copyOfRange(0, 4_096)
        val requiredBytes = Id3Reader.peekId3RequiredBytes(probe)!!
        assertTrue(requiredBytes > 200_000) // confirms this fixture actually needs more than the old budget
        val rightSizedPrefix = bytes.copyOfRange(0, requiredBytes.toInt())
        val tagsFixed = Id3Reader.readFromPrefix(rightSizedPrefix, totalFileSize = bytes.size.toLong(), includeCoverArt = true)
        assertNotNull(tagsFixed)
        assertNotNull("fix: cover found once fetched to peekId3RequiredBytes' exact size", tagsFixed!!.coverArt)
        assertJpeg(tagsFixed.coverArt!!)
    }

    @Test
    fun `peekMp4RequiredBytes finds moov's exact extent, skipping over mdat`() {
        // test.m4a's layout (confirmed by direct inspection): ftyp, free,
        // mdat (audio - comes BEFORE moov in this fixture, unlike the real
        // "Opera on 3" case this was built for, where moov comes first but
        // is itself huge - either way, peekMp4RequiredBytes must walk past
        // whatever precedes moov using only box headers to find it.
        val bytes = File(resourcePath("test.m4a")).readBytes()
        val required = Id3Reader.peekMp4RequiredBytes(bytes)
        assertEquals(bytes.size.toLong(), required) // moov runs to the end of this fixture
    }

    @Test
    fun `peekMp4RequiredBytes returns null for a non-MP4 prefix`() {
        assertNull(Id3Reader.peekMp4RequiredBytes("not an mp4 file at all".toByteArray()))
    }

    @Test
    fun `an MP4 cover is missed by a prefix that cuts off mid-moov, but not by one sized via peekMp4RequiredBytes`() {
        val bytes = File(resourcePath("test.m4a")).readBytes()

        val tooSmall = bytes.copyOfRange(0, 500) // moov starts at 331 in this fixture - this cuts it off partway through
        val tagsTruncated = Id3Reader.readFromPrefix(tooSmall, totalFileSize = bytes.size.toLong(), includeCoverArt = true)
        assertTrue(tagsTruncated == null || tagsTruncated.coverArt == null)

        val required = Id3Reader.peekMp4RequiredBytes(bytes.copyOfRange(0, 4_096.coerceAtMost(bytes.size)))!!
        val rightSized = bytes.copyOfRange(0, required.toInt())
        val tagsFixed = Id3Reader.readFromPrefix(rightSized, totalFileSize = bytes.size.toLong(), includeCoverArt = true)
        assertNotNull(tagsFixed)
        assertNotNull("fix: cover found once fetched to peekMp4RequiredBytes' exact size", tagsFixed!!.coverArt)
        assertJpeg(tagsFixed.coverArt!!)
    }

    private fun assertPng(bytes: ByteArray) {
        assertTrue(bytes.size > 4)
        assertEquals(0x89.toByte(), bytes[0])
        assertEquals('P'.code.toByte(), bytes[1])
        assertEquals('N'.code.toByte(), bytes[2])
        assertEquals('G'.code.toByte(), bytes[3])
    }

    private fun assertJpeg(bytes: ByteArray) {
        assertTrue(bytes.size > 2)
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])
    }
}
