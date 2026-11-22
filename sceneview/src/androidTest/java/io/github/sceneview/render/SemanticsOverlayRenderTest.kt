package io.github.sceneview.render

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.EntityManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Skybox
import com.google.android.filament.Texture
import com.google.android.filament.ToneMapper
import io.github.sceneview.filmicViews
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.matchToneMapInverse
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
import kotlin.math.abs

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
 * - `eachClass_isDrawnInItsLegendColour_*` need readback too: each of the 11 classes, drawn full
 *   frame in a [io.github.sceneview.createView] view (PBR Neutral) and in a Filmic view like
 *   [io.github.sceneview.createARView]'s, reads back as the colour the demo's legend paints for
 *   it. The palette used to be taken as linear: SKY `#66B0F5` came out (157, 204, 237) (#5102).
 */
@RunWith(AndroidJUnit4::class)
class SemanticsOverlayRenderTest {

    companion object {
        private const val SIZE = 64

        /**
         * The colours the demo's legend paints for classes 1..11 (`SemanticsOverlay.PALETTE_ARGB`,
         * held equal to the material's table by `SemanticsOverlayTest`, #5101), sRGB bytes.
         */
        private val LEGEND_RGB = intArrayOf(
            0x66B0F5, 0xD95F57, 0x4CB050, 0x757575, 0xBDBD8C, 0xCC9E6B,
            0x9E75BD, 0xFFC207, 0x2196F2, 0xF5428C, 0x00BDD4,
        )

        /** 8-bit steps per channel left to the grading LUT, the colour buffer's precision and dithering. */
        private const val LEGEND_TOLERANCE = 6

        /**
         * A channel darker than [DARK_CHANNEL] may land up to [DARK_TOLERANCE] steps above its legend.
         * Under Filmic, VEHICLE's and WATER's red would need a negative scene value, which the colour
         * buffer cannot hold; and near black the sRGB curve is steep enough that 16 steps are 0.5 %
         * of white in linear light.
         */
        private const val DARK_CHANNEL = 0x30
        private const val DARK_TOLERANCE = 16

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

    /** One row of an `R8` texture holding [labels], the bytes ARCore writes, one per pixel. */
    private fun labelTexture(vararg labels: Int): Texture {
        val texture = Texture.Builder()
            .width(labels.size)
            .height(1)
            .levels(1)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.R8)
            .build(harness.engine)
        val row = ByteBuffer.allocateDirect(labels.size)
        labels.forEachIndexed { i, label -> row.put(i, label.toByte()) }
        texture.setImage(
            harness.engine,
            0,
            Texture.PixelBufferDescriptor(row, Texture.Format.R, Texture.Type.UBYTE)
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

    /**
     * The frame drawn by the overlay material for one row of [labels], on a quad that fills it.
     *
     * The quad, the instance and the texture are released whether or not the frame came back.
     * Left in the scene by a readback that timed out, they are still in use when the class's
     * teardown destroys the material loader: that second failure would be the one reported.
     */
    private fun frameOf(vararg labels: Int): Bitmap {
        var texture: Texture? = null
        var instance: MaterialInstance? = null
        var quad: PlaneNode? = null
        try {
            harness.runOnMain {
                val row = labelTexture(*labels).also { texture = it }
                val overlay = materialLoader.createSemanticsOverlayInstance(row, opacity = 1f)
                    .also { instance = it }
                // What a Scene or ARScene does when the node carrying it is added (#5102).
                overlay.matchToneMapInverse(harness.view)
                val node = fullFrameQuad(overlay).also { quad = it }
                harness.scene.addEntity(node.entity)
                harness.renderFrames(5)
            }
            return harness.capturePixels()
        } finally {
            harness.runOnMain {
                quad?.let {
                    harness.scene.remove(it.entity)
                    it.destroy()
                }
                instance?.let { materialLoader.destroyMaterialInstance(it) }
                texture?.let { harness.engine.destroyTexture(it) }
            }
        }
    }

    @Test
    fun material_loadsWithItsTwoParameters() {
        harness.runOnMain {
            val texture = labelTexture(1)
            var instance: MaterialInstance? = null
            try {
                val overlay = materialLoader.createSemanticsOverlayInstance(texture, opacity = 1f)
                    .also { instance = it }

                assertEquals("semanticsOverlay", overlay.material.name)
                assertTrue(overlay.material.hasParameter("semanticTexture"))
                assertTrue(overlay.material.hasParameter("opacity"))
            } finally {
                instance?.let { materialLoader.destroyMaterialInstance(it) }
                harness.engine.destroyTexture(texture)
            }
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
        // The background by itself, before any quad is drawn over it.
        val background = harness.capturePixels().getPixel(centre, centre)

        val painted = intArrayOf(0, 1, 11, 12).associateWith { label ->
            frameOf(label).getPixel(centre, centre)
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

    /** Each class drawn full frame, read at the centre, against the colour its legend paints. */
    private fun assertEachClassShowsItsLegend(view: String) {
        harness.resetScene()
        harness.runOnMain { lookAtTheOriginOverRed() }
        val centre = SIZE / 2
        fun channels(rgb: Int) = IntArray(3) { (rgb shr (16 - 8 * it)) and 0xFF }
        val drawn = (1..11).associateWith { label -> frameOf(label).getPixel(centre, centre) and 0xFFFFFF }
        val said = drawn.entries.joinToString("; ") { (label, rgb) ->
            val off = channels(rgb).zip(channels(LEGEND_RGB[label - 1])).maxOf { (a, b) -> abs(a - b) }
            "class $label legend #%06X drawn #%06X (%d off)".format(LEGEND_RGB[label - 1], rgb, off)
        }
        Log.i("SemanticsOverlayRenderTest", "$view: $said")
        drawn.forEach { (label, rgb) ->
            val legend = channels(LEGEND_RGB[label - 1])
            channels(rgb).forEachIndexed { c, value ->
                val tolerance = if (legend[c] < DARK_CHANNEL) DARK_TOLERANCE else LEGEND_TOLERANCE
                assertTrue(
                    "$view, class $label channel $c: ${abs(value - legend[c])} steps off (tolerance $tolerance): $said",
                    abs(value - legend[c]) <= tolerance
                )
            }
        }
    }

    @Test
    fun eachClass_isDrawnInItsLegendColour_inA3dView() {
        RenderTestCapabilities.assumeGpuReadbackAvailable()
        // createView's grading: PBR Neutral.
        assertEachClassShowsItsLegend("PBR Neutral (createView)")
    }

    @Test
    fun eachClass_isDrawnInItsLegendColour_inAnArView() {
        RenderTestCapabilities.assumeGpuReadbackAvailable()
        // createARView's grading: Filmic, and the view listed among the Filmic ones.
        var filmic: ColorGrading? = null
        var neutral: ColorGrading? = null
        harness.runOnMain {
            neutral = harness.view.colorGrading
            filmic = ColorGrading.Builder().toneMapper(ToneMapper.Filmic()).build(harness.engine)
            harness.view.colorGrading = filmic
            filmicViews += harness.view
        }
        try {
            assertEachClassShowsItsLegend("Filmic (createARView)")
        } finally {
            harness.runOnMain {
                filmicViews -= harness.view
                harness.view.colorGrading = neutral
                // A frame graded with the view's own grading again, and the GPU done with the
                // last Filmic one, before the Filmic grading goes.
                harness.renderFrames(2)
                harness.engine.flushAndWait()
                filmic?.let { harness.engine.destroyColorGrading(it) }
            }
        }
    }

    @Test
    fun twoClassesSideBySide_showOnlyThoseTwo() {
        RenderTestCapabilities.assumeGpuReadbackAvailable()
        harness.resetScene()

        harness.runOnMain { lookAtTheOriginOverRed() }
        // Two texels, SKY beside WATER: the classes furthest apart in ARCore's numbering.
        val frame = frameOf(1, 11)

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
