package io.github.sceneview.animation

/**
 * A step in an animation sequence.
 *
 * @param startValue Start value for this step.
 * @param endValue End value for this step.
 * @param durationSeconds Duration of this step.
 * @param easing Easing function for this step.
 */
data class SequenceStep(
    val startValue: Float,
    val endValue: Float,
    val durationSeconds: Float,
    val easing: (Float) -> Float = Easing.Linear
)

/**
 * A sequence of animations that play one after another.
 *
 * This provides a simple way to chain animations: each step plays to completion
 * before the next begins. The total duration is the sum of all step durations.
 *
 * @param steps The ordered list of animation steps.
 * @param loop Whether to loop the entire sequence.
 */
class AnimationSequence(
    val steps: List<SequenceStep>,
    val loop: Boolean = false
) {
    init {
        require(steps.isNotEmpty()) { "AnimationSequence requires at least 1 step" }
    }

    /** Total duration of the entire sequence. */
    val totalDuration: Float = steps.sumOf { it.durationSeconds.toDouble() }.toFloat()

    /**
     * Evaluate the sequence at the given elapsed time.
     *
     * @param elapsedSeconds Time elapsed since the sequence started.
     * @return The interpolated value at the given time.
     */
    fun evaluate(elapsedSeconds: Float): Float {
        val time = if (loop && totalDuration > 0f) {
            elapsedSeconds % totalDuration
        } else {
            elapsedSeconds.coerceIn(0f, totalDuration)
        }

        var accumulated = 0f
        for (step in steps) {
            if (time <= accumulated + step.durationSeconds) {
                val localTime = time - accumulated
                val fraction = if (step.durationSeconds > 0f) {
                    localTime / step.durationSeconds
                } else {
                    1f
                }
                val easedFraction = step.easing(fraction.coerceIn(0f, 1f))
                return lerp(step.startValue, step.endValue, easedFraction)
            }
            accumulated += step.durationSeconds
        }

        // Past the end — return the last step's end value
        return steps.last().endValue
    }
}

/**
 * State of a running animation sequence.
 *
 * @param sequence The sequence being played.
 * @param elapsedSeconds Time elapsed since start.
 * @param isRunning Whether the sequence is currently advancing.
 */
data class SequenceState(
    val sequence: AnimationSequence,
    val elapsedSeconds: Float = 0f,
    val isRunning: Boolean = true
) {
    /** Current value of the sequence. */
    val value: Float get() = sequence.evaluate(elapsedSeconds)

    /** Index of the currently active step. */
    val currentStepIndex: Int
        get() {
            val time = if (sequence.loop && sequence.totalDuration > 0f) {
                elapsedSeconds % sequence.totalDuration
            } else {
                elapsedSeconds.coerceIn(0f, sequence.totalDuration)
            }
            var accumulated = 0f
            for (index in sequence.steps.indices) {
                val step = sequence.steps[index]
                accumulated += step.durationSeconds
                if (time <= accumulated) return index
            }
            return sequence.steps.size - 1
        }

    /** Whether the sequence has completed (non-looping only). */
    val isFinished: Boolean
        get() = !sequence.loop && elapsedSeconds >= sequence.totalDuration
}

/**
 * Advance a sequence animation by [deltaSeconds].
 *
 * Pure function — no mutation.
 */
fun advanceSequence(state: SequenceState, deltaSeconds: Float): SequenceState {
    if (!state.isRunning || state.isFinished) return state

    val newElapsed = state.elapsedSeconds + deltaSeconds

    return if (!state.sequence.loop && newElapsed >= state.sequence.totalDuration) {
        state.copy(elapsedSeconds = state.sequence.totalDuration, isRunning = false)
    } else {
        state.copy(elapsedSeconds = newElapsed)
    }
}

// --- Convenience builders ---

/**
 * Build an animation sequence using a DSL.
 *
 * Example:
 * ```
 * val seq = animationSequence {
 *     step(0f, 1f, 0.5f, Easing.EaseInOut)
 *     step(1f, 0.5f, 0.3f, Easing.EaseOut)
 *     step(0.5f, 0f, 0.2f, Easing.Linear)
 *     loop = true
 * }
 * ```
 */
fun animationSequence(block: AnimationSequenceBuilder.() -> Unit): AnimationSequence {
    val builder = AnimationSequenceBuilder()
    builder.block()
    return builder.build()
}

class AnimationSequenceBuilder {
    private val steps = mutableListOf<SequenceStep>()
    var loop: Boolean = false

    fun step(
        startValue: Float,
        endValue: Float,
        durationSeconds: Float,
        easing: (Float) -> Float = Easing.Linear
    ) {
        steps.add(SequenceStep(startValue, endValue, durationSeconds, easing))
    }

    /** Add a delay step that holds the current value. */
    fun delay(durationSeconds: Float) {
        val value = steps.lastOrNull()?.endValue ?: 0f
        steps.add(SequenceStep(value, value, durationSeconds))
    }

    internal fun build(): AnimationSequence = AnimationSequence(steps, loop)
}
