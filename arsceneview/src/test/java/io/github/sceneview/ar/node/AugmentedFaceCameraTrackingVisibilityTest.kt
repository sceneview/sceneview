package io.github.sceneview.ar.node

import com.google.ar.core.TrackingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression contract for #3575 — the Augmented Faces mesh was hidden one frame after it was
 * built, on every device, for the whole life of the demo.
 *
 * ARCore documents that a `Session.Feature.FRONT_CAMERA` session — the only kind Augmented Faces
 * runs on — never tracks the device pose: `Camera.getTrackingState()` **always** returns
 * [TrackingState.PAUSED], `Camera.getDisplayOrientedPose()` always returns identity, and every
 * `Frame.hitTest()` returns an empty list.
 *
 * `PoseNode` hides any node whose `cameraTrackingState` is outside `visibleCameraTrackingStates`,
 * and that set defaults to `{TRACKING}`. `AugmentedFaceNode` builds its mesh inside its own
 * constructor, while the field still holds its `TRACKING` initial value, and then writes the
 * frame's camera tracking state into it on the first `update(session, frame)` — so the mesh was
 * visible for one or two frames and invisible for the rest of the session, while the demo banner
 * kept truthfully reporting "Tracking 1 face(s)".
 *
 * Neither leg of that runtime path can execute on the JVM (ARCore's `AugmentedFace` and Filament's
 * `Engine` are both device-bound), so the fix is pinned two ways: the value of the opt-out set,
 * and its assignment in `init` **before** `trackable` is assigned — the point at which the mesh is
 * built.
 */
class AugmentedFaceCameraTrackingVisibilityTest {

    // JVM tests run with the module directory as CWD.
    private val source =
        File("src/main/java/io/github/sceneview/ar/node/AugmentedFaceNode.kt").readText()

    @Test
    fun `the opt-out set covers every tracking state, PAUSED included`() {
        assertEquals(
            "A front-camera session reports PAUSED for the device camera forever, so the face " +
                "mesh must not be gated on the camera's tracking state at all.",
            TrackingState.values().toSet(),
            kFaceVisibleCameraTrackingStates
        )
        assertTrue(
            "PAUSED is the state ARCore actually reports on a front-camera session — it is the " +
                "one membership this whole fix exists for.",
            TrackingState.PAUSED in kFaceVisibleCameraTrackingStates
        )
    }

    @Test
    fun `AugmentedFaceNode opts out of the camera-tracking visibility gate`() {
        assertTrue(
            "AugmentedFaceNode must assign `visibleCameraTrackingStates = " +
                "kFaceVisibleCameraTrackingStates` (#3575).",
            source.contains("visibleCameraTrackingStates = kFaceVisibleCameraTrackingStates")
        )
    }

    @Test
    fun `the opt-out is applied before the mesh is built`() {
        // Anchored at the start of a line, so the prose that names either assignment in a
        // comment cannot stand in for the assignment itself.
        val optOutIdx = Regex(
            """^\s*visibleCameraTrackingStates = kFaceVisibleCameraTrackingStates""",
            RegexOption.MULTILINE
        ).find(source)?.range?.first ?: -1
        val trackableIdx = Regex("""^\s*trackable = augmentedFace""", RegexOption.MULTILINE)
            .find(source)?.range?.first ?: -1
        assertTrue("Expected the visibility opt-out in the source.", optOutIdx >= 0)
        assertTrue("Expected `trackable = augmentedFace` in the init block.", trackableIdx >= 0)
        assertTrue(
            "The opt-out must run BEFORE `trackable = augmentedFace`: that assignment is what " +
                "dispatches update() and builds the face mesh, and a mesh built while the gate " +
                "still reads {TRACKING} is a mesh that Filament is told to show and then hide.",
            optOutIdx < trackableIdx
        )
    }

    @Test
    fun `the face's own tracking state is still what gates the mesh`() {
        assertTrue(
            "Opting out of the CAMERA gate must not opt out of the FACE gate — " +
                "TrackableNode.visibleTrackingStates stays at its {TRACKING} default, which is " +
                "the state that actually means \"there is a face here\".",
            !source.contains("visibleTrackingStates =")
        )
    }
}
