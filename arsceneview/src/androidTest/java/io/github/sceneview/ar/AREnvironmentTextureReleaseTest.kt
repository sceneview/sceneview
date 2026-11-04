package io.github.sceneview.ar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import io.github.sceneview.createEglContext
import io.github.sceneview.createEngine
import io.github.sceneview.safeDestroy
import io.github.sceneview.safeDestroyEnvironment
import io.github.sceneview.utils.readBuffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The AR baseline environment releases the cubemap of its KTX indirect light (#4358), measured on
 * a real Filament `Engine`.
 *
 * [createAREnvironment] is what `rememberAREnvironment` builds and what its `onDispose` hands to
 * `Engine.safeDestroyEnvironment`. It needs no ARCore session, so the factory and its destroy run
 * here as they do in an AR screen. What this cannot show is the frame loop: the light estimation
 * rebuilds an indirect light that may sample this same cubemap, and only a device with a camera
 * exercises that.
 *
 * The probe is the one of the 3D `EnvironmentTextureReleaseTest`: `Engine.getTextureCount()` read
 * before the environment is built and again after it is destroyed (Filament 1.77 dropped
 * `Engine.isValidTexture`).
 */
@RunWith(AndroidJUnit4::class)
class AREnvironmentTextureReleaseTest {

    private companion object {
        /** Ships in `:sceneview`'s assets, merged into this module's test APK. */
        const val NEUTRAL_IBL = "environments/neutral/neutral_ibl.ktx"

        const val SWAPS = 20
    }

    private lateinit var engine: Engine

    private val assets get() = InstrumentationRegistry.getInstrumentation().targetContext.assets

    @Before
    fun setup() {
        onMain {
            Gltfio.init(); Filament.init(); Utils.init()
            engine = createEngine(createEglContext())
        }
    }

    @After
    fun teardown() {
        if (!::engine.isInitialized) return
        onMain { engine.safeDestroy() }
    }

    /** Runs [block] on the main thread — every Filament JNI call must. */
    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test
    fun baselineEnvironment_cubemapIsReleasedWithIt() {
        onMain {
            val baseline = engine.textureCount
            val environment = createAREnvironment(engine, assets.readBuffer(NEUTRAL_IBL))
            assertNull("the camera feed is the AR backdrop", environment.skybox)
            checkNotNull(environment.indirectLight?.reflectionsTexture)
            assertEquals(1, engine.textureCount - baseline)

            engine.safeDestroyEnvironment(environment)

            assertEquals("the KTX cubemap is still in the engine", 0, engine.textureCount - baseline)
        }
    }

    @Test
    fun baselineEnvironment_noCubemapSurvivesTwentySwaps() {
        onMain {
            val baseline = engine.textureCount
            repeat(SWAPS) {
                val environment = createAREnvironment(engine, assets.readBuffer(NEUTRAL_IBL))
                checkNotNull(environment.indirectLight?.reflectionsTexture)
                engine.safeDestroyEnvironment(environment)
                // Destroyed twice when the screen and its engine owner leave together.
                engine.safeDestroyEnvironment(environment)
            }
            assertEquals(
                "cubemaps left in the engine after $SWAPS swaps", 0, engine.textureCount - baseline
            )
        }
    }

    @Test
    fun environmentWithoutIbl_hasNothingToRelease() {
        onMain {
            val environment = createAREnvironment(engine)
            assertNull(environment.indirectLight)
            assertNull(environment.skybox)

            engine.safeDestroyEnvironment(environment)
        }
    }
}
