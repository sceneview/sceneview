package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * What Portrait focus (`ar-depth-of-field`, #4649) keeps sharp — the rules, apart from
 * Filament and ARCore so they are tested on the JVM.
 *
 * A phone camera's portrait mode keeps the subject sharp as you move and lets you tap
 * elsewhere to focus there. The demo does the same: [Subject] follows the sneaker every frame,
 * [Point] holds the real-world depth of the pixel tapped last.
 */
sealed interface PortraitFocusTarget {
    /** The sneaker: the focus distance follows it as the phone moves. */
    data object Subject : PortraitFocusTarget

    /** A tapped point of the room, at [distanceMetres] from the lens when it was tapped. */
    data class Point(val distanceMetres: Float) : PortraitFocusTarget
}

/** The sentence over the camera, by case; the screen maps each to a string resource. */
enum class PortraitFocusStatus {
    /** The "Original" side of the before/after switch: no blur at all. */
    Original,

    /** No ARCore Depth on this phone: the room cannot blur. */
    Unsupported,

    /** Following the sneaker, but its distance is not known yet (no tracked frame). */
    Waiting,

    /** Focused on the sneaker. */
    Subject,

    /** Focused on a tapped point of the room. */
    Point,
}

object PortraitFocus {
    /** Nearest focus Filament is given, in metres: a sneaker under the lens is still a distance. */
    const val MIN_FOCUS_METRES = 0.1f

    /** Farthest focus the slider offers, in metres. */
    const val MAX_FOCUS_METRES = 5f

    /**
     * Smallest change of the followed distance worth a recomposition, in metres. The camera
     * pose moves every frame by sub-millimetre noise; a centimetre is below what the blur shows.
     */
    const val FOLLOW_STEP_METRES = 0.01f

    /** Distance between the lens at [camera] and the subject at [subject], both world (x, y, z). */
    fun distance(camera: FloatArray, subject: FloatArray): Float {
        val dx = camera[0] - subject[0]
        val dy = camera[1] - subject[1]
        val dz = camera[2] - subject[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** [metres] inside the range Filament and the slider share. */
    fun clampFocus(metres: Float): Float = metres.coerceIn(MIN_FOCUS_METRES, MAX_FOCUS_METRES)

    /**
     * The focus distance to apply while following the subject: [measured] when it moved by at
     * least [FOLLOW_STEP_METRES] from [current], else [current] unchanged (no new state).
     */
    fun follow(current: Float?, measured: Float): Float {
        val next = clampFocus(measured)
        return if (current == null || abs(next - current) >= FOLLOW_STEP_METRES) next else current
    }

    /** Which sentence the status card says. */
    fun status(
        portrait: Boolean,
        depthSupported: Boolean?,
        target: PortraitFocusTarget,
        subjectDistance: Float?,
    ): PortraitFocusStatus = when {
        !portrait -> PortraitFocusStatus.Original
        depthSupported == false -> PortraitFocusStatus.Unsupported
        target is PortraitFocusTarget.Point -> PortraitFocusStatus.Point
        subjectDistance == null -> PortraitFocusStatus.Waiting
        else -> PortraitFocusStatus.Subject
    }

    /** The distance Filament focuses at for [target], or `null` while the subject's is unknown. */
    fun focusDistance(target: PortraitFocusTarget, subjectDistance: Float?): Float? = when (target) {
        is PortraitFocusTarget.Point -> clampFocus(target.distanceMetres)
        PortraitFocusTarget.Subject -> subjectDistance?.let(::clampFocus)
    }
}
