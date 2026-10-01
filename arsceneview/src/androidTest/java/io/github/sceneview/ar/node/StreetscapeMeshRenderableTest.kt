package io.github.sceneview.ar.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import io.github.sceneview.createEglContext
import io.github.sceneview.createEngine
import io.github.sceneview.node.Node
import io.github.sceneview.safeDestroy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * Build, rebuild and drop of a Streetscape mesh renderable on real Filament.
 *
 * The AR Scene Geometry sample aborted in Filament ("AABB can't be empty", Crashlytics 4.51.0)
 * because `StreetscapeGeometryNode` built its mesh without a bounding box. `StreetscapeGeometry`
 * only exists inside a live Geospatial session, so the node delegates the renderable to
 * [StreetscapeMeshRenderable], driven here with plain buffers the way the node drives it with
 * ARCore's mesh. A regression is a native abort: the test process dies.
 */
@RunWith(AndroidJUnit4::class)
class StreetscapeMeshRenderableTest {

    private lateinit var engine: Engine
    private lateinit var parent: Node
    private lateinit var renderable: StreetscapeMeshRenderable

    @Before
    fun setup() {
        runOnMain {
            Gltfio.init(); Filament.init(); Utils.init()
            engine = createEngine(createEglContext())
            parent = Node(engine)
            renderable = StreetscapeMeshRenderable(
                engine = engine,
                parent = parent,
                materialInstance = null,
                builder = {}
            )
        }
    }

    @After
    fun teardown() {
        runOnMain {
            parent.destroy()
            engine.safeDestroy()
        }
    }

    @Test
    fun emptyMesh_buildsNoRenderable() {
        runOnMain {
            assertTrue(sync(TERRAIN_EMPTY))
            assertNull(renderable.meshNode)
            assertTrue(parent.childNodes.isEmpty())
        }
    }

    @Test
    fun flatTerrain_buildsARenderableWithAComputedBox() {
        runOnMain {
            // Before the fix this build() aborted the process.
            sync(TERRAIN_TILE)
            val meshNode = renderable.meshNode
            assertNotNull("a non-empty mesh builds a renderable", meshNode)
            meshNode!!
            assertSame(parent, meshNode.parent)

            val rm = engine.renderableManager
            val instance = rm.getInstance(meshNode.entity)
            val box = rm.getAxisAlignedBoundingBox(instance, null)
            // Tile spans x and z 0..10 on y = -1.5.
            assertEquals(5f, box.center[0], EPS)
            assertEquals(-1.5f, box.center[1], EPS)
            assertEquals(5f, box.halfExtent[0], EPS)
            assertEquals(STREETSCAPE_MIN_AABB_HALF_EXTENT_M, box.halfExtent[1], EPS)
            assertTrue("default shadow receiving is kept", rm.isShadowReceiver(instance))
        }
    }

    @Test
    fun sameCounts_keepTheRenderable() {
        runOnMain {
            sync(TERRAIN_TILE)
            val first = renderable.meshNode
            assertFalse(sync(TERRAIN_TILE))
            assertSame(first, renderable.meshNode)
        }
    }

    @Test
    fun changedCounts_rebuildTheRenderable_andDestroyTheOldOne() {
        runOnMain {
            sync(TERRAIN_TILE)
            val first = renderable.meshNode!!

            assertTrue(sync(BUILDING_TWO_TILES))
            val second = renderable.meshNode!!
            assertNotSame(first, second)
            assertTrue("the replaced renderable is destroyed", first.isDestroyed)
            assertEquals(listOf(second), parent.childNodes.toList())
            val box = engine.renderableManager
                .getAxisAlignedBoundingBox(engine.renderableManager.getInstance(second.entity), null)
            assertEquals(4f, box.halfExtent[1], EPS)
        }
    }

    @Test
    fun meshBecomingEmpty_dropsTheRenderable_andComingBackRebuildsIt() {
        runOnMain {
            sync(TERRAIN_TILE)
            val first = renderable.meshNode!!

            assertTrue(sync(TERRAIN_EMPTY))
            assertNull(renderable.meshNode)
            assertTrue(first.isDestroyed)
            assertTrue(parent.childNodes.isEmpty())

            assertTrue(sync(TERRAIN_TILE))
            assertNotNull(renderable.meshNode)
        }
    }

    @Test
    fun unchangedCounts_doNotReadTheBuffers() {
        runOnMain {
            sync(TERRAIN_TILE)
            val changed = renderable.sync(
                vertexCount = TERRAIN_TILE.vertexCount,
                indexCount = TERRAIN_TILE.indices.size,
                vertexList = { error("vertex buffer read without a rebuild") },
                indexList = { error("index buffer read without a rebuild") }
            )
            assertFalse(changed)
        }
    }

    private fun sync(mesh: TestMesh): Boolean = renderable.sync(
        vertexCount = mesh.vertexCount,
        indexCount = mesh.indices.size,
        vertexList = { floats(mesh.positions) },
        indexList = { ints(mesh.indices) }
    )

    private fun runOnMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private class TestMesh(val positions: FloatArray, val indices: IntArray) {
        val vertexCount get() = positions.size / 3
    }

    private companion object {
        const val EPS = 1e-5f

        val TERRAIN_EMPTY = TestMesh(floatArrayOf(), intArrayOf())

        // Flat terrain tile on y = -1.5: zero extent on its up axis.
        val TERRAIN_TILE = TestMesh(
            floatArrayOf(
                0f, -1.5f, 0f,
                10f, -1.5f, 0f,
                10f, -1.5f, 10f,
                0f, -1.5f, 10f,
            ),
            intArrayOf(0, 1, 2, 0, 2, 3)
        )

        // A building wall 8 m high: different vertex and index counts from the tile.
        val BUILDING_TWO_TILES = TestMesh(
            floatArrayOf(
                0f, 0f, 0f,
                4f, 0f, 0f,
                4f, 8f, 0f,
                0f, 8f, 0f,
                0f, 8f, 4f,
            ),
            intArrayOf(0, 1, 2, 0, 2, 3, 3, 2, 4)
        )

        fun floats(values: FloatArray): FloatBuffer = ByteBuffer
            .allocateDirect(maxOf(values.size, 1) * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(values); rewind(); limit(values.size) }

        fun ints(values: IntArray): IntBuffer = ByteBuffer
            .allocateDirect(maxOf(values.size, 1) * Int.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()
            .apply { put(values); rewind(); limit(values.size) }
    }
}
