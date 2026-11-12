package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.ArDebugTrace
import io.github.sceneview.demo.demos.internal.ReplayManifest
import io.github.sceneview.demo.demos.internal.RerunReplayAssets
import io.github.sceneview.demo.demos.internal.RerunCapturePack
import io.github.sceneview.demo.demos.internal.RerunScanFile
import io.github.sceneview.demo.demos.internal.RerunExampleAssets
import io.github.sceneview.demo.demos.internal.model.SurfelSplats
import io.github.sceneview.demo.demos.internal.of
import io.github.sceneview.demo.demos.internal.parseArDebugLog
import io.github.sceneview.demo.demos.internal.ScanResultFraming
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The example is the bundled room replay; dense result checks use a scan opened from a file. */
class ScanExampleTest {
    private val dir = listOf(
        File("src/main/assets"),
        File("samples/android-demo/src/main/assets"),
    ).first { File(it, RerunExampleAssets.DIR).isDirectory }

    // Keep the dense garden as a real scan fixture, independently of the landing's example.
    private val fileCapture = run {
        val file = File.createTempFile("scan-result-", ".svscan")
        try {
            file.writeBytes(RerunScanFile.write(RerunCapturePack(
                File(dir, RerunExampleAssets.MANIFEST).readBytes(),
                File(dir, RerunExampleAssets.LOG).readBytes(),
                File(dir, RerunExampleAssets.MEDIA).readBytes(),
            )))
            requireNotNull(RerunScanFile.read(file.readBytes()))
        } finally {
            file.delete()
        }
    }
    private val manifest = requireNotNull(ReplayManifest.parse(String(fileCapture.manifest)))
    private val archive = fileCapture.media
    private val trace = ArDebugTrace.of(parseArDebugLog(fileCapture.log))
    private val dense = ReplayDenseLayer.of(manifest, archive)
    private val media = RerunReplayMedia(trace, manifest, emptyMap(), emptyMap(), archive, dense = dense)

    @Test
    fun `the bundled room is a replay with photos and planes rather than a model result`() {
        val roomManifest = requireNotNull(ReplayManifest.parse(File(dir, RerunReplayAssets.MANIFEST).readText()))
        val roomArchive = File(dir, RerunReplayAssets.MEDIA).readBytes()
        val roomTrace = ArDebugTrace.of(File(dir, RerunReplayAssets.LOG).useLines { parseArDebugLog(it) })
        val room = RerunReplayMedia(roomTrace, roomManifest, emptyMap(), emptyMap(), roomArchive)
        assertNull(RerunModelSource.of(pack = null, media = room))
        assertTrue("the room has no video strip", roomTrace.imageCount > 0)
        assertTrue("the room has no planes to measure", roomTrace.lastPlanes().isNotEmpty())
        assertNotNull("the room has no floor", roomManifest.floorY)
        for (i in 0 until roomTrace.imageCount) {
            val span = requireNotNull(roomManifest.media[roomTrace.imagePath(i)])
            assertTrue(span.offset + span.length <= roomArchive.size)
        }
    }

    @Test
    fun `the retained garden fixture has the normals a result is built from`() {
        val cloud = assertNotNull("the retained garden has no dense cloud", dense).let { dense!!.cloud }
        assertTrue("only ${cloud.count} surfels: not a place one recognises", cloud.count >= MIN_SURFELS)
        assertNotNull("surfels without normals build no model", cloud.normals)
        val colours = cloud.colors.toSet().size
        assertTrue("$colours colours: a place drawn in one tone", colours >= MIN_COLOURS)
    }

    @Test
    fun `a real scan opened from a file has a surfel result`() {
        val source = RerunModelSource.of(pack = fileCapture, media = media)
        assertTrue("no result to open: $source", source is RerunModelSource.Surfels)
    }

    @Test
    fun `the result offers its Photo layer in every build, with splats to draw`() {
        val source = RerunModelSource.of(pack = fileCapture, media = media)
        assertTrue(photoOffered(source, dense?.cloud))
        val cloud = requireNotNull(photoCloudOf(source, dense?.cloud))
        assertTrue(SurfelSplats.of(cloud).count > 0)
        // Reopened from its kept model, with no source to build from, the scan's surfels still offer it.
        assertTrue(photoOffered(source = null, surfels = dense?.cloud))
    }

