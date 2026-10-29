package io.github.sceneview.ar.arcore

import com.google.ar.core.Config
import com.google.ar.core.Config.LightEstimationMode.AMBIENT_INTENSITY
import com.google.ar.core.Config.LightEstimationMode.DISABLED
import com.google.ar.core.Config.LightEstimationMode.ENVIRONMENTAL_HDR
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM test for the light-estimation support gate in [ARSession.configure].
 *
 * ARCore throws `UnsupportedConfigurationException` when a session is configured with a light
 * estimation mode the device cannot run. Environmental HDR is the default and the richest mode;
 * a device without it must step down to the ambient-intensity estimate (one colour and one
 * intensity, still better than a fixed light) and only then to no estimation at all.
 */
class ResolveLightEstimationModeTest {

    private fun supports(vararg modes: Config.LightEstimationMode): (Config.LightEstimationMode) -> Boolean =
        { it in modes }

    @Test
    fun `HDR is kept when the device supports it`() {
        assertEquals(ENVIRONMENTAL_HDR, resolveLightEstimationMode(ENVIRONMENTAL_HDR, supports(ENVIRONMENTAL_HDR, AMBIENT_INTENSITY)))
    }

    @Test
    fun `HDR steps down to ambient intensity when HDR is unsupported`() {
        assertEquals(AMBIENT_INTENSITY, resolveLightEstimationMode(ENVIRONMENTAL_HDR, supports(AMBIENT_INTENSITY)))
    }

    @Test
    fun `HDR steps down to disabled when no estimation mode is supported`() {
        assertEquals(DISABLED, resolveLightEstimationMode(ENVIRONMENTAL_HDR, supports()))
    }

    @Test
    fun `ambient intensity is kept when supported`() {
        assertEquals(AMBIENT_INTENSITY, resolveLightEstimationMode(AMBIENT_INTENSITY, supports(AMBIENT_INTENSITY)))
    }

    @Test
    fun `ambient intensity never upgrades to HDR`() {
        // An app that asked for the cheaper mode keeps it, even on an HDR-capable device.
        assertEquals(DISABLED, resolveLightEstimationMode(AMBIENT_INTENSITY, supports(ENVIRONMENTAL_HDR)))
    }

    @Test
    fun `disabled is returned without probing the device`() {
        var probes = 0
        val result = resolveLightEstimationMode(DISABLED) { probes++; true }
        assertEquals(DISABLED, result)
        assertEquals(0, probes)
    }

    @Test
    fun `supported HDR is probed once and nothing else`() {
        val probed = mutableListOf<Config.LightEstimationMode>()
        resolveLightEstimationMode(ENVIRONMENTAL_HDR) { probed += it; true }
        assertEquals(listOf(ENVIRONMENTAL_HDR), probed)
    }
}
