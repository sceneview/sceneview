package io.github.sceneview.ar.arcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pure-JVM regression test for [GeospatialPoseSnapshot] — the data class returned by
 * `GeospatialPose.snapshot()` (#1769).
 *
 * ARCore's [com.google.ar.core.GeospatialPose] is JNI-only (its constructor takes a native
 * handle and a session), so the `.snapshot()` extension can't be exercised under pure-JVM
 * tests without spinning up a live AR session. What we *can* pin here is the data class's
 * shape, equality, copy semantics, and the field-name contract — preventing a silent rename
 * of `horizontalAccuracy` → `accuracyHorizontal` (or similar reordering) that would break
 * the umbrella's acceptance criterion #2: "Expose lat/long/altitude/heading/horizontalAccuracy/
 * verticalAccuracy/orientationYawAccuracy as named properties".
 */
class GeospatialPoseSnapshotTest {

    @Test
    fun `snapshot exposes all 7 fields of the umbrella acceptance criterion`() {
        val snap = GeospatialPoseSnapshot(
            latitude = 48.8584,             // Eiffel Tower
            longitude = 2.2945,
            altitude = 35.0,                // m above WGS84 ellipsoid
            heading = 12.5,                 // degrees clockwise from north
            horizontalAccuracy = 1.8,       // m — sub-2-m AR-grade
            verticalAccuracy = 2.4,
            orientationYawAccuracy = 4.2    // degrees
        )
        assertEquals(48.8584, snap.latitude, 0.0)
        assertEquals(2.2945, snap.longitude, 0.0)
        assertEquals(35.0, snap.altitude, 0.0)
        assertEquals(12.5, snap.heading, 0.0)
        assertEquals(1.8, snap.horizontalAccuracy, 0.0)
        assertEquals(2.4, snap.verticalAccuracy, 0.0)
        assertEquals(4.2, snap.orientationYawAccuracy, 0.0)
    }

    @Test
    fun `data class equality compares every field`() {
        val a = GeospatialPoseSnapshot(
            latitude = 48.0,
            longitude = 2.0,
            altitude = 35.0,
            heading = 12.5,
            horizontalAccuracy = 1.8,
            verticalAccuracy = 2.4,
            orientationYawAccuracy = 4.2
        )
        val b = a.copy()
        assertEquals(a, b)

        // Mutating any single field must break equality — pin every named property.
        assertNotEquals(a, a.copy(latitude = 48.0001))
        assertNotEquals(a, a.copy(longitude = 2.0001))
        assertNotEquals(a, a.copy(altitude = 35.0001))
        assertNotEquals(a, a.copy(heading = 12.6))
        assertNotEquals(a, a.copy(horizontalAccuracy = 1.81))
        assertNotEquals(a, a.copy(verticalAccuracy = 2.41))
        assertNotEquals(a, a.copy(orientationYawAccuracy = 4.21))
    }
}
