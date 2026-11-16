package io.github.sceneview.demo.demos.internal

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Environment retirement must follow the consumer's apply, rather than a frame-clock guess. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StudioEnvironmentRetirementTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun retiredPairsAreReleasedInTheApplyThatUnbindsThem() {
        val current = mutableStateOf("first")
        val retired = mutableListOf<String>()
        val released = mutableListOf<String>()
        var expectedReleased = emptyList<String>()
        var bound: String? = null
        composeRule.setContent {
            ReleaseRetiredStudioResources(current.value, retired) {
                assertFalse("released the environment still bound to the scene", it == bound)
                released.add(it)
            }
            // SceneView binds and unbinds its environment in a DisposableEffect too.
            val environment = current.value
            DisposableEffect(environment) {
                bound = environment
                onDispose { bound = null }
            }
            SideEffect {
                assertEquals(environment, bound)
                assertEquals(
                    "retired pairs must be released by the end of apply",
                    expectedReleased,
                    released,
                )
                assertEquals(emptyList<String>(), retired)
            }
        }

        repeat(2) { index ->
            composeRule.runOnIdle {
                expectedReleased = listOf("first", "second").take(index + 1)
                retired.add(current.value)
                current.value = if (index == 0) "second" else "third"
                Snapshot.sendApplyNotifications()
            }
            composeRule.waitForIdle()
        }
        composeRule.runOnIdle {
            assertEquals("third", bound)
            assertEquals(listOf("first", "second"), released)
        }
    }
}
