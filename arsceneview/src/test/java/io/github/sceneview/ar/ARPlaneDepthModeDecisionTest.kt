package io.github.sceneview.ar

import com.google.ar.core.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ARPlaneDepthModeDecisionTest {
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
