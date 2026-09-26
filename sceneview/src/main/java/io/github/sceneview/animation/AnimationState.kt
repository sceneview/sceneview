package io.github.sceneview.animation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.github.sceneview.ExperimentalSceneViewApi
import io.github.sceneview.node.ModelNode

/**
 * Observable animation state for a [ModelNode].
 *
 * Exposes the model's animation state as Compose [State] values so that UI elements can react
 * to animation progress, currently playing animation index, and completion status.
 *
 * Use [rememberModelAnimationState] to create and wire an instance to a [ModelNode].
 *
 * ```kotlin
 * val animState = rememberModelAnimationState(modelNode)
 *
 * Text("Animation: ${animState.currentAnimationName ?: "None"}")
 * Text("Progress: ${(animState.progress * 100).toInt()}%")
 * LinearProgressIndicator(progress = animState.progress)
 * ```
 *
 * @property playingAnimationCount The number of animations currently playing.
 * @property currentAnimationIndex The index of the first currently playing animation, or -1.
 * @property currentAnimationName  The name of the first currently playing animation, or null.
 * @property progress              Normalised playback progress of the first playing animation [0, 1].
 * @property isPlaying             Whether any animation is currently playing.
 */
@ExperimentalSceneViewApi
class ModelAnimationState internal constructor(
    private val modelNode: ModelNode
) {
    private val _playingCount = mutableIntStateOf(0)
    private val _currentIndex = mutableIntStateOf(-1)
    private val _currentName = mutableStateOf<String?>(null)
    private val _progress = mutableFloatStateOf(0f)
    private val _isPlaying = mutableStateOf(false)

    /** Number of animations currently playing. */
    val playingAnimationCount: Int get() = _playingCount.intValue

    /** Index of the first currently playing animation, or -1 if none. */
    val currentAnimationIndex: Int get() = _currentIndex.intValue

    /** Name of the first currently playing animation, or null. */
    val currentAnimationName: String? get() = _currentName.value

    /** Normalised playback progress of the first playing animation [0, 1]. */
    val progress: Float get() = _progress.floatValue

    /** Whether any animation is currently playing. */
    val isPlaying: Boolean get() = _isPlaying.value

    /**
     * Polls the [ModelNode] for its current animation state.
     *
     * Called automatically from a per-frame callback when using [rememberModelAnimationState].
     */
    internal fun update(frameTimeNanos: Long) {
        val animations = modelNode.playingAnimations
        _playingCount.intValue = animations.size
        _isPlaying.value = animations.isNotEmpty()

        if (animations.isNotEmpty()) {
            val (index, anim) = animations.entries.first()
            _currentIndex.intValue = index
            _currentName.value = runCatching {
                modelNode.animator.getAnimationName(index)
            }.getOrNull()

            val duration = modelNode.animator.getAnimationDuration(index)
            if (duration > 0f) {
                val elapsed = (frameTimeNanos - anim.startTime) / 1_000_000_000.0
                val adjusted = (elapsed * kotlin.math.abs(anim.speed)).toFloat()
                _progress.floatValue = if (anim.loop) {
                    (adjusted % duration) / duration
                } else {
                    (adjusted / duration).coerceIn(0f, 1f)
                }
            } else {
                _progress.floatValue = 0f
            }
        } else {
            _currentIndex.intValue = -1
            _currentName.value = null
            _progress.floatValue = 0f
        }
    }
}

/**
 * Creates and remembers an [ModelAnimationState] that tracks the given [modelNode]'s animations.
 *
 * The state is updated every frame via the node's `onFrame` callback and exposes Compose-
 * observable properties (playing count, name, progress) that can drive UI elements.
 *
 * ```kotlin
 * val animState = rememberModelAnimationState(modelNode)
 * Text("Playing: ${animState.currentAnimationName}")
 * ```
 *
 * @param modelNode The [ModelNode] to observe.
 * @return An [ModelAnimationState] whose properties are updated every frame.
 */
@ExperimentalSceneViewApi
@Composable
fun rememberModelAnimationState(modelNode: ModelNode): ModelAnimationState {
    val state = remember(modelNode) { ModelAnimationState(modelNode) }

    // `internalOnFrame`, not the public `onFrame`: this is an *observer*, and observing must not
    // change what is observed. The public slot is read by `Node.isFrameActive` as a standing request
    // for frames, so taking it here meant that merely displaying "Playing: idle" in the UI kept the
    // whole scene at full cadence for as long as the composable was alive (#3718). It also stopped
    // clobbering the caller's own `onFrame`, which the old save/restore dance only half-solved.
    // `ModelNode.isFrameActive` already reports a glTF animation in flight, which is the real reason
    // this state would need frames.
    DisposableEffect(modelNode) {
        val previousOnFrame = modelNode.internalOnFrame
        modelNode.internalOnFrame = { frameTimeNanos ->
            previousOnFrame?.invoke(frameTimeNanos)
            state.update(frameTimeNanos)
        }
        onDispose {
            modelNode.internalOnFrame = previousOnFrame
        }
    }

    return state
}
