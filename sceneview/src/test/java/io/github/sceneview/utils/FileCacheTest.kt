package io.github.sceneview.utils

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [FileCache] — the on-disk cache for remote http(s) files.
 *
 * [FileCache] is deliberately `Context`-free and operates on a plain [File] directory, so these
 * run on a pure JVM with no Android or Filament dependency.
 */
class FileCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cacheDir: File

    private val url = "https://example.com/models/helmet.glb"
    private val bytes = byteArrayOf(1, 2, 3, 4, 5)

    @Before
    fun setUp() {
        cacheDir = tempFolder.newFolder("cache")
        FileCache.enabled = true
    }

    @After
    fun tearDown() {
        FileCache.enabled = true
    }

    private fun bufferBytes(buffer: java.nio.ByteBuffer?): ByteArray {
        requireNotNull(buffer)
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    // ── getOrPut: miss / hit ─────────────────────────────────────────────────────

    @Test
    fun `getOrPut on a miss downloads and returns the bytes`() = runBlocking {
        val result = FileCache.getOrPut(cacheDir, url) { bytes }
        assertArrayEquals(bytes, bufferBytes(result))
    }

    @Test
    fun `getOrPut on a miss persists the file to disk`() = runBlocking {
        FileCache.getOrPut(cacheDir, url) { bytes }
        assertTrue(FileCache.fileOf(cacheDir, url).isFile)
    }

    @Test
    fun `getOrPut on a hit does not invoke download again`() = runBlocking {
        val downloads = AtomicInteger(0)
        FileCache.getOrPut(cacheDir, url) { downloads.incrementAndGet(); bytes }
        val second = FileCache.getOrPut(cacheDir, url) { downloads.incrementAndGet(); bytes }

        assertEquals(1, downloads.get())
        assertArrayEquals(bytes, bufferBytes(second))
    }

    @Test
    fun `getOrPut returns null when download yields null`() = runBlocking {
        val result = FileCache.getOrPut(cacheDir, url) { null }
        assertNull(result)
        assertFalse(FileCache.fileOf(cacheDir, url).exists())
    }

    @Test
    fun `getOrPut keeps distinct entries for distinct urls`() = runBlocking {
        val other = "https://example.com/models/fox.glb"
        FileCache.getOrPut(cacheDir, url) { bytes }
        FileCache.getOrPut(cacheDir, other) { byteArrayOf(9, 9) }

        assertArrayEquals(bytes, FileCache.fileOf(cacheDir, url).readBytes())
        assertArrayEquals(byteArrayOf(9, 9), FileCache.fileOf(cacheDir, other).readBytes())
    }

    // ── disabled ─────────────────────────────────────────────────────────────────

    @Test
    fun `getOrPut bypasses cache when disabled`() = runBlocking {
        FileCache.enabled = false
        val downloads = AtomicInteger(0)

        FileCache.getOrPut(cacheDir, url) { downloads.incrementAndGet(); bytes }
        FileCache.getOrPut(cacheDir, url) { downloads.incrementAndGet(); bytes }

        assertEquals(2, downloads.get())
        assertFalse(FileCache.fileOf(cacheDir, url).exists())
    }

    // ── concurrency ──────────────────────────────────────────────────────────────

    @Test
    fun `getOrPut leaves no lingering per-url locks`() = runBlocking {
        // Regression pin: the per-URL lock map must not grow without bound as distinct URLs
        // are streamed — each getOrPut drops its lock once nobody holds it.
        for (i in 1..25) {
            FileCache.getOrPut(cacheDir, "https://example.com/m$i.glb") { byteArrayOf(i.toByte()) }
        }
        assertEquals(0, FileCache.pendingLockCount())
    }

    @Test
    fun `concurrent getOrPut for the same url downloads only once`() = runBlocking {
        val downloads = AtomicInteger(0)
        val results = (1..8).map {
            async {
                FileCache.getOrPut(cacheDir, url) {
                    downloads.incrementAndGet()
                    delay(20)
                    bytes
                }
            }
        }.awaitAll()

        assertEquals(1, downloads.get())
        results.forEach { assertArrayEquals(bytes, bufferBytes(it)) }
    }

    // ── clear / size ─────────────────────────────────────────────────────────────

    @Test
    fun `clear removes every entry and returns the count`() = runBlocking {
        FileCache.getOrPut(cacheDir, url) { bytes }
        FileCache.getOrPut(cacheDir, "https://example.com/b.glb") { byteArrayOf(7) }

        assertEquals(2, FileCache.clear(cacheDir))
        assertEquals(0L, FileCache.size(cacheDir))
    }

    @Test
    fun `clear on an empty directory returns zero`() {
        assertEquals(0, FileCache.clear(cacheDir))
    }

    @Test
    fun `size reports the total bytes on disk`() = runBlocking {
        FileCache.getOrPut(cacheDir, url) { bytes }
        FileCache.getOrPut(cacheDir, "https://example.com/b.glb") { byteArrayOf(7, 7, 7) }

        assertEquals((bytes.size + 3).toLong(), FileCache.size(cacheDir))
    }

    // ── keyOf / fileOf ───────────────────────────────────────────────────────────

    @Test
    fun `keyOf is deterministic`() {
        assertEquals(FileCache.keyOf(url), FileCache.keyOf(url))
    }

    @Test
    fun `keyOf differs per url`() {
        assertNotEquals(FileCache.keyOf(url), FileCache.keyOf("https://example.com/other.glb"))
    }

    @Test
    fun `keyOf is a 64-char lowercase hex string`() {
        val key = FileCache.keyOf(url)
        assertEquals(64, key.length)
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `fileOf resolves inside the cache directory`() {
        assertEquals(cacheDir, FileCache.fileOf(cacheDir, url).parentFile)
    }
}
