package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.RAW_FIRST_TRIANGLES
import io.github.sceneview.demo.demos.RerunModelSource
import io.github.sceneview.demo.demos.buildScanModel
import io.github.sceneview.demo.demos.internal.model.Box
import io.github.sceneview.demo.demos.internal.model.CleanScenes
import io.github.sceneview.demo.demos.internal.model.ModelPhoto
import io.github.sceneview.demo.demos.internal.model.ModelPhotos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A model the result screen falls back to — the points when meshing fails, the raw surface when
 * the finish fails — is shown but never kept as the scan's model: the next open tries again,
 * instead of reading the fallback back for good. A big scan shows its raw surface while it
 * finishes, and only the finished model is kept. Robolectric for the fallbacks' `Log` lines.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RerunScanFallbackTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `the points shown when meshing fails are not kept as the scan's model`() {
        val model = assertNotNull(buildScanModel(RerunModelSource.Synthetic(meshFails = true)) {})
        assertEquals(RerunScanModel.Kind.Points, model.kind)

        val store = RerunSessionStore(folder.newFolder("sessions"))
        val saved = store.save(bundled(), "Kitchen", RerunSessionSource.Recorded, 1_000_000L)
        store.saveModel(saved.id, model)
        assertNull("a fallback read back for good", store.model(saved.id))

        val withModel = store.save(bundled(), "Hall", RerunSessionSource.Recorded, 2_000_000L, model = model)
        assertNull("a fallback read back for good", store.model(withModel.id))
    }

    @Test
    fun `the raw surface shown when the finish fails is not kept as the scan's model`() {
        val box = Box(0f, 0f, 0f, 1.2f, 0.9f, 1.0f)
        val tsdf = CleanScenes.fuse(Box(-0.2f, -0.2f, -0.2f, 1.4f, 1.1f, 1.2f), NOISE_M, NOISE_M) { x, y, z ->
            box.sdf(x, y, z)
        }
        // A key photo that no longer decodes: the finish fails, and the raw surface is shown.
        val unreadable = ModelPhoto(RerunSyntheticRoom.lookPose(Vec3(0.6f, 0.45f, 2.6f), 0f, 0f)) {
            error("unreadable photo")
        }
        val source = RerunModelSource.Live(tsdf, cloud = null, photos = ModelPhotos(LENS, listOf(unreadable)))
        val model = assertNotNull(buildScanModel(source) {})
        assertEquals(RerunScanModel.Kind.Mesh, model.kind)

        val store = RerunSessionStore(folder.newFolder("sessions"))
        val saved = store.save(bundled(), "Kitchen", RerunSessionSource.Recorded, 1_000_000L)
        store.saveModel(saved.id, model)
        assertNull("a raw surface read back as the finished model", store.model(saved.id))
    }

    @Test
    fun `a big scan shows its raw surface first, then the finished model in the same place`() {
        val shown = ArrayList<RerunScanModel>()
        var progressAtRaw = -1f
        var progress = 0f
        val model = assertNotNull(
            buildScanModel(RerunModelSource.Synthetic(), onRaw = { shown += it; progressAtRaw = progress }) { progress = it },
        )
        val raw = shown.single()
        assertFalse("the raw surface kept as the model", raw.finished)
        assertTrue("${raw.triangles} raw triangles", raw.triangles >= RAW_FIRST_TRIANGLES)
        assertTrue("the raw surface came at $progressAtRaw, after the finish", progressAtRaw < MESHED_BY)
        assertTrue(model.finished)
        assertTrue("${model.triangles} of ${raw.triangles}", model.triangles < raw.triangles)
        for (axis in 0 until 6) {
            assertEquals("bounds[$axis]: the finished model moved", raw.bounds[axis], model.bounds[axis], SAME_PLACE_M)
        }
    }

    @Test
    fun `a big scan whose finish runs out of memory keeps its raw surface, read back as it is`() {
        val shown = ArrayList<RerunScanModel>()
        var outOfMemory = false
        val model = assertNotNull(
            buildScanModel(RerunModelSource.Synthetic(), onRaw = { shown += it }) {
                // The finish's first step past the raw surface runs out of a phone's heap.
                if (shown.isNotEmpty() && !outOfMemory) {
                    outOfMemory = true
                    throw OutOfMemoryError("finish")
                }
            },
        )
        val raw = shown.single()
        assertTrue("the finish never started", outOfMemory)
        assertEquals("the raw surface shown gave way to the ${model.kind}", RerunScanModel.Kind.Mesh, model.kind)
        assertTrue("expected the raw surface shown, got another model", raw.glb.contentEquals(model.glb))
        assertFalse("a raw surface passed off as finished", model.finished)

        // Kept, so the next open shows it at once instead of failing the same finish again.
        val store = RerunSessionStore(folder.newFolder("sessions"))
        val saved = store.save(bundled(), "Kitchen", RerunSessionSource.Recorded, 1_000_000L)
        store.saveModel(saved.id, model)
        val reopened = assertNotNull(store.model(saved.id))
        assertFalse("a raw surface read back as finished", reopened.finished)
        assertTrue("expected the raw surface read back", raw.glb.contentEquals(reopened.glb))

        // A bigger heap than the one it ran out of finishes it again.
        val heap = assertNotNull(model.outOfMemoryInHeap)
        assertNotNull(RerunScanModel.parse(model.infoJson(), model.glb, heap))
        assertNull("a bigger heap left on the raw surface", RerunScanModel.parse(model.infoJson(), model.glb, heap + 1))
    }

    @Test
    fun `a raw surface finished again hands the finish its own glb, never a second one`() {
        // The raw surface a first finish ran out of memory on, kept, shown at once on the next open.
        var ranOut = false
        val kept = assertNotNull(
            buildScanModel(RerunModelSource.Synthetic(), onRaw = {}) {
                if (it > FINISHING_FROM && !ranOut) {
                    ranOut = true
                    throw OutOfMemoryError("finish")
                }
            },
        )
        assertTrue("kept to be finished again", kept.retriesFinish)

        // Finished again behind it, out of memory once more.
        var heard = 0
        var ranOutAgain = false
        val again = assertNotNull(
            buildScanModel(RerunModelSource.Synthetic(), onRaw = { heard++ }, shown = kept.glb) {
                if (it > FINISHING_FROM && !ranOutAgain) {
                    ranOutAgain = true
                    throw OutOfMemoryError("finish again")
                }
            },
        )
        assertTrue("the finish never started", ranOutAgain)
        assertEquals("a second raw surface shown", 0, heard)
        assertSame("a second raw .glb written beside the one shown", kept.glb, again.glb)
        val next = kept.finishedAgain(again)
        assertSame("the surface on screen gave way", kept.glb, next.glb)
        assertEquals(2, next.outOfMemoryAttempts)
        assertEquals(again.outOfMemoryInHeap, next.outOfMemoryInHeap)
    }

    @Test
    fun `a small scan goes straight to its finished model`() {
        val box = Box(0f, 0f, 0f, 1.2f, 0.9f, 1.0f)
        val tsdf = CleanScenes.fuse(Box(-0.2f, -0.2f, -0.2f, 1.4f, 1.1f, 1.2f), NOISE_M, NOISE_M) { x, y, z ->
            box.sdf(x, y, z)
        }
        var shown = 0
        val model = assertNotNull(buildScanModel(RerunModelSource.Live(tsdf, cloud = null), onRaw = { shown++ }) {})
        assertEquals("a raw surface shown for ${model.triangles} triangles", 0, shown)
        assertTrue(model.finished)
    }

    private fun <T> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }

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
        const val NOISE_M = 0.002f

        /** The raw surface is ready once meshed, well before the finish's share of the progress. */
        const val MESHED_BY = 0.5f

        /** Past the meshing: the finish's own progress, as [buildScanModel] reports it. */
        const val FINISHING_FROM = 0.41f

        /** The finished model's box against the raw one's: the cleaning trims specks, no more. */
        const val SAME_PLACE_M = 0.1f
        val LENS = ReplayLens(0.35f, 0.6f)
    }
}
