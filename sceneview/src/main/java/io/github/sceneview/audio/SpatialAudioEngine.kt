package io.github.sceneview.audio

import androidx.annotation.MainThread
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.math.Position

/**
 * Process-wide registry that keeps every live [SpatialAudioPlayer] in sync with the current
 * listener pose.
 *
 * Background — Android's `MediaPlayer` is a per-instance audio bus and does **not** share a
 * listener context with other players in the process. To deliver a coherent
 * "camera-tracked positional audio" experience we therefore have to recompute and push the
 * per-source gain (and L/R pan, on the MediaPlayer fallback) every frame for **every**
 * active source — there is no global listener primitive on this path.
 *
 * The engine is a singleton (no Filament `Engine` coupling) so it is safe to share across
 * multiple `SceneView` composables in the same process. It holds nothing but weak
 * conceptual references — players unregister themselves on dispose, and a single
 * registered listener pose is kept (last writer wins).
 *
 * Phase 2 will introduce a true `Spatializer` AudioTrack pipeline that does have a global
 * listener; this engine will then forward listener pose to the spatializer directly
 * instead of recomputing per source.
 */
internal object SpatialAudioEngine {

    private val players = mutableListOf<SpatialAudioPlayer>()
    private var listenerPosition: Position = Position(x = 0f)
    private var listenerForward: Position = Position(z = -1f)
    private var listenerRight: Position = Position(x = 1f)

    @MainThread
    fun register(player: SpatialAudioPlayer) {
        if (!players.contains(player)) players.add(player)
        // Push the current listener so the player has something to attenuate against
        // the moment it starts — otherwise the very first frame is at full volume.
        player.updateFromListener(listenerPosition, listenerForward, listenerRight)
    }

    @MainThread
    fun unregister(player: SpatialAudioPlayer) {
        players.remove(player)
    }

    /**
     * Update the listener pose. Called by [AudioListener] each frame.
     *
     * @param position World-space position of the virtual ears.
     * @param forward  Unit vector pointing where the listener is looking.
     * @param right    Unit vector pointing to the listener's right (used for L/R pan).
     */
    @MainThread
    fun setListenerPose(position: Position, forward: Position, right: Position) {
        listenerPosition = position
        listenerForward = forward
        listenerRight = right
        // Push to every player. Keeps audio in sync with the camera at the cost of one
        // small per-player gain recompute — well under a microsecond per source.
        for (i in players.indices) {
            players[i].updateFromListener(position, forward, right)
        }
    }

    /**
     * Refresh every player using the most recently set listener pose. Used by
     * [SpatialAudioNode] when a source's *own* world position changes between frames
     * but the listener has not — so we don't have to wait for the next [setListenerPose]
     * tick to apply the new gain.
     */
    @MainThread
    fun refreshAll() {
        for (i in players.indices) {
            players[i].updateFromListener(listenerPosition, listenerForward, listenerRight)
        }
    }

    /** Test hook — reset internal state between runs. */
    internal fun resetForTest() {
        players.clear()
        listenerPosition = Position(x = 0f)
        listenerForward = Position(z = -1f)
        listenerRight = Position(x = 1f)
    }
}

/** 3D vector distance helper that tolerates zero-length input. */
internal fun distance3(a: Position, b: Position): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    val dz = a.z - b.z
    return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
}

/**
 * Compute the constant-power L/R pan ([0..1] each) for a source [sourcePosition] given the
 * listener pose. Equal-power pan law keeps total perceived loudness constant as the source
 * sweeps across the stereo field.
 */
internal fun panLR(
    sourcePosition: Position,
    listenerPosition: Position,
    listenerRight: Position
): Pair<Float, Float> {
    // Direction from listener to source.
    val toSource = Float3(
        sourcePosition.x - listenerPosition.x,
        sourcePosition.y - listenerPosition.y,
        sourcePosition.z - listenerPosition.z
    )
    val len = kotlin.math.sqrt(toSource.x * toSource.x + toSource.y * toSource.y + toSource.z * toSource.z)
    if (len < 1e-4f) return 0.5f to 0.5f  // source on top of listener
    val dir = normalize(toSource)
    // Dot of `dir · listenerRight` ∈ [-1, 1]. +1 = full right, -1 = full left.
    val dot = dir.x * listenerRight.x + dir.y * listenerRight.y + dir.z * listenerRight.z
    val pan = dot.coerceIn(-1f, 1f)
    // Equal-power pan law (cos / sin curve around 45° middle).
    val angle = (pan + 1f) * kotlin.math.PI.toFloat() / 4f  // [-1, 1] → [0, π/2]
    val left = kotlin.math.cos(angle)
    val right = kotlin.math.sin(angle)
    return left to right
}
