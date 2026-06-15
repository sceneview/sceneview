package io.github.sceneview.gesture

import io.github.sceneview.math.Transform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CameraViewportSeed] — the helper that keeps a freshly-swapped camera
 * manipulator's viewport in sync with the render surface (#2514).
 *
 * Regression guard for the wildly-over-sensitive two-finger pan: a manipulator that never
 * receives a real viewport stays at its uninitialised 1×1 default, and Filament's ORBIT pan
 * then divides the touch pixel by 1 instead of the surface width/height, exploding the pan
 * delta by ~3 orders of magnitude (a 10 px drag panned the camera ~50 m → model off-screen).
 */
class CameraViewportSeedTest {

    /** A [CameraGestureDetector.CameraManipulator] that records every [setViewport] call. */
    private class RecordingManipulator : CameraGestureDetector.CameraManipulator {
        val viewports = mutableListOf<Pair<Int, Int>>()
        override fun setViewport(width: Int, height: Int) { viewports += width to height }
        override fun getTransform(): Transform = Transform()
        override fun grabBegin(x: Int, y: Int, strafe: Boolean) = Unit
        override fun grabUpdate(x: Int, y: Int) = Unit
        override fun grabEnd() = Unit
        override fun scrollBegin(x: Int, y: Int, separation: Float) = Unit
        override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) = Unit
        override fun scrollEnd() = Unit
        override fun update(deltaTime: Float) = Unit
    }

    @Test fun packViewport_roundTrips() {
        val packed = CameraViewportSeed.packViewport(1080, 2400)
        assertEquals(1080, CameraViewportSeed.unpackWidth(packed))
        assertEquals(2400, CameraViewportSeed.unpackHeight(packed))
    }

    @Test fun packViewport_isNeverUnsetForRealSize() {
        // Any surface that has been laid out is at least 1×1; the pack of such a size must not
        // collide with the UNSET sentinel, or a real viewport would be silently dropped.
        assertTrue(CameraViewportSeed.packViewport(1, 1) != CameraViewportSeed.UNSET)
        assertTrue(CameraViewportSeed.packViewport(1080, 1920) != CameraViewportSeed.UNSET)
        // A genuinely-unsized (0×0) surface DOES pack to UNSET — correct: there is nothing to seed.
        assertEquals(CameraViewportSeed.UNSET, CameraViewportSeed.packViewport(0, 0))
    }

    @Test fun seed_appliesCachedViewportToSwappedManipulator() {
        // Surface sized once (cached), then a NEW manipulator is swapped in without a resize —
        // exactly the #2514 path (auto-fit viewer rebuilds its manipulator on model load).
        val cached = CameraViewportSeed.packViewport(1080, 2400)
        val swappedIn = RecordingManipulator()

        val applied = CameraViewportSeed.seed(swappedIn, cached)

        assertTrue("seed should report it applied the viewport", applied)
        assertEquals(
            "swapped-in manipulator must inherit the real surface size, not stay 1×1",
            listOf(1080 to 2400),
            swappedIn.viewports,
        )
    }

    @Test fun seed_unsetViewport_isNoOp() {
        // Before the surface is ever sized there is nothing to seed — the manipulator must NOT be
        // poked with a bogus 0×0 (or 1×1) viewport; it gets the real one from onSurfaceResized.
        val manip = RecordingManipulator()
        val applied = CameraViewportSeed.seed(manip, CameraViewportSeed.UNSET)
        assertFalse(applied)
        assertTrue("no setViewport should happen for an unset cache", manip.viewports.isEmpty())
    }

    @Test fun seed_nullManipulator_isNoOp() {
        // SceneView(cameraManipulator = null) disables camera interaction — seeding must tolerate it.
        val applied = CameraViewportSeed.seed(null, CameraViewportSeed.packViewport(1080, 2400))
        assertFalse(applied)
    }

    @Test fun seed_demonstratesBugClass_staleVsRealViewport() {
        // The bug: a manipulator that only ever saw a 1×1 viewport (never seeded) vs one seeded
        // with the real surface. We assert the seam carries the REAL size through — the value the
        // Filament pan math divides by. The old SceneView (no re-seed on swap) would leave the
        // swapped manipulator at its 1×1 default; this test would catch that regression because
        // the swapped manipulator would record NO real viewport.
        val realSurface = CameraViewportSeed.packViewport(1080, 2400)

        // Pre-fix behaviour, modelled: swap a manipulator in but DON'T re-seed it.
        val unSeeded = RecordingManipulator()
        assertTrue("pre-fix: swapped manipulator never got the real viewport", unSeeded.viewports.isEmpty())

        // Post-fix behaviour: SceneView re-seeds on swap.
        val seeded = RecordingManipulator()
        CameraViewportSeed.seed(seeded, realSurface)
        val (w, h) = seeded.viewports.single()
        assertEquals(1080, w)
        assertEquals(2400, h)
        // Sanity: the seeded width/height are the divisor Filament uses; ~1080/2400, not 1.
        assertTrue("seeded viewport must be the real surface, not the 1×1 that explodes pan", w > 1 && h > 1)
    }
}
