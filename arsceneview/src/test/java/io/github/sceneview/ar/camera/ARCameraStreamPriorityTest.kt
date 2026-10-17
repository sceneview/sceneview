package io.github.sceneview.ar.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM sentinel for the camera-stream draw-priority contract that makes
 * occlusion actually occlude (#1617).
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
 * Occluder meshes (`MaterialLoader.createOcclusionInstance`, enabled through
 * `ARCameraStream.isOccluderMeshEnabled`) need the same ordering for the
 * opposite reason: the mesh writes depth and no colour, so a feed drawn after
 * it fails the depth test there and leaves a black hole.
 *
 * The real `RenderableManager.setPriority` is a Filament JNI call needing a
 * native engine, which a pure-JVM unit test can't load. What we pin here is
 * the *priority-selection logic* — [cameraStreamPriority], the exact function
 * the setters call.
 */
class ARCameraStreamPriorityTest {

    /** Filament's priority when a renderable never calls `priority(…)`. */
    private val defaultRenderablePriority = 4

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
        assertTrue(
            "occluder priority must be a valid Filament priority",
            ARCameraStream.OCCLUDER_PRIORITY in 0..7
        )
    }

    @Test
    fun `depth occlusion ON draws the camera quad before virtual geometry`() {
        // Filament priority 0 = drawn first. The depth material primes the
        // z-buffer with real-world depth, so it MUST precede virtual geometry.
        assertEquals(
            "depth-on must use the lowest priority so it draws first",
            0,
            cameraStreamPriority(depthOcclusion = true, personOcclusion = false, occluderMesh = false)
        )
    }

    @Test
    fun `people occlusion ON draws the camera quad first`() {
        assertEquals(
            0,
            cameraStreamPriority(depthOcclusion = false, personOcclusion = true, occluderMesh = false)
        )
    }

    @Test
    fun `occluder mesh ON draws the camera quad first so occluders cut into the feed`() {
        assertEquals(
            "an occluder mesh drawn over an already-drawn feed shows the feed, not a hole",
            0,
            cameraStreamPriority(depthOcclusion = false, personOcclusion = false, occluderMesh = true)
        )
    }

    @Test
    fun `every occlusion mode OFF draws the camera quad last to minimise overdraw`() {
        // Filament priority 7 = drawn last. The flat opaque background is
        // early-Z rejected wherever virtual geometry already drew.
        assertEquals(
            "occlusion-off must use the highest priority so it draws last",
            7,
            cameraStreamPriority(depthOcclusion = false, personOcclusion = false, occluderMesh = false)
        )
    }

    @Test
    fun `any combination with an occlusion mode ON draws the camera quad first`() {
        val flags = listOf(false, true)
        for (depth in flags) for (person in flags) for (occluder in flags) {
            if (!depth && !person && !occluder) continue
            assertEquals(
                "depth=$depth person=$person occluder=$occluder",
                ARCameraStream.CAMERA_PRIORITY_DEPTH_PRIME,
                cameraStreamPriority(depth, person, occluder)
            )
        }
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

    @Test
    fun `occluder draws after the camera feed and before default-priority content`() {
        assertTrue(
            "the occluder must write depth over a feed that is already drawn",
            ARCameraStream.OCCLUDER_PRIORITY > ARCameraStream.CAMERA_PRIORITY_DEPTH_PRIME
        )
        assertTrue(
            "the occluder must write depth before the content it hides is drawn",
            ARCameraStream.OCCLUDER_PRIORITY < defaultRenderablePriority
        )
    }
}
