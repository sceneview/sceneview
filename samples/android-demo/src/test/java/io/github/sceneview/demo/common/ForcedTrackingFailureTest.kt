package io.github.sceneview.demo.common

import com.google.ar.core.TrackingFailureReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests for the [ForcedTrackingFailure] developer-debug shim (#1881).
 *
 * Pins the singleton's default state and verifies that every non-NONE
 * [TrackingFailureReason] can flow through the override slot — guards against
 * a future ARCore upgrade silently making one of the reasons unforceable.
 */
class ForcedTrackingFailureTest {

    @After
    fun clearOverride() {
        // Singleton state — leaking a forced reason between tests would mask
        // the production "no override" default and break any subsequent test.
        ForcedTrackingFailure.override = null
    }

    @Test
    fun `override defaults to null - no forced reason`() {
        assertNull(ForcedTrackingFailure.override)
    }

    @Test
    fun `every non-NONE TrackingFailureReason can be assigned to override`() {
        TrackingFailureReason.values()
            .filter { it != TrackingFailureReason.NONE }
            .forEach { reason ->
                ForcedTrackingFailure.override = reason
                assertEquals(reason, ForcedTrackingFailure.override)
            }
    }

    @Test
    fun `setting override to NONE is allowed and reads back as NONE`() {
        // NONE is a valid enum value — the menu hides it (it would be
        // redundant with "Clear override"), but the data class accepts any
        // enum value defensively.
        ForcedTrackingFailure.override = TrackingFailureReason.NONE
        assertEquals(TrackingFailureReason.NONE, ForcedTrackingFailure.override)
    }

    @Test
    fun `clearing override after setting it restores null`() {
        ForcedTrackingFailure.override = TrackingFailureReason.INSUFFICIENT_LIGHT
        assertNotNull(ForcedTrackingFailure.override)
        ForcedTrackingFailure.override = null
        assertNull(ForcedTrackingFailure.override)
    }
}
