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
    fun `a session keeps what was scanned, and one kept before reads as a room`() {
        val store = RerunSessionStore(folder.newFolder("scoped"))
        val vase = store.save(bundled(), "Object · Oct 19", RerunSessionSource.Recorded, scope = ScanScope.Object)
        assertEquals(ScanScope.Object, vase.scope)
        assertEquals(ScanScope.Object, store.list().single().scope)
        assertTrue(File(store.directoryOf(vase.id), "session.json").readText().contains("\"scope\": \"object\""))
        // A rename keeps it.
        assertEquals(ScanScope.Object, store.rename(vase.id, "Vase")!!.scope)
        assertEquals(ScanScope.Object, store.list().single().scope)

        // A session.json from before #4683 — or the iOS demo's — has no scope: it is a room.
        val old = """{"id":"A","title":"t","createdAt":"2026-09-28T12:32:05Z","source":"recorded",""" +
            """"duration":1,"pathMetres":1,"points":1,"planes":1,"photos":1}"""
        assertEquals(ScanScope.Room, RerunStoredSession.parse(old)!!.scope)
        // A scope a later build adds reads as a room too, rather than not at all.
        val later = old.replace("\"photos\":1}", "\"photos\":1,\"scope\":\"garden\"}")
        assertEquals(ScanScope.Room, RerunStoredSession.parse(later)!!.scope)
        for (scope in ScanScope.entries) {
            val session = RerunStoredSession.parse(old)!!.copy(scope = scope)
            assertEquals(session, RerunStoredSession.parse(session.toJson()))
        }
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

    @Test
    fun `an object scan shared as a file opens as an object, and an old file as a room (#4683)`() {
        val noRrd: (ByteArray) -> RerunCapturePack = { fail("not a .rrd"); error("") }
        val shareRoot = folder.newFolder("share")
        for (photos in listOf(true, false)) {
            val sent = RerunShareCopy.write(bundled(), "Mug", shareRoot, photos, ScanScope.Object)
            val store = RerunSessionStore(folder.newFolder("object-$photos"))
            val opened = store.import(sent.name, sent.readBytes(), noRrd)
            assertEquals(ScanScope.Object, opened.scope)
            assertEquals(ScanScope.Object, store.list().single().scope)
            // Shared on again from the phone it was opened on, it is still an object.
            val again = RerunShareCopy.write(store.capture(opened.id)!!, opened.title, shareRoot, photos, opened.scope)
            val other = RerunSessionStore(folder.newFolder("again-$photos"))
            assertEquals(ScanScope.Object, other.import(again.name, again.readBytes(), noRrd).scope)
        }

        // A room's file is the stored capture with only its title added, and opens as a room.
        val room = RerunShareCopy.write(bundled(), "Kitchen", shareRoot, true, ScanScope.Room)
        assertArrayEquals(RerunScanFile.write(RerunScanFile.withTitle(bundled(), "Kitchen")), room.readBytes())
        val rooms = RerunSessionStore(folder.newFolder("rooms"))
        assertEquals(ScanScope.Room, rooms.import(room.name, room.readBytes(), noRrd).scope)
        // A file written before the key — or with one this version does not know — opens as a room.
        val unknown = RerunCapturePack(
            String(bundled().manifest).replaceFirst("{", "{\"scope\":\"galaxy\",").toByteArray(),
            bundled().log,
            bundled().media,
        )
        assertEquals(ScanScope.Room, RerunScanFile.scopeOf(bundled()))
        assertEquals(ScanScope.Room, RerunScanFile.scopeOf(unknown))
        assertEquals(ScanScope.Object, RerunScanFile.scopeOf(RerunScanFile.withScope(unknown, ScanScope.Object)))
    }

    @Test
    fun `a scan file opens under the title it was saved with, colons included (#4695)`() {
        val noRrd: (ByteArray) -> RerunCapturePack = { fail("not a .rrd"); error("") }
        val title = "Object · Oct 19, 12:16 PM"
        val shareRoot = folder.newFolder("share")
        for (photos in listOf(true, false)) {
            // The share flow: the file name loses the colon, the title inside keeps it.
            val sent = RerunShareCopy.write(bundled(), title, shareRoot, photos, ScanScope.Object)
            assertEquals("Object · Oct 19, 12-16 PM.svscan", sent.name)
            val store = RerunSessionStore(folder.newFolder("share-$photos"))
            val opened = store.import(sent.name, sent.readBytes(), noRrd)
            assertEquals(title, opened.title)
            assertEquals(ScanScope.Object, opened.scope)
            assertEquals(title, store.list().single().title)
        }
        // The export sheet's "Original scan" writes the same stamped capture.
        val exported = RerunScanFile.write(RerunScanFile.stamped(bundled(), ScanScope.Room, "Room · Oct 19, 9:05 AM"))
        val exportStore = RerunSessionStore(folder.newFolder("export"))
        val reopened = exportStore.import("Room · Oct 19, 9-05 AM.svscan", exported, noRrd)
        assertEquals("Room · Oct 19, 9:05 AM", reopened.title)
        // Stamping twice with the same title changes nothing; every other key survives.
        val stamped = RerunScanFile.withTitle(bundled(), title)
        assertTrue(stamped === RerunScanFile.withTitle(stamped, title))
        val before = kotlinx.serialization.json.Json.parseToJsonElement(String(bundled().manifest))
            as kotlinx.serialization.json.JsonObject
        val after = kotlinx.serialization.json.Json.parseToJsonElement(String(stamped.manifest))
            as kotlinx.serialization.json.JsonObject
        assertEquals(before, kotlinx.serialization.json.JsonObject(after - "title"))

        // A file written before the key, or with a blank or non-string one, keeps its file name.
        val old = RerunSessionStore(folder.newFolder("old"))
        assertNull(RerunScanFile.titleOf(bundled()))
        assertEquals("kitchen", old.import("kitchen.svscan", RerunScanFile.write(bundled()), noRrd).title)
        for ((index, value) in listOf("\"  \"", "42").withIndex()) {
            val odd = RerunCapturePack(
                String(bundled().manifest).replaceFirst("{", "{\"title\":$value,").toByteArray(),
                bundled().log,
                bundled().media,
            )
            assertNull(RerunScanFile.titleOf(odd))
            val into = RerunSessionStore(folder.newFolder("odd-$index"))
            assertEquals("odd scan", into.import("odd scan.svscan", RerunScanFile.write(odd), noRrd).title)
        }
    }

    @Test
    fun `the same file opened twice is one session, a different file is another (#4680)`() {
        val store = RerunSessionStore(folder.newFolder("sessions"))
        val noRrd: (ByteArray) -> RerunCapturePack = { fail("not a .rrd"); error("") }
        val file = RerunScanFile.write(bundled())

        val first = store.import("Example scan.svscan", file, noRrd, nowMillis = 1_000L)
        val renamed = store.rename(first.id, "Kitchen")!!
        // Same bytes, another name, later: it opens the session the first import made, as it is now.
        val again = store.import("Example scan (1).svscan", file.copyOf(), noRrd, nowMillis = 9_000L)
        assertEquals(renamed, again)
        assertEquals(listOf(renamed), store.list())

        // One byte different is another file.
        val pack = bundled()
        val other = RerunScanFile.write(RerunCapturePack(pack.manifest, pack.log + '\n'.code.toByte(), pack.media))
        val second = store.import("Example scan.svscan", other, noRrd, nowMillis = 2_000L)
        assertFalse(second.id == first.id)
        assertEquals(2, store.list().size)

        // Deleted, it imports anew.
        store.delete(first.id)
        val back = store.import("Example scan.svscan", file, noRrd)
        assertFalse(back.id == first.id)
        assertEquals(2, store.list().size)
    }

    @Test
    fun `two imports of one file racing each other keep one session (#4680)`() {
        val root = folder.newFolder("sessions")
        val noRrd: (ByteArray) -> RerunCapturePack = { error("not a .rrd") }
        val file = RerunScanFile.write(bundled())
        // Two stores on one directory, as two activities each hold one.
        val stores = listOf(RerunSessionStore(root), RerunSessionStore(root))
        val start = java.util.concurrent.CountDownLatch(1)
        val ids = java.util.Collections.synchronizedList(mutableListOf<String>())
        val threads = stores.map { store ->
            Thread {
                start.await()
                ids += store.import("Example scan.svscan", file, noRrd).id
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, ids.toSet().size)
        assertEquals(1, RerunSessionStore(root).list().size)
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
