package io.github.sceneview.ar.physics

import dev.romainguy.kotlin.math.Mat4
import dev.romainguy.kotlin.math.translation
import io.github.sceneview.ar.node.DepthMeshSnapshot
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the [DepthCollider.ingestSnapshot] path without spinning up Filament: builds a synthetic
 * [DepthMeshSnapshot], hands it to the collider via the `internal` ingest method, then asserts the
 * collider returns sane world-space surface Y values.
 *
 * The full composable + ARCore wiring is exercised on-device in `samples/android-demo` —
 * `DepthColliderDemo` is the visual acceptance — but these JVM tests catch the common regressions
 * (forgotten transform application, stale snapshot kept after destroy, region-cull defeats the
 * lookup) that would otherwise only show up on depth-capable hardware.
 */
class DepthColliderIngestTest {

    private fun snapshot(
        positions: FloatArray,
        indices: IntArray,
        transform: Mat4 = Mat4.identity(),
        timestampMs: Long = 0L,
        width: Int = 0,
        height: Int = 0,
    ) = DepthMeshSnapshot(
        positions = positions,
        indices = indices,
        width = width,
        height = height,
        worldTransform = transform,
        timestampMs = timestampMs,
    )

    @Test
    fun `floorYAt returns null before any rebuild has landed`() {
        // A bare collider with no DepthMeshNode wiring — we don't need Filament for this test.
        // We never call ingestSnapshot, so floorYAt must return null and the body falls back to
        // its static floor.
        val collider = newInertCollider()
        assertNull(collider.floorYAt(0f, 0f, 0f, radius = 0.1f))
        assertEquals(0, collider.triangleCount)
    }

    @Test
    fun `ingest with identity transform stores world positions equal to camera positions`() {
        val collider = newInertCollider()
        val cam = floatArrayOf(
            -0.5f, 0f, -0.5f,
            0.5f, 0f, -0.5f,
            0f, 0f, 0.5f,
        )
        collider.ingestSnapshot(snapshot(positions = cam, indices = intArrayOf(0, 1, 2)))
        val y = collider.floorYAt(0f, 5f, -0.1f, radius = 0.05f)
        assertNotNull(y)
        assertEquals(0.0f, y!!, 1e-4f)
        assertEquals(1, collider.triangleCount)
    }

    @Test
    fun `ingest with translated transform shifts the surface up by the translation`() {
        val collider = newInertCollider()
        val cam = floatArrayOf(
            -0.5f, 0f, -0.5f,
            0.5f, 0f, -0.5f,
            0f, 0f, 0.5f,
        )
        // World transform places the camera 2 m above the origin → the depth mesh's y=0 plane
        // becomes the y=2 plane in world space.
        val t = translation(Position(0f, 2f, 0f))
        collider.ingestSnapshot(snapshot(positions = cam, indices = intArrayOf(0, 1, 2), transform = t))
        val y = collider.floorYAt(0f, 10f, -0.1f, radius = 0.05f)
        assertNotNull(y)
        assertEquals(2.0f, y!!, 1e-4f)
    }

    @Test
    fun `successive ingests replace the active snapshot`() {
        val collider = newInertCollider()
        // First ingest: floor at y=0.
        collider.ingestSnapshot(
            snapshot(
                positions = floatArrayOf(-1f, 0f, -1f, 1f, 0f, -1f, 0f, 0f, 1f),
                indices = intArrayOf(0, 1, 2),
                timestampMs = 1L,
            ),
        )
        assertEquals(0.0f, collider.floorYAt(0f, 0f, 0f, 0.05f)!!, 1e-4f)

        // Second ingest: completely new geometry — floor at y=1. The previous snapshot must be
        // entirely replaced, not merged.
        collider.ingestSnapshot(
            snapshot(
                positions = floatArrayOf(-1f, 1f, -1f, 1f, 1f, -1f, 0f, 1f, 1f),
                indices = intArrayOf(0, 1, 2),
                timestampMs = 2L,
            ),
        )
        assertEquals(1.0f, collider.floorYAt(0f, 0f, 0f, 0.05f)!!, 1e-4f)
        assertEquals(2L, collider.lastRebuildTimestampMs)
    }

    @Test
    fun `setBodiesRegion to null disables culling`() {
        val collider = newInertCollider()
        // Region restricting us to (10, 10, 10) — far from any body, but we pass null to disable.
        collider.setBodiesRegion(null, padding = 0f)
        collider.ingestSnapshot(
            snapshot(
                positions = floatArrayOf(-1f, 0f, -1f, 1f, 0f, -1f, 0f, 0f, 1f),
                indices = intArrayOf(0, 1, 2),
            ),
        )
        val y = collider.floorYAt(0f, 5f, 0f, 0.05f)
        assertNotNull(y)
    }

    @Test
    fun `setBodiesRegion narrows the active triangle set`() {
        val collider = newInertCollider()
        // Body cluster sits at origin — region centred on (10, 0, 10) drops the triangle at origin.
        collider.setBodiesRegion(centres = floatArrayOf(10f, 0f, 10f), padding = 0.5f)
        collider.ingestSnapshot(
            snapshot(
                positions = floatArrayOf(-1f, 0f, -1f, 1f, 0f, -1f, 0f, 0f, 1f),
                indices = intArrayOf(0, 1, 2),
            ),
        )
        // The single triangle was culled — querying at the origin yields no surface.
        assertEquals(0, collider.triangleCount)
        assertNull(collider.floorYAt(0f, 0f, 0f, 0.05f))
    }

    // Input validation (#1812): defensive guards on public API.

    @Test
    fun `setBodiesRegion throws when centres size is not a multiple of 3`() {
        val collider = newInertCollider()
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            // 4 floats — not a valid flat-packed [x,y,z,...] triplet.
            collider.setBodiesRegion(centres = floatArrayOf(1f, 2f, 3f, 4f), padding = 0.1f)
        }
    }

    @Test
    fun `setBodiesRegion falls back to disabled culling on non-finite centres`() {
        val collider = newInertCollider()
        // NaN centre would produce a non-finite region that culls every triangle silently.
        // Defensive path: disable region culling instead of poisoning the collider.
        collider.setBodiesRegion(centres = floatArrayOf(Float.NaN, 0f, 0f), padding = 0.1f)
        collider.ingestSnapshot(
            snapshot(
                positions = floatArrayOf(-1f, 0f, -1f, 1f, 0f, -1f, 0f, 0f, 1f),
                indices = intArrayOf(0, 1, 2),
            ),
        )
        // With region disabled, the triangle remains in play.
        assertEquals(1, collider.triangleCount)
        assertNotNull(collider.floorYAt(0f, 5f, 0f, 0.05f))
    }

    @Test
    fun `destroy clears the snapshot and the listener`() {
        val collider = newInertCollider()
        collider.ingestSnapshot(
            snapshot(
                positions = floatArrayOf(-1f, 0f, -1f, 1f, 0f, -1f, 0f, 0f, 1f),
                indices = intArrayOf(0, 1, 2),
            ),
        )
        assertEquals(1, collider.triangleCount)
        collider.destroy()
        assertEquals(0, collider.triangleCount)
        assertNull(collider.floorYAt(0f, 0f, 0f, 0.05f))
    }

    /**
     * Builds a [DepthCollider] that never receives mesh rebuilds via its native [io.github.sceneview.ar.node.DepthMeshNode]
     * subscription. The tests drive [DepthCollider.ingestSnapshot] directly — that avoids the
     * Filament Engine that would otherwise be required to instantiate a real DepthMeshNode.
     */
    private fun newInertCollider(): DepthCollider = DepthCollider.forTesting()
}
