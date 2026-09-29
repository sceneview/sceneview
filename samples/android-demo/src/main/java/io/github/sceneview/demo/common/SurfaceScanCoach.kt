package io.github.sceneview.demo.common

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.sceneview.ar.ArGuidanceCue
import io.github.sceneview.ar.ArGuidanceState
import kotlinx.coroutines.delay

/**
 * The animated AR coaching for a demo that looks for a surface itself — a raw `ARSceneView`
 * with its own plane or hit-test logic, rather than the placement flow, which gets the same
 * glyph from `rememberArGuidanceState`.
 *
 * It is the guidance of the 3D AR Model Viewer app, on the SDK's `ARCoachingOverlay`: a phone
 * sweeping over the floor with "Move your phone slowly over the floor or a table." under it
 * while nothing is found, a short "found" beat when the first surface appears, then nothing.
 * If the camera stops following the room afterwards, "Paused. Move your phone more slowly,
 * in a brighter spot." Pure logic with an explicit clock, so the rules are pinned on the JVM
 * (`SurfaceScanCoachTest`).
 *
 * Rules (the only ones):
 *  - AR ruled out on this device, or no camera frame yet → [ArGuidanceCue.NONE]: the SDK's
 *    card or the camera init scrim is already on screen.
 *  - Searching → [ArGuidanceCue.SCAN].
 *  - Searching stops → [ArGuidanceCue.SURFACE_FOUND] for [ArGuidanceState.FOUND_HOLD_MS].
 *  - Found, then the camera loses the room → [ArGuidanceCue.TRACKING_LIMITED].
 */
@Stable
class SurfaceScanCoach {
    var cue: ArGuidanceCue by mutableStateOf(ArGuidanceCue.NONE)
        private set

    private var searchedBefore = false
    private var foundUntil: Long? = null

    fun update(
        searching: Boolean,
        cameraLost: Boolean,
        cameraReady: Boolean,
        arUnavailable: Boolean,
        nowMillis: Long,
    ) {
        if (searching) {
            searchedBefore = true
            foundUntil = null
        } else if (searchedBefore) {
            searchedBefore = false
            foundUntil = nowMillis + ArGuidanceState.FOUND_HOLD_MS
        }
        val holding = foundUntil?.let { nowMillis < it } == true
        if (!holding) foundUntil = null
        cue = when {
            arUnavailable || !cameraReady -> ArGuidanceCue.NONE
            searching -> ArGuidanceCue.SCAN
            holding -> ArGuidanceCue.SURFACE_FOUND
            cameraLost -> ArGuidanceCue.TRACKING_LIMITED
            else -> ArGuidanceCue.NONE
        }
    }

    /** Milliseconds until the "found" beat ends, `null` when only an input can change [cue]. */
    fun nextTransitionDelayMillis(nowMillis: Long): Long? =
        foundUntil?.let { (it - nowMillis).coerceAtLeast(ArGuidanceState.MIN_DELAY_MS) }
}

/**
 * The coaching cue for a demo's own surface search. Draw it with
 * `ARCoachingOverlay(cue = …)` over the camera, and hide the demo's own status pill while it
 * is anything but [ArGuidanceCue.NONE] — one voice at a time.
 *
 * @param searching nothing usable found yet (no plane, no hit) — the demo's own definition.
 * @param cameraLost the camera stopped following the room (a tracking failure reason is set).
 * @param cameraReady the first camera frame arrived; before it the screen is still black.
 * @param arUnavailable ARCore ruled this device out; the SDK's card explains it.
 */
@Composable
fun rememberSurfaceScanCue(
    searching: Boolean,
    cameraLost: Boolean,
    cameraReady: Boolean = true,
    arUnavailable: Boolean = false,
): ArGuidanceCue {
    val coach = remember { SurfaceScanCoach() }
    LaunchedEffect(coach, searching, cameraLost, cameraReady, arUnavailable) {
        coach.update(searching, cameraLost, cameraReady, arUnavailable, SystemClock.uptimeMillis())
        while (true) {
            val wait = coach.nextTransitionDelayMillis(SystemClock.uptimeMillis()) ?: break
            delay(wait)
            coach.update(searching, cameraLost, cameraReady, arUnavailable, SystemClock.uptimeMillis())
        }
    }
    return coach.cue
}
