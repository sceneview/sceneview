package io.github.sceneview.ar.light

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source guard for the native double-free measured on a Pixel 4a on 2026-08-14. Filament texture
 * destruction cannot run on the JVM, so the two setter symbols retain one normalized check.
 */
class LightEstimatorTextureSwapTest {

    @Test
    fun `cubemap texture setters swap atomically`() {
        val source = File("src/main/java/io/github/sceneview/ar/light/LightEstimator.kt")
            .readText()
            .replace(Regex("""/\*[\s\S]*?\*/|//[^\n]*"""), "")
            .replace(Regex("""\s+"""), "")

        listOf("cubeMapTexture", "cubeMapTextureSpecular").forEach { property ->
            val setter = source.substringAfter("privatevar$property:Texture?")
                .substringBefore("privatevar")
            assertTrue("$property must atomically remove the old texture", setter.contains("getAndSet(value)"))
        }
    }
}
