package io.github.sceneview.ar.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the [DepthMeshNode.MAX_UPLOAD_BUFFER_BYTES] cap introduced in #1846.
 *
 * The pre-cap implementation cached the upload [java.nio.ByteBuffer]s indefinitely — a one-off
 * oversized depth image (rare today, possible if a future ARCore exposes higher-res depth)
 * would permanently inflate the cache. The cap ensures the steady-state memory footprint
 * matches the typical stride-4 mesh from a 160×90 depth image (~12 KB) while still allowing
 * larger short-lived allocations.
 *
 * The actual [DepthMeshNode.acquireDirectBuffer] is private and Filament-coupled. This test
 * pins the **invariants** of the cap so a future refactor can't silently lower the ceiling
 * below the steady-state working set (which would defeat #1810's amortisation gain) or raise
 * it without justification.
 */
class DepthMeshNodeUploadBufferCapTest {

    @Test
    fun `MIN is at most MAX (sanity)`() {
        assertTrue(
            "MIN_UPLOAD_BUFFER_BYTES (${DepthMeshNode.MIN_UPLOAD_BUFFER_BYTES}) must be <= " +
                "MAX_UPLOAD_BUFFER_BYTES (${DepthMeshNode.MAX_UPLOAD_BUFFER_BYTES}).",
            DepthMeshNode.MIN_UPLOAD_BUFFER_BYTES <= DepthMeshNode.MAX_UPLOAD_BUFFER_BYTES,
        )
    }

    @Test
    fun `MAX is at least 1 MB so typical captures stay in the no-shrink path`() {
        // A stride-4 mesh from a 160×90 ARCore depth image is ~12 KB. A stride-2 mesh from a
        // 320×180 image is ~96 KB. A stride-1 mesh from a 640×360 image is ~768 KB. The cap
        // must stay >= 1 MB so the typical no-shrink amortisation path of #1810 covers every
        // device ARCore ships today.
        assertTrue(
            "MAX_UPLOAD_BUFFER_BYTES must be >= 1 MB to cover the typical depth-image " +
                "captures without triggering reallocation churn (got " +
                "${DepthMeshNode.MAX_UPLOAD_BUFFER_BYTES} bytes).",
            DepthMeshNode.MAX_UPLOAD_BUFFER_BYTES >= 1 * 1024 * 1024,
        )
    }

    @Test
    fun `MAX has a documented finite ceiling (not Int_MAX)`() {
        // The point of the cap is that one-off oversized images don't permanently inflate the
        // cache. Int.MAX_VALUE would defeat that. Pin a generous-but-finite ceiling so a future
        // bump beyond ~64 MB triggers a deliberate review.
        assertTrue(
            "MAX_UPLOAD_BUFFER_BYTES must be a finite cap, not effectively unbounded " +
                "(got ${DepthMeshNode.MAX_UPLOAD_BUFFER_BYTES} bytes).",
            DepthMeshNode.MAX_UPLOAD_BUFFER_BYTES <= 64 * 1024 * 1024,
        )
    }

    @Test
    fun `cap is currently 1 MB`() {
        // Regression pin: catches an accidental shrink to MIN or accidental growth past 1 MB.
        // Bumping this value is a deliberate decision that should also update this test.
        assertEquals(1 * 1024 * 1024, DepthMeshNode.MAX_UPLOAD_BUFFER_BYTES)
    }
}
