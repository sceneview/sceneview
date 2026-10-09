package io.github.sceneview.environment

import com.google.android.filament.Texture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EnvironmentTextureOwnershipTest {

    @Test
    fun `owned textures are released once in registration order`() {
        val first = Texture(1L)
        val second = Texture(2L)
        val environment = Environment()
        val destroyed = mutableListOf<Texture>()

        environment.ownTextures(listOf(first, second, first))
        environment.destroyOwnedTextures { destroyed += it }
        environment.destroyOwnedTextures { destroyed += it }

        assertEquals(2, destroyed.size)
        assertSame(first, destroyed[0])
        assertSame(second, destroyed[1])
    }

    @Test
    fun `3D KTX factories transfer every bundle cubemap to the environment`() {
        val sceneFactories = File("src/main/java/io/github/sceneview/SceneFactories.kt").readText()
        val environmentLoader =
            File("src/main/java/io/github/sceneview/loaders/EnvironmentLoader.kt").readText()

        assertTrue(
            Regex("""textures\s*=\s*listOfNotNull\(indirectLightBundle\.cubemap\)""")
                .containsMatchIn(sceneFactories),
        )
        assertTrue(
            Regex(
                """textures\s*=\s*listOfNotNull\(""" +
                    """indirectLightBundle\?\.cubemap,\s*skyboxBundle\?\.cubemap\)""",
            ).containsMatchIn(environmentLoader),
        )
    }

    @Test
    fun `engine destroys owned textures after light and skybox`() {
        val engineSource = File("src/main/java/io/github/sceneview/Engine.kt").readText()
        val destroyBlock = functionBlock(engineSource, "fun Engine.safeDestroyEnvironment")

        assertTrue(destroyBlock.indexOf("safeDestroyIndirectLight") >= 0)
        assertTrue(
            destroyBlock.indexOf("safeDestroyIndirectLight") <
                destroyBlock.indexOf("destroyOwnedTextures"),
        )
        assertTrue(
            destroyBlock.indexOf("safeDestroySkybox") <
                destroyBlock.indexOf("destroyOwnedTextures"),
        )
    }

    @Test
    fun `loader destroys owned textures after light and skybox`() {
        val loaderSource =
            File("src/main/java/io/github/sceneview/loaders/EnvironmentLoader.kt").readText()
        val destroyBlock = functionBlock(loaderSource, "fun destroyEnvironment")

        assertTrue(destroyBlock.indexOf("safeDestroyIndirectLight") >= 0)
        assertTrue(
            destroyBlock.indexOf("safeDestroyIndirectLight") <
                destroyBlock.indexOf("destroyOwnedTextures"),
        )
        assertTrue(
            destroyBlock.indexOf("safeDestroySkybox") <
                destroyBlock.indexOf("destroyOwnedTextures"),
        )
    }

    private fun functionBlock(source: String, signature: String): String {
        val functionStart = source.indexOf(signature)
        val lineStart = source.lastIndexOf('\n', functionStart) + 1
        val indentation = source.substring(lineStart, functionStart).takeWhile { it == ' ' }
        val functionEnd = source.indexOf("\n$indentation}", functionStart)
        return source.substring(functionStart, functionEnd)
    }
}
