package io.github.sceneview.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.IndexBuffer
import com.google.android.filament.RenderableManager.PrimitiveType
import com.google.android.filament.VertexBuffer
import com.google.android.filament.VertexBuffer.AttributeType
import com.google.android.filament.VertexBuffer.VertexAttribute
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import io.github.sceneview.createEglContext
import io.github.sceneview.createEngine
import io.github.sceneview.safeDestroy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [MeshNode]'s bounding-box contract on real Filament.
 *
 * Filament's `RenderableManager.Builder.build` aborts the process ("AABB can't be empty, unless
 * culling is disabled and the object is not a shadow caster/receiver") for a renderable without
 * a box that still receives shadows — Filament's default. `StreetscapeGeometryNode` hit it in
 * production (Crashlytics, 4.51.0, AR Scene Geometry). A native abort cannot be caught: if the
 * no-box branch regresses, this test process dies instead of failing an assertion.
 */
@RunWith(AndroidJUnit4::class)
class MeshNodeBoundingBoxTest {

    private lateinit var engine: Engine

    @Before
    fun setup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Gltfio.init(); Filament.init(); Utils.init()
            engine = createEngine(createEglContext())
        }
    }

    @After
    fun teardown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            engine.safeDestroy()
        }
    }

    @Test
    fun meshWithoutBox_builds_withCullingAndShadowsOff() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // Before the fix this build() aborted the process.
            val node = triangleMeshNode(boundingBox = null)
            val rm = engine.renderableManager
            val instance = rm.getInstance(node.entity)

            assertTrue("a renderable must have been built", rm.hasComponent(node.entity))
            assertFalse("no-box mesh must not receive shadows", rm.isShadowReceiver(instance))
            assertFalse("no-box mesh must not cast shadows", rm.isShadowCaster(instance))

            node.destroy()
        }
    }

    @Test
    fun meshWithBox_keepsTheBox_andDefaultShadowReceiving() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val node = triangleMeshNode(boundingBox = Box(0.5f, 0.5f, 0f, 0.5f, 0.5f, 0.001f))
            val rm = engine.renderableManager
            val instance = rm.getInstance(node.entity)

            val box = rm.getAxisAlignedBoundingBox(instance, null)
            assertEquals(0.5f, box.center[0], EPS)
            assertEquals(0.5f, box.halfExtent[1], EPS)
            assertTrue("a boxed mesh keeps Filament's default", rm.isShadowReceiver(instance))

            node.destroy()
        }
    }

    @Test
    fun builderRunsAfterTheNoBoxDefaults() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // The user builder still has the last word: a box it supplies is honoured.
            val node = triangleMeshNode(boundingBox = null) {
                boundingBox(Box(0f, 0f, 0f, 1f, 1f, 1f))
                culling(true)
                receiveShadows(true)
            }
            val rm = engine.renderableManager
            val instance = rm.getInstance(node.entity)
            assertTrue(rm.isShadowReceiver(instance))
            assertEquals(1f, rm.getAxisAlignedBoundingBox(instance, null).halfExtent[0], EPS)

            node.destroy()
        }
    }

    private fun triangleMeshNode(
        boundingBox: Box?,
        builder: com.google.android.filament.RenderableManager.Builder.() -> Unit = {},
    ): MeshNode {
        val positions = ByteBuffer.allocateDirect(9 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply {
                asFloatBuffer().put(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f))
            }
        val indices = ByteBuffer.allocateDirect(3 * Int.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply { asIntBuffer().put(intArrayOf(0, 1, 2)) }
        val vertexBuffer = VertexBuffer.Builder()
            .bufferCount(1)
            .attribute(VertexAttribute.POSITION, 0, AttributeType.FLOAT3)
            .vertexCount(3)
            .build(engine)
            .apply { setBufferAt(engine, 0, positions) }
        val indexBuffer = IndexBuffer.Builder()
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .indexCount(3)
            .build(engine)
            .apply { setBuffer(engine, indices) }
        return MeshNode(
            engine = engine,
            primitiveType = PrimitiveType.TRIANGLES,
            vertexBuffer = vertexBuffer,
            indexBuffer = indexBuffer,
            boundingBox = boundingBox,
            destroyBuffersOnDispose = true,
            builder = builder
        )
    }

    private companion object {
        const val EPS = 1e-5f
    }
}
