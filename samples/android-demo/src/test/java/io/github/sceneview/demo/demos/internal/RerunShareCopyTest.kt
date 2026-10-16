package io.github.sceneview.demo.demos.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class RerunShareCopyTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `photos included sends exactly the stored capture in a cache copy`() {
        val capture = fixture()
        val stored = temp.newFile("original.svscan").apply { writeBytes(RerunScanFile.write(capture)) }
        val original = stored.readBytes()
        val cache = temp.newFolder("cache")
        val shared = RerunShareCopy.write(capture, "Room", cache, true)
        assertTrue(shared.canonicalPath.startsWith(cache.canonicalPath + File.separator))
        assertFalse(shared.canonicalPath == stored.canonicalPath)
        assertArrayEquals(original, shared.readBytes())
        assertArrayEquals(original, stored.readBytes())
        assertEntries(shared)
        val opened = RerunScanFile.read(shared.readBytes())!!.open()!!
        assertArrayEquals(byteArrayOf(1, 2, 3), opened.firstPhoto())
        assertEquals(1, opened.trace.imageCount)
    }

    @Test
    fun `photos excluded repacks surfaces and points with new offsets and preserves the original`() {
        val capture = fixture()
        val stored = temp.newFile("original.svscan").apply { writeBytes(RerunScanFile.write(capture)) }
        val original = stored.readBytes()
        val shared = RerunShareCopy.write(capture, "Room", temp.newFolder("cache"), false)
        assertEntries(shared)
        assertTrue(shared.length() < stored.length())
        assertArrayEquals(original, stored.readBytes())
        assertArrayEquals(original, RerunScanFile.write(capture))
        val pack = RerunScanFile.read(shared.readBytes())!!
        val opened = pack.open()!!
        assertEquals(setOf("planes/a.jpg", ReplayDense.PATH), opened.manifest.media.keys)
        assertEquals(MediaSpan(0, 2), opened.manifest.media["planes/a.jpg"])
        assertEquals(MediaSpan(2, 4), opened.manifest.media[ReplayDense.PATH])
        assertArrayEquals(byteArrayOf(4, 5), opened.bytesOf("planes/a.jpg"))
        assertArrayEquals(byteArrayOf(6, 7, 8, 9), opened.bytesOf(ReplayDense.PATH))
        assertNull(opened.bytesOf("scan/frame-0001.jpg"))
        assertNull(opened.bytesOf("orphan.jpg"))
        assertNull(opened.firstPhoto())
        assertEquals(0, opened.trace.imageCount)
        assertEquals(0, opened.manifest.frameCount)
        assertEquals(1, opened.manifest.textures.size)
        assertEquals(2, opened.manifest.version)
        assertNotNull(opened.manifest.dense)
        assertEquals(Json.parseToJsonElement("{\"future\":true}"),
            Json.parseToJsonElement(String(pack.manifest)).jsonObject["place"])
        assertEquals(1, opened.trace.frameAt(1f).mapPointCount)
        assertEquals(1, opened.trace.frameAt(1f).planes.size)
        assertEquals(1f, opened.trace.duration, 0f)
        assertEquals(1f, opened.trace.frameAt(1f).trail.last(), 0f)
        val store = RerunSessionStore(temp.newFolder("sessions"))
        val imported = store.import(shared.name, shared.readBytes(), readRrd = { error("Not RRD") })
        assertEquals(0, imported.photos)
        assertNotNull(store.capture(imported.id)!!.open())
        assertNull(store.thumbnail(imported.id))
    }

    @Test
    fun `a capture with only photos in its media opens after sharing without them`() {
        val capture = fixture()
        val manifest = """{"frames":1,"media":[{"path":"scan/frame-0001.jpg","offset":0,"length":3}]}"""
        val photoOnly = RerunCapturePack(manifest.toByteArray(), capture.log, byteArrayOf(1, 2, 3))
        val shared = RerunShareCopy.write(photoOnly, "Room", temp.newFolder(), false)
        val opened = RerunScanFile.read(shared.readBytes())!!.open()!!
        assertTrue(opened.media.isEmpty())
        assertEquals(0, opened.trace.imageCount)
        assertFalse(opened.trace.isEmpty)
    }

    @Test
    fun `a photo named as a texture or as the cloud does not leave with a scan shared without photos`() {
        val capture = fixture()
        val manifest = """{"version":2,"frames":1,
            "textures":[{"plane":1,"path":"scan/frame-0001.jpg","origin":[0,0,0],"u":[1,0,0],"v":[0,0,1]},
                {"plane":2,"path":"planes/a.jpg","origin":[0,0,0],"u":[1,0,0],"v":[0,0,1]}],
            "dense":{"path":"frames/0001.jpg","count":1},
            "media":[{"path":"scan/frame-0001.jpg","offset":0,"length":3},
                {"path":"planes/a.jpg","offset":3,"length":2},
                {"path":"frames/0001.jpg","offset":5,"length":4}]}""".trimIndent()
        val forged = RerunCapturePack(manifest.toByteArray(), capture.log, capture.media)
        val shared = RerunShareCopy.write(forged, "Room", temp.newFolder(), false)
        val opened = RerunScanFile.read(shared.readBytes())!!.open()!!
        assertEquals(setOf("planes/a.jpg"), opened.manifest.media.keys)
        assertArrayEquals(byteArrayOf(4, 5), opened.bytesOf("planes/a.jpg"))
        assertNull(opened.bytesOf("scan/frame-0001.jpg"))
        assertNull(opened.bytesOf("frames/0001.jpg"))
        assertFalse(opened.trace.isEmpty)
    }

    @Test
    fun `only the latest copy stays in the share cache`() {
        val capture = fixture()
        val cache = temp.newFolder("cache")
        val first = RerunShareCopy.write(capture, "Room", cache, true)
        val second = RerunShareCopy.write(capture, "Room", cache, false)
        assertFalse(first.exists())
        assertTrue(second.exists())
        assertEquals(listOf(second.parentFile), cache.listFiles()!!.toList())
        assertEquals(listOf(second), second.parentFile!!.listFiles()!!.toList())
    }

    private fun assertEntries(file: File) {
        ZipFile(file).use { zip ->
            assertEquals(listOf(RerunCapturePack.MANIFEST, RerunCapturePack.LOG, RerunCapturePack.MEDIA),
                zip.entries().asSequence().map { it.name }.toList())
        }
    }

    private fun fixture(): RerunCapturePack {
        val manifest = """{"version":2,"frames":1,"place":{"future":true},
            "textures":[{"plane":1,"path":"planes/a.jpg","origin":[0,0,0],"u":[1,0,0],"v":[0,0,1]}],
            "dense":{"path":"dense/points.bin","count":1},
            "media":[{"path":"scan/frame-0001.jpg","offset":0,"length":3},
                {"path":"planes/a.jpg","offset":3,"length":2},
                {"path":"dense/points.bin","offset":5,"length":4},
                {"path":"orphan.jpg","offset":9,"length":4096}]}""".trimIndent()
        val log = ArDebugLogWriter.write(listOf(
            ArDebugEvent.CameraPose(1_000_000_000L, DebugPose(0f, 0f, 0f)),
            ArDebugEvent.Image(1_000_000_000L, "scan/frame-0001.jpg"),
            ArDebugEvent.Points(1_000_000_000L, floatArrayOf(0f, 0f, 0f), confidences = null,
                colors = intArrayOf(0xFF123456.toInt())),
            ArDebugEvent.Plane(1_000_000_000L, 1, DebugPlaneKind.Floor,
                floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)),
            ArDebugEvent.CameraPose(2_000_000_000L, DebugPose(0f, 0f, 1f)),
        ))
        return RerunCapturePack(manifest.toByteArray(), log.toByteArray(),
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9) + ByteArray(4096))
    }
}
