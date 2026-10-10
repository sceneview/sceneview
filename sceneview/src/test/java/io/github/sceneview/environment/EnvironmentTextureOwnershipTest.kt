package io.github.sceneview.environment

import com.google.android.filament.IndirectLight
import com.google.android.filament.Skybox
import com.google.android.filament.Texture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ownership rules of the cubemaps an [Environment] is handed (#4358), run against
 * [Environment.destroy] itself — the one implementation both `Engine.safeDestroyEnvironment` and
 * `EnvironmentLoader.destroyEnvironment` go through.
 *
 * The Filament wrappers are built on made-up native pointers and never dereferenced: this pins
 * what is destroyed, how many times and in which order. That the textures really leave the engine
 * is measured on a real one by the instrumented `EnvironmentTextureReleaseTest`.
 */
class EnvironmentTextureOwnershipTest {

    private val indirectLight = IndirectLight(1L)
    private val skybox = Skybox(2L)
    private val reflections = Texture(3L)
    private val sky = Texture(4L)

    /** Every handle a destroy pass was given, in the order it was given. */
    private fun Environment.destroyRecording(): List<Any> = buildList<Any> {
        destroy(
            destroyIndirectLight = { add(it) },
            destroySkybox = { add(it) },
            destroyTexture = { add(it) },
        )
    }

    @Test
    fun `the light and the skybox are destroyed before the textures they sample`() {
        val environment = Environment(indirectLight, skybox).apply {
            ownTextures(listOf(reflections, sky))
        }

        val destroyed = environment.destroyRecording()

        assertEquals(4, destroyed.size)
        assertSame(indirectLight, destroyed[0])
        assertSame(skybox, destroyed[1])
        assertSame(reflections, destroyed[2])
        assertSame(sky, destroyed[3])
    }

    @Test
    fun `a cubemap serving both the light and the skybox is released once`() {
        val environment = Environment(indirectLight, skybox).apply {
            ownTextures(listOf(reflections, reflections))
            ownTextures(listOf(reflections))
        }

        val textures = environment.destroyRecording().filterIsInstance<Texture>()

        assertEquals(1, textures.size)
        assertSame(reflections, textures.single())
    }

    @Test
    fun `a second destroy is handed no texture`() {
        val environment = Environment(indirectLight, skybox).apply {
            ownTextures(listOf(reflections, sky))
        }

        val first = environment.destroyRecording().filterIsInstance<Texture>()
        val second = environment.destroyRecording().filterIsInstance<Texture>()

        assertEquals(2, first.size)
        assertTrue("textures released twice: $second", second.isEmpty())
    }

    @Test
    fun `a copy owns no texture and leaves the original's in place`() {
        val original = Environment(indirectLight, skybox).apply {
            ownTextures(listOf(reflections, sky))
        }
        val copy = original.copy(skybox = null)

        val fromCopy = copy.destroyRecording().filterIsInstance<Texture>()
        val fromOriginal = original.destroyRecording().filterIsInstance<Texture>()

        assertTrue("a copy released the original's textures: $fromCopy", fromCopy.isEmpty())
        assertEquals(2, fromOriginal.size)
        assertSame(reflections, fromOriginal[0])
        assertSame(sky, fromOriginal[1])
    }

    @Test
    fun `an environment handed no texture releases only its light and skybox`() {
        val destroyed = Environment(indirectLight, skybox).destroyRecording()

        assertEquals(2, destroyed.size)
        assertSame(indirectLight, destroyed[0])
        assertSame(skybox, destroyed[1])
    }
}
