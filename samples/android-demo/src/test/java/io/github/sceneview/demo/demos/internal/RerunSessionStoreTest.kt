package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** "Your sessions" on disk, and the `.svscan` file — the iOS demo's layout and bytes (#4068). */
class RerunSessionStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    // ── The scan file ─────────────────────────────────────────────────────────

    @Test
    fun `a scan file round-trips the three capture files byte for byte`() {
        val capture = bundled()
        val file = RerunScanFile.write(capture)

        val back = RerunScanFile.read(file)!!
        assertArrayEquals(capture.manifest, back.manifest)
        assertArrayEquals(capture.log, back.log)
        assertArrayEquals(capture.media, back.media)
        // Written again from what was read, it is the same file.
        assertArrayEquals(file, RerunScanFile.write(back))
    }

    @Test
    fun `a scan file is a plain stored zip any zip tool reads, data on 64-byte boundaries`() {
        val capture = RerunCapturePack("{}".toByteArray(), "{\"t\":1}\n".toByteArray(), ByteArray(300) { it.toByte() })
        val file = RerunScanFile.write(capture)

        val seen = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(file)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                assertEquals(ZipEntry.STORED, entry.method)
                seen[entry.name] = zip.readBytes()
            }
        }
        val names = listOf(RerunCapturePack.MANIFEST, RerunCapturePack.LOG, RerunCapturePack.MEDIA)
        assertEquals(names, seen.keys.toList())
        assertArrayEquals(capture.media, seen[RerunCapturePack.MEDIA])

        // The iOS writer's alignment: every file's data starts on a 64-byte boundary.
        var at = 0
        repeat(3) { index ->
            val nameLength = StoredZip.u16(file, at + 26)
            val extraLength = StoredZip.u16(file, at + 28)
            val start = at + 30 + nameLength + extraLength
            assertEquals(0, start % 64)
            at = start + capture.files()[index].second.size
        }
        assertTrue(RerunScanFile.looksLikeOne(file.copyOf(64)))
    }

    @Test
    fun `a zip that compresses, or misses a file, is not a scan file`() {
        val deflated = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                for (name in listOf(RerunCapturePack.MANIFEST, RerunCapturePack.LOG, RerunCapturePack.MEDIA)) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write("x".toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        assertNull(RerunScanFile.read(deflated))
        assertNull(RerunScanFile.read(StoredZip.write(listOf(RerunCapturePack.MANIFEST to ByteArray(1)))))
        assertNull(RerunScanFile.read("hello".toByteArray()))
        assertNull(RerunScanFile.read(ByteArray(0)))
    }

    @Test
    fun `a scan file is named after its session, never with a path in it`() {
        assertEquals("Room · Sep 28, 2-32 PM.svscan", RerunScanFile.fileName("Room · Sep 28, 2:32 PM"))
        assertEquals("a-b-c.svscan", RerunScanFile.fileName("a/b\\c"))
        assertEquals("scan.svscan", RerunScanFile.fileName("   "))
    }

    @Test
    fun `a file is told apart by its name, or by its first bytes`() {
        val scan = RerunScanFile.write(bundled()).copyOf(64)
        assertEquals(RerunFileKind.Scan, RerunFileKind.of("kitchen.SVSCAN", ByteArray(0)))
        assertEquals(RerunFileKind.Rrd, RerunFileKind.of("kitchen.rrd", ByteArray(0)))
        assertEquals(RerunFileKind.Scan, RerunFileKind.of(null, scan))
        assertEquals(RerunFileKind.Rrd, RerunFileKind.of("download", "RRF2....".toByteArray()))
        assertNull(RerunFileKind.of("model.glb", "glTF".toByteArray()))
    }

    // ── session.json ──────────────────────────────────────────────────────────

    @Test
    fun `session json reads back, and reads the iOS encoder's`() {
        val session = RerunStoredSession(
            id = "5B0E1C0A-0000-4000-8000-000000000001",
            title = "Room · Sep 28, 2:32 PM",
            createdAt = 1_790_000_000L,
            source = RerunSessionSource.Recorded,
            duration = 18.5f,
            pathMetres = 4.2f,
            points = 3_812,
            planes = 3,
            photos = 42,
        )
        assertEquals(session, RerunStoredSession.parse(session.toJson()))
        assertTrue(session.toJson().contains("\"createdAt\": \"2026-09-21T"))

        // What iOS's JSONEncoder writes: pretty, sorted keys, " : ", ISO 8601 in UTC.
        val ios = """
            {
              "createdAt" : "2026-09-28T12:32:05Z",
              "duration" : 18.53333282470703,
              "id" : "5B0E1C0A-0000-4000-8000-000000000002",
              "pathMetres" : 4.2,
              "photos" : 42,
              "planes" : 3,
              "points" : 3812,
              "source" : "scan",
              "title" : "kitchen"
            }
        """.trimIndent()
        val read = RerunStoredSession.parse(ios)!!
        assertEquals(RerunStoredSession.epochSecondOf("2026-09-28T12:32:05Z"), read.createdAt)
        assertEquals(RerunSessionSource.Scan, read.source)
        assertEquals("kitchen", read.title)
        assertEquals(3812, read.points)
    }

    @Test
    fun `a session json missing a key, or from nowhere, does not read`() {
        assertNull(RerunStoredSession.parse("""{"id":"A","title":"t","source":"recorded"}"""))
        assertNull(RerunStoredSession.parse("not json"))
        val unknownSource = """{"id":"A","title":"t","createdAt":"2026-09-28T12:32:05Z","source":"cloud",""" +
            """"duration":1,"pathMetres":1,"points":1,"planes":1,"photos":1}"""
        assertNull(RerunStoredSession.parse(unknownSource))
    }

    // ── The store ─────────────────────────────────────────────────────────────

    @Test
    fun `a saved session lists with its figures, reads back whole, and deletes`() {
        val store = RerunSessionStore(folder.newFolder("sessions"))
        val capture = bundled()
        val saved = store.save(capture, "Room · Sep 28", RerunSessionSource.Recorded, nowMillis = 1_790_000_000_999L)

        assertEquals(1_790_000_000L, saved.createdAt)
        assertEquals(listOf(saved), store.list())
        assertTrue(saved.points > 0)
        assertTrue(saved.photos > 0)
        assertTrue(saved.pathMetres > 0f)
        assertEquals(capture, store.capture(saved.id))

        val dir = store.directoryOf(saved.id)
        assertEquals(
            setOf(
                "capture-manifest.json", "capture-session.jsonl", "capture-media.bin", "session.json", "thumbnail.jpg",
            ),
            dir.list()!!.toSet(),
        )
        // The thumbnail is the first photo, byte for byte as recorded (the iOS demo's choice too).
        assertArrayEquals(capture.open()!!.firstPhoto(), store.thumbnail(saved.id)!!.readBytes())

        assertTrue(store.delete(saved.id))
        assertFalse(dir.exists())
        assertTrue(store.list().isEmpty())
        assertNull(store.capture(saved.id))
    }

    @Test
    fun `a save cut short before session json never lists, and the newest lists first`() {
        val root = folder.newFolder("sessions")
        val store = RerunSessionStore(root)
        File(root, "HALF").apply { mkdirs() }.resolve(RerunCapturePack.MANIFEST).writeText("{}")
        val older = store.save(bundled(), "older", RerunSessionSource.Recorded, nowMillis = 1_000_000L)
        val newer = store.save(bundled(), "newer", RerunSessionSource.Scan, nowMillis = 2_000_000L)

        assertEquals(listOf(newer, older), store.list())
    }

    @Test
    fun `a scan file imports as a session titled after it, and an empty one is refused`() {
        val store = RerunSessionStore(folder.newFolder("sessions"))
        val noRrd: (ByteArray) -> RerunCapturePack = { fail("not a .rrd"); error("") }

        val imported = store.import("kitchen.svscan", RerunScanFile.write(bundled()), noRrd)
        assertEquals("kitchen", imported.title)
        assertEquals(RerunSessionSource.Scan, imported.source)
        assertEquals(bundled(), store.capture(imported.id))

        val empty = RerunCapturePack(bundled().manifest, ByteArray(0), ByteArray(0))
        assertThrows<RerunImportFailure.Empty> { store.import("empty.svscan", RerunScanFile.write(empty), noRrd) }
        assertThrows<RerunImportFailure.Unreadable> { store.import("broken.svscan", "PK".toByteArray(), noRrd) }
        assertThrows<RerunImportFailure.Unsupported> { store.import("notes.txt", "hello".toByteArray(), noRrd) }
        assertThrows<RerunImportFailure.RrdCompressed> {
            store.import("take.rrd", "RRF2".toByteArray(), readRrd = {
                throw RerunImportFailure.RrdCompressed("take.rrd")
            })
        }
        // Any other exception from the reader is an unreadable file.
        assertThrows<RerunImportFailure.Unreadable> {
            store.import("bad.rrd", "RRF2".toByteArray(), readRrd = { error("boom") })
        }
        assertEquals(1, store.list().size)
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (expected: Throwable) {
            assertTrue("expected ${T::class.simpleName}, got $expected", expected is T)
            return
        }
        fail("expected ${T::class.simpleName}")
    }

    /** The bundled sample's three files, as a capture. */
    private fun bundled(): RerunCapturePack {
        val dir = listOf(
            File("src/main/assets/${RerunReplayAssets.DIR}"),
            File("samples/android-demo/src/main/assets/${RerunReplayAssets.DIR}"),
        ).first { it.isDirectory }
        return RerunCapturePack(
            manifest = File(dir, "showcase-manifest.json").readBytes(),
            log = File(dir, "showcase-session.jsonl").readBytes(),
            media = File(dir, "showcase-media.bin").readBytes(),
        ).also { assertNotNull(it.open()) }
    }
}
