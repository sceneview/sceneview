package io.github.sceneview.environment

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.Texture
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.Utils
import io.github.sceneview.createEglContext
import io.github.sceneview.createEngine
import io.github.sceneview.createEnvironment
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.safeDestroy
import io.github.sceneview.safeDestroyEnvironment
import io.github.sceneview.safeDestroyTexture
import io.github.sceneview.utils.readBuffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/**
 * The cubemaps behind an [Environment] leave the engine with it (#4358), measured on a real
 * Filament `Engine`.
 *
 * `KTX1Loader` returns a bundle: the `IndirectLight` or `Skybox`, and the cubemap `Texture` it
 * samples. Filament frees neither texture with the object that samples it, and the KTX factories
 * used to keep the first half of the bundle only — every rebuilt environment left a cubemap on the
 * GPU (2 MB for the bundled neutral IBL) until the engine went away.
 *
 * ## The probe
 *
 * `IndirectLight.getReflectionsTexture()` and `Skybox.getTexture()` hand back a fresh Java wrapper
 * on the native texture, and `Engine.isValidTexture` says whether that texture is still in the
 * engine's resource list. Read before the environment is destroyed, asked after: `true` is a
 * leaked cubemap. The question is always asked before anything else is created, so a recycled
 * address cannot answer for a dead texture.
 *
 * It has to be a second wrapper: `Engine.destroyTexture` clears the wrapper it is given, and
 * asking anything of a cleared wrapper throws instead of answering `false`.
 *
 * [canary_aDroppedCubemapIsSeenByTheProbe] builds an environment the way the factories did before
 * the fix and asserts the probe reports the leak — if a Filament upgrade ever turned
 * `isValidTexture` into a constant, that test fails instead of every other one passing on a dead
 * instrument.
 *
 * Headless, like `LeakChurnTest`: no `SwapChain`, no `readPixels`, so it runs on the SwiftShader
 * emulator. A texture destroyed twice does not fail an assertion, it aborts the process: the
 * double-destroy tests pass by finishing.
 */
@RunWith(AndroidJUnit4::class)
class EnvironmentTextureReleaseTest {

    private companion object {
        const val NEUTRAL_IBL = "environments/neutral/neutral_ibl.ktx"
        const val NEUTRAL_SKYBOX = "environments/neutral/neutral_skybox.ktx"

        /** Environment swaps per churn test — the count #4358 asks to be measured over. */
        const val SWAPS = 20
    }

    private lateinit var engine: Engine
    private lateinit var environmentLoader: EnvironmentLoader

    private val assets get() = InstrumentationRegistry.getInstrumentation().targetContext.assets

    @Before
    fun setup() {
        onMain {
            Gltfio.init(); Filament.init(); Utils.init()
            engine = createEngine(createEglContext())
            environmentLoader = EnvironmentLoader(
                engine,
                InstrumentationRegistry.getInstrumentation().targetContext,
            )
        }
    }

    @After
    fun teardown() {
        // `createEglContext()` hard-errors on a host without a usable EGL config, which would
        // leave both uninitialized; touching them here would bury the real setup failure.
        if (!::engine.isInitialized) return
        onMain {
            if (::environmentLoader.isInitialized) environmentLoader.destroy()
            engine.safeDestroy()
        }
    }

    /** Runs [block] on the main thread — every Filament JNI call must. */
    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    /** The cubemaps [environment] samples, as wrappers that outlive its destruction. */
    private fun cubemapsOf(environment: Environment): List<Texture> = listOfNotNull(
        environment.indirectLight?.reflectionsTexture,
        environment.skybox?.texture,
    )

    private fun aliveAmong(textures: List<Texture>) = textures.count { engine.isValidTexture(it) }

