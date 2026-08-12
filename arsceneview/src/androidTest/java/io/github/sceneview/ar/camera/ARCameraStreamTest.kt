package io.github.sceneview.ar.camera

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.Scene
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import io.github.sceneview.createEglContext
import io.github.sceneview.createEngine
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.safeDestroy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device regressions for [ARCameraStream]: the depth-texture sizing bug
 * [#1617](https://github.com/sceneview/sceneview/issues/1617) and the entity-recycling
 * teardown [#2877](https://github.com/sceneview/sceneview/issues/2877). Both are Filament
 * **native** properties, so they need a real engine — see below.
 *
 * ## #1617 — *"depth occlusion does not occlude anything at all"*
 *
 * ## The bug
 *
 * [ARCameraStream] built its depth texture as `Texture.Builder()…build(engine)` with no
 * `width`/`height`. Filament's builder defaults those to **1**, and the
 * `setImage(engine, level, descriptor)` overload uploads `getWidth(level)`×`getHeight(level)`
 * texels — so every per-frame upload of a 160×120 ARCore depth image consumed exactly its
 * first texel. `camera_stream_depth.mat` then sampled that one value across the whole screen,
 * making the real-world depth a screen-wide constant: the virtual model was uniformly in
 * front of (or behind) *everything*, which reads to a user as "the toggle does nothing".
 *
 * ## Why this test, and why on a device
 *
 * The texture's size is a **Filament native** property — a JVM/Robolectric shadow would
 * happily report whatever the builder was told and could not catch a default that lives in
 * the native builder. So this asserts `Texture.getWidth(0)` on a real engine.
 *
 * It also deliberately does *not* need an ARCore session. Reproducing the visible symptom
 * end-to-end requires a recorded session that both tracks a plane and has a real object in
 * front of the placed model — measured on 2026-08-12, a Pixel 4a sweep of a room produced no
 * plane hit at all, so a screenshot comparison is a coin flip on framing while this property
 * is exact. [ARCameraStream.ensureDepthTexture] is the seam the per-frame depth upload calls,
 * so pinning it pins the fix.
 *
 * To run: `./gradlew :arsceneview:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class ARCameraStreamTest {

    private lateinit var engine: Engine
    private lateinit var materialLoader: MaterialLoader
    private lateinit var cameraStream: ARCameraStream

    @Before
    fun setup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Gltfio.init(); Filament.init(); Utils.init()
            val eglContext = createEglContext()
            engine = createEngine(eglContext)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            materialLoader = MaterialLoader(engine, context)
            cameraStream = ARCameraStream(materialLoader)
        }
    }

    @After
    fun teardown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            cameraStream.destroy()
            materialLoader.destroy()
            engine.safeDestroy()
        }
    }

    /**
     * The placeholder is 1×1 by design — a zero RG8 texel decodes to `depth_mm == 0`, which
     * the material treats as "no depth available" — but it must not survive the first depth
     * frame. This is the exact pre-fix state, pinned so a regression is unambiguous.
     */
    @Test
    fun depthTexture_startsAsA1x1Placeholder() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertEquals(1, cameraStream.depthTexture.getWidth(0))
            assertEquals(1, cameraStream.depthTexture.getHeight(0))
        }
    }

    /**
     * The regression proper: after a depth image lands, the texture must carry the image's
     * full resolution. Pre-fix this stayed 1×1 forever, and the screen-wide constant depth
     * was the visible bug.
     */
    @Test
    fun depthTexture_isResizedToTheDepthImageResolution() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // 160×120 is ARCore's typical DepthMode.AUTOMATIC resolution.
            cameraStream.ensureDepthTexture(160, 120)

            assertEquals(160, cameraStream.depthTexture.getWidth(0))
            assertEquals(120, cameraStream.depthTexture.getHeight(0))
        }
    }

    /**
     * A Filament texture is immutable-size, so a resolution change (display rotation, camera
     * config switch) must rebuild it rather than silently keep sampling the old size.
     */
    @Test
    fun depthTexture_isRebuiltWhenTheResolutionChanges() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            cameraStream.ensureDepthTexture(160, 120)
            val first = cameraStream.depthTexture

            cameraStream.ensureDepthTexture(240, 180)

            assertEquals(240, cameraStream.depthTexture.getWidth(0))
            assertEquals(180, cameraStream.depthTexture.getHeight(0))
            assertNotEquals(
                "A resolution change must rebuild the texture, not reuse the old handle",
                first,
                cameraStream.depthTexture
            )
        }
    }

    /**
     * #2877 — `destroy()` must un-register the entity from the scene it was added to *before*
     * recycling its id. Recycling an id the scene still holds lets Filament reissue it while
     * the scene renders whatever renderable is built on it next.
     *
     * This pins the [ARCameraStream] half of the contract; `ARSceneView` owns the other half
     * (setting `attachedScene` at **every** add-site — the deferred-session cold-launch path
     * is a separate one from the camera-stream `SideEffect`, and missing it made this teardown
     * a silent no-op).
     */
    @Test
    fun destroy_removesTheEntityFromTheSceneItWasAddedTo() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val stream = ARCameraStream(materialLoader)
            val scene: Scene = engine.createScene()
            scene.addEntity(stream.entity)
            stream.attachedScene = scene
            assertTrue(
                "Precondition: the entity must be in the scene before teardown",
                scene.hasEntity(stream.entity)
            )

            stream.destroy()

            assertFalse(
                "destroy() must remove the entity from its scene before recycling the id",
                scene.hasEntity(stream.entity)
            )
            engine.destroyScene(scene)
        }
    }

    /**
     * [ARCameraStream.update] calls the seam on **every** depth frame, so an unchanged
     * resolution must be a no-op — rebuilding a texture 30 times a second would both leak
     * work and unbind the sampler mid-frame.
     */
    @Test
    fun depthTexture_isNotRebuiltWhenTheResolutionIsUnchanged() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            cameraStream.ensureDepthTexture(160, 120)
            val first = cameraStream.depthTexture

            cameraStream.ensureDepthTexture(160, 120)

            assertEquals(
                "An unchanged resolution must not rebuild the texture",
                first,
                cameraStream.depthTexture
            )
        }
    }
}
