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
import org.junit.Assert.assertNotNull
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
 * `Engine.getTextureCount()` is the size of the engine's texture list. Read before the
 * environment is built, read again after it is destroyed: any difference is a leaked cubemap.
 * Filament 1.77 dropped `Engine.isValidTexture`, the per-texture probe these tests used before,
 * and a Java wrapper cannot stand in for it: `Engine.destroyTexture` clears only the wrapper it is
 * given, so a second wrapper on the same texture keeps answering as if it were alive.
 *
 * [canary_aDroppedCubemapIsSeenByTheProbe] builds an environment the way the factories did before
 * the fix and asserts the probe reports the leak — if a Filament upgrade ever turned the texture
 * count into a constant, that test fails instead of every other one passing on a dead
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

    /** Textures in the engine now, against a [baseline] read before the environment existed. */
    private fun texturesSince(baseline: Int) = engine.textureCount - baseline

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
            val baseline = engine.textureCount
            val environment = createEnvironment(environmentLoader)
            assertEquals("the neutral IBL samples one cubemap", 1, cubemapsOf(environment).size)
            assertEquals(1, texturesSince(baseline))

            engine.safeDestroyEnvironment(environment)

            assertEquals("cubemaps still in the engine", 0, texturesSince(baseline))
        }
    }

    @Test
    fun defaultEnvironment_cubemapIsReleasedByTheLoaderDestroy() {
        onMain {
            val baseline = engine.textureCount
            val environment = createEnvironment(environmentLoader)
            assertEquals(1, texturesSince(baseline))

            environmentLoader.destroyEnvironment(environment)

            assertEquals("cubemaps still in the engine", 0, texturesSince(baseline))
        }
    }

    @Test
    fun ktxEnvironment_iblAndSkyboxCubemapsAreBothReleased() {
        onMain {
            val baseline = engine.textureCount
            val environment = environmentLoader.createKTX1Environment(
                iblBuffer = assets.readBuffer(NEUTRAL_IBL),
                skyboxBuffer = assets.readBuffer(NEUTRAL_SKYBOX),
            )
            assertEquals(
                "one cubemap for the light, one for the skybox", 2, cubemapsOf(environment).size
            )
            assertEquals(2, texturesSince(baseline))

            environmentLoader.destroyEnvironment(environment)

            assertEquals("cubemaps still in the engine", 0, texturesSince(baseline))
        }
    }

    @Test
    fun hdrEnvironment_cubemapsAreReleasedByEitherDestroy() {
        onMain {
            // The loader may keep prefilter state from its first HDR build; build
            // and drop one before the baseline so only the environment's own cubemaps are counted.
            environmentLoader.createHDREnvironment(tinyHdr())
                ?.let { environmentLoader.destroyEnvironment(it) }
            // The HDR path owned its cubemaps before #4358, through the loader only: they now
            // travel with the environment, so the engine-side destroy releases them too.
            listOf<(Environment) -> Unit>(
                { environmentLoader.destroyEnvironment(it) },
                { engine.safeDestroyEnvironment(it) },
            ).forEachIndexed { index, destroy ->
                val baseline = engine.textureCount
                val environment = environmentLoader.createHDREnvironment(tinyHdr())
                assertNotNull("HDRLoader refused the test image", environment)
                assertEquals(
                    "reflections and sky are two cubemaps",
                    2,
                    cubemapsOf(checkNotNull(environment)).size,
                )
                assertEquals(2, texturesSince(baseline))

                destroy(environment)

                assertEquals("destroy path #$index left cubemaps", 0, texturesSince(baseline))
            }
        }
    }

    @Test
    fun destroyingTwiceThroughEveryPath_destroysEachTextureOnce() {
        onMain {
            val baseline = engine.textureCount
            val environment = environmentLoader.createKTX1Environment(
                iblBuffer = assets.readBuffer(NEUTRAL_IBL),
                skyboxBuffer = assets.readBuffer(NEUTRAL_SKYBOX),
            )

            // A remembered environment is destroyed by its own `onDispose`, and again by the
            // loader's `clear()` when the two leave together. Filament aborts on a texture
            // destroyed twice, so reaching the assert is the result.
            engine.safeDestroyEnvironment(environment)
            environmentLoader.destroyEnvironment(environment)
            engine.safeDestroyEnvironment(environment)
            environmentLoader.clear()

            assertEquals(0, texturesSince(baseline))
        }
    }

    @Test
    fun copy_sharesTheHandlesWithoutOwningTheCubemap() {
        onMain {
            val baseline = engine.textureCount
            val original = createEnvironment(environmentLoader)
            // What the demos do to hide a sky: same light, another skybox.
            val copy = original.copy(skybox = null)

            engine.safeDestroyEnvironment(copy)
            assertEquals("a copy took the original's cubemap", 1, texturesSince(baseline))

            engine.safeDestroyEnvironment(original)
            assertEquals(0, texturesSince(baseline))
        }
    }

    @Test
    fun churn_noCubemapSurvivesTwentySwaps() {
        onMain {
            val baseline = engine.textureCount
            repeat(SWAPS) {
                val environment = createEnvironment(environmentLoader)
                assertEquals(1, texturesSince(baseline))
                environmentLoader.destroyEnvironment(environment)
            }
            assertEquals("cubemaps left in the engine after $SWAPS swaps", 0, texturesSince(baseline))
        }
    }

    @Test
    fun canary_aDroppedCubemapIsSeenByTheProbe() {
        onMain {
            val baseline = engine.textureCount
            val dropped = mutableListOf<Texture>()
            repeat(SWAPS) {
                // The factories before #4358: the light is kept, the bundle's cubemap is not.
                val bundle = KTX1Loader.createIndirectLight(engine, assets.readBuffer(NEUTRAL_IBL))
                val environment = Environment(indirectLight = bundle.indirectLight)
                engine.safeDestroyEnvironment(environment)
                bundle.cubemap?.let { dropped += it }
            }
            try {
                assertEquals("the probe no longer sees a dropped cubemap", SWAPS, texturesSince(baseline))
            } finally {
                dropped.forEach { engine.safeDestroyTexture(it) }
            }
            assertEquals(0, texturesSince(baseline))
        }
    }

    @Test
    fun callerOwnedCubemap_survivesAnEnvironmentThatWasNotHandedIt() {
        onMain {
            val baseline = engine.textureCount
            // `createEnvironment(engine, indirectLight = …)` without `textures` keeps its meaning:
            // the caller built the cubemap and still owns it.
            val bundle = KTX1Loader.createIndirectLight(engine, assets.readBuffer(NEUTRAL_IBL))
            val cubemap = checkNotNull(bundle.cubemap)
            val environment = createEnvironment(
                engine = engine,
                indirectLight = bundle.indirectLight,
                skybox = null,
            )

            engine.safeDestroyEnvironment(environment)
            assertEquals("a texture the caller owns was destroyed", 1, texturesSince(baseline))

            engine.safeDestroyTexture(cubemap)
            assertEquals(0, texturesSince(baseline))
        }
    }
}