    @Test
    fun `the result opens on the scan's own surfels, framed as its model will be`() {
        val source = RerunModelSource.of(pack = fileCapture, media = media)
        val cloud = requireNotNull(photoFirstCloud(source, dense?.cloud)) { "the result opens on a bare stage" }
        val frame = requireNotNull(ScanResultFraming.of(cloud))
        // A box a few metres wide, standing on its floor: the garden, not a speck nor the horizon.
        val width = frame.local[3] - frame.local[0]
        val depth = frame.local[5] - frame.local[2]
        assertTrue("framed ${width} m by ${depth} m", width in 2f..40f && depth in 2f..40f)
        // The QA room is ray-cast first: it opens on its model, as before.
        assertNull(photoFirstCloud(RerunModelSource.Synthetic(), surfels = null))
    }

    @Test
    fun `the Photo layer grows in the order the scan found it, most of it in the first second`() {
        assertEquals(0f, grownShare(0f), 0f)
        assertEquals(1f, grownShare(1f), 0f)
        assertEquals(1f, grownShare(2f), 0f)
        assertTrue("a third of the way in", grownShare(1_000f / GROW_MS) > 0.75f)
        var last = 0f
        for (step in 1..GROW_STEPS) {
            val share = grownShare(step.toFloat() / GROW_STEPS)
            assertTrue(share >= last)
            last = share
        }
        // Half grown: the first half of the splats, less those their face hides.
        val faces = booleanArrayOf(true, false, true, true, true, true)
        assertArrayEquals(booleanArrayOf(true, false, true, false, false, false), grownFilter(faces, 6, 0.5f))
        assertArrayEquals(booleanArrayOf(true, true, false, false), grownFilter(null, 4, 0.5f))
        // Whole: the faces' own filter, not a copy — the scene compares it by identity.
        assertSame(faces, grownFilter(faces, 6, 1f))
        assertNull(grownFilter(null, 4, 1f))
    }

    @Test
    fun `the finished model takes the Photo preview's place unless a layer was chosen`() {
        val opened = ResultLayers(offered = true, photoFirst = true)
        assertTrue(opened.photoPicked)
        assertEquals(0f, opened.grown, 0f)
        assertFalse("the model is not there yet", opened.handsOverToModel(modelReady = false, busy = false))
        assertFalse("a film is being taken of it", opened.handsOverToModel(modelReady = true, busy = true))
        assertTrue(opened.handsOverToModel(modelReady = true, busy = false))
        opened.userPicked = true
        assertFalse("the user chose Photo", opened.handsOverToModel(modelReady = true, busy = false))
        // A result that opens on its model has nothing to hand over, and is whole from the start.
        val onModel = ResultLayers(offered = true)
        assertFalse(onModel.handsOverToModel(modelReady = true, busy = false))
        assertEquals(1f, onModel.grown, 0f)
    }

    @Test
    fun `every photo the retained garden timeline names is in its archive`() {
        assertTrue("an example without photos has nothing to texture its model", trace.imageCount > 0)
        val missing = (0 until trace.imageCount).map { trace.imagePath(it) }.filterNot { it in manifest.media }
        assertEquals(emptyList<String>(), missing)
        manifest.media.values.forEach { span -> assertTrue(span.offset + span.length <= archive.size) }
    }

    @Test
    fun `the retained garden stays light enough to ship in the app`() {
        val bytes = listOf(RerunExampleAssets.MANIFEST, RerunExampleAssets.LOG, RerunExampleAssets.MEDIA)
            .sumOf { File(dir, it).length() }
        assertTrue("$bytes bytes", bytes <= MAX_BYTES)
    }

    private companion object {
        /** The dense garden has far more surfels than the sparse room replay or a small object. */
        const val MIN_SURFELS = 100_000

        /** The camera's own colours, not a fallback tone. */
        const val MIN_COLOURS = 1_000

        const val MAX_BYTES = 8L * 1024 * 1024
    }
}
