package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.cos
import kotlin.math.sin

/**
 * A long scan's save (#4815): its log written and read without one giant string, the save that
 * does not parse it twice, the scan file streamed — all byte for byte what they were — and why a
 * save failed, said in the user's words.
 */
class ScanSaveTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `the log written as bytes is the log written as text, across chunk boundaries`() {
        // About 3 MB: many of the writer's 64 KB chunks, lines cut across their edges.
        val events = journal(seconds = 40, pointsPerFrame = 300)
        val bytes = ArDebugLogWriter.writeBytes(events)
        assertTrue("the journal spans several chunks", bytes.size > 2 * (1 shl 20))
        assertArrayEquals(ArDebugLogWriter.write(events).toByteArray(), bytes)
        assertArrayEquals(ByteArray(0), ArDebugLogWriter.writeBytes(emptyList()))
    }

    @Test
    fun `the log read from bytes is the log read from its text`() {
        val log = bundled().log
        val fromText = parseArDebugLog(String(log).lineSequence())
        assertTrue(fromText.isNotEmpty())
        assertEquals(fromText.map(ArDebugLogWriter::line), parseArDebugLog(log).map(ArDebugLogWriter::line))
    }

    @Test
    fun `a save handed the opened capture keeps exactly what a save that opens it keeps`() {
        val capture = bundled()
        val opened = requireNotNull(capture.open())
        val plain = RerunSessionStore(folder.newFolder("plain"))
        val handed = RerunSessionStore(folder.newFolder("handed"))
        val a = plain.save(capture, "Room", RerunSessionSource.Recorded, 1_000_000L)
        val b = handed.save(capture, "Room", RerunSessionSource.Recorded, 1_000_000L, opened = opened)
        assertEquals(a.copy(id = b.id), b)
        assertEquals(plain.list().map { it.copy(id = "") }, handed.list().map { it.copy(id = "") })
        for (name in listOf(RerunCapturePack.MANIFEST, RerunCapturePack.LOG, RerunCapturePack.MEDIA, "thumbnail.jpg")) {
            assertArrayEquals(name, File(plain.root, "${a.id}/$name").readBytes(), File(handed.root, "${b.id}/$name").readBytes())
        }
    }

    @Test
    fun `a scan file streamed is the scan file written in memory`() {
        val capture = bundled()
        val streamed = ByteArrayOutputStream().also { RerunScanFile.write(capture, it) }.toByteArray()
        assertArrayEquals(RerunScanFile.write(capture), streamed)
        assertEquals(capture, RerunScanFile.read(streamed))
    }

    @Test
    fun `Save as file writes a scan file that opens again`() {
        val capture = bundled()
        val file = RerunShareCopy.write(capture, "Garden · Oct 23", folder.newFolder("unsaved"), includePhotos = true)
        assertEquals("Garden · Oct 23.svscan", file.name)
        val read = requireNotNull(RerunScanFile.read(file.readBytes()))
        assertArrayEquals(capture.log, read.log)
        assertArrayEquals(capture.media, read.media)
    }

    @Test
    fun `a write that fails is a full storage only when the phone has less room than the scan`() {
        val notADirectory = folder.newFile("sessions")
        val error = requireNotNull(
            runCatching { RerunSessionStore(notADirectory).save(bundled(), "Room", RerunSessionSource.Recorded) }
                .exceptionOrNull(),
        )
        assertTrue("got $error", error is IOException)
        val scan = 85L * MB
        // Room enough: not the user's to free up, and Save as file may still keep it.
        assertEquals(ScanSaveFailure.Other, ScanSaveFailure.of(error, scanBytes = scan, freeStorageBytes = 3_000L * MB))
        assertEquals(ScanSaveFailure.Other, ScanSaveFailure.of(error))
        assertEquals(ScanSaveFailure.Storage, ScanSaveFailure.of(error, scanBytes = scan, freeStorageBytes = 12L * MB))
    }

    @Test
    fun `each failure is read off what stopped the save, or one of its causes`() {
        assertEquals(ScanSaveFailure.Memory, ScanSaveFailure.of(OutOfMemoryError("Failed to allocate")))
        assertEquals(ScanSaveFailure.Memory, ScanSaveFailure.of(IllegalStateException("build", OutOfMemoryError())))
        assertEquals(ScanSaveFailure.Storage, ScanSaveFailure.of(IOException("No space left on device"), MB, 0L))
        assertEquals(ScanSaveFailure.Empty, ScanSaveFailure.of(RerunImportFailure.Empty("Room")))
        assertEquals(ScanSaveFailure.Other, ScanSaveFailure.of(RerunImportFailure.Unreadable("Room")))
        assertEquals(ScanSaveFailure.Other, ScanSaveFailure.of(IllegalStateException("QA")))
    }

    @Test
    fun `the card says why and what to do, and offers a file only when one can keep the scan`() {
        assertEquals(ScanCopy.SAVE_FAILED_DETAIL, ScanCopy.saveFailedDetail(null))
        assertTrue(ScanCopy.saveFailedDetail(ScanSaveFailure.Memory).contains("ran out of memory"))
        assertTrue(ScanCopy.saveFailedDetail(ScanSaveFailure.Memory).contains("save it as a file"))
        val storage = ScanCopy.saveFailedDetail(ScanSaveFailure.Storage, scanBytes = 85L * 1024 * 1024)
        assertTrue(storage, storage.contains("storage is full") && storage.contains("85.0 MB"))
        assertTrue(ScanCopy.saveFailedDetail(ScanSaveFailure.Empty).contains("nothing to keep"))
        assertTrue(ScanSaveFailure.Memory.offersFile)
        assertTrue(ScanSaveFailure.Other.offersFile)
        assertFalse(ScanSaveFailure.Storage.offersFile)
        assertFalse(ScanSaveFailure.Empty.offersFile)
        assertFalse(ScanSaveFailure.Refused.offersFile)
    }

    @Test
    fun `a part that did not line up says why, and offers a separate scan instead of Try again`() {
        assertEquals("Couldn't line up this part", ScanCopy.saveFailedTitle(ScanSaveFailure.Refused))
        assertEquals("Save as a separate scan", ScanCopy.saveFailedAction(ScanSaveFailure.Refused))
        assertEquals(ScanCopy.SAVE_FAILED, ScanCopy.saveFailedTitle(ScanSaveFailure.Memory))
        assertEquals(ScanCopy.TRY_AGAIN, ScanCopy.saveFailedAction(ScanSaveFailure.Memory))
        assertEquals(ScanCopy.TRY_AGAIN, ScanCopy.saveFailedAction(null))
        assertEquals(
            "This part didn't line up with your scan: too little of what you scanned before is in it. " +
                "Save it as a separate scan to keep it, or discard it. Your scan stays as it was.",
            ScanCopy.saveFailedDetail(ScanSaveFailure.Refused, refusal = RescanRefusal.TooLittleOverlap),
        )
        // Every refusal, and a re-scan with no depth points, has a reason of its own.
        val reasons = (RescanRefusal.entries + null).map(ScanCopy::rescanRefusedDetail)
        assertEquals(reasons.size, reasons.toSet().size)
        assertTrue(ScanCopy.rescanRefusedDetail(null).contains("no depth points"))
    }

    @Test
    fun `the logcat line names the stage, the cause and the figures`() {
        val line = ScanSaveFailure.logLine(
            stage = "write",
            failure = ScanSaveFailure.Memory,
            error = OutOfMemoryError("Failed to allocate a 157286416 byte allocation"),
            logBytes = 75L * 1024 * 1024,
            mediaBytes = 40L * 1024 * 1024,
            heapUsedBytes = 250L * 1024 * 1024,
            heapMaxBytes = 256L * 1024 * 1024,
            freeStorageBytes = 3L * 1024 * 1024 * 1024,
        )
        assertEquals(
            "3D Scan save failed at write: Memory (java.lang.OutOfMemoryError: Failed to allocate a " +
                "157286416 byte allocation); log 75.0 MB, media 40.0 MB; heap 250.0 MB of 256.0 MB; " +
                "storage free 3072.0 MB",
            line,
        )
    }

    /** A synthetic outdoor scan: poses at 30 Hz, coloured feature points at 5 Hz, planes at 2 Hz. */
    private fun journal(seconds: Int, pointsPerFrame: Int): List<ArDebugEvent> {
        val events = ArrayList<ArDebugEvent>()
        val start = 1_000_000_000L
        for (f in 0 until seconds * FPS) {
            val t = start + f * FRAME_NANOS
            val a = f * 0.002f
            events += ArDebugEvent.CameraPose(t, DebugPose(4f * cos(a), 1.4f, 4f * sin(a), 0f, sin(a / 2), 0f, cos(a / 2)))
            if (f % 6 == 0) {
                val xyz = FloatArray(pointsPerFrame * 3) { (it * 0.37f + f * 0.01f) % 20f - 10f }
                val colors = IntArray(pointsPerFrame) { 0xFF000000.toInt() or (it * 7919 and 0xFFFFFF) }
                val confidence = FloatArray(pointsPerFrame) { 0.5f + (it % 50) / 100f }
                events += ArDebugEvent.Points(t, xyz, confidence, colors)
            }
            if (f % 15 == 0) {
                val polygon = FloatArray(24 * 3) { i -> if (i % 3 == 1) 0f else (i * 0.13f) % 15f }
                events += ArDebugEvent.Plane(t, 1, DebugPlaneKind.Floor, polygon)
            }
            if (f % FPS == 0) events += ArDebugEvent.Image(t, "frames/${f / FPS}.jpg")
        }
        return events
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
        )
    }

    private companion object {
        const val MB = 1024L * 1024L
        const val FPS = 30
        const val FRAME_NANOS = 33_333_333L
    }
}
