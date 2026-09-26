package io.github.sceneview.demo.demos.internal

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlin.math.sqrt

/**
 * What the Rerun demo's in-app 3D view shows, and when (#3950): the [trace] being drawn, the
 * time cursor, and which layers are on.
 *
 * Time has two states. **Live** follows the head of the session — the default, and what the
 * view returns to. **Replay** holds a [cursor] the user scrubs or plays forward; playing past the
 * end of the session goes live again, the way a video player catches up to a live stream.
 */
@Stable
class ArDebugSession(initial: ArDebugTrace = ArDebugTrace()) {

    /** The trace drawn. Replaced wholesale (a QA loop restarting), otherwise appended to. */
    var trace: ArDebugTrace by mutableStateOf(initial)

    /** `true` while the view follows the head of the session. */
    var live: Boolean by mutableStateOf(true)
        private set

    /** `true` while a replay advances by itself. Meaningless while [live]. */
    var playing: Boolean by mutableStateOf(false)
        private set

    /** Replay time in seconds from the start of the session. Meaningless while [live]. */
    var cursor: Float by mutableFloatStateOf(0f)
        private set

    /** Layers drawn. The floor grid ([DebugGroup.Stage]) is not a toggle: it is the ground. */
    var visibleGroups: Set<DebugGroup> by mutableStateOf(DebugGroup.entries.toSet())
        private set

    /** Counts for the chrome, refreshed a few times a second by the view — not every frame. */
    var stats: ArDebugStats by mutableStateOf(ArDebugStats.Empty)

    /** The time the view draws. */
    val time: Float get() = if (live) trace.duration else cursor.coerceAtMost(trace.duration)

    fun isVisible(group: DebugGroup) = group in visibleGroups

    fun toggle(group: DebugGroup) {
        if (group == DebugGroup.Stage) return
        visibleGroups = if (group in visibleGroups) visibleGroups - group else visibleGroups + group
    }

    /** Scrubbing pauses on the frame the finger is on. */
    fun scrubTo(seconds: Float) {
        live = false
        playing = false
        cursor = seconds.coerceIn(0f, trace.duration)
    }

    fun goLive() {
        live = true
        playing = false
    }

    /**
     * The play/pause button. Live → pause on the current instant. Paused → play on, from the
     * start again if the cursor sits at the end. Playing → pause.
     */
    fun togglePlay() {
        when {
            live -> scrubTo(trace.duration)
            playing -> playing = false
            else -> {
                if (cursor >= trace.duration - END_EPSILON_S) cursor = 0f
                playing = true
            }
        }
    }

    /** Advances a playing replay by [deltaSeconds]; reaching the head of the session goes live. */
    fun tick(deltaSeconds: Float) {
        if (live || !playing) return
        val next = cursor + deltaSeconds.coerceIn(0f, 0.25f)
        if (next >= trace.duration) goLive() else cursor = next
    }

    companion object {
        private const val END_EPSILON_S = 0.05f
    }
}

/** The figures the chrome shows: time, path walked, and what ARCore has mapped. */
data class ArDebugStats(
    val time: Float,
    val duration: Float,
    val pathMetres: Float,
    val mapPoints: Int,
    val planes: Int,
    val anchors: Int,
    val tracking: Boolean,
) {
    companion object {
        val Empty = ArDebugStats(0f, 0f, 0f, 0, 0, 0, tracking = false)

        fun of(frame: ArDebugFrame, duration: Float): ArDebugStats = ArDebugStats(
            time = frame.time,
            duration = duration,
            pathMetres = pathLength(frame.trail),
            mapPoints = frame.mapPointCount,
            planes = frame.planes.size,
            anchors = frame.anchors.size,
            tracking = frame.camera != null,
        )

        /** Length of a flat `[x,y,z, …]` polyline, in its own units. */
        fun pathLength(path: FloatArray): Float {
            var length = 0f
            for (i in 1 until path.size / 3) {
                val dx = path[i * 3] - path[i * 3 - 3]
                val dy = path[i * 3 + 1] - path[i * 3 - 2]
                val dz = path[i * 3 + 2] - path[i * 3 - 1]
                length += sqrt(dx * dx + dy * dy + dz * dz)
            }
            return length
        }
    }
}

/** Text of the 3D view's chrome. Plain functions, so the wording is testable. */
object ArDebugFormat {
    /** `72.4` → `1:12`. Negative and non-finite inputs read as zero. */
    fun clock(seconds: Float): String {
        val total = if (seconds.isFinite() && seconds > 0f) seconds.toInt() else 0
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    /** `0.42` → `42 cm`, `14.236` → `14.2 m`. */
    fun distance(metres: Float): String = when {
        !metres.isFinite() || metres <= 0f -> "0 m"
        metres < 1f -> "${(metres * 100).toInt()} cm"
        metres < 100f -> String.format(Locale.US, "%.1f m", metres)
        else -> String.format(Locale.US, "%,d m", metres.toInt())
    }

    /** `3812` → `3,812`. */
    fun count(value: Int): String = String.format(Locale.US, "%,d", value)
}
