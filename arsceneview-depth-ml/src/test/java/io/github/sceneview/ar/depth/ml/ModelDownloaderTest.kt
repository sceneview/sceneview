package io.github.sceneview.ar.depth.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

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
        assertFalse(cache.resolve("m.tflite.part").exists())

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
