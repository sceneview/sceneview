package io.github.sceneview.ar.node

import com.google.ar.core.TrackingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Regression guard for #3575, whose ARCore and Filament path is device-bound. */
class AugmentedFaceCameraTrackingVisibilityTest {

    @Test
    fun `the face visibility policy accepts every camera tracking state`() {
        assertEquals(TrackingState.values().toSet(), kFaceVisibleCameraTrackingStates)
        assertTrue(TrackingState.PAUSED in kFaceVisibleCameraTrackingStates)
    }

    @Test
    fun `AugmentedFaceNode installs the policy before its trackable`() {
        val source = File("src/main/java/io/github/sceneview/ar/node/AugmentedFaceNode.kt")
            .readText()
            .replace(Regex("""/\*[\s\S]*?\*/|//[^\n]*"""), "")
            .replace(Regex("""\s+"""), "")
        val policy = source.indexOf("visibleCameraTrackingStates=kFaceVisibleCameraTrackingStates")
        val trackable = source.indexOf("trackable=augmentedFace", startIndex = policy.coerceAtLeast(0))
        assertTrue(
            "The camera policy must be installed before trackable dispatch builds the mesh",
            policy >= 0 && trackable > policy,
        )
    }
}
