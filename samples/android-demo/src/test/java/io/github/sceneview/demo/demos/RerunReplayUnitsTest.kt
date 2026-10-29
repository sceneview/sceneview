package io.github.sceneview.demo.demos

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.github.sceneview.demo.demos.internal.ArDebugSession
import io.github.sceneview.demo.demos.internal.ArDebugStats
import io.github.sceneview.demo.demos.internal.DebugGroup
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The replay's settings name how far the phone went in the floor plan's units, like the plan the
 * scan opens: feet and inches in US English, metres elsewhere, with the language's decimal
 * separator. Read on the "Camera path" row as TalkBack reads it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RerunReplayUnitsTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** The "Camera path" row's description after a 14.236 m walk, under the test's locale. */
    private fun cameraPathRow(): String {
        val session = ArDebugSession().apply { stats = ArDebugStats.Empty.copy(pathMetres = 14.236f) }
        composeRule.setContent { RerunLayersSection(session) }
        composeRule.waitForIdle()
        return composeRule.onNodeWithTag(RERUN_LAYER_ROW_TAG + DebugGroup.Trail.name, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription]
            .first()
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `camera path reads feet and inches in US English`() {
        assertEquals("Camera path, 46 ft 8 in, $HINT", cameraPathRow())
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `camera path reads metres with a decimal comma in French`() {
        assertEquals("Camera path, 14,2 m, $HINT", cameraPathRow())
    }

    @Test
    @Config(qualifiers = "en-rGB")
    fun `camera path reads metres with a decimal point in British English`() {
        assertEquals("Camera path, 14.2 m, $HINT", cameraPathRow())
    }

    private companion object {
        const val HINT = "Where the phone went, stronger is later"
    }
}
