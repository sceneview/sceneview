package io.github.sceneview.demo.demos

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Texture
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.ScanAdvice
import io.github.sceneview.demo.demos.internal.ScanCoach
import io.github.sceneview.demo.demos.internal.ScanCoverage
import io.github.sceneview.demo.theme.SceneViewTokens.ArOverlay

/**
 * The scan's coach on the main thread, fed one camera frame at a time from `onSessionUpdated`.
 * It holds what the line over the shutter says ([advice]) and how much of the room is in
 * ([percent]) as Compose state, and stops the paint from growing while the phone moves too fast
 * ([ScanCoverage.paused]) — a blurred frame would paint what the scan does not really hold.
 */
@Stable
internal class ScanGuide {
    private val coach = ScanCoach()
    private var source: ScanCoverage? = null
    private var summaryAt = NEVER
    private var covered = false

    /** The coach's line, or `null` when it has nothing to say. */
    var advice by mutableStateOf<ScanAdvice?>(null)
        private set

    /** "Room N% covered", refreshed every [SUMMARY_INTERVAL_NS]. */
    var percent by mutableIntStateOf(0)
        private set

    /**
     * One frame of [scan] (`null` once it stops). While the camera is not [tracking], the SDK's
     * own card says what to do, and the coach keeps its state for when tracking comes back.
     */
    fun onFrame(
        scan: ScanCapture?,
        tracking: Boolean,
        failure: TrackingFailureReason?,
        nowNanos: Long = System.nanoTime(),
    ) {
        val coverage = scan?.coverage
        if (coverage !== source) {
            source = coverage
            coach.reset()
            summaryAt = NEVER
            covered = false
            percent = 0
            advice = null
        }
        if (scan == null || coverage == null || !tracking) return
        if (summaryAt == NEVER || nowNanos - summaryAt >= SUMMARY_INTERVAL_NS) {
            summaryAt = nowNanos
            val summary = coverage.summary()
            percent = summary.percent
            covered = summary.isEnough
        }
        val look = coverage.lastLook
        advice = coach.update(
            nowNanos,
            ScanCoach.Reading(
                degreesPerSecond = scan.motion.degreesPerSecond,
                metresPerSecond = scan.motion.metresPerSecond,
                distanceM = look?.distanceM ?: Float.NaN,
                luma = scan.luma,
                points = scan.framePoints,
                poorShare = look?.poorShare ?: 0f,
                excessiveMotion = failure == TrackingFailureReason.EXCESSIVE_MOTION,
                insufficientLight = failure == TrackingFailureReason.INSUFFICIENT_LIGHT,
                covered = covered,
            ),
        )
        coverage.paused = coach.isTooFast
    }

    private companion object {
        /** "Room N% covered" moves twice a second: often enough to follow a sweep, never a flicker. */
        const val SUMMARY_INTERVAL_NS = 500_000_000L
        const val NEVER = Long.MIN_VALUE
    }
}

/** The coach's words for [ScanAdvice]: what to do, never why in the scan's own terms. */
@get:StringRes
internal val ScanAdvice.line: Int
    get() = when (this) {
        ScanAdvice.SlowDown -> R.string.demo_ar_rerun_coach_slow_down
        ScanAdvice.MoreLight -> R.string.demo_ar_rerun_coach_more_light
        ScanAdvice.StepBack -> R.string.demo_ar_rerun_coach_step_back
        ScanAdvice.MoveCloser -> R.string.demo_ar_rerun_coach_move_closer
        ScanAdvice.MoreDetail -> R.string.demo_ar_rerun_coach_more_detail
        ScanAdvice.HoldHere -> R.string.demo_ar_rerun_coach_hold_here
        ScanAdvice.Covered -> R.string.demo_ar_rerun_coach_covered
    }

/** The line's leading glyph: a picture of the fix, read before the words. */
internal val ScanAdvice.icon: ImageVector
    get() = when (this) {
        ScanAdvice.SlowDown -> Icons.Rounded.Speed
        ScanAdvice.MoreLight -> Icons.Rounded.LightMode
        ScanAdvice.StepBack -> Icons.Rounded.ZoomOut
        ScanAdvice.MoveCloser -> Icons.Rounded.ZoomIn
        ScanAdvice.MoreDetail -> Icons.Rounded.Texture
        ScanAdvice.HoldHere -> Icons.Rounded.PanTool
        ScanAdvice.Covered -> Icons.Rounded.CheckCircle
    }

/**
 * The glyph's colour: `warning` for something to change ([ArOverlay.accentGuidance]), the
 * paint's own `primary` while holding fills a patch in, `success` once the room is in.
 */
internal val ScanAdvice.tint: Color
    get() = when (this) {
        ScanAdvice.HoldHere -> ArOverlay.accentProgress
        ScanAdvice.Covered -> ArOverlay.accentSuccess
        else -> ArOverlay.accentGuidance
    }
