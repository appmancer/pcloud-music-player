package com.sjpickard.pcloudmusic.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures under src/test/resources/audio were generated with ffmpeg (1s of
 * silence, a 32x32 solid-color attached picture, and title/artist/album/
 * album_artist/track=7/12/disc=2/3 tags) - see the ffmpeg invocation in the
 * PR/commit that added this test if they ever need regenerating.
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
