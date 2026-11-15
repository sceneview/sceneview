package io.github.sceneview.demo.common

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import io.github.sceneview.demo.BuildConfig
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The actual shared card replaces competing overlays and offers no in-app retry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MotionSensorScaffoldTest {
    @get:Rule val compose = createComposeRule()

    @After fun tearDown() {
        DemoSettings.qaMode = false
        DemoSettings.qaDemoState = null
    }

    @Test fun restartCardLight() = card(dark = false)
    @Test fun restartCardDark() = card(dark = true)
    @Test fun pinnedCardRemainsCapturableWhenTheEmulatorCannotStartAr() = card(dark = false, unavailable = true)

    @Test fun ordinaryStartupKeepsItsGuidance() {
        DemoSettings.qaMode = false
        DemoSettings.qaDemoState = null
        compose.setContent {
            SceneViewDemoTheme {
                DemoScaffold(
                    title = "AR Plane Node",
                    onBack = {},
                    monitorArMotionSensors = true,
                    topOverlay = { Text("Plane count") },
                    bottomOverlay = { Text("Move your phone") },
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
        compose.onNodeWithText("Plane count").assertIsDisplayed()
        compose.onNodeWithText("Move your phone").assertIsDisplayed()
        compose.onNodeWithText("Restart your phone").assertDoesNotExist()
    }

    private fun card(dark: Boolean, unavailable: Boolean = false) {
        assumeTrue(BuildConfig.DEBUG)
        DemoSettings.qaMode = true
        DemoSettings.qaDemoState = MOTION_SENSOR_SILENT_QA_STATE
        var sceneYielded = false
        compose.setContent {
            SceneViewDemoTheme(darkTheme = dark) {
                DemoScaffold(
                    title = "AR Plane Node",
                    onBack = {},
                    monitorArMotionSensors = true,
                    arSessionFailed = unavailable,
                    arOverlaysEnabled = !unavailable,
                    topOverlay = { Text("Plane count") },
                    bottomOverlay = { Text("Move your phone") },
                ) {
                    check(!unavailable) { "The unavailable scene must yield to the pinned QA card" }
                    sceneYielded = LocalMotionSensorSilent.current
                    Box(Modifier.fillMaxSize().background(Color.DarkGray))
                }
            }
        }
        compose.onNodeWithText("Restart your phone").assertIsDisplayed()
        compose.onNodeWithText("Its motion sensor isn't responding, and AR can't start without it.").assertIsDisplayed()
        compose.onNodeWithText("Plane count").assertDoesNotExist()
        compose.onNodeWithText("Move your phone").assertDoesNotExist()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        if (!unavailable) assertTrue("scene coaching was not told to yield", sceneYielded)
        val variant = if (unavailable) "unavailable" else if (dark) "dark" else "light"
        val file = File("build/reports/motion-sensors/restart-$variant.png")
        file.parentFile.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
