package io.github.sceneview.render

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Camera
import com.google.android.filament.EntityManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Skybox
import com.google.android.filament.Texture
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.node.PlaneNode
import io.github.sceneview.render.RenderTestHarness.Companion.colorsMatch
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/**
 * Loads the compiled Scene Semantics overlay material and draws it.
 *
 * `semantics_overlay.filamat` is a compiled blob: its source can be right and the blob stale, or
 * built for another Filament. Nothing in the repository creates an instance of it (the demo
 * paints its overlay as a bitmap), so until this class no test loaded it at all, and the fix
 * that put its palette back in ARCore's order (#4036) was only ever read, never run.
 *
 * - [material_loadsWithItsTwoParameters] runs everywhere: the blob parses on the bundled Filament
 *   and still exposes the two parameters `MaterialLoader.createSemanticsOverlayInstance` sets.
 * - [eachByte_isPaintedAsItsClass] needs pixel readback ([RenderTestCapabilities]): one quad per
 *   byte, over a red background, for the four bytes that tell the palette's order — `0`
 *   (`UNLABELED`, transparent), `1` (`SKY`), `11` (`WATER`) and `12` (past the last class,
 *   transparent). One place off, as the table was, `0` is tinted sky blue, `1` comes out
 *   terracotta and `11` is left transparent: all three fail here.
 */
@RunWith(AndroidJUnit4::class)
class SemanticsOverlayRenderTest {

    companion object {
        private const val SIZE = 64

        private lateinit var harness: RenderTestHarness
        private lateinit var materialLoader: MaterialLoader

        /** The camera of the one test that renders; it lives as long as the view that uses it. */
        private var cameraEntity: Int? = null

        @JvmStatic
        @BeforeClass
        fun setupClass() {
            harness = RenderTestHarness(width = SIZE, height = SIZE)
            harness.runOnMain {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                materialLoader = MaterialLoader(harness.engine, context)
            }
        }

        @JvmStatic
        @AfterClass
        fun teardownClass() {
            harness.runOnMain {
                materialLoader.destroy()
                cameraEntity?.let {
                    harness.engine.destroyCameraComponent(it)
                    EntityManager.get().destroy(it)
                }
            }
            harness.destroy()
        }
    }

