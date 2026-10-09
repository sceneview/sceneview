package io.github.sceneview.ar

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ARKtxEnvironmentTextureOwnershipContractTest {

    @Test
    fun `AR KTX factory transfers its bundle cubemap to the environment`() {
        val source = File("src/main/java/io/github/sceneview/ar/ARFactories.kt").readText()

        assertTrue(
            Regex("""textures\s*=\s*listOfNotNull\(indirectLightBundle\?\.cubemap\)""")
                .containsMatchIn(source),
        )
    }
}
