package io.github.sceneview.ar

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Guards the native AR frame/teardown wiring of #4939, which needs ARCore + Filament to run.
 * Registry behaviour and the shared hooks run in ReflectionProbeRegistryTest and
 * ReflectionProbeNodeCompositionTest in sceneview; this guard catches bypassing those hooks.
 */
class ARSceneEnvironmentContractTest {
    @Test
    fun `AR lighting never bypasses the shared probe registry`() {
        val source = File("src/main/java/io/github/sceneview/ar/ARSceneView.kt").readText()

        assertFalse(
            "Direct IBL writes bypass active probes, including during teardown",
            Regex("""(?m)^\s*scene\.indirectLight\s*=""").containsMatchIn(source),
        )
    }
}
