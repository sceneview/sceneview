package io.github.sceneview.node

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.LightManager
import com.google.android.filament.RenderableManager
import com.google.android.filament.TransformManager
import com.google.android.filament.VertexBuffer
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentInstance
import com.google.android.filament.gltfio.FilamentAsset
import io.github.sceneview.math.Position
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.safeDestroyGeometry
import io.github.sceneview.safeDestroyModel
import io.github.sceneview.testing.ShadowFilamentEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

/** Real node teardown and reconstruction over persistent, instrumented Filament components. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    instrumentedPackages = ["com.google.android.filament"],
    shadows = [
        ModelNodeResetTest.Instance::class,
        ModelNodeResetTest.AssetBounds::class,
        ModelNodeResetTest.Transforms::class,
        ModelNodeResetTest.Renderables::class,
        ModelNodeResetTest.Lights::class,
        ModelNodeResetTest.EngineDestruction::class,
        ModelNodeResetTest.AssetDestruction::class,
    ],
)
class ModelNodeResetTest {
    private lateinit var engine: Engine

    @Before
    fun setUp() {
        engine = FilamentWithoutJni.engine()
        parents.clear()
        transforms.clear()
        parents.putAll(mapOf(7 to 9, 9 to 100, 8 to 100))
        destroyed.clear()
        releasedBuffers.clear()
        boundBuffers.clear()
        geometryWrites = 0
    }

    @Test
    fun `reset destroys each placement tree and reuses the same model five times`() {
        val instance = FilamentWithoutJni.modelInstance(engine)
        repeat(5) { cycle ->
            val pivot = Node(engine, 500 + cycle)
            val model = ModelNode(instance).apply { parent = pivot }
            val wrappers = model.nodes + model
            assertEquals(1, model.renderableNodes.size)
            assertSame(instance, model.modelInstance)
            assertEquals(9, model.renderableNodes.single().parent?.entity)

            // Anchor/pivot teardown may precede the model composable's own disposal.
            pivot.destroy()
            model.destroy()

            assertTrue(pivot.isDestroyed)
            assertTrue(pivot.childNodes.isEmpty())
            assertTrue(wrappers.all { it.isDestroyed && it.childNodes.isEmpty() })
            assertNull(model.parent)
            assertEquals(9, parents[7])
            assertEquals(100, parents[9])
            assertEquals(0, parents[100])
            assertFalse(destroyed.any { it.second in setOf(7, 8, 9, 100) })
            assertTrue(destroyed.contains("entity" to pivot.entity))
        }
    }

    @Test
    fun `origin correction does not accumulate across resets or a same-frame replacement`() {
        val instance = FilamentWithoutJni.modelInstance(engine)
        repeat(5) {
            val old = ModelNode(instance, centerOrigin = Position(y = -1f))
            assertEquals(1f, old.position.y, 0f)
            old.position = Position(y = 5f)

            // Compose constructs the replacement before disposing the previous node.
            val replacement = ModelNode(instance, centerOrigin = Position(y = -1f))
            old.destroy()
            assertEquals(1f, replacement.position.y, 0f)
            assertEquals(1f, transforms.getValue(100)[13], 0f)
            replacement.destroy()
        }
    }

    @Test
    fun `disposing the model before its pivot also preserves the instance`() {
        val pivot = Node(engine, 500)
        val model = ModelNode(FilamentWithoutJni.modelInstance(engine)).apply { parent = pivot }
        model.destroy()
        pivot.destroy()
        assertEquals(listOf("transform" to 500, "entity" to 500), destroyed)
        assertTrue(model.isDestroyed)
        assertTrue(pivot.childNodes.isEmpty())
    }

    @Test
    fun `ordinary nodes still release their components exactly once`() {
        val nodes = listOf(
            Node(engine, 501),
            RenderableNode(engine, 502),
            LightNode(engine, 503),
            CameraNode(engine, 504),
        )
        nodes.forEach { it.destroy(); it.destroy() }
        assertEquals(listOf(501, 502, 503, 504), destroyed.filter { it.first == "entity" }.map { it.second })
        assertTrue(destroyed.contains("renderable" to 502))
        assertTrue(destroyed.contains("light" to 503))
        assertTrue(destroyed.contains("camera" to 504))
    }

    @Test
    fun `a model camera wrapper leaves its component to the asset owner`() {
        val model = ModelNode(FilamentWithoutJni.modelInstance(engine))
        val camera = model.CameraNode(model.modelInstance, 10).apply {
            parent = model
        }
        model.destroy()
        assertTrue(camera.isDestroyed)
        assertTrue(destroyed.isEmpty())
    }

    @Test
    fun `replacement geometry survives five resets and is released after the asset`() {
        val instance = FilamentWithoutJni.modelInstance(engine)
        val geometry = geometry()
        var model = ModelNode(instance)
        model.renderableNodes.single().setGeometry(geometry)
        repeat(5) {
            model.destroy()
            engine.safeDestroyGeometry(geometry)
            assertTrue("the preserved renderable still draws from these buffers", releasedBuffers.isEmpty())
            assertFalse(destroyed.contains("renderable" to 7))
            val writesBefore = geometryWrites
            geometry.setVertices(engine, geometry.vertices.map { vertex ->
                vertex.copy(position = vertex.position + Position(x = 1f))
            })
            assertEquals("updates still reach the unwrapped component", writesBefore + 1, geometryWrites)
            model = ModelNode(instance)
            model.renderableNodes.single().setGeometry(geometry)
            assertSame(geometry.vertexBuffer, boundBuffers.getValue(7).first)
        }
        model.destroy()
        Shadow.newInstanceOf(AssetLoader::class.java).safeDestroyModel(instance.asset)
        assertEquals(listOf(geometry.vertexBuffer, geometry.indexBuffer), releasedBuffers)
        assertNull(ModelGeometryBindings.get(instance.asset, 7))
        engine.safeDestroyGeometry(geometry)
        assertEquals(2, releasedBuffers.size)
    }

    @Test
    fun `overlapping wrappers release replaced geometry but preserve the current binding`() {
        val instance = FilamentWithoutJni.modelInstance(engine)
        val firstGeometry = geometry()
        val replacement = geometry()
        val old = ModelNode(instance)
        old.renderableNodes.single().setGeometry(firstGeometry)
        val current = ModelNode(instance)
        current.renderableNodes.single().setGeometry(replacement)
        assertEquals(listOf(firstGeometry.vertexBuffer, firstGeometry.indexBuffer), releasedBuffers)

        old.destroy()
        current.destroy()
        assertEquals(2, releasedBuffers.size)

        val next = ModelNode(instance)
        next.renderableNodes.single().setGeometry(replacement)
        assertEquals(2, releasedBuffers.size)
        // Asset teardown can precede composable disposal as well as follow it.
        Shadow.newInstanceOf(AssetLoader::class.java).safeDestroyModel(instance.asset)
        next.destroy()
        assertEquals(
            listOf(firstGeometry.vertexBuffer, firstGeometry.indexBuffer, replacement.vertexBuffer, replacement.indexBuffer),
            releasedBuffers,
        )
    }

    @Test
    fun `asset disposal preserves geometry shared with an ordinary node until that node is gone`() {
        val instance = FilamentWithoutJni.modelInstance(engine)
        val geometry = geometry()
        val model = ModelNode(instance)
        model.renderableNodes.single().setGeometry(geometry)
        val ordinary = RenderableNode(engine, 502)
        ordinary.setGeometry(geometry)

        model.destroy()
        Shadow.newInstanceOf(AssetLoader::class.java).safeDestroyModel(instance.asset)
        assertTrue(releasedBuffers.isEmpty())

        ordinary.destroy()
        ordinary.destroy()
        assertEquals(listOf(geometry.vertexBuffer, geometry.indexBuffer), releasedBuffers)
    }

    @Test
    fun `disposing an ordinary shared node preserves the model binding across reset`() {
        val instance = FilamentWithoutJni.modelInstance(engine)
        val geometry = geometry()
        val model = ModelNode(instance)
        val ordinary = RenderableNode(engine, 502)
        model.renderableNodes.single().setGeometry(geometry)
        ordinary.setGeometry(geometry)

        ordinary.destroy()
        model.destroy()
        assertTrue(releasedBuffers.isEmpty())

        Shadow.newInstanceOf(AssetLoader::class.java).safeDestroyModel(instance.asset)
        assertEquals(listOf(geometry.vertexBuffer, geometry.indexBuffer), releasedBuffers)
    }

    private fun geometry(): Geometry = Geometry(
        primitiveType = RenderableManager.PrimitiveType.TRIANGLES,
        vertices = List(3) { Geometry.Vertex(Position(x = it.toFloat())) },
        vertexBuffer = VertexBuffer::class.java.getDeclaredConstructor(Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(1L),
        primitivesIndices = listOf(listOf(0, 1, 2)),
        indexBuffer = IndexBuffer::class.java.getDeclaredConstructor(Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(1L),
        primitivesOffsets = listOf(0..2),
        boundingBox = Box(0f, 0f, 0f, 1f, 1f, 1f),
    )

    @Implements(FilamentInstance::class)
    class Instance {
        @Implementation fun getRoot(): Int = 100
        @Implementation fun getEntities(): IntArray = intArrayOf(7, 8, 9)
    }

    @Implements(FilamentAsset::class)
    class AssetBounds {
        @Implementation fun getBoundingBox(): Box = Box(0f, 0f, 0f, 1f, 1f, 1f)
    }

    @Suppress("UnusedParameter")
    @Implements(TransformManager::class)
    class Transforms {
        @Implementation fun hasComponent(entity: Int): Boolean = true
        @Implementation fun getInstance(entity: Int): Int = entity
        @Implementation fun getParent(instance: Int): Int = parents[instance] ?: 0
        @Implementation fun setParent(instance: Int, parent: Int) { parents[instance] = parent }
        @Implementation fun destroy(entity: Int) { destroyed += "transform" to entity }
        @Implementation fun getTransform(instance: Int, out: FloatArray?): FloatArray = identity(out).apply {
            transforms[instance]?.copyInto(this)
        }
        @Implementation fun setTransform(instance: Int, value: FloatArray) { transforms[instance] = value.copyOf() }
        @Implementation fun getWorldTransform(instance: Int, out: FloatArray?) = identity(out)
    }

    @Suppress("UnusedParameter")
    @Implements(RenderableManager::class)
    class Renderables {
        @Implementation fun hasComponent(entity: Int): Boolean = entity == 7
        @Implementation fun getInstance(entity: Int): Int = entity
        @Implementation fun getMorphTargetCount(instance: Int): Int = 0
        @Implementation fun getPrimitiveCount(instance: Int): Int = 1
        @Implementation fun setGeometryAt(
            instance: Int, primitive: Int, type: RenderableManager.PrimitiveType,
            vertices: VertexBuffer, indices: IndexBuffer, offset: Int, count: Int,
        ) {
            boundBuffers[instance] = vertices to indices
            geometryWrites++
        }
        @Implementation fun getAxisAlignedBoundingBox(instance: Int, out: Box?): Box =
            (out ?: Box()).apply { setHalfExtent(1f, 1f, 1f) }
        @Implementation fun destroy(entity: Int) { destroyed += "renderable" to entity }
    }

    @Implements(LightManager::class)
    class Lights {
        @Implementation fun hasComponent(entity: Int): Boolean = entity == 8
        @Implementation fun getInstance(entity: Int): Int = entity
        @Implementation fun destroy(entity: Int) { destroyed += "light" to entity }
    }

    @Implements(Engine::class)
    class EngineDestruction : ShadowFilamentEngine() {
        @Implementation fun destroyEntity(entity: Int) { destroyed += "entity" to entity }
        @Implementation fun destroyCameraComponent(entity: Int) { destroyed += "camera" to entity }
        @Implementation fun destroyVertexBuffer(buffer: VertexBuffer) { releasedBuffers += buffer }
        @Implementation fun destroyIndexBuffer(buffer: IndexBuffer) { releasedBuffers += buffer }
    }

    @Implements(AssetLoader::class)
    class AssetDestruction {
        @Implementation fun destroyAsset(asset: FilamentAsset) {
            boundBuffers[7]?.let { (vertices, indices) ->
                assertFalse("vertices must outlive their renderable", vertices in releasedBuffers)
                assertFalse("indices must outlive their renderable", indices in releasedBuffers)
            }
            destroyed += "renderable" to 7
            FilamentAsset::class.java.getDeclaredField("mNativeObject")
                .apply { isAccessible = true }.setLong(asset, 0L)
        }
    }

    companion object {
        private val parents = mutableMapOf<Int, Int>()
        private val transforms = mutableMapOf<Int, FloatArray>()
        private val destroyed = mutableListOf<Pair<String, Int>>()
        private val releasedBuffers = mutableListOf<Any>()
        private val boundBuffers = mutableMapOf<Int, Pair<VertexBuffer, IndexBuffer>>()
        private var geometryWrites = 0

        private fun identity(out: FloatArray?): FloatArray = (out ?: FloatArray(16)).apply {
            fill(0f)
            for (i in 0..3) this[i * 5] = 1f
        }
    }
}