    /** A 4×2 flat Radiance file, mid grey: the smallest input the HDR path prefilters. */
    private fun tinyHdr(): ByteBuffer {
        val header = "#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 2 +X 4\n".toByteArray(Charsets.US_ASCII)
        val pixel = byteArrayOf(128.toByte(), 128.toByte(), 128.toByte(), 129.toByte())
        val bytes = header + ByteArray(4 * 2 * pixel.size) { pixel[it % pixel.size] }
        return ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            rewind()
        }
    }

    @Test
    fun defaultEnvironment_cubemapIsReleasedByTheEngineDestroy() {
        onMain {
            val environment = createEnvironment(environmentLoader)
            val cubemaps = cubemapsOf(environment)
            assertEquals("the neutral IBL samples one cubemap", 1, cubemaps.size)
            assertEquals(1, aliveAmong(cubemaps))

            engine.safeDestroyEnvironment(environment)

            assertEquals("cubemaps still in the engine", 0, aliveAmong(cubemaps))
        }
    }

    @Test
    fun defaultEnvironment_cubemapIsReleasedByTheLoaderDestroy() {
        onMain {
            val environment = createEnvironment(environmentLoader)
            val cubemaps = cubemapsOf(environment)
            assertEquals(1, aliveAmong(cubemaps))

            environmentLoader.destroyEnvironment(environment)

            assertEquals("cubemaps still in the engine", 0, aliveAmong(cubemaps))
        }
    }

    @Test
    fun ktxEnvironment_iblAndSkyboxCubemapsAreBothReleased() {
        onMain {
            val environment = environmentLoader.createKTX1Environment(
                iblBuffer = assets.readBuffer(NEUTRAL_IBL),
                skyboxBuffer = assets.readBuffer(NEUTRAL_SKYBOX),
            )
            val cubemaps = cubemapsOf(environment)
            assertEquals("one cubemap for the light, one for the skybox", 2, cubemaps.size)
            assertEquals(2, aliveAmong(cubemaps))

            environmentLoader.destroyEnvironment(environment)

            assertEquals("cubemaps still in the engine", 0, aliveAmong(cubemaps))
        }
    }

    @Test
    fun hdrEnvironment_cubemapsAreReleasedByEitherDestroy() {
        onMain {
            // The HDR path owned its cubemaps before #4358, through the loader only: they now
            // travel with the environment, so the engine-side destroy releases them too.
            listOf<(Environment) -> Unit>(
                { environmentLoader.destroyEnvironment(it) },
                { engine.safeDestroyEnvironment(it) },
            ).forEachIndexed { index, destroy ->
                val environment = environmentLoader.createHDREnvironment(tinyHdr())
                assertNotNull("HDRLoader refused the test image", environment)
                val cubemaps = cubemapsOf(checkNotNull(environment))
                assertEquals("reflections and sky are two cubemaps", 2, cubemaps.size)
                assertEquals(2, aliveAmong(cubemaps))

                destroy(environment)

                assertEquals("destroy path #$index left cubemaps", 0, aliveAmong(cubemaps))
            }
        }
    }

    @Test
    fun destroyingTwiceThroughEveryPath_destroysEachTextureOnce() {
        onMain {
            val environment = environmentLoader.createKTX1Environment(
                iblBuffer = assets.readBuffer(NEUTRAL_IBL),
                skyboxBuffer = assets.readBuffer(NEUTRAL_SKYBOX),
            )
            val cubemaps = cubemapsOf(environment)

            // A remembered environment is destroyed by its own `onDispose`, and again by the
            // loader's `clear()` when the two leave together. Filament aborts on a texture
            // destroyed twice, so reaching the assert is the result.
            engine.safeDestroyEnvironment(environment)
            environmentLoader.destroyEnvironment(environment)
            engine.safeDestroyEnvironment(environment)
            environmentLoader.clear()

            assertEquals(0, aliveAmong(cubemaps))
        }
    }

    @Test
    fun copy_sharesTheHandlesWithoutOwningTheCubemap() {
        onMain {
            val original = createEnvironment(environmentLoader)
            val cubemaps = cubemapsOf(original)
            // What the demos do to hide a sky: same light, another skybox.
            val copy = original.copy(skybox = null)

            engine.safeDestroyEnvironment(copy)
            assertEquals("a copy took the original's cubemap", 1, aliveAmong(cubemaps))

            engine.safeDestroyEnvironment(original)
            assertEquals(0, aliveAmong(cubemaps))
        }
    }

    @Test
    fun churn_noCubemapSurvivesTwentySwaps() {
        onMain {
            var leaked = 0
            repeat(SWAPS) {
                val environment = createEnvironment(environmentLoader)
                val cubemaps = cubemapsOf(environment)
                assertEquals(1, aliveAmong(cubemaps))
                environmentLoader.destroyEnvironment(environment)
                leaked += aliveAmong(cubemaps)
            }
            assertEquals("cubemaps left in the engine after $SWAPS swaps", 0, leaked)
        }
    }

    @Test
    fun canary_aDroppedCubemapIsSeenByTheProbe() {
        onMain {
            val dropped = mutableListOf<Texture>()
            val probes = mutableListOf<Texture>()
            var leaked = 0
            repeat(SWAPS) {
                // The factories before #4358: the light is kept, the bundle's cubemap is not.
                val bundle = KTX1Loader.createIndirectLight(engine, assets.readBuffer(NEUTRAL_IBL))
                val environment = Environment(indirectLight = bundle.indirectLight)
                val cubemaps = cubemapsOf(environment)
                engine.safeDestroyEnvironment(environment)
                leaked += aliveAmong(cubemaps)
                probes += cubemaps
                bundle.cubemap?.let { dropped += it }
            }
            try {
                assertEquals("the probe no longer sees a dropped cubemap", SWAPS, leaked)
                assertEquals("dropped cubemaps all still in the engine", SWAPS, aliveAmong(probes))
            } finally {
                dropped.forEach { engine.safeDestroyTexture(it) }
            }
            assertEquals(0, aliveAmong(probes))
        }
    }

    @Test
    fun callerOwnedCubemap_survivesAnEnvironmentThatWasNotHandedIt() {
        onMain {
            // `createEnvironment(engine, indirectLight = …)` without `textures` keeps its meaning:
            // the caller built the cubemap and still owns it.
            val bundle = KTX1Loader.createIndirectLight(engine, assets.readBuffer(NEUTRAL_IBL))
            val cubemap = checkNotNull(bundle.cubemap)
            val environment = createEnvironment(
                engine = engine,
                indirectLight = bundle.indirectLight,
                skybox = null,
            )
            val probe = cubemapsOf(environment).single()

            engine.safeDestroyEnvironment(environment)
            assertTrue("a texture the caller owns was destroyed", engine.isValidTexture(probe))

            engine.safeDestroyTexture(cubemap)
            assertFalse(engine.isValidTexture(probe))
        }
    }
}
