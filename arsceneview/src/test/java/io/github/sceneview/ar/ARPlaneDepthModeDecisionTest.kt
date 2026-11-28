package io.github.sceneview.ar

import com.google.ar.core.Config
import io.github.sceneview.ar.scene.SceneUnderstanding
import io.github.sceneview.ar.scene.applyModelDepthOcclusion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ARPlaneDepthModeDecisionTest {
    @Test
    fun `implicit plane depth preserves both previous model occlusion values without a write`() {
        assertEquals(Config.DepthMode.AUTOMATIC, resolvePlaneDepthMode(null, true, true))
        for (previous in listOf(false, true)) {
            var modelOcclusion = previous
            var writes = 0
            // Run the same rule ARSceneView runs on recomposition while plane depth toggles.
            for (planes in listOf(true, false, true)) {
                resolvePlaneDepthMode(null, true, planes)
                applyModelDepthOcclusion(null) { modelOcclusion = it; writes++ }
                assertEquals(previous, modelOcclusion)
            }
            assertEquals("Implicit plane depth cannot touch a custom stream", 0, writes)
        }
    }

    @Test
    fun `explicit grouped model occlusion wins independently of every plane depth request`() {
        for (requested in listOf(null) + Config.DepthMode.values()) {
            for (planes in listOf(false, true)) {
                for (optIn in listOf(false, true)) {
                    resolvePlaneDepthMode(requested, true, planes)
                    var modelOcclusion = !optIn
                    applyModelDepthOcclusion(SceneUnderstanding(occlusion = optIn)) {
                        modelOcclusion = it
                    }
                    assertEquals(optIn, modelOcclusion)
                }
            }
        }
    }

    @Test
    fun `plane-only toggles preserve callback-disabled depth with an explicit automatic request`() {
        val requested = Config.DepthMode.AUTOMATIC
        val gate = ChangeGate(planeDepthConfigurationKey(requested, true))
        // sessionConfiguration overrides the typed AUTOMATIC request on session creation.
        var configured = Config.DepthMode.DISABLED
        var reconfigurations = 0
        listOf(true, false, true).forEach { planeOcclusion ->
            if (gate.shouldApply(planeDepthConfigurationKey(requested, planeOcclusion))) {
                configured = resolvePlaneDepthMode(requested, true, planeOcclusion)
                reconfigurations++
            }
            assertEquals(Config.DepthMode.DISABLED, configured)
        }
        assertEquals(0, reconfigurations)
    }

    @Test
    fun `plane flag never reconfigures any unchanged explicit depth mode`() {
        Config.DepthMode.values().forEach { requested ->
            val gate = ChangeGate(planeDepthConfigurationKey(requested, true))
            assertFalse(gate.shouldApply(planeDepthConfigurationKey(requested, false)))
            assertFalse(gate.shouldApply(planeDepthConfigurationKey(requested, true)))
        }
    }

    @Test
    fun `implicit depth still follows plane toggles and genuine typed changes still apply`() {
        val gate = ChangeGate(planeDepthConfigurationKey(null, true))
        assertFalse(gate.shouldApply(planeDepthConfigurationKey(null, true)))
        assertTrue(gate.shouldApply(planeDepthConfigurationKey(null, false)))
        assertTrue(gate.shouldApply(planeDepthConfigurationKey(null, true)))
        assertTrue(gate.shouldApply(planeDepthConfigurationKey(Config.DepthMode.AUTOMATIC, true)))
        assertFalse(gate.shouldApply(planeDepthConfigurationKey(Config.DepthMode.AUTOMATIC, false)))
        assertTrue(gate.shouldApply(planeDepthConfigurationKey(Config.DepthMode.DISABLED, false)))
        assertTrue(gate.shouldApply(planeDepthConfigurationKey(null, false)))
    }

    @Test
    fun `implicit depth follows support and plane opt-out`() {
        listOf(
            Triple(true, true, Config.DepthMode.AUTOMATIC),
            Triple(true, false, Config.DepthMode.DISABLED),
            Triple(false, true, Config.DepthMode.DISABLED),
            Triple(false, false, Config.DepthMode.DISABLED),
        ).forEach { (automaticDepthSupported, planeDepthOcclusion, expected) ->
            assertEquals(
                "supported=$automaticDepthSupported, planeDepthOcclusion=$planeDepthOcclusion",
                expected,
                resolvePlaneDepthMode(
                    requestedDepthMode = null,
                    automaticDepthSupported = automaticDepthSupported,
                    planeDepthOcclusion = planeDepthOcclusion,
                ),
            )
        }
    }

    @Test
    fun `every explicit depth mode wins across support and plane opt-out`() {
        Config.DepthMode.values().forEach { requestedDepthMode ->
            listOf(true, false).forEach { automaticDepthSupported ->
                listOf(true, false).forEach { planeDepthOcclusion ->
                    assertEquals(
                        "requested=$requestedDepthMode, supported=$automaticDepthSupported, " +
                            "planeDepthOcclusion=$planeDepthOcclusion",
                        requestedDepthMode,
                        resolvePlaneDepthMode(
                            requestedDepthMode = requestedDepthMode,
                            automaticDepthSupported = automaticDepthSupported,
                            planeDepthOcclusion = planeDepthOcclusion,
                        ),
                    )
                }
            }
        }
    }
}
