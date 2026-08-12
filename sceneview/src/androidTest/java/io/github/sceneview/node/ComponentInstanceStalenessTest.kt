package io.github.sceneview.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Box
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.LightManager
import com.google.android.filament.RenderableManager
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import io.github.sceneview.createEglContext
import io.github.sceneview.createEngine
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.safeDestroy
import io.github.sceneview.safeDestroyEntity
import io.github.sceneview.safeDestroyLight
import io.github.sceneview.safeDestroyRenderable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Engine-backed regression tests for the component-handle staleness family (#2991, #3123) —
 * the `LightManager` / `RenderableManager` siblings of the `TransformManager` bug #2978 fixed,
 * plus the two `TransformManager` destroy paths #2978 left unbumped.
 *
 * All three managers are `SingleInstanceComponentManager`: a packed array that compacts on
 * removal by swapping the LAST live entity into the freed slot
 * (`removeComponentsHelper`: `p[index] = std::move(p[last])`). Any handle a caller cached for
 * the moved entity silently starts addressing a different component — reads and writes stay
 * self-consistent from Kotlin while the renderer uses the real one, which is what makes this
 * bug class invisible without a fresh-vs-cached comparison like the ones below.
 *
 * Every test asserts the same invariant: the node's **cached** handle equals a **fresh**,
 * uncached `getInstance(entity)` lookup after an unrelated destroy.
 */
@RunWith(AndroidJUnit4::class)
class ComponentInstanceStalenessTest {

    private lateinit var engine: com.google.android.filament.Engine

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

    private fun buildLightOn(entity: Int) = LightManager.Builder(LightManager.Type.POINT)
        .intensity(1000.0f)
        .build(engine, entity)

    private fun buildRenderableOn(entity: Int) = RenderableManager.Builder(0)
        .boundingBox(Box(0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f))
        .culling(false)
        .build(engine, entity)

    /**
     * [LightNode.lightInstance] caches a [LightManager] handle (#2285). Destroying an unrelated
     * light compacts the array and reindexes one surviving light — every subsequent
     * intensity/color/direction write on the victim then drives **another** light (#2991).
     *
     * The decoys are raw entities destroyed through [io.github.sceneview.safeDestroyLight] and
     * not through `LightNode.destroy()`, deliberately: `Node.destroy()` also goes through
     * `safeDestroyEntity`, which bumps all three generations, and would mask the removal of the
     * [io.github.sceneview.destroyLight] bump this test is here to pin.
     */
    @Test
    fun lightInstance_survivesUnrelatedLightDestroy() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val lm = engine.lightManager

            val decoyEntities = List(8) {
                EntityManager.get().create().also { buildLightOn(it) }
            }
            val underTest = LightNode(engine, LightManager.Type.POINT) { intensity(1000.0f) }
            underTest.lightInstance // populate the cache

            engine.safeDestroyLight(decoyEntities[3])

            assertEquals(
                "underTest's cached lightInstance must track LightManager's real index after " +
                    "an unrelated light component is destroyed",
                lm.getInstance(underTest.entity), underTest.lightInstance,
            )

            underTest.destroy()
            decoyEntities.forEach { engine.safeDestroyEntity(it) }
        }
    }

    /**
     * [RenderableNode.renderableInstance] caches a [RenderableManager] handle (#2287). Same
     * packed-array compaction — and this handle feeds write paths (`setLayerMask`,
     * `setPriority`, the AABB setter), so a stale one drives another renderable (#3123).
     *
     * Raw decoy entities for the same reason as the light test above: destroying a whole
     * [RenderableNode] would also bump through `safeDestroyEntity` and hide a missing
     * [io.github.sceneview.destroyRenderable] bump.
     */
    @Test
    fun renderableInstance_survivesUnrelatedRenderableDestroy() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val rm = engine.renderableManager

            val decoyEntities = List(8) {
                EntityManager.get().create().also { buildRenderableOn(it) }
            }
            val underTest = RenderableNode(engine).also { buildRenderableOn(it.entity) }
            underTest.renderableInstance // populate the cache

            engine.safeDestroyRenderable(decoyEntities[3])

            assertEquals(
                "underTest's cached renderableInstance must track RenderableManager's real " +
                    "index after an unrelated renderable component is destroyed",
                rm.getInstance(underTest.entity), underTest.renderableInstance,
            )

            underTest.destroy()
            decoyEntities.forEach { engine.safeDestroyEntity(it) }
        }
    }

    /**
     * Models `SplatNode.destroy()`: batch entities that carry a transform component
     * (`transformManager.create(entity, transformInstance, null)`) are torn down with
     * [io.github.sceneview.safeDestroyEntity] alone — never [io.github.sceneview
     * .destroyTransformable]. `FEngine::destroy(Entity)` destroys the transform component all
     * the same, so it reindexes the array; before this fix that path bumped nothing and #2978's
     * invalidation never fired for it (#3123).
     */
    @Test
    fun transformInstance_survivesDestroyEntityOnlyTeardown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val tm = engine.transformManager

            val rawEntities = List(8) {
                EntityManager.get().create().also { tm.create(it) }
            }
            val underTest = Node(engine)
            underTest.transformInstance // populate the cache

            // The SplatNode shape: destroyEntity only, no destroyTransformable beforehand.
            engine.safeDestroyEntity(rawEntities[3])

            assertEquals(
                "underTest's cached transformInstance must track TransformManager's real index " +
                    "after a transform component is destroyed through destroyEntity alone",
                tm.getInstance(underTest.entity), underTest.transformInstance,
            )

            underTest.destroy()
            rawEntities.forEach { runCatching { engine.safeDestroyEntity(it) } }
        }
    }

    /**
     * Binds the `ModelLoader.destroyModel` bump to a test. #2978 added that bump but no test
     * exercised it: its own asset-teardown test called `destroyTransformable` directly, so
     * deleting the `destroyModel` line left the whole suite green — a mutation the suite could
     * not see. This one goes through `destroyModel`, the real `AssetLoader.destroyAsset` path.
     */
    @Test
    fun transformInstance_survivesModelTeardown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().context
            val tm = engine.transformManager
            val modelLoader = ModelLoader(engine, context)

            val model = modelLoader.createModel("webp_quad.glb")
            val underTest = Node(engine)
            underTest.transformInstance // populate the cache

            modelLoader.destroyModel(model)

            assertEquals(
                "underTest's cached transformInstance must track TransformManager's real index " +
                    "after a glTF asset teardown (ModelLoader.destroyModel -> destroyAsset)",
                tm.getInstance(underTest.entity), underTest.transformInstance,
            )

            underTest.destroy()
        }
    }
}
