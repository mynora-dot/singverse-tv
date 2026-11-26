package com.mynora.singverse.tv

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TvArtworkDiskCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val songPath = "/api/v1/tv/library/songs/song-1/cover"
    private val source = checkNotNull(TvArtworkSource.of(songPath))
    private val namespace = checkNotNull(artworkNamespace("nas", "https://NAS.example:443", "credential"))
    private val key = source.cacheKey(namespace, "cover-1")
    private val bytes = ByteArray(650) { it.toByte() }
    private val entry = TvArtworkEntry(artworkEtagFor(bytes), 300_001, "image/png", bytes)

    @Test fun officialRoutesNormalizeVariantsAndNamespacesStaySeparate() {
        assertEquals("$songPath?variant=thumbnail", source.path)
        assertEquals(source, TvArtworkSource.of(source.path))
        assertEquals(512, source.decodeLimit)
        assertEquals(1280, TvArtworkSource.of("/api/v1/tv/library/artists/a-1/images/portrait")?.decodeLimit)
        assertNull(TvArtworkSource.of("https://nas.example$songPath"))
        assertNull(TvArtworkSource.of("$songPath?variant=original"))
        assertNull(TvArtworkSource.of("/api/v1/tv/library/songs/song-1/text-tracks"))
        assertEquals(namespace, artworkNamespace("nas", "https://nas.example/", "credential"))
        assertNotEquals(namespace, artworkNamespace("other-nas", "https://nas.example/", "credential"))
        assertNotEquals(namespace, artworkNamespace("nas", "http://nas.example/", "credential"))
        assertNotEquals(namespace, artworkNamespace("nas", "https://nas.example/", "other-credential"))
        assertNotEquals(key, source.cacheKey(namespace, "cover-2"))
    }

    @Test fun entriesSurviveReopeningAndCorruptBytesAreRemoved() {
        val directory = temporary.newFolder()
        assertEquals("\"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad\"", artworkEtagFor("abc".toByteArray()))
        TvArtworkDiskCache(directory).write(key, entry, ::publish)
        val disk = TvArtworkDiskCache(directory)
        val restored = checkNotNull(disk.read(key))
        assertArrayEquals(bytes, restored.bytes)
        assertTrue(restored.isFresh(1))
        assertFalse(restored.isFresh(0))
        assertFalse(restored.isFresh(300_001))
        directory.listFiles()!!.single().appendBytes(byteArrayOf(1))
        assertNull(disk.read(key))
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun cacheBudgetEvictsAndInvalidatedWriterLeavesNoPartialEntry() {
        val directory = temporary.newFolder()
        val disk = TvArtworkDiskCache(directory, 1_500)
        disk.write(key, entry, ::publish)
        val otherKey = source.cacheKey(namespace, "other")
        disk.write(otherKey, entry, ::publish)
        assertNull(disk.read(key))
        assertNotNull(disk.read(otherKey))
        assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 1_500)

        disk.write(key, entry) { _, _ -> /* Disconnected before publishing. */ }
        assertNull(disk.read(key))
        assertFalse(directory.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    private fun publish(temporary: File, destination: File) {
        assertTrue(temporary.renameTo(destination))
    }
}
