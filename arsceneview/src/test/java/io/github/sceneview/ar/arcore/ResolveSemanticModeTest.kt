package io.github.sceneview.ar.arcore

import com.google.ar.core.Config
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM regression test for the Scene Semantics support gate landed in #1730.
 *
 * ARCore's `Session.isSemanticModeSupported()` is a JNI call that cannot be mocked under pure-JVM
 * tests, so the decision logic was extracted into [resolveSemanticMode] — this test pins the
 * support-gate contract:
 *
 *  - `DISABLED` is always honoured.
 *  - `ENABLED` is honoured when the adapter reports it as supported.
 *  - `ENABLED` silently downgrades to `DISABLED` when the adapter reports it as unsupported
 *    (device without the on-device semantics model, front-camera session, or runtime older than
 *    ARCore 1.41).
 */
class ResolveSemanticModeTest {

    @Test
    fun `DISABLED is returned unchanged on supported devices`() {
        val result = resolveSemanticMode(Config.SemanticMode.DISABLED, isSupported = { true })
        assertEquals(Config.SemanticMode.DISABLED, result)
    }

    @Test
    fun `DISABLED is returned unchanged on unsupported devices`() {
        // The support-check is skipped for DISABLED — apps must be able to leave semantics off
        // regardless of device capability.
        val result = resolveSemanticMode(Config.SemanticMode.DISABLED, isSupported = { false })
        assertEquals(Config.SemanticMode.DISABLED, result)
    }

    @Test
    fun `ENABLED is returned when supported`() {
        val result = resolveSemanticMode(Config.SemanticMode.ENABLED, isSupported = { true })
        assertEquals(Config.SemanticMode.ENABLED, result)
    }

    @Test
    fun `ENABLED downgrades to DISABLED when unsupported`() {
        val result = resolveSemanticMode(Config.SemanticMode.ENABLED, isSupported = { false })
        assertEquals(Config.SemanticMode.DISABLED, result)
    }

    @Test
    fun `support adapter is only invoked for non-DISABLED modes`() {
        var invocations = 0
        resolveSemanticMode(Config.SemanticMode.DISABLED, isSupported = {
            invocations++
            true
        })
        assertEquals(
            "isSemanticModeSupported must not be invoked for DISABLED — calling it on devices " +
                "without the Scene Semantics ML model could surface an unexpected throw.",
            0,
            invocations
        )
    }
}
