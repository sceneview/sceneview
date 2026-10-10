package io.github.sceneview.demo.demos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source contract for #4346 — Reset must wake the parked Contact Shadow scene.
 *
 * [ContactShadowCameraRenderEffectTest] proves the extracted effect asks for a frame when the home
 * orbit is rebuilt, but it composes the effect on its own: deleting the call from
 * `ContactShadowPreviewDemo` would leave it green while the screen went back to waiting for a
 * touch. The demo body needs a Filament engine and cannot be composed on the JVM, so the wiring
 * itself is pinned at the source level, the way `ARFaceDemoLightingContractTest` pins its own.
 */
class ContactShadowResetRenderWiringTest {

    // JVM tests run with the module directory as CWD.
    private val source =
        File("src/main/java/io/github/sceneview/demo/demos/ContactShadowPreviewDemo.kt").readText()

    /** The demo composable only: from its signature to the next top-level declaration. */
    private val demoBody: String = source
        .substringAfter("fun ContactShadowPreviewDemo(", missingDelimiterValue = "")
        .substringBefore("\n}\n")

    @Test
    fun `the demo body is found`() {
        assertTrue(
            "ContactShadowPreviewDemo was renamed or moved — point this contract at it.",
            demoBody.contains("SceneView(")
        )
    }

    @Test
    fun `the demo asks for a frame when the home orbit is rebuilt`() {
        val call = Regex(
            """RequestContactShadowCameraRenderOnHomeChange\(\s*""" +
                """cameraHomeGeneration = demoState\.cameraHomeGeneration,\s*""" +
                """homeShot = homeShot,\s*""" +
                """requestRender = renderInvalidator::requestRender,\s*\)"""
        )
        assertEquals(
            "ContactShadowPreviewDemo must call RequestContactShadowCameraRenderOnHomeChange " +
                "once, keyed on the reset generation and the home shot: without it Reset " +
                "rebuilds the orbit but the parked scene keeps its last picture (#4346).",
            1,
            call.findAll(demoBody).count()
        )
    }

    @Test
    fun `the frame is requested on the invalidator the scene listens to`() {
        assertTrue(
            "The invalidator woken on Reset must be the one handed to SceneView.",
            demoBody.contains("val renderInvalidator = rememberRenderInvalidator()") &&
                Regex("""SceneView\([^{]*?renderInvalidator = renderInvalidator,""")
                    .containsMatchIn(demoBody)
        )
    }
}
