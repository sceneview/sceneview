package io.github.sceneview.ar.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM sentinel for the camera-stream draw-priority contract that makes
 * depth occlusion actually occlude (#1617).
 *
 * The bug: [ARCameraStream] always built its renderable with Filament
 * `priority(7)` (drawn **last**). That is correct for the flat camera
 * background — an opaque quad drawn last is rejected by early-Z wherever
 * virtual geometry already drew, minimising overdraw.
 *
 * It is **wrong** for the depth-occlusion material: `camera_stream_depth.mat`
 * writes the real-world per-pixel depth via `gl_FragDepth`. For virtual
 * geometry to be hidden behind real surfaces, that real-world depth has to be
 * in the z-buffer *before* the virtual objects are depth-tested — i.e. the
 * camera quad must draw **first**. Drawing it last wrote the real-world depth
 * after every virtual object had already passed its depth test against an
 * empty buffer, so nothing was ever occluded.
 *
 * The real `RenderableManager.setPriority` is a Filament JNI call needing a
 * native engine, which a pure-JVM unit test can't load. What we pin here is
 * the *priority-selection logic* — the same `depthEnabled ? PRIME : BACKGROUND`
 * decision the setter applies.
 */
class ARCameraStreamPriorityTest {

    /** Mirror of `ARCameraStream.applyCameraStreamPriority`'s selection. */
    private fun priorityFor(depthOcclusionEnabled: Boolean): Int =
        if (depthOcclusionEnabled) {
            ARCameraStream.CAMERA_PRIORITY_DEPTH_PRIME
        } else {
            ARCameraStream.CAMERA_PRIORITY_BACKGROUND
        }

    @Test
    fun `priority constants stay inside Filament's valid 0-7 range`() {
        assertTrue(
            "background priority must be a valid Filament priority",
            ARCameraStream.CAMERA_PRIORITY_BACKGROUND in 0..7
        )
        assertTrue(
            "depth-prime priority must be a valid Filament priority",
            ARCameraStream.CAMERA_PRIORITY_DEPTH_PRIME in 0..7
        )
    }

    @Test
    fun `depth occlusion ON draws the camera quad before virtual geometry`() {
        // Filament priority 0 = drawn first. The depth material primes the
        // z-buffer with real-world depth, so it MUST precede virtual geometry.
        assertEquals(
            "depth-on must use the lowest priority so it draws first",
            0,
            priorityFor(depthOcclusionEnabled = true)
        )
    }

    @Test
    fun `depth occlusion OFF draws the camera quad last to minimise overdraw`() {
        // Filament priority 7 = drawn last. The flat opaque background is
        // early-Z rejected wherever virtual geometry already drew.
        assertEquals(
            "depth-off must use the highest priority so it draws last",
            7,
            priorityFor(depthOcclusionEnabled = false)
        )
    }

    @Test
    fun `depth-prime priority is strictly earlier than the background priority`() {
        // The whole fix hinges on this ordering: the depth camera quad has to
        // render strictly before the flat-background ordering would have it.
        assertTrue(
            "depth-prime must sort before background in Filament draw order",
            ARCameraStream.CAMERA_PRIORITY_DEPTH_PRIME < ARCameraStream.CAMERA_PRIORITY_BACKGROUND
        )
    }
}
