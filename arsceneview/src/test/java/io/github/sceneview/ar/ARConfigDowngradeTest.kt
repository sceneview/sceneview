package io.github.sceneview.ar

import com.google.ar.core.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM regression test for the AR config-downgrade detection landed in #2096.
 *
 * `ARScene` silently downgrades a requested camera config / depth mode that the device cannot
 * honour. The diff that turns those silent downgrades into observable [ARConfigDowngrade] events
 * is centralised in [detectConfigDowngrades] — a free function with no live ARCore `Session`
 * dependency — so this test pins the contract:
 *
 *  - a request honoured 1:1 produces no event;
 *  - an unsupported depth mode that fell back to `DISABLED` produces a [ARConfigDowngrade.DepthMode]
 *    carrying both the requested and the effective value;
 *  - a `null` request (the app asked for nothing) can never downgrade.
 *
 * The camera-config branch is exercised through its `null` guards only — `CameraConfig` is a
 * final, JNI-backed ARCore class that cannot be instantiated under a pure-JVM test; its
 * value-comparison branch is identical in shape to the depth-mode one verified here.
 */
class ARConfigDowngradeTest {

    @Test
    fun `no downgrade when depth mode honoured`() {
        val result = detectConfigDowngrades(
            requestedDepthMode = Config.DepthMode.AUTOMATIC,
            effectiveDepthMode = Config.DepthMode.AUTOMATIC,
            requestedCameraConfig = null,
            effectiveCameraConfig = null
        )
        assertTrue("A honoured request must produce no downgrade event.", result.isEmpty())
    }

    @Test
    fun `depth mode downgrade is reported with requested and effective values`() {
        val result = detectConfigDowngrades(
            requestedDepthMode = Config.DepthMode.AUTOMATIC,
            effectiveDepthMode = Config.DepthMode.DISABLED,
            requestedCameraConfig = null,
            effectiveCameraConfig = null
        )
        assertEquals(1, result.size)
        val downgrade = result.single()
        assertTrue(downgrade is ARConfigDowngrade.DepthMode)
        downgrade as ARConfigDowngrade.DepthMode
        assertEquals(Config.DepthMode.AUTOMATIC, downgrade.requested)
        assertEquals(Config.DepthMode.DISABLED, downgrade.effective)
    }

    @Test
    fun `requesting DISABLED depth mode never downgrades`() {
        // DISABLED is the floor — it is always supported, so it must never be reported.
        val result = detectConfigDowngrades(
            requestedDepthMode = Config.DepthMode.DISABLED,
            effectiveDepthMode = Config.DepthMode.DISABLED,
            requestedCameraConfig = null,
            effectiveCameraConfig = null
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `null requested depth mode never downgrades`() {
        // The app passed `depthMode = null` — it requested nothing, so nothing can be downgraded.
        val result = detectConfigDowngrades(
            requestedDepthMode = null,
            effectiveDepthMode = Config.DepthMode.DISABLED,
            requestedCameraConfig = null,
            effectiveCameraConfig = null
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `null requested camera config never downgrades`() {
        // No `sessionCameraConfig` selector was supplied — the camera-config branch must be inert.
        val result = detectConfigDowngrades(
            requestedDepthMode = null,
            effectiveDepthMode = Config.DepthMode.DISABLED,
            requestedCameraConfig = null,
            effectiveCameraConfig = null
        )
        assertTrue(result.isEmpty())
    }
}