    /** A 1 × 1 `R8` texture holding [label], the byte ARCore writes for one pixel. */
    private fun labelTexture(label: Int): Texture {
        val texture = Texture.Builder()
            .width(1)
            .height(1)
            .levels(1)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.R8)
            .build(harness.engine)
        val pixel = ByteBuffer.allocateDirect(1).put(0, label.toByte())
        texture.setImage(
            harness.engine,
            0,
            Texture.PixelBufferDescriptor(pixel, Texture.Format.R, Texture.Type.UBYTE)
        )
        return texture
    }

    /**
     * Points the view at the origin from 5 m, over a red background: red, so a pixel left
     * transparent cannot be mistaken for UNLABELED's own black. Main thread.
     */
    private fun lookAtTheOriginOverRed() {
        val engine = harness.engine
        val entity = cameraEntity ?: EntityManager.get().create().also { cameraEntity = it }
        harness.view.camera = (engine.getCameraComponent(entity) ?: engine.createCamera(entity)).apply {
            setProjection(45.0, 1.0, 0.1, 100.0, Camera.Fov.VERTICAL)
            lookAt(0.0, 0.0, 5.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
            setExposure(1.0f)
        }
        harness.scene.skybox = Skybox.Builder().color(1f, 0f, 0f, 1f).build(engine)
    }

    /** A quad wider than the view at the camera's distance, facing it: it fills the frame. */
    private fun fullFrameQuad(instance: MaterialInstance) = PlaneNode(
        engine = harness.engine,
        size = Size(6f, 6f),
        center = Position(0f, 0f, 0f),
        normal = Direction(0f, 0f, 1f),
        materialInstances = listOf(instance)
    )

    @Test
    fun material_loadsWithItsTwoParameters() {
        harness.runOnMain {
            val texture = labelTexture(1)
            val instance = materialLoader.createSemanticsOverlayInstance(texture, opacity = 1f)

            assertEquals("semanticsOverlay", instance.material.name)
            assertTrue(instance.material.hasParameter("semanticTexture"))
            assertTrue(instance.material.hasParameter("opacity"))

            materialLoader.destroyMaterialInstance(instance)
            harness.engine.destroyTexture(texture)
        }
    }

    @Test
    fun eachByte_isPaintedAsItsClass() {
        RenderTestCapabilities.assumeGpuReadbackAvailable()
        harness.resetScene()
        val centre = SIZE / 2

        harness.runOnMain {
            lookAtTheOriginOverRed()
            harness.renderFrames(5)
        }
        // Read back from the test's own thread, never inside runOnMain: the callback that hands
        // the pixels over is delivered on the main thread, which must be free to take it.
        val background = harness.capturePixels().getPixel(centre, centre)

        val painted = mutableMapOf<Int, Int>()
        for (label in intArrayOf(0, 1, 11, 12)) {
            var texture: Texture? = null
            var instance: MaterialInstance? = null
            var quad: PlaneNode? = null
            harness.runOnMain {
                val labels = labelTexture(label).also { texture = it }
                val overlay = materialLoader.createSemanticsOverlayInstance(labels, opacity = 1f)
                    .also { instance = it }
                val node = fullFrameQuad(overlay).also { quad = it }
                harness.scene.addEntity(node.entity)
                harness.renderFrames(5)
            }
            painted[label] = harness.capturePixels().getPixel(centre, centre)
            harness.runOnMain {
                quad?.let {
                    harness.scene.remove(it.entity)
                    it.destroy()
                }
                instance?.let { materialLoader.destroyMaterialInstance(it) }
                texture?.let { harness.engine.destroyTexture(it) }
            }
        }

        fun describe(pixel: Int) = "(${Color.red(pixel)}, ${Color.green(pixel)}, ${Color.blue(pixel)})"
        val said = "background ${describe(background)}, " +
            painted.entries.joinToString { "byte ${it.key} ${describe(it.value)}" }

        assertTrue(
            "UNLABELED (0) lets the background through: $said",
            colorsMatch(painted.getValue(0), background, tolerance = 12)
        )
        assertTrue(
            "A byte past the last class (12) lets the background through: $said",
            colorsMatch(painted.getValue(12), background, tolerance = 12)
        )

        val sky = painted.getValue(1)
        assertFalse("SKY (1) is painted: $said", colorsMatch(sky, background, tolerance = 40))
        assertTrue(
            "SKY (1) is light blue — blue over green over a red that is still there: $said",
            Color.blue(sky) > Color.green(sky) && Color.green(sky) > Color.red(sky) && Color.red(sky) >= 90
        )

        val water = painted.getValue(11)
        assertFalse("WATER (11) is painted: $said", colorsMatch(water, background, tolerance = 40))
        assertTrue(
            "WATER (11) is cyan — green and blue with no red: $said",
            Color.red(water) <= 40 && Color.green(water) >= 150 && Color.blue(water) >= 150
        )
    }

    @Test
    fun twoClassesSideBySide_showOnlyThoseTwo() {
        RenderTestCapabilities.assumeGpuReadbackAvailable()
        harness.resetScene()

        var texture: Texture? = null
        var instance: MaterialInstance? = null
        var quad: PlaneNode? = null
        harness.runOnMain {
            lookAtTheOriginOverRed()
            // Two texels, SKY beside WATER: the classes furthest apart in ARCore's numbering.
            val labels = Texture.Builder()
                .width(2)
                .height(1)
                .levels(1)
                .sampler(Texture.Sampler.SAMPLER_2D)
                .format(Texture.InternalFormat.R8)
                .build(harness.engine)
                .also { texture = it }
            val bytes = ByteBuffer.allocateDirect(2).put(0, 1.toByte()).put(1, 11.toByte())
            labels.setImage(
                harness.engine,
                0,
                Texture.PixelBufferDescriptor(bytes, Texture.Format.R, Texture.Type.UBYTE)
            )
            val overlay = materialLoader.createSemanticsOverlayInstance(labels, opacity = 1f)
                .also { instance = it }
            val node = fullFrameQuad(overlay).also { quad = it }
            harness.scene.addEntity(node.entity)
            harness.renderFrames(5)
        }
        val frame = harness.capturePixels()
        harness.runOnMain {
            quad?.let {
                harness.scene.remove(it.entity)
                it.destroy()
            }
            instance?.let { materialLoader.destroyMaterialInstance(it) }
            texture?.let { harness.engine.destroyTexture(it) }
        }

        val row = (0 until SIZE).map { frame.getPixel(it, SIZE / 2) }
        val oneSide = row.first()
        val otherSide = row.last()
        assertFalse("The two ends of the row are two classes", colorsMatch(oneSide, otherSide, tolerance = 40))
        // A texel is a class number. Blended with its neighbour's it is another class's number:
        // filtered linearly, the nine classes between SKY and WATER were each painted in a band
        // across the boundary. One pixel either side of it is left to the view's anti-aliasing.
        val neither = row.count { !colorsMatch(it, oneSide, tolerance = 12) && !colorsMatch(it, otherSide, tolerance = 12) }
        assertTrue(
            "$neither of $SIZE pixels across SKY | WATER are in neither class's colour: " +
                row.joinToString(" ") { "%06X".format(it and 0xFFFFFF) },
            neither <= 2
        )
    }
}
