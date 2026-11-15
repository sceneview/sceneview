package io.github.sceneview.ar

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pins the native scene's wiring; the cards' actual layout is measured by Robolectric. */
class AutoPlacementGuidancePaddingContractTest {
    @Test
    fun recoveryCardsReceiveHostPadding_independentlyOfTheCoachingFlag() {
        // Composing AutoPlacementScene itself needs Filament JNI and an ARCore session.
        val source = File("src/main/java/io/github/sceneview/ar/AutoPlacementScene.kt").readText()
        val sceneCall = source.substringAfter("        ARSceneView(").substringBefore("        if (coaching")
        listOf("cameraPermissionOverlay" to "ARCameraPermissionOverlay", "arCoreAvailabilityOverlay" to "ARCoreAvailabilityOverlay")
            .forEach { (slot, overlay) ->
                assertTrue(
                    "$slot must forward the host's padding even when coaching is disabled",
                    Regex("""$slot\s*=\s*\{\s*$overlay\([^{}]*contentPadding\s*=\s*coachingContentPadding[^{}]*\)\s*}""")
                        .containsMatchIn(sceneCall),
                )
            }
    }
    @Test
    fun permissionRecoveryIsForwardedToTheHostWithoutChangingPlacementPhase() {
        val source = File("src/main/java/io/github/sceneview/ar/AutoPlacementScene.kt").readText()
        val sceneCall = source.substringAfter("        ARSceneView(").substringBefore("        if (coaching")
        assertTrue(
            "hosts must observe the same nullable permission state as the SDK card",
            Regex("""onCameraPermissionStateChanged\s*=\s*\{[^}]*cameraDenied\s*=\s*it != null[^}]*onCameraPermissionStateChanged\?\.invoke\(it\)""")
                .containsMatchIn(sceneCall),
        )
    }

}
