package io.github.sceneview.demo.demos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source contract for #3576 — the Augmented Faces overlay had no lighting model at all.
 *
 * The demo painted the ARCore face mesh with an **unlit** flat colour: one uniform blue across the
 * whole face, no highlight, no falloff, nothing that reads as fitted 3D topology. ARCore
 * force-disables light estimation on a front-camera session, which is why the demo had drifted to
 * unlit — but "no estimate" argues for a *deterministic* rig, not for no shading.
 *
 * A front-camera session never tracks the device pose (`Camera.getDisplayOrientedPose()` is always
 * identity), so world space is pinned to the phone and a fixed light direction is a stable,
 * camera-anchored rig. The SDK defaults are wrong for a portrait specifically: `DefaultLightNode`
 * points straight down `(0, -1, 0)`, the overhead angle that buries the eyes and the mouth.
 *
 * None of this renders on the JVM — and it cannot render on the shared emulator either, which has
 * no camera — so the wiring is pinned at the source level.
 */
class ARFaceDemoLightingContractTest {

    // JVM tests run with the module directory as CWD.
    private val source =
        File("src/main/java/io/github/sceneview/demo/demos/ARFaceDemo.kt").readText()

    @Test
    fun `the face mesh uses a lit material`() {
        assertTrue(
            "ARFaceDemo must build its face material with `rememberMaterialInstance` (lit PBR).",
            source.contains("rememberMaterialInstance(")
        )
        assertFalse(
            "The unlit flat-colour overlay is the #3576 defect — it cannot come back without " +
                "this contract being revisited.",
            source.contains("rememberUnlitMaterialInstance")
        )
    }

    @Test
    fun `a lit material must get its per-frame tangent quaternions`() {
        assertTrue(
            "PBR samples the FLOAT4 TANGENTS attribute; `computeTangents = false` is the " +
                "unlit-only shortcut (#878) and leaves a lit face mesh with undefined shading.",
            source.contains("computeTangents = true")
        )
        // Anchored: the comment above the call names the old value on purpose.
        assertFalse(
            "`computeTangents = false` must not come back on a lit face mesh.",
            Regex("""^\s*computeTangents = false""", RegexOption.MULTILINE).containsMatchIn(source)
        )
    }

    @Test
    fun `the demo installs its own key and fill lights`() {
        assertTrue(
            "The demo must override the SDK's straight-down default rig with a portrait rig.",
            source.contains("mainLightNode = rememberMainLightNode(") &&
                source.contains("fillLightNode = rememberFillLightNode(")
        )
    }

    @Test
    fun `the key light points out of the screen at the user, not straight down`() {
        val key = source.substringAfter("mainLightNode = rememberMainLightNode(")
            .substringBefore("},")
        val z = Regex("""z\s*=\s*(-?[\d.]+)f""").find(key)?.groupValues?.get(1)?.toFloat()
        assertTrue(
            "The key light's direction must travel along -Z — out of the screen and into the " +
                "user's face. Found z=$z.",
            z != null && z < 0f
        )
        val y = Regex("""y\s*=\s*(-?[\d.]+)f""").find(key)?.groupValues?.get(1)?.toFloat()
        assertTrue(
            "The key light must not be the straight-down (0, -1, 0) default that buries the " +
                "eyes and mouth in shadow. Found y=$y.",
            y != null && y > -1f
        )
    }

    @Test
    fun `the face tint comes from a design token, never a hardcoded colour`() {
        assertTrue(
            "DESIGN.md: demo UI never hardcodes colours — the face tint is a SceneViewColors token.",
            source.contains("SceneViewColors.FaceMeshOverlay")
        )
        assertFalse(
            "No raw ARGB literal in the demo's material setup.",
            Regex("""Color\(0x[0-9A-Fa-f]{8}\)""").containsMatchIn(source)
        )
    }
}
