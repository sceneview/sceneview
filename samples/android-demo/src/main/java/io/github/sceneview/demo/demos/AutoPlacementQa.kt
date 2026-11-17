package io.github.sceneview.demo.demos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARCoreAvailabilityOverlay
import io.github.sceneview.ar.ARCoreAvailabilityState
import io.github.sceneview.demo.BuildConfig
import io.github.sceneview.demo.common.qaStateOverridesAllowed

/**
 * Pins One call on the SDK's session-failure card, without waiting for ARCore on an emulator:
 *
 * `adb shell am start -n io.github.sceneview.demo.qa/io.github.sceneview.demo.MainActivity --es demo ar-placement --es tab one-call --ez qa_mode true --es qa_state ar_start_failed`
 *
 * Honoured only in a debug build under [qaStateOverridesAllowed], like `motion_sensor_silent`.
 * The SDK card and its insets match [io.github.sceneview.ar.AutoPlacementScene]; Try again
 * keeps this QA state pinned so all orientation/theme captures show the same failure.
 */
internal const val AR_START_FAILED_QA_STATE = "ar_start_failed"

/** Whether this launch may pin One call on [AR_START_FAILED_QA_STATE]. */
internal fun arStartFailedForced(
    qaState: String?,
    overridesAllowed: Boolean,
    debugBuild: Boolean = BuildConfig.DEBUG,
): Boolean = debugBuild && overridesAllowed && qaState == AR_START_FAILED_QA_STATE

/** The same SDK overlay, black viewport and safe-drawing modifier used inside AutoPlacementScene. */
@Composable
internal fun AutoPlacementStartFailedQaOverlay(contentPadding: PaddingValues) {
    val state = remember { ARCoreAvailabilityState(ARCoreAvailability.SessionFailed, retry = {}) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ARCoreAvailabilityOverlay(
            state = state,
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = contentPadding,
        )
    }
}
