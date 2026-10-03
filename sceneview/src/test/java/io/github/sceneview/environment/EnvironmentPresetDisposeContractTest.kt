package io.github.sceneview.environment

import androidx.compose.runtime.Composer
import io.github.sceneview.loaders.EnvironmentLoader
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Public-shape guard for the remembered environment loaders. */
class EnvironmentPresetDisposeContractTest {

    private val presetsClass: Class<*> by lazy {
        Class.forName("io.github.sceneview.environment.EnvironmentPresetsKt")
    }

    @Test
    fun `EnvironmentLoader exposes the destroy call retained state uses`() {
        val destroy = EnvironmentLoader::class.java.declaredMethods.firstOrNull {
            it.name == "destroyEnvironment" &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == Environment::class.java
        }
        assertNotNull(destroy)
    }

    @Test
    fun `both remembered loaders remain public composables`() {
        listOf("rememberHDREnvironment", "rememberKTXEnvironment").forEach { name ->
            val factory = presetsClass.declaredMethods.firstOrNull { method ->
                method.name == name && method.parameterTypes.any { it == Composer::class.java }
            }
            assertNotNull("Missing public composable $name", factory)
            assertTrue(factory!!.returnType == Environment::class.java)
        }
    }
}
