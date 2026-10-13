package io.github.sceneview.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Filament
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import io.github.sceneview.createEglContext
import io.github.sceneview.createEngine
import io.github.sceneview.math.Rotation
import io.github.sceneview.safeDestroy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Engine-backed half of `NodeRotationConventionTest` (#3745): the same question asked of real
 * nodes, whose world matrix comes back from Filament's `TransformManager`.
 *
 * Until #3745 a node with no parent set to `rotation = Rotation(y = 30f)` read
 * `worldRotation.y == -30f`, and `node.worldRotation = node.worldRotation` turned it.
 */
@RunWith(AndroidJUnit4::class)
class NodeRotationConventionInstrumentedTest {

    private lateinit var engine: com.google.android.filament.Engine

    @Before
    fun setup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Gltfio.init(); Filament.init(); Utils.init()
            val eglContext = createEglContext()
            engine = createEngine(eglContext)
        }
    }

    @After
    fun teardown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            engine.safeDestroy()
        }
    }

    @Test
    fun rootNode_worldRotation_readsWhatWasWrittenToRotation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            CASES.forEach { written ->
                val node = Node(engine)
                node.rotation = written

                assertRotationEquals("rotation after writing $written", written, node.rotation)
                assertRotationEquals(
                    "worldRotation after writing rotation = $written",
                    written,
                    node.worldRotation,
                )

                node.destroy()
            }
        }
    }

    @Test
    fun worldRotation_writtenBackToItself_doesNotTurnTheNode() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            CASES.forEach { written ->
                val parent = Node(engine)
                val child = Node(engine)
                parent.rotation = Rotation(x = -12f, y = 35f, z = 8f)
                parent.addChildNode(child)
                child.rotation = written

                val before = child.worldRotation
                child.worldRotation = before

                assertRotationEquals("worldRotation of a child at $written", before, child.worldRotation)
                assertRotationEquals("rotation of a child at $written", written, child.rotation)

                child.destroy()
                parent.destroy()
            }
        }
    }

    private fun assertRotationEquals(message: String, expected: Rotation, actual: Rotation) {
        assertEquals("$message — x of $actual", expected.x, actual.x, EPS)
        assertEquals("$message — y of $actual", expected.y, actual.y, EPS)
        assertEquals("$message — z of $actual", expected.z, actual.z, EPS)
    }

    private companion object {
        const val EPS = 5e-2f

        val CASES = listOf(
            Rotation(y = 30f),
            Rotation(x = 20f),
            Rotation(z = 15f),
            Rotation(x = 20f, y = 30f, z = 15f),
            Rotation(y = 45f, z = 10f),
        )
    }
}
