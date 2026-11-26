package io.github.sceneview.geometries

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import io.github.sceneview.createEglContext
import io.github.sceneview.createEngine
import io.github.sceneview.destroyGeometry
import io.github.sceneview.math.Position
import io.github.sceneview.safeDestroy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #4489 against a real Filament engine: a tube with reserved capacity grows inside the buffers
 * it was built with, and draws only the live part of them.
 */
@RunWith(AndroidJUnit4::class)
class TubeCapacityGrowthTest {

    private companion object {
        const val CAPACITY = 16
        const val RADIAL_SEGMENTS = 8
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private lateinit var engine: Engine

    @Before
    fun setup() {
        instrumentation.runOnMainSync {
            Gltfio.init(); Filament.init(); Utils.init()
            engine = createEngine(createEglContext())
        }
    }

    @After
    fun teardown() {
        if (!::engine.isInitialized) return
        instrumentation.runOnMainSync { engine.safeDestroy() }
    }

    private fun pathOf(count: Int) = List(count) { Position(x = it * 0.1f) }

    @Test
    fun growingWithinCapacity_keepsTheBuffersAndDrawsOnlyTheLiveRange() {
        instrumentation.runOnMainSync {
            val tube = Tube.Builder()
                .points(pathOf(2))
                .radialSegments(RADIAL_SEGMENTS)
                .capStyle(Tube.CapStyle.Round)
                .capacity(CAPACITY)
                .build(engine)
            val vertexBuffer = tube.vertexBuffer
            val indexBuffer = tube.indexBuffer
            val allocatedIndices = tube.primitivesIndices.single().size
            assertEquals(CAPACITY, tube.capacity)
            assertEquals(
                listOf(0 until Tube.liveIndexCount(2, RADIAL_SEGMENTS, false, Tube.CapStyle.Round)),
                tube.primitivesOffsets,
            )

            tube.update(engine, points = pathOf(3))

            assertSame("Growing within capacity must not reallocate", vertexBuffer, tube.vertexBuffer)
            assertSame(indexBuffer, tube.indexBuffer)
            assertEquals(CAPACITY, tube.capacity)
            assertEquals(allocatedIndices, tube.primitivesIndices.single().size)
            val live = Tube.liveIndexCount(3, RADIAL_SEGMENTS, false, Tube.CapStyle.Round)
            assertEquals(
                "Only the 3 live points are drawn, not the padding rings",
                listOf(0 until live),
                tube.primitivesOffsets,
            )
            assertTrue("The live range is a strict prefix", live < allocatedIndices)

            engine.destroyGeometry(tube)
        }
    }
}
