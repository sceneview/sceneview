package io.github.sceneview.ar.arcore

import com.google.ar.core.Config
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM regression test for the Flash Mode support gate landed in #1732.
 *
 * ARCore's `Session.isFlashModeSupported()` is a JNI call that cannot be mocked under pure-JVM
 * tests, so the decision logic was extracted into [resolveFlashMode] — this test pins the
 * support-gate contract:
 *
 *  - `OFF` is always honoured.
 *  - `TORCH` is honoured when the adapter reports it as supported.
 *  - `TORCH` silently downgrades to `OFF` when the adapter reports it as unsupported (front
 *    camera, ARCore < 1.45 runtime, or device without a back-camera LED).
 */
class ResolveFlashModeTest {

    @Test
    fun `OFF is returned unchanged on supported devices`() {
        val result = resolveFlashMode(Config.FlashMode.OFF, isSupported = { true })
        assertEquals(Config.FlashMode.OFF, result)
    }

    @Test
    fun `OFF is returned unchanged on unsupported devices`() {
        // The support-check is skipped for OFF — apps must be able to leave the torch off
        // regardless of device capability.
        val result = resolveFlashMode(Config.FlashMode.OFF, isSupported = { false })
        assertEquals(Config.FlashMode.OFF, result)
    }

    @Test
    fun `TORCH is returned when supported`() {
        val result = resolveFlashMode(Config.FlashMode.TORCH, isSupported = { true })
        assertEquals(Config.FlashMode.TORCH, result)
    }

    @Test
    fun `TORCH downgrades to OFF when unsupported`() {
        val result = resolveFlashMode(Config.FlashMode.TORCH, isSupported = { false })
        assertEquals(Config.FlashMode.OFF, result)
    }

    @Test
    fun `support adapter is only invoked for non-OFF modes`() {
        var invocations = 0
        resolveFlashMode(Config.FlashMode.OFF, isSupported = {
            invocations++
            true
        })
        assertEquals(
            "isFlashModeSupported must not be invoked for OFF — calling it on devices without " +
                "Flash Mode runtime support could surface an unexpected throw.",
            0,
            invocations
        )
    }
}
