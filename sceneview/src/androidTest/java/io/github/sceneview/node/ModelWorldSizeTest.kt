package io.github.sceneview.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import io.github.sceneview.createEglContext
import io.github.sceneview.createEngine
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.safeDestroy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Engine-backed check that [ModelNode.worldSize] and [ModelNode.worldBoundingBox] report real
 * metres on a real glTF: `unit_cube_1m.glb` is a 1 m cube authored standing on its origin
 * (accessor min `(-0.5, 0, -0.5)`, max `(0.5, 1, 0.5)`).
 */
@RunWith(AndroidJUnit4::class)
class ModelWorldSizeTest {

    private lateinit var engine: Engine
    private lateinit var loader: ModelLoader

    @Before
    fun setup() = onMain {
        Gltfio.init(); Filament.init(); Utils.init()
        engine = createEngine(createEglContext())
        loader = ModelLoader(engine, InstrumentationRegistry.getInstrumentation().context)
    }

    @After
    fun teardown() = onMain {
        loader.destroy()
        engine.safeDestroy()
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun cube(scaleToUnits: Float? = null) =
        ModelNode(loader.createModelInstance("unit_cube_1m.glb"), scaleToUnits = scaleToUnits)

    @Test
    fun oneMetreAssetIsOneMetre() = onMain {
        val node = cube()
        assertEquals(1f, node.worldSize.x, EPS)
        assertEquals(1f, node.worldSize.y, EPS)
        assertEquals(1f, node.worldSize.z, EPS)
        val box = node.worldBoundingBox
        assertEquals("rests on its origin", 0f, box.center[1] - box.halfExtent[1], EPS)
        assertEquals(0.5f, box.halfExtent[0], EPS)
    }

    @Test
    fun scaleToUnitsAndParentScaleCount() = onMain {
        val fitted = cube(scaleToUnits = 0.3f)
        assertEquals(0.3f, fitted.worldSize.y, EPS)
        val parent = Node(engine).apply { scale = Scale(2f) }
        val child = cube()
        parent.addChildNode(child)
        assertEquals(2f, child.worldSize.y, EPS)
    }

    @Test
    fun worldBoundingBoxFollowsRotationAndPosition() = onMain {
        val node = cube().apply {
            rotation = Rotation(z = 90f)
            position = Position(3f, 0f, 0f)
        }
        val box = node.worldBoundingBox
        // Tipped onto its side about Z: the 1 m height now runs along -X from x = 3.
        assertEquals(2.5f, box.center[0], EPS)
        assertEquals(0.5f, box.halfExtent[0], EPS)
        assertEquals(0f, box.center[1], EPS)
        assertEquals(1f, node.worldSize.y, EPS) // worldSize ignores rotation
    }

    private companion object {
        const val EPS = 1e-4f
    }
}
