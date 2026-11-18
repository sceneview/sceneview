package io.github.sceneview.demo.demos.internal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.sceneview.math.Position
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A kept scan reopened: its model and its points are read side by side. When the model is on screen
 * first, the result is staged again on the points, behind its veil, and has to be let seen again
 * ([rememberResultCinematics]). On a Pixel 4a five reopenings in six stayed on an empty stage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResultStageCinematicsTest {
    @get:Rule
    val compose = createComposeRule()

    private val home = OrbitPose(
        target = Position(0.2f, 0.8f, -0.4f),
        azimuthDegrees = 35f,
        elevationDegrees = 40f,
        distance = 5.5f,
    )

    /** One stage's frames, in `SceneView`'s order; `true` when one of them let the view be seen. */
    private fun seenWithin(cinematics: ScanResultCinematics, frames: Int = FRAMES): Boolean {
        val opening = DollhouseOpening()
        opening.aim(cinematics.orbit, home)
        repeat(frames) {
            cinematics.manipulator.update(FRAME_SECONDS)
            cinematics.manipulator.getTransform()
            if (cinematics.frameDrawn(opening, home, ready = true)) return true
        }
        return false
    }

    @Test
    fun `the same cinematics lets one stage be seen, never a second`() {
        val cinematics = ScanResultCinematics(ArDebugOrbitCamera(drift = false), reveals = false)

        assertTrue(seenWithin(cinematics))
        assertFalse("what made a stage of its own necessary", seenWithin(cinematics))
    }

    @Test
    fun `a stage that starts over is let seen again`() {
        val orbit = ArDebugOrbitCamera(drift = false)
        var stage by mutableStateOf<Any>("the model, on screen first")
        lateinit var cinematics: ScanResultCinematics
        compose.setContent { cinematics = rememberResultCinematics(stage, orbit, reveals = false) }
        compose.waitForIdle()
        val first = cinematics
        assertTrue(seenWithin(first))

        stage = "the scan's points, read after it"
        compose.waitForIdle()

        assertNotSame(first, cinematics)
        assertTrue(seenWithin(cinematics))
    }

    @Test
    fun `a stage that stays keeps its cinematics`() {
        val orbit = ArDebugOrbitCamera(drift = false)
        var redraw by mutableStateOf(0)
        lateinit var cinematics: ScanResultCinematics
        compose.setContent {
            redraw
            cinematics = rememberResultCinematics("one stage", orbit, reveals = false)
        }
        compose.waitForIdle()
        val first = cinematics

        redraw++
        compose.waitForIdle()

        assertSame(first, cinematics)
    }

    private companion object {
        const val FRAME_SECONDS = 1f / 60f
        const val FRAMES = 120
    }
}
