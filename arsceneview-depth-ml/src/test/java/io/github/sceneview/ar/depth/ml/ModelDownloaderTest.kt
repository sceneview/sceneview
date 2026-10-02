package io.github.sceneview.ar.depth.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class ModelDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // SHA-256 of the 3 ASCII bytes "abc" (FIPS 180-2 test vector).
    private val abcSha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun `downloads, verifies and caches`() {
        val remote = tmp.newFile("remote.tflite").apply { writeText("abc") }
        val cache = tmp.newFolder("cache")
        val source = ModelSource.Url(remote.toURI().toString(), abcSha, sizeBytes = 3, fileName = "m.tflite")
        var lastProgress = 0f
        val file = ModelDownloader.ensure(source, cache) { lastProgress = it }
        assertEquals("abc", file.readText())
        assertEquals(1f, lastProgress, 0f)
        assertTrue(cache.list()!!.none { it.endsWith(".part") })

        // Second call is served from the cache without reading the remote.
        remote.delete()
        var called = false
        assertEquals(file, ModelDownloader.ensure(source, cache) { called = true })
        assertFalse(called)
    }

    @Test
    fun `a digest mismatch is never kept`() {
        val remote = tmp.newFile("evil.tflite").apply { writeText("abd") }
        val cache = tmp.newFolder("cache")
        val source = ModelSource.Url(remote.toURI().toString(), abcSha, sizeBytes = 3, fileName = "m.tflite")
        try {
            ModelDownloader.ensure(source, cache)
            fail("expected a digest mismatch")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("digest mismatch"))
        }
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    /**
     * Regression for the shared `<name>.part` race: several loaders starting at once used to
     * delete and rewrite the same temporary file under each other, so all but one failed with a
     * digest mismatch or "Could not move" — or published a file another thread was still writing.
     */
    @Test
    fun `concurrent loads download once and all get the verified file`() {
        val payload = Random(42).nextBytes(2 * 1024 * 1024)
        val remote = tmp.newFile("remote.tflite").apply { writeBytes(payload) }
        val cache = tmp.newFolder("cache")
        val source = ModelSource.Url(remote.toURI().toString(), sha256(payload), payload.size.toLong(), "m.tflite")

        val callers = 8
        val start = CountDownLatch(1)
        val downloads = AtomicInteger()
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val results = Collections.synchronizedList(mutableListOf<File>())
        val pool = Executors.newFixedThreadPool(callers)
        repeat(callers) {
            pool.execute {
                start.await()
                var downloading = false
                try {
                    val file = ModelDownloader.ensure(source, cache) {
                        if (!downloading) downloads.incrementAndGet()
                        downloading = true
                        Thread.sleep(1) // keep the download in flight while the others arrive
                    }
                    // Checked at return time: the file must already be complete and verified.
                    assertEquals(source.sha256, ModelDownloader.sha256(file))
                    results += file
                } catch (t: Throwable) {
                    errors += t
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))

        assertEquals(emptyList<Throwable>(), errors.toList())
        assertEquals(callers, results.size)
        assertEquals(setOf(cache.resolve("m.tflite")), results.toSet())
        assertEquals(1, downloads.get())
        assertEquals(listOf("m.tflite"), cache.list()!!.toList())
    }

    @Test
    fun `stale temporary files are cleaned and a live foreign one is left alone`() {
        val remote = tmp.newFile("remote.tflite").apply { writeText("abc") }
        val cache = tmp.newFolder("cache")
        val stale = cache.resolve("m.tflite.dead.part").apply {
            writeText("half")
            setLastModified(System.currentTimeMillis() - 60 * 60 * 1000L)
        }
        val live = cache.resolve("m.tflite.other-process.part").apply { writeText("ab") }
        val source = ModelSource.Url(remote.toURI().toString(), abcSha, sizeBytes = 3, fileName = "m.tflite")

        assertEquals("abc", ModelDownloader.ensure(source, cache).readText())
        assertFalse(stale.exists())
        assertEquals("ab", live.readText())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `pinned model is the Apache-2_0 Small variant at a fixed revision`() {
        val source = ModelSource.DepthAnythingV2Small
        assertTrue(source.url.contains("depth-anything-v2-small"))
        assertTrue(source.url.contains("/resolve/178427e448dbf4da93b1e7b1b2abc103ad329bd6/"))
        assertFalse(source.url.contains("base", ignoreCase = true) || source.url.contains("large", ignoreCase = true))
        assertEquals(64, source.sha256.length)
        assertEquals("depth_anything_v2_small_wi8_afp32.tflite", source.fileName)
    }
}
